package io.casehub.devtown.app.mcp;

import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.api.model.event.CaseEventLogRecord;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.devtown.app.MergeQueueService;
import io.casehub.devtown.app.PrReviewCaseHub;
import io.casehub.devtown.app.ledger.IncidentFeedbackService;
import io.casehub.devtown.domain.IncidentFeedback;
import io.casehub.devtown.domain.IncidentFeedbackResult;
import io.casehub.devtown.domain.IncidentSeverity;
import io.casehub.devtown.queue.Batch;
import io.casehub.devtown.queue.PriorityLane;
import io.casehub.devtown.queue.QueuedPr;
import io.casehub.devtown.review.PrPayload;
import io.casehub.ledger.runtime.service.LedgerProvExportService;
import io.casehub.ledger.runtime.service.TrustGateService;
import io.casehub.ledger.runtime.service.federation.TrustExportService;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.api.memory.CaseMemoryStore;
import io.casehub.platform.api.memory.MemoryOrder;
import io.casehub.platform.api.memory.MemoryQuery;
import io.casehub.devtown.domain.memory.DevtownMemoryDomain;
import io.casehub.devtown.domain.memory.ModulePathNormalizer;
import io.casehub.qhorus.runtime.message.Commitment;
import io.casehub.qhorus.runtime.store.CommitmentStore;
import io.casehub.work.api.WorkItemStatus;
import io.casehub.work.runtime.repository.WorkItemQuery;
import io.casehub.work.runtime.repository.WorkItemStore;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.WrapBusinessError;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
@WrapBusinessError({IllegalArgumentException.class, IllegalStateException.class})
public class DevtownMcpTools {

    private static final Map<String, String> CAPABILITY_CONTEXT_KEYS = Map.of(
        "code-analysis", "codeAnalysis",
        "security-review", "securityReview",
        "architecture-review", "architectureReview",
        "style-review", "styleCheck",
        "test-coverage", "testCoverage",
        "performance-analysis", "performanceAnalysis"
    );

    @Inject
    PrReviewCaseTracker tracker;

    @Inject
    CaseHubRuntime caseHubRuntime;

    @Inject
    CommitmentStore commitmentStore;

    @Inject
    TrustGateService trustGateService;

    @Inject
    TrustExportService trustExportService;

    @Inject
    LedgerProvExportService provExportService;

    @Inject
    Instance<CaseMemoryStore> memoryStoreInstance;

    @Inject
    Instance<WorkItemStore> workItemStoreInstance;

    @Inject
    PrReviewCaseHub caseHub;

    @Inject
    CurrentPrincipal principal;

    @Inject
    IncidentFeedbackService incidentFeedbackService;

    @Inject
    MergeQueueService mergeQueueService;

    @ConfigProperty(name = "devtown.policy.human-approval-threshold", defaultValue = "500")
    int humanApprovalThreshold;

    @ConfigProperty(name = "devtown.policy.security-review-required", defaultValue = "true")
    boolean securityReviewRequired;

    @ConfigProperty(name = "devtown.policy.require-senior-approval", defaultValue = "false")
    boolean requireSeniorApproval;

    @ConfigProperty(name = "devtown.queue.sla-minutes", defaultValue = "120")
    int queueSlaMinutes;

    // ==================== Response Records ====================

    public record QueueStatus(int total, Map<String, Integer> countsByStatus, List<ActiveReview> reviews) {}

    public record ActiveReview(
        UUID caseId,
        String repo,
        int prNumber,
        String contributor,
        int linesChanged,
        String status,
        Instant startedAt,
        Instant lastEventAt
    ) {}

    public record ReviewDetail(
        UUID caseId,
        PrPayload pr,
        List<EventEntry> timeline,
        List<CapabilityStatus> capabilities
    ) {}

    public record EventEntry(Instant timestamp, String eventType, String actor, String summary) {}

    public record CapabilityStatus(String name, String status, String outcome, Instant completedAt) {}

    public record ReviewerHealth(
        String reviewerId,
        int openCommitments,
        Map<String, Double> trustByCapability,
        Map<String, Double> trustByDimension,
        int totalDecisions,
        List<RecentOutcome> recentOutcomes
    ) {}

    public record RecentOutcome(UUID caseId, String capability, String outcome, Instant timestamp) {}

    public record Problem(
        String category,
        String severity,
        String description,
        UUID caseId,
        String actorId,
        Instant since
    ) {}

    public record SystemHealth(
        int activeCases,
        int fleetSize,
        Map<String, Double> avgTrustByCapability,
        int openCommitments,
        int pendingWorkItems
    ) {}

    public record PriorDecision(
        UUID caseId,
        String repo,
        int prNumber,
        String capability,
        String outcome,
        Instant decidedAt
    ) {}

    public record RetryResult(UUID caseId, String capability, String status) {}

    public record RerouteResult(UUID oldCaseId, UUID newCaseId) {}

    public record ForceCompleteResult(UUID caseId, String capability, String outcome, String status) {}

    public record MergeQueueStatus(
        int queuedCount,
        int activeBatchCount,
        List<QueuedPrEntry> queuedPrs,
        List<ActiveBatchEntry> activeBatches
    ) {}

    public record QueuedPrEntry(
        int number,
        String headSha,
        String author,
        double trustScore,
        String priorityLane,
        Instant enqueuedAt,
        long waitMinutes,
        Set<Integer> dependsOn
    ) {}

    public record ActiveBatchEntry(UUID caseId, String batchId, int prCount, String riskLevel) {}

    public record BatchStatus(
        String batchId,
        UUID caseId,
        List<BatchPrEntry> prs,
        String riskLevel,
        String bisectionStrategy
    ) {}

    public record BatchPrEntry(int number, String headSha, String author, double trustScore, String lane) {}

    public record MergeQueueMetrics(
        int queueDepth,
        int activeBatches,
        long oldestWaitMinutes,
        double avgTrustScore,
        Map<String, Integer> countsByLane
    ) {}

    public record EnqueueResult(int prNumber, String lane, String status) {}

    public record DequeueResult(int prNumber, boolean removed, String status) {}

    // ==================== Read Tools ====================

    @Tool(
        name = "get_queue_status",
        description = "Get current PR review queue status with counts by status and active reviews"
    )
    public QueueStatus getQueueStatus() {
        List<CaseInfo> active = tracker.activeCases();
        Map<String, Integer> countsByStatus = new HashMap<>();

        List<ActiveReview> reviews = active.stream()
            .map(c -> {
                String statusStr = c.status().name();
                countsByStatus.merge(statusStr, 1, Integer::sum);

                return new ActiveReview(
                    c.caseId(),
                    c.payload().repo(),
                    c.payload().prNumber(),
                    c.payload().contributor(),
                    c.payload().linesChanged(),
                    statusStr,
                    c.startedAt(),
                    c.lastEventAt()
                );
            })
            .toList();

        return new QueueStatus(active.size(), countsByStatus, reviews);
    }

    @Tool(
        name = "get_recent_events",
        description = "Get recent PR review events from the ring buffer"
    )
    public List<TrackedEvent> getRecentEvents(
        @ToolArg(name = "limit", description = "Maximum events to return", required = false) Integer limit,
        @ToolArg(name = "since", description = "ISO-8601 timestamp to filter events after", required = false) String since
    ) {
        int effectiveLimit = limit != null ? limit : 50;
        Instant sinceTime = since != null ? Instant.parse(since) : null;
        return tracker.recentEvents(effectiveLimit, sinceTime);
    }

    @Tool(
        name = "get_system_health",
        description = "Get overall system health metrics across all cases and agents"
    )
    public SystemHealth getSystemHealth() {
        List<CaseInfo> active = tracker.activeCases();
        List<Commitment> openCommitments = commitmentStore.findAllOpen();

        // Fleet size from trust export (all agents with any trust score)
        var trustExport = trustExportService.exportAll(0.0);
        int fleetSize = trustExport.actors().size();

        // Average trust by capability across fleet
        Map<String, Double> avgTrustByCapability = new HashMap<>();
        for (String capability : CAPABILITY_CONTEXT_KEYS.keySet()) {
            double sum = 0.0;
            int count = 0;
            for (var actor : trustExport.actors()) {
                var scores = trustGateService.allCapabilityScores(actor.actorId());
                if (scores.containsKey(capability)) {
                    sum += scores.get(capability);
                    count++;
                }
            }
            if (count > 0) {
                avgTrustByCapability.put(capability, sum / count);
            }
        }

        int pendingWorkItems = 0;
        if (workItemStoreInstance.isResolvable()) {
            pendingWorkItems = workItemStoreInstance.get()
                .scan(WorkItemQuery.builder().status(WorkItemStatus.PENDING).build()).size();
        }

        return new SystemHealth(
            active.size(),
            fleetSize,
            avgTrustByCapability,
            openCommitments.size(),
            pendingWorkItems
        );
    }

    @Tool(
        name = "list_problems",
        description = "List detected problems: stalled cases, expired commitments, failed workers, queue SLA breaches"
    )
    public List<Problem> listProblems(
        @ToolArg(name = "threshold_minutes", description = "Stall threshold in minutes", required = false) Integer thresholdMinutes
    ) {
        int threshold = thresholdMinutes != null ? thresholdMinutes : 60;
        List<Problem> problems = new ArrayList<>();

        // Stalled cases
        for (CaseInfo stalled : tracker.stalledCases(threshold)) {
            problems.add(new Problem(
                "stalled_case",
                "warning",
                String.format("PR review stalled for %s#%d — no progress for %d+ minutes",
                    stalled.payload().repo(), stalled.payload().prNumber(), threshold),
                stalled.caseId(),
                null,
                stalled.lastEventAt()
            ));
        }

        // Expired commitments
        Instant now = Instant.now();
        for (Commitment expired : commitmentStore.findExpiredBefore(now)) {
            problems.add(new Problem(
                "expired_commitment",
                "error",
                String.format("Commitment expired: obligor=%s messageType=%s",
                    expired.obligor, expired.messageType),
                expired.channelId,  // Use channelId as case reference
                expired.obligor,
                expired.expiresAt
            ));
        }

        // Failed workers from event buffer
        List<TrackedEvent> recentEvents = tracker.recentEvents(100, null);
        for (TrackedEvent event : recentEvents) {
            if (event.eventType().contains("Failed")) {
                problems.add(new Problem(
                    "worker_failure",
                    "error",
                    String.format("Worker failure: %s on %s#%d",
                        event.actorId(), event.repo(), event.prNumber()),
                    event.caseId(),
                    event.actorId(),
                    event.timestamp()
                ));
            }
        }

        // Queue SLA breaches — PRs waiting longer than the configured SLA
        Instant now2 = Instant.now();
        for (QueuedPr pr : mergeQueueService.queuedPrs()) {
            long waitMinutes = Duration.between(pr.enqueuedAt(), now2).toMinutes();
            if (waitMinutes > queueSlaMinutes) {
                problems.add(new Problem(
                    "queue_sla_breach",
                    "warning",
                    String.format("PR #%d has been queued for %d minutes (SLA: %d minutes)",
                        pr.number(), waitMinutes, queueSlaMinutes),
                    null,
                    pr.author(),
                    pr.enqueuedAt()
                ));
            }
        }

        return problems;
    }

    @Tool(
        name = "inspect_review",
        description = "Get detailed review status including timeline and capability progress"
    )
    public ReviewDetail inspectReview(
        @ToolArg(name = "case_id", description = "Case UUID", required = true) String caseIdStr
    ) {
        UUID caseId = UUID.fromString(caseIdStr);
        CaseInfo caseInfo = tracker.getCase(caseId);

        if (caseInfo == null) {
            throw new IllegalArgumentException("Case not found: " + caseId);
        }

        String tenant = principal.tenancyId();

        // Event log timeline (await async result)
        List<CaseEventLogRecord> events = caseHubRuntime.eventLog(caseId).toCompletableFuture().join();
        List<EventEntry> timeline = events.stream()
            .map(e -> {
                // Extract actorId from metadata if present
                String actorId = "system";
                if (e.metadata() != null && e.metadata().has("actorId")) {
                    actorId = e.metadata().get("actorId").asText();
                }
                return new EventEntry(
                    e.timestamp(),
                    e.eventType().toString(),
                    actorId,
                    e.eventType().toString()
                );
            })
            .toList();

        var workerEvents = caseHubRuntime.eventLog(caseId, java.util.Set.of(
            CaseHubEventType.WORK_SUBMITTED,
            CaseHubEventType.WORKER_EXECUTION_COMPLETED,
            CaseHubEventType.WORKER_EXECUTION_FAILED,
            CaseHubEventType.WORKER_OUTCOME_DECLINED
        )).toCompletableFuture().join();

        Map<String, CapabilityStatus> capabilityMap = new LinkedHashMap<>();
        for (CaseEventLogRecord event : workerEvents) {
            String capName = event.metadata() != null && event.metadata().has("capabilityName")
                ? event.metadata().get("capabilityName").asText()
                : null;
            if (capName == null) continue;
            String status = switch (event.eventType()) {
                case WORKER_EXECUTION_COMPLETED -> "COMPLETED";
                case WORKER_EXECUTION_FAILED -> "FAILED";
                case WORKER_OUTCOME_DECLINED -> "DECLINED";
                default -> "SCHEDULED";
            };
            capabilityMap.put(capName, new CapabilityStatus(capName, status, null, event.timestamp()));
        }

        List<CapabilityStatus> capabilities = new ArrayList<>(capabilityMap.values());

        return new ReviewDetail(caseId, caseInfo.payload(), timeline, capabilities);
    }

    @Tool(
        name = "get_reviewer_health",
        description = "Get health metrics for a specific reviewer: commitments, trust scores, decision history"
    )
    public ReviewerHealth getReviewerHealth(
        @ToolArg(name = "reviewer_id", description = "Reviewer actor ID", required = true) String reviewerId
    ) {

        List<Commitment> openCommitments = commitmentStore.findOpenByObligor(reviewerId);
        Map<String, Double> trustByCapability = trustGateService.allCapabilityScores(reviewerId);
        Map<String, Double> trustByDimension = trustGateService.allDimensionScores(reviewerId);

        // Total decisions across all capabilities
        int totalDecisions = 0;
        for (String capability : CAPABILITY_CONTEXT_KEYS.keySet()) {
            totalDecisions += trustGateService.decisionCount(reviewerId, capability);
        }

        // Recent outcomes from event buffer
        List<TrackedEvent> recentEvents = tracker.recentEvents(100, null);
        List<RecentOutcome> recentOutcomes = recentEvents.stream()
            .filter(e -> reviewerId.equals(e.actorId()))
            .filter(e -> e.eventType().contains("Complete"))
            .map(e -> {
                String capability = CAPABILITY_CONTEXT_KEYS.keySet().stream()
                    .filter(cap -> e.eventType().contains(cap))
                    .findFirst()
                    .orElse("unknown");
                String outcome = e.eventType().contains("APPROVED") ? "APPROVED" :
                               e.eventType().contains("DECLINED") ? "DECLINED" :
                               "DONE";
                return new RecentOutcome(e.caseId(), capability, outcome, e.timestamp());
            })
            .limit(10)
            .toList();

        return new ReviewerHealth(
            reviewerId,
            openCommitments.size(),
            trustByCapability,
            trustByDimension,
            totalDecisions,
            recentOutcomes
        );
    }

    @Tool(
        name = "get_prior_decisions",
        description = "Find prior review decisions for a specific repository and file path"
    )
    public List<PriorDecision> getPriorDecisions(
        @ToolArg(name = "repo", description = "Repository name", required = true) String repo,
        @ToolArg(name = "file_path", description = "File path within repo", required = true) String filePath
    ) {
        String tenant = principal.tenancyId();

        if (!memoryStoreInstance.isResolvable()) {
            return List.of();
        }

        var modules = ModulePathNormalizer.normalize(List.of(filePath));
        List<String> entityIds = modules.stream()
            .map(m -> DevtownMemoryDomain.MODULE_PREFIX + repo + "/" + m)
            .limit(MemoryQuery.MAX_ENTITY_IDS)
            .toList();

        if (entityIds.isEmpty()) {
            return List.of();
        }

        CaseMemoryStore memoryStore = memoryStoreInstance.get();
        var memories = memoryStore.query(
            MemoryQuery.forEntities(entityIds, DevtownMemoryDomain.SOFTWARE_REVIEW, tenant)
                .withLimit(20)
                .withOrder(MemoryOrder.CHRONOLOGICAL)
        );

        return memories.stream()
            .map(m -> new PriorDecision(
                null, repo, 0,
                m.attributes().getOrDefault("capability", "unknown"),
                m.text(),
                m.createdAt()))
            .toList();
    }

    @Tool(
        name = "export_prov",
        description = "Export PROV-DM provenance record for a case (PROV-JSON-LD format)"
    )
    public String exportProv(
        @ToolArg(name = "case_id", description = "Case UUID", required = true) String caseIdStr
    ) {
        UUID caseId = UUID.fromString(caseIdStr);
        return provExportService.exportSubject(caseId, principal.tenancyId());
    }

    @Tool(
        name = "get_merge_queue",
        description = "Get current merge queue state: queued PRs with priority scores, wait times, dependencies, and active batches"
    )
    public MergeQueueStatus getMergeQueue() {
        Instant now = Instant.now();
        List<QueuedPr> queued = mergeQueueService.queuedPrs();
        Map<UUID, Batch> batches = mergeQueueService.activeBatches();

        List<QueuedPrEntry> prEntries = queued.stream()
            .map(pr -> new QueuedPrEntry(
                pr.number(),
                pr.headSha(),
                pr.author(),
                pr.trustScore(),
                pr.lane().name(),
                pr.enqueuedAt(),
                Duration.between(pr.enqueuedAt(), now).toMinutes(),
                pr.dependsOn()
            ))
            .toList();

        List<ActiveBatchEntry> batchEntries = batches.entrySet().stream()
            .map(e -> new ActiveBatchEntry(e.getKey(), e.getValue().id(), e.getValue().size(), e.getValue().riskLevel()))
            .toList();

        return new MergeQueueStatus(queued.size(), batches.size(), prEntries, batchEntries);
    }

    @Tool(
        name = "get_batch_status",
        description = "Get batch state: PRs in the batch, risk level, bisection strategy"
    )
    public BatchStatus getBatchStatus(
        @ToolArg(name = "batch_case_id", description = "Case UUID of the batch", required = true) String batchCaseIdStr
    ) {
        UUID batchCaseId = UUID.fromString(batchCaseIdStr);
        Map<UUID, Batch> batches = mergeQueueService.activeBatches();
        Batch batch = batches.get(batchCaseId);
        if (batch == null) {
            throw new IllegalArgumentException("No active batch found for case: " + batchCaseId);
        }

        List<BatchPrEntry> prEntries = batch.prs().stream()
            .map(pr -> new BatchPrEntry(pr.number(), pr.headSha(), pr.author(), pr.trustScore(), pr.lane().name()))
            .toList();

        return new BatchStatus(batch.id(), batchCaseId, prEntries, batch.riskLevel(), batch.bisectionStrategy());
    }

    @Tool(
        name = "get_merge_queue_metrics",
        description = "Get operational metrics: queue depth, active batches, oldest wait time, average trust score, lane distribution"
    )
    public MergeQueueMetrics getMergeQueueMetrics() {
        Instant now = Instant.now();
        List<QueuedPr> queued = mergeQueueService.queuedPrs();
        Map<UUID, Batch> batches = mergeQueueService.activeBatches();

        long oldestWaitMinutes = queued.stream()
            .mapToLong(pr -> Duration.between(pr.enqueuedAt(), now).toMinutes())
            .max()
            .orElse(0);

        double avgTrust = queued.stream()
            .mapToDouble(QueuedPr::trustScore)
            .average()
            .orElse(0.0);

        Map<String, Integer> countsByLane = new HashMap<>();
        for (QueuedPr pr : queued) {
            countsByLane.merge(pr.lane().name(), 1, Integer::sum);
        }

        return new MergeQueueMetrics(queued.size(), batches.size(), oldestWaitMinutes, avgTrust, countsByLane);
    }

    // ==================== Write Tools ====================

    @Tool(
        name = "retry_reviewer",
        description = "Retry a specific capability by signaling the case with a null context value"
    )
    public RetryResult retryReviewer(
        @ToolArg(name = "case_id", description = "Case UUID", required = true) String caseIdStr,
        @ToolArg(name = "capability", description = "Capability to retry", required = true) String capability
    ) {
        UUID caseId = UUID.fromString(caseIdStr);

        CaseInfo caseInfo = tracker.getCase(caseId);
        if (caseInfo == null) {
            throw new IllegalArgumentException("Case not found: " + caseId);
        }

        String contextKey = CAPABILITY_CONTEXT_KEYS.get(capability);
        if (contextKey == null) {
            throw new IllegalArgumentException("Unknown capability: " + capability);
        }
        caseHubRuntime.signal(caseId, contextKey, null);

        return new RetryResult(caseId, capability, "RETRY_SIGNALED");
    }

    @Tool(
        name = "reroute_review",
        description = "Cancel current case and start a fresh review with the same PR payload"
    )
    public RerouteResult rerouteReview(
        @ToolArg(name = "case_id", description = "Case UUID to cancel", required = true) String caseIdStr
    ) {
        UUID oldCaseId = UUID.fromString(caseIdStr);

        CaseInfo caseInfo = tracker.getCase(oldCaseId);
        if (caseInfo == null) {
            throw new IllegalArgumentException("Case not found: " + oldCaseId);
        }

        String tenant = principal.tenancyId();

        caseHubRuntime.cancelCase(oldCaseId);

        var prContext = Map.<String, Object>of(
            "id", String.valueOf(caseInfo.payload().prNumber()),
            "repo", caseInfo.payload().repo(),
            "linesChanged", caseInfo.payload().linesChanged(),
            "baseRef", caseInfo.payload().baseRef(),
            "headSha", caseInfo.payload().headSha(),
            "contributor", caseInfo.payload().contributor(),
            "changedPaths", caseInfo.payload().changedPaths()
        );
        var policy = Map.<String, Object>of(
            "humanApprovalThreshold", humanApprovalThreshold,
            "securityReviewRequired", securityReviewRequired,
            "requireSeniorApproval", requireSeniorApproval
        );
        var initialContext = new HashMap<String, Object>();
        initialContext.put("pr", prContext);
        initialContext.put("policy", policy);

        UUID newCaseId = caseHub.startCase(initialContext).toCompletableFuture().join();

        // Register with tracker
        tracker.register(newCaseId, tenant, caseInfo.payload());

        return new RerouteResult(oldCaseId, newCaseId);
    }

    @Tool(
        name = "force_complete_check",
        description = "Force-complete a capability check with operator override"
    )
    public ForceCompleteResult forceCompleteCheck(
        @ToolArg(name = "case_id", description = "Case UUID", required = true) String caseIdStr,
        @ToolArg(name = "capability", description = "Capability to force-complete", required = true) String capability,
        @ToolArg(name = "outcome", description = "Outcome (APPROVED/DECLINED)", required = true) String outcome,
        @ToolArg(name = "reason", description = "Override reason", required = true) String reason
    ) {
        UUID caseId = UUID.fromString(caseIdStr);

        CaseInfo caseInfo = tracker.getCase(caseId);
        if (caseInfo == null) {
            throw new IllegalArgumentException("Case not found: " + caseId);
        }

        String contextKey = CAPABILITY_CONTEXT_KEYS.get(capability);
        if (contextKey == null) {
            throw new IllegalArgumentException("Unknown capability: " + capability);
        }

        // Signal case with synthetic result
        Map<String, Object> syntheticResult = Map.of(
            "outcome", outcome,
            "operatorOverride", true,
            "reason", reason,
            "timestamp", Instant.now().toString()
        );

        caseHubRuntime.signal(caseId, contextKey, syntheticResult);

        return new ForceCompleteResult(caseId, capability, outcome, "FORCE_COMPLETED");
    }

    @Tool(
        name = "enqueue_pr",
        description = "Add a PR to the merge queue with priority and trust score"
    )
    public EnqueueResult enqueuePr(
        @ToolArg(name = "repo", description = "Repository slug (e.g. casehubio/devtown)") String repo,
        @ToolArg(name = "pr_number", description = "PR number") int prNumber,
        @ToolArg(name = "head_sha", description = "Head commit SHA") String headSha,
        @ToolArg(name = "author", description = "PR author") String author,
        @ToolArg(name = "trust_score", description = "Author trust score [0.0, 1.0]") double trustScore,
        @ToolArg(name = "priority", description = "Priority lane: NORMAL, HIGH, or CRITICAL", required = false) String priority
    ) {
        if (repo == null || repo.isBlank()) {
            throw new IllegalArgumentException("repo is required");
        }
        if (headSha == null || headSha.isBlank()) {
            throw new IllegalArgumentException("head_sha is required");
        }
        if (author == null || author.isBlank()) {
            throw new IllegalArgumentException("author is required");
        }

        PriorityLane lane = PriorityLane.NORMAL;
        if (priority != null && !priority.isBlank()) {
            lane = PriorityLane.valueOf(priority.toUpperCase());
        }

        QueuedPr pr = new QueuedPr(prNumber, headSha, author, trustScore, lane, Instant.now(), Set.of());
        mergeQueueService.enqueue(pr);

        return new EnqueueResult(prNumber, lane.name(), "ENQUEUED");
    }

    @Tool(
        name = "dequeue_pr",
        description = "Remove a PR from the merge queue"
    )
    public DequeueResult dequeuePr(
        @ToolArg(name = "repo", description = "Repository slug (e.g. casehubio/devtown)") String repo,
        @ToolArg(name = "pr_number", description = "PR number") int prNumber
    ) {
        if (repo == null || repo.isBlank()) {
            throw new IllegalArgumentException("repo is required");
        }

        boolean removed = mergeQueueService.dequeue(prNumber);
        return new DequeueResult(prNumber, removed, removed ? "REMOVED" : "NOT_FOUND");
    }

    @Tool(
        name = "report_incident",
        description = "Report a production incident against a merged PR — writes FLAGGED attestation against the reviewer's trust score"
    )
    public IncidentFeedbackResult reportIncident(
        @ToolArg(name = "repository", description = "GitHub repo slug (e.g. casehubio/devtown)") String repository,
        @ToolArg(name = "prNumber", description = "PR number") int prNumber,
        @ToolArg(name = "incidentId", description = "External incident tracker ID") String incidentId,
        @ToolArg(name = "severity", description = "LOW, MEDIUM, HIGH, or CRITICAL") String severity,
        @ToolArg(name = "description", description = "What went wrong") String description,
        @ToolArg(name = "reviewCapability", description = "Which capability missed the issue (e.g. security-review)") String reviewCapability,
        @ToolArg(name = "caseId", description = "Optional — disambiguate when multiple cases exist for the same PR", required = false) String caseId
    ) {
        IncidentSeverity sev = IncidentSeverity.valueOf(severity.toUpperCase());
        UUID parsedCaseId = caseId != null ? UUID.fromString(caseId) : null;
        IncidentFeedback feedback = new IncidentFeedback(
                repository, prNumber, incidentId, sev, description, reviewCapability, parsedCaseId);
        return incidentFeedbackService.recordFeedback(feedback);
    }
}
