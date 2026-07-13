package io.casehub.devtown.app.routing;

import io.casehub.api.spi.routing.DoublePreference;
import io.casehub.api.spi.routing.TrustPhase;
import io.casehub.api.spi.routing.TrustRoutingPolicy;
import io.casehub.api.spi.routing.TrustRoutingPolicyKeys;
import io.casehub.api.spi.routing.TrustRoutingPolicyProvider;
import io.casehub.api.spi.routing.TrustRoutingPolicyResolver;
import io.casehub.devtown.domain.AgentQualification;
import io.casehub.devtown.domain.DevtownTrustDimension;
import io.casehub.devtown.domain.ReviewDomain;
import io.casehub.devtown.domain.RoutingPolicy;
import io.casehub.devtown.domain.spi.CapabilityRegistry;
import io.casehub.platform.api.preferences.PreferenceProvider;
import io.casehub.platform.api.preferences.Preferences;
import io.casehub.platform.api.preferences.SettingsScope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

@ApplicationScoped
public class DevtownTrustRoutingPolicyProvider implements TrustRoutingPolicyProvider {

    static final TrustRoutingPolicyKeys KEYS =
            TrustRoutingPolicyKeys.create("casehubio.devtown.trust-routing")
                                  .withFloor(DevtownTrustDimension.REVIEW_THOROUGHNESS, "review-thoroughness")
                                  .withFloor(DevtownTrustDimension.PRECISION, "precision")
                                  .withFloor(DevtownTrustDimension.SCOPE_CALIBRATION, "scope-calibration");

    static final Map<String, Set<TrustPhase>> EVIDENTIAL_CHECK_PHASES = Map.of(
            ReviewDomain.SECURITY_REVIEW,
            Set.of(TrustPhase.BELOW_THRESHOLD, TrustPhase.QUALITY_FAILED, TrustPhase.BOOTSTRAP),
            ReviewDomain.ARCHITECTURE_REVIEW,
            Set.of(TrustPhase.BELOW_THRESHOLD, TrustPhase.QUALITY_FAILED),
            AgentQualification.MERGE_EXECUTOR,
            Set.of(TrustPhase.BELOW_THRESHOLD, TrustPhase.QUALITY_FAILED,
                   TrustPhase.BOOTSTRAP, TrustPhase.BORDERLINE)
                                                                              );

    private final PreferenceProvider preferenceProvider;
    private final CapabilityRegistry capabilityRegistry;

    @Override
    public String id() {
        return "devtown";
    }

    @Inject
    public DevtownTrustRoutingPolicyProvider(
            final PreferenceProvider preferenceProvider,
            final CapabilityRegistry capabilityRegistry) {
        this.preferenceProvider = preferenceProvider;
        this.capabilityRegistry = capabilityRegistry;
    }

    @Override
    public TrustRoutingPolicy forCapability(final String capabilityName) {
        final Optional<RoutingPolicy> rp = capabilityRegistry.policy(capabilityName);
        if (rp.isEmpty()) {
            return TrustRoutingPolicy.DEFAULT;
        }

        final RoutingPolicy routingPolicy = rp.get();
        final Preferences prefs = preferenceProvider.resolve(
                SettingsScope.of("casehubio", "devtown", "trust-routing", capabilityName));

        final double threshold = routingPolicy.threshold()
                                              .orElse(TrustRoutingPolicy.DEFAULT.threshold());
        final int minimumObservations = routingPolicy.minimumObservations()
                                                     .orElse(TrustRoutingPolicy.DEFAULT.minimumObservations());
        final double borderlineMargin = routingPolicy.borderlineMargin()
                                                     .orElse(TrustRoutingPolicy.DEFAULT.borderlineMargin());

        final DoublePreference blendFactorPref = prefs.get(KEYS.blendFactor());
        final double blendFactor = blendFactorPref != null
                                   ? blendFactorPref.value()
                                   : TrustRoutingPolicy.DEFAULT.blendFactor();

        final Map<String, Double> qualityFloors =
                TrustRoutingPolicyResolver.collectFloors(prefs, KEYS.allFloorKeys());

        final Set<TrustPhase> evidentialPhases =
                EVIDENTIAL_CHECK_PHASES.getOrDefault(capabilityName, Set.of());

        return new TrustRoutingPolicy(threshold, minimumObservations, borderlineMargin,
                                      blendFactor, Map.copyOf(qualityFloors), routingPolicy.fallbackType().isPresent(),
                                      TrustRoutingPolicy.DEFAULT.fallbackBinding(), evidentialPhases);
    }
}
