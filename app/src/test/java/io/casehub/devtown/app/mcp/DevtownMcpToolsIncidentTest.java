package io.casehub.devtown.app.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.devtown.app.ledger.IncidentFeedbackService;
import io.casehub.devtown.app.ledger.LedgerEnabledTestProfile;
import io.casehub.devtown.app.ledger.MergeDecisionLedgerEntry;
import io.casehub.devtown.domain.IncidentFeedbackResult;
import io.casehub.devtown.domain.ReviewDomain;
import io.casehub.ledger.api.model.LedgerEntryType;
import io.casehub.ledger.model.WorkerDecisionEntry;
import io.casehub.ledger.api.model.LedgerEntry;
import io.casehub.ledger.api.spi.LedgerEntryRepository;
import io.casehub.platform.api.identity.ActorType;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(LedgerEnabledTestProfile.class)
@TestSecurity(user = "devtown-admin", roles = {"devtown-admin"})
class DevtownMcpToolsIncidentTest {

    private static final String TENANT = "test-tenant";

    @Inject DevtownMcpTools mcpTools;
    @Inject LedgerEntryRepository ledgerRepo;

    @Test
    void reportIncident_delegatesToService() {
        UUID caseId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        seedMergeDecision(caseId, "casehubio/devtown", 500, "APPROVED", now);
        seedWorkerDecision(caseId, "claude:sec@v1", ReviewDomain.SECURITY_REVIEW, now.plusMillis(100));

        IncidentFeedbackResult result = mcpTools.reportIncident(
                "casehubio/devtown", 500, "INC-MCP-1", "HIGH",
                "MCP test incident", ReviewDomain.SECURITY_REVIEW, null);

        assertThat(result.attestationsWritten()).isEqualTo(1);
        assertThat(result.flaggedAgents()).hasSize(1);
        assertThat(result.flaggedAgents().get(0).agentId()).isEqualTo("claude:sec@v1");
    }

    @Test
    void reportIncident_invalidSeverity_throws() {
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> mcpTools.reportIncident(
                        "casehubio/devtown", 1, "INC-MCP-2", "INVALID",
                        "bad severity", ReviewDomain.SECURITY_REVIEW, null));
    }

    void seedMergeDecision(UUID caseId, String repo, int prNumber,
                           String decision, Instant occurredAt) {
        QuarkusTransaction.requiringNew().run(() -> {
            MergeDecisionLedgerEntry mde = new MergeDecisionLedgerEntry();
            mde.subjectId = caseId;
            mde.caseId = caseId;
            mde.tenancyId = TENANT;
            mde.entryType = LedgerEntryType.EVENT;
            mde.prNumber = prNumber;
            mde.repository = repo;
            mde.decision = decision;
            mde.actorId = "system";
            mde.actorType = ActorType.SYSTEM;
            mde.actorRole = "ORCHESTRATOR";
            mde.occurredAt = occurredAt;
            ledgerRepo.save(mde, TENANT);
        });
    }

    UUID seedWorkerDecision(UUID caseId, String workerId,
                            String capabilityTag, Instant occurredAt) {
        return QuarkusTransaction.requiringNew().call(() -> {
            WorkerDecisionEntry wde = new WorkerDecisionEntry();
            wde.subjectId = caseId;
            wde.caseId = caseId;
            wde.tenancyId = TENANT;
            wde.entryType = LedgerEntryType.EVENT;
            wde.workerId = workerId;
            wde.actorId = workerId;
            wde.actorType = ActorType.SYSTEM;
            wde.actorRole = "WORKER";
            wde.capabilityTag = capabilityTag;
            wde.occurredAt = occurredAt;
            LedgerEntry saved = ledgerRepo.save(wde, TENANT);
            return saved.id;
        });
    }
}
