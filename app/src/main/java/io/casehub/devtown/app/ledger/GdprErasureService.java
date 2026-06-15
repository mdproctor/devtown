package io.casehub.devtown.app.ledger;

import io.casehub.devtown.domain.HashUtils;
import io.casehub.devtown.domain.memory.DevtownMemoryDomain;
import io.casehub.devtown.review.compliance.ErasureReceipt;
import io.casehub.ledger.api.model.LedgerEntryType;
import io.casehub.ledger.runtime.privacy.ActorIdentityProvider;
import io.casehub.ledger.runtime.privacy.LedgerErasureService;
import io.casehub.ledger.runtime.repository.LedgerEntryRepository;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.platform.api.memory.CaseMemoryStore;
import io.casehub.platform.api.memory.MemoryCapabilityException;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.jboss.logging.Logger;

@ApplicationScoped
public class GdprErasureService {

    private static final Logger LOG = Logger.getLogger(GdprErasureService.class);
    private static final String SYSTEM_ACTOR_ID = "devtown:gdpr-erasure";
    private static final String ACTOR_ROLE = "GDPR_COMPLIANCE";

    @Inject LedgerErasureService ledgerErasureService;
    @Inject CaseMemoryStore memoryStore;
    @Inject ActorIdentityProvider actorIdentityProvider;
    @Inject LedgerEntryRepository ledgerRepo;

    public ErasureReceipt erase(final String rawActorId, final String tenancyId, final String reason) {
        String queryResult = actorIdentityProvider.tokeniseForQuery(rawActorId);
        String erasedActorToken = queryResult.equals(rawActorId)
                ? HashUtils.sha256Hex("erasure:" + rawActorId)
                : queryResult;

        int memoryRecordsErased = eraseMemory(rawActorId, tenancyId);

        return QuarkusTransaction.requiringNew().call(() -> {
            var erasureResult = ledgerErasureService.erase(rawActorId);

            var receipt = new ErasureReceiptLedgerEntry();
            receipt.erasedActorToken = erasedActorToken;
            receipt.reason = reason;
            receipt.ledgerEntriesAffected = erasureResult.affectedEntryCount();
            receipt.memoryRecordsErased = memoryRecordsErased;
            receipt.subjectId = UUID.nameUUIDFromBytes(("erasure:" + erasedActorToken).getBytes(StandardCharsets.UTF_8));
            receipt.entryType = LedgerEntryType.EVENT;
            receipt.actorId = SYSTEM_ACTOR_ID;
            receipt.actorType = ActorType.SYSTEM;
            receipt.actorRole = ACTOR_ROLE;
            receipt.occurredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);

            ledgerRepo.save(receipt, tenancyId);

            return new ErasureReceipt(
                    erasedActorToken,
                    receipt.occurredAt,
                    erasureResult.affectedEntryCount(),
                    memoryRecordsErased,
                    receipt.id,
                    reason);
        });
    }

    private int eraseMemory(final String rawActorId, final String tenancyId) {
        int total = 0;
        total += eraseEntitySafely(DevtownMemoryDomain.CONTRIBUTOR_PREFIX + rawActorId, tenancyId);
        total += eraseEntitySafely(DevtownMemoryDomain.REVIEWER_PREFIX + rawActorId, tenancyId);
        return total;
    }

    private int eraseEntitySafely(final String entityId, final String tenancyId) {
        try {
            return memoryStore.eraseEntity(entityId, tenancyId);
        } catch (MemoryCapabilityException e) {
            LOG.debugf("Memory store does not support eraseEntity: %s", e.getMessage());
            return 0;
        }
    }

}
