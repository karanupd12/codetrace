package dev.codetrace.impact.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class ImpactAnalyzerNotesTest {
    @Test
    void eachWidelyImplementedMethodGetsOnePlainNote() {
        Map<String, Integer> skipped = new TreeMap<>(Map.of("p.Shape#area()", 12, "p.Codec#read(Reader)", 2));

        assertThat(ImpactAnalyzer.dispatchNotes(skipped)).containsExactly(
                "Callers of p.Codec#read(Reader) were not followed: the changed code implements it, but so do 1 other class, "
                        + "so a call to it rarely runs the changed code. Check its callers by hand if the change alters "
                        + "behaviour they rely on.",
                "Callers of p.Shape#area() were not followed: the changed code implements it, but so do 11 other classes, "
                        + "so a call to it rarely runs the changed code. Check its callers by hand if the change alters "
                        + "behaviour they rely on.");
        assertThat(ImpactAnalyzer.dispatchNotes(Map.of())).isEmpty();
    }
}
