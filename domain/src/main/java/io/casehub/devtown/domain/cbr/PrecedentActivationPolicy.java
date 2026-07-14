package io.casehub.devtown.domain.cbr;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public final class PrecedentActivationPolicy {

    private PrecedentActivationPolicy() {}

    public static Set<String> evaluate(List<Precedent> precedents,
                                       Function<String, ActivationThreshold> thresholds) {
        if (precedents.isEmpty()) {return Set.of();}
        Map<String, Double> weighted = weightedFindings(precedents);
        double totalWeight = precedents.stream()
                                       .mapToDouble(p -> p.similarity().score())
                                       .sum();
        Set<String> result = new LinkedHashSet<>();
        for (var entry : weighted.entrySet()) {
            String              capability = entry.getKey();
            double              evidence   = entry.getValue();
            ActivationThreshold t          = thresholds.apply(capability);
            if (evidence >= t.minEvidence() &&
                (totalWeight > 0 && evidence / totalWeight >= t.minFraction())) {
                result.add(capability);
            }
        }
        return Set.copyOf(result);
    }

    private static Map<String, Double> weightedFindings(List<Precedent> precedents) {
        var weighted = new HashMap<String, Double>();
        for (var precedent : precedents) {
            double weight = precedent.similarity().score();
            for (var entry : precedent.capabilityOutcomes().entrySet()) {
                if (entry.getValue().hadFindings()) {
                    weighted.merge(entry.getKey(), weight, Double::sum);
                }
            }
        }
        return weighted;
    }
}
