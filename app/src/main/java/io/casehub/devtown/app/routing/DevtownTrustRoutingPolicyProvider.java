package io.casehub.devtown.app.routing;

import io.casehub.api.spi.routing.TrustRoutingPolicy;
import io.casehub.api.spi.routing.TrustRoutingPolicyProvider;
import io.casehub.devtown.domain.RoutingPolicy;
import io.casehub.devtown.domain.spi.CapabilityRegistry;
import io.casehub.devtown.domain.trust.DoublePreference;
import io.casehub.devtown.domain.trust.TrustRoutingPolicyKeys;
import io.casehub.platform.api.preferences.PreferenceKey;
import io.casehub.platform.api.preferences.PreferenceProvider;
import io.casehub.platform.api.preferences.Preferences;
import io.casehub.platform.api.preferences.SettingsScope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Devtown-specific {@link TrustRoutingPolicyProvider}.
 *
 * <p>Reads threshold/minimumObservations/borderlineMargin from {@link CapabilityRegistry}
 * (single source of truth in the domain layer). Reads blendFactor and quality floors from
 * YAML config via {@link PreferenceProvider} at scope
 * {@code casehubio/devtown/trust-routing/<capabilityName>}.
 *
 * <p>{@code @ApplicationScoped} (no {@code @DefaultBean}) displaces
 * {@code DefaultTrustRoutingPolicyProvider @DefaultBean} automatically.
 */
@ApplicationScoped
public class DevtownTrustRoutingPolicyProvider implements TrustRoutingPolicyProvider {

    private final PreferenceProvider preferenceProvider;
    private final CapabilityRegistry capabilityRegistry;

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
        // resolve() returns empty prefs for capabilities with no YAML scope — that is safe and expected
        final Preferences prefs = preferenceProvider.resolve(
            SettingsScope.of("casehubio", "devtown", "trust-routing", capabilityName));

        final double threshold = routingPolicy.threshold()
            .orElse(TrustRoutingPolicy.DEFAULT.threshold());
        final int minimumObservations = routingPolicy.minimumObservations()
            .orElse(TrustRoutingPolicy.DEFAULT.minimumObservations());
        final double borderlineMargin = routingPolicy.borderlineMargin()
            .orElse(TrustRoutingPolicy.DEFAULT.borderlineMargin());

        final DoublePreference blendFactorPref = prefs.get(TrustRoutingPolicyKeys.BLEND_FACTOR);
        final double blendFactor = blendFactorPref != null
            ? blendFactorPref.value()
            : TrustRoutingPolicy.DEFAULT.blendFactor();

        final Map<String, Double> qualityFloors = new HashMap<>();
        TrustRoutingPolicyKeys.allFloorKeys().forEach((dimension, key) ->
            addFloor(qualityFloors, prefs, key, dimension));

        return new TrustRoutingPolicy(threshold, minimumObservations, borderlineMargin,
            blendFactor, Map.copyOf(qualityFloors));
    }

    // 0.0 is the no-floor sentinel per TrustRoutingPolicyKeys — skip absent or zero floors
    private static void addFloor(final Map<String, Double> floors, final Preferences prefs,
            final PreferenceKey<DoublePreference> key, final String dimension) {
        final DoublePreference value = prefs.get(key);
        if (value != null && value.value() > 0.0) {
            floors.put(dimension, value.value());
        }
    }
}
