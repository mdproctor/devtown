package io.casehub.devtown.app;

import io.casehub.devtown.app.mcp.PrReviewCaseTracker;
import io.casehub.devtown.domain.CiStatusClient;
import io.casehub.devtown.domain.CombinedCiStatus;
import io.casehub.devtown.review.LifecycleResult;
import io.casehub.devtown.review.PrPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PrReviewCaseServiceCiStatusTest {

    private PrReviewCaseTracker tracker;
    private PrReviewCaseHub caseHub;
    private CiStatusClient ciStatusClient;
    private PrReviewCaseService service;
    private UUID caseId;

    @BeforeEach
    void setUp() {
        tracker = new PrReviewCaseTracker();
        caseId = UUID.randomUUID();

        var payload = new PrPayload("casehubio/devtown", 42, "sha123", "main", 100, "octocat", List.of());
        tracker.register(caseId, "default", payload);

        caseHub = mock(PrReviewCaseHub.class);
        when(caseHub.signal(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(caseHub.query(eq(caseId), eq("pr.headSha"), eq(String.class)))
            .thenReturn(CompletableFuture.completedFuture("sha123"));

        ciStatusClient = mock(CiStatusClient.class);

        service = new PrReviewCaseService();
        service.caseHub = caseHub;
        service.caseTracker = tracker;
        service.ciStatusClient = ciStatusClient;
        service.ciMode = "external";
    }

    @Test
    void signalCiStatus_noActiveCase_returnsNoActiveCase() {
        var result = service.signalCiStatus("casehubio/other", 99, "sha123", 1001, "success");
        assertThat(result).isEqualTo(LifecycleResult.NO_ACTIVE_CASE);
        verifyNoInteractions(ciStatusClient);
    }

    @Test
    void signalCiStatus_staleSha_returnsStaleEvent() {
        var result = service.signalCiStatus("casehubio/devtown", 42, "oldsha", 1001, "success");
        assertThat(result).isEqualTo(LifecycleResult.STALE_EVENT);
        verify(caseHub, never()).signal(eq(caseId), eq("ci.status"), any());
        verifyNoInteractions(ciStatusClient);
    }

    @Test
    void signalCiStatus_githubConfirmsPassing() {
        when(ciStatusClient.getCombinedStatus("casehubio", "devtown", "sha123"))
            .thenReturn(new CombinedCiStatus.Passing());

        var result = service.signalCiStatus("casehubio/devtown", 42, "sha123", 1001, "success");

        assertThat(result).isEqualTo(LifecycleResult.UPDATED);
        verify(caseHub).signal(caseId, "ci.status", "passing");
    }

    @Test
    void signalCiStatus_githubSaysPending() {
        when(ciStatusClient.getCombinedStatus("casehubio", "devtown", "sha123"))
            .thenReturn(new CombinedCiStatus.Pending(1, 3));

        var result = service.signalCiStatus("casehubio/devtown", 42, "sha123", 1001, "success");

        assertThat(result).isEqualTo(LifecycleResult.UPDATED);
        verify(caseHub, never()).signal(eq(caseId), eq("ci.status"), any());
    }

    @Test
    void signalCiStatus_githubConfirmsFailing() {
        when(ciStatusClient.getCombinedStatus("casehubio", "devtown", "sha123"))
            .thenReturn(new CombinedCiStatus.Failing("1 of 3 suites failed"));

        var result = service.signalCiStatus("casehubio/devtown", 42, "sha123", 1001, "failure");

        assertThat(result).isEqualTo(LifecycleResult.UPDATED);
        verify(caseHub).signal(caseId, "ci.status", "failing");
    }

    @Test
    void signalCiStatus_githubUnavailable() {
        when(ciStatusClient.getCombinedStatus("casehubio", "devtown", "sha123"))
            .thenReturn(new CombinedCiStatus.Unavailable("api error: HTTP 403"));

        var result = service.signalCiStatus("casehubio/devtown", 42, "sha123", 1001, "success");

        assertThat(result).isEqualTo(LifecycleResult.UPDATED);
        verify(caseHub, never()).signal(eq(caseId), eq("ci.status"), any());
    }

    @Test
    void signalCiStatus_alwaysRecordsSuiteRegardlessOfCombinedStatus() {
        var variants = List.<CombinedCiStatus>of(
            new CombinedCiStatus.Passing(),
            new CombinedCiStatus.Pending(1, 3),
            new CombinedCiStatus.Failing("1 of 3 failed"),
            new CombinedCiStatus.Unavailable("network error")
        );

        long suiteId = 1001;
        for (var variant : variants) {
            setUp();
            when(ciStatusClient.getCombinedStatus("casehubio", "devtown", "sha123"))
                .thenReturn(variant);

            service.signalCiStatus("casehubio/devtown", 42, "sha123", suiteId, "success");

            verify(caseHub).signal(eq(caseId), eq("ci.suites." + suiteId), any(Map.class));
            suiteId++;
        }
    }

    @Test
    void signalCheckRun_noActiveCase_returnsNoActiveCase() {
        var result = service.signalCheckRun("casehubio/other", 99, "sha123", "lint", "success", java.time.Instant.now());
        assertThat(result).isEqualTo(LifecycleResult.NO_ACTIVE_CASE);
    }

    @Test
    void signalCheckRun_staleSha_returnsStaleEvent() {
        var result = service.signalCheckRun("casehubio/devtown", 42, "oldsha", "lint", "success", java.time.Instant.now());
        assertThat(result).isEqualTo(LifecycleResult.STALE_EVENT);
    }

    @Test
    void signalCheckRun_validSha_signalsCheckResult() {
        var completedAt = java.time.Instant.parse("2026-06-21T12:00:00Z");
        var result = service.signalCheckRun("casehubio/devtown", 42, "sha123", "lint", "success", completedAt);
        assertThat(result).isEqualTo(LifecycleResult.UPDATED);
        verify(caseHub).signal(eq(caseId), eq("ci.checks.lint"), eq(Map.of(
            "conclusion", "success",
            "completedAt", completedAt.toString()
        )));
    }

    @Test
    void signalCheckRun_doesNotChangeCiStatus() {
        service.signalCheckRun("casehubio/devtown", 42, "sha123", "lint", "failure", java.time.Instant.now());
        verify(caseHub, never()).signal(eq(caseId), eq("ci.status"), any());
    }
}
