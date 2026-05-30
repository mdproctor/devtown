package io.casehub.devtown.domain.trust;

import io.casehub.devtown.domain.DevtownTrustDimension;
import io.casehub.platform.api.preferences.PreferenceKey;
import java.util.Map;

/**
 * PreferenceKey constants for trust routing YAML configuration.
 * Resolved at scope: casehubio/devtown/trust-routing/<capabilityName>
 *
 * threshold, minimumObservations, and borderlineMargin come from DevtownCapabilityRegistry —
 * these keys cover only the engine-specific fields not present in RoutingPolicy.
 */
public final class TrustRoutingPolicyKeys {

    /** Weight of trust score vs workload (0.0 = pure workload, 1.0 = pure trust). Functional default is in YAML. */
    public static final PreferenceKey<DoublePreference> BLEND_FACTOR =
        new PreferenceKey<>(
            "casehubio.devtown.trust-routing",
            "blend-factor",
            DoublePreference.of(0.0),
            DoublePreference::parse);

    /** Minimum review-thoroughness dimension score (higher = finds more real issues). 0.0 = no floor. */
    public static final PreferenceKey<DoublePreference> FLOOR_REVIEW_THOROUGHNESS =
        new PreferenceKey<>(
            "casehubio.devtown.trust-routing",
            "floor.review-thoroughness",
            DoublePreference.of(0.0),
            DoublePreference::parse);

    /** Minimum precision dimension score (higher = fewer false positives). 0.0 = no floor. */
    public static final PreferenceKey<DoublePreference> FLOOR_PRECISION =
        new PreferenceKey<>(
            "casehubio.devtown.trust-routing",
            "floor.precision",
            DoublePreference.of(0.0),
            DoublePreference::parse);

    /** Minimum scope-calibration dimension score (higher = better DECLINE accuracy). 0.0 = no floor. */
    public static final PreferenceKey<DoublePreference> FLOOR_SCOPE_CALIBRATION =
        new PreferenceKey<>(
            "casehubio.devtown.trust-routing",
            "floor.scope-calibration",
            DoublePreference.of(0.0),
            DoublePreference::parse);

    /**
     * All floor keys keyed by their trust dimension name.
     * Use this to iterate all floors — do not call addFloor manually for each key.
     */
    public static Map<String, PreferenceKey<DoublePreference>> allFloorKeys() {
        return Map.of(
            DevtownTrustDimension.REVIEW_THOROUGHNESS, FLOOR_REVIEW_THOROUGHNESS,
            DevtownTrustDimension.PRECISION,           FLOOR_PRECISION,
            DevtownTrustDimension.SCOPE_CALIBRATION,   FLOOR_SCOPE_CALIBRATION
        );
    }

    private TrustRoutingPolicyKeys() {}
}
