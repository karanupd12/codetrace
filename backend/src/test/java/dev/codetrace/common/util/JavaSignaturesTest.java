package dev.codetrace.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JavaSignaturesTest {
    @Test
    void countsPlainParameters() {
        assertThat(JavaSignatures.arityRange("()")).containsExactly(0, 0);
        assertThat(JavaSignatures.arityRange("(double)")).containsExactly(1, 1);
        assertThat(JavaSignatures.arityRange("(int,String,long)")).containsExactly(3, 3);
    }

    @Test
    void commasInsideGenericsDoNotSeparateParameters() {
        assertThat(JavaSignatures.arityRange("(Map<String,List<Integer>>)")).containsExactly(1, 1);
        assertThat(JavaSignatures.arityRange("(Map<String,Integer>,Function<A,B>)")).containsExactly(2, 2);
    }

    @Test
    void varargsAcceptAnyNumberOfTrailingArguments() {
        assertThat(JavaSignatures.arityRange("(String...)")).containsExactly(0, Integer.MAX_VALUE);
        assertThat(JavaSignatures.arityRange("(int,String...)")).containsExactly(1, Integer.MAX_VALUE);
    }

    @Test
    void anUnparseableSignatureIsTreatedAsNoParameters() {
        assertThat(JavaSignatures.arityRange("")).containsExactly(0, 0);
        assertThat(JavaSignatures.arityRange("noParens")).containsExactly(0, 0);
    }
}
