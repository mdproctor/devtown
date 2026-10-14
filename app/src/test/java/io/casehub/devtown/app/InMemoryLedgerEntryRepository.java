package io.casehub.devtown.app;

import io.casehub.ledger.api.model.LedgerAttestation;
import io.casehub.ledger.api.model.LedgerEntry;
import io.casehub.ledger.api.spi.LedgerEntryRepository;
import io.casehub.ledger.jpa.LedgerPersistenceUnit;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

@Alternative
@Priority(2)
@ApplicationScoped
class InMemoryLedgerEntryRepository implements LedgerEntryRepository {

    private final List<LedgerEntry>       entries      = new CopyOnWriteArrayList<>();
    private final List<LedgerAttestation> attestations = new CopyOnWriteArrayList<>();

    @Inject
    @LedgerPersistenceUnit
    EntityManager em;

    @Override
    public LedgerEntry save(LedgerEntry entry, String tenancyId) {
        if (entry.id == null) {
            entry.id = UUID.randomUUID();
        }
        if (entry.tenancyId == null) {
            entry.tenancyId = tenancyId;
        }
        entries.add(entry);
        tryPersistToJpa(entry);
        return entry;
    }

    @Override
    public List<LedgerEntry> findBySubjectId(UUID subjectId, String tenancyId) {
        return entries.stream().filter(e -> subjectId.equals(e.subjectId)).toList();
    }

    @Override
    public List<LedgerEntry> findBySubjectIdAndTimeRange(UUID subjectId, Instant from, Instant to, String tenancyId) {
        return List.of();
    }

    @Override
    public Optional<LedgerEntry> findLatestBySubjectId(UUID subjectId, String tenancyId) {
        return entries.stream()
                      .filter(e -> subjectId.equals(e.subjectId))
                      .max(java.util.Comparator.comparing(e -> e.occurredAt));
    }

    @Override
    public Optional<LedgerEntry> findEntryById(UUID id, String tenancyId) {
        return entries.stream().filter(e -> id.equals(e.id)).findFirst();
    }

    @Override
    public List<LedgerAttestation> findAttestationsByEntryId(UUID entryId, String tenancyId) {
        return attestations.stream().filter(a -> entryId.equals(a.ledgerEntryId)).toList();
    }

    @Override
    public LedgerAttestation saveAttestation(LedgerAttestation attestation, String tenancyId) {
        if (attestation.id == null) {
            attestation.id = UUID.randomUUID();
        }
        attestations.add(attestation);
        tryPersistToJpa(attestation);
        return attestation;
    }

    @Override
    public List<LedgerEntry> findByActorId(String actorId, Instant from, Instant to, String tenancyId) {
        return entries.stream()
                      .filter(e -> actorId.equals(e.actorId))
                      .filter(e -> e.occurredAt != null && !e.occurredAt.isBefore(from) && !e.occurredAt.isAfter(to))
                      .toList();
    }

    @Override
    public List<LedgerEntry> findByActorRole(String role, Instant from, Instant to, String tenancyId) {
        return List.of();
    }

    @Override
    public List<LedgerEntry> findCausedBy(UUID causeId, String tenancyId) {
        return List.of();
    }

    @Override
    public List<LedgerAttestation> findAttestationsByEntryIdAndCapabilityTag(UUID entryId, String tag, String tenancyId) {
        return attestations.stream()
                           .filter(a -> entryId.equals(a.ledgerEntryId) && tag.equals(a.capabilityTag))
                           .toList();
    }

    @Override
    public List<LedgerAttestation> findAttestationsByEntryIdGlobal(UUID entryId, String tenancyId) {
        return attestations.stream().filter(a -> entryId.equals(a.ledgerEntryId)).toList();
    }

    @Override
    public List<LedgerAttestation> findAttestationsByAttestorIdAndCapabilityTag(String attestorId, String tag, String tenancyId) {
        return attestations.stream()
                           .filter(a -> attestorId.equals(a.attestorId) && tag.equals(a.capabilityTag))
                           .toList();
    }

    @Override
    public List<LedgerEntry> findByTimeRange(Instant from, Instant to, String tenancyId) {
        return entries.stream()
                      .filter(e -> e.occurredAt != null && !e.occurredAt.isBefore(from) && !e.occurredAt.isAfter(to))
                      .toList();
    }

    List<LedgerEntry> allEntries() {
        return entries;
    }

    List<LedgerAttestation> allAttestations() {
        return attestations;
    }

    private void tryPersistToJpa(Object entity) {
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                em.merge(entity);
                em.flush();
            });
        } catch (Exception e) {
            System.err.println("[InMemoryLedgerEntryRepository] JPA dual-write failed for " + entity.getClass().getSimpleName() + ": " + e.getMessage());
            Throwable cause = e.getCause();
            while (cause != null) {
                System.err.println("  Caused by: " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
                cause = cause.getCause();
            }
        }
    }
}
