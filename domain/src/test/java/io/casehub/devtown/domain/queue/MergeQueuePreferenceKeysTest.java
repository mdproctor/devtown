package io.casehub.devtown.domain.queue;

import io.casehub.devtown.domain.preferences.BooleanPreference;
import io.casehub.devtown.domain.preferences.DoublePreference;
import io.casehub.devtown.domain.preferences.IntPreference;
import io.casehub.devtown.domain.sla.StringPreference;
import io.casehub.platform.api.preferences.PreferenceKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MergeQueuePreferenceKeysTest {

    @Test
    void enabled_hasCorrectQualifiedNameAndDefault() {
        PreferenceKey<BooleanPreference> key = MergeQueuePreferenceKeys.ENABLED;
        assertEquals("devtown.merge-queue.enabled", key.qualifiedName());
        assertEquals(false, key.defaultValue().value());
    }

    @Test
    void enabled_parseTrue() {
        PreferenceKey<BooleanPreference> key = MergeQueuePreferenceKeys.ENABLED;
        BooleanPreference parsed = key.parser().apply("true");
        assertTrue(parsed.value());
    }

    @Test
    void enabled_parseFalse() {
        PreferenceKey<BooleanPreference> key = MergeQueuePreferenceKeys.ENABLED;
        BooleanPreference parsed = key.parser().apply("false");
        assertFalse(parsed.value());
    }

    @Test
    void maxBatchSize_hasCorrectQualifiedNameAndDefault() {
        PreferenceKey<IntPreference> key = MergeQueuePreferenceKeys.MAX_BATCH_SIZE;
        assertEquals("devtown.merge-queue.max-batch-size", key.qualifiedName());
        assertEquals(10, key.defaultValue().value());
    }

    @Test
    void maxBatchSize_parse() {
        PreferenceKey<IntPreference> key = MergeQueuePreferenceKeys.MAX_BATCH_SIZE;
        IntPreference parsed = key.parser().apply("20");
        assertEquals(20, parsed.value());
    }

    @Test
    void minBatchSize_hasCorrectQualifiedNameAndDefault() {
        PreferenceKey<IntPreference> key = MergeQueuePreferenceKeys.MIN_BATCH_SIZE;
        assertEquals("devtown.merge-queue.min-batch-size", key.qualifiedName());
        assertEquals(1, key.defaultValue().value());
    }

    @Test
    void bisectionStrategy_hasCorrectQualifiedNameAndDefault() {
        PreferenceKey<StringPreference> key = MergeQueuePreferenceKeys.BISECTION_STRATEGY;
        assertEquals("devtown.merge-queue.bisection-strategy", key.qualifiedName());
        assertEquals("trust-weighted", key.defaultValue().value());
    }

    @Test
    void bisectionStrategy_parse() {
        PreferenceKey<StringPreference> key = MergeQueuePreferenceKeys.BISECTION_STRATEGY;
        StringPreference parsed = key.parser().apply("sequential");
        assertEquals("sequential", parsed.value());
    }

    @Test
    void failureRateWindow_hasCorrectQualifiedNameAndDefault() {
        PreferenceKey<IntPreference> key = MergeQueuePreferenceKeys.FAILURE_RATE_WINDOW;
        assertEquals("devtown.merge-queue.failure-rate-window", key.qualifiedName());
        assertEquals(20, key.defaultValue().value());
    }

    @Test
    void decayRatePerHour_hasCorrectQualifiedNameAndDefault() {
        PreferenceKey<IntPreference> key = MergeQueuePreferenceKeys.DECAY_RATE_PER_HOUR;
        assertEquals("devtown.merge-queue.priority.decay-rate-per-hour", key.qualifiedName());
        assertEquals(125, key.defaultValue().value());
    }

    @Test
    void targetBranch_hasCorrectQualifiedNameAndDefault() {
        PreferenceKey<StringPreference> key = MergeQueuePreferenceKeys.TARGET_BRANCH;
        assertEquals("devtown.merge-queue.target-branch", key.qualifiedName());
        assertEquals("main", key.defaultValue().value());
    }

    @Test
    void slaCritical_hasCorrectQualifiedNameAndDefault() {
        PreferenceKey<StringPreference> key = MergeQueuePreferenceKeys.SLA_CRITICAL;
        assertEquals("devtown.merge-queue.sla.CRITICAL", key.qualifiedName());
        assertEquals("PT1H", key.defaultValue().value());
    }

    @Test
    void slaHigh_hasCorrectQualifiedNameAndDefault() {
        PreferenceKey<StringPreference> key = MergeQueuePreferenceKeys.SLA_HIGH;
        assertEquals("devtown.merge-queue.sla.HIGH", key.qualifiedName());
        assertEquals("PT4H", key.defaultValue().value());
    }

    @Test
    void slaNormal_hasCorrectQualifiedNameAndDefault() {
        PreferenceKey<StringPreference> key = MergeQueuePreferenceKeys.SLA_NORMAL;
        assertEquals("devtown.merge-queue.sla.NORMAL", key.qualifiedName());
        assertEquals("PT8H", key.defaultValue().value());
    }

    @Test
    void slaKeyFor_critical() {
        PreferenceKey<StringPreference> key = MergeQueuePreferenceKeys.slaKeyFor(PriorityLane.CRITICAL);
        assertSame(MergeQueuePreferenceKeys.SLA_CRITICAL, key);
    }

    @Test
    void slaKeyFor_high() {
        PreferenceKey<StringPreference> key = MergeQueuePreferenceKeys.slaKeyFor(PriorityLane.HIGH);
        assertSame(MergeQueuePreferenceKeys.SLA_HIGH, key);
    }

    @Test
    void slaKeyFor_normal() {
        PreferenceKey<StringPreference> key = MergeQueuePreferenceKeys.slaKeyFor(PriorityLane.NORMAL);
        assertSame(MergeQueuePreferenceKeys.SLA_NORMAL, key);
    }

    @Test
    void defaultAdmissionTrustScore_hasCorrectQualifiedNameAndDefault() {
        PreferenceKey<DoublePreference> key = MergeQueuePreferenceKeys.DEFAULT_ADMISSION_TRUST_SCORE;
        assertEquals("devtown.merge-queue.default-admission-trust-score", key.qualifiedName());
        assertEquals(0.5, key.defaultValue().value(), 0.001);
    }

    @Test
    void defaultAdmissionTrustScore_parse() {
        PreferenceKey<DoublePreference> key    = MergeQueuePreferenceKeys.DEFAULT_ADMISSION_TRUST_SCORE;
        DoublePreference                parsed = key.parser().apply("0.3");
        assertEquals(0.3, parsed.value(), 0.001);
    }

    @Test
    void defaultAdmissionTrustScore_parseInvalid_throws() {
        PreferenceKey<DoublePreference> key = MergeQueuePreferenceKeys.DEFAULT_ADMISSION_TRUST_SCORE;
        assertThrows(NumberFormatException.class, () -> key.parser().apply("not-a-number"));
    }


}
