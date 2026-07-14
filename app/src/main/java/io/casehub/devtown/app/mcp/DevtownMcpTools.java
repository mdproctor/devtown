package io.casehub.devtown.app.mcp;

import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.devtown.app.MergeQueueService;
import io.casehub.devtown.app.PrReviewCaseHub;
import io.casehub.devtown.app.governance.GovernanceQueryService;
import io.casehub.devtown.app.ledger.IncidentFeedbackService;
import io.casehub.devtown.domain.IncidentFeedback;
import io.casehub.devtown.domain.IncidentFeedbackResult;
import io.casehub.devtown.domain.IncidentSeverity;
import io.casehub.devtown.domain.memory.DevtownMemoryDomain;
import io.casehub.devtown.domain.memory.ModulePathNormalizer;
import io.casehub.devtown.domain.queue.PriorityLane;
import io.casehub.devtown.queue.QueuedPr;
import io.casehub.ledger.runtime.service.LedgerProvExportService;
import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.neocortex.memory.MemoryOrder;
import io.casehub.neocortex.memory.MemoryQuery;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.WrapBusinessError;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
@WrapBusinessError({IllegalArgumentException.class, IllegalStateException.class})
public class DevtownMcpTools {

    @Inject
    GovernanceQueryService governanceQuery;

    @Inject
    PrReviewCaseTracker tracker;

    @Inject
    CaseHubRuntime caseHubRuntime;

    @Inject
    LedgerProvExportService provExportService;

    @Inject
    Instance<CaseMemoryStore> memoryStoreInstance;

    @Inject
    PrReviewCaseHub caseHub;

    @Inject
    CurrentPrincipal principal;

    @Inject
    IncidentFeedbackService incidentFeedbackService;

    @Inject
    MergeQueueService mergeQueueService;
    @Inject
    io.casehub.devtown.app.trust.EvidentialViolationStore violationStore;
    @Inject
    io.casehub.devtown.app.CbrWeightOverrideStore         cbrWeightOverrides;
    @Inject
    jakarta.enterprise.inject.Instance<io.casehub.devtown.review.CbrRetrievalService> cbrRetrievalService;


    @ConfigProperty(name = "devtown.policy.human-approval-threshold", defaultValue = "500")
    int humanApprovalThreshold;

    @ConfigProperty(name = "devtown.policy.security-review-required", defaultValue = "true")
    boolean securityReviewRequired;

    @ConfigProperty(name = "devtown.policy.require-senior-approval", defaultValue = "false")
    boolean requireSeniorApproval;

    // ==================== Response Records ====================
    // Read method records now come from GovernanceQueryService
    // Write method records stay here

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

    public record EnqueueResult(int prNumber, String lane, String status) {}

    public record DequeueResult(int prNumber, boolean removed, String status) {}

    // ==================== Read Tools ====================

    @Tool(
        name = "get_queue_status",
        description = "Get current PR review queue status with counts by status and active reviews"
    )
    public GovernanceQueryService.QueueStatus getQueueStatus() {
        return governanceQuery.queueStatus();
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
        return governanceQuery.recentEvents(effectiveLimit, sinceTime);
    }

    @Tool(
        name = "get_system_health",
        description = "Get overall system health metrics across all cases and agents"
    )
    public GovernanceQueryService.SystemHealth getSystemHealth() {
        return governanceQuery.systemHealth();
    }

    @Tool(
        name = "list_problems",
        description = "List detected problems: stalled cases, expired commitments, failed workers, queue SLA breaches"
    )
    public List<GovernanceQueryService.Problem> listProblems(
        @ToolArg(name = "threshold_minutes", description = "Stall threshold in minutes", required = false) Integer thresholdMinutes
    ) {
        int threshold = thresholdMinutes != null ? thresholdMinutes : 60;
        return governanceQuery.problems(threshold);
    }

    @Tool(
        name = "inspect_review",
        description = "Get detailed review status including timeline and capability progress"
    )
    public GovernanceQueryService.ReviewDetail inspectReview(
        @ToolArg(name = "case_id", description = "Case UUID", required = true) String caseIdStr
    ) {
        UUID caseId = UUID.fromString(caseIdStr);
        String tenant = principal.tenancyId();
        return governanceQuery.reviewDetail(caseId, tenant);
    }

    @Tool(
        name = "get_reviewer_health",
        description = "Get health metrics for a specific reviewer: commitments, trust scores, decision history"
    )
    public GovernanceQueryService.ReviewerHealth getReviewerHealth(
        @ToolArg(name = "reviewer_id", description = "Reviewer actor ID", required = true) String reviewerId
    ) {
        return governanceQuery.reviewerHealth(reviewerId);
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
            name = "search_memory_by_contributor",
            description = "Search case memory for a contributor's review history — outcomes, patterns, and prior decisions"
    )
    public List<PriorDecision> searchMemoryByContributor(
            @ToolArg(name = "contributor", description = "Contributor username") String contributor,
            @ToolArg(name = "limit", description = "Max results (default 20)", required = false) Integer limit
                                                        ) {
        if (!memoryStoreInstance.isResolvable()) {return List.of();}
        int max = limit != null ? limit : 20;
        var memories = memoryStoreInstance.get().query(
                MemoryQuery.forEntity(
                                   DevtownMemoryDomain.CONTRIBUTOR_PREFIX + contributor,
                                   DevtownMemoryDomain.SOFTWARE_REVIEW,
                                   principal.tenancyId())
                           .withLimit(max)
                           .withOrder(MemoryOrder.CHRONOLOGICAL));
        return memories.stream()
                       .map(m -> new PriorDecision(
                               null, m.attributes().getOrDefault("repo", "unknown"), 0,
                               m.attributes().getOrDefault("capability", "unknown"),
                               m.text(), m.createdAt()))
                       .toList();
    }

    @Tool(
            name = "search_memory_by_capability",
            description = "Search case memory for all entries related to a specific review capability across contributors"
    )
    public List<PriorDecision> searchMemoryByCapability(
            @ToolArg(name = "capability", description = "Capability name (e.g. security-review, architecture-review)") String capability,
            @ToolArg(name = "limit", description = "Max results (default 20)", required = false) Integer limit
                                                       ) {
        if (!memoryStoreInstance.isResolvable()) {return List.of();}
        int max = limit != null ? limit : 20;
        var memories = memoryStoreInstance.get().scan(
                new io.casehub.neocortex.memory.MemoryScanRequest(
                        principal.tenancyId(),
                        DevtownMemoryDomain.SOFTWARE_REVIEW.name(),
                        "capability", capability, max, null));
        return memories.stream()
                       .map(m -> new PriorDecision(
                               null, m.attributes().getOrDefault("repo", "unknown"), 0,
                               capability, m.text(), m.createdAt()))
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
    public GovernanceQueryService.MergeQueueStatus getMergeQueue() {
        return governanceQuery.mergeQueue();
    }

    @Tool(
        name = "get_batch_status",
        description = "Get batch state: PRs in the batch, risk level, bisection strategy"
    )
    public GovernanceQueryService.BatchStatus getBatchStatus(
        @ToolArg(name = "batch_case_id", description = "Case UUID of the batch", required = true) String batchCaseIdStr
    ) {
        UUID batchCaseId = UUID.fromString(batchCaseIdStr);
        return governanceQuery.batchStatus(batchCaseId);
    }

    @Tool(
        name = "get_merge_queue_metrics",
        description = "Get operational metrics: queue depth, active batches, wait times, throughput, failure rate, trust score, lane and batch size distribution"
    )
    public GovernanceQueryService.MergeQueueMetrics getMergeQueueMetrics() {
        return governanceQuery.mergeQueueMetrics();
    }

    @Tool(
            name = "get_failure_rates_by_repository",
            description = "Per-repository batch failure rates from completed merge queue batches within the configured window"
    )
    public List<io.casehub.devtown.app.MergeQueueService.RepositoryFailureRate> getFailureRatesByRepository() {
        return mergeQueueService.failureRateByRepository();
    }

    @Tool(
            name = "evaluate_failure_rate_alerts",
            description = "Check per-repository batch failure rates against configured thresholds and fire alerts for repos exceeding them"
    )
    public List<io.casehub.devtown.app.FailureRateAlertEvent> evaluateFailureRateAlerts() {
        return mergeQueueService.evaluateFailureRateAlerts();
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

        String contextKey = GovernanceQueryService.CAPABILITY_CONTEXT_KEYS.get(capability);
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

        String contextKey = GovernanceQueryService.CAPABILITY_CONTEXT_KEYS.get(capability);
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

        QueuedPr pr = new QueuedPr(prNumber, repo, headSha, author, trustScore, lane, Instant.now(), Set.of());
        boolean inserted = mergeQueueService.enqueue(pr);
        return new EnqueueResult(prNumber, lane.name(), inserted ? "ENQUEUED" : "ALREADY_QUEUED");
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

        boolean removed = mergeQueueService.dequeue(prNumber, repo);
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

    @Tool(
            name = "get_evidential_violations",
            description = "List evidential benchmark violations from FLAGGED attestations — shows which checks failed and why"
    )
    public List<io.casehub.devtown.app.trust.EvidentialViolationStore.ViolationRecord> getEvidentialViolations(
            @ToolArg(name = "commitmentId", description = "Optional — filter by commitment UUID", required = false) String commitmentId
                                                                                                              ) {
        if (commitmentId != null) {
            return violationStore.get(commitmentId)
                                 .map(List::of)
                                 .orElse(List.of());
        }
        return violationStore.all();
    }

    @Tool(
            name = "find_similar_cases",
            description = "Find cases similar to a PR using CBR similarity search — returns ranked precedents with similarity scores and capability outcomes"
    )
    public List<io.casehub.devtown.domain.cbr.Precedent> findSimilarCases(
            @ToolArg(name = "repo", description = "GitHub repo slug (e.g. casehubio/devtown)") String repo,
            @ToolArg(name = "prNumber", description = "PR number") int prNumber,
            @ToolArg(name = "contributor", description = "PR author username") String contributor,
            @ToolArg(name = "linesChanged", description = "Total lines changed") int linesChanged,
            @ToolArg(name = "changedPaths", description = "Comma-separated list of changed file paths") String changedPaths
                                                                         ) {
        if (!cbrRetrievalService.isResolvable()) {
            return List.of();
        }
        var vector = io.casehub.devtown.domain.cbr.PrFeatureVector.from(
                repo, prNumber, contributor, linesChanged,
                java.util.Arrays.stream(changedPaths.split(",")).map(String::trim).toList());
        return cbrRetrievalService.get().findSimilar(vector, repo, principal.tenancyId());
    }

    @Tool(
            name = "get_cbr_weight_status",
            description = "Show current CBR similarity weights — base preferences plus any dynamic adjustments from outcome feedback"
    )
    public java.util.Map<String, Object> getCbrWeightStatus() {
        return java.util.Map.of(
                "overrides", cbrWeightOverrides.currentOverrides(),
                "sampleCount", cbrWeightOverrides.sampleCount()
                               );
    }

    @Tool(
            name = "get_agent_messages",
            description = "Get agent channel message history for a case — dispatch, completion, decline, failure events with payloads"
    )
    public List<AgentMessage> getAgentMessages(
            @ToolArg(name = "case_id", description = "Case UUID") String caseIdStr
                                              ) {
        UUID caseId = UUID.fromString(caseIdStr);
        try {
            var events = caseHubRuntime.eventLog(caseId, java.util.Set.of(
                    io.casehub.api.model.event.CaseHubEventType.AGENT_DISPATCHED,
                    io.casehub.api.model.event.CaseHubEventType.AGENT_COMPLETED,
                    io.casehub.api.model.event.CaseHubEventType.AGENT_FAILED,
                    io.casehub.api.model.event.CaseHubEventType.WORKER_OUTCOME_DECLINED,
                    io.casehub.api.model.event.CaseHubEventType.WORKER_OUTCOME_FAILED,
                    io.casehub.api.model.event.CaseHubEventType.WORKER_OUTCOME_EXPIRED,
                    io.casehub.api.model.event.CaseHubEventType.ORCHESTRATION_ESCALATED
                                                                         )).toCompletableFuture().join();
            return events.stream().map(e -> new AgentMessage(
                    e.timestamp(), e.eventType().name(),
                    e.payload() != null ? e.payload().toString() : null
            )).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    public record AgentMessage(
            java.time.Instant timestamp, String messageType, String payload
    ) {}
}
