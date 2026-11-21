package dev.codetrace.impact.risk;

import dev.codetrace.common.util.SourcePaths;
import dev.codetrace.impact.model.ImpactAnalysis;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

/** Impact that crosses module boundaries is harder to see and to test. */
@Component
public class ModuleSpreadSignal implements RiskSignal {
    @Override
    public String id() {
        return "04-module-spread";
    }

    @Override
    public int maxPoints() {
        return 15;
    }

    @Override
    public RiskFactor evaluate(ImpactAnalysis a) {
        Set<String> modules = new TreeSet<>();
        a.changed().stream().filter(c -> !SourcePaths.isTestPath(c.any().path()))
                .forEach(c -> modules.add(SourcePaths.displayModule(c.any().path())));
        a.affected().stream().filter(x -> !x.test()).forEach(x -> modules.add(x.module()));
        int points = Math.min(maxPoints(), 5 * Math.max(0, modules.size() - 1));
        return new RiskFactor(id(), points, maxPoints(),
                "Production impact spans " + modules.size() + " module(s): " + String.join(", ", modules) + ".");
    }
}
