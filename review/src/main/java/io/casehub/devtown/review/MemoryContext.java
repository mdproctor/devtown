package io.casehub.devtown.review;

import io.casehub.devtown.domain.memory.DevtownMemoryKeys;
import io.casehub.devtown.domain.memory.ReviewOutcome;
import io.casehub.neocortex.memory.Memory;
import io.casehub.neocortex.memory.MemoryAttributeKeys;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record MemoryContext(
    List<Memory> contributorHistory,
    List<Memory> codeAreaHistory
) {
    public static final MemoryContext EMPTY = new MemoryContext(List.of(), List.of());

    private static final Set<String> SAFE_OUTCOMES = Set.of("approved", "passed");

    public Map<String, Object> toContextMap() {
        return Map.of(
            "contributorHistory", toEntryList(contributorHistory),
            "codeAreaHistory", toEntryList(codeAreaHistory)
        );
    }

    public boolean hasRiskSignals() {
        return hasRisk(contributorHistory) || hasRisk(codeAreaHistory);
    }

    private static boolean hasRisk(List<Memory> memories) {
        return memories.stream().anyMatch(m -> {
            String outcome = m.attributes().get(MemoryAttributeKeys.OUTCOME);
            if (ReviewOutcome.FAILED.name().equals(outcome)) return true;
            if (ReviewOutcome.COMPLETED.name().equals(outcome)) {
                String detail = m.attributes().get(DevtownMemoryKeys.OUTCOME_DETAIL);
                return detail == null || !SAFE_OUTCOMES.contains(detail.toLowerCase());
            }
            return false;
        });
    }

    private static List<Map<String, Object>> toEntryList(List<Memory> memories) {
        return memories.stream().map(m -> Map.<String, Object>of(
            "text", m.text(),
            "outcome", m.attributes().getOrDefault(MemoryAttributeKeys.OUTCOME, ""),
            "capability", m.attributes().getOrDefault(DevtownMemoryKeys.CAPABILITY, ""),
            "createdAt", m.createdAt().toString()
        )).toList();
    }
}
