package io.casehub.devtown.review;

import io.casehub.devtown.domain.cbr.CapabilityOutcome;
import io.casehub.devtown.domain.cbr.Precedent;
import io.casehub.devtown.domain.memory.DevtownMemoryKeys;
import io.casehub.devtown.domain.memory.ReviewOutcome;
import io.casehub.neocortex.memory.Memory;
import io.casehub.neocortex.memory.MemoryAttributeKeys;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record MemoryContext(
        List<Memory> contributorHistory,
        List<Memory> codeAreaHistory,
        List<Precedent> precedents,
        Set<String> precedentActivations
) {
    public static final MemoryContext EMPTY = new MemoryContext(List.of(), List.of(), List.of(), Set.of());

    public Map<String, Object> toContextMap() {
        return Map.of(
                "contributorHistory", toEntryList(contributorHistory),
                "codeAreaHistory", toEntryList(codeAreaHistory),
                "precedents", precedents.stream().map(p -> {
                    var m = new java.util.LinkedHashMap<String, Object>();
                    m.put("caseId", p.caseId().toString());
                    m.put("similarity", p.similarity().score());
                    m.put("breakdown", p.similarity().breakdown());
                    m.put("outcome", p.outcome());
                    m.put("capabilityOutcomes", p.capabilityOutcomes().entrySet().stream()
                                                 .collect(java.util.stream.Collectors.toMap(
                                                         Map.Entry::getKey,
                                                         e -> {
                                                             var co = e.getValue();
                                                             var cm = new java.util.LinkedHashMap<String, String>();
                                                             cm.put("outcome", co.outcome());
                                                             if (co.detail() != null) {cm.put("detail", co.detail());}
                                                             return cm;
                                                         })));
                    if (p.completionTime() != null) {
                        m.put("completionTimeSeconds", p.completionTime().toSeconds());
                    }
                    return m;
                }).toList(),
                "precedentActivations", List.copyOf(precedentActivations)
                     );}

    public boolean hasRiskSignals() {
        return hasRisk(contributorHistory) || hasRisk(codeAreaHistory)
               || precedents.stream().anyMatch(p -> "failed".equals(p.outcome()));
    }

    private static boolean hasRisk(List<Memory> memories) {
        return memories.stream().anyMatch(m -> {
            String outcome = m.attributes().get(MemoryAttributeKeys.OUTCOME);
            if (ReviewOutcome.FAILED.name().equals(outcome)) {return true;}
            String detail = m.attributes().get(DevtownMemoryKeys.OUTCOME_DETAIL);
            return new CapabilityOutcome(outcome, detail).hadFindings();
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
