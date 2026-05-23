package io.casehub.devtown.domain.sla;

import io.casehub.platform.api.preferences.SingleValuePreference;
import java.util.Objects;

public record StringPreference(String value) implements SingleValuePreference {
    public StringPreference {
        Objects.requireNonNull(value, "value must not be null");
    }
    public static StringPreference of(String value) { return new StringPreference(value); }
    // parse is the Function<String,T> reference used by PreferenceKey; identical to of() for strings
    public static StringPreference parse(String raw) {
        Objects.requireNonNull(raw, "raw must not be null");
        return new StringPreference(raw);
    }
}
