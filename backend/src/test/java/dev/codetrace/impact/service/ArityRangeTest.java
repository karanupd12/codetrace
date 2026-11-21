package dev.codetrace.impact.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ArityRangeTest {
    @Test
    void countsPlainParameters() {
        assertThat(ImpactAnalyzer.arityRange("()")).containsExactly(0, 0);
        assertThat(ImpactAnalyzer.arityRange("(double)")).containsExactly(1, 1);
        assertThat(ImpactAnalyzer.arityRange("(int,String,long)")).containsExactly(3, 3);
    }

    @Test
    void commasInsideGenericsDoNotSeparateParameters() {
        assertThat(ImpactAnalyzer.arityRange("(Map<String,List<Integer>>)")).containsExactly(1, 1);
        assertThat(ImpactAnalyzer.arityRange("(Map<String,Integer>,Function<A,B>)")).containsExactly(2, 2);
    }

    @Test
    void varargsAcceptAnyNumberOfTrailingArguments() {
        assertThat(ImpactAnalyzer.arityRange("(String...)")).containsExactly(0, Integer.MAX_VALUE);
        assertThat(ImpactAnalyzer.arityRange("(int,String...)")).containsExactly(1, Integer.MAX_VALUE);
    }

    @Test
    void anUnparseableSignatureIsTreatedAsNoParameters() {
        assertThat(ImpactAnalyzer.arityRange("")).containsExactly(0, 0);
        assertThat(ImpactAnalyzer.arityRange("noParens")).containsExactly(0, 0);
    }
}
