package dev.codetrace.graph.engine;

import static dev.codetrace.graph.engine.GraphTestSupport.SNAPSHOT;
import static dev.codetrace.graph.engine.GraphTestSupport.build;
import static dev.codetrace.graph.engine.GraphTestSupport.dsts;
import static dev.codetrace.graph.engine.GraphTestSupport.facts;
import static dev.codetrace.graph.engine.GraphTestSupport.from;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.codetrace.graph.model.GraphModel.Edge;
import dev.codetrace.graph.model.GraphModel.EdgeType;
import dev.codetrace.graph.model.GraphModel.Limits;
import dev.codetrace.graph.model.GraphModel.Node;
import dev.codetrace.graph.model.GraphModel.NodeType;
import dev.codetrace.graph.model.GraphModel.Resolution;
import dev.codetrace.graph.model.GraphModel.Result;
import dev.codetrace.jobs.JobFailureException;
import dev.codetrace.jobs.JobFailureException.Category;
import dev.codetrace.support.GitFixtures;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Properties that must hold for every graph the builder produces. */
class GraphBuilderInvariantsTest {

    private static Map<String, String> demoSources() throws IOException {
        Path root = GitFixtures.fixtureRoot("demo-java");
        Map<String, String> out = new HashMap<>();
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.filter(f -> f.toString().endsWith(".java")).toList()) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                if (rel.startsWith("target/") || rel.startsWith("node_modules/")) continue; // ignored by the indexer too
                out.put(rel, Files.readString(p));
            }
        }
        return out;
    }

    // ---- invariants over a realistic graph --------------------------------------------------------------------------

    @Test
    void structuralInvariantsHoldOnTheDemoRepository() throws IOException {
        Result r = build(demoSources());
        Map<UUID, Node> nodes = r.nodes().stream().collect(Collectors.toMap(Node::id, n -> n));

        assertThat(nodes).hasSize(r.nodes().size()); // node ids are unique
        for (Edge e : r.edges()) {
            assertThat(nodes).as("src of %s", e).containsKey(e.srcId());
            if (e.resolution() == Resolution.UNRESOLVED) {
                assertThat(e.dstId()).as("unresolved edge has no target: %s", e).isNull();
                assertThat(e.dstName()).as("unresolved edge says what it wanted: %s", e).isNotBlank();
                assertThat(e.reason()).isNotBlank();
                assertThat(e.confidence()).isZero();
            } else {
                assertThat(nodes).as("dst of %s", e).containsKey(e.dstId());
                assertThat(e.confidence()).isBetween(0.0, 1.0).isPositive();
            }
            assertThat(e.evidenceKind()).isNotBlank();
            assertThat(e.evidencePath()).isNotNull();
            assertThat(e.occurrences()).isPositive();
        }
        // ambiguous alternatives always come in groups of two or more
        Map<String, Long> groups = r.edges().stream().filter(e -> e.resolution() == Resolution.AMBIGUOUS)
                .collect(Collectors.groupingBy(Edge::alternativeGroup, Collectors.counting()));
        assertThat(groups.values()).allMatch(n -> n >= 2);
        assertThat(r.edges().stream().filter(e -> e.resolution() != Resolution.AMBIGUOUS))
                .allMatch(e -> e.alternativeGroup().isEmpty());
    }

    @Test
    void everyDeclarationHasExactlyOneContainer() throws IOException {
        Result r = build(demoSources());
        Map<UUID, Long> parents = r.edges().stream().filter(e -> e.type() == EdgeType.CONTAINS)
                .collect(Collectors.groupingBy(Edge::dstId, Collectors.counting()));

        for (Node n : r.nodes()) {
            if (n.type() == NodeType.TYPE || n.type() == NodeType.METHOD || n.type() == NodeType.FIELD) {
                assertThat(parents.get(n.id())).as("parents of %s", n.qualifiedName()).isEqualTo(1L);
            }
        }
    }

    @Test
    void demoRepositoryResolvesWhatTheEvidenceSupportsAndNothingMore() throws IOException {
        Result r = build(demoSources());

        // Order implements Priced (same package)
        assertThat(dsts(r, "com.acme.shop.Order", EdgeType.IMPLEMENTS)).containsExactly("com.acme.shop.Priced");
        // addLine(sku, 1) -> exactly one 2-argument overload
        assertThat(dsts(r, "com.acme.shop.Order#addLine(String)", EdgeType.CALLS))
                .contains("com.acme.shop.Order#addLine(String,int)");
        // addLine(sku) has two 1-argument overloads (String / Line): argument types are not checked, so AMBIGUOUS
        List<Edge> amb = from(r, "com.acme.shop.Order#addAll(String...)", EdgeType.CALLS).stream()
                .filter(e -> e.resolution() == Resolution.AMBIGUOUS).toList();
        assertThat(amb).hasSize(2);
        // a parameter-typed receiver resolves across files
        assertThat(dsts(r, "com.acme.shop.OrderService#checkout(Order)", EdgeType.CALLS))
                .contains("com.acme.shop.Order#priceCents()");
        // Map/HashMap are not in the repository: the call stays unresolved and says so
        assertThat(from(r, "com.acme.shop.OrderService#checkout(Order)", EdgeType.CALLS)).anySatisfy(e -> {
            assertThat(e.resolution()).isEqualTo(Resolution.UNRESOLVED);
            assertThat(e.reason()).isEqualTo("RECEIVER_TYPE_NOT_IN_REPOSITORY");
        });
        // ignored paths never enter the graph
        assertThat(r.nodes()).noneMatch(n -> n.path().startsWith("target/") || n.qualifiedName().contains("Vendored"));
    }

    // ---- determinism --------------------------------------------------------------------------------------------------

    @Test
    void sameInputAndSnapshotAlwaysProduceTheSameGraph() throws IOException {
        Result a = build(demoSources());
        Result b = build(demoSources());

        assertThat(b.nodes()).isEqualTo(a.nodes());
        assertThat(b.edges()).isEqualTo(a.edges());
        assertThat(b.stats()).isEqualTo(a.stats());
    }

    @Test
    void nodeIdsAreScopedToTheirSnapshot() throws IOException {
        var input = facts(demoSources());
        Result a = new GraphBuilder().build(SNAPSHOT, input, new Limits(10_000, 100_000));
        Result b = new GraphBuilder().build(UUID.fromString("00000000-0000-0000-0000-000000000002"), input,
                new Limits(10_000, 100_000));

        Set<UUID> idsA = a.nodes().stream().map(Node::id).collect(Collectors.toSet());
        assertThat(b.nodes()).extracting(Node::id).noneMatch(idsA::contains);
        assertThat(b.nodes()).hasSameSizeAs(a.nodes());
    }

    // ---- aggregation and limits -------------------------------------------------------------------------------------------

    @Test
    void repeatedCallSitesMergeIntoOneEdgeWithTheFirstLineAsEvidence() {
        Result r = build(Map.of("src/p/A.java", "package p;\nclass A {\n void go() {\n  h();\n  h();\n  h();\n }\n void h() {}\n}"));

        Edge e = from(r, "p.A#go()", EdgeType.CALLS).get(0);
        assertThat(e.occurrences()).isEqualTo(3);
        assertThat(e.evidenceLine()).isEqualTo(4);
    }

    @Test
    void anOversizedGraphIsRejectedNotTruncated() throws IOException {
        var input = facts(demoSources());

        assertThatThrownBy(() -> new GraphBuilder().build(SNAPSHOT, input, new Limits(5, 100_000)))
                .isInstanceOfSatisfying(JobFailureException.class, e -> assertThat(e.category()).isEqualTo(Category.LIMIT_EXCEEDED))
                .hasMessageContaining("nodes");
        assertThatThrownBy(() -> new GraphBuilder().build(SNAPSHOT, input, new Limits(10_000, 5)))
                .isInstanceOfSatisfying(JobFailureException.class, e -> assertThat(e.category()).isEqualTo(Category.LIMIT_EXCEEDED))
                .hasMessageContaining("edges");
    }

    @Test
    void emptyInputBuildsAnEmptyGraph() {
        Result r = new GraphBuilder().build(SNAPSHOT, facts(Map.of()), new Limits(10, 10));

        assertThat(r.nodes()).isEmpty();
        assertThat(r.edges()).isEmpty();
    }

    @Test
    void filesThatFailedToParseContributeNothing() {
        Result r = build(Map.of("src/p/Bad.java", "this is not java at all {{{", "src/p/Ok.java", "package p; class Ok {}"));

        assertThat(r.nodes()).noneMatch(n -> n.path().equals("src/p/Bad.java") && n.type() != NodeType.FILE);
        assertThat(r.nodes()).anyMatch(n -> n.qualifiedName().equals("p.Ok"));
    }
}
