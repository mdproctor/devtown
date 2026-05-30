package io.casehub.devtown.domain.trust;

import io.casehub.platform.api.preferences.SingleValuePreference;
import java.util.Objects;

public record DoublePreference(double value) implements SingleValuePreference {
    public static DoublePreference of(double value) { return new DoublePreference(value); }
    public static DoublePreference parse(String raw) {
        Objects.requireNonNull(raw, "raw must not be null");
        return new DoublePreference(Double.parseDouble(raw));
    }
}
