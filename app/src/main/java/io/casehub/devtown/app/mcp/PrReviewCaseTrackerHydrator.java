package io.casehub.devtown.app.mcp;

import io.casehub.api.model.CaseStatus;
import io.casehub.devtown.review.PrPayload;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class PrReviewCaseTrackerHydrator {

    private static final Logger LOG = Logger.getLogger(PrReviewCaseTrackerHydrator.class);

    private static final List<CaseStatus> NON_TERMINAL_STATUSES = List.of(
        CaseStatus.STARTING, CaseStatus.RUNNING, CaseStatus.WAITING, CaseStatus.SUSPENDED
    );

    @Inject
    CaseInstanceRepository caseInstanceRepository;

    @Inject
    PrReviewCaseTracker tracker;

    @Inject
    CurrentPrincipal principal;

    void onStartup(@Observes StartupEvent event) {
        hydrate();
    }

    void hydrate() {
        String tenancyId = principal.tenancyId();
        int count = 0;

        for (CaseStatus status : NON_TERMINAL_STATUSES) {
            for (CaseInstance instance : caseInstanceRepository.findByStatus(status, tenancyId)) {
                PrPayload payload = extractPrPayload(instance);
                if (payload == null) continue;

                tracker.register(instance.getUuid(), tenancyId, payload);
                if (status != CaseStatus.RUNNING) {
                    tracker.updateStatus(instance.getUuid(), status.name(), Instant.now());
                }
                count++;
            }
        }

        if (count > 0) {
            LOG.infof("Hydrated %d active PR review cases from durable state", count);
        }
    }

    @SuppressWarnings("unchecked")
    PrPayload extractPrPayload(CaseInstance instance) {
        var ctx = instance.getCaseContext();
        if (ctx == null) return null;

        Object prObj = ctx.get("pr");
        if (!(prObj instanceof Map<?, ?> pr)) return null;

        try {
            return new PrPayload(
                (String) pr.get("repo"),
                toInt(pr.get("id")),
                (String) pr.get("headSha"),
                (String) pr.get("baseRef"),
                toInt(pr.get("linesChanged")),
                (String) pr.get("contributor"),
                pr.containsKey("changedPaths") ? (List<String>) pr.get("changedPaths") : List.of()
            );
        } catch (Exception e) {
            LOG.warnf("Failed to extract PR payload from case=%s: %s", instance.getUuid(), e.getMessage());
            return null;
        }
    }

    private static int toInt(Object value) {
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) return Integer.parseInt(s);
        return 0;
    }
}
