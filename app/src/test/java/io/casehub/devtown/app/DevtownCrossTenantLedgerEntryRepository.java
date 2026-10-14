package io.casehub.devtown.app;

import io.casehub.ledger.api.model.LedgerAttestation;
import io.casehub.ledger.api.model.LedgerEntry;
import io.casehub.ledger.api.model.LedgerEntryType;
import io.casehub.ledger.api.model.SubjectSequenceStats;
import io.casehub.ledger.api.spi.CrossTenantLedgerEntryRepository;
import io.casehub.ledger.runtime.qualifier.CrossTenant;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@CrossTenant
@Alternative
@Priority(2)
@ApplicationScoped
class DevtownCrossTenantLedgerEntryRepository implements CrossTenantLedgerEntryRepository {

    @Inject
    InMemoryLedgerEntryRepository blocking;

    @Override
    public List<LedgerEntry> listAll() {
        return List.copyOf(blocking.allEntries());
    }

    @Override
    public List<LedgerEntry> findAllEvents() {
        return blocking.allEntries().stream()
                .filter(e -> e.entryType == LedgerEntryType.EVENT)
                .toList();
    }

    @Override
    public List<LedgerEntry> findEventsByActorId(String actorId) {
        return blocking.allEntries().stream()
                .filter(e -> e.entryType == LedgerEntryType.EVENT)
                .filter(e -> actorId.equals(e.actorId))
                .toList();
    }

    @Override
    public List<LedgerEntry> findByTimeRange(Instant from, Instant to) {
        return blocking.allEntries().stream()
                .filter(e -> e.occurredAt != null && !e.occurredAt.isBefore(from) && !e.occurredAt.isAfter(to))
                .sorted(Comparator.comparing(e -> e.occurredAt))
                .toList();
    }

    @Override
    public Map<UUID, List<LedgerAttestation>> findAttestationsForEntries(Set<UUID> entryIds) {
        if (entryIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return blocking.allAttestations().stream()
                       .filter(a -> entryIds.contains(a.ledgerEntryId))
                       .collect(Collectors.groupingBy(a -> a.ledgerEntryId));
    }

    @Override
    public Map<UUID, List<LedgerAttestation>> findAttestationsByActorId(String actorId) {
        Set<UUID> eventIds = blocking.allEntries().stream()
                .filter(e -> e.entryType == LedgerEntryType.EVENT)
                .filter(e -> actorId.equals(e.actorId))
                .map(e -> e.id)
                .collect(Collectors.toSet());
        if (eventIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return blocking.allAttestations().stream()
                .filter(a -> eventIds.contains(a.ledgerEntryId))
                .collect(Collectors.groupingBy(a -> a.ledgerEntryId));
    }

    @Override
    public List<SubjectSequenceStats> findSequenceStats() {
        return List.of();
    }

    @Override
    public long countByActorId(String actorId, String tenancyId) {
        return blocking.allEntries().stream()
                .filter(e -> actorId.equals(e.actorId) && tenancyId.equals(e.tenancyId))
                .count();
    }
}
