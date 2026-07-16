package io.casehub.devtown.domain.sla;

import io.casehub.devtown.domain.cbr.Precedent;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class SlaEstimator {

    public static Optional<SlaEstimate> estimate(List<Precedent> precedents) {
        List<Duration> durations = precedents.stream()
            .map(Precedent::completionTime)
            .filter(Objects::nonNull)
            .filter(d -> !d.isNegative() && !d.isZero())
            .sorted()
            .toList();

        if (durations.isEmpty()) return Optional.empty();

        Duration median = durations.get(durations.size() / 2);
        Duration min = durations.getFirst();
        Duration max = durations.getLast();

        return Optional.of(new SlaEstimate(median, durations.size(), min, max));
    }

    private SlaEstimator() {}
}
