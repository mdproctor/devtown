package io.casehub.devtown.app.governance;

import io.casehub.devtown.app.mcp.TrackedEvent;
import io.casehub.devtown.domain.DevtownRoles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * REST endpoints for the governance workbench.
 *
 * Thin delegation layer to GovernanceQueryService. All endpoints require admin role.
 * Consumed by the TypeScript frontend datasets (Task 5/6).
 */
@Path("/api/governance")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(DevtownRoles.ADMIN)
public class GovernanceResource {

    @Inject
    GovernanceQueryService queryService;

    @GET
    @Path("/queue-status")
    public GovernanceQueryService.QueueStatus queueStatus() {
        return queryService.queueStatus();
    }

    @GET
    @Path("/recent-events")
    public List<TrackedEvent> recentEvents(
            @QueryParam("limit") @DefaultValue("50") int limit,
            @QueryParam("since") String since) {
        Instant sinceTime = since != null ? Instant.parse(since) : null;
        return queryService.recentEvents(limit, sinceTime);
    }

    @GET
    @Path("/system-health")
    public GovernanceQueryService.SystemHealth systemHealth() {
        return queryService.systemHealth();
    }

    @GET
    @Path("/problems")
    public List<GovernanceQueryService.Problem> problems(
            @QueryParam("threshold_minutes") @DefaultValue("60") int thresholdMinutes) {
        return queryService.problems(thresholdMinutes);
    }

    @GET
    @Path("/reviews")
    public List<GovernanceQueryService.ReviewListEntry> reviewsList() {
        return queryService.reviewsList();
    }

    @GET
    @Path("/reviews/{caseId}")
    public GovernanceQueryService.ReviewDetail reviewDetail(@PathParam("caseId") UUID caseId) {
        return queryService.reviewDetail(caseId);
    }

    @GET
    @Path("/reviewers")
    public List<GovernanceQueryService.ReviewerFleetEntry> reviewerFleet() {
        return queryService.reviewerFleet();
    }

    @GET
    @Path("/reviewers/{actorId}")
    public GovernanceQueryService.ReviewerHealth reviewerHealth(@PathParam("actorId") String actorId) {
        return queryService.reviewerHealth(actorId);
    }

    @GET
    @Path("/merge-queue")
    public GovernanceQueryService.MergeQueueStatus mergeQueue() {
        return queryService.mergeQueue();
    }

    @GET
    @Path("/merge-queue/metrics")
    public GovernanceQueryService.MergeQueueMetrics mergeQueueMetrics() {
        return queryService.mergeQueueMetrics();
    }

    @GET
    @Path("/merge-queue/batch/{batchId}")
    public GovernanceQueryService.BatchStatus batchStatus(@PathParam("batchId") UUID batchId) {
        return queryService.batchStatus(batchId);
    }

    @GET
    @Path("/triage")
    public List<GovernanceQueryService.TriageItem> triageItems() {
        return queryService.triageItems();
    }
}
