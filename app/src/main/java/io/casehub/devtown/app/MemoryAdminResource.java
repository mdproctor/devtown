package io.casehub.devtown.app;

import io.casehub.devtown.domain.DevtownRoles;
import io.casehub.devtown.domain.memory.DevtownMemoryDomain;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.neocortex.memory.MemoryCapabilityException;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

@Path("/api/admin/memory")
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed(DevtownRoles.ADMIN)
public class MemoryAdminResource {

    private static final Logger LOG = Logger.getLogger(MemoryAdminResource.class);

    private final CaseMemoryStore store;
    private final CurrentPrincipal principal;

    @Inject
    public MemoryAdminResource(CaseMemoryStore store, CurrentPrincipal principal) {
        this.store = store;
        this.principal = principal;
    }

    @POST
    @Path("/erase/contributor")
    public Response eraseContributor(final EraseContributorRequest request) {
        if (request.login() == null || request.login().isBlank()) {
            throw new BadRequestException("login is required");
        }
        final String entityId = DevtownMemoryDomain.CONTRIBUTOR_PREFIX + request.login();
        LOG.infof("GDPR erasure requested — entityId=%s, requestedBy=%s, tenantId=%s",
                entityId, principal.actorId(), principal.tenancyId());
        try {
            store.eraseEntity(entityId, principal.tenancyId());
            LOG.infof("GDPR erasure completed — entityId=%s, requestedBy=%s, tenantId=%s",
                    entityId, principal.actorId(), principal.tenancyId());
            return Response.noContent().build();
        } catch (MemoryCapabilityException e) {
            LOG.warnf("GDPR erasure not supported by active adapter — entityId=%s, requestedBy=%s",
                    entityId, principal.actorId());
            return Response.status(501).entity("eraseEntity not supported by the active CaseMemoryStore adapter").build();
        }
    }

    public record EraseContributorRequest(String login) {}
}
