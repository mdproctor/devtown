package io.casehub.devtown.domain.preferences;

import io.casehub.platform.api.preferences.PreferenceKey;

public final class PrReviewPreferenceKeys {

    public static final PreferenceKey<IntPreference> HUMAN_APPROVAL_THRESHOLD =
        new PreferenceKey<>("devtown.pr-review", "human-approval-threshold",
            IntPreference.of(500), IntPreference::parse);

    public static final PreferenceKey<BooleanPreference> SECURITY_REVIEW_REQUIRED =
        new PreferenceKey<>("devtown.pr-review", "security-review-required",
            BooleanPreference.of(true), BooleanPreference::parse);

    public static final PreferenceKey<BooleanPreference> REQUIRE_SENIOR_APPROVAL =
        new PreferenceKey<>("devtown.pr-review", "require-senior-approval",
            BooleanPreference.of(false), BooleanPreference::parse);

    private PrReviewPreferenceKeys() {}
}
