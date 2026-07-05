package io.casehub.devtown.app;

import io.casehub.devtown.domain.memory.DevtownMemoryDomain;
import io.casehub.devtown.domain.memory.MemoryRecallKeys;
import io.casehub.devtown.review.PrPayload;
import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.neocortex.memory.Memory;
import io.casehub.neocortex.memory.MemoryInput;
import io.casehub.neocortex.memory.MemoryQuery;
import io.casehub.platform.api.preferences.MapPreferences;
import io.casehub.platform.api.preferences.PreferenceProvider;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CaseMemoryRecallerPreferenceTest {

    private static final PreferenceProvider LIMIT_ONE = scope ->
        new MapPreferences(Map.of(
            MemoryRecallKeys.CONTRIBUTOR_LIMIT.qualifiedName(), "1",
            MemoryRecallKeys.CODE_AREA_LIMIT.qualifiedName(), "2",
            MemoryRecallKeys.TIME_WINDOW_DAYS.qualifiedName(), "30"
        ));

    private SimpleMemoryStore store;
    private CaseMemoryRecaller recaller;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        store = new SimpleMemoryStore();
        Instance<CaseMemoryStore> storeInstance = mock(Instance.class);
        when(storeInstance.isResolvable()).thenReturn(true);
        when(storeInstance.get()).thenReturn(store);

        var principal = new io.casehub.platform.testing.FixedCurrentPrincipal();
        principal.setTenancyId(TenancyConstants.DEFAULT_TENANT_ID);

        recaller = new CaseMemoryRecaller(storeInstance, principal, LIMIT_ONE);
    }

    @Test
    void contributorLimitRespected() {
        String tenantId = TenancyConstants.DEFAULT_TENANT_ID;
        for (int i = 0; i < 5; i++) {
            store.store(new MemoryInput(
                "contributor:alice",
                DevtownMemoryDomain.SOFTWARE_REVIEW,
                tenantId,
                UUID.randomUUID().toString(),
                "Review " + i,
                Map.of()));
        }

        var pr = new PrPayload("repo1", 1, "sha", "main", 100, "alice", List.of());
        var result = recaller.recall(pr);

        assertThat(result.contributorHistory()).hasSize(1);
    }

    @Test
    void codeAreaLimitRespected() {
        String tenantId = TenancyConstants.DEFAULT_TENANT_ID;
        for (int i = 0; i < 5; i++) {
            store.store(new MemoryInput(
                "module:repo1/app",
                DevtownMemoryDomain.SOFTWARE_REVIEW,
                tenantId,
                UUID.randomUUID().toString(),
                "review history for app in repo1 entry " + i,
                Map.of()));
        }

        var pr = new PrPayload("repo1", 1, "sha", "main", 100, "bob",
            List.of("app/src/main/java/Foo.java"));
        var result = recaller.recall(pr);

        assertThat(result.codeAreaHistory()).hasSize(2);
    }

    /**
     * Minimal in-memory store for unit testing — no tenant assertion needed.
     */
    private static class SimpleMemoryStore implements CaseMemoryStore {
        private final List<Memory> memories = new ArrayList<>();

        @Override
        public String store(final MemoryInput input) {
            String id = UUID.randomUUID().toString();
            memories.add(new Memory(id, input.entityId(), input.domain(),
                input.tenantId(), input.caseId(), input.text(),
                input.attributes(), Instant.now()));
            return id;
        }

        @Override
        public List<Memory> query(final MemoryQuery query) {
            return memories.stream()
                .filter(m -> query.entityIds().contains(m.entityId()))
                .filter(m -> query.domain().equals(m.domain()))
                .filter(m -> query.tenantId().equals(m.tenantId()))
                .filter(m -> query.since() == null || !m.createdAt().isBefore(query.since()))
                .filter(m -> query.question() == null
                    || m.text().toLowerCase().contains(query.question().toLowerCase()))
                .limit(query.limit())
                .toList();
        }

        @Override
        public int erase(final io.casehub.neocortex.memory.EraseRequest request) { return 0; }
    }
}
