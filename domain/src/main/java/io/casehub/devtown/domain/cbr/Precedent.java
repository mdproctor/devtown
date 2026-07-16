package io.casehub.devtown.domain.cbr;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

public record Precedent(
        UUID caseId,
        SimilarityScore similarity,
        PrFeatureVector vector,
        String outcome,
        Map<String, CapabilityOutcome> capabilityOutcomes,
        Duration completionTime
) {}
