package io.casehub.devtown.app;

import io.casehub.ledger.api.model.LedgerAttestation;
import io.casehub.ledger.api.model.LedgerEntry;
import io.casehub.ledger.api.spi.LedgerEntryRepository;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

@ApplicationScoped
class InMemoryLedgerEntryRepository implements LedgerEntryRepository {

    private final List<LedgerEntry> entries = new CopyOnWriteArrayList<>();
    private final List<LedgerAttestation> attestations = new CopyOnWriteArrayList<>();

    @Override
    public LedgerEntry save(LedgerEntry entry, String tenancyId) {
        entries.add(entry);
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
        return List.of();
    }

    @Override
    public LedgerAttestation saveAttestation(LedgerAttestation attestation, String tenancyId) {
        attestations.add(attestation);
        return attestation;
    }

    @Override
    public List<LedgerEntry> findByActorId(String actorId, Instant from, Instant to, String tenancyId) {
        return List.of();
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
        return List.of();
    }

    @Override
    public List<LedgerAttestation> findAttestationsByEntryIdGlobal(UUID entryId, String tenancyId) {
        return List.of();
    }

    @Override
    public List<LedgerAttestation> findAttestationsByAttestorIdAndCapabilityTag(String attestorId, String tag, String tenancyId) {
        return List.of();
    }
}
