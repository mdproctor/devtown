package io.casehub.devtown.app.ledger;

import io.casehub.devtown.domain.HashUtils;
import io.casehub.devtown.domain.memory.DevtownMemoryDomain;
import io.casehub.devtown.review.compliance.ErasureReceipt;
import io.casehub.ledger.api.model.ErasureReason;
import io.casehub.ledger.api.spi.ActorIdentityProvider;
import io.casehub.ledger.runtime.privacy.LedgerErasureService;
import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.neocortex.memory.MemoryCapabilityException;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.jboss.logging.Logger;

@ApplicationScoped
public class GdprErasureService {

    private static final Logger LOG = Logger.getLogger(GdprErasureService.class);

    @Inject LedgerErasureService ledgerErasureService;
    @Inject CaseMemoryStore memoryStore;
    @Inject ActorIdentityProvider actorIdentityProvider;

    public ErasureReceipt erase(final String rawActorId, final String tenancyId, final String reason) {
        final String erasedActorToken = actorIdentityProvider.tokeniseForQuery(rawActorId)
                .orElseGet(() -> HashUtils.sha256Hex("erasure:" + rawActorId));

        final int memoryRecordsErased = eraseMemory(rawActorId, tenancyId);

        return QuarkusTransaction.requiringNew().call(() -> {
            var erasureResult = ledgerErasureService.erase(rawActorId, ErasureReason.GDPR_ART_17_REQUEST);

            return new ErasureReceipt(
                    erasedActorToken,
                    Instant.now().truncatedTo(ChronoUnit.MILLIS),
                    erasureResult.affectedEntryCount(),
                    memoryRecordsErased,
                    erasureResult.receiptEntryId().orElse(null),
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
