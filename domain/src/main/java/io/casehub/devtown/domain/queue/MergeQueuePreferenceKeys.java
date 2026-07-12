package io.casehub.devtown.domain.queue;

import io.casehub.devtown.domain.preferences.BooleanPreference;
import io.casehub.devtown.domain.preferences.IntPreference;
import io.casehub.devtown.domain.sla.StringPreference;
import io.casehub.platform.api.preferences.PreferenceKey;

public final class MergeQueuePreferenceKeys {

    public static final PreferenceKey<BooleanPreference> ENABLED =
        new PreferenceKey<>("devtown.merge-queue", "enabled",
            BooleanPreference.of(false), BooleanPreference::parse);

    public static final PreferenceKey<IntPreference> MAX_BATCH_SIZE =
        new PreferenceKey<>("devtown.merge-queue", "max-batch-size",
            IntPreference.of(10), IntPreference::parse);

    public static final PreferenceKey<IntPreference> MIN_BATCH_SIZE =
        new PreferenceKey<>("devtown.merge-queue", "min-batch-size",
            IntPreference.of(1), IntPreference::parse);

    public static final PreferenceKey<StringPreference> BISECTION_STRATEGY =
        new PreferenceKey<>("devtown.merge-queue", "bisection-strategy",
            StringPreference.of("trust-weighted"), StringPreference::parse);

    public static final PreferenceKey<IntPreference> FAILURE_RATE_WINDOW =
        new PreferenceKey<>("devtown.merge-queue", "failure-rate-window",
            IntPreference.of(20), IntPreference::parse);

    public static final PreferenceKey<IntPreference> DECAY_RATE_PER_HOUR =
        new PreferenceKey<>("devtown.merge-queue.priority", "decay-rate-per-hour",
            IntPreference.of(125), IntPreference::parse);

    public static final PreferenceKey<StringPreference> TARGET_BRANCH =
        new PreferenceKey<>("devtown.merge-queue", "target-branch",
            StringPreference.of("main"), StringPreference::parse);

    public static final PreferenceKey<StringPreference> MERGE_READY_LABEL =
        new PreferenceKey<>("devtown.merge-queue", "merge-ready-label",
            StringPreference.of("merge-ready"), StringPreference::parse);

    public static final PreferenceKey<BooleanPreference> DEQUEUE_ON_UNLABEL =
        new PreferenceKey<>("devtown.merge-queue", "dequeue-on-unlabel",
            BooleanPreference.of(false), BooleanPreference::parse);
    public static final PreferenceKey<IntPreference>     BATCH_RETENTION_DAYS =
            new PreferenceKey<>("devtown.merge-queue", "batch-retention-days",
                                IntPreference.of(30), IntPreference::parse);


    public static final PreferenceKey<StringPreference> SLA_CRITICAL =
        new PreferenceKey<>("devtown.merge-queue.sla", "CRITICAL",
            StringPreference.of("PT1H"), StringPreference::parse);

    public static final PreferenceKey<StringPreference> SLA_HIGH =
        new PreferenceKey<>("devtown.merge-queue.sla", "HIGH",
            StringPreference.of("PT4H"), StringPreference::parse);

    public static final PreferenceKey<StringPreference> SLA_NORMAL =
        new PreferenceKey<>("devtown.merge-queue.sla", "NORMAL",
            StringPreference.of("PT8H"), StringPreference::parse);

    public static PreferenceKey<StringPreference> slaKeyFor(PriorityLane lane) {
        return switch (lane) {
            case CRITICAL -> SLA_CRITICAL;
            case HIGH -> SLA_HIGH;
            case NORMAL -> SLA_NORMAL;
        };
    }

    private MergeQueuePreferenceKeys() {}
}
