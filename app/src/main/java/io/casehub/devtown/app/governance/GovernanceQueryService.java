package io.casehub.devtown.app.governance;

import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.api.model.event.CaseEventLogRecord;
import io.casehub.api.model.event.CaseHubEventType;
import io.casehub.devtown.app.MergeQueueService;
import io.casehub.devtown.app.mcp.CaseInfo;
import io.casehub.devtown.app.mcp.PrReviewCaseTracker;
import io.casehub.devtown.app.mcp.TrackedEvent;
import io.casehub.devtown.merge.BatchRecord;
import io.casehub.devtown.queue.QueuedPr;
import io.casehub.devtown.review.PrPayload;
import io.casehub.ledger.runtime.service.TrustGateService;
import io.casehub.ledger.runtime.service.federation.TrustExportService;
import io.casehub.qhorus.api.message.Commitment;
import io.casehub.qhorus.api.store.CommitmentStore;
import io.casehub.work.api.WorkItemStatus;
import io.casehub.work.runtime.model.WorkItem;
import io.casehub.work.runtime.repository.WorkItemQuery;
import io.casehub.work.runtime.repository.WorkItemStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@ApplicationScoped
public class GovernanceQueryService {

    public static final Map<String, String> CAPABILITY_CONTEXT_KEYS = Map.of(
        "code-analysis", "codeAnalysis",
        "security-review", "securityReview",
        "architecture-review", "architectureReview",
        "style-review", "styleCheck",
        "test-coverage", "testCoverage",
        "performance-analysis", "performanceAnalysis"
    );

    private final PrReviewCaseTracker tracker;
    private final CommitmentStore commitmentStore;
    private final TrustExportService trustExportService;
    private final TrustGateService trustGateService;
    private final WorkItemStore workItemStore;
    private final MergeQueueService mergeQueueService;
    private final CaseHubRuntime caseHubRuntime;

    @Inject
    public GovernanceQueryService(
            PrReviewCaseTracker tracker,
            CommitmentStore commitmentStore,
            TrustExportService trustExportService,
            TrustGateService trustGateService,
            WorkItemStore workItemStore,
            MergeQueueService mergeQueueService,
            CaseHubRuntime caseHubRuntime) {
        this.tracker = tracker;
        this.commitmentStore = commitmentStore;
        this.trustExportService = trustExportService;
        this.trustGateService = trustGateService;
        this.workItemStore = workItemStore;
        this.mergeQueueService = mergeQueueService;
        this.caseHubRuntime = caseHubRuntime;
    }

    // ── Records ──────────────────────────────────────────────

    public record QueueStatus(int total, Map<String, Integer> countsByStatus, List<ActiveReview> reviews) {}

    public record ActiveReview(UUID caseId, String repo, int prNumber, String contributor,
                               int linesChanged, String status, Instant startedAt, Instant lastEventAt) {}

    public record SystemHealth(int activeCases, int fleetSize, Map<String, Double> avgTrustByCapability,
                               int openCommitments, int pendingWorkItems) {}

    public record ReviewDetail(UUID caseId, PrPayload pr, List<EventEntry> timeline,
                               List<CapabilityStatus> capabilities) {}

    public record EventEntry(Instant timestamp, String eventType, String actor, String summary) {}

    public record CapabilityStatus(String name, String status, String outcome, Instant completedAt) {}

    public record ReviewerHealth(String reviewerId, int openCommitments, Map<String, Double> trustByCapability,
                                 Map<String, Double> trustByDimension, int totalDecisions,
                                 List<RecentOutcome> recentOutcomes) {}

    public record RecentOutcome(UUID caseId, String capability, String outcome, Instant timestamp) {}

    public record Problem(String category, String severity, String description,
                          UUID caseId, String actorId, Instant since) {}

    public record MergeQueueStatus(int queuedCount, int activeBatchCount,
                                   List<QueuedPrEntry> queuedPrs, List<ActiveBatchEntry> activeBatches) {}

    public record QueuedPrEntry(int number, String repository, String headSha, String author,
                                double trustScore, String priorityLane, Instant enqueuedAt,
                                long waitMinutes, Set<Integer> dependsOn) {}

    public record ActiveBatchEntry(UUID caseId, String batchId, int prCount, String riskLevel) {}

    public record BatchStatus(String batchId, UUID caseId, List<BatchPrEntry> prs,
                              String riskLevel, String bisectionStrategy) {}

    public record BatchPrEntry(int number, String repository, String headSha,
                               String author, double trustScore, String lane) {}

    public record MergeQueueMetrics(int queueDepth, int activeBatches, long oldestWaitMinutes,
                                    long avgWaitMinutes, double avgTrustScore,
                                    Map<String, Integer> countsByLane, int throughput24h,
                                    double failureRate, Map<Integer, Integer> batchSizeDistribution) {}

    public record ReviewerFleetEntry(String actorId, Map<String, Double> trustByCapability,
                                     String maturityPhase, int openCommitments, int totalDecisions) {}

    public record TriageItem(UUID workItemId, String prRef, String decisionType, String candidateGroup,
                             Instant expiresAt, String escalationStage, Instant createdAt, UUID caseId) {}

    public record ReviewListEntry(UUID caseId, String repo, int prNumber, String contributor,
                                  String status, Instant startedAt, Instant lastEventAt) {}

    // ── Query methods ────────────────────────────────────────

    public QueueStatus queueStatus() {
        List<CaseInfo> active = tracker.activeCases();
        Map<String, Integer> countsByStatus = new HashMap<>();

        List<ActiveReview> reviews = active.stream()
            .map(c -> {
                String statusStr = c.status().name();
                countsByStatus.merge(statusStr, 1, Integer::sum);
                return new ActiveReview(
                    c.caseId(), c.payload().repo(), c.payload().prNumber(),
                    c.payload().contributor(), c.payload().linesChanged(),
                    statusStr, c.startedAt(), c.lastEventAt()
                );
            })
            .toList();

        return new QueueStatus(active.size(), countsByStatus, reviews);
    }

    public List<TrackedEvent> recentEvents(int limit, Instant since) {
        return tracker.recentEvents(limit, since);
    }

    public SystemHealth systemHealth() {
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
        if (workItemStore != null) {
            pendingWorkItems = workItemStore
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

    public List<Problem> problems(int thresholdMinutes) {
        List<Problem> problems = new ArrayList<>();

        // Stalled cases
        for (CaseInfo stalled : tracker.stalledCases(thresholdMinutes)) {
            problems.add(new Problem(
                "stalled_case",
                "warning",
                String.format("PR review stalled for %s#%d — no progress for %d+ minutes",
                    stalled.payload().repo(), stalled.payload().prNumber(), thresholdMinutes),
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
                    expired.obligor(), expired.messageType()),
                expired.channelId(),
                expired.obligor(),
                expired.expiresAt()
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

        // Queue SLA breaches — delegate to service for lane-specific SLA detection
        for (var breach : mergeQueueService.detectSlaBreaches()) {
            problems.add(new Problem(
                "queue_sla_breach",
                "warning",
                String.format("PR #%d (%s) has waited %d min — exceeds %s SLA (%d min)",
                    breach.pr().number(), breach.pr().lane(),
                    breach.waited().toMinutes(),
                    breach.pr().lane(), breach.sla().toMinutes()),
                null,
                breach.pr().author(),
                breach.pr().enqueuedAt()
            ));
        }

        return problems;
    }

    public ReviewDetail reviewDetail(UUID caseId, String tenant) {
        CaseInfo caseInfo = tracker.getCase(caseId);

        if (caseInfo == null) {
            throw new IllegalArgumentException("Case not found: " + caseId);
        }

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

    public ReviewerHealth reviewerHealth(String reviewerId) {
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

    public MergeQueueStatus mergeQueue() {
        Instant now = Instant.now();
        List<QueuedPr> queued = mergeQueueService.queuedPrs();
        Map<String, BatchRecord> batches = mergeQueueService.activeBatches();

        List<QueuedPrEntry> prEntries = queued.stream()
            .map(pr -> new QueuedPrEntry(
                pr.number(),
                pr.repository(),
                pr.headSha(),
                pr.author(),
                pr.trustScore(),
                pr.lane().name(),
                pr.enqueuedAt(),
                Duration.between(pr.enqueuedAt(), now).toMinutes(),
                pr.dependsOn()
            ))
            .toList();

        List<ActiveBatchEntry> batchEntries = batches.values().stream()
            .map(b -> new ActiveBatchEntry(b.caseId(), b.batchId(), b.prNumbers().size(), "ROUTINE"))
            .toList();

        return new MergeQueueStatus(queued.size(), batches.size(), prEntries, batchEntries);
    }

    public BatchStatus batchStatus(UUID batchCaseId) {
        Map<String, BatchRecord> batches = mergeQueueService.activeBatches();
        BatchRecord batch = batches.values().stream()
            .filter(b -> b.caseId().equals(batchCaseId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("No active batch found for case: " + batchCaseId));

        // BatchRecord does not carry full PR details — return PR numbers with placeholders
        List<BatchPrEntry> prEntries = batch.prNumbers().stream()
            .map(prNum -> new BatchPrEntry(prNum, batch.repository(), "", "", 0.0, ""))
            .toList();

        return new BatchStatus(batch.batchId(), batchCaseId, prEntries, "ROUTINE", "trust-weighted");
    }

    public MergeQueueMetrics mergeQueueMetrics() {
        Instant now = Instant.now();
        List<QueuedPr> queued = mergeQueueService.queuedPrs();
        Map<String, BatchRecord> batches = mergeQueueService.activeBatches();

        long oldestWaitMinutes = queued.stream()
            .mapToLong(pr -> Duration.between(pr.enqueuedAt(), now).toMinutes())
            .max()
            .orElse(0);

        long avgWaitMinutes = queued.isEmpty() ? 0 :
            (long) queued.stream()
                .mapToLong(pr -> Duration.between(pr.enqueuedAt(), now).toMinutes())
                .average().orElse(0);

        double avgTrust = queued.stream()
            .mapToDouble(QueuedPr::trustScore)
            .average()
            .orElse(0.0);

        Map<String, Integer> countsByLane = new HashMap<>();
        for (QueuedPr pr : queued) {
            countsByLane.merge(pr.lane().name(), 1, Integer::sum);
        }

        List<BatchRecord> completed = mergeQueueService.completedBatches(Duration.ofHours(24));
        int throughput24h = completed.size();

        double failureRate = mergeQueueService.aggregateFailureRate();

        Map<Integer, Integer> batchSizeDist = new HashMap<>();
        for (BatchRecord batch : completed) {
            batchSizeDist.merge(batch.prNumbers().size(), 1, Integer::sum);
        }

        return new MergeQueueMetrics(
            queued.size(), batches.size(), oldestWaitMinutes, avgWaitMinutes,
            avgTrust, countsByLane, throughput24h, failureRate, batchSizeDist);
    }

    public List<ReviewerFleetEntry> reviewerFleet() {
        var trustExport = trustExportService.exportAll(0.0);
        var openByActor = commitmentStore.findAllOpen().stream()
            .collect(Collectors.groupingBy(Commitment::obligor, Collectors.counting()));

        return trustExport.actors().stream().map(actor -> {
            var capScores = trustGateService.allCapabilityScores(actor.actorId());
            int totalDecisions = capScores.keySet().stream()
                .mapToInt(cap -> trustGateService.decisionCount(actor.actorId(), cap))
                .sum();
            // Maturity phase: Bootstrap (0 decisions), Emerging (<min obs), Active (>=min obs)
            String phase = totalDecisions == 0 ? "Bootstrap" : totalDecisions < 5 ? "Emerging" : "Active";
            int openCommitments = openByActor.getOrDefault(actor.actorId(), 0L).intValue();
            return new ReviewerFleetEntry(actor.actorId(), capScores, phase, openCommitments, totalDecisions);
        }).toList();
    }

    public List<TriageItem> triageItems() {
        if (workItemStore == null) return List.of();
        var humanDecisions = workItemStore.scan(
            WorkItemQuery.builder().status(WorkItemStatus.PENDING).category("human-decision").build());
        var humanOversight = workItemStore.scan(
            WorkItemQuery.builder().status(WorkItemStatus.PENDING).category("human-oversight").build());

        var all = new ArrayList<WorkItem>();
        all.addAll(humanDecisions);
        all.addAll(humanOversight);

        return all.stream().map(wi -> new TriageItem(
            wi.id, "", wi.category, wi.candidateGroups,
            wi.expiresAt, "", wi.createdAt, wi.parentId
        )).sorted(Comparator.comparing(t -> t.expiresAt() != null ? t.expiresAt() : Instant.MAX))
        .toList();
    }

    public List<ReviewListEntry> reviewsList() {
        return tracker.activeCases().stream().map(c -> new ReviewListEntry(
            c.caseId(), c.payload().repo(), c.payload().prNumber(),
            c.payload().contributor(), c.status().name(),
            c.startedAt(), c.lastEventAt()
        )).toList();
    }

    public ReviewDetail reviewDetail(UUID caseId) {
        return reviewDetail(caseId, "default");
    }
}
