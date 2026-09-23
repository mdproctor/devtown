package io.casehub.devtown.app.mcp;

import io.casehub.api.engine.CaseHubRuntime;
import io.casehub.devtown.app.MergeQueueService;
import io.casehub.devtown.app.PrReviewCaseHub;
import io.casehub.devtown.app.governance.GovernanceQueryService;
import io.casehub.devtown.app.ledger.IncidentFeedbackService;
import io.casehub.devtown.domain.IncidentFeedback;
import io.casehub.devtown.domain.IncidentSeverity;
import io.casehub.devtown.domain.queue.PriorityLane;
import io.casehub.devtown.queue.QueuedPr;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.api.mcp.McpDomain;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Name;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@McpDomain(value = "devtown", app = "devtown")
@GraphQLApi
@ApplicationScoped
public class GovernanceMutationResolver {

    @Inject CaseHubRuntime caseHubRuntime;
    @Inject PrReviewCaseTracker tracker;
    @Inject PrReviewCaseHub caseHub;
    @Inject CurrentPrincipal principal;
    @Inject IncidentFeedbackService incidentFeedbackService;
    @Inject MergeQueueService mergeQueueService;
    @Inject io.casehub.devtown.review.PrReviewApplicationService reviewService;

    @org.eclipse.microprofile.config.inject.ConfigProperty(
            name = "devtown.policy.human-approval-threshold", defaultValue = "500")
    int humanApprovalThreshold;

    @org.eclipse.microprofile.config.inject.ConfigProperty(
            name = "devtown.policy.security-review-required", defaultValue = "true")
    boolean securityReviewRequired;

    @org.eclipse.microprofile.config.inject.ConfigProperty(
            name = "devtown.policy.require-senior-approval", defaultValue = "false")
    boolean requireSeniorApproval;

    @Mutation
    @Description("Retry a specific capability by signaling the case with a null context value")
    public RetryResult retryReviewer(
            @Name("caseId") @Description("Case UUID") UUID caseId,
            @Name("capability") @Description("Capability to retry") String capability) {
        CaseInfo caseInfo = tracker.getCase(caseId);
        if (caseInfo == null) throw new IllegalArgumentException("Case not found: " + caseId);

        String contextKey = GovernanceQueryService.CAPABILITY_CONTEXT_KEYS.get(capability);
        if (contextKey == null) throw new IllegalArgumentException("Unknown capability: " + capability);
        caseHubRuntime.signal(caseId, contextKey, null);

        return new RetryResult(caseId, capability, "RETRY_SIGNALED");
    }

    @Mutation
    @Description("Cancel current case and start a fresh review with the same PR payload")
    public RerouteResult rerouteReview(
            @Name("caseId") @Description("Case UUID to cancel") UUID caseId) {
        CaseInfo caseInfo = tracker.getCase(caseId);
        if (caseInfo == null) throw new IllegalArgumentException("Case not found: " + caseId);

        caseHubRuntime.cancelCase(caseId);

        var prContext = Map.<String, Object>of(
                "id", String.valueOf(caseInfo.payload().prNumber()),
                "repo", caseInfo.payload().repo(),
                "linesChanged", caseInfo.payload().linesChanged(),
                "baseRef", caseInfo.payload().baseRef(),
                "headSha", caseInfo.payload().headSha(),
                "contributor", caseInfo.payload().contributor(),
                "changedPaths", caseInfo.payload().changedPaths());
        var policy = Map.<String, Object>of(
                "humanApprovalThreshold", humanApprovalThreshold,
                "securityReviewRequired", securityReviewRequired,
                "requireSeniorApproval", requireSeniorApproval);
        var initialContext = new HashMap<String, Object>();
        initialContext.put("pr", prContext);
        initialContext.put("policy", policy);

        UUID newCaseId = caseHub.startCase(initialContext);
        String tenant = principal.tenancyId();
        tracker.register(newCaseId, tenant, caseInfo.payload());

        return new RerouteResult(caseId, newCaseId);
    }

    @Mutation
    @Description("Force-complete a capability check with operator override")
    public ForceCompleteResult forceCompleteCheck(
            @Name("caseId") @Description("Case UUID") UUID caseId,
            @Name("capability") @Description("Capability to force-complete") String capability,
            @Name("outcome") @Description("Outcome (APPROVED/DECLINED)") String outcome,
            @Name("reason") @Description("Override reason") String reason) {
        CaseInfo caseInfo = tracker.getCase(caseId);
        if (caseInfo == null) throw new IllegalArgumentException("Case not found: " + caseId);

        String contextKey = GovernanceQueryService.CAPABILITY_CONTEXT_KEYS.get(capability);
        if (contextKey == null) throw new IllegalArgumentException("Unknown capability: " + capability);

        Map<String, Object> syntheticResult = Map.of(
                "outcome", outcome,
                "operatorOverride", true,
                "reason", reason,
                "timestamp", Instant.now().toString());

        caseHubRuntime.signal(caseId, contextKey, syntheticResult);

        return new ForceCompleteResult(caseId, capability, outcome, "FORCE_COMPLETED");
    }

    @Mutation
    @Description("Add a PR to the merge queue with priority and trust score")
    public EnqueueResult enqueuePr(
            @Name("repo") @Description("Repository slug (e.g. casehubio/devtown)") String repo,
            @Name("prNumber") @Description("PR number") int prNumber,
            @Name("headSha") @Description("Head commit SHA") String headSha,
            @Name("author") @Description("PR author") String author,
            @Name("trustScore") @Description("Author trust score [0.0, 1.0]") double trustScore,
            @Name("priority") @Description("Priority lane: NORMAL, HIGH, or CRITICAL") String priority) {
        if (repo == null || repo.isBlank()) throw new IllegalArgumentException("repo is required");
        if (headSha == null || headSha.isBlank()) throw new IllegalArgumentException("head_sha is required");
        if (author == null || author.isBlank()) throw new IllegalArgumentException("author is required");
        if (trustScore < 0 || trustScore > 1) throw new IllegalArgumentException("trustScore must be between 0.0 and 1.0");

        PriorityLane lane = PriorityLane.NORMAL;
        if (priority != null && !priority.isBlank()) {
            lane = PriorityLane.valueOf(priority.toUpperCase());
        }

        QueuedPr pr = new QueuedPr(prNumber, repo, headSha, author, trustScore, lane, Instant.now(), Set.of());
        boolean inserted = mergeQueueService.enqueue(pr);
        return new EnqueueResult(prNumber, lane.name(), inserted ? "ENQUEUED" : "ALREADY_QUEUED");
    }

    @Mutation
    @Description("Remove a PR from the merge queue")
    public DequeueResult dequeuePr(
            @Name("repo") @Description("Repository slug (e.g. casehubio/devtown)") String repo,
            @Name("prNumber") @Description("PR number") int prNumber) {
        if (repo == null || repo.isBlank()) throw new IllegalArgumentException("repo is required");

        boolean removed = mergeQueueService.dequeue(prNumber, repo);
        return new DequeueResult(prNumber, removed, removed ? "REMOVED" : "NOT_FOUND");
    }

    @Mutation
    @Description("Supersede a PR case — marks the old case as SUPERSEDED, opens a new review case for the replacement PR")
    public SupersedeResult supersedePr(
            @Name("repo") @Description("Repository full name") String repo,
            @Name("oldPrNumber") @Description("PR number being superseded") int oldPrNumber,
            @Name("newPrNumber") @Description("Replacement PR number") int newPrNumber,
            @Name("headSha") @Description("Head SHA of the replacement PR") String headSha,
            @Name("baseRef") @Description("Base branch") String baseRef,
            @Name("linesChanged") @Description("Lines changed in the replacement PR") int linesChanged,
            @Name("contributor") @Description("Author of the replacement PR") String contributor,
            @Name("changedPaths") @Description("Comma-separated list of changed file paths") String changedPaths) {
        var replacement = new io.casehub.devtown.review.PrPayload(
                repo, newPrNumber, headSha, baseRef, linesChanged, contributor,
                0L, java.util.Arrays.asList(changedPaths.split(",")));

        var result = reviewService.supersedePr(repo, oldPrNumber, replacement);
        if (!result.succeeded()) {
            String status = result.supersededCaseId() != null ? "already-terminal" : "no-active-case";
            return new SupersedeResult(result.supersededCaseId(), null, status);
        }
        return new SupersedeResult(result.supersededCaseId(), result.replacementCaseId(), "superseded");
    }

    @Mutation
    @Description("Report a production incident against a merged PR — writes FLAGGED attestation against the reviewer's trust score")
    public IncidentReport reportIncident(
            @Name("repository") @Description("GitHub repo slug") String repository,
            @Name("prNumber") @Description("PR number") int prNumber,
            @Name("incidentId") @Description("External incident tracker ID") String incidentId,
            @Name("severity") @Description("LOW, MEDIUM, HIGH, or CRITICAL") String severity,
            @Name("description") @Description("What went wrong") String description,
            @Name("reviewCapability") @Description("Which capability missed the issue") String reviewCapability,
            @Name("caseId") @Description("Optional — disambiguate when multiple cases exist") String caseId) {
        IncidentSeverity sev          = IncidentSeverity.valueOf(severity.toUpperCase());
        UUID             parsedCaseId = caseId != null ? UUID.fromString(caseId) : null;
        IncidentFeedback feedback = new IncidentFeedback(
                repository, prNumber, incidentId, sev, description, reviewCapability, parsedCaseId);
        var result = incidentFeedbackService.recordFeedback(feedback);
        return new IncidentReport(
                result.caseId(),
                result.attestationsWritten(),
                result.flaggedAgents().stream()
                      .map(fa -> new FlaggedAgentEntry(fa.agentId(), fa.capabilityTag(), fa.attestationId()))
                      .toList());
    }

    public record RetryResult(UUID caseId, String capability, String status) {}
    public record RerouteResult(UUID oldCaseId, UUID newCaseId) {}
    public record ForceCompleteResult(UUID caseId, String capability, String outcome, String status) {}
    public record EnqueueResult(int prNumber, String lane, String status) {}
    public record DequeueResult(int prNumber, boolean removed, String status) {}
    public record SupersedeResult(UUID supersededCaseId, UUID replacementCaseId, String status) {}

    public record IncidentReport(UUID caseId, int attestationsWritten,
                                 java.util.List<FlaggedAgentEntry> flaggedAgents) {}

    public record FlaggedAgentEntry(String agentId, String capabilityTag, UUID attestationId) {}

}
