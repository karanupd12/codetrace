package dev.codetrace.impact.risk;

import dev.codetrace.change.ChangedSymbolMapper.Kind;
import dev.codetrace.impact.ImpactAnalysis;
import dev.codetrace.impact.ImpactAnalysis.ChangedEntry;
import dev.codetrace.impact.RiskSignal;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Removing a symbol (which includes changing its signature) or narrowing its visibility breaks every dependent that
 * still uses the old form. Public and protected symbols are weighted highest because code outside the package can use
 * them.
 */
@Component
public class BreakingApiSignal implements RiskSignal {
    @Override
    public String id() {
        return "03-breaking-api";
    }

    @Override
    public int maxPoints() {
        return 20;
    }

    @Override
    public RiskFactor evaluate(ImpactAnalysis a) {
        int points = 0;
        String reason = "No removed or less-visible symbol has dependents.";
        for (ChangedEntry c : a.changed()) {
            if (c.directDependents() == 0 || c.base() == null) continue;
            if (c.kind() == Kind.REMOVED) {
                boolean exposed = isExposed(c.base().modifiers());
                int p = exposed ? 20 : 10;
                if (p > points) {
                    points = p;
                    reason = (exposed ? "Public/protected " : "") + "symbol " + c.base().qualifiedName()
                            + " was removed or its signature changed; " + c.directDependents() + " direct dependent(s).";
                }
            } else if (c.kind() == Kind.MODIFIED && c.head() != null && isExposed(c.base().modifiers())
                    && !isExposed(c.head().modifiers()) && points < 15) {
                points = 15;
                reason = "Visibility of " + c.base().qualifiedName() + " was reduced; " + c.directDependents()
                        + " direct dependent(s).";
            }
        }
        return new RiskFactor(id(), points, maxPoints(), reason);
    }

    private static boolean isExposed(String modifiers) {
        List<String> m = Arrays.asList(modifiers.split("[\\s,]+"));
        return m.contains("public") || m.contains("protected");
    }
}
