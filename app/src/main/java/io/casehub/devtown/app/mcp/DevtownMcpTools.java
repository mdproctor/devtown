package io.casehub.devtown.app.mcp;

import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.api.model.event.CaseEventLogRecord;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.devtown.app.PrReviewCaseHub;
import io.casehub.devtown.review.PrPayload;
import io.casehub.ledger.runtime.service.LedgerProvExportService;
import io.casehub.ledger.runtime.service.TrustGateService;
import io.casehub.ledger.runtime.service.federation.TrustExportService;
import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.platform.api.memory.CaseMemoryStore;
import io.casehub.platform.api.memory.MemoryOrder;
import io.casehub.platform.api.memory.MemoryQuery;
import io.casehub.devtown.domain.memory.DevtownMemoryDomain;
import io.casehub.devtown.domain.memory.ModulePathNormalizer;
import io.casehub.qhorus.runtime.message.Commitment;
import io.casehub.qhorus.runtime.store.CommitmentStore;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.WrapBusinessError;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    PrReviewCaseHub caseHub;

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

        // Work items not directly accessible without WorkItemStore — placeholder
        int pendingWorkItems = 0;

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
        description = "List detected problems: stalled cases, expired commitments, failed workers"
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

        return problems;
    }

    @Tool(
        name = "inspect_review",
        description = "Get detailed review status including timeline and capability progress"
    )
    public ReviewDetail inspectReview(
        @ToolArg(name = "case_id", description = "Case UUID", required = true) String caseIdStr,
        @ToolArg(name = "tenancy_id", description = "Tenancy ID", required = false) String tenancyId
    ) {
        UUID caseId = UUID.fromString(caseIdStr);
        CaseInfo caseInfo = tracker.getCase(caseId);

        if (caseInfo == null) {
            throw new IllegalArgumentException("Case not found: " + caseId);
        }

        String tenant = tenancyId != null ? tenancyId : TenancyConstants.DEFAULT_TENANT_ID;

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
        @ToolArg(name = "reviewer_id", description = "Reviewer actor ID", required = true) String reviewerId,
        @ToolArg(name = "tenancy_id", description = "Tenancy ID", required = false) String tenancyId
    ) {
        String tenant = tenancyId != null ? tenancyId : TenancyConstants.DEFAULT_TENANT_ID;

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
        @ToolArg(name = "file_path", description = "File path within repo", required = true) String filePath,
        @ToolArg(name = "tenancy_id", description = "Tenancy ID", required = false) String tenancyId
    ) {
        String tenant = tenancyId != null ? tenancyId : TenancyConstants.DEFAULT_TENANT_ID;

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
        @ToolArg(name = "case_id", description = "Case UUID", required = true) String caseIdStr,
        @ToolArg(name = "tenancy_id", description = "Tenancy ID", required = false) String tenancyId
    ) {
        UUID caseId = UUID.fromString(caseIdStr);
        String tenant = tenancyId != null ? tenancyId : TenancyConstants.DEFAULT_TENANT_ID;

        return provExportService.exportSubject(caseId, tenant);
    }

    // ==================== Write Tools ====================

    @Tool(
        name = "retry_reviewer",
        description = "Retry a specific capability by signaling the case with a null context value"
    )
    public RetryResult retryReviewer(
        @ToolArg(name = "case_id", description = "Case UUID", required = true) String caseIdStr,
        @ToolArg(name = "capability", description = "Capability to retry", required = true) String capability,
        @ToolArg(name = "tenancy_id", description = "Tenancy ID", required = false) String tenancyId
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

        String tenant = tenancyId != null ? tenancyId : TenancyConstants.DEFAULT_TENANT_ID;

        // Signal case with null to trigger retry
        caseHubRuntime.signal(caseId, contextKey, null);

        return new RetryResult(caseId, capability, "RETRY_SIGNALED");
    }

    @Tool(
        name = "reroute_review",
        description = "Cancel current case and start a fresh review with the same PR payload"
    )
    public RerouteResult rerouteReview(
        @ToolArg(name = "case_id", description = "Case UUID to cancel", required = true) String caseIdStr,
        @ToolArg(name = "tenancy_id", description = "Tenancy ID", required = false) String tenancyId
    ) {
        UUID oldCaseId = UUID.fromString(caseIdStr);

        CaseInfo caseInfo = tracker.getCase(oldCaseId);
        if (caseInfo == null) {
            throw new IllegalArgumentException("Case not found: " + oldCaseId);
        }

        String tenant = tenancyId != null ? tenancyId : TenancyConstants.DEFAULT_TENANT_ID;

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
        var initialContext = new HashMap<String, Object>();
        initialContext.put("pr", prContext);

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
        @ToolArg(name = "reason", description = "Override reason", required = true) String reason,
        @ToolArg(name = "tenancy_id", description = "Tenancy ID", required = false) String tenancyId
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

        String tenant = tenancyId != null ? tenancyId : TenancyConstants.DEFAULT_TENANT_ID;

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
}
