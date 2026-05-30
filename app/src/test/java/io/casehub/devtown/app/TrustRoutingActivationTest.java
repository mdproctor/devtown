package io.casehub.devtown.app;

import io.casehub.api.spi.routing.AgentRoutingStrategy;
import io.casehub.api.spi.routing.TrustRoutingPolicy;
import io.casehub.api.spi.routing.TrustRoutingPolicyProvider;
import io.casehub.devtown.app.routing.DevtownTrustRoutingPolicyProvider;
import io.casehub.devtown.domain.ReviewDomain;
import io.casehub.devtown.domain.AgentQualification;
import io.casehub.ledger.routing.TrustWeightedAgentStrategy;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class TrustRoutingActivationTest {

    @Inject
    AgentRoutingStrategy agentRoutingStrategy;

    @Inject
    TrustRoutingPolicyProvider policyProvider;

    @Test
    void trustWeightedStrategyActivated() {
        assertThat(agentRoutingStrategy).isInstanceOf(TrustWeightedAgentStrategy.class);
    }

    @Test
    void devtownProviderActivated() {
        assertThat(policyProvider).isInstanceOf(DevtownTrustRoutingPolicyProvider.class);
    }

    @Test
    void styleReviewThresholdProvesYamlLoaded() {
        // style-review threshold = 0.50 from registry; DEFAULT.threshold() = 0.70.
        // If DevtownTrustRoutingPolicyProvider isn't wired, we'd get DEFAULT (0.70).
        // Getting 0.50 proves the provider is active and reading from the registry.
        TrustRoutingPolicy policy = policyProvider.forCapability(ReviewDomain.STYLE_REVIEW);
        assertThat(policy.threshold()).isEqualTo(0.50);
    }

    @Test
    void architectureReviewThresholdDiffersFromDefault() {
        // architecture-review threshold = 0.65; DEFAULT = 0.70
        TrustRoutingPolicy policy = policyProvider.forCapability(ReviewDomain.ARCHITECTURE_REVIEW);
        assertThat(policy.threshold()).isEqualTo(0.65);
        assertThat(policy.threshold()).isNotEqualTo(TrustRoutingPolicy.DEFAULT.threshold());
    }

    @Test
    void securityReviewHasThoroughnessFloor() {
        TrustRoutingPolicy policy = policyProvider.forCapability(ReviewDomain.SECURITY_REVIEW);
        assertThat(policy.qualityFloors()).containsEntry("review-thoroughness", 0.60);
    }

    @Test
    void mergeExecutorHasPrecisionFloor() {
        TrustRoutingPolicy policy = policyProvider.forCapability(AgentQualification.MERGE_EXECUTOR);
        assertThat(policy.qualityFloors()).containsEntry("precision", 0.70);
    }

    @Test
    void securityReviewBlendFactorFromYaml() {
        // blend-factor for security-review = 0.70 (YAML); DEFAULT.blendFactor() = 0.60
        TrustRoutingPolicy policy = policyProvider.forCapability(ReviewDomain.SECURITY_REVIEW);
        assertThat(policy.blendFactor()).isEqualTo(0.70);
        assertThat(policy.blendFactor()).isNotEqualTo(TrustRoutingPolicy.DEFAULT.blendFactor());
    }
}
