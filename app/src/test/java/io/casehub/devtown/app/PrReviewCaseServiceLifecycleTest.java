package io.casehub.devtown.app;

import io.casehub.devtown.app.mcp.PrReviewCaseTracker;
import io.casehub.devtown.review.LifecycleResult;
import io.casehub.devtown.review.PrPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PrReviewCaseServiceLifecycleTest {

    private PrReviewCaseTracker tracker;
    private PrReviewCaseHub caseHub;
    private PrReviewCaseService service;
    private UUID caseId;

    @BeforeEach
    void setUp() {
        tracker = new PrReviewCaseTracker();
        caseId = UUID.randomUUID();

        var payload = new PrPayload("casehubio/devtown", 42, "oldsha", "main", 100, "octocat", List.of());
        tracker.register(caseId, "default", payload);

        caseHub = mock(PrReviewCaseHub.class);
        when(caseHub.signal(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));

        service = new PrReviewCaseService();
        service.caseHub = caseHub;
        service.caseTracker = tracker;
    }

    @Test
    void revisePr_signalsMetadataBeforeInvalidation() {
        service.revisePr("casehubio/devtown", 42, "newsha", 200);

        InOrder inOrder = inOrder(caseHub);
        inOrder.verify(caseHub).signal(eq(caseId), eq("pr.headSha"), eq("newsha"));
        inOrder.verify(caseHub).signal(eq(caseId), eq("pr.linesChanged"), eq(200));
        inOrder.verify(caseHub).signal(eq(caseId), eq("codeAnalysis"), isNull());
    }

    @Test
    void revisePr_nullsAllAnalysisFields() {
        service.revisePr("casehubio/devtown", 42, "newsha", 200);

        verify(caseHub).signal(caseId, "codeAnalysis", null);
        verify(caseHub).signal(caseId, "securityReview", null);
        verify(caseHub).signal(caseId, "architectureReview", null);
        verify(caseHub).signal(caseId, "styleCheck", null);
        verify(caseHub).signal(caseId, "testCoverage", null);
        verify(caseHub).signal(caseId, "performanceAnalysis", null);
    }

    @Test
    void revisePr_doesNotNullHumanApproval() {
        service.revisePr("casehubio/devtown", 42, "newsha", 200);

        verify(caseHub, never()).signal(eq(caseId), eq("humanApproval"), any());
    }

    @Test
    void revisePr_returnsUpdated_whenCaseExists() {
        var result = service.revisePr("casehubio/devtown", 42, "newsha", 200);
        assertThat(result).isEqualTo(LifecycleResult.UPDATED);
    }

    @Test
    void revisePr_returnsNoActiveCase_whenNoCaseExists() {
        var result = service.revisePr("casehubio/other", 99, "sha", 10);
        assertThat(result).isEqualTo(LifecycleResult.NO_ACTIVE_CASE);
    }

    @Test
    void closePr_notMerged_signalsClosedStatus() {
        service.closePr("casehubio/devtown", 42, false);

        verify(caseHub).signal(caseId, "pr.status", "closed");
    }

    @Test
    void closePr_merged_signalsMergedStatus() {
        service.closePr("casehubio/devtown", 42, true);

        verify(caseHub).signal(caseId, "pr.status", "merged");
    }

    @Test
    void closePr_returnsNoActiveCase_whenNoCaseExists() {
        var result = service.closePr("casehubio/other", 99, false);
        assertThat(result).isEqualTo(LifecycleResult.NO_ACTIVE_CASE);
    }

    @Test
    void startReview_existingCase_delegatesToRevisePr() {
        service.startReview(new PrPayload("casehubio/devtown", 42, "newsha", "main", 200, "octocat", List.of()));

        verify(caseHub).signal(eq(caseId), eq("pr.headSha"), eq("newsha"));
        verify(caseHub, never()).startCase(any());
    }

    @Test
    void revisePr_externalMode_resetsCiToPending() {
        service.ciMode = "external";
        service.revisePr("casehubio/devtown", 42, "newsha", 200);

        verify(caseHub).signal(eq(caseId), eq("ci"), eq(Map.of("status", "pending")));
    }

    @Test
    void revisePr_dispatchedMode_nullsCi() {
        service.ciMode = "dispatched";
        service.revisePr("casehubio/devtown", 42, "newsha", 200);

        verify(caseHub).signal(eq(caseId), eq("ci"), isNull());
    }

    @Test
    void revisePr_updatesTrackerHeadSha() {
        service.ciMode = "external";
        service.revisePr("casehubio/devtown", 42, "newsha", 200);

        assertThat(tracker.getCase(caseId).payload().headSha()).isEqualTo("newsha");
    }

    @Test
    void revisePr_ciInvalidation_afterAnalysisInvalidation() {
        service.ciMode = "external";
        service.revisePr("casehubio/devtown", 42, "newsha", 200);

        InOrder inOrder = inOrder(caseHub);
        inOrder.verify(caseHub).signal(eq(caseId), eq("performanceAnalysis"), isNull());
        inOrder.verify(caseHub).signal(eq(caseId), eq("ci"), eq(Map.of("status", "pending")));
    }
}
