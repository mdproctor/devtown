package io.casehub.devtown.app.trust;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.casehub.api.spi.routing.TrustPhase;
import io.casehub.api.spi.routing.TrustRoutingPolicy;
import io.casehub.api.spi.routing.TrustRoutingPolicyProvider;
import io.casehub.ledger.api.model.AttestationVerdict;
import io.casehub.ledger.api.spi.TrustScoreSource;
import io.casehub.ledger.routing.TrustGatedAttestationPolicy;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.spi.CommitmentAttestationPolicy.AttestationOutcome;
import io.casehub.qhorus.api.spi.CommitmentContext;
import io.casehub.qhorus.runtime.audit.BenchmarkViolation;
import io.casehub.qhorus.runtime.audit.EvidentialChecker;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
class EvidentialAttestationPolicyTest {

    private static final String AGENT_ID = "claude:reviewer@v1";
    private static final String SECURITY_REVIEW = "security-review";
    private static final String STYLE_REVIEW = "style-review";

    private static final AttestationOutcome SOUND_BASE =
            new AttestationOutcome(AttestationVerdict.SOUND, 0.7, AGENT_ID, ActorType.AGENT);
    private static final AttestationOutcome SOUND_SCALED =
            new AttestationOutcome(AttestationVerdict.SOUND, 0.35, AGENT_ID, ActorType.AGENT);

    private final TrustGatedAttestationPolicy delegate = mock(TrustGatedAttestationPolicy.class);
    private final EvidentialChecker checker = mock(EvidentialChecker.class);
    private final TrustScoreSource scoreSource = mock(TrustScoreSource.class);
    private final TrustRoutingPolicyProvider policyProvider = mock(TrustRoutingPolicyProvider.class);

    private EvidentialAttestationPolicy policy;

    private static final TrustRoutingPolicy SECURITY_POLICY = new TrustRoutingPolicy(
            0.70, 10, 0.05, 0.6, Map.of(), true, null,
            Set.of(TrustPhase.BELOW_THRESHOLD, TrustPhase.QUALITY_FAILED, TrustPhase.BOOTSTRAP));

    private static final TrustRoutingPolicy STYLE_POLICY = new TrustRoutingPolicy(
            0.50, 5, 0.0, 0.6, Map.of(), false, null, Set.of());

    @BeforeEach
    void setUp() {
        policy = new EvidentialAttestationPolicy(delegate, checker, scoreSource, policyProvider);
    }

    // ── DONE + QUALIFIED agent → no evidential checks ──

    @Test
    void done_qualifiedAgent_skipsEvidentialCheck() {
        CommitmentContext ctx = context(SECURITY_REVIEW);
        when(delegate.attestationFor(MessageType.DONE, AGENT_ID, ctx))
                .thenReturn(Optional.of(SOUND_BASE));
        when(policyProvider.forCapability(SECURITY_REVIEW)).thenReturn(SECURITY_POLICY);
        when(scoreSource.capabilityScore(AGENT_ID, SECURITY_REVIEW))
                .thenReturn(OptionalDouble.of(0.85));
        when(scoreSource.decisionCount(AGENT_ID, SECURITY_REVIEW)).thenReturn(20);
        when(scoreSource.qualityScores(AGENT_ID, SECURITY_REVIEW)).thenReturn(Map.of());

        Optional<AttestationOutcome> result = policy.attestationFor(MessageType.DONE, AGENT_ID, ctx);

        assertThat(result).isPresent();
        assertThat(result.get().verdict()).isEqualTo(AttestationVerdict.SOUND);
        verify(checker, never()).check(any(), any(), any());
    }

    // ── DONE + BELOW_THRESHOLD + no violations → delegate passes through ──

    @Test
    void done_belowThreshold_noViolations_returnsSoundFromDelegate() {
        CommitmentContext ctx = context(SECURITY_REVIEW);
        when(delegate.attestationFor(MessageType.DONE, AGENT_ID, ctx))
                .thenReturn(Optional.of(SOUND_SCALED));
        when(policyProvider.forCapability(SECURITY_REVIEW)).thenReturn(SECURITY_POLICY);
        when(scoreSource.capabilityScore(AGENT_ID, SECURITY_REVIEW))
                .thenReturn(OptionalDouble.of(0.40));
        when(scoreSource.decisionCount(AGENT_ID, SECURITY_REVIEW)).thenReturn(15);
        when(scoreSource.qualityScores(AGENT_ID, SECURITY_REVIEW)).thenReturn(Map.of());
        when(checker.check(any(), any(), any())).thenReturn(List.of());

        Optional<AttestationOutcome> result = policy.attestationFor(MessageType.DONE, AGENT_ID, ctx);

        assertThat(result).isPresent();
        assertThat(result.get().verdict()).isEqualTo(AttestationVerdict.SOUND);
        assertThat(result.get().confidence()).isEqualTo(0.35);
    }

    // ── DONE + BELOW_THRESHOLD + violations → FLAGGED at 0.8 ──

    @Test
    void done_belowThreshold_violations_returnsFlaggedAtHighConfidence() {
        CommitmentContext ctx = context(SECURITY_REVIEW);
        when(delegate.attestationFor(MessageType.DONE, AGENT_ID, ctx))
                .thenReturn(Optional.of(SOUND_SCALED));
        when(policyProvider.forCapability(SECURITY_REVIEW)).thenReturn(SECURITY_POLICY);
        when(scoreSource.capabilityScore(AGENT_ID, SECURITY_REVIEW))
                .thenReturn(OptionalDouble.of(0.40));
        when(scoreSource.decisionCount(AGENT_ID, SECURITY_REVIEW)).thenReturn(15);
        when(scoreSource.qualityScores(AGENT_ID, SECURITY_REVIEW)).thenReturn(Map.of());
        when(checker.check(any(), any(), any())).thenReturn(List.of())
                .thenReturn(List.of(new BenchmarkViolation("V2", "I_df",
                        "DONE claimed on channel with 0 messages", "evidence")));

        Optional<AttestationOutcome> result = policy.attestationFor(MessageType.DONE, AGENT_ID, ctx);

        assertThat(result).isPresent();
        assertThat(result.get().verdict()).isEqualTo(AttestationVerdict.FLAGGED);
        assertThat(result.get().confidence()).isEqualTo(0.8);
        assertThat(result.get().attestorId()).isEqualTo("system");
        assertThat(result.get().attestorType()).isEqualTo(ActorType.SYSTEM);
    }

    // ── BOOTSTRAP + configured → runs checks ──

    @Test
    void done_bootstrapAgent_checksConfigured_runsChecks() {
        CommitmentContext ctx = context(SECURITY_REVIEW);
        when(delegate.attestationFor(MessageType.DONE, AGENT_ID, ctx))
                .thenReturn(Optional.of(SOUND_BASE));
        when(policyProvider.forCapability(SECURITY_REVIEW)).thenReturn(SECURITY_POLICY);
        when(scoreSource.capabilityScore(AGENT_ID, SECURITY_REVIEW))
                .thenReturn(OptionalDouble.empty());
        when(scoreSource.decisionCount(AGENT_ID, SECURITY_REVIEW)).thenReturn(2);
        when(checker.check(any(), any(), any())).thenReturn(List.of());

        Optional<AttestationOutcome> result = policy.attestationFor(MessageType.DONE, AGENT_ID, ctx);

        assertThat(result).isPresent();
        assertThat(result.get().verdict()).isEqualTo(AttestationVerdict.SOUND);
        verify(checker, times(4)).check(eq("DONE"), any(), any());
    }

    // ── BOOTSTRAP + NOT configured → skips checks ──

    @Test
    void done_bootstrapAgent_checksNotConfigured_skips() {
        CommitmentContext ctx = context(STYLE_REVIEW);
        when(delegate.attestationFor(MessageType.DONE, AGENT_ID, ctx))
                .thenReturn(Optional.of(SOUND_BASE));
        when(policyProvider.forCapability(STYLE_REVIEW)).thenReturn(STYLE_POLICY);
        when(scoreSource.capabilityScore(AGENT_ID, STYLE_REVIEW))
                .thenReturn(OptionalDouble.empty());
        when(scoreSource.decisionCount(AGENT_ID, STYLE_REVIEW)).thenReturn(2);

        Optional<AttestationOutcome> result = policy.attestationFor(MessageType.DONE, AGENT_ID, ctx);

        assertThat(result).isPresent();
        assertThat(result.get().verdict()).isEqualTo(AttestationVerdict.SOUND);
        verify(checker, never()).check(any(), any(), any());
    }

    // ── Empty evidentialCheckPhases → pure passthrough ──

    @Test
    void done_emptyEvidentialPhases_alwaysDelegates() {
        CommitmentContext ctx = context(STYLE_REVIEW);
        when(delegate.attestationFor(MessageType.DONE, AGENT_ID, ctx))
                .thenReturn(Optional.of(SOUND_BASE));
        when(policyProvider.forCapability(STYLE_REVIEW)).thenReturn(STYLE_POLICY);

        Optional<AttestationOutcome> result = policy.attestationFor(MessageType.DONE, AGENT_ID, ctx);

        assertThat(result).isPresent();
        assertThat(result.get().verdict()).isEqualTo(AttestationVerdict.SOUND);
        verify(checker, never()).check(any(), any(), any());
    }

    // ── Non-DONE type → delegate unchanged ──

    @Test
    void failure_alwaysDelegates() {
        CommitmentContext ctx = context(SECURITY_REVIEW);
        AttestationOutcome failOutcome = new AttestationOutcome(
                AttestationVerdict.FLAGGED, 0.6, "system", ActorType.SYSTEM);
        when(delegate.attestationFor(MessageType.FAILURE, AGENT_ID, ctx))
                .thenReturn(Optional.of(failOutcome));

        Optional<AttestationOutcome> result = policy.attestationFor(MessageType.FAILURE, AGENT_ID, ctx);

        assertThat(result).isPresent();
        assertThat(result.get().verdict()).isEqualTo(AttestationVerdict.FLAGGED);
        assertThat(result.get().confidence()).isEqualTo(0.6);
        verify(checker, never()).check(any(), any(), any());
    }

    // ── Null context → delegate ──

    @Test
    void done_nullContext_delegates() {
        when(delegate.attestationFor(MessageType.DONE, AGENT_ID, null))
                .thenReturn(Optional.of(SOUND_BASE));

        Optional<AttestationOutcome> result = policy.attestationFor(MessageType.DONE, AGENT_ID, null);

        assertThat(result).isPresent();
        assertThat(result.get().verdict()).isEqualTo(AttestationVerdict.SOUND);
        verify(checker, never()).check(any(), any(), any());
    }

    // ── Checker throws → WARN log, base outcome ──

    @Test
    void done_checkerThrows_fallsBackToDelegate() {
        CommitmentContext ctx = context(SECURITY_REVIEW);
        when(delegate.attestationFor(MessageType.DONE, AGENT_ID, ctx))
                .thenReturn(Optional.of(SOUND_SCALED));
        when(policyProvider.forCapability(SECURITY_REVIEW)).thenReturn(SECURITY_POLICY);
        when(scoreSource.capabilityScore(AGENT_ID, SECURITY_REVIEW))
                .thenReturn(OptionalDouble.of(0.40));
        when(scoreSource.decisionCount(AGENT_ID, SECURITY_REVIEW)).thenReturn(15);
        when(scoreSource.qualityScores(AGENT_ID, SECURITY_REVIEW)).thenReturn(Map.of());
        when(checker.check(any(), any(), any())).thenThrow(new RuntimeException("store unavailable"));

        Optional<AttestationOutcome> result = policy.attestationFor(MessageType.DONE, AGENT_ID, ctx);

        assertThat(result).isPresent();
        assertThat(result.get().verdict()).isEqualTo(AttestationVerdict.SOUND);
        assertThat(result.get().confidence()).isEqualTo(0.35);
    }

    // ── BORDERLINE in phases → runs checks ──

    @Test
    void done_borderline_inPhases_runsChecks() {
        TrustRoutingPolicy mergePolicy = new TrustRoutingPolicy(
                0.80, 15, 0.05, 0.6, Map.of(), true, null,
                Set.of(TrustPhase.BELOW_THRESHOLD, TrustPhase.QUALITY_FAILED,
                       TrustPhase.BOOTSTRAP, TrustPhase.BORDERLINE));
        String mergeExecutor = "merge-executor";
        CommitmentContext ctx = context(mergeExecutor);
        when(delegate.attestationFor(MessageType.DONE, AGENT_ID, ctx))
                .thenReturn(Optional.of(SOUND_BASE));
        when(policyProvider.forCapability(mergeExecutor)).thenReturn(mergePolicy);
        when(scoreSource.capabilityScore(AGENT_ID, mergeExecutor))
                .thenReturn(OptionalDouble.of(0.78));
        when(scoreSource.decisionCount(AGENT_ID, mergeExecutor)).thenReturn(20);
        when(scoreSource.qualityScores(AGENT_ID, mergeExecutor)).thenReturn(Map.of());
        when(checker.check(any(), any(), any())).thenReturn(List.of());

        Optional<AttestationOutcome> result = policy.attestationFor(MessageType.DONE, AGENT_ID, ctx);

        assertThat(result).isPresent();
        verify(checker, times(4)).check(eq("DONE"), any(), any());
    }

    // ── QUALITY_FAILED in phases → runs checks ──

    @Test
    void done_qualityFailed_inPhases_runsChecks() {
        CommitmentContext ctx = context(SECURITY_REVIEW);
        when(delegate.attestationFor(MessageType.DONE, AGENT_ID, ctx))
                .thenReturn(Optional.of(SOUND_BASE));
        when(policyProvider.forCapability(SECURITY_REVIEW)).thenReturn(SECURITY_POLICY);
        when(scoreSource.capabilityScore(AGENT_ID, SECURITY_REVIEW))
                .thenReturn(OptionalDouble.of(0.85));
        when(scoreSource.decisionCount(AGENT_ID, SECURITY_REVIEW)).thenReturn(20);
        when(scoreSource.qualityScores(AGENT_ID, SECURITY_REVIEW))
                .thenReturn(Map.of("review-thoroughness", 0.30));
        when(checker.check(any(), any(), any())).thenReturn(List.of());

        // SECURITY_POLICY has no quality floors defined in the test, so the quality check
        // depends on the policy's qualityFloors map. Let me use a policy with floors.
        TrustRoutingPolicy withFloors = new TrustRoutingPolicy(
                0.70, 10, 0.05, 0.6, Map.of("review-thoroughness", 0.5), true, null,
                Set.of(TrustPhase.BELOW_THRESHOLD, TrustPhase.QUALITY_FAILED, TrustPhase.BOOTSTRAP));
        when(policyProvider.forCapability(SECURITY_REVIEW)).thenReturn(withFloors);

        Optional<AttestationOutcome> result = policy.attestationFor(MessageType.DONE, AGENT_ID, ctx);

        assertThat(result).isPresent();
        verify(checker, times(4)).check(eq("DONE"), any(), any());
    }

    private static CommitmentContext context(String capabilityTag) {
        return new CommitmentContext(
                UUID.randomUUID().toString(), UUID.randomUUID(), "test-channel",
                UUID.randomUUID(), capabilityTag, null, null, null);
    }
}
