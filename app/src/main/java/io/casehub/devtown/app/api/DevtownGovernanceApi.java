package io.casehub.devtown.app.api;

import io.casehub.devtown.app.governance.GovernanceQueryService;
import io.casehub.devtown.app.governance.PagedResult;
import io.casehub.devtown.app.mcp.TrackedEvent;
import io.casehub.devtown.domain.DevtownRoles;
import io.casehub.devtown.domain.governance.GovernancePreferenceKeys;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PathParam;
import io.casehub.platform.api.mcp.PlatformMutation;
import io.casehub.platform.api.mcp.PlatformQuery;
import io.casehub.platform.api.mcp.RestPath;
import io.casehub.platform.api.preferences.PreferenceProvider;
import io.casehub.platform.api.preferences.Preferences;
import io.casehub.platform.api.preferences.SettingsScope;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.QueryParam;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@McpDomain(value = "devtown/governance", app = "devtown", basePath = "/api/devtown/governance", summary = "Development governance — code review policies, merge rules")
@ApplicationScoped
public class DevtownGovernanceApi {

    @Inject GovernanceQueryService queryService;
    @Inject PreferenceProvider preferenceProvider;
    @Inject
    io.casehub.work.api.spi.WorkItemOperations workItemOperations;

    @RolesAllowed({DevtownRoles.ADMIN, DevtownRoles.ENGINEER, DevtownRoles.AUDITOR, DevtownRoles.SERVICE})
    @PlatformQuery("Get queue status")
    @RestPath("/queue-status")
    public GovernanceQueryService.QueueStatus queueStatus() {
        return queryService.queueStatus();
    }

    @RolesAllowed({DevtownRoles.ADMIN, DevtownRoles.ENGINEER, DevtownRoles.AUDITOR, DevtownRoles.SERVICE})
    @PlatformQuery("Get recent governance events")
    @RestPath("/recent-events")
    public List<TrackedEvent> recentEvents(
            @QueryParam("limit") @DefaultValue("50") int limit,
            @QueryParam("since") String since) {
        Instant sinceTime = since != null ? Instant.parse(since) : null;
        return queryService.recentEvents(limit, sinceTime);
    }

    @RolesAllowed({DevtownRoles.ADMIN, DevtownRoles.ENGINEER, DevtownRoles.AUDITOR, DevtownRoles.SERVICE})
    @PlatformQuery("Get system health")
    @RestPath("/system-health")
    public GovernanceQueryService.SystemHealth systemHealth() {
        return queryService.systemHealth();
    }

    @RolesAllowed({DevtownRoles.ADMIN, DevtownRoles.ENGINEER, DevtownRoles.AUDITOR, DevtownRoles.SERVICE})
    @PlatformQuery("Get governance problems")
    @RestPath("/problems")
    public PagedResult<GovernanceQueryService.Problem> problems(
            @QueryParam("threshold_minutes") @DefaultValue("60") int thresholdMinutes,
            @QueryParam("cursor") String cursor,
            @QueryParam("limit") @DefaultValue("50") int limit) {
        return PagedResult.paginate(queryService.problems(thresholdMinutes), cursor, limit);
    }

    @RolesAllowed({DevtownRoles.ADMIN, DevtownRoles.ENGINEER, DevtownRoles.AUDITOR, DevtownRoles.SERVICE})
    @PlatformQuery("Get merge queue status")
    @RestPath("/merge-queue")
    public GovernanceQueryService.MergeQueueStatus mergeQueue() {
        return queryService.mergeQueue();
    }

    @RolesAllowed({DevtownRoles.ADMIN, DevtownRoles.ENGINEER, DevtownRoles.AUDITOR, DevtownRoles.SERVICE})
    @PlatformQuery("Get merge queue metrics")
    @RestPath("/merge-queue/metrics")
    public GovernanceQueryService.MergeQueueMetrics mergeQueueMetrics() {
        return queryService.mergeQueueMetrics();
    }

    @RolesAllowed({DevtownRoles.ADMIN, DevtownRoles.ENGINEER, DevtownRoles.AUDITOR, DevtownRoles.SERVICE})
    @PlatformQuery("Get merge batch status")
    @RestPath("/merge-queue/batch/{batchId}")
    public GovernanceQueryService.BatchStatus batchStatus(@PathParam UUID batchId) {
        return queryService.batchStatus(batchId);
    }

    @RolesAllowed({DevtownRoles.ADMIN, DevtownRoles.ENGINEER, DevtownRoles.AUDITOR, DevtownRoles.SERVICE})
    @PlatformQuery("Get triage items")
    @RestPath("/triage")
    public PagedResult<GovernanceQueryService.TriageItem> triageItems(
            @QueryParam("cursor") String cursor,
            @QueryParam("limit") @DefaultValue("50") int limit) {
        return PagedResult.paginate(queryService.triageItems(), cursor, limit);
    }

    @RolesAllowed({DevtownRoles.ADMIN, DevtownRoles.ENGINEER, DevtownRoles.AUDITOR, DevtownRoles.SERVICE})
    @PlatformMutation("Claim a triage work item")
    @RestPath("/triage/{workItemId}/claim")
    public io.casehub.work.api.WorkItem claimTriageItem(
            @PathParam UUID workItemId,
            @QueryParam("claimant") @DefaultValue("dev-user") String claimant) {
        return workItemOperations.claim(workItemId, claimant);
    }

    public record TriageDecision(String outcome) {}

    @RolesAllowed({DevtownRoles.ADMIN, DevtownRoles.ENGINEER, DevtownRoles.AUDITOR, DevtownRoles.SERVICE})
    @PlatformMutation("Decide a triage work item")
    @RestPath("/triage/{workItemId}/decide")
    public io.casehub.work.api.WorkItem decideTriageItem(
            @PathParam UUID workItemId,
            TriageDecision decision) {
        String actor = "dev-user";
        return switch (decision.outcome().toUpperCase()) {
            case "APPROVED" -> workItemOperations.complete(workItemId, actor, null, "APPROVED");
            case "REJECTED" -> workItemOperations.reject(workItemId, actor, "Rejected by reviewer", "REJECTED");
            case "BLOCKED" -> workItemOperations.suspend(workItemId, actor, "Blocked by reviewer");
            default -> throw new IllegalArgumentException("Unknown outcome: " + decision.outcome());
        };
    }


    @RolesAllowed({DevtownRoles.ADMIN, DevtownRoles.ENGINEER, DevtownRoles.AUDITOR, DevtownRoles.SERVICE})
    @PlatformQuery("Get SLA comparison")
    @RestPath("/sla-comparison")
    public GovernanceQueryService.SlaComparison slaComparison() {
        return queryService.slaComparison();
    }

    @RolesAllowed({DevtownRoles.ADMIN, DevtownRoles.ENGINEER, DevtownRoles.AUDITOR, DevtownRoles.SERVICE})
    @PlatformQuery("Get governance preferences")
    @RestPath("/preferences")
    public Map<String, Map<String, String>> preferences() {
        Preferences prefs = preferenceProvider.resolve(SettingsScope.root("casehubio"));
        return Map.of(
                "refresh",
                Map.of(
                        "operational", prefs.getOrDefault(GovernancePreferenceKeys.REFRESH_OPERATIONAL).value(),
                        "metrics", prefs.getOrDefault(GovernancePreferenceKeys.REFRESH_METRICS).value(),
                        "caseDetail", prefs.getOrDefault(GovernancePreferenceKeys.REFRESH_CASE_DETAIL).value()));
    }
}
