package io.casehub.devtown.domain.trust;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DoublePreferenceTest {

    @Test
    void ofPreservesValue() {
        assertThat(DoublePreference.of(0.70).value()).isEqualTo(0.70);
    }

    @Test
    void parseConvertsString() {
        assertThat(DoublePreference.parse("0.42").value()).isEqualTo(0.42);
    }

    @Test
    void parseRejectsNull() {
        assertThatThrownBy(() -> DoublePreference.parse(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void parseRejectsNonNumeric() {
        assertThatThrownBy(() -> DoublePreference.parse("not-a-number"))
            .isInstanceOf(NumberFormatException.class);
    }
}
