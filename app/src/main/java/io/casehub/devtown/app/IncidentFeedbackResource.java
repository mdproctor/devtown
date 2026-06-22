package io.casehub.devtown.app;

import io.casehub.devtown.app.ledger.IncidentFeedbackService;
import io.casehub.devtown.domain.DevtownRoles;
import io.casehub.devtown.domain.IncidentFeedback;
import io.casehub.devtown.domain.IncidentFeedbackResult;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/api/incident-feedback")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(DevtownRoles.ADMIN)
@ApplicationScoped
public class IncidentFeedbackResource {

    @Inject
    IncidentFeedbackService service;

    @POST
    public IncidentFeedbackResult recordFeedback(IncidentFeedback feedback) {
        return service.recordFeedback(feedback);
    }
}
