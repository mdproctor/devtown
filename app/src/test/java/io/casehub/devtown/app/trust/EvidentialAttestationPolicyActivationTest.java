package io.casehub.devtown.app.trust;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.ledger.routing.TrustGatedAttestationPolicy;
import io.casehub.qhorus.api.spi.CommitmentAttestationPolicy;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(TrustScoringTestProfile.class)
@TestSecurity(user = "devtown-admin", roles = {"devtown-admin"})
class EvidentialAttestationPolicyActivationTest {

    @Inject CommitmentAttestationPolicy attestationPolicy;

    @Test
    void policyIsEvidential() {
        assertThat(attestationPolicy).isInstanceOf(EvidentialAttestationPolicy.class);
    }

    @Test
    void delegateIsTrustGated() {
        assertThat(attestationPolicy).isInstanceOf(EvidentialAttestationPolicy.class);
        EvidentialAttestationPolicy evidential = (EvidentialAttestationPolicy) attestationPolicy;
        assertThat(evidential.delegate()).isInstanceOf(TrustGatedAttestationPolicy.class);
    }
}
