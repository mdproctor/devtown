package io.casehub.devtown.domain.sla;

import java.time.Duration;
import java.util.Map;

public record SlaEstimate(
    Duration median,
    int precedentCount,
    Duration min,
    Duration max
) {
    public Map<String, Object> toContextMap() {
        return Map.of(
            "medianSeconds", median.toSeconds(),
            "precedentCount", precedentCount,
            "minSeconds", min.toSeconds(),
            "maxSeconds", max.toSeconds()
        );
    }
}
