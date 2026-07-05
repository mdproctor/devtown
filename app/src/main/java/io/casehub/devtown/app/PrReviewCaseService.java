package io.casehub.devtown.app;

import io.casehub.devtown.app.mcp.PrReviewCaseTracker;
import io.casehub.devtown.domain.CiStatusClient;
import io.casehub.devtown.domain.CombinedCiStatus;
import io.casehub.devtown.domain.preferences.PrReviewPreferenceKeys;
import io.casehub.devtown.domain.queue.MergeQueuePreferenceKeys;
import io.casehub.devtown.review.LifecycleResult;
import io.casehub.devtown.review.PrPayload;
import io.casehub.devtown.review.PrReviewApplicationService;
import io.casehub.devtown.review.PrReviewOutcome;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.api.preferences.PreferenceProvider;
import io.casehub.platform.api.preferences.Preferences;
import io.casehub.platform.api.preferences.SettingsScope;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
@Alternative
@Priority(2)
public class PrReviewCaseService implements PrReviewApplicationService {

    private static final String VERDICT_CASE_OPENED = "case-opened";

    @Inject
    PrReviewCaseHub caseHub;

    @Inject
    CaseMemoryRecaller memoryRecaller;

    @Inject
    PrReviewCaseTracker caseTracker;

    @Inject
    CurrentPrincipal principal;

    @Inject
    CiStatusClient ciStatusClient;

    @Inject
    PreferenceProvider preferenceProvider;

    @ConfigProperty(name = "devtown.ci.mode", defaultValue = "external")
    String ciMode;

    @Override
    public PrReviewOutcome startReview(PrPayload pr) {
        var existing = caseTracker.findActiveCaseByPr(pr.repo(), pr.prNumber());
        if (existing.isPresent()) {
            revisePr(pr.repo(), pr.prNumber(), pr.headSha(), pr.linesChanged());
            return new PrReviewOutcome(VERDICT_CASE_OPENED, List.of());
        }

        var memoryContext = memoryRecaller.recall(pr);

        Preferences prefs = preferenceProvider.resolve(
            SettingsScope.of("casehubio", "devtown", "pr-review"));
        Preferences mergeQueuePrefs = preferenceProvider.resolve(
            SettingsScope.of("casehubio", "devtown", "merge-queue"));

        var policy = Map.<String, Object>of(
            "humanApprovalThreshold", prefs.getOrDefault(PrReviewPreferenceKeys.HUMAN_APPROVAL_THRESHOLD).value(),
            "securityReviewRequired", prefs.getOrDefault(PrReviewPreferenceKeys.SECURITY_REVIEW_REQUIRED).value(),
            "requireSeniorApproval", prefs.getOrDefault(PrReviewPreferenceKeys.REQUIRE_SENIOR_APPROVAL).value(),
            "mergeQueueEnabled", mergeQueuePrefs.getOrDefault(MergeQueuePreferenceKeys.ENABLED).value()
        );
        var prContext = new LinkedHashMap<String, Object>(Map.of(
            "id", String.valueOf(pr.prNumber()),
            "repo", pr.repo(),
            "linesChanged", pr.linesChanged(),
            "baseRef", pr.baseRef(),
            "headSha", pr.headSha(),
            "contributor", pr.contributor(),
            "changedPaths", pr.changedPaths()
        ));
        var initialContext = new HashMap<String, Object>();
        initialContext.put("pr", prContext);
        initialContext.put("policy", policy);
        initialContext.put("memory", memoryContext.toContextMap());
        if ("external".equals(ciMode)) {
            initialContext.put("ci", Map.of("status", "pending"));
        }

        UUID caseId = caseHub.startCase(initialContext).toCompletableFuture().join();
        caseTracker.register(caseId, principal.tenancyId(), pr);
        return new PrReviewOutcome(VERDICT_CASE_OPENED, List.of());
    }

    @Override
    public LifecycleResult revisePr(String repo, int prNumber, String newHeadSha, int linesChanged) {
        var active = caseTracker.findActiveCaseByPr(repo, prNumber);
        if (active.isEmpty()) return LifecycleResult.NO_ACTIVE_CASE;

        UUID caseId = active.get().caseId();

        // Signal ordering contract: metadata BEFORE invalidation.
        // initial-analysis binding fires on .codeAnalysis == null and reads pr.headSha as input.
        caseHub.signal(caseId, "pr.headSha", newHeadSha);
        caseHub.signal(caseId, "pr.linesChanged", linesChanged);

        // Invalidate stale analysis — triggers binding re-evaluation.
        // Human approvals are preserved (explicit policy — see spec §14).
        caseHub.signal(caseId, "codeAnalysis", null);
        caseHub.signal(caseId, "securityReview", null);
        caseHub.signal(caseId, "architectureReview", null);
        caseHub.signal(caseId, "styleCheck", null);
        caseHub.signal(caseId, "testCoverage", null);
        caseHub.signal(caseId, "performanceAnalysis", null);

        if ("external".equals(ciMode)) {
            caseHub.signal(caseId, "ci", Map.of("status", "pending"));
        } else {
            caseHub.signal(caseId, "ci", null);
        }
        caseTracker.updateHeadSha(caseId, newHeadSha);

        return LifecycleResult.UPDATED;
    }

    @Override
    public LifecycleResult closePr(String repo, int prNumber, boolean merged) {
        var active = caseTracker.findActiveCaseByPr(repo, prNumber);
        if (active.isEmpty()) return LifecycleResult.NO_ACTIVE_CASE;

        UUID caseId = active.get().caseId();
        caseHub.signal(caseId, "pr.status", merged ? "merged" : "closed");

        return LifecycleResult.UPDATED;
    }

    @Override
    public LifecycleResult signalCiStatus(String repo, int prNumber, String headSha, long suiteId, String conclusion) {
        var active = caseTracker.findActiveCaseByPr(repo, prNumber);
        if (active.isEmpty()) return LifecycleResult.NO_ACTIVE_CASE;

        UUID caseId = active.get().caseId();

        String currentSha = caseHub.query(caseId, "pr.headSha", String.class).toCompletableFuture().join();
        if (!headSha.equals(currentSha)) return LifecycleResult.STALE_EVENT;

        caseHub.signal(caseId, "ci.suites." + suiteId, Map.of(
            "conclusion", conclusion,
            "completedAt", java.time.Instant.now().toString()
        ));

        String[] parts = repo.split("/");
        switch (ciStatusClient.getCombinedStatus(parts[0], parts[1], headSha)) {
            case CombinedCiStatus.Passing() -> caseHub.signal(caseId, "ci.status", "passing");
            case CombinedCiStatus.Failing(var summary) -> caseHub.signal(caseId, "ci.status", "failing");
            case CombinedCiStatus.Pending(var completed, var total) -> { }
            case CombinedCiStatus.Unavailable(var reason) -> { }
        }

        return LifecycleResult.UPDATED;
    }

    @Override
    public LifecycleResult signalCheckRun(String repo, int prNumber, String headSha, String checkName, String conclusion, Instant completedAt) {
        var active = caseTracker.findActiveCaseByPr(repo, prNumber);
        if (active.isEmpty()) return LifecycleResult.NO_ACTIVE_CASE;

        UUID caseId = active.get().caseId();

        String currentSha = caseHub.query(caseId, "pr.headSha", String.class).toCompletableFuture().join();
        if (!headSha.equals(currentSha)) return LifecycleResult.STALE_EVENT;

        caseHub.signal(caseId, "ci.checks." + checkName, Map.of(
            "conclusion", conclusion,
            "completedAt", completedAt.toString()
        ));

        return LifecycleResult.UPDATED;
    }
}
