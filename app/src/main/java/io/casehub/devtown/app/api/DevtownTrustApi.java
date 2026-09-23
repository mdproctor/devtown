package io.casehub.devtown.app.api;

import io.casehub.devtown.app.governance.TrustQueryService;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PathParam;
import io.casehub.platform.api.mcp.PlatformQuery;
import io.casehub.platform.api.mcp.RestPath;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.QueryParam;

import java.util.List;
import java.util.UUID;

@McpDomain(value = "devtown/trust", app = "devtown", basePath = "/api/devtown/trust")
@ApplicationScoped
public class DevtownTrustApi {

    @Inject TrustQueryService trustQueryService;

    @PlatformQuery("Get trust score for an actor")
    @RestPath("/{actorId}")
    public TrustQueryService.TrustScoreResponse trustScore(@PathParam String actorId) {
        return trustQueryService.trustScore(actorId);
    }

    @PlatformQuery("Get trust trend for an actor")
    @RestPath("/{actorId}/trend")
    public List<TrustQueryService.TrustTrendPoint> trustTrend(
            @PathParam String actorId,
            @QueryParam("capability") String capability,
            @QueryParam("limit") @DefaultValue("30") int limit) {
        return trustQueryService.trustTrend(actorId, capability, limit);
    }

    @PlatformQuery("Get routing history for an actor")
    @RestPath("/{actorId}/routing-history")
    public List<TrustQueryService.RoutingDecisionSummary> routingHistory(
            @PathParam String actorId,
            @QueryParam("capability") String capability,
            @QueryParam("limit") @DefaultValue("50") int limit) {
        return trustQueryService.routingHistory(actorId, capability, limit);
    }

    @PlatformQuery("Get routing decision detail")
    @RestPath("/{actorId}/routing-history/{entryId}")
    public Object routingDetail(@PathParam String actorId, @PathParam UUID entryId) {
        return trustQueryService.routingDetail(actorId, entryId);
    }
}
