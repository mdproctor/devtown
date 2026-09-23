package io.casehub.devtown.app.api;

import io.casehub.devtown.app.governance.GovernanceQueryService;
import io.casehub.devtown.app.governance.PagedResult;
import io.casehub.devtown.app.mcp.TrackedEvent;
import io.casehub.devtown.domain.governance.GovernancePreferenceKeys;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PathParam;
import io.casehub.platform.api.mcp.PlatformQuery;
import io.casehub.platform.api.mcp.RestPath;
import io.casehub.platform.api.preferences.PreferenceProvider;
import io.casehub.platform.api.preferences.Preferences;
import io.casehub.platform.api.preferences.SettingsScope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.QueryParam;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@McpDomain(value = "devtown/governance", app = "devtown", basePath = "/api/devtown/governance")
@ApplicationScoped
public class DevtownGovernanceApi {

    @Inject GovernanceQueryService queryService;
    @Inject PreferenceProvider preferenceProvider;

    @PlatformQuery("Get queue status")
    @RestPath("/queue-status")
    public GovernanceQueryService.QueueStatus queueStatus() {
        return queryService.queueStatus();
    }

    @PlatformQuery("Get recent governance events")
    @RestPath("/recent-events")
    public List<TrackedEvent> recentEvents(
            @QueryParam("limit") @DefaultValue("50") int limit,
            @QueryParam("since") String since) {
        Instant sinceTime = since != null ? Instant.parse(since) : null;
        return queryService.recentEvents(limit, sinceTime);
    }

    @PlatformQuery("Get system health")
    @RestPath("/system-health")
    public GovernanceQueryService.SystemHealth systemHealth() {
        return queryService.systemHealth();
    }

    @PlatformQuery("Get governance problems")
    @RestPath("/problems")
    public PagedResult<GovernanceQueryService.Problem> problems(
            @QueryParam("threshold_minutes") @DefaultValue("60") int thresholdMinutes,
            @QueryParam("cursor") String cursor,
            @QueryParam("limit") @DefaultValue("50") int limit) {
        return PagedResult.paginate(queryService.problems(thresholdMinutes), cursor, limit);
    }

    @PlatformQuery("Get merge queue status")
    @RestPath("/merge-queue")
    public GovernanceQueryService.MergeQueueStatus mergeQueue() {
        return queryService.mergeQueue();
    }

    @PlatformQuery("Get merge queue metrics")
    @RestPath("/merge-queue/metrics")
    public GovernanceQueryService.MergeQueueMetrics mergeQueueMetrics() {
        return queryService.mergeQueueMetrics();
    }

    @PlatformQuery("Get merge batch status")
    @RestPath("/merge-queue/batch/{batchId}")
    public GovernanceQueryService.BatchStatus batchStatus(@PathParam UUID batchId) {
        return queryService.batchStatus(batchId);
    }

    @PlatformQuery("Get triage items")
    @RestPath("/triage")
    public PagedResult<GovernanceQueryService.TriageItem> triageItems(
            @QueryParam("cursor") String cursor,
            @QueryParam("limit") @DefaultValue("50") int limit) {
        return PagedResult.paginate(queryService.triageItems(), cursor, limit);
    }

    @PlatformQuery("Get SLA comparison")
    @RestPath("/sla-comparison")
    public GovernanceQueryService.SlaComparison slaComparison() {
        return queryService.slaComparison();
    }

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
