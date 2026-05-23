package io.casehub.devtown.domain.sla;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.platform.api.path.Path;
import io.casehub.platform.api.preferences.MapPreferences;
import io.casehub.platform.api.preferences.Preferences;
import io.casehub.work.api.BreachDecision;
import io.casehub.work.api.BreachType;
import io.casehub.work.api.BreachedTask;
import io.casehub.work.api.SlaBreachContext;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DefaultSlaBreachPolicyTest {

    private final DefaultSlaBreachPolicy policy = new DefaultSlaBreachPolicy();

    @Test
    void firstBreach_escalatesToDefaultEscalationGroup() {
        var ctx = ctx(Set.of("pr-reviewers"), defaultPrefs());
        var decision = policy.onBreach(ctx);
        assertThat(decision).isInstanceOf(BreachDecision.EscalateTo.class);
        var escalate = (BreachDecision.EscalateTo) decision;
        assertThat(escalate.groups()).containsExactly("pr-leads");
    }

    @Test
    void firstBreach_deadlineIsEscalationHours() {
        var ctx = ctx(Set.of("pr-reviewers"), defaultPrefs());
        var decision = (BreachDecision.EscalateTo) policy.onBreach(ctx);
        assertThat(decision.deadline()).isEqualTo(Duration.ofHours(8));
    }

    @Test
    void firstBreach_claimExpiredAlsoEscalates() {
        var ctx = ctxType(BreachType.CLAIM_EXPIRED, Set.of("pr-reviewers"), defaultPrefs());
        assertThat(policy.onBreach(ctx)).isInstanceOf(BreachDecision.EscalateTo.class);
    }

    @Test
    void secondBreach_failsWithTerminalReason() {
        var ctx = ctx(Set.of("pr-leads"), defaultPrefs());
        var decision = policy.onBreach(ctx);
        assertThat(decision).isInstanceOf(BreachDecision.Fail.class);
        assertThat(((BreachDecision.Fail) decision).reason()).isEqualTo("sla-breach");
    }

    @Test
    void secondBreach_withCustomTerminalReason() {
        var prefs = new MapPreferences(
            Map.of("devtown.sla.breach-terminal-reason", "custom-breach-reason"));
        var ctx = ctx(Set.of("pr-leads"), prefs);
        var decision = (BreachDecision.Fail) policy.onBreach(ctx);
        assertThat(decision.reason()).isEqualTo("custom-breach-reason");
    }

    @Test
    void customEscalationGroup_usedForBothTierDetectionAndEscalation() {
        var prefs = new MapPreferences(Map.of("devtown.sla.escalation-group", "senior-leads"));
        var ctx1 = ctx(Set.of("pr-reviewers"), prefs);
        assertThat(policy.onBreach(ctx1)).isInstanceOf(BreachDecision.EscalateTo.class);
        assertThat(((BreachDecision.EscalateTo) policy.onBreach(ctx1)).groups())
            .containsExactly("senior-leads");

        var ctx2 = ctx(Set.of("senior-leads"), prefs);
        assertThat(policy.onBreach(ctx2)).isInstanceOf(BreachDecision.Fail.class);
    }

    @Test
    void customEscalationHours_appliedToDeadline() {
        var prefs = new MapPreferences(Map.of("devtown.sla.escalation-hours", "4"));
        var ctx = ctx(Set.of("pr-reviewers"), prefs);
        var decision = (BreachDecision.EscalateTo) policy.onBreach(ctx);
        assertThat(decision.deadline()).isEqualTo(Duration.ofHours(4));
    }

    @Test
    void blankEscalationGroup_returnsSafeFail() {
        var prefs = new MapPreferences(Map.of("devtown.sla.escalation-group", ""));
        var ctx = ctx(Set.of("pr-reviewers"), prefs);
        var decision = (BreachDecision.Fail) policy.onBreach(ctx);
        assertThat(decision.reason()).isEqualTo("escalation-group-not-configured");
    }

    private static Preferences defaultPrefs() {
        return new MapPreferences(Map.of());
    }

    private static SlaBreachContext ctx(Set<String> groups, Preferences prefs) {
        return ctxType(BreachType.COMPLETION_EXPIRED, groups, prefs);
    }

    private static SlaBreachContext ctxType(BreachType type, Set<String> groups, Preferences prefs) {
        var task = new BreachedTask(UUID.randomUUID(), "case:x/pi:y", "PR review", groups);
        return new SlaBreachContext(type, task, Path.root(), prefs);
    }
}
