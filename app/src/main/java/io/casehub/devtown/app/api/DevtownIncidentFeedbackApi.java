package io.casehub.devtown.app.api;

import io.casehub.devtown.app.ledger.IncidentFeedbackService;
import io.casehub.devtown.domain.DevtownRoles;
import io.casehub.devtown.domain.IncidentFeedback;
import io.casehub.devtown.domain.IncidentFeedbackResult;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PlatformMutation;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@McpDomain(value = "devtown/incident-feedback", app = "devtown")
@ApplicationScoped
@RolesAllowed(DevtownRoles.ADMIN)
public class DevtownIncidentFeedbackApi {

    @Inject
    IncidentFeedbackService service;

    @PlatformMutation("Record incident feedback for trust scoring")
    public IncidentFeedbackResult recordFeedback(IncidentFeedback feedback) {
        return service.recordFeedback(feedback);
    }
}
