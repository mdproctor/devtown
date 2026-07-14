package io.casehub.devtown.app;

import io.casehub.devtown.app.ledger.GdprErasureService;
import io.casehub.devtown.domain.DevtownRoles;
import io.casehub.devtown.review.compliance.ErasureReceipt;
import io.casehub.platform.api.identity.CurrentPrincipal;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/api/actors/{actorId}/erasure")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({DevtownRoles.ADMIN, DevtownRoles.DATA_CONTROLLER})
public class GdprErasureResource {

    @Inject
    GdprErasureService service;
    @Inject
    CurrentPrincipal   principal;

    @POST
    public ErasureReceipt erase(
            @PathParam("actorId") final String actorId,
            final ErasureRequest request) {
        return service.erase(actorId, principal.tenancyId(),
                             request != null ? request.reason() : null);
    }

    public record ErasureRequest(String reason) {}
}
