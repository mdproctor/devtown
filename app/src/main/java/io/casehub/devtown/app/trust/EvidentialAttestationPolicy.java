package io.casehub.devtown.app.trust;

import io.casehub.api.spi.routing.TrustPhase;
import io.casehub.api.spi.routing.TrustRoutingPolicy;
import io.casehub.api.spi.routing.TrustRoutingPolicyProvider;
import io.casehub.ledger.api.model.AttestationVerdict;
import io.casehub.ledger.api.model.CapabilityTag;
import io.casehub.ledger.api.spi.TrustScoreSource;
import io.casehub.ledger.routing.TrustGatedAttestationPolicy;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.spi.CommitmentAttestationPolicy;
import io.casehub.qhorus.api.spi.CommitmentContext;
import io.casehub.qhorus.runtime.audit.BenchmarkContext;
import io.casehub.qhorus.runtime.audit.BenchmarkViolation;
import io.casehub.qhorus.runtime.audit.EvidentialChecker;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import org.jboss.logging.Logger;

@Alternative
@Priority(2)
@ApplicationScoped
public class EvidentialAttestationPolicy implements CommitmentAttestationPolicy {

    private static final Logger LOG = Logger.getLogger(EvidentialAttestationPolicy.class);
    static final double EVIDENTIAL_FAILURE_CONFIDENCE = 0.8;

    private final TrustGatedAttestationPolicy delegate;
    private final EvidentialChecker checker;
    private final TrustScoreSource scoreSource;
    private final TrustRoutingPolicyProvider policyProvider;

    @Inject
    public EvidentialAttestationPolicy(
            final TrustGatedAttestationPolicy delegate,
            final EvidentialChecker checker,
            final TrustScoreSource scoreSource,
            final TrustRoutingPolicyProvider policyProvider) {
        this.delegate = delegate;
        this.checker = checker;
        this.scoreSource = scoreSource;
        this.policyProvider = policyProvider;
    }

    public TrustGatedAttestationPolicy delegate() {
        return delegate;
    }

    @Override
    public Optional<AttestationOutcome> attestationFor(
            final MessageType terminalType,
            final String resolvedActorId,
            final CommitmentContext context) {

        Optional<AttestationOutcome> base = delegate.attestationFor(terminalType, resolvedActorId, context);

        if (terminalType != MessageType.DONE) {
            return base;
        }
        if (context == null || !hasCapabilityTag(context)) {
            return base;
        }

        String capabilityTag = context.capabilityTag();
        TrustRoutingPolicy routingPolicy = policyProvider.forCapability(capabilityTag);

        if (routingPolicy.evidentialCheckPhases().isEmpty()) {
            return base;
        }

        try {
            TrustPhase phase = classifyPhase(resolvedActorId, capabilityTag, routingPolicy);

            if (!routingPolicy.evidentialCheckPhases().contains(phase)) {
                return base;
            }

            List<BenchmarkViolation> violations = runEvidentialChecks(context);

            if (violations.isEmpty()) {
                return base;
            }

            LOG.warnf("Evidential check failed for %s on %s: %d violations — %s",
                    resolvedActorId, capabilityTag, violations.size(), summarize(violations));

            return Optional.of(new AttestationOutcome(
                    AttestationVerdict.FLAGGED, EVIDENTIAL_FAILURE_CONFIDENCE,
                    "system", ActorType.SYSTEM));
        } catch (Exception e) {
            LOG.warnf("Evidential check error for %s on %s — falling back to delegate: %s",
                    resolvedActorId, capabilityTag, e.getMessage());
            return base;
        }
    }

    private TrustPhase classifyPhase(
            final String actorId, final String capabilityTag, final TrustRoutingPolicy policy) {

        OptionalDouble capScore = scoreSource.capabilityScore(actorId, capabilityTag);
        int decCount = scoreSource.decisionCount(actorId, capabilityTag);

        if (capScore.isEmpty() || policy.isBootstrap(decCount)) {
            return TrustPhase.BOOTSTRAP;
        }

        double score = capScore.getAsDouble();

        if (policy.isBorderline(score)) {
            return TrustPhase.BORDERLINE;
        }
        if (!policy.passesThresholdCheck(score)) {
            return TrustPhase.BELOW_THRESHOLD;
        }

        Map<String, Double> qualityScores = scoreSource.qualityScores(actorId, capabilityTag);
        for (Map.Entry<String, Double> floor : policy.qualityFloors().entrySet()) {
            Double actual = qualityScores.get(floor.getKey());
            if (actual != null && actual < floor.getValue()) {
                return TrustPhase.QUALITY_FAILED;
            }
        }

        return TrustPhase.QUALIFIED;
    }

    private List<BenchmarkViolation> runEvidentialChecks(final CommitmentContext ctx) {
        List<BenchmarkViolation> all = new ArrayList<>();

        all.addAll(checker.check("DONE", null,
                new BenchmarkContext("V1", ctx.artefactUuid(), null, null, null, null)));
        all.addAll(checker.check("DONE", null,
                new BenchmarkContext("V2", null, ctx.channelId(), null, null, null)));
        all.addAll(checker.check("DONE", null,
                new BenchmarkContext("V3", null, null, ctx.correlationId(), null, null)));
        all.addAll(checker.check("DONE", ctx.content(),
                new BenchmarkContext("V4", null, null, null, ctx.expectedToken(), null)));

        return all;
    }

    private static boolean hasCapabilityTag(final CommitmentContext context) {
        String tag = context.capabilityTag();
        return tag != null && !tag.isEmpty() && !CapabilityTag.GLOBAL.equals(tag);
    }

    private static String summarize(final List<BenchmarkViolation> violations) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < violations.size(); i++) {
            if (i > 0) sb.append("; ");
            BenchmarkViolation v = violations.get(i);
            sb.append(v.variantId()).append(": ").append(v.description());
        }
        return sb.toString();
    }
}
