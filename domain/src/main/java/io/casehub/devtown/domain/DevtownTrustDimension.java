package io.casehub.devtown.domain;

public final class DevtownTrustDimension {

    public static final String REVIEW_THOROUGHNESS = "review-thoroughness";
    public static final String PRECISION           = "precision"; // TP/(TP+FP); higher = better
    public static final String SCOPE_CALIBRATION   = "scope-calibration";

    private DevtownTrustDimension() {}
}
