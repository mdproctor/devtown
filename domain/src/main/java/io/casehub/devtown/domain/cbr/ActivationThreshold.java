package io.casehub.devtown.domain.cbr;

public record ActivationThreshold(int minFindings, double minFraction) {

    public static final ActivationThreshold DEFAULT = new ActivationThreshold(2, 0.4);
}
