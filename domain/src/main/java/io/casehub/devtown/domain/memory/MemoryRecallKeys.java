package io.casehub.devtown.domain.memory;

import io.casehub.devtown.domain.preferences.IntPreference;
import io.casehub.platform.api.preferences.PreferenceKey;

/**
 * PreferenceKey constants for memory recall configuration.
 *
 * <p>PreferenceKey namespace (map key prefix): {@code casehubio.devtown.memory-recall}
 * <p>Resolution path (SettingsScope): {@code casehubio/devtown/memory-recall}
 */
public final class MemoryRecallKeys {

    public static final PreferenceKey<IntPreference> CONTRIBUTOR_LIMIT =
        new PreferenceKey<>(
            "casehubio.devtown.memory-recall",
            "contributor-limit",
            IntPreference.of(10),
            IntPreference::parse);

    public static final PreferenceKey<IntPreference> CODE_AREA_LIMIT =
        new PreferenceKey<>(
            "casehubio.devtown.memory-recall",
            "code-area-limit",
            IntPreference.of(15),
            IntPreference::parse);

    public static final PreferenceKey<IntPreference> TIME_WINDOW_DAYS =
        new PreferenceKey<>(
            "casehubio.devtown.memory-recall",
            "time-window-days",
            IntPreference.of(90),
            IntPreference::parse);

    private MemoryRecallKeys() {}
}
