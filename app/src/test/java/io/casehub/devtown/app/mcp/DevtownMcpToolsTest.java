package io.casehub.devtown.app.mcp;

import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.api.model.event.CaseEventLogRecord;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.api.model.event.EventStreamType;
import io.casehub.devtown.app.PrReviewCaseHub;
import io.casehub.devtown.review.PrPayload;
import io.casehub.ledger.runtime.service.LedgerProvExportService;
import io.casehub.ledger.runtime.service.TrustGateService;
import io.casehub.ledger.runtime.service.federation.TrustExportService;
import io.casehub.ledger.runtime.service.federation.TrustExportPayload;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.platform.api.memory.CaseMemoryStore;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.message.Commitment;
import io.casehub.qhorus.runtime.store.CommitmentStore;
import com.fasterxml.jackson.databind.JsonNode;
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
        when(tracker.activeCases()).thenReturn(List.of());

        DevtownMcpTools.QueueStatus status = tools.getQueueStatus();

        assertThat(status.total()).isZero();
        assertThat(status.countsByStatus()).isEmpty();
        assertThat(status.reviews()).isEmpty();
    }

    @Test
    void getQueueStatus_withRegisteredCases_returnsCorrectCountsAndReviews() {
        Instant now = Instant.now();
        CaseInfo case1 = new CaseInfo(
            UUID.randomUUID(),
            TenancyConstants.DEFAULT_TENANT_ID,
            testPayload,
            now,
            now,
            CaseTrackingStatus.RUNNING
        );
        CaseInfo case2 = new CaseInfo(
            UUID.randomUUID(),
            TenancyConstants.DEFAULT_TENANT_ID,
            testPayload,
            now,
            now,
            CaseTrackingStatus.WAITING
        );

        when(tracker.activeCases()).thenReturn(List.of(case1, case2));

        DevtownMcpTools.QueueStatus status = tools.getQueueStatus();

        assertThat(status.total()).isEqualTo(2);
        assertThat(status.countsByStatus()).containsEntry("RUNNING", 1);
        assertThat(status.countsByStatus()).containsEntry("WAITING", 1);
        assertThat(status.reviews()).hasSize(2);
        assertThat(status.reviews().get(0).repo()).isEqualTo("casehubio/devtown");
        assertThat(status.reviews().get(0).prNumber()).isEqualTo(42);
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
        when(tracker.recentEvents(50, null)).thenReturn(List.of(event));

        List<TrackedEvent> events = tools.getRecentEvents(null, null);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).eventType()).isEqualTo("CaseStarted");
        verify(tracker).recentEvents(50, null);
    }

    @Test
    void getRecentEvents_withLimitAndSince_passesParameters() {
        Instant since = Instant.now().minus(1, ChronoUnit.HOURS);
        when(tracker.recentEvents(eq(10), any(Instant.class))).thenReturn(List.of());

        List<TrackedEvent> events = tools.getRecentEvents(10, since.toString());

        verify(tracker).recentEvents(eq(10), argThat(instant ->
            instant != null && instant.equals(since)
        ));
    }

    @Test
    void getSystemHealth_assemblesFromMultipleSources() {
        when(tracker.activeCases()).thenReturn(List.of(testCaseInfo));
        when(commitmentStore.findAllOpen()).thenReturn(List.of());

        // Mock TrustExportPayload
        TrustExportPayload mockPayload = mock(TrustExportPayload.class);
        when(mockPayload.actors()).thenReturn(List.of());  // Empty fleet for simplicity
        when(trustExportService.exportAll(0.0)).thenReturn(mockPayload);

        DevtownMcpTools.SystemHealth health = tools.getSystemHealth();

        assertThat(health.activeCases()).isEqualTo(1);
        assertThat(health.fleetSize()).isZero();
        assertThat(health.openCommitments()).isZero();
    }

    @Test
    void listProblems_findsStalledCases() {
        Instant staleTime = Instant.now().minus(90, ChronoUnit.MINUTES);
        CaseInfo stalledCase = new CaseInfo(
            testCaseId,
            TenancyConstants.DEFAULT_TENANT_ID,
            testPayload,
            staleTime,
            staleTime,
            CaseTrackingStatus.RUNNING
        );

        when(tracker.stalledCases(60)).thenReturn(List.of(stalledCase));
        when(commitmentStore.findExpiredBefore(any(Instant.class))).thenReturn(List.of());
        when(tracker.recentEvents(100, null)).thenReturn(List.of());

        List<DevtownMcpTools.Problem> problems = tools.listProblems(60);

        assertThat(problems).hasSize(1);
        assertThat(problems.get(0).category()).isEqualTo("stalled_case");
        assertThat(problems.get(0).severity()).isEqualTo("warning");
        assertThat(problems.get(0).caseId()).isEqualTo(testCaseId);
    }

    @Test
    void listProblems_findsExpiredCommitments() {
        Instant expired = Instant.now().minus(10, ChronoUnit.MINUTES);
        Commitment commitment = new Commitment();
        commitment.id = UUID.randomUUID();
        commitment.channelId = testCaseId;
        commitment.obligor = "reviewer-1";
        commitment.messageType = MessageType.COMMAND;
        commitment.expiresAt = expired;
        commitment.tenancyId = TenancyConstants.DEFAULT_TENANT_ID;

        when(tracker.stalledCases(60)).thenReturn(List.of());
        when(commitmentStore.findExpiredBefore(any(Instant.class))).thenReturn(List.of(commitment));
        when(tracker.recentEvents(100, null)).thenReturn(List.of());

        List<DevtownMcpTools.Problem> problems = tools.listProblems(60);

        assertThat(problems).hasSize(1);
        assertThat(problems.get(0).category()).isEqualTo("expired_commitment");
        assertThat(problems.get(0).severity()).isEqualTo("error");
        assertThat(problems.get(0).actorId()).isEqualTo("reviewer-1");
    }

    @Test
    void listProblems_findsFailedWorkers() {
        TrackedEvent failedEvent = new TrackedEvent(
            Instant.now(),
            testCaseId,
            "casehubio/devtown",
            42,
            "ReviewFailed",
            "FAILED",
            "reviewer-1"
        );

        when(tracker.stalledCases(60)).thenReturn(List.of());
        when(commitmentStore.findExpiredBefore(any(Instant.class))).thenReturn(List.of());
        when(tracker.recentEvents(100, null)).thenReturn(List.of(failedEvent));

        List<DevtownMcpTools.Problem> problems = tools.listProblems(60);

        assertThat(problems).hasSize(1);
        assertThat(problems.get(0).category()).isEqualTo("worker_failure");
        assertThat(problems.get(0).actorId()).isEqualTo("reviewer-1");
    }

    @Test
    void inspectReview_unknownCase_throwsIllegalArgumentException() {
        when(tracker.getCase(testCaseId)).thenReturn(null);

        assertThatThrownBy(() -> tools.inspectReview(testCaseId.toString()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Case not found");
    }

    @Test
    void inspectReview_knownCase_returnsDetailWithTimeline() {
        when(tracker.getCase(testCaseId)).thenReturn(testCaseInfo);

        Instant now = Instant.now();
        // Timeline events
        CaseEventLogRecord timelineEvent = new CaseEventLogRecord(
            CaseHubEventType.CASE_STARTED, EventStreamType.CASE, now,
            objectMapper.createObjectNode(), objectMapper.createObjectNode());

        when(caseHubRuntime.eventLog(testCaseId))
            .thenReturn(CompletableFuture.completedFuture(List.of(timelineEvent)));

        // Worker events for capability resolution
        ObjectNode capMeta = objectMapper.createObjectNode().put("capabilityName", "code-analysis");
        CaseEventLogRecord workerEvent = new CaseEventLogRecord(
            CaseHubEventType.WORKER_EXECUTION_COMPLETED, EventStreamType.CASE, now.plusSeconds(10),
            objectMapper.createObjectNode(), capMeta);

        when(caseHubRuntime.eventLog(eq(testCaseId), anySet()))
            .thenReturn(CompletableFuture.completedFuture(List.of(workerEvent)));

        DevtownMcpTools.ReviewDetail detail = tools.inspectReview(testCaseId.toString());

        assertThat(detail.caseId()).isEqualTo(testCaseId);
        assertThat(detail.pr()).isEqualTo(testPayload);
        assertThat(detail.timeline()).hasSize(1);
        assertThat(detail.capabilities()).hasSize(1);
        assertThat(detail.capabilities().get(0).name()).isEqualTo("code-analysis");
        assertThat(detail.capabilities().get(0).status()).isEqualTo("COMPLETED");
    }

    @Test
    void getReviewerHealth_returnsCommitmentCountAndTrustScores() {
        Commitment commitment = new Commitment();
        commitment.id = UUID.randomUUID();
        commitment.channelId = testCaseId;
        commitment.obligor = "reviewer-1";
        commitment.messageType = MessageType.COMMAND;
        commitment.expiresAt = Instant.now().plusSeconds(3600);
        commitment.tenancyId = TenancyConstants.DEFAULT_TENANT_ID;

        when(commitmentStore.findOpenByObligor("reviewer-1")).thenReturn(List.of(commitment));
        when(trustGateService.allCapabilityScores("reviewer-1"))
            .thenReturn(Map.of("code-analysis", 0.75, "security-review", 0.82));
        when(trustGateService.allDimensionScores("reviewer-1"))
            .thenReturn(Map.of("review-thoroughness", 0.80));
        when(trustGateService.decisionCount(eq("reviewer-1"), anyString())).thenReturn(5);
        when(tracker.recentEvents(100, null)).thenReturn(List.of());

        DevtownMcpTools.ReviewerHealth health = tools.getReviewerHealth("reviewer-1");

        assertThat(health.reviewerId()).isEqualTo("reviewer-1");
        assertThat(health.openCommitments()).isEqualTo(1);
        assertThat(health.trustByCapability()).containsEntry("code-analysis", 0.75);
        assertThat(health.trustByDimension()).containsEntry("review-thoroughness", 0.80);
        assertThat(health.totalDecisions()).isGreaterThan(0);
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
