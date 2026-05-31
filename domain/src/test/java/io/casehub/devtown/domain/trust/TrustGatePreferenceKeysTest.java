package io.casehub.devtown.domain.trust;

import io.casehub.devtown.domain.preferences.DoublePreference;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TrustGatePreferenceKeysTest {

    @Test
    void minObligorTrustHasCorrectNamespace() {
        assertThat(TrustGatePreferenceKeys.MIN_OBLIGOR_TRUST.namespace())
            .isEqualTo("casehubio.devtown.trust-gate");
    }

    @Test
    void minObligorTrustHasCorrectName() {
        assertThat(TrustGatePreferenceKeys.MIN_OBLIGOR_TRUST.name())
            .isEqualTo("min-obligor-trust");
    }

    @Test
    void minObligorTrustDefaultIsZero() {
        assertThat(TrustGatePreferenceKeys.MIN_OBLIGOR_TRUST.defaultValue())
            .isEqualTo(DoublePreference.of(0.0));
    }
}
