package io.casehub.devtown.app.mcp;

import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.devtown.app.governance.GovernanceQueryService;
import io.casehub.ledger.runtime.service.LedgerProvExportService;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.api.mcp.McpDomain;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.DefaultValue;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.Query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@McpDomain(value = "devtown/model", app = "devtown", summary = "DevTown domain model enrichment and state")
@GraphQLApi
@ApplicationScoped
public class GovernanceQueryResolver {

    @Inject GovernanceQueryService governanceQuery;
    @Inject LedgerProvExportService provExportService;
    @Inject CurrentPrincipal principal;
    @Inject CaseHubRuntime caseHubRuntime;
    @Inject io.casehub.devtown.app.MergeQueueService mergeQueueService;

    @Query
    @Description("Get current PR review queue status with counts by status and active reviews")
    public GovernanceQueryService.QueueStatus queueStatus() {
        return governanceQuery.queueStatus();
    }

    @Query
    @Description("Get recent PR review events from the ring buffer")
    public List<TrackedEvent> recentEvents(
            @Name("limit") @Description("Maximum events to return") @DefaultValue("50") int limit,
            @Name("since") @Description("ISO-8601 timestamp to filter events after") String since) {
        Instant sinceTime = since != null ? Instant.parse(since) : null;
        return governanceQuery.recentEvents(limit, sinceTime);
    }

    @Query
    @Description("Get overall system health metrics across all cases and agents")
    public GovernanceQueryService.SystemHealth systemHealth() {
        return governanceQuery.systemHealth();
    }

    @Query
    @Description("List detected problems: stalled cases, expired commitments, failed workers, queue SLA breaches")
    public List<GovernanceQueryService.Problem> problems(
            @Name("thresholdMinutes") @Description("Stall threshold in minutes") @DefaultValue("60") int thresholdMinutes) {
        return governanceQuery.problems(thresholdMinutes);
    }

    @Query
    @Description("Get detailed review status including timeline and capability progress")
    public ReviewDetailResult reviewDetail(
            @Name("caseId") @Description("Case UUID") UUID caseId) {
        String tenant = principal.tenancyId();
        var    detail = governanceQuery.reviewDetail(caseId, tenant);
        var    pr     = detail.pr();
        return new ReviewDetailResult(
                detail.caseId(),
                new PrInfo(pr.repo(), pr.prNumber(), pr.headSha(), pr.baseRef(),
                           pr.linesChanged(), pr.contributor(), pr.changedPaths()),
                detail.timeline(),
                detail.capabilities());
    }

    @Query
    @Description("Get health metrics for a specific reviewer: commitments, trust scores, decision history")
    public GovernanceQueryService.ReviewerHealth reviewerHealth(
            @Name("reviewerId") @Description("Reviewer actor ID") String reviewerId) {
        return governanceQuery.reviewerHealth(reviewerId);
    }

    @Query
    @Description("Get current merge queue state: queued PRs with priority scores, wait times, dependencies, and active batches")
    public GovernanceQueryService.MergeQueueStatus mergeQueue() {
        return governanceQuery.mergeQueue();
    }

    @Query
    @Description("Get batch state: PRs in the batch, risk level, bisection strategy")
    public GovernanceQueryService.BatchStatus batchStatus(
            @Name("batchCaseId") @Description("Case UUID of the batch") UUID batchCaseId) {
        return governanceQuery.batchStatus(batchCaseId);
    }

    @Query
    @Description("Get operational metrics: queue depth, active batches, wait times, throughput, failure rate, trust score, lane and batch size distribution")
    public GovernanceQueryService.MergeQueueMetrics mergeQueueMetrics() {
        return governanceQuery.mergeQueueMetrics();
    }

    @Query
    @Description("Per-repository batch failure rates from completed merge queue batches within the configured window")
    public List<io.casehub.devtown.app.MergeQueueService.RepositoryFailureRate> failureRatesByRepository() {
        return mergeQueueService.failureRateByRepository();
    }

    @Query
    @Description("Check per-repository batch failure rates against configured thresholds and fire alerts for repos exceeding them")
    public List<io.casehub.devtown.app.FailureRateAlertEvent> failureRateAlerts() {
        return mergeQueueService.evaluateFailureRateAlerts();
    }

    @Query
    @Description("Get agent channel message history for a case — dispatch, completion, decline, failure events with payloads")
    public List<AgentMessage> agentMessages(
            @Name("caseId") @Description("Case UUID") UUID caseId) {
        try {
            var events = caseHubRuntime.eventLog(caseId, java.util.Set.of(
                    io.casehub.api.model.event.CaseHubEventType.AGENT_DISPATCHED,
                    io.casehub.api.model.event.CaseHubEventType.AGENT_COMPLETED,
                    io.casehub.api.model.event.CaseHubEventType.AGENT_FAILED,
                    io.casehub.api.model.event.CaseHubEventType.WORKER_OUTCOME_DECLINED,
                    io.casehub.api.model.event.CaseHubEventType.WORKER_OUTCOME_FAILED,
                    io.casehub.api.model.event.CaseHubEventType.WORKER_OUTCOME_EXPIRED,
                    io.casehub.api.model.event.CaseHubEventType.ORCHESTRATION_ESCALATED));
            return events.stream().map(e -> new AgentMessage(
                    e.timestamp(), e.eventType().name(),
                    e.payload() != null ? e.payload().toString() : null
            )).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    @Query
    @Description("Export PROV-DM provenance record for a case (PROV-JSON-LD format)")
    public String exportProv(
            @Name("caseId") @Description("Case UUID") UUID caseId) {
        return provExportService.exportSubject(caseId, principal.tenancyId());
    }

    public record AgentMessage(Instant timestamp, String messageType, String payload) {}

    public record PrInfo(String repo, int prNumber, String headSha, String baseRef,
                         int linesChanged, String contributor, java.util.List<String> changedPaths) {}

    public record ReviewDetailResult(UUID caseId, PrInfo pr,
                                     java.util.List<GovernanceQueryService.EventEntry> timeline,
                                     java.util.List<GovernanceQueryService.CapabilityStatus> capabilities) {}

}
