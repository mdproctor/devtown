package io.casehub.devtown.app;

import io.casehub.devtown.domain.DevtownRoles;
import io.casehub.devtown.review.PrPayload;
import io.casehub.devtown.review.PrReviewApplicationService;
import io.casehub.devtown.review.PrReviewOutcome;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/api/reviews")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({DevtownRoles.ADMIN, DevtownRoles.ENGINEER, DevtownRoles.SERVICE})
public class PrReviewResource {

    @Inject
    PrReviewApplicationService service;

    @POST
    public PrReviewOutcome review(PrPayload pr) {
        return service.startReview(pr);
    }
}
