package io.casehub.devtown.app;

import io.casehub.devtown.domain.memory.DevtownMemoryDomain;
import io.casehub.devtown.domain.memory.DevtownMemoryKeys;
import io.casehub.devtown.domain.memory.ReviewOutcome;
import io.casehub.devtown.review.PrPayload;
import io.casehub.devtown.review.ReviewCompletedEvent;
import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.neocortex.memory.MemoryAttributeKeys;
import io.casehub.neocortex.memory.MemoryInput;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

class CaseMemoryEmitterTest {

    private CaseMemoryEmitter emitter;
    private CaseMemoryStore store;
    private Instance<CaseMemoryStore> storeInstance;

    @BeforeEach
    void setUp() {
        emitter = new CaseMemoryEmitter();
        store = mock(CaseMemoryStore.class);
        storeInstance = mockInstance(store);
        emitter.store = storeInstance;
    }

    @Test
    void emits_contributor_reviewer_and_module_facts() {
        var event = sampleEvent("security-review", "mdproctor", List.of("app/src/main/Foo.java"));

        emitter.onReviewCompleted(event);

        var captor = ArgumentCaptor.forClass(List.class);
        verify(store).storeAll(captor.capture());
        var facts = (List<MemoryInput>) captor.getValue();

        assertThat(facts).hasSize(3); // contributor + reviewer + module
    }

    @Test
    void contributor_fact_has_correct_entityId() {
        var event = sampleEvent("security-review", "mdproctor", List.of("app/src/main/Foo.java"));

        emitter.onReviewCompleted(event);

        var captor = ArgumentCaptor.forClass(List.class);
        verify(store).storeAll(captor.capture());
        var facts = (List<MemoryInput>) captor.getValue();

        var contributorFact = facts.stream()
            .filter(f -> f.attributes().get(DevtownMemoryKeys.ENTITY_TYPE).equals("contributor"))
            .findFirst().orElseThrow();

        assertThat(contributorFact.entityId()).isEqualTo("contributor:mdproctor");
    }

    @Test
    void reviewer_fact_has_correct_entityId() {
        var event = sampleEvent("security-review", "mdproctor", List.of("app/src/main/Foo.java"));

        emitter.onReviewCompleted(event);

        var captor = ArgumentCaptor.forClass(List.class);
        verify(store).storeAll(captor.capture());
        var facts = (List<MemoryInput>) captor.getValue();

        var reviewerFact = facts.stream()
            .filter(f -> f.attributes().get(DevtownMemoryKeys.ENTITY_TYPE).equals("reviewer"))
            .findFirst().orElseThrow();

        assertThat(reviewerFact.entityId()).isEqualTo("reviewer:security-agent-1");
    }

    @Test
    void code_area_fact_has_module_level_entityId() {
        var event = sampleEvent("security-review", "mdproctor", List.of("app/src/main/Foo.java"));

        emitter.onReviewCompleted(event);

        var captor = ArgumentCaptor.forClass(List.class);
        verify(store).storeAll(captor.capture());
        var facts = (List<MemoryInput>) captor.getValue();

        var codeAreaFact = facts.stream()
            .filter(f -> f.attributes().get(DevtownMemoryKeys.ENTITY_TYPE).equals("code-area"))
            .findFirst().orElseThrow();

        assertThat(codeAreaFact.entityId()).isEqualTo("module:casehubio/devtown/app");
    }

    @Test
    void all_facts_have_correct_domain_and_tenant() {
        var event = sampleEvent("security-review", "mdproctor", List.of("app/src/main/Foo.java"));

        emitter.onReviewCompleted(event);

        var captor = ArgumentCaptor.forClass(List.class);
        verify(store).storeAll(captor.capture());
        var facts = (List<MemoryInput>) captor.getValue();

        assertThat(facts).allSatisfy(fact -> {
            assertThat(fact.domain()).isEqualTo(DevtownMemoryDomain.SOFTWARE_REVIEW);
            assertThat(fact.tenantId()).isEqualTo("tenant-123");
        });
    }

    @Test
    void all_facts_have_caseId_set() {
        var caseId = UUID.randomUUID();
        var event = new ReviewCompletedEvent(
            caseId,
            "tenant-123",
            "security-review",
            "security-agent-1",
            ReviewOutcome.COMPLETED,
            "approved",
            new PrPayload("casehubio/devtown", 45, "abc123", "main", 342, "mdproctor", List.of("app/src/main/Foo.java"))
        );

        emitter.onReviewCompleted(event);

        var captor = ArgumentCaptor.forClass(List.class);
        verify(store).storeAll(captor.capture());
        var facts = (List<MemoryInput>) captor.getValue();

        assertThat(facts).allSatisfy(fact -> {
            assertThat(fact.caseId()).isEqualTo(caseId.toString());
        });
    }

    @Test
    void all_facts_use_platform_reserved_keys() {
        var event = sampleEvent("security-review", "mdproctor", List.of("app/src/main/Foo.java"));

        emitter.onReviewCompleted(event);

        var captor = ArgumentCaptor.forClass(List.class);
        verify(store).storeAll(captor.capture());
        var facts = (List<MemoryInput>) captor.getValue();

        assertThat(facts).allSatisfy(fact -> {
            assertThat(fact.attributes()).containsKey(MemoryAttributeKeys.OUTCOME);
            assertThat(fact.attributes()).containsKey(MemoryAttributeKeys.ACTOR_ID);
            assertThat(fact.attributes()).containsKey(MemoryAttributeKeys.ACTOR_ROLE);
        });
    }

    @Test
    void multiple_modules_deduplicated() {
        var event = sampleEvent("security-review", "mdproctor",
            List.of("app/src/main/Foo.java", "app/src/test/Bar.java", "domain/src/main/Baz.java"));

        emitter.onReviewCompleted(event);

        var captor = ArgumentCaptor.forClass(List.class);
        verify(store).storeAll(captor.capture());
        var facts = (List<MemoryInput>) captor.getValue();

        // contributor + reviewer + 2 modules (app, domain)
        assertThat(facts).hasSize(4);

        var codeAreaFacts = facts.stream()
            .filter(f -> f.attributes().get(DevtownMemoryKeys.ENTITY_TYPE).equals("code-area"))
            .toList();

        assertThat(codeAreaFacts).hasSize(2);
        assertThat(codeAreaFacts)
            .extracting(MemoryInput::entityId)
            .containsExactlyInAnyOrder("module:casehubio/devtown/app", "module:casehubio/devtown/domain");
    }

    @Test
    void text_is_natural_language() {
        var event = sampleEvent("security-review", "mdproctor", List.of("app/src/main/Foo.java"));

        emitter.onReviewCompleted(event);

        var captor = ArgumentCaptor.forClass(List.class);
        verify(store).storeAll(captor.capture());
        var facts = (List<MemoryInput>) captor.getValue();

        assertThat(facts).allSatisfy(fact -> {
            // Natural language: contains spaces, no = or { characters
            assertThat(fact.text()).contains(" ");
            assertThat(fact.text()).doesNotContain("=");
            assertThat(fact.text()).doesNotContain("{");
        });
    }

    @Test
    void code_area_text_does_not_contain_contributor_login() {
        var event = sampleEvent("security-review", "mdproctor", List.of("app/src/main/Foo.java"));

        emitter.onReviewCompleted(event);

        var captor = ArgumentCaptor.forClass(List.class);
        verify(store).storeAll(captor.capture());
        var facts = (List<MemoryInput>) captor.getValue();

        var codeAreaFact = facts.stream()
            .filter(f -> f.attributes().get(DevtownMemoryKeys.ENTITY_TYPE).equals("code-area"))
            .findFirst().orElseThrow();

        // GDPR: code area facts must not contain contributor login
        assertThat(codeAreaFact.text()).doesNotContain("mdproctor");
    }

    @Test
    void empty_changedPaths_produces_only_contributor_and_reviewer_facts() {
        var event = sampleEvent("security-review", "mdproctor", List.of());

        emitter.onReviewCompleted(event);

        var captor = ArgumentCaptor.forClass(List.class);
        verify(store).storeAll(captor.capture());
        var facts = (List<MemoryInput>) captor.getValue();

        assertThat(facts).hasSize(2);
        assertThat(facts)
            .extracting(f -> f.attributes().get(DevtownMemoryKeys.ENTITY_TYPE))
            .containsExactlyInAnyOrder("contributor", "reviewer");
    }

    @Test
    void store_failure_is_swallowed() {
        var event = sampleEvent("security-review", "mdproctor", List.of("app/src/main/Foo.java"));
        doThrow(new RuntimeException("DB down")).when(store).storeAll(anyList());

        assertThatCode(() -> emitter.onReviewCompleted(event))
            .doesNotThrowAnyException();
    }

    @Test
    void store_not_resolvable_skips_storeAll() {
        var event = sampleEvent("security-review", "mdproctor", List.of("app/src/main/Foo.java"));

        // Mock isResolvable() to return false
        Instance<CaseMemoryStore> unresolvableInstance = mock(Instance.class);
        when(unresolvableInstance.isResolvable()).thenReturn(false);
        emitter.store = unresolvableInstance;

        emitter.onReviewCompleted(event);

        verify(store, never()).storeAll(anyList());
    }

    // --- Helpers ---

    private ReviewCompletedEvent sampleEvent(String capability, String contributor, List<String> changedPaths) {
        return new ReviewCompletedEvent(
            UUID.randomUUID(),
            "tenant-123",
            capability,
            "security-agent-1",
            ReviewOutcome.COMPLETED,
            "approved",
            new PrPayload("casehubio/devtown", 45, "abc123", "main", 342, contributor, changedPaths)
        );
    }

    @SuppressWarnings("unchecked")
    private Instance<CaseMemoryStore> mockInstance(CaseMemoryStore store) {
        Instance<CaseMemoryStore> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(store);
        return instance;
    }
}
