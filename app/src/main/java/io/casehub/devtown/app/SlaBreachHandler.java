package io.casehub.devtown.app;

import io.casehub.work.api.BreachDecision;
import io.casehub.work.runtime.event.SlaBreachEvent;
import io.casehub.workadapter.CallerRef;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.Map;
import org.jboss.logging.Logger;

@ApplicationScoped
public class SlaBreachHandler {

    private static final Logger LOG = Logger.getLogger(SlaBreachHandler.class);

    @Inject PrReviewCaseHub caseHub;

    void onBreach(@Observes SlaBreachEvent event) {
        try {
            CallerRef ref = CallerRef.parse(event.context().task().callerRef());
            if (ref == null) return;

            switch (event.decision()) {
                case BreachDecision.Fail fail ->
                    caseHub.signal(ref.caseId(), "humanApproval",
                        Map.of("status", fail.reason()));
                default -> {}
            }
        } catch (Exception e) {
            LOG.errorf(e, "SlaBreachHandler failed for callerRef=%s — case may not be signaled",
                event.context().task().callerRef());
        }
    }
}
