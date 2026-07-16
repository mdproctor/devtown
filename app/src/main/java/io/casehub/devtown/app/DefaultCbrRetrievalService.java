package io.casehub.devtown.app;

import io.casehub.devtown.domain.cbr.CapabilityOutcome;
import io.casehub.devtown.domain.cbr.CbrPreferenceKeys;
import io.casehub.devtown.domain.cbr.PrFeatureVector;
import io.casehub.devtown.domain.cbr.Precedent;
import io.casehub.devtown.domain.cbr.SimilarityGate;
import io.casehub.devtown.domain.cbr.SimilarityMetric;
import io.casehub.devtown.domain.cbr.SimilarityScore;
import io.casehub.devtown.domain.cbr.WeightedJaccardSimilarity;
import io.casehub.devtown.domain.memory.DevtownMemoryDomain;
import io.casehub.devtown.domain.memory.DevtownMemoryKeys;
import io.casehub.devtown.review.CbrRetrievalService;
import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.neocortex.memory.Memory;
import io.casehub.neocortex.memory.MemoryAttributeKeys;
import io.casehub.neocortex.memory.MemoryQuery;
import io.casehub.neocortex.memory.MemoryScanRequest;
import io.casehub.platform.api.preferences.PreferenceProvider;
import io.casehub.platform.api.preferences.Preferences;
import io.casehub.platform.api.preferences.SettingsScope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class DefaultCbrRetrievalService implements CbrRetrievalService {

    private static final Logger             LOG       = Logger.getLogger(DefaultCbrRetrievalService.class);
    private static final SettingsScope      CBR_SCOPE =
            SettingsScope.of("casehubio", "devtown", "cbr");
    private final        CaseMemoryStore         store;
    private final        PreferenceProvider      preferenceProvider;
    private final        CbrWeightOverrideStore  weightOverrides;

    @Inject
    public DefaultCbrRetrievalService(CaseMemoryStore store,
                                      PreferenceProvider preferenceProvider,
                                      CbrWeightOverrideStore weightOverrides) {
        this.store              = store;
        this.preferenceProvider = preferenceProvider;
        this.weightOverrides    = weightOverrides;
    }

    @Override
    public List<Precedent> findSimilar(PrFeatureVector query, String repo, String tenantId) {
        try {
            Preferences prefs          = preferenceProvider.resolve(CBR_SCOPE);
            int         kLimit         = prefs.getOrDefault(CbrPreferenceKeys.K_LIMIT).value();
            double      minThreshold   = prefs.getOrDefault(CbrPreferenceKeys.MIN_THRESHOLD).value();
            int         timeWindowDays = prefs.getOrDefault(CbrPreferenceKeys.TIME_WINDOW_DAYS).value();
            Instant     since          = Instant.now().minus(Duration.ofDays(timeWindowDays));

            SimilarityMetric metric = buildMetric(prefs);
            SimilarityGate   gate   = buildGate(prefs);

            List<Memory> caseVectors = store.scan(new MemoryScanRequest(
                    tenantId,
                    DevtownMemoryDomain.SOFTWARE_REVIEW.name(),
                    DevtownMemoryKeys.ENTITY_TYPE,
                    "case-vector",
                    1000,
                    null));

            return caseVectors.stream()
                              .filter(m -> m.createdAt().isAfter(since))
                              .map(m -> toCandidateVector(m))
                              .filter(cv -> cv != null)
                              .filter(cv -> gate.passes(query, cv.vector))
                              .map(cv -> scoreCandidate(cv, query, metric, tenantId))
                              .filter(scored -> scored != null)
                              .filter(scored -> scored.similarity().score() >= minThreshold)
                              .sorted(Comparator.comparing(Precedent::similarity).reversed())
                              .limit(kLimit)
                              .toList();
        } catch (Exception e) {
            LOG.warnf(e, "CBR retrieval failed for repo=%s — returning empty precedents", repo);
            return List.of();
        }
    }

    private CandidateVector toCandidateVector(Memory memory) {
        try {
            PrFeatureVector stored = PrFeatureVector.fromAttributes(memory.attributes());
            UUID            caseId = UUID.fromString(memory.caseId());
            return new CandidateVector(caseId, stored, stored.contributor(), memory.createdAt());
        } catch (Exception e) {
            LOG.debugf(e, "Failed to parse candidate memory=%s", memory.memoryId());
            return null;
        }}

    private Precedent scoreCandidate(CandidateVector cv, PrFeatureVector query,
                                     SimilarityMetric metric, String tenantId) {
        try {
            SimilarityScore score = metric.compute(query, cv.vector);

            EnrichmentResult enrichment = enrichOutcomes(cv.caseId, cv.contributor, tenantId);
            if (enrichment.outcomes().isEmpty()) {return null;}

            Duration completionTime = null;
            if (enrichment.latestOutcomeTime() != null && cv.startedAt() != null) {
                Duration raw = Duration.between(cv.startedAt(), enrichment.latestOutcomeTime());
                if (raw.isNegative()) {
                    LOG.warnf("Negative completion time for case=%s: start=%s outcome=%s — possible clock skew or async race",
                              cv.caseId(), cv.startedAt(), enrichment.latestOutcomeTime());
                } else {
                    completionTime = raw;
                }
            }

            String aggregate = aggregateOutcome(enrichment.outcomes());
            return new Precedent(cv.caseId, score, cv.vector, aggregate, enrichment.outcomes(), completionTime);
        } catch (Exception e) {
            LOG.debugf(e, "Failed to score candidate case=%s", cv.caseId);
            return null;
        }}

    private EnrichmentResult enrichOutcomes(UUID caseId, String contributor, String tenantId) {
        try {
            List<Memory> outcomeFacts = store.query(
                    MemoryQuery.forEntity(
                                       DevtownMemoryDomain.CONTRIBUTOR_PREFIX + contributor,
                                       DevtownMemoryDomain.SOFTWARE_REVIEW,
                                       tenantId)
                               .withCaseId(caseId.toString())
                               .withLimit(20));

            var outcomes = new LinkedHashMap<String, CapabilityOutcome>();
            for (var fact : outcomeFacts) {
                String capability = fact.attributes().get(DevtownMemoryKeys.CAPABILITY);
                String outcome    = fact.attributes().get(MemoryAttributeKeys.OUTCOME);
                String detail     = fact.attributes().get(DevtownMemoryKeys.OUTCOME_DETAIL);
                if (capability != null && outcome != null) {
                    outcomes.put(capability, new CapabilityOutcome(outcome, detail));
                }
            }

            Instant latestOutcome = outcomeFacts.stream()
                                                .map(Memory::createdAt)
                                                .max(Instant::compareTo)
                                                .orElse(null);

            return new EnrichmentResult(outcomes, latestOutcome);
        } catch (Exception e) {
            LOG.debugf(e, "Failed to enrich outcomes for case=%s", caseId);
            return new EnrichmentResult(Map.of(), null);
        }
    }

    private String aggregateOutcome(Map<String, CapabilityOutcome> capabilityOutcomes) {
        boolean anyFailed = capabilityOutcomes.values().stream()
                                              .anyMatch(co -> "FAILED".equals(co.outcome()));
        if (anyFailed) {return "failed";}
        boolean anyFindings = capabilityOutcomes.values().stream()
                                                .anyMatch(CapabilityOutcome::hadFindings);
        return anyFindings ? "flagged" : "approved";
    }

    private SimilarityGate buildGate(Preferences prefs) {
        return new SimilarityGate(
                prefs.getOrDefault(CbrPreferenceKeys.GATE_MIN_MODULE_OVERLAP).value(),
                prefs.getOrDefault(CbrPreferenceKeys.GATE_MIN_CHANGE_SIZE_RATIO).value(),
                prefs.getOrDefault(CbrPreferenceKeys.GATE_SAME_REPO).value()
        );
    }

    private SimilarityMetric buildMetric(Preferences prefs) {
        return new WeightedJaccardSimilarity(
                weightOverrides.resolveWeight("filePaths",
                                              prefs.getOrDefault(CbrPreferenceKeys.WEIGHT_FILE_PATHS).value()),
                weightOverrides.resolveWeight("modules",
                                              prefs.getOrDefault(CbrPreferenceKeys.WEIGHT_MODULES).value()),
                weightOverrides.resolveWeight("languages",
                                              prefs.getOrDefault(CbrPreferenceKeys.WEIGHT_LANGUAGES).value()),
                weightOverrides.resolveWeight("changeSize",
                                              prefs.getOrDefault(CbrPreferenceKeys.WEIGHT_CHANGE_SIZE).value()),
                weightOverrides.resolveWeight("contributor",
                                              prefs.getOrDefault(CbrPreferenceKeys.WEIGHT_CONTRIBUTOR).value())
        );
    }


    private record EnrichmentResult(Map<String, CapabilityOutcome> outcomes, Instant latestOutcomeTime) {}

    private record CandidateVector(UUID caseId, PrFeatureVector vector, String contributor, Instant startedAt) {}
}
