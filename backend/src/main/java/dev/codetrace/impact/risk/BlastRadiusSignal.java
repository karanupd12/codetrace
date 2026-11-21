package dev.codetrace.impact.risk;

import dev.codetrace.impact.ImpactAnalysis;
import dev.codetrace.impact.RiskSignal;
import org.springframework.stereotype.Component;

/** The more production symbols depend on the change, the more can break. */
@Component
public class BlastRadiusSignal implements RiskSignal {
    @Override
    public String id() {
        return "02-blast-radius";
    }

    @Override
    public int maxPoints() {
        return 35;
    }

    @Override
    public RiskFactor evaluate(ImpactAnalysis a) {
        long n = a.productionAffected();
        int points = n == 0 ? 0 : n <= 5 ? 5 : n <= 20 ? 15 : n <= 100 ? 25 : 35;
        return new RiskFactor(id(), points, maxPoints(), n + " production symbol(s) depend on the change.");
    }
}
