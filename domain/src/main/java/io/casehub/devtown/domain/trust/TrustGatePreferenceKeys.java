package io.casehub.devtown.domain.trust;

import io.casehub.devtown.domain.preferences.DoublePreference;
import io.casehub.platform.api.preferences.PreferenceKey;

public final class TrustGatePreferenceKeys {

    /**
     * Global trust floor for non-bootstrap agents. 0.0 = gate disabled.
     * Consumers must null-check the resolved value — absent YAML keys return null, not this default.
     */
    public static final PreferenceKey<DoublePreference> MIN_OBLIGOR_TRUST =
        new PreferenceKey<>(
            "casehubio.devtown.trust-gate",
            "min-obligor-trust",
            DoublePreference.of(0.0),
            DoublePreference::parse);

    private TrustGatePreferenceKeys() {}
}
