package io.casehub.devtown.app;

import io.casehub.devtown.domain.memory.DevtownMemoryDomain;
import io.casehub.devtown.domain.memory.DevtownMemoryKeys;
import io.casehub.devtown.domain.memory.ReviewOutcome;
import io.casehub.devtown.review.MemoryContext;
import io.casehub.devtown.review.PrPayload;
import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.neocortex.memory.MemoryAttributeKeys;
import io.casehub.neocortex.memory.MemoryInput;
import io.casehub.platform.testing.FixedCurrentPrincipal;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link CaseMemoryRecaller}.
 */
@QuarkusTest
class CaseMemoryRecallerTest {

    @Inject
    CaseMemoryRecaller recaller;

    @Inject
    Instance<CaseMemoryStore> storeInstance;

    @Inject
    FixedCurrentPrincipal principal;

    private CaseMemoryStore store;

    @BeforeEach
    void setUp() {
        principal.reset();
        principal.setTenancyId(TenancyConstants.DEFAULT_TENANT_ID);
        store = storeInstance.get();
    }

    @Test
    void recall_with_empty_store_returns_empty() {
        var pr = new PrPayload(
            "repo1",
            1,
            "abc123",
            "main",
            100,
            "alice",
            List.of("src/main/java/Foo.java")
        );

        var result = recaller.recall(pr);

        assertThat(result).isEqualTo(MemoryContext.EMPTY);
        assertThat(result.contributorHistory()).isEmpty();
        assertThat(result.codeAreaHistory()).isEmpty();
    }

    @Test
    void recall_with_contributor_history_returns_populated_context() {
        var pr = new PrPayload(
            "repo1",
            2,
            "def456",
            "main",
            200,
            "bob",
            List.of("src/main/java/Bar.java")
        );

        String tenantId = principal.tenancyId();

        // Pre-populate contributor history
        var attrs = new HashMap<String, String>();
        attrs.put(MemoryAttributeKeys.ACTOR_ID, "bob");
        attrs.put(MemoryAttributeKeys.ACTOR_ROLE, "contributor");
        attrs.put(MemoryAttributeKeys.OUTCOME, ReviewOutcome.COMPLETED.name());
        attrs.put(DevtownMemoryKeys.CAPABILITY, "style-review");
        attrs.put(DevtownMemoryKeys.OUTCOME_DETAIL, "APPROVED");
        attrs.put(DevtownMemoryKeys.PR_NUMBER, "100");
        attrs.put(DevtownMemoryKeys.PR_REPO, "repo1");

        store.store(new MemoryInput(
            "contributor:bob",
            DevtownMemoryDomain.SOFTWARE_REVIEW,
            tenantId,
            UUID.randomUUID().toString(),
            "Style review completed successfully",
            attrs
        ));

        var result = recaller.recall(pr);

        assertThat(result.contributorHistory()).hasSize(1);
        assertThat(result.contributorHistory().get(0).text()).isEqualTo("Style review completed successfully");
    }

    @Test
    void recall_toContextMap_produces_serializable_structure() {
        var pr = new PrPayload(
            "repo1",
            5,
            "mno345",
            "main",
            500,
            "eve",
            List.of("src/test/java/Test.java")
        );

        String tenantId = principal.tenancyId();

        var attrs = new HashMap<String, String>();
        attrs.put(MemoryAttributeKeys.OUTCOME, ReviewOutcome.COMPLETED.name());
        attrs.put(DevtownMemoryKeys.CAPABILITY, "test-coverage");
        attrs.put(DevtownMemoryKeys.OUTCOME_DETAIL, "passed");
        store.store(new MemoryInput(
            "contributor:eve",
            DevtownMemoryDomain.SOFTWARE_REVIEW,
            tenantId,
            UUID.randomUUID().toString(),
            "Test coverage passed",
            attrs
        ));

        var result = recaller.recall(pr);
        Map<String, Object> contextMap = result.toContextMap();

        assertThat(contextMap).containsKeys("contributorHistory", "codeAreaHistory");
        assertThat(contextMap.get("contributorHistory"))
            .isInstanceOf(List.class)
            .asList()
            .hasSize(1);

        var entry = (Map<String, Object>) ((List<?>) contextMap.get("contributorHistory")).get(0);
        assertThat(entry).containsKeys("text", "outcome", "capability", "createdAt");
        assertThat(entry.get("text")).isEqualTo("Test coverage passed");
        assertThat(entry.get("outcome")).isEqualTo(ReviewOutcome.COMPLETED.name());
        assertThat(entry.get("capability")).isEqualTo("test-coverage");
    }

    @Test
    void recall_with_code_area_history_returns_populated_context() {
        var pr = new PrPayload(
            "repo1",
            10,
            "codearea1",
            "main",
            300,
            "carol",
            List.of("app/src/main/java/Foo.java")
        );

        String tenantId = principal.tenancyId();

        var attrs = new HashMap<String, String>();
        attrs.put(MemoryAttributeKeys.OUTCOME, ReviewOutcome.COMPLETED.name());
        attrs.put(DevtownMemoryKeys.CAPABILITY, "security-review");
        attrs.put(DevtownMemoryKeys.ENTITY_TYPE, "code-area");
        store.store(new MemoryInput(
            "module:repo1/app",
            DevtownMemoryDomain.SOFTWARE_REVIEW,
            tenantId,
            UUID.randomUUID().toString(),
            "review history for app in repo1 — no critical findings",
            attrs
        ));

        var result = recaller.recall(pr);

        assertThat(result.codeAreaHistory())
            .as("code-area recall via recaller.recall()")
            .isNotEmpty();
        assertThat(result.codeAreaHistory().get(0).entityId())
            .isEqualTo("module:repo1/app");
        assertThat(result.codeAreaHistory().get(0).text())
            .isEqualTo("review history for app in repo1 — no critical findings");
    }

    @Test
    void store_and_query_directly_works() {
        String tenantId = principal.tenancyId();

        // Store a fact
        var attrs = new HashMap<String, String>();
        attrs.put(MemoryAttributeKeys.OUTCOME, ReviewOutcome.COMPLETED.name());
        store.store(new MemoryInput(
            "module:repo1/testmod",
            DevtownMemoryDomain.SOFTWARE_REVIEW,
            tenantId,
            UUID.randomUUID().toString(),
            "Test fact",
            attrs
        ));

        // Query it back immediately
        var query = io.casehub.neocortex.memory.MemoryQuery.forEntity(
            "module:repo1/testmod",
            DevtownMemoryDomain.SOFTWARE_REVIEW,
            tenantId
        ).withLimit(10);

        var results = store.query(query);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).text()).isEqualTo("Test fact");
    }
}
