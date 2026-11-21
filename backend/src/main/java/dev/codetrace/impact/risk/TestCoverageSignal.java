package dev.codetrace.impact.risk;

import dev.codetrace.impact.change.ChangedSymbolMapper.Kind;
import dev.codetrace.impact.model.ImpactAnalysis;
import org.springframework.stereotype.Component;

/** Changed production code that no test depends on, according to the graph, is unprotected. */
@Component
public class TestCoverageSignal implements RiskSignal {
    @Override
    public String id() {
        return "06-test-coverage";
    }

    @Override
    public int maxPoints() {
        return 10;
    }

    @Override
    public RiskFactor evaluate(ImpactAnalysis a) {
        boolean productionChanged = a.changed().stream()
                .anyMatch(c -> c.kind() != Kind.ADDED && !ImpactAnalysis.isTestPath(c.any().path()));
        if (!productionChanged) {
            return new RiskFactor(id(), 0, maxPoints(), "No existing production symbol changed.");
        }
        long tests = a.testsAffected();
        return tests == 0
                ? new RiskFactor(id(), 10, maxPoints(), "No test depends on the changed code (according to the dependency graph).")
                : new RiskFactor(id(), 0, maxPoints(), tests + " test symbol(s) depend on the changed code.");
    }
}
