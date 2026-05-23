package io.casehub.devtown.domain.sla;

import io.casehub.platform.api.preferences.SingleValuePreference;
import java.util.Objects;

public record IntPreference(int value) implements SingleValuePreference {
    public static IntPreference of(int value) { return new IntPreference(value); }
    public static IntPreference parse(String raw) {
        Objects.requireNonNull(raw, "raw must not be null");
        return new IntPreference(Integer.parseInt(raw));
    }
}
