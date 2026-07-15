package io.casehub.devtown.app.trust;

import io.casehub.ledger.api.model.AttestationVerdict;
import io.casehub.ledger.api.model.LedgerAttestation;
import io.casehub.ledger.api.model.LedgerEntry;
import io.casehub.ledger.api.model.LedgerEntryType;
import io.casehub.ledger.api.spi.LedgerEntryRepository;
import io.casehub.ledger.api.spi.TrustScoreSource;
import io.casehub.ledger.model.WorkerDecisionEntry;
import io.casehub.ledger.routing.TrustGatedAttestationPolicy;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.spi.CommitmentAttestationPolicy;
import io.casehub.qhorus.api.spi.CommitmentContext;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@TestProfile(TrustScoringTestProfile.class)
@TestSecurity(user = "devtown-admin", roles = {"devtown-admin"})
class TrustGatedAttestationPolicyActivationTest {

    private static final String TENANT = "test-tenant";
    private static final String HIGH_TRUST_AGENT = "claude:trusted-reviewer@v1";
    private static final String LOW_TRUST_AGENT = "claude:untrusted-reviewer@v1";
    private static final String SECURITY_REVIEW = "security-review";

    @Inject CommitmentAttestationPolicy attestationPolicy;
    @Inject LedgerEntryRepository ledgerRepo;
    @Inject TrustScoreSource trustScoreSource;
    @Inject io.casehub.ledger.runtime.service.TrustScoreJob trustScoreJob;

    @Test
    void policyIsEvidentialWrappingTrustGated() {
        assertThat(attestationPolicy).isInstanceOf(EvidentialAttestationPolicy.class);
        EvidentialAttestationPolicy evidential = (EvidentialAttestationPolicy) attestationPolicy;
        assertThat(evidential.delegate()).isInstanceOf(TrustGatedAttestationPolicy.class);
    }

    @Test
    void highTrustAgentDoneProducesHigherConfidence() {
        // Seed 15 SOUND attestations for high-trust agent (crosses minimumObservations=10)
        seedAttestations(HIGH_TRUST_AGENT, SECURITY_REVIEW, 15, AttestationVerdict.SOUND, 0.7);
        // Seed 15 mixed attestations for low-trust agent (below threshold)
        seedAttestations(LOW_TRUST_AGENT, SECURITY_REVIEW, 10, AttestationVerdict.SOUND, 0.7);
        seedAttestations(LOW_TRUST_AGENT, SECURITY_REVIEW, 5, AttestationVerdict.FLAGGED, 0.6);

        // Recompute trust scores
        QuarkusTransaction.requiringNew().run(() -> trustScoreJob.runComputation());

        // Both agents send DONE for security-review
        var context = new CommitmentContext(
                UUID.randomUUID().toString(), UUID.randomUUID(), "test-channel",
                UUID.randomUUID(), SECURITY_REVIEW, null, null, null);

        Optional<CommitmentAttestationPolicy.AttestationOutcome> highTrustResult =
                attestationPolicy.attestationFor(MessageType.DONE, HIGH_TRUST_AGENT, context);
        Optional<CommitmentAttestationPolicy.AttestationOutcome> lowTrustResult =
                attestationPolicy.attestationFor(MessageType.DONE, LOW_TRUST_AGENT, context);

        assertThat(highTrustResult).isPresent();
        assertThat(lowTrustResult).isPresent();
        assertThat(highTrustResult.get().verdict()).isEqualTo(AttestationVerdict.SOUND);
        assertThat(lowTrustResult.get().verdict()).isEqualTo(AttestationVerdict.SOUND);
        // High-trust agent's DONE should carry MORE confidence than low-trust agent's DONE
        assertThat(highTrustResult.get().confidence())
                .isGreaterThan(lowTrustResult.get().confidence());
        // Both should have agent attestor identity
        assertThat(highTrustResult.get().attestorId()).isEqualTo(HIGH_TRUST_AGENT);
        assertThat(highTrustResult.get().attestorType()).isEqualTo(ActorType.AGENT);
    }

    @Test
    void failureIsFlaggedRegardlessOfTrust() {
        var context = new CommitmentContext(
                UUID.randomUUID().toString(), UUID.randomUUID(), "test-channel",
                UUID.randomUUID(), SECURITY_REVIEW, null, null, null);

        Optional<CommitmentAttestationPolicy.AttestationOutcome> result =
                attestationPolicy.attestationFor(MessageType.FAILURE, HIGH_TRUST_AGENT, context);

        assertThat(result).isPresent();
        assertThat(result.get().verdict()).isEqualTo(AttestationVerdict.FLAGGED);
        assertThat(result.get().confidence()).isEqualTo(0.6);
        assertThat(result.get().attestorId()).isEqualTo("system");
        assertThat(result.get().attestorType()).isEqualTo(ActorType.SYSTEM);
    }

    @Test
    void nullContextFallsBackToBaseConfidence() {
        Optional<CommitmentAttestationPolicy.AttestationOutcome> result =
                attestationPolicy.attestationFor(MessageType.DONE, HIGH_TRUST_AGENT, null);

        assertThat(result).isPresent();
        assertThat(result.get().verdict()).isEqualTo(AttestationVerdict.SOUND);
        assertThat(result.get().confidence()).isEqualTo(0.7);
    }

    @Test
    void nonDischargeTypeReturnsEmpty() {
        var context = new CommitmentContext(
                UUID.randomUUID().toString(), UUID.randomUUID(), "test-channel",
                UUID.randomUUID(), SECURITY_REVIEW, null, null, null);

        Optional<CommitmentAttestationPolicy.AttestationOutcome> result =
                attestationPolicy.attestationFor(MessageType.QUERY, HIGH_TRUST_AGENT, context);

        assertThat(result).isEmpty();
    }

    private void seedAttestations(String agentId, String capabilityTag, int count,
                                  AttestationVerdict verdict, double confidence) {
        // Phase 1: save all LedgerEntries in one transaction so IDs are committed
        var savedIds = new java.util.ArrayList<java.util.AbstractMap.SimpleEntry<UUID, UUID>>();
        QuarkusTransaction.requiringNew().run(() -> {
            for (int i = 0; i < count; i++) {
                UUID    caseId     = UUID.randomUUID();
                Instant occurredAt = Instant.now();

                WorkerDecisionEntry entry = new WorkerDecisionEntry();
                entry.subjectId     = caseId;
                entry.caseId        = caseId;
                entry.tenancyId     = TENANT;
                entry.workerId      = agentId;
                entry.actorId       = agentId;
                entry.actorType     = ActorType.AGENT;
                entry.actorRole     = "WORKER";
                entry.capabilityTag = capabilityTag;
                entry.entryType     = LedgerEntryType.EVENT;
                entry.occurredAt    = occurredAt;
                LedgerEntry saved = ledgerRepo.save(entry, TENANT);
                savedIds.add(new java.util.AbstractMap.SimpleEntry<>(saved.id, caseId));
            }
        });

        // Phase 2: save attestations — saveAttestation uses REQUIRES_NEW so needs
        // the entries already committed (not just flushed in a suspended tx)
        QuarkusTransaction.requiringNew().run(() -> {
            for (var pair : savedIds) {
                UUID entryId = pair.getKey();
                UUID caseId  = pair.getValue();

                LedgerAttestation att = new io.casehub.ledger.runtime.model.LedgerAttestation();
                att.ledgerEntryId = entryId;
                att.subjectId     = caseId;
                att.attestorId    = verdict == AttestationVerdict.SOUND ? agentId : "system";
                att.attestorType  = verdict == AttestationVerdict.SOUND ? ActorType.AGENT : ActorType.SYSTEM;
                att.attestorRole  = verdict == AttestationVerdict.SOUND ? "AGENT" : "SYSTEM";
                att.verdict       = verdict;
                att.confidence    = confidence;
                att.capabilityTag = capabilityTag;
                att.occurredAt    = Instant.now();
                ledgerRepo.saveAttestation(att, TENANT);
            }
        });
    }
}
