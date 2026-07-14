package io.casehub.devtown.domain.cbr;

import io.casehub.devtown.domain.preferences.BooleanPreference;
import io.casehub.devtown.domain.preferences.DoublePreference;
import io.casehub.devtown.domain.preferences.IntPreference;
import io.casehub.platform.api.preferences.PreferenceKey;

public final class CbrPreferenceKeys {

    private static final String NS = "casehubio.devtown.cbr";

    public static final PreferenceKey<DoublePreference> WEIGHT_FILE_PATHS =
        new PreferenceKey<>(NS, "weight-file-paths", DoublePreference.of(1.0), DoublePreference::parse);
    public static final PreferenceKey<DoublePreference> WEIGHT_MODULES =
        new PreferenceKey<>(NS, "weight-modules", DoublePreference.of(1.5), DoublePreference::parse);
    public static final PreferenceKey<DoublePreference> WEIGHT_LANGUAGES =
        new PreferenceKey<>(NS, "weight-languages", DoublePreference.of(0.5), DoublePreference::parse);
    public static final PreferenceKey<DoublePreference> WEIGHT_CHANGE_SIZE =
        new PreferenceKey<>(NS, "weight-change-size", DoublePreference.of(1.0), DoublePreference::parse);
    public static final PreferenceKey<DoublePreference> WEIGHT_CONTRIBUTOR =
        new PreferenceKey<>(NS, "weight-contributor", DoublePreference.of(0.5), DoublePreference::parse);

    public static final PreferenceKey<IntPreference> K_LIMIT =
        new PreferenceKey<>(NS, "k-limit", IntPreference.of(5), IntPreference::parse);
    public static final PreferenceKey<DoublePreference> MIN_THRESHOLD =
        new PreferenceKey<>(NS, "min-threshold", DoublePreference.of(0.3), DoublePreference::parse);
    public static final PreferenceKey<IntPreference> TIME_WINDOW_DAYS =
        new PreferenceKey<>(NS, "time-window-days", IntPreference.of(180), IntPreference::parse);

    // Hard gates — pre-filter before scoring (default: all disabled)
    public static final PreferenceKey<IntPreference> GATE_MIN_MODULE_OVERLAP =
        new PreferenceKey<>(NS, "gate-min-module-overlap", IntPreference.of(0), IntPreference::parse);
    public static final PreferenceKey<DoublePreference> GATE_MIN_CHANGE_SIZE_RATIO =
        new PreferenceKey<>(NS, "gate-min-change-size-ratio", DoublePreference.of(0.0), DoublePreference::parse);
    public static final PreferenceKey<BooleanPreference> GATE_SAME_REPO =
        new PreferenceKey<>(NS, "gate-same-repo", BooleanPreference.of(true), BooleanPreference::parse);
    public static final PreferenceKey<IntPreference>     PRECEDENT_ACTIVATION_MIN_FINDINGS =
            new PreferenceKey<>(NS, "precedent-activation-min-findings", IntPreference.of(2), IntPreference::parse);
    public static final PreferenceKey<DoublePreference>  PRECEDENT_ACTIVATION_MIN_FRACTION =
            new PreferenceKey<>(NS, "precedent-activation-min-fraction", DoublePreference.of(0.4), DoublePreference::parse);
    public static final PreferenceKey<IntPreference>     SECURITY_REVIEW_MIN_FINDINGS      =
            new PreferenceKey<>(NS, "precedent-activation.security-review.min-findings", IntPreference.of(2), IntPreference::parse);
    public static final PreferenceKey<DoublePreference>  SECURITY_REVIEW_MIN_FRACTION      =
            new PreferenceKey<>(NS, "precedent-activation.security-review.min-fraction", DoublePreference.of(0.3), DoublePreference::parse);
    public static final PreferenceKey<IntPreference>     ARCHITECTURE_REVIEW_MIN_FINDINGS  =
            new PreferenceKey<>(NS, "precedent-activation.architecture-review.min-findings", IntPreference.of(2), IntPreference::parse);
    public static final PreferenceKey<DoublePreference>  ARCHITECTURE_REVIEW_MIN_FRACTION  =
            new PreferenceKey<>(NS, "precedent-activation.architecture-review.min-fraction", DoublePreference.of(0.4), DoublePreference::parse);


    private CbrPreferenceKeys() {}
}
