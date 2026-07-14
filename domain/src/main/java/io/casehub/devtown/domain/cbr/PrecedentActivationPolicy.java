package io.casehub.devtown.domain.cbr;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PrecedentActivationPolicy {

    private PrecedentActivationPolicy() {}

    public static Set<String> evaluate(List<Precedent> precedents,
                                       java.util.function.Function<String, ActivationThreshold> thresholds) {
        if (precedents.isEmpty()) {return Set.of();}
        Map<String, Long> counts = countFindings(precedents);
        Set<String>       result = new LinkedHashSet<>();
        for (var entry : counts.entrySet()) {
            String              capability = entry.getKey();
            long                count      = entry.getValue();
            ActivationThreshold t          = thresholds.apply(capability);
            if (count >= t.minFindings() &&
                (double) count / precedents.size() >= t.minFraction()) {
                result.add(capability);
            }
        }
        return Set.copyOf(result);
    }

    private static Map<String, Long> countFindings(List<Precedent> precedents) {
        var counts = new HashMap<String, Long>();
        for (var precedent : precedents) {
            for (var entry : precedent.capabilityOutcomes().entrySet()) {
                if (entry.getValue().hadFindings()) {
                    counts.merge(entry.getKey(), 1L, Long::sum);
                }
            }
        }
        return counts;
    }
}
