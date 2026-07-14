package io.casehub.devtown.app.mcp;

import io.casehub.api.context.CaseContext;
import io.casehub.api.model.CaseStatus;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.platform.api.identity.CurrentPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PrReviewCaseTrackerHydratorTest {

    private PrReviewCaseTrackerHydrator hydrator;
    private PrReviewCaseTracker tracker;
    private CaseInstanceRepository repository;

    @BeforeEach
    void setUp() {
        hydrator = new PrReviewCaseTrackerHydrator();
        tracker = new PrReviewCaseTracker();
        repository = mock(CaseInstanceRepository.class);

        var principal = mock(CurrentPrincipal.class);
        when(principal.tenancyId()).thenReturn("test-tenant");

        hydrator.caseInstanceRepository = repository;
        hydrator.tracker = tracker;
        hydrator.principal = principal;
    }

    @Test
    void hydrates_running_case_into_tracker() {
        var caseId = UUID.randomUUID();
        var instance = caseInstance(caseId, CaseStatus.RUNNING, prContext("casehubio/devtown", 42));

        stubEmptyForOtherStatuses();
        when(repository.findByStatus(CaseStatus.RUNNING, "test-tenant")).thenReturn(List.of(instance));

        hydrator.hydrate();

        assertThat(tracker.activeCases()).hasSize(1);
        var info = tracker.getCase(caseId);
        assertThat(info).isNotNull();
        assertThat(info.payload().repo()).isEqualTo("casehubio/devtown");
        assertThat(info.payload().prNumber()).isEqualTo(42);
        assertThat(info.status()).isEqualTo(CaseTrackingStatus.RUNNING);
    }

    @Test
    void hydrates_waiting_case_with_correct_status() {
        var caseId = UUID.randomUUID();
        var instance = caseInstance(caseId, CaseStatus.WAITING, prContext("casehubio/devtown", 99));

        stubEmptyForOtherStatuses();
        when(repository.findByStatus(CaseStatus.WAITING, "test-tenant")).thenReturn(List.of(instance));

        hydrator.hydrate();

        var info = tracker.getCase(caseId);
        assertThat(info).isNotNull();
        assertThat(info.status()).isEqualTo(CaseTrackingStatus.WAITING);
    }

    @Test
    void hydrated_case_findable_by_pr() {
        var caseId = UUID.randomUUID();
        var instance = caseInstance(caseId, CaseStatus.RUNNING, prContext("casehubio/devtown", 42));

        stubEmptyForOtherStatuses();
        when(repository.findByStatus(CaseStatus.RUNNING, "test-tenant")).thenReturn(List.of(instance));

        hydrator.hydrate();

        var found = tracker.findActiveCaseByPr("casehubio/devtown", 42);
        assertThat(found).isPresent();
        assertThat(found.get().caseId()).isEqualTo(caseId);
    }

    @Test
    void skips_case_without_pr_context() {
        var caseId = UUID.randomUUID();
        var instance = caseInstance(caseId, CaseStatus.RUNNING, null);

        stubEmptyForOtherStatuses();
        when(repository.findByStatus(CaseStatus.RUNNING, "test-tenant")).thenReturn(List.of(instance));

        hydrator.hydrate();

        assertThat(tracker.activeCases()).isEmpty();
    }

    @Test
    void hydrates_multiple_cases_across_statuses() {
        var running = caseInstance(UUID.randomUUID(), CaseStatus.RUNNING, prContext("repo", 1));
        var waiting = caseInstance(UUID.randomUUID(), CaseStatus.WAITING, prContext("repo", 2));

        when(repository.findByStatus(CaseStatus.RUNNING, "test-tenant")).thenReturn(List.of(running));
        when(repository.findByStatus(CaseStatus.WAITING, "test-tenant")).thenReturn(List.of(waiting));
        when(repository.findByStatus(CaseStatus.STARTING, "test-tenant")).thenReturn(List.of());
        when(repository.findByStatus(CaseStatus.SUSPENDED, "test-tenant")).thenReturn(List.of());

        hydrator.hydrate();

        assertThat(tracker.activeCases()).hasSize(2);
    }

    @Test
    void extractPrPayload_reconstructs_all_fields() {
        var ctx = prContext("casehubio/devtown", 42);
        var instance = caseInstance(UUID.randomUUID(), CaseStatus.RUNNING, ctx);

        var payload = hydrator.extractPrPayload(instance);

        assertThat(payload).isNotNull();
        assertThat(payload.repo()).isEqualTo("casehubio/devtown");
        assertThat(payload.prNumber()).isEqualTo(42);
        assertThat(payload.headSha()).isEqualTo("abc123");
        assertThat(payload.baseRef()).isEqualTo("main");
        assertThat(payload.linesChanged()).isEqualTo(150);
        assertThat(payload.contributor()).isEqualTo("alice");
        assertThat(payload.changedPaths()).containsExactly("src/Main.java");
    }

    @Test
    void extractPrPayload_handles_string_prNumber() {
        var prMap = new LinkedHashMap<String, Object>();
        prMap.put("id", "99");
        prMap.put("repo", "r");
        prMap.put("headSha", "sha");
        prMap.put("baseRef", "main");
        prMap.put("linesChanged", "50");
        prMap.put("contributor", "bob");
        prMap.put("changedPaths", List.of());

        var instance = caseInstance(UUID.randomUUID(), CaseStatus.RUNNING, prMap);

        var payload = hydrator.extractPrPayload(instance);

        assertThat(payload).isNotNull();
        assertThat(payload.prNumber()).isEqualTo(99);
        assertThat(payload.linesChanged()).isEqualTo(50);
    }

    @Test
    void extractPrPayload_returns_null_for_null_context() {
        var instance = new CaseInstance();
        instance.setUuid(UUID.randomUUID());

        assertThat(hydrator.extractPrPayload(instance)).isNull();
    }

    // --- Helpers ---

    private CaseInstance caseInstance(UUID caseId, CaseStatus status, Map<String, Object> prMap) {
        var instance = new CaseInstance();
        instance.setUuid(caseId);
        instance.setState(status);
        if (prMap != null) {
            var ctx = mock(CaseContext.class);
            when(ctx.get("pr")).thenReturn(prMap);
            instance.setCaseContext(ctx);
        }
        return instance;
    }

    private Map<String, Object> prContext(String repo, int prNumber) {
        var pr = new LinkedHashMap<String, Object>();
        pr.put("id", String.valueOf(prNumber));
        pr.put("repo", repo);
        pr.put("headSha", "abc123");
        pr.put("baseRef", "main");
        pr.put("linesChanged", 150);
        pr.put("contributor", "alice");
        pr.put("changedPaths", List.of("src/Main.java"));
        return pr;
    }

    private void stubEmptyForOtherStatuses() {
        lenient().when(repository.findByStatus(any(), eq("test-tenant"))).thenReturn(List.of());
    }
}
