package io.casehub.devtown.domain.trust;

import io.casehub.devtown.domain.preferences.DoublePreference;
import io.casehub.platform.api.preferences.PreferenceKey;

public final class TrustGatePreferenceKeys {

    /**
     * Global trust floor for non-bootstrap agents. 0.0 = gate disabled.
     * Note: constructor default is unused at runtime — MapPreferences.get()
     * returns null for absent keys. See null guard in DevtownObligorTrustPolicy.
     */
    public static final PreferenceKey<DoublePreference> MIN_OBLIGOR_TRUST =
        new PreferenceKey<>(
            "casehubio.devtown.trust-gate",
            "min-obligor-trust",
            DoublePreference.of(0.0),
            DoublePreference::parse);

    private TrustGatePreferenceKeys() {}
}
