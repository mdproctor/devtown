package io.casehub.devtown.app.routing;

import io.casehub.devtown.domain.preferences.DoublePreference;
import io.casehub.devtown.domain.trust.TrustGatePreferenceKeys;
import io.casehub.ledger.runtime.service.TrustGateService;
import io.casehub.platform.api.preferences.PreferenceProvider;
import io.casehub.platform.api.preferences.Preferences;
import io.casehub.platform.api.preferences.SettingsScope;
import io.casehub.qhorus.api.spi.ObligorTrustContext;
import io.casehub.qhorus.api.spi.ObligorTrustPolicy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Devtown trust gate — global trust floor for non-bootstrap agents.
 *
 * <p>Bootstrap agents (no ledger observations — {@link TrustGateService#currentScore} returns
 * {@link java.util.Optional#empty()}) are always permitted. Only agents with a recorded score
 * below the configured floor are denied.
 *
 * <p>{@code @ApplicationScoped} (no {@code @DefaultBean}) displaces
 * {@code DefaultObligorTrustPolicy @DefaultBean} via CDI priority.
 */
@ApplicationScoped
public class DevtownObligorTrustPolicy implements ObligorTrustPolicy {

    private final PreferenceProvider preferenceProvider;
    private final TrustGateService trustGateService;

    @Inject
    public DevtownObligorTrustPolicy(PreferenceProvider preferenceProvider,
                                      TrustGateService trustGateService) {
        this.preferenceProvider = preferenceProvider;
        this.trustGateService = trustGateService;
    }

    @Override
    public boolean permits(ObligorTrustContext ctx) {
        Preferences prefs = preferenceProvider.resolve(
            SettingsScope.of("casehubio", "devtown", "trust-gate"));

        DoublePreference thresholdPref = prefs.get(TrustGatePreferenceKeys.MIN_OBLIGOR_TRUST);
        double floor = thresholdPref != null ? thresholdPref.value() : 0.0;

        if (floor <= 0.0) return true;          // gate disabled

        return trustGateService.currentScore(ctx.obligorId())
            .map(score -> score >= floor)
            .orElse(true);                       // bootstrap — no observations → permit
    }
}
