package io.casehub.devtown.app.api;

import io.casehub.devtown.app.ledger.CodeReviewComplianceEvidence;
import io.casehub.devtown.app.ledger.CodeReviewComplianceService;
import io.casehub.devtown.app.ledger.GdprErasureService;
import io.casehub.devtown.domain.memory.DevtownMemoryDomain;
import io.casehub.devtown.review.compliance.ErasureReceipt;
import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.neocortex.memory.MemoryCapabilityException;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.PathParam;
import io.casehub.platform.api.mcp.PlatformMutation;
import io.casehub.platform.api.mcp.PlatformQuery;
import io.casehub.platform.api.mcp.RestPath;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.WebApplicationException;

import java.util.Optional;
import java.util.UUID;

@McpDomain(value = "devtown/compliance", app = "devtown", basePath = "/api/devtown/compliance", summary = "Development compliance — policy enforcement and audit")
@ApplicationScoped
public class DevtownComplianceApi {

    @Inject CodeReviewComplianceService complianceService;
    @Inject GdprErasureService erasureService;
    @Inject CaseMemoryStore memoryStore;
    @Inject CurrentPrincipal principal;

    @PlatformQuery("Get code review compliance evidence for a case")
    @RestPath("/code-review/{caseId}")
    public CodeReviewComplianceEvidence getEvidence(@PathParam UUID caseId) {
        Optional<CodeReviewComplianceEvidence> evidence =
                complianceService.findEvidence(caseId, principal.tenancyId());
        return evidence.orElseThrow(() -> new WebApplicationException(404));
    }

    @PlatformMutation("Request GDPR erasure for an actor")
    @RestPath("/erasure/{actorId}")
    public ErasureReceipt erase(@PathParam String actorId, String reason) {
        return erasureService.erase(actorId, principal.tenancyId(), reason);
    }

    @PlatformMutation("Erase contributor memory (GDPR)")
    @RestPath("/memory/erase-contributor")
    public void eraseContributor(String login) {
        if (login == null || login.isBlank()) {
            throw new BadRequestException("login is required");
        }
        String entityId = DevtownMemoryDomain.CONTRIBUTOR_PREFIX + login;
        try {
            memoryStore.eraseEntity(entityId, principal.tenancyId());
        } catch (MemoryCapabilityException e) {
            throw new WebApplicationException("eraseEntity not supported by the active CaseMemoryStore adapter", 501);
        }
    }
}
