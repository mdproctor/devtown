package io.casehub.devtown.app;

import io.casehub.devtown.app.ledger.CodeReviewComplianceService;
import io.casehub.devtown.domain.DevtownRoles;
import io.casehub.platform.api.identity.CurrentPrincipal;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.UUID;

@Path("/api/compliance/code-review")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({DevtownRoles.ADMIN, DevtownRoles.ENGINEER, DevtownRoles.AUDITOR})
public class CodeReviewComplianceResource {

    @Inject
    CodeReviewComplianceService service;
    @Inject
    CurrentPrincipal            principal;

    @GET
    @Path("/{caseId}")
    public Response getEvidence(@PathParam("caseId") UUID caseId) {
        return service.findEvidence(caseId, principal.tenancyId())
                      .map(evidence -> Response.ok(evidence).build())
                      .orElse(Response.status(Response.Status.NOT_FOUND).build());
    }
}
