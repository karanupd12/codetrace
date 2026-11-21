package dev.codetrace.impact;

/**
 * One independent, deterministic contribution to a change's risk score. Implement and register as a Spring component
 * to add a signal; the assessor picks it up without any change to existing code. A signal must be a pure function of
 * the analysis: same input, same points, no clocks, randomness or external calls.
 */
public interface RiskSignal {

    /** Stable identifier, also the sort key that fixes the order signals are reported in. */
    String id();

    /** Maximum points this signal can contribute. */
    int maxPoints();

    /** Points (0..maxPoints) and the human-readable reason. A zero-point factor means the signal found nothing. */
    RiskFactor evaluate(ImpactAnalysis analysis);

    record RiskFactor(String signal, int points, int maxPoints, String reason) {
    }
}
