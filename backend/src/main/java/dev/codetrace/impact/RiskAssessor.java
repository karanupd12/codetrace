package dev.codetrace.impact;

import dev.codetrace.impact.RiskSignal.RiskFactor;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Adds up the registered {@link RiskSignal}s into a 0..100 score and a level. */
@Component
public class RiskAssessor {
    public enum Level { LOW, MEDIUM, HIGH, CRITICAL }

    public record RiskAssessment(int score, Level level, List<RiskFactor> factors) {
    }

    private final List<RiskSignal> signals;

    public RiskAssessor(List<RiskSignal> signals) {
        Map<String, Long> counts = signals.stream().collect(Collectors.groupingBy(RiskSignal::id, Collectors.counting()));
        counts.forEach((id, n) -> {
            if (n > 1) throw new IllegalStateException("Two risk signals share the id " + id);
        });
        this.signals = signals.stream().sorted(Comparator.comparing(RiskSignal::id)).toList();
    }

    public RiskAssessment assess(ImpactAnalysis analysis) {
        List<RiskFactor> factors = signals.stream().map(s -> {
            RiskFactor f = s.evaluate(analysis);
            int points = Math.max(0, Math.min(s.maxPoints(), f.points())); // a signal can never exceed its own cap
            return new RiskFactor(s.id(), points, s.maxPoints(), f.reason());
        }).toList();
        int score = Math.min(100, factors.stream().mapToInt(RiskFactor::points).sum());
        return new RiskAssessment(score, level(score), factors);
    }

    public static Level level(int score) {
        if (score >= 75) return Level.CRITICAL;
        if (score >= 50) return Level.HIGH;
        if (score >= 25) return Level.MEDIUM;
        return Level.LOW;
    }
}
