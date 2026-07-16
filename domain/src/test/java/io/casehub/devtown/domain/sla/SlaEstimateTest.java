package io.casehub.devtown.domain.sla;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

class SlaEstimateTest {

    @Test
    void toContextMapProducesCorrectKeysAndValues() {
        var estimate = new SlaEstimate(Duration.ofMinutes(12), 5, Duration.ofMinutes(3), Duration.ofMinutes(25));
        var map = estimate.toContextMap();

        assertThat(map.get("medianSeconds")).isEqualTo(720L);
        assertThat(map.get("precedentCount")).isEqualTo(5);
        assertThat(map.get("minSeconds")).isEqualTo(180L);
        assertThat(map.get("maxSeconds")).isEqualTo(1500L);
    }

    @Test
    void subSecondDurationsTruncateToZero() {
        var estimate = new SlaEstimate(Duration.ofMillis(500), 1, Duration.ofMillis(500), Duration.ofMillis(500));
        var map = estimate.toContextMap();

        assertThat(map.get("medianSeconds")).isEqualTo(0L);
    }
}
