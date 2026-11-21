package dev.codetrace.impact.change;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SignificantLinesTest {
    private static boolean[] of(String s) {
        return SignificantLines.of(s);
    }

    @Test
    void blankAndCommentOnlyLinesAreNotCode() {
        boolean[] s = of("""
                int a;

                // line comment
                /* block */
                /**
                 * javadoc
                 */
                int b; // trailing
                """);
        assertThat(s).containsExactly(true, false, false, false, false, false, false, true, false);
    }

    @Test
    void codeAfterABlockCommentOnTheSameLineCounts() {
        assertThat(of("/* note */ int a;\n*/")).startsWith(true);
        assertThat(of("/* start\n end */ int b;\n")).containsExactly(false, true, false);
    }

    @Test
    void aWrappedExpressionStartingWithAnAsteriskIsCode() {
        assertThat(of("int total = price\n    * quantity;\n")).containsExactly(true, true, false);
    }

    @Test
    void commentMarkersInsideLiteralsAreText() {
        assertThat(of("String s = \"/* not a comment\";\nint after;\n")).containsExactly(true, true, false);
        assertThat(of("String u = \"http://example.com\"; // real comment\nint c;\n")).containsExactly(true, true, false);
        assertThat(of("char c = '\"';\nint d;\n")).containsExactly(true, true, false);
        assertThat(of("String e = \"esc \\\" /* still a string\";\nint f;\n")).containsExactly(true, true, false);
    }

    @Test
    void textBlockContentIsCodeEvenWhenItLooksLikeAComment() {
        boolean[] s = of("String t = \"\"\"\n    // inside\n\n    \"\"\";\nint g;\n");
        assertThat(s).containsExactly(true, true, true, true, true, false);
    }

    @Test
    void anUnterminatedStringDoesNotSwallowTheRestOfTheFile() {
        assertThat(of("String broken = \"oops\n// comment\nint h;\n")).containsExactly(true, false, true, false);
    }
}
