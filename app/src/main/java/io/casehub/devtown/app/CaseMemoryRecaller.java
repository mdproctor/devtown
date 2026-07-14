package io.casehub.devtown.app;

import io.casehub.devtown.domain.ReviewDomain;
import io.casehub.devtown.domain.cbr.ActivationThreshold;
import io.casehub.devtown.domain.cbr.CbrPreferenceKeys;
import io.casehub.devtown.domain.cbr.PrFeatureVector;
import io.casehub.devtown.domain.cbr.Precedent;
import io.casehub.devtown.domain.cbr.PrecedentActivationPolicy;
import io.casehub.devtown.domain.memory.DevtownMemoryDomain;
import io.casehub.devtown.domain.memory.MemoryRecallKeys;
import io.casehub.devtown.domain.memory.ModulePathNormalizer;
import io.casehub.devtown.review.CbrRetrievalService;
import io.casehub.devtown.review.MemoryContext;
import io.casehub.devtown.review.PrPayload;
import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.neocortex.memory.Memory;
import io.casehub.neocortex.memory.MemoryOrder;
import io.casehub.neocortex.memory.MemoryQuery;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.api.preferences.PreferenceProvider;
import io.casehub.platform.api.preferences.Preferences;
import io.casehub.platform.api.preferences.SettingsScope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Recalls memory context before case open.
 * <p>
 * Runs in request scope — {@link CurrentPrincipal} is available.
 * Queries contributor and code area history from {@link CaseMemoryStore}.
 * <p>
 * Fail-open: query failures return {@link MemoryContext#EMPTY}.
 */
@ApplicationScoped
public class CaseMemoryRecaller {

    private static final Logger        LOG          = Logger.getLogger(CaseMemoryRecaller.class);
    private static final SettingsScope RECALL_SCOPE =
            SettingsScope.of("casehubio", "devtown", "memory-recall");

    private final Instance<CaseMemoryStore>     store;
    private final CurrentPrincipal              principal;
    private final PreferenceProvider            preferenceProvider;
    private final Instance<CbrRetrievalService> cbrService;

    @Inject
    public CaseMemoryRecaller(final Instance<CaseMemoryStore> store,
                              final CurrentPrincipal principal,
                              final PreferenceProvider preferenceProvider,
                              final Instance<CbrRetrievalService> cbrService) {
        this.store              = store;
        this.principal          = principal;
        this.preferenceProvider = preferenceProvider;
        this.cbrService         = cbrService;
    }

    public MemoryContext recall(final PrPayload pr) {
        if (!store.isResolvable()) {
            return MemoryContext.EMPTY;
        }

        try {
            var         s                = store.get();
            Preferences prefs            = preferenceProvider.resolve(RECALL_SCOPE);
            int         contributorLimit = prefs.getOrDefault(MemoryRecallKeys.CONTRIBUTOR_LIMIT).value();
            int         codeAreaLimit    = prefs.getOrDefault(MemoryRecallKeys.CODE_AREA_LIMIT).value();
            int         timeWindowDays   = prefs.getOrDefault(MemoryRecallKeys.TIME_WINDOW_DAYS).value();

            String  tenantId = principal.tenancyId();
            Instant since    = Instant.now().minus(Duration.ofDays(timeWindowDays));

            List<Memory> contributorHistory = s.query(
                    MemoryQuery.forEntity(
                                       DevtownMemoryDomain.CONTRIBUTOR_PREFIX + pr.contributor(),
                                       DevtownMemoryDomain.SOFTWARE_REVIEW,
                                       tenantId)
                               .withLimit(contributorLimit)
                               .withSince(since)
                               .withOrder(MemoryOrder.CHRONOLOGICAL)
                                                     );

            var modules = ModulePathNormalizer.normalize(pr.changedPaths());
            List<String> moduleIds = modules.stream()
                                            .map(m -> DevtownMemoryDomain.MODULE_PREFIX + pr.repo() + "/" + m)
                                            .limit(MemoryQuery.MAX_ENTITY_IDS)
                                            .toList();

            // No withQuestion() — entity IDs already scope to specific modules.
            // Semantic search adds no value for structured module queries.
            List<Memory> codeAreaHistory = moduleIds.isEmpty()
                                           ? List.of()
                                           : s.query(
                    MemoryQuery.forEntities(
                                       moduleIds,
                                       DevtownMemoryDomain.SOFTWARE_REVIEW,
                                       tenantId)
                               .withLimit(codeAreaLimit)
                               .withSince(since)
                               .withOrder(MemoryOrder.CHRONOLOGICAL)
                                                    );

            List<Precedent> precedents  = retrievePrecedents(pr, tenantId);
            Set<String>     activations = evaluateActivations(precedents);

            return new MemoryContext(contributorHistory, codeAreaHistory, precedents, activations);
        } catch (Exception e) {
            LOG.warnf(e, "Memory recall failed for contributor=%s — proceeding without memory",
                      pr.contributor());
            return MemoryContext.EMPTY;
        }
    }

    private List<Precedent> retrievePrecedents(PrPayload pr, String tenantId) {
        if (!cbrService.isResolvable()) {return List.of();}
        try {
            var vector = PrFeatureVector.from(
                    pr.repo(), pr.prNumber(), pr.contributor(),
                    pr.linesChanged(), pr.changedPaths());
            return cbrService.get().findSimilar(vector, pr.repo(), tenantId);
        } catch (Exception e) {
            LOG.warnf(e, "CBR retrieval failed for contributor=%s — proceeding without precedents",
                      pr.contributor());
            return List.of();
        }
    }

    private Set<String> evaluateActivations(List<Precedent> precedents) {
        if (precedents.isEmpty()) {return Set.of();}
        Preferences cbrPrefs = preferenceProvider.resolve(
                SettingsScope.of("casehubio", "devtown", "cbr"));
        var defaultThreshold = new ActivationThreshold(
                cbrPrefs.getOrDefault(CbrPreferenceKeys.PRECEDENT_ACTIVATION_MIN_FINDINGS).value(),
                cbrPrefs.getOrDefault(CbrPreferenceKeys.PRECEDENT_ACTIVATION_MIN_FRACTION).value());
        var overrides = Map.of(
                ReviewDomain.SECURITY_REVIEW, new ActivationThreshold(
                        cbrPrefs.getOrDefault(CbrPreferenceKeys.SECURITY_REVIEW_MIN_FINDINGS).value(),
                        cbrPrefs.getOrDefault(CbrPreferenceKeys.SECURITY_REVIEW_MIN_FRACTION).value()),
                ReviewDomain.ARCHITECTURE_REVIEW, new ActivationThreshold(
                        cbrPrefs.getOrDefault(CbrPreferenceKeys.ARCHITECTURE_REVIEW_MIN_FINDINGS).value(),
                        cbrPrefs.getOrDefault(CbrPreferenceKeys.ARCHITECTURE_REVIEW_MIN_FRACTION).value()));
        return PrecedentActivationPolicy.evaluate(precedents,
                                                  capability -> overrides.getOrDefault(capability, defaultThreshold));}

}
