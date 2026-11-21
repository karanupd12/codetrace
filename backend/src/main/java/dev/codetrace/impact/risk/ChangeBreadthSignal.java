package dev.codetrace.impact.risk;

import dev.codetrace.common.util.SourcePaths;
import dev.codetrace.impact.model.ImpactAnalysis;
import org.springframework.stereotype.Component;

/** Many changed production symbols are harder to review and more likely to hide a problem. */
@Component
public class ChangeBreadthSignal implements RiskSignal {
    @Override
    public String id() {
        return "01-change-breadth";
    }

    @Override
    public int maxPoints() {
        return 15;
    }

    @Override
    public RiskFactor evaluate(ImpactAnalysis a) {
        long n = a.changed().stream().filter(c -> !SourcePaths.isTestPath(c.any().path())).count();
        int points = n <= 2 ? 0 : n <= 10 ? 5 : n <= 30 ? 10 : 15;
        return new RiskFactor(id(), points, maxPoints(), n + " production symbol(s) changed.");
    }
}
