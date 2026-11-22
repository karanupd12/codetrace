package dev.codetrace.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ArgumentTypesTest {
    @Test
    void hintsSurviveAStorageRoundTripIncludingEmptyOnesAndGenerics() {
        List<String> hints = List.of("int", "", "Map<String,Integer>", "String[]");

        assertThat(ArgumentTypes.decode(ArgumentTypes.encode(hints), 4)).isEqualTo(hints);
    }

    @Test
    void oneUnknownArgumentIsNotConfusedWithNoArguments() {
        assertThat(ArgumentTypes.decode("", 1)).containsExactly("");
        assertThat(ArgumentTypes.decode("", 0)).isEmpty();
    }

    @Test
    void storedDataThatDoesNotMatchTheArgumentCountCountsAsUnknown() {
        assertThat(ArgumentTypes.decode("int", 3)).containsExactly("", "", "");
        assertThat(ArgumentTypes.decode(null, 2)).containsExactly("", "");
    }
}
