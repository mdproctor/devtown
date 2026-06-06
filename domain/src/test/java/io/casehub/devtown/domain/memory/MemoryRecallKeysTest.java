package io.casehub.devtown.domain.memory;

import io.casehub.devtown.domain.preferences.IntPreference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryRecallKeysTest {

    @Test
    void contributorLimitDefault() {
        assertThat(MemoryRecallKeys.CONTRIBUTOR_LIMIT.defaultValue().value()).isEqualTo(10);
    }

    @Test
    void codeAreaLimitDefault() {
        assertThat(MemoryRecallKeys.CODE_AREA_LIMIT.defaultValue().value()).isEqualTo(15);
    }

    @Test
    void timeWindowDaysDefault() {
        assertThat(MemoryRecallKeys.TIME_WINDOW_DAYS.defaultValue().value()).isEqualTo(90);
    }

    @Test
    void contributorLimitParseRoundTrip() {
        IntPreference parsed = MemoryRecallKeys.CONTRIBUTOR_LIMIT.parse("25");
        assertThat(parsed.value()).isEqualTo(25);
    }

    @Test
    void codeAreaLimitParseRoundTrip() {
        IntPreference parsed = MemoryRecallKeys.CODE_AREA_LIMIT.parse("50");
        assertThat(parsed.value()).isEqualTo(50);
    }

    @Test
    void timeWindowDaysParseRoundTrip() {
        IntPreference parsed = MemoryRecallKeys.TIME_WINDOW_DAYS.parse("180");
        assertThat(parsed.value()).isEqualTo(180);
    }

    @Test
    void qualifiedNameFollowsConvention() {
        assertThat(MemoryRecallKeys.CONTRIBUTOR_LIMIT.qualifiedName())
            .isEqualTo("casehubio.devtown.memory-recall.contributor-limit");
        assertThat(MemoryRecallKeys.CODE_AREA_LIMIT.qualifiedName())
            .isEqualTo("casehubio.devtown.memory-recall.code-area-limit");
        assertThat(MemoryRecallKeys.TIME_WINDOW_DAYS.qualifiedName())
            .isEqualTo("casehubio.devtown.memory-recall.time-window-days");
    }
}
