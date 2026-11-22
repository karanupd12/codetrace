package dev.codetrace.impact.risk;

import dev.codetrace.common.util.SourcePaths;
import dev.codetrace.impact.model.ImpactAnalysis;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.codetrace.impact.change.ChangedSymbolMapper.Kind;
import dev.codetrace.impact.change.SymbolRef;
import dev.codetrace.impact.model.ImpactAnalysis.AffectedSymbol;
import dev.codetrace.impact.model.ImpactAnalysis.ChangedEntry;
import dev.codetrace.impact.model.ImpactAnalysis.EndpointImpact;
import dev.codetrace.impact.model.ImpactAnalysis.PossibleDependent;
import dev.codetrace.impact.risk.RiskAssessor.Level;
import dev.codetrace.impact.risk.RiskAssessor.RiskAssessment;
import dev.codetrace.impact.risk.RiskSignal.RiskFactor;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class RiskAssessorTest {
    private static final String MAIN = "core/src/main/java/p/A.java";

    private static SymbolRef ref(String qn, String sig, String path, String modifiers) {
        return new SymbolRef(UUID.randomUUID(), "METHOD", qn, sig, path, 1, 5, modifiers);
    }

    private static ChangedEntry modified(String qn, String path, int dependents) {
        SymbolRef r = ref(qn, "()", path, "public");
        return new ChangedEntry(Kind.MODIFIED, r, r, dependents);
    }

    private static ChangedEntry removed(String qn, String modifiers, int dependents) {
        return new ChangedEntry(Kind.REMOVED, ref(qn, "()", MAIN, modifiers), null, dependents);
    }

    private static AffectedSymbol affected(String path, boolean certain) {
        return new AffectedSymbol(UUID.randomUUID(), "METHOD", "p.X#m", "()", path, 1, SourcePaths.displayModule(path),
                SourcePaths.isTestPath(path), 1, certain, certain ? 1.0 : 0.5, "p.A#a", List.of());
    }

    private static ImpactAnalysis analysis(List<ChangedEntry> changed, List<AffectedSymbol> affected, int possible, boolean truncated) {
        List<PossibleDependent> p = IntStream.range(0, possible)
                .mapToObj(i -> new PossibleDependent(UUID.randomUUID(), "p.Y#c" + i, MAIN, 1, "p.A#a")).toList();
        return new ImpactAnalysis("impact-1", 4, changed, affected, p, List.of(), 0, 0, truncated);
    }

    private static List<AffectedSymbol> many(int n, String path) {
        return IntStream.range(0, n).mapToObj(i -> affected(path, true)).toList();
    }

    private static int points(RiskSignal s, ImpactAnalysis a) {
        return s.evaluate(a).points();
    }

    @Test
    void changeBreadthGrowsWithTheNumberOfChangedProductionSymbols() {
        ChangeBreadthSignal s = new ChangeBreadthSignal();
        java.util.function.IntFunction<ImpactAnalysis> n = k -> analysis(
                IntStream.range(0, k).mapToObj(i -> modified("p.A#m" + i, MAIN, 0)).toList(), List.of(), 0, false);

        assertThat(points(s, n.apply(2))).isZero();
        assertThat(points(s, n.apply(3))).isEqualTo(5);
        assertThat(points(s, n.apply(11))).isEqualTo(10);
        assertThat(points(s, n.apply(31))).isEqualTo(15);
        // test code changes do not count
        assertThat(points(s, analysis(List.of(modified("t.T#t", "core/src/test/java/t/T.java", 0),
                modified("t.T#u", "core/src/test/java/t/T.java", 0), modified("t.T#v", "core/src/test/java/t/T.java", 0)),
                List.of(), 0, false))).isZero();
    }

    @Test
    void blastRadiusCountsOnlyProductionSymbols() {
        BlastRadiusSignal s = new BlastRadiusSignal();

        assertThat(points(s, analysis(List.of(), many(0, MAIN), 0, false))).isZero();
        assertThat(points(s, analysis(List.of(), many(5, MAIN), 0, false))).isEqualTo(5);
        assertThat(points(s, analysis(List.of(), many(6, MAIN), 0, false))).isEqualTo(15);
        assertThat(points(s, analysis(List.of(), many(21, MAIN), 0, false))).isEqualTo(25);
        assertThat(points(s, analysis(List.of(), many(101, MAIN), 0, false))).isEqualTo(35);
        assertThat(points(s, analysis(List.of(), many(500, "core/src/test/java/p/T.java"), 0, false))).isZero();
    }

    @Test
    void removingAnExposedSymbolWithDependentsIsTheWorstApiBreak() {
        BreakingApiSignal s = new BreakingApiSignal();

        assertThat(points(s, analysis(List.of(removed("p.A#a", "public", 3)), List.of(), 0, false))).isEqualTo(20);
        assertThat(points(s, analysis(List.of(removed("p.A#a", "protected static", 1)), List.of(), 0, false))).isEqualTo(20);
        assertThat(points(s, analysis(List.of(removed("p.A#a", "private", 2)), List.of(), 0, false))).isEqualTo(10);
        assertThat(points(s, analysis(List.of(removed("p.A#a", "public", 0)), List.of(), 0, false))).isZero(); // nobody uses it
        assertThat(s.evaluate(analysis(List.of(removed("p.A#a", "public", 3)), List.of(), 0, false)).reason())
                .contains("p.A#a").contains("3 direct dependent");
    }

    @Test
    void aRemovedHttpEndpointIsAFullApiBreak() {
        EndpointImpact gone = new EndpointImpact("REMOVED", "DELETE /orders/{id}", "p.C#delete(long)", MAIN, 3,
                "SPRING_MVC", "", 0, true, 1.0, List.of());
        EndpointImpact added = new EndpointImpact("ADDED", "PUT /orders/{id}", "p.C#put(long)", MAIN, 4, "SPRING_MVC", "",
                0, true, 1.0, List.of());
        BreakingApiSignal s = new BreakingApiSignal();

        RiskFactor f = s.evaluate(new ImpactAnalysis("impact-4", 4, List.of(), List.of(), List.of(), List.of(gone, added),
                0, 0, false));

        assertThat(f.points()).isEqualTo(20);
        assertThat(f.reason()).contains("1 HTTP endpoint(s) removed").contains("DELETE /orders/{id}");
        assertThat(points(s, new ImpactAnalysis("impact-4", 4, List.of(), List.of(), List.of(), List.of(added), 0, 0,
                false))).isZero();
    }

    @Test
    void narrowingVisibilityOfAUsedSymbolIsPenalised() {
        BreakingApiSignal s = new BreakingApiSignal();
        SymbolRef before = ref("p.A#a", "()", MAIN, "public");
        SymbolRef after = ref("p.A#a", "()", MAIN, "private");

        assertThat(points(s, analysis(List.of(new ChangedEntry(Kind.MODIFIED, before, after, 2)), List.of(), 0, false))).isEqualTo(15);
        assertThat(points(s, analysis(List.of(new ChangedEntry(Kind.MODIFIED, before, after, 0)), List.of(), 0, false))).isZero();
        assertThat(points(s, analysis(List.of(new ChangedEntry(Kind.MODIFIED, before, before, 2)), List.of(), 0, false))).isZero();
    }

    @Test
    void impactAcrossModulesScoresFivePerExtraModuleUpToTheCap() {
        ModuleSpreadSignal s = new ModuleSpreadSignal();
        List<ChangedEntry> changed = List.of(modified("p.A#a", MAIN, 1));

        assertThat(points(s, analysis(changed, List.of(affected(MAIN, true)), 0, false))).isZero();
        assertThat(points(s, analysis(changed, List.of(affected("web/src/main/java/W.java", true)), 0, false))).isEqualTo(5);
        assertThat(points(s, analysis(changed, List.of(affected("web/src/main/java/W.java", true),
                affected("cli/src/main/java/C.java", true), affected("api/src/main/java/I.java", true),
                affected("x/src/main/java/X.java", true)), 0, false))).isEqualTo(15); // 5 modules, capped
        assertThat(points(s, analysis(changed, List.of(affected("web/src/test/java/WTest.java", true)), 0, false))).isZero();
    }

    @Test
    void ambiguousLinksAndUnknownReceiversEachAddUncertainty() {
        UncertaintySignal s = new UncertaintySignal();

        assertThat(points(s, analysis(List.of(), List.of(affected(MAIN, true)), 0, false))).isZero();
        assertThat(points(s, analysis(List.of(), List.of(affected(MAIN, false)), 0, false))).isEqualTo(5);
        assertThat(points(s, analysis(List.of(), List.of(affected(MAIN, true)), 2, false))).isEqualTo(5);
        assertThat(points(s, analysis(List.of(), List.of(affected(MAIN, false)), 2, false))).isEqualTo(10);
    }

    @Test
    void changedProductionCodeWithoutTestsAttractsPoints() {
        TestCoverageSignal s = new TestCoverageSignal();
        List<ChangedEntry> changed = List.of(modified("p.A#a", MAIN, 1));

        assertThat(points(s, analysis(changed, List.of(affected(MAIN, true)), 0, false))).isEqualTo(10);
        assertThat(points(s, analysis(changed, List.of(affected("core/src/test/java/p/ATest.java", true)), 0, false))).isZero();
        // only additions, or only test changes: nothing existing is at risk
        SymbolRef added = ref("p.A#new", "()", MAIN, "public");
        assertThat(points(s, analysis(List.of(new ChangedEntry(Kind.ADDED, null, added, 0)), List.of(), 0, false))).isZero();
        assertThat(points(s, analysis(List.of(modified("t.T#t", "core/src/test/java/t/T.java", 0)), List.of(), 0, false))).isZero();
    }

    @Test
    void truncatedAnalysisIsFlagged() {
        AnalysisBoundSignal s = new AnalysisBoundSignal();

        assertThat(points(s, analysis(List.of(), List.of(), 0, false))).isZero();
        assertThat(points(s, analysis(List.of(), List.of(), 0, true))).isEqualTo(5);
    }

    private RiskAssessor realAssessor() {
        return new RiskAssessor(List.of(new TestCoverageSignal(), new BlastRadiusSignal(), new AnalysisBoundSignal(),
                new ChangeBreadthSignal(), new BreakingApiSignal(), new UncertaintySignal(), new ModuleSpreadSignal()));
    }

    @Test
    void anUntouchedRepositoryScoresZeroAndLow() {
        RiskAssessment r = realAssessor().assess(analysis(List.of(), List.of(), 0, false));

        assertThat(r.score()).isZero();
        assertThat(r.level()).isEqualTo(Level.LOW);
    }

    @Test
    void factorsAreReportedInSignalIdOrderRegardlessOfRegistrationOrder() {
        RiskAssessment r = realAssessor().assess(analysis(List.of(), List.of(), 0, false));

        assertThat(r.factors()).extracting(RiskFactor::signal).containsExactly("01-change-breadth", "02-blast-radius",
                "03-breaking-api", "04-module-spread", "05-uncertainty", "06-test-coverage", "07-analysis-bound");
    }

    @Test
    void aWorstCaseChangeIsCappedAtOneHundredAndCritical() {
        List<ChangedEntry> changed = new ArrayList<>(IntStream.range(0, 40).mapToObj(i -> modified("p.A#m" + i, MAIN, 5)).toList());
        changed.add(removed("p.A#gone", "public", 9));
        List<AffectedSymbol> aff = new ArrayList<>(many(150, MAIN));
        aff.addAll(List.of(affected("web/src/main/java/W.java", false), affected("cli/src/main/java/C.java", true),
                affected("api/src/main/java/I.java", true), affected("x/src/main/java/X.java", true)));

        RiskAssessment r = realAssessor().assess(analysis(changed, aff, 3, true));

        assertThat(r.factors().stream().mapToInt(RiskFactor::points).sum()).isGreaterThan(100); // 15+35+20+15+10+10+5
        assertThat(r.score()).isEqualTo(100);
        assertThat(r.level()).isEqualTo(Level.CRITICAL);
    }

    @Test
    void levelThresholds() {
        assertThat(RiskAssessor.level(0)).isEqualTo(Level.LOW);
        assertThat(RiskAssessor.level(24)).isEqualTo(Level.LOW);
        assertThat(RiskAssessor.level(25)).isEqualTo(Level.MEDIUM);
        assertThat(RiskAssessor.level(49)).isEqualTo(Level.MEDIUM);
        assertThat(RiskAssessor.level(50)).isEqualTo(Level.HIGH);
        assertThat(RiskAssessor.level(74)).isEqualTo(Level.HIGH);
        assertThat(RiskAssessor.level(75)).isEqualTo(Level.CRITICAL);
    }

    @Test
    void aSignalCannotExceedItsOwnCapOrGoNegative() {
        RiskSignal greedy = new RiskSignal() {
            public String id() { return "99-greedy"; }
            public int maxPoints() { return 10; }
            public RiskFactor evaluate(ImpactAnalysis a) { return new RiskFactor(id(), 500, 500, "too much"); }
        };
        RiskSignal negative = new RiskSignal() {
            public String id() { return "98-negative"; }
            public int maxPoints() { return 10; }
            public RiskFactor evaluate(ImpactAnalysis a) { return new RiskFactor(id(), -50, 10, "less than nothing"); }
        };

        RiskAssessment r = new RiskAssessor(List.of(greedy, negative)).assess(analysis(List.of(), List.of(), 0, false));

        assertThat(r.factors()).extracting(RiskFactor::points).containsExactly(0, 10); // ordered 98-, 99-
        assertThat(r.score()).isEqualTo(10);
    }

    @Test
    void twoSignalsWithTheSameIdAreRejectedAtStartup() {
        assertThatThrownBy(() -> new RiskAssessor(List.of(new BlastRadiusSignal(), new BlastRadiusSignal())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("02-blast-radius");
    }

    @Test
    void identicalInputGivesIdenticalScores() {
        ImpactAnalysis a = analysis(List.of(modified("p.A#a", MAIN, 2)), many(7, MAIN), 1, false);

        assertThat(realAssessor().assess(a)).isEqualTo(realAssessor().assess(a));
    }
}
