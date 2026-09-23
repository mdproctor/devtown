package io.casehub.devtown.app.api;

import io.casehub.devtown.app.governance.GovernanceQueryService;
import io.casehub.devtown.app.governance.PagedResult;
import io.casehub.devtown.app.ledger.IncidentFeedbackService;
import io.casehub.devtown.domain.IncidentFeedback;
import io.casehub.devtown.domain.IncidentFeedbackResult;
import io.casehub.devtown.review.PrPayload;
import io.casehub.devtown.review.PrReviewApplicationService;
import io.casehub.devtown.review.PrReviewOutcome;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PathParam;
import io.casehub.platform.api.mcp.PlatformMutation;
import io.casehub.platform.api.mcp.PlatformQuery;
import io.casehub.platform.api.mcp.RestPath;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.QueryParam;

import java.util.UUID;

@McpDomain(value = "devtown/reviews", app = "devtown", basePath = "/api/devtown/reviews")
@ApplicationScoped
public class DevtownReviewApi {

    @Inject GovernanceQueryService queryService;
    @Inject PrReviewApplicationService prReviewService;
    @Inject IncidentFeedbackService feedbackService;

    @PlatformQuery("List code reviews")
    @RestPath("/")
    public PagedResult<GovernanceQueryService.ReviewListEntry> reviewsList(
            @QueryParam("cursor") String cursor,
            @QueryParam("limit") @DefaultValue("50") int limit) {
        return PagedResult.paginate(queryService.reviewsList(), cursor, limit);
    }

    @PlatformQuery("Get review details")
    @RestPath("/{caseId}")
    public GovernanceQueryService.ReviewDetail reviewDetail(@PathParam UUID caseId) {
        return queryService.reviewDetail(caseId);
    }

    @PlatformQuery("List reviewer fleet")
    @RestPath("/reviewers")
    public PagedResult<GovernanceQueryService.ReviewerFleetEntry> reviewerFleet(
            @QueryParam("cursor") String cursor,
            @QueryParam("limit") @DefaultValue("50") int limit) {
        return PagedResult.paginate(queryService.reviewerFleet(), cursor, limit);
    }

    @PlatformQuery("Get reviewer health")
    @RestPath("/reviewers/{actorId}")
    public GovernanceQueryService.ReviewerHealth reviewerHealth(@PathParam String actorId) {
        return queryService.reviewerHealth(actorId);
    }

    @PlatformQuery("List contributor fleet")
    @RestPath("/contributors")
    public PagedResult<GovernanceQueryService.ContributorFleetEntry> contributorFleet(
            @QueryParam("cursor") String cursor,
            @QueryParam("limit") @DefaultValue("50") int limit) {
        return PagedResult.paginate(queryService.contributorFleet(), cursor, limit);
    }

    @PlatformQuery("Get contributor details")
    @RestPath("/contributors/{actorId}")
    public GovernanceQueryService.ContributorDetail contributorDetail(@PathParam String actorId) {
        return queryService.contributorDetail(actorId);
    }

    @PlatformMutation("Submit a PR for review")
    @RestPath("/submit")
    public PrReviewOutcome submitReview(PrPayload pr) {
        return prReviewService.startReview(pr);
    }

    @PlatformMutation("Record incident feedback")
    @RestPath("/feedback")
    public IncidentFeedbackResult recordFeedback(IncidentFeedback feedback) {
        return feedbackService.recordFeedback(feedback);
    }
}
