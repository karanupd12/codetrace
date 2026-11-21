package dev.codetrace.impact.engine;

import static org.assertj.core.api.Assertions.assertThat;

import dev.codetrace.graph.model.EdgeView;
import dev.codetrace.impact.engine.BlastRadiusCalculator.Affected;
import dev.codetrace.impact.engine.BlastRadiusCalculator.Result;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class BlastRadiusCalculatorTest {
    private final BlastRadiusCalculator calc = new BlastRadiusCalculator();
    private final Map<String, UUID> ids = new LinkedHashMap<>();
    private final List<EdgeView> edges = new ArrayList<>();
    private long nextEdge = 1;

    private UUID id(String name) {
        return ids.computeIfAbsent(name, k -> UUID.nameUUIDFromBytes(k.getBytes()));
    }

    private String name(UUID id) {
        return ids.entrySet().stream().filter(e -> e.getValue().equals(id)).map(Map.Entry::getKey).findFirst().orElse("?");
    }

    /** {@code from} depends on {@code to}. */
    private void edge(String from, String to, String type, String resolution, double confidence) {
        edges.add(new EdgeView(nextEdge++, id(from), from, id(to), to, type, resolution, confidence, "CALL", "", "",
                "src/" + from + ".java", 10, 1, "test", "test"));
    }

    private void calls(String from, String to) {
        edge(from, to, "CALLS", "RESOLVED", 1.0);
    }

    private Function<Collection<UUID>, List<EdgeView>> lookup() {
        return targets -> {
            List<EdgeView> out = new ArrayList<>(edges.stream()
                    .filter(e -> e.dstId() != null && targets.contains(e.dstId())).toList());
            out.sort(Comparator.comparingLong(EdgeView::id));
            return out;
        };
    }

    private Result run(Set<String> seeds, int depth, int max) {
        Set<UUID> seedIds = new java.util.LinkedHashSet<>();
        seeds.forEach(s -> seedIds.add(id(s)));
        return calc.calculate(seedIds, depth, max, lookup(), 1_000_000);
    }

    private Map<String, Affected> byName(Result r) {
        Map<String, Affected> m = new HashMap<>();
        r.affected().forEach(a -> m.put(name(a.nodeId()), a));
        return m;
    }

    @Test
    void findsDirectAndTransitiveDependentsWithEvidencePaths() {
        calls("caller", "changed");
        calls("outer", "caller");
        calls("unrelated", "elsewhere");

        Map<String, Affected> r = byName(run(Set.of("changed"), 4, 100));

        assertThat(r).containsOnlyKeys("caller", "outer");
        assertThat(r.get("caller").distance()).isEqualTo(1);
        assertThat(r.get("outer").distance()).isEqualTo(2);
        assertThat(r.get("outer").path()).extracting(EdgeView::srcName, EdgeView::dstName)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("outer", "caller"), org.assertj.core.groups.Tuple.tuple("caller", "changed"));
        assertThat(name(r.get("outer").seedId())).isEqualTo("changed");
        assertThat(r.get("outer").certain()).isTrue();
        assertThat(r.get("outer").confidence()).isEqualTo(1.0);
    }

    @Test
    void cyclesTerminateAndEveryNodeAppearsOnce() {
        calls("a", "changed");
        calls("b", "a");
        calls("a", "b"); // a <-> b
        calls("changed", "b"); // and the changed node depends back on the cycle

        Result r = run(Set.of("changed"), 8, 100);

        assertThat(r.affected()).extracting(a -> name(a.nodeId())).containsExactlyInAnyOrder("a", "b");
        assertThat(r.truncated()).isFalse();
    }

    @Test
    void changedSymbolsAreNeverReportedAsAffected() {
        calls("x", "y");
        calls("y", "x");

        Result r = run(Set.of("x", "y"), 4, 100);

        assertThat(r.affected()).isEmpty();
    }

    @Test
    void depthLimitStopsTheSearchWithoutTruncationFlag() {
        for (int i = 1; i <= 5; i++) calls("n" + i, i == 1 ? "changed" : "n" + (i - 1));

        Result r = run(Set.of("changed"), 2, 100);

        assertThat(r.affected()).extracting(a -> name(a.nodeId())).containsExactly("n1", "n2");
        assertThat(r.truncated()).isFalse();
    }

    @Test
    void resultCapTruncatesDeterministically() {
        for (int i = 1; i <= 10; i++) calls(String.format("c%02d", i), "changed");

        Result r1 = run(Set.of("changed"), 4, 3);
        Result r2 = run(Set.of("changed"), 4, 3);

        assertThat(r1.truncated()).isTrue();
        assertThat(r1.affected()).extracting(a -> name(a.nodeId())).containsExactly("c01", "c02", "c03");
        assertThat(r2).isEqualTo(r1);
    }

    @Test
    void edgeCapFromTheLookupMarksTheResultTruncated() {
        for (int i = 1; i <= 10; i++) calls("c" + i, "changed");
        Function<Collection<UUID>, List<EdgeView>> capped = t -> lookup().apply(t).subList(0, 4);

        Result r = calc.calculate(Set.of(id("changed")), 4, 100, capped, 4);

        assertThat(r.truncated()).isTrue();
        assertThat(r.affected()).hasSize(4);
    }

    @Test
    void ambiguousHopsAreFollowedButMarkTheChainUncertain() {
        edge("caller", "changed", "CALLS", "AMBIGUOUS", 0.45);
        calls("outer", "caller");

        Map<String, Affected> r = byName(run(Set.of("changed"), 4, 100));

        assertThat(r.get("caller").certain()).isFalse();
        assertThat(r.get("caller").confidence()).isEqualTo(0.45);
        assertThat(r.get("outer").certain()).isFalse(); // uncertainty propagates down the chain
        assertThat(r.get("outer").confidence()).isEqualTo(0.45);
    }

    @Test
    void aCertainChainWinsOverAnUncertainOneOfTheSameLength() {
        edge("both", "changed", "CALLS", "AMBIGUOUS", 0.45);
        edge("both", "changed", "REFERENCES", "RESOLVED", 1.0);

        Affected a = byName(run(Set.of("changed"), 4, 100)).get("both");

        assertThat(a.certain()).isTrue();
        assertThat(a.confidence()).isEqualTo(1.0);
        assertThat(a.path()).hasSize(1);
    }

    @Test
    void aShorterChainWinsOverALongerCertainOne() {
        edge("x", "changed", "CALLS", "AMBIGUOUS", 0.5); // distance 1, uncertain
        calls("m", "changed");
        calls("x", "m"); // distance 2, certain

        Affected x = byName(run(Set.of("changed"), 4, 100)).get("x");

        assertThat(x.distance()).isEqualTo(1);
        assertThat(x.certain()).isFalse();
    }

    @Test
    void eachAffectedSymbolPointsAtItsNearestSeed() {
        calls("near1", "s1");
        calls("near2", "s2");
        calls("viaS2", "near2");

        Map<String, Affected> r = byName(run(Set.of("s1", "s2"), 4, 100));

        assertThat(name(r.get("near1").seedId())).isEqualTo("s1");
        assertThat(name(r.get("viaS2").seedId())).isEqualTo("s2");
    }

    @Test
    void edgesWithoutATargetAreIgnored() {
        edges.add(new EdgeView(nextEdge++, id("ghostCaller"), "ghostCaller", null, "?#foo", "CALLS", "UNRESOLVED", 0.0,
                "CALL", "RECEIVER_TYPE_UNKNOWN", "", "a.java", 1, 1, "test", "test"));

        assertThat(run(Set.of("changed"), 4, 100).affected()).isEmpty();
    }

    @Test
    void noSeedsMeansNoImpact() {
        calls("a", "b");

        Result r = calc.calculate(Set.of(), 4, 100, lookup(), 1000);

        assertThat(r.affected()).isEmpty();
        assertThat(r.truncated()).isFalse();
    }

    @Test
    void resultIsIdenticalOnRepeatedRuns() {
        for (int i = 1; i <= 6; i++) {
            calls("a" + i, "changed");
            calls("b" + i, "a" + i);
        }

        assertThat(run(Set.of("changed"), 4, 100)).isEqualTo(run(Set.of("changed"), 4, 100));
    }
}
