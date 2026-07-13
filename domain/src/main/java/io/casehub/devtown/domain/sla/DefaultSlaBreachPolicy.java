package io.casehub.devtown.domain.sla;

import io.casehub.work.api.BreachDecision;
import io.casehub.work.api.SlaBreachContext;
import io.casehub.work.api.spi.SlaBreachPolicy;
import java.time.Duration;

public class DefaultSlaBreachPolicy implements SlaBreachPolicy {
    @Override
    public String id() {
        return "devtown-default";
    }


    @Override
    public BreachDecision onBreach(SlaBreachContext ctx) {
        var p = ctx.preferences();
        var escalationGroup = p.getOrDefault(SlaPreferenceKeys.ESCALATION_GROUP).value();
        var terminalReason  = p.getOrDefault(SlaPreferenceKeys.BREACH_TERMINAL_REASON).value();
        int escalationHours = p.getOrDefault(SlaPreferenceKeys.ESCALATION_HOURS).value();

        if (escalationGroup.isBlank()) {
            return new BreachDecision.Fail("escalation-group-not-configured");
        }
        if (ctx.task().candidateGroups().contains(escalationGroup)) {
            return new BreachDecision.Fail(terminalReason);
        }
        return BreachDecision.EscalateTo.to(escalationGroup)
                .withDeadline(Duration.ofHours(escalationHours));
    }
}
