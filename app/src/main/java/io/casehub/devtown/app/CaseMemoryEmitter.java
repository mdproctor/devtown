package io.casehub.devtown.app;

import io.casehub.devtown.domain.memory.DevtownMemoryDomain;
import io.casehub.devtown.domain.memory.DevtownMemoryKeys;
import io.casehub.devtown.domain.memory.ModulePathNormalizer;
import io.casehub.devtown.review.ReviewCompletedEvent;
import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.neocortex.memory.MemoryAttributeKeys;
import io.casehub.neocortex.memory.MemoryInput;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

@ApplicationScoped
public class CaseMemoryEmitter {

    private static final Logger LOG = Logger.getLogger(CaseMemoryEmitter.class);

    @Inject
    Instance<CaseMemoryStore> store;

    void onReviewCompleted(@ObservesAsync ReviewCompletedEvent event) {
        if (!store.isResolvable()) return;

        try {
            store.get().storeAll(buildFacts(event));
        } catch (Exception e) {
            LOG.warnf(e, "Memory emission failed for case=%s capability=%s",
                event.caseId(), event.capability());
        }
    }

    List<MemoryInput> buildFacts(ReviewCompletedEvent event) {
        var facts = new ArrayList<MemoryInput>();

        // Contributor fact
        facts.add(buildContributorFact(event));

        // Reviewer fact
        facts.add(buildReviewerFact(event));

        // Code area facts (one per deduplicated module)
        var modules = ModulePathNormalizer.normalize(event.pr().changedPaths());
        for (var module : modules) {
            facts.add(buildCodeAreaFact(event, module));
        }

        return facts;
    }

    private MemoryInput buildContributorFact(ReviewCompletedEvent event) {
        var pr = event.pr();
        var entityId = DevtownMemoryDomain.CONTRIBUTOR_PREFIX + pr.contributor();

        var attributes = new HashMap<String, String>();
        attributes.put(MemoryAttributeKeys.ACTOR_ID, pr.contributor());
        attributes.put(MemoryAttributeKeys.ACTOR_ROLE, "contributor");
        attributes.put(MemoryAttributeKeys.OUTCOME, event.outcome().name());
        attributes.put(DevtownMemoryKeys.CAPABILITY, event.capability());
        attributes.put(DevtownMemoryKeys.PR_NUMBER, String.valueOf(pr.prNumber()));
        attributes.put(DevtownMemoryKeys.PR_REPO, pr.repo());
        attributes.put(DevtownMemoryKeys.LINES_CHANGED, String.valueOf(pr.linesChanged()));
        attributes.put(DevtownMemoryKeys.ENTITY_TYPE, "contributor");
        if (event.outcomeDetail() != null) {
            attributes.put(DevtownMemoryKeys.OUTCOME_DETAIL, event.outcomeDetail());
        }

        var text = buildContributorText(event);

        return new MemoryInput(
            entityId,
            DevtownMemoryDomain.SOFTWARE_REVIEW,
            event.tenantId(),
            event.caseId().toString(),
            text,
            attributes
        );
    }

    private MemoryInput buildReviewerFact(ReviewCompletedEvent event) {
        var pr = event.pr();
        var entityId = DevtownMemoryDomain.REVIEWER_PREFIX + event.reviewerId();

        var attributes = new HashMap<String, String>();
        attributes.put(MemoryAttributeKeys.ACTOR_ID, event.reviewerId());
        attributes.put(MemoryAttributeKeys.ACTOR_ROLE, "reviewer");
        attributes.put(MemoryAttributeKeys.OUTCOME, event.outcome().name());
        attributes.put(DevtownMemoryKeys.CAPABILITY, event.capability());
        attributes.put(DevtownMemoryKeys.PR_NUMBER, String.valueOf(pr.prNumber()));
        attributes.put(DevtownMemoryKeys.PR_REPO, pr.repo());
        attributes.put(DevtownMemoryKeys.LINES_CHANGED, String.valueOf(pr.linesChanged()));
        attributes.put(DevtownMemoryKeys.ENTITY_TYPE, "reviewer");
        if (event.outcomeDetail() != null) {
            attributes.put(DevtownMemoryKeys.OUTCOME_DETAIL, event.outcomeDetail());
        }

        var text = buildReviewerText(event);

        return new MemoryInput(
            entityId,
            DevtownMemoryDomain.SOFTWARE_REVIEW,
            event.tenantId(),
            event.caseId().toString(),
            text,
            attributes
        );
    }

    private MemoryInput buildCodeAreaFact(ReviewCompletedEvent event, String module) {
        var pr = event.pr();
        var entityId = DevtownMemoryDomain.MODULE_PREFIX + pr.repo() + "/" + module;

        var attributes = new HashMap<String, String>();
        attributes.put(MemoryAttributeKeys.ACTOR_ID, event.reviewerId());
        attributes.put(MemoryAttributeKeys.ACTOR_ROLE, "reviewer");
        attributes.put(MemoryAttributeKeys.OUTCOME, event.outcome().name());
        attributes.put(DevtownMemoryKeys.CAPABILITY, event.capability());
        attributes.put(DevtownMemoryKeys.PR_NUMBER, String.valueOf(pr.prNumber()));
        attributes.put(DevtownMemoryKeys.PR_REPO, pr.repo());
        attributes.put(DevtownMemoryKeys.LINES_CHANGED, String.valueOf(pr.linesChanged()));
        attributes.put(DevtownMemoryKeys.ENTITY_TYPE, "code-area");
        if (event.outcomeDetail() != null) {
            attributes.put(DevtownMemoryKeys.OUTCOME_DETAIL, event.outcomeDetail());
        }

        var text = buildCodeAreaText(event, module);

        return new MemoryInput(
            entityId,
            DevtownMemoryDomain.SOFTWARE_REVIEW,
            event.tenantId(),
            event.caseId().toString(),
            text,
            attributes
        );
    }

    private String buildContributorText(ReviewCompletedEvent event) {
        var pr = event.pr();
        var outcomePhrase = isApproved(event.outcomeDetail()) ? "no issues found" : "found issues requiring attention";

        return String.format(
            "%s review of a %d-line pull request by %s in %s %s.",
            capitalize(event.capability().replace("-", " ")),
            pr.linesChanged(),
            pr.contributor(),
            pr.repo(),
            outcomePhrase
        );
    }

    private String buildReviewerText(ReviewCompletedEvent event) {
        var pr = event.pr();
        var outcomePhrase = isApproved(event.outcomeDetail()) ? "no issues found" : "found issues requiring attention";

        return String.format(
            "%s completed a %s review of pull request #%d in %s. Outcome: %s.",
            event.reviewerId(),
            event.capability().replace("-", " "),
            pr.prNumber(),
            pr.repo(),
            outcomePhrase
        );
    }

    private String buildCodeAreaText(ReviewCompletedEvent event, String module) {
        var pr = event.pr();
        var outcomePhrase = isApproved(event.outcomeDetail()) ? "No issues found" : "Found issues requiring attention";

        return String.format(
            "The %s module in %s received a %s review on pull request #%d. %s.",
            module,
            pr.repo(),
            event.capability().replace("-", " "),
            pr.prNumber(),
            outcomePhrase
        );
    }

    private boolean isApproved(String outcomeDetail) {
        if (outcomeDetail == null) return false;
        var lower = outcomeDetail.toLowerCase();
        return lower.equals("approved") || lower.equals("passed");
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }
}
