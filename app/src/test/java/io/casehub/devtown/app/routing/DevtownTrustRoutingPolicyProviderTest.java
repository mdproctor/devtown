package io.casehub.devtown.app.routing;

import io.casehub.api.spi.routing.TrustRoutingPolicy;
import io.casehub.devtown.domain.DevtownCapabilityRegistry;
import io.casehub.devtown.domain.ReviewDomain;
import io.casehub.devtown.domain.AgentQualification;
import io.casehub.platform.api.preferences.MapPreferences;
import io.casehub.platform.api.preferences.PreferenceProvider;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class DevtownTrustRoutingPolicyProviderTest {

    // Preference provider that returns empty preferences for all scopes
    private static final PreferenceProvider EMPTY = scope -> new MapPreferences(Map.of());

    // Preference provider that returns specific values (simulates YAML-loaded prefs)
    private static final PreferenceProvider POPULATED = scope ->
        new MapPreferences(Map.of(
            "casehubio.devtown.trust-routing.blend-factor", "0.42",
            "casehubio.devtown.trust-routing.floor.review-thoroughness", "0.88"
        ));

    private final DevtownCapabilityRegistry registry = new DevtownCapabilityRegistry();

    // === Fallback path: EMPTY prefs — values come from registry only ===

    @Test
    void securityReviewUsesRegistryThreshold() {
        var provider = new DevtownTrustRoutingPolicyProvider(EMPTY, registry);
        TrustRoutingPolicy policy = provider.forCapability(ReviewDomain.SECURITY_REVIEW);
        assertThat(policy.threshold()).isEqualTo(0.70);
    }

    @Test
    void securityReviewUsesRegistryMinObservations() {
        var provider = new DevtownTrustRoutingPolicyProvider(EMPTY, registry);
        TrustRoutingPolicy policy = provider.forCapability(ReviewDomain.SECURITY_REVIEW);
        assertThat(policy.minimumObservations()).isEqualTo(10);
    }

    @Test
    void securityReviewUsesRegistryBorderlineMargin() {
        var provider = new DevtownTrustRoutingPolicyProvider(EMPTY, registry);
        TrustRoutingPolicy policy = provider.forCapability(ReviewDomain.SECURITY_REVIEW);
        assertThat(policy.borderlineMargin()).isEqualTo(0.05);
    }

    @Test
    void unknownCapabilityReturnsDefault() {
        var provider = new DevtownTrustRoutingPolicyProvider(EMPTY, registry);
        TrustRoutingPolicy policy = provider.forCapability("unknown-capability");
        assertThat(policy).isEqualTo(TrustRoutingPolicy.DEFAULT);
    }

    @Test
    void noFloorInEmptyPrefs() {
        var provider = new DevtownTrustRoutingPolicyProvider(EMPTY, registry);
        TrustRoutingPolicy policy = provider.forCapability(ReviewDomain.SECURITY_REVIEW);
        assertThat(policy.qualityFloors()).isEmpty();
    }

    // === Parsing path: POPULATED prefs — verifies field assembly ===

    @Test
    void blendFactorParsedFromPrefs() {
        var provider = new DevtownTrustRoutingPolicyProvider(POPULATED, registry);
        TrustRoutingPolicy policy = provider.forCapability(ReviewDomain.SECURITY_REVIEW);
        assertThat(policy.blendFactor()).isEqualTo(0.42);
    }

    @Test
    void floorParsedFromPrefsAndAddedToMap() {
        var provider = new DevtownTrustRoutingPolicyProvider(POPULATED, registry);
        TrustRoutingPolicy policy = provider.forCapability(ReviewDomain.SECURITY_REVIEW);
        assertThat(policy.qualityFloors()).containsEntry("review-thoroughness", 0.88);
    }

    @Test
    void zeroFloorValueNotAddedToMap() {
        var provider = new DevtownTrustRoutingPolicyProvider(
            scope -> new MapPreferences(Map.of(
                "casehubio.devtown.trust-routing.floor.precision", "0.0"
            )),
            registry);
        TrustRoutingPolicy policy = provider.forCapability(AgentQualification.MERGE_EXECUTOR);
        assertThat(policy.qualityFloors()).doesNotContainKey("precision");
    }

    @Test
    void allSixCapabilitiesResolveWithoutException() {
        var provider = new DevtownTrustRoutingPolicyProvider(EMPTY, registry);
        assertThat(provider.forCapability(ReviewDomain.CODE_ANALYSIS)).isNotNull();
        assertThat(provider.forCapability(ReviewDomain.SECURITY_REVIEW)).isNotNull();
        assertThat(provider.forCapability(ReviewDomain.ARCHITECTURE_REVIEW)).isNotNull();
        assertThat(provider.forCapability(ReviewDomain.STYLE_REVIEW)).isNotNull();
        assertThat(provider.forCapability(ReviewDomain.TEST_COVERAGE)).isNotNull();
        assertThat(provider.forCapability(ReviewDomain.PERFORMANCE_ANALYSIS)).isNotNull();
    }
}
