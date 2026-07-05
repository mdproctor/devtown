package io.casehub.devtown.app.mcp;

import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.api.model.event.CaseEventLogRecord;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.devtown.app.MergeQueueService;
import io.casehub.devtown.app.PrReviewCaseHub;
import io.casehub.devtown.app.governance.GovernanceQueryService;
import io.casehub.devtown.domain.queue.PriorityLane;
import io.casehub.devtown.review.PrPayload;
import io.casehub.ledger.runtime.service.LedgerProvExportService;
import io.casehub.ledger.runtime.service.TrustGateService;
import io.casehub.ledger.runtime.service.federation.TrustExportService;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.qhorus.api.message.Commitment;
import io.casehub.qhorus.api.store.CommitmentStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DevtownMcpToolsTest {

    @Mock
    GovernanceQueryService governanceQuery;

    @Mock
    PrReviewCaseTracker tracker;

    @Mock
    CaseHubRuntime caseHubRuntime;

    @Mock
    CommitmentStore commitmentStore;

    @Mock
    TrustGateService trustGateService;

    @Mock
    TrustExportService trustExportService;

    @Mock
    LedgerProvExportService provExportService;

    @Mock
    Instance<CaseMemoryStore> memoryStoreInstance;

    @Mock
    Instance<io.casehub.work.runtime.repository.WorkItemStore> workItemStoreInstance;

    @Mock
    MergeQueueService mergeQueueService;

    @Mock
    PrReviewCaseHub caseHub;

    @Mock
    CurrentPrincipal principal;

    @InjectMocks
    DevtownMcpTools tools;

    private UUID testCaseId;
    private PrPayload testPayload;
    private CaseInfo testCaseInfo;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(principal.tenancyId()).thenReturn(TenancyConstants.DEFAULT_TENANT_ID);
        objectMapper = new ObjectMapper();
        testCaseId = UUID.randomUUID();
        testPayload = new PrPayload(
            "casehubio/devtown",
            42,
            "abc123",
            "main",
            250,
            "alice",
            List.of("src/Main.java", "src/Test.java")
        );
        Instant now = Instant.now();
        testCaseInfo = new CaseInfo(
            testCaseId,
            TenancyConstants.DEFAULT_TENANT_ID,
            testPayload,
            now,
            now,
            CaseTrackingStatus.RUNNING
        );
    }

    @Test
    void getQueueStatus_emptyTracker_returnsZeroCounts() {
        GovernanceQueryService.QueueStatus emptyStatus = new GovernanceQueryService.QueueStatus(
            0, Map.of(), List.of()
        );
        when(governanceQuery.queueStatus()).thenReturn(emptyStatus);

        GovernanceQueryService.QueueStatus status = tools.getQueueStatus();

        assertThat(status.total()).isZero();
        assertThat(status.countsByStatus()).isEmpty();
        assertThat(status.reviews()).isEmpty();
    }

    @Test
    void getQueueStatus_withRegisteredCases_returnsCorrectCountsAndReviews() {
        Instant now = Instant.now();
        var review1 = new GovernanceQueryService.ActiveReview(
            UUID.randomUUID(), "casehubio/devtown", 42, "alice", 250, "RUNNING", now, now
        );
        var review2 = new GovernanceQueryService.ActiveReview(
            UUID.randomUUID(), "casehubio/devtown", 42, "alice", 250, "WAITING", now, now
        );
        Map<String, Integer> counts = Map.of("RUNNING", 1, "WAITING", 1);
        GovernanceQueryService.QueueStatus status = new GovernanceQueryService.QueueStatus(
            2, counts, List.of(review1, review2)
        );
        when(governanceQuery.queueStatus()).thenReturn(status);

        GovernanceQueryService.QueueStatus result = tools.getQueueStatus();

        assertThat(result.total()).isEqualTo(2);
        assertThat(result.countsByStatus()).containsEntry("RUNNING", 1);
        assertThat(result.countsByStatus()).containsEntry("WAITING", 1);
        assertThat(result.reviews()).hasSize(2);
        assertThat(result.reviews().get(0).repo()).isEqualTo("casehubio/devtown");
        assertThat(result.reviews().get(0).prNumber()).isEqualTo(42);
    }

    @Test
    void getRecentEvents_delegatesToTracker() {
        Instant now = Instant.now();
        TrackedEvent event = new TrackedEvent(
            now,
            testCaseId,
            "casehubio/devtown",
            42,
            "CaseStarted",
            "RUNNING",
            "system"
        );
        when(governanceQuery.recentEvents(50, null)).thenReturn(List.of(event));

        List<TrackedEvent> events = tools.getRecentEvents(null, null);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).eventType()).isEqualTo("CaseStarted");
        verify(governanceQuery).recentEvents(50, null);
    }

    @Test
    void getRecentEvents_withLimitAndSince_passesParameters() {
        Instant since = Instant.now().minus(1, ChronoUnit.HOURS);
        when(governanceQuery.recentEvents(eq(10), any(Instant.class))).thenReturn(List.of());

        List<TrackedEvent> events = tools.getRecentEvents(10, since.toString());

        verify(governanceQuery).recentEvents(eq(10), argThat(instant ->
            instant != null && instant.equals(since)
        ));
    }

    @Test
    void getSystemHealth_assemblesFromMultipleSources() {
        GovernanceQueryService.SystemHealth health = new GovernanceQueryService.SystemHealth(
            1, 0, Map.of(), 0, 0
        );
        when(governanceQuery.systemHealth()).thenReturn(health);

        GovernanceQueryService.SystemHealth result = tools.getSystemHealth();

        assertThat(result.activeCases()).isEqualTo(1);
        assertThat(result.fleetSize()).isZero();
        assertThat(result.openCommitments()).isZero();
    }

    @Test
    void listProblems_findsStalledCases() {
        Instant staleTime = Instant.now().minus(90, ChronoUnit.MINUTES);
        var problem = new GovernanceQueryService.Problem(
            "stalled_case",
            "warning",
            "Case stalled for 90 minutes",
            testCaseId,
            null,
            staleTime
        );
        when(governanceQuery.problems(60)).thenReturn(List.of(problem));

        List<GovernanceQueryService.Problem> problems = tools.listProblems(60);

        assertThat(problems).hasSize(1);
        assertThat(problems.get(0).category()).isEqualTo("stalled_case");
        assertThat(problems.get(0).severity()).isEqualTo("warning");
        assertThat(problems.get(0).caseId()).isEqualTo(testCaseId);
    }

    @Test
    void listProblems_findsExpiredCommitments() {
        Instant expired = Instant.now().minus(10, ChronoUnit.MINUTES);
        var problem = new GovernanceQueryService.Problem(
            "expired_commitment",
            "error",
            "Commitment expired 10 minutes ago",
            null,
            "reviewer-1",
            expired
        );
        when(governanceQuery.problems(60)).thenReturn(List.of(problem));

        List<GovernanceQueryService.Problem> problems = tools.listProblems(60);

        assertThat(problems).hasSize(1);
        assertThat(problems.get(0).category()).isEqualTo("expired_commitment");
        assertThat(problems.get(0).severity()).isEqualTo("error");
        assertThat(problems.get(0).actorId()).isEqualTo("reviewer-1");
    }

    @Test
    void listProblems_findsFailedWorkers() {
        Instant now = Instant.now();
        var problem = new GovernanceQueryService.Problem(
            "worker_failure",
            "error",
            "Worker failed",
            testCaseId,
            "reviewer-1",
            now
        );
        when(governanceQuery.problems(60)).thenReturn(List.of(problem));

        List<GovernanceQueryService.Problem> problems = tools.listProblems(60);

        assertThat(problems).hasSize(1);
        assertThat(problems.get(0).category()).isEqualTo("worker_failure");
        assertThat(problems.get(0).actorId()).isEqualTo("reviewer-1");
    }

    @Test
    void inspectReview_unknownCase_throwsIllegalArgumentException() {
        when(governanceQuery.reviewDetail(eq(testCaseId), anyString()))
            .thenThrow(new IllegalArgumentException("Case not found: " + testCaseId));

        assertThatThrownBy(() -> tools.inspectReview(testCaseId.toString()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Case not found");
    }

    @Test
    void inspectReview_knownCase_returnsDetailWithTimeline() {
        Instant now = Instant.now();
        var timelineEvent = new GovernanceQueryService.EventEntry(
            now, "CASE_STARTED", "system", "Case started"
        );
        var capability = new GovernanceQueryService.CapabilityStatus(
            "code-analysis", "COMPLETED", "APPROVED", now.plusSeconds(10)
        );
        var detail = new GovernanceQueryService.ReviewDetail(
            testCaseId,
            testPayload,
            List.of(timelineEvent),
            List.of(capability)
        );
        when(governanceQuery.reviewDetail(eq(testCaseId), anyString())).thenReturn(detail);

        GovernanceQueryService.ReviewDetail result = tools.inspectReview(testCaseId.toString());

        assertThat(result.caseId()).isEqualTo(testCaseId);
        assertThat(result.pr()).isEqualTo(testPayload);
        assertThat(result.timeline()).hasSize(1);
        assertThat(result.capabilities()).hasSize(1);
        assertThat(result.capabilities().get(0).name()).isEqualTo("code-analysis");
        assertThat(result.capabilities().get(0).status()).isEqualTo("COMPLETED");
    }

    @Test
    void getReviewerHealth_returnsCommitmentCountAndTrustScores() {
        var health = new GovernanceQueryService.ReviewerHealth(
            "reviewer-1",
            1,
            Map.of("code-analysis", 0.75, "security-review", 0.82),
            Map.of("review-thoroughness", 0.80),
            5,
            List.of()
        );
        when(governanceQuery.reviewerHealth("reviewer-1")).thenReturn(health);

        GovernanceQueryService.ReviewerHealth result = tools.getReviewerHealth("reviewer-1");

        assertThat(result.reviewerId()).isEqualTo("reviewer-1");
        assertThat(result.openCommitments()).isEqualTo(1);
        assertThat(result.trustByCapability()).containsEntry("code-analysis", 0.75);
        assertThat(result.trustByDimension()).containsEntry("review-thoroughness", 0.80);
        assertThat(result.totalDecisions()).isGreaterThan(0);
    }

    @Test
    void getPriorDecisions_storeNotResolvable_returnsEmptyList() {
        when(memoryStoreInstance.isResolvable()).thenReturn(false);

        List<DevtownMcpTools.PriorDecision> decisions = tools.getPriorDecisions(
            "casehubio/devtown",
            "src/Main.java"
        );

        assertThat(decisions).isEmpty();
    }

    @Test
    void retryReviewer_unknownCase_throws() {
        when(tracker.getCase(testCaseId)).thenReturn(null);

        assertThatThrownBy(() -> tools.retryReviewer(testCaseId.toString(), "code-analysis"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Case not found");
    }

    @Test
    void retryReviewer_unknownCapability_throws() {
        when(tracker.getCase(testCaseId)).thenReturn(testCaseInfo);

        assertThatThrownBy(() -> tools.retryReviewer(testCaseId.toString(), "invalid-capability"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown capability");
    }

    @Test
    void retryReviewer_validInputs_signalsCase() {
        when(tracker.getCase(testCaseId)).thenReturn(testCaseInfo);

        DevtownMcpTools.RetryResult result = tools.retryReviewer(
            testCaseId.toString(),
            "code-analysis"
        );

        assertThat(result.caseId()).isEqualTo(testCaseId);
        assertThat(result.capability()).isEqualTo("code-analysis");
        assertThat(result.status()).isEqualTo("RETRY_SIGNALED");
        verify(caseHubRuntime).signal(testCaseId, "codeAnalysis", null);
    }

    @Test
    void rerouteReview_unknownCase_throws() {
        when(tracker.getCase(testCaseId)).thenReturn(null);

        assertThatThrownBy(() -> tools.rerouteReview(testCaseId.toString()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Case not found");
    }

    @Test
    @SuppressWarnings("unchecked")
    void rerouteReview_validCase_cancelsAndStartsNew() {
        UUID newCaseId = UUID.randomUUID();
        when(tracker.getCase(testCaseId)).thenReturn(testCaseInfo);
        when(caseHub.startCase(any(Map.class)))
            .thenReturn(CompletableFuture.completedFuture(newCaseId));

        DevtownMcpTools.RerouteResult result = tools.rerouteReview(testCaseId.toString());

        assertThat(result.oldCaseId()).isEqualTo(testCaseId);
        assertThat(result.newCaseId()).isEqualTo(newCaseId);
        verify(caseHubRuntime).cancelCase(testCaseId);
        verify(caseHub).startCase(any(Map.class));
        verify(tracker).register(eq(newCaseId), anyString(), eq(testPayload));
    }

    @Test
    void forceCompleteCheck_unknownCase_throws() {
        when(tracker.getCase(testCaseId)).thenReturn(null);

        assertThatThrownBy(() -> tools.forceCompleteCheck(
            testCaseId.toString(),
            "code-analysis",
            "APPROVED",
            "Manual override"
        ))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Case not found");
    }

    @Test
    void forceCompleteCheck_validInputs_signalsWithOverride() {
        when(tracker.getCase(testCaseId)).thenReturn(testCaseInfo);

        DevtownMcpTools.ForceCompleteResult result = tools.forceCompleteCheck(
            testCaseId.toString(),
            "code-analysis",
            "APPROVED",
            "Emergency override"
        );

        assertThat(result.caseId()).isEqualTo(testCaseId);
        assertThat(result.capability()).isEqualTo("code-analysis");
        assertThat(result.outcome()).isEqualTo("APPROVED");
        assertThat(result.status()).isEqualTo("FORCE_COMPLETED");

        verify(caseHubRuntime).signal(
            eq(testCaseId),
            eq("codeAnalysis"),
            argThat(obj -> obj instanceof Map && ((Map<?, ?>) obj).get("operatorOverride").equals(true))
        );
    }

    @Test
    void exportProv_delegatesToProvExportService() {
        String provJson = "{\"@context\": \"http://www.w3.org/ns/prov#\"}";
        when(provExportService.exportSubject(testCaseId, TenancyConstants.DEFAULT_TENANT_ID))
            .thenReturn(provJson);

        String result = tools.exportProv(testCaseId.toString());

        assertThat(result).isEqualTo(provJson);
        verify(provExportService).exportSubject(testCaseId, TenancyConstants.DEFAULT_TENANT_ID);
    }

    // ==================== Merge Queue Read Tools ====================

    @Test
    void getMergeQueue_emptyQueue_returnsZeroCounts() {
        var emptyStatus = new GovernanceQueryService.MergeQueueStatus(
            0, 0, List.of(), List.of()
        );
        when(governanceQuery.mergeQueue()).thenReturn(emptyStatus);

        GovernanceQueryService.MergeQueueStatus status = tools.getMergeQueue();

        assertThat(status.queuedCount()).isZero();
        assertThat(status.activeBatchCount()).isZero();
        assertThat(status.queuedPrs()).isEmpty();
        assertThat(status.activeBatches()).isEmpty();
    }

    @Test
    void getMergeQueue_withQueuedPrsAndBatches_returnsCorrectState() {
        Instant enqueued = Instant.now().minus(30, ChronoUnit.MINUTES);
        UUID batchCaseId = UUID.randomUUID();

        var queuedPr = new GovernanceQueryService.QueuedPrEntry(
            101, "casehubio/devtown", "sha1", "alice", 0.85, "HIGH", enqueued, 30, java.util.Set.of()
        );
        var batchSummary = new GovernanceQueryService.ActiveBatchEntry(
            batchCaseId, "batch-1", 1, "low"
        );
        var status = new GovernanceQueryService.MergeQueueStatus(
            1, 1, List.of(queuedPr), List.of(batchSummary)
        );
        when(governanceQuery.mergeQueue()).thenReturn(status);

        GovernanceQueryService.MergeQueueStatus result = tools.getMergeQueue();

        assertThat(result.queuedCount()).isEqualTo(1);
        assertThat(result.activeBatchCount()).isEqualTo(1);
        assertThat(result.queuedPrs()).hasSize(1);
        assertThat(result.queuedPrs().get(0).number()).isEqualTo(101);
        assertThat(result.queuedPrs().get(0).priorityLane()).isEqualTo("HIGH");
        assertThat(result.queuedPrs().get(0).waitMinutes()).isGreaterThanOrEqualTo(29);
        assertThat(result.activeBatches()).hasSize(1);
        assertThat(result.activeBatches().get(0).batchId()).isEqualTo("batch-1");
    }

    @Test
    void getBatchStatus_unknownBatch_throws() {
        UUID unknownId = UUID.randomUUID();
        when(governanceQuery.batchStatus(unknownId))
            .thenThrow(new IllegalArgumentException("No active batch found for caseId: " + unknownId));

        assertThatThrownBy(() -> tools.getBatchStatus(unknownId.toString()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("No active batch found");
    }

    @Test
    void getBatchStatus_knownBatch_returnsDetail() {
        UUID batchCaseId = UUID.randomUUID();
        var pr1 = new GovernanceQueryService.BatchPrEntry(10, "casehubio/devtown", "sha1", "alice", 0.8, "NORMAL");
        var pr2 = new GovernanceQueryService.BatchPrEntry(11, "casehubio/devtown", "sha2", "bob", 0.7, "NORMAL");
        var status = new GovernanceQueryService.BatchStatus(
            "batch-2", batchCaseId, List.of(pr1, pr2), "low", "sequential"
        );
        when(governanceQuery.batchStatus(batchCaseId)).thenReturn(status);

        GovernanceQueryService.BatchStatus result = tools.getBatchStatus(batchCaseId.toString());

        assertThat(result.batchId()).isEqualTo("batch-2");
        assertThat(result.caseId()).isEqualTo(batchCaseId);
        assertThat(result.prs()).hasSize(2);
        assertThat(result.prs().get(0).number()).isEqualTo(10);
        assertThat(result.prs().get(1).number()).isEqualTo(11);
    }

    @Test
    void getMergeQueueMetrics_emptyQueue_returnsZeros() {
        var emptyMetrics = new GovernanceQueryService.MergeQueueMetrics(
            0, 0, 0L, 0L, 0.0, Map.of(), 0, 0.0, Map.of()
        );
        when(governanceQuery.mergeQueueMetrics()).thenReturn(emptyMetrics);

        GovernanceQueryService.MergeQueueMetrics metrics = tools.getMergeQueueMetrics();

        assertThat(metrics.queueDepth()).isZero();
        assertThat(metrics.activeBatches()).isZero();
        assertThat(metrics.oldestWaitMinutes()).isZero();
        assertThat(metrics.avgWaitMinutes()).isZero();
        assertThat(metrics.avgTrustScore()).isZero();
        assertThat(metrics.countsByLane()).isEmpty();
        assertThat(metrics.throughput24h()).isZero();
        assertThat(metrics.failureRate()).isZero();
        assertThat(metrics.batchSizeDistribution()).isEmpty();
    }

    @Test
    void getMergeQueueMetrics_withPrs_computesCorrectly() {
        var metrics = new GovernanceQueryService.MergeQueueMetrics(
            2,              // queueDepth
            0,              // activeBatches
            60L,            // oldestWaitMinutes
            35L,            // avgWaitMinutes
            0.7,            // avgTrustScore
            Map.of("NORMAL", 1, "HIGH", 1),
            1,              // throughput24h
            0.25,           // failureRate
            Map.of(2, 1)    // batchSizeDistribution
        );
        when(governanceQuery.mergeQueueMetrics()).thenReturn(metrics);

        GovernanceQueryService.MergeQueueMetrics result = tools.getMergeQueueMetrics();

        assertThat(result.queueDepth()).isEqualTo(2);
        assertThat(result.oldestWaitMinutes()).isGreaterThanOrEqualTo(59);
        assertThat(result.avgWaitMinutes()).isGreaterThanOrEqualTo(34);
        assertThat(result.avgTrustScore()).isCloseTo(0.7, org.assertj.core.data.Offset.offset(0.01));
        assertThat(result.countsByLane()).containsEntry("NORMAL", 1).containsEntry("HIGH", 1);
        assertThat(result.throughput24h()).isEqualTo(1);
        assertThat(result.failureRate()).isCloseTo(0.25, org.assertj.core.data.Offset.offset(0.01));
        assertThat(result.batchSizeDistribution()).containsEntry(2, 1);
    }

    // ==================== Merge Queue Write Tools ====================

    @Test
    void enqueuePr_validInputs_enqueuesSuccessfully() {
        when(mergeQueueService.enqueue(any())).thenReturn(true);
        DevtownMcpTools.EnqueueResult result = tools.enqueuePr(
            "casehubio/devtown", 99, "deadbeef", "alice", 0.75, "HIGH"
        );

        assertThat(result.prNumber()).isEqualTo(99);
        assertThat(result.lane()).isEqualTo("HIGH");
        assertThat(result.status()).isEqualTo("ENQUEUED");
    }

    @Test
    void enqueuePr_duplicate_returnsAlreadyQueued() {
        when(mergeQueueService.enqueue(any())).thenReturn(false);
        DevtownMcpTools.EnqueueResult result = tools.enqueuePr(
            "casehubio/devtown", 99, "deadbeef", "alice", 0.75, "HIGH"
        );
        assertThat(result.status()).isEqualTo("ALREADY_QUEUED");
    }

    @Test
    void enqueuePr_nullPriority_defaultsToNormal() {
        DevtownMcpTools.EnqueueResult result = tools.enqueuePr(
            "casehubio/devtown", 50, "abc123", "bob", 0.5, null
        );

        assertThat(result.lane()).isEqualTo("NORMAL");
        verify(mergeQueueService).enqueue(argThat(pr -> pr.lane() == PriorityLane.NORMAL));
    }

    @Test
    void enqueuePr_blankRepo_throws() {
        assertThatThrownBy(() -> tools.enqueuePr("", 1, "sha", "alice", 0.5, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("repo is required");
    }

    @Test
    void enqueuePr_invalidTrustScore_throws() {
        assertThatThrownBy(() -> tools.enqueuePr("repo", 1, "sha", "alice", 1.5, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("trustScore");
    }

    @Test
    void dequeuePr_existing_returnsRemoved() {
        when(mergeQueueService.dequeue(42, "casehubio/devtown")).thenReturn(true);

        DevtownMcpTools.DequeueResult result = tools.dequeuePr("casehubio/devtown", 42);

        assertThat(result.prNumber()).isEqualTo(42);
        assertThat(result.removed()).isTrue();
        assertThat(result.status()).isEqualTo("REMOVED");
    }

    @Test
    void dequeuePr_notFound_returnsNotFound() {
        when(mergeQueueService.dequeue(999, "casehubio/devtown")).thenReturn(false);

        DevtownMcpTools.DequeueResult result = tools.dequeuePr("casehubio/devtown", 999);

        assertThat(result.removed()).isFalse();
        assertThat(result.status()).isEqualTo("NOT_FOUND");
    }

    @Test
    void dequeuePr_blankRepo_throws() {
        assertThatThrownBy(() -> tools.dequeuePr("", 1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("repo is required");
    }

    // ==================== QUEUE_SLA_BREACH in listProblems ====================

    @Test
    void listProblems_detectsQueueSlaBreaches() {
        Instant longAgo = Instant.now().minus(180, ChronoUnit.MINUTES);
        var problem = new GovernanceQueryService.Problem(
            "queue_sla_breach",
            "warning",
            "PR #77 (CRITICAL lane) has waited 180 minutes, exceeding SLA of 60 minutes",
            null,
            "dave",
            longAgo
        );
        when(governanceQuery.problems(60)).thenReturn(List.of(problem));

        List<GovernanceQueryService.Problem> problems = tools.listProblems(60);

        assertThat(problems).hasSize(1);
        assertThat(problems.get(0).category()).isEqualTo("queue_sla_breach");
        assertThat(problems.get(0).description()).contains("PR #77").contains("CRITICAL");
        assertThat(problems.get(0).actorId()).isEqualTo("dave");
    }

    // Helper to create CaseEventLogRecord
    private CaseEventLogRecord createCaseEvent(Instant timestamp, String eventTypeStr, String actorId) {
        ObjectNode metadata = objectMapper.createObjectNode();
        if (actorId != null) {
            metadata.put("actorId", actorId);
        }

        CaseHubEventType eventType = mock(CaseHubEventType.class);
        when(eventType.toString()).thenReturn(eventTypeStr);

        return new CaseEventLogRecord(
            eventType,
            EventStreamType.CASE,
            timestamp,
            objectMapper.createObjectNode(),
            metadata
        );
    }
}
