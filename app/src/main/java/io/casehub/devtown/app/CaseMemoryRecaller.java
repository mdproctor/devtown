package io.casehub.devtown.app;

import io.casehub.devtown.domain.memory.DevtownMemoryDomain;
import io.casehub.devtown.domain.memory.MemoryRecallKeys;
import io.casehub.devtown.domain.memory.ModulePathNormalizer;
import io.casehub.devtown.review.MemoryContext;
import io.casehub.devtown.review.PrPayload;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.neocortex.memory.Memory;
import io.casehub.neocortex.memory.MemoryOrder;
import io.casehub.neocortex.memory.MemoryQuery;
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

    private static final Logger LOG = Logger.getLogger(CaseMemoryRecaller.class);
    private static final SettingsScope RECALL_SCOPE =
        SettingsScope.of("casehubio", "devtown", "memory-recall");

    private final Instance<CaseMemoryStore> store;
    private final CurrentPrincipal principal;
    private final PreferenceProvider preferenceProvider;

    @Inject
    public CaseMemoryRecaller(final Instance<CaseMemoryStore> store,
                              final CurrentPrincipal principal,
                              final PreferenceProvider preferenceProvider) {
        this.store = store;
        this.principal = principal;
        this.preferenceProvider = preferenceProvider;
    }

    public MemoryContext recall(final PrPayload pr) {
        if (!store.isResolvable()) {
            return MemoryContext.EMPTY;
        }

        try {
            var s = store.get();
            Preferences prefs = preferenceProvider.resolve(RECALL_SCOPE);
            int contributorLimit = prefs.getOrDefault(MemoryRecallKeys.CONTRIBUTOR_LIMIT).value();
            int codeAreaLimit = prefs.getOrDefault(MemoryRecallKeys.CODE_AREA_LIMIT).value();
            int timeWindowDays = prefs.getOrDefault(MemoryRecallKeys.TIME_WINDOW_DAYS).value();

            String tenantId = principal.tenancyId();
            Instant since = Instant.now().minus(Duration.ofDays(timeWindowDays));

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
            // Semantic search adds no value for structured module queries, and
            // InMemoryMemoryStore uses substring matching that fails silently
            // when the question text isn't a substring of the stored fact text.
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

            return new MemoryContext(contributorHistory, codeAreaHistory);
        } catch (Exception e) {
            LOG.warnf(e, "Memory recall failed for contributor=%s — proceeding without memory",
                pr.contributor());
            return MemoryContext.EMPTY;
        }
    }
}
