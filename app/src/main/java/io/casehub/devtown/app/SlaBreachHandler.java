package io.casehub.devtown.app;

import io.casehub.devtown.review.OutputMappingKeys;
import io.casehub.engine.common.spi.PlanItemStore;
import io.casehub.work.api.BreachDecision;
import io.casehub.work.runtime.event.SlaBreachEvent;
import io.casehub.workadapter.CallerRef;
import io.casehub.workadapter.PlanItemCallerRef;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;

@ApplicationScoped
public class SlaBreachHandler {

    private static final Logger LOG = Logger.getLogger(SlaBreachHandler.class);

    @Inject PrReviewCaseHub caseHub;
    @Inject PlanItemStore planItemStore;

    void onBreach(@Observes SlaBreachEvent event) {
        try {
            CallerRef ref = CallerRef.parse(event.context().task().callerRef());
            if (ref == null) return;

            switch (event.decision()) {
                case BreachDecision.Fail fail -> {
                    String contextKey = resolveContextKey(ref, event.tenancyId());
                    if (contextKey == null) {
                        LOG.warnf("Cannot resolve context key for callerRef=%s — signaling skipped",
                                event.context().task().callerRef());
                        return;
                    }
                    caseHub.signal(ref.caseId(), contextKey, Map.of("outcome", "BLOCKED"));
                }
                default -> {}
            }
        } catch (Exception e) {
            LOG.errorf(e, "SlaBreachHandler failed for callerRef=%s — case may not be signaled",
                event.context().task().callerRef());
        }
    }

    private String resolveContextKey(CallerRef ref, String tenancyId) {
        if (!(ref instanceof PlanItemCallerRef pi)) {return null;}

        var records = planItemStore.findDelegated(pi.caseId(), tenancyId);
        for (var record : records) {
            if (pi.planItemId().equals(record.planItemId()) && record.outputMappingExpression() != null) {
                return OutputMappingKeys.topLevelKey(record.outputMappingExpression());
            }
        }
        return null;
    }
}
