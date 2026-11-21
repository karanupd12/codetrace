package dev.codetrace.impact.risk;

import dev.codetrace.impact.ImpactAnalysis;
import dev.codetrace.impact.RiskSignal;
import org.springframework.stereotype.Component;

/** A traversal that hit its limits under-reports the impact. */
@Component
public class AnalysisBoundSignal implements RiskSignal {
    @Override
    public String id() {
        return "07-analysis-bound";
    }

    @Override
    public int maxPoints() {
        return 5;
    }

    @Override
    public RiskFactor evaluate(ImpactAnalysis a) {
        return a.truncated()
                ? new RiskFactor(id(), 5, maxPoints(), "The traversal reached a configured limit, so the impact is a lower bound.")
                : new RiskFactor(id(), 0, maxPoints(), "The traversal completed within its limits.");
    }
}
