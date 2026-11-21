package dev.codetrace.impact.risk;

import dev.codetrace.impact.ImpactAnalysis;
import dev.codetrace.impact.RiskSignal;
import org.springframework.stereotype.Component;

/** The analysis cannot prove every dependency; unproven ones mean the real impact may be larger than reported. */
@Component
public class UncertaintySignal implements RiskSignal {
    @Override
    public String id() {
        return "05-uncertainty";
    }

    @Override
    public int maxPoints() {
        return 10;
    }

    @Override
    public RiskFactor evaluate(ImpactAnalysis a) {
        long uncertain = a.affected().stream().filter(x -> !x.certain()).count();
        int points = (uncertain > 0 ? 5 : 0) + (a.possible().isEmpty() ? 0 : 5);
        return new RiskFactor(id(), points, maxPoints(), uncertain + " affected symbol(s) rest on ambiguous links; "
                + a.possible().size() + " call(s) with an unknown receiver type might also depend on a changed method.");
    }
}
