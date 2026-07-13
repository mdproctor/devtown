/*
 * Copyright 2026-Present The Case Hub Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.casehub.devtown.review;

import io.casehub.api.model.Binding;
import io.casehub.worker.api.Capability;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.ContextChangeTrigger;
import io.casehub.api.model.Goal;
import io.casehub.api.model.GoalExpression;
import io.casehub.api.model.GoalKind;
import io.casehub.api.model.HumanTaskTarget;
import io.casehub.api.model.OutcomeAction;
import io.casehub.api.model.OutcomePolicy;
import io.casehub.api.context.CaseContext;
import io.casehub.api.model.evaluator.LambdaExpressionEvaluator;
import io.casehub.devtown.domain.AgentQualification;
import io.casehub.devtown.domain.FailurePolicy;
import io.casehub.devtown.domain.ReviewDomain;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

public final class PrReviewCaseDefinition {

    static final List<String> CAPABILITY_CONTEXT_KEYS = List.of(
        "securityReview", "architectureReview", "styleCheck",
        "testCoverage", "performanceAnalysis");

    private static final OutcomePolicy REROUTE_POLICY =
            new OutcomePolicy(OutcomeAction.REROUTE, OutcomeAction.REROUTE, OutcomeAction.REROUTE, 2);

    private static final String DEEP_MERGE = "DEEP_MERGE";

    private PrReviewCaseDefinition() {}

    public static CaseDefinition build(int humanApprovalThreshold) {
        var codeAnalysisCap   = cap(ReviewDomain.CODE_ANALYSIS, "{ pr: .pr }", "{ codeAnalysis: . }");
        var securityReviewCap = cap(ReviewDomain.SECURITY_REVIEW, "{ pr: .pr, codeAnalysis: .codeAnalysis }", "{ securityReview: { outcome: . } }");
        var archReviewCap     = cap(ReviewDomain.ARCHITECTURE_REVIEW, "{ pr: .pr, codeAnalysis: .codeAnalysis }", "{ architectureReview: { outcome: . } }");
        var styleReviewCap    = cap(ReviewDomain.STYLE_REVIEW, "{ pr: .pr }", "{ styleCheck: { outcome: . } }");
        var testCoverageCap   = cap(ReviewDomain.TEST_COVERAGE, "{ pr: .pr }", "{ testCoverage: { outcome: . } }");
        var perfAnalysisCap   = cap(ReviewDomain.PERFORMANCE_ANALYSIS, "{ pr: .pr }", "{ performanceAnalysis: { outcome: . } }");
        var ciRunnerCap       = cap(AgentQualification.CI_RUNNER, "{ pr: .pr }", "{ ci: { status: . } }");
        var mergeExecutorCap  = cap(AgentQualification.MERGE_EXECUTOR, "{ pr: .pr }", ".");
        var mergeQueueEnqueueCap = cap("merge-queue-enqueue", "{ pr: .pr, codeAnalysis: .codeAnalysis }", "{ enqueueResult: . }");

        // ── Goals ──

        var prApproved = Goal.builder()
            .name("pr-approved").kind(GoalKind.SUCCESS)
            .condition(ctx ->
                (Boolean.FALSE.equals(ctx.getPath("codeAnalysis.securitySensitive")) ||
                    "APPROVED".equals(ctx.getPath("securityReview.outcome"))) &&
                (Boolean.FALSE.equals(ctx.getPath("codeAnalysis.architectureCrossing")) ||
                    "APPROVED".equals(ctx.getPath("architectureReview.outcome"))) &&
                "APPROVED".equals(ctx.getPath("styleCheck.outcome")) &&
                "APPROVED".equals(ctx.getPath("testCoverage.outcome")) &&
                "APPROVED".equals(ctx.getPath("performanceAnalysis.outcome")))
            .build();

        var securityVerified = Goal.builder()
            .name("security-verified").kind(GoalKind.SUCCESS)
            .condition(ctx ->
                Boolean.FALSE.equals(ctx.getPath("codeAnalysis.securitySensitive")) ||
                "APPROVED".equals(ctx.getPath("securityReview.outcome")))
            .build();

        var ciPassing = Goal.builder()
            .name("ci-passing").kind(GoalKind.SUCCESS)
            .condition(ctx -> "passing".equals(ctx.getPath("ci.status")))
            .build();

        var reviewBlocked = Goal.builder()
            .name("review-blocked").kind(GoalKind.FAILURE)
            .condition(ctx ->
                Stream.concat(CAPABILITY_CONTEXT_KEYS.stream(), Stream.of("humanApproval"))
                    .anyMatch(key -> "BLOCKED".equals(ctx.getPathAsString(key + ".outcome"))))
            .build();

        var reviewRejected = Goal.builder()
            .name("review-rejected").kind(GoalKind.FAILURE)
            .condition(ctx ->
                Stream.concat(CAPABILITY_CONTEXT_KEYS.stream(), Stream.of("humanApproval"))
                    .anyMatch(key -> "REJECTED".equals(ctx.getPathAsString(key + ".outcome"))))
            .build();

        var reviewAbandoned = Goal.builder()
            .name("review-abandoned").kind(GoalKind.FAILURE)
            .condition(ctx -> {
                String status = ctx.getPathAsString("pr.status");
                return "closed".equals(status) || "superseded".equals(status);
            })
            .build();

        var mergeCompleted = Goal.builder()
            .name("merge-completed").kind(GoalKind.SUCCESS)
            .condition(ctx -> "merged".equals(ctx.getPath("pr.status")) || ctx.get("merge_sha") != null)
            .build();

        var trigger = new ContextChangeTrigger((io.casehub.api.model.evaluator.ExpressionEvaluator) null);

        CaseDefinition def = CaseDefinition.builder()
            .namespace("devtown").name("pr-review").version("1.0.0")
            .completion(
                GoalExpression.allOf(prApproved, securityVerified, ciPassing, mergeCompleted),
                GoalExpression.anyOf(reviewBlocked, reviewRejected, reviewAbandoned))
            .build();

        def.getCapabilities().addAll(List.of(
            codeAnalysisCap, securityReviewCap, archReviewCap,
            styleReviewCap, testCoverageCap, perfAnalysisCap,
            ciRunnerCap, mergeExecutorCap, mergeQueueEnqueueCap));

        def.getGoals().addAll(List.of(prApproved, securityVerified, ciPassing,
            reviewBlocked, reviewRejected, reviewAbandoned, mergeCompleted));

        // ── Tier 1-2: Capability dispatch with OutcomePolicy reroute loop ──

        def.getBindings().add(capBinding("initial-analysis", trigger,
            ctx -> ctx.get("pr") != null && ctx.get("codeAnalysis") == null,
            codeAnalysisCap));

        def.getBindings().add(capBinding("run-ci", trigger,
            ctx -> ctx.get("pr") != null && ctx.get("ci") == null,
            ciRunnerCap));

        def.getBindings().add(capBinding("security-review", trigger,
            ctx -> Boolean.TRUE.equals(ctx.getPath("codeAnalysis.complete")) &&
                   Boolean.TRUE.equals(ctx.getPath("codeAnalysis.securitySensitive")) &&
                   ctx.get("securityReview") == null,
            securityReviewCap));

        def.getBindings().add(capBinding("architecture-review", trigger,
            ctx -> Boolean.TRUE.equals(ctx.getPath("codeAnalysis.complete")) &&
                   Boolean.TRUE.equals(ctx.getPath("codeAnalysis.architectureCrossing")) &&
                   ctx.get("architectureReview") == null,
            archReviewCap));

        def.getBindings().add(capBinding("style-check", trigger,
            ctx -> Boolean.TRUE.equals(ctx.getPath("codeAnalysis.complete")) &&
                   ctx.get("styleCheck") == null,
            styleReviewCap));

        def.getBindings().add(capBinding("test-coverage", trigger,
            ctx -> Boolean.TRUE.equals(ctx.getPath("codeAnalysis.complete")) &&
                   ctx.get("testCoverage") == null,
            testCoverageCap));

        def.getBindings().add(capBinding("performance-analysis", trigger,
            ctx -> Boolean.TRUE.equals(ctx.getPath("codeAnalysis.complete")) &&
                   ctx.get("performanceAnalysis") == null,
            perfAnalysisCap));

        // ── Human gate ──

        def.getBindings().add(Binding.builder().name("human-approval").on(trigger)
            .when(new LambdaExpressionEvaluator(ctx -> {
                Object linesChanged = ctx.getPath("pr.linesChanged");
                return linesChanged instanceof Number n &&
                    n.intValue() > humanApprovalThreshold &&
                    ctx.get("humanApproval") == null;
            }))
            .conflictResolverStrategy(DEEP_MERGE)
            .humanTask(HumanTaskTarget.inline()
                .title("PR approval required")
                .candidateGroups(Set.of("pr-reviewers"))
                .expiresIn(Duration.ofHours(24))
                .outputMapping("{ humanApproval: . }")
                .build())
            .build());

        // ── Tier 3: Scope reduction ──

        FailurePolicy secPolicy = PrReviewCaseDescriptor.FAILURE_POLICIES.get(ReviewDomain.SECURITY_REVIEW);
        def.getBindings().add(Binding.builder().name("security-review-reduced-scope").on(trigger)
            .when(new LambdaExpressionEvaluator(ctx ->
                "REROUTES_EXHAUSTED".equals(ctx.getPathAsString("securityReview.status")) &&
                ctx.getPath("securityReview.reducedScope") == null))
            .contextWrite(Map.of("securityReview", Map.of("status", "PENDING", "reducedScope", true, "excludedAgents", List.of())))
            .capability(securityReviewCap)
            .inputProjectionOverride(secPolicy.reducedInputSchema())
            .conflictResolverStrategy(DEEP_MERGE)
            .outcomePolicy(REROUTE_POLICY)
            .build());

        FailurePolicy archPolicy = PrReviewCaseDescriptor.FAILURE_POLICIES.get(ReviewDomain.ARCHITECTURE_REVIEW);
        def.getBindings().add(Binding.builder().name("architecture-review-reduced-scope").on(trigger)
            .when(new LambdaExpressionEvaluator(ctx ->
                "REROUTES_EXHAUSTED".equals(ctx.getPathAsString("architectureReview.status")) &&
                ctx.getPath("architectureReview.reducedScope") == null))
            .contextWrite(Map.of("architectureReview", Map.of("status", "PENDING", "reducedScope", true, "excludedAgents", List.of())))
            .capability(archReviewCap)
            .inputProjectionOverride(archPolicy.reducedInputSchema())
            .conflictResolverStrategy(DEEP_MERGE)
            .outcomePolicy(REROUTE_POLICY)
            .build());

        // ── Tier 4: Human escalation (one per capability) ──

        addEscalation(def, trigger, "security-review-human-escalation",
            "Security review escalation — all automated reviewers exhausted",
            "securityReview", true, "security-reviewers",
            PrReviewCaseDescriptor.FAILURE_POLICIES.get(ReviewDomain.SECURITY_REVIEW));

        addEscalation(def, trigger, "architecture-review-human-escalation",
            "Architecture review escalation — all automated reviewers exhausted",
            "architectureReview", true, "architecture-reviewers",
            PrReviewCaseDescriptor.FAILURE_POLICIES.get(ReviewDomain.ARCHITECTURE_REVIEW));

        addEscalation(def, trigger, "style-check-human-escalation",
            "Style review escalation",
            "styleCheck", false, "pr-reviewers",
            PrReviewCaseDescriptor.FAILURE_POLICIES.get(ReviewDomain.STYLE_REVIEW));

        addEscalation(def, trigger, "test-coverage-human-escalation",
            "Test coverage escalation",
            "testCoverage", false, "pr-reviewers",
            PrReviewCaseDescriptor.FAILURE_POLICIES.get(ReviewDomain.TEST_COVERAGE));

        addEscalation(def, trigger, "performance-analysis-human-escalation",
            "Performance analysis escalation",
            "performanceAnalysis", false, "pr-reviewers",
            PrReviewCaseDescriptor.FAILURE_POLICIES.get(ReviewDomain.PERFORMANCE_ANALYSIS));

        addEscalation(def, trigger, "code-analysis-human-escalation",
            "Code analysis escalation",
            "codeAnalysis", false, "pr-reviewers",
            PrReviewCaseDescriptor.FAILURE_POLICIES.get(ReviewDomain.CODE_ANALYSIS));

        addEscalation(def, trigger, "ci-runner-human-escalation",
            "CI escalation — automated CI failed",
            "ci", false, "pr-reviewers",
            PrReviewCaseDescriptor.FAILURE_POLICIES.get(AgentQualification.CI_RUNNER));

        // ── Merge bindings — mutually exclusive based on merge queue enablement ──

        def.getBindings().add(Binding.builder().name("enqueue-for-merge").on(trigger)
            .when(new LambdaExpressionEvaluator(ctx -> {
                Object linesChanged = ctx.getPath("pr.linesChanged");
                boolean humanOk =
                    (linesChanged instanceof Number l && l.intValue() <= humanApprovalThreshold) ||
                    "APPROVED".equals(ctx.getPath("humanApproval.outcome"));
                return ctx.get("merge_sha") == null &&
                    ctx.get("enqueueResult") == null &&
                    !"merged".equals(ctx.getPath("pr.status")) &&
                    (Boolean.FALSE.equals(ctx.getPath("codeAnalysis.securitySensitive")) ||
                        "APPROVED".equals(ctx.getPath("securityReview.outcome"))) &&
                    (Boolean.FALSE.equals(ctx.getPath("codeAnalysis.architectureCrossing")) ||
                        "APPROVED".equals(ctx.getPath("architectureReview.outcome"))) &&
                    "APPROVED".equals(ctx.getPath("styleCheck.outcome")) &&
                    "APPROVED".equals(ctx.getPath("testCoverage.outcome")) &&
                    "APPROVED".equals(ctx.getPath("performanceAnalysis.outcome")) &&
                    humanOk &&
                    "passing".equals(ctx.getPath("ci.status")) &&
                    Boolean.TRUE.equals(ctx.getPath("policy.mergeQueueEnabled"));
            }))
            .conflictResolverStrategy(DEEP_MERGE)
            .outcomePolicy(new OutcomePolicy(OutcomeAction.FAULT, OutcomeAction.FAULT, OutcomeAction.FAULT, 0))
            .capability(mergeQueueEnqueueCap).build());

        def.getBindings().add(Binding.builder().name("merge-direct").on(trigger)
            .when(new LambdaExpressionEvaluator(ctx -> {
                Object linesChanged = ctx.getPath("pr.linesChanged");
                boolean humanOk =
                    (linesChanged instanceof Number l && l.intValue() <= humanApprovalThreshold) ||
                    "APPROVED".equals(ctx.getPath("humanApproval.outcome"));
                return ctx.get("merge_sha") == null &&
                    !"merged".equals(ctx.getPath("pr.status")) &&
                    (Boolean.FALSE.equals(ctx.getPath("codeAnalysis.securitySensitive")) ||
                        "APPROVED".equals(ctx.getPath("securityReview.outcome"))) &&
                    (Boolean.FALSE.equals(ctx.getPath("codeAnalysis.architectureCrossing")) ||
                        "APPROVED".equals(ctx.getPath("architectureReview.outcome"))) &&
                    "APPROVED".equals(ctx.getPath("styleCheck.outcome")) &&
                    "APPROVED".equals(ctx.getPath("testCoverage.outcome")) &&
                    "APPROVED".equals(ctx.getPath("performanceAnalysis.outcome")) &&
                    humanOk &&
                    "passing".equals(ctx.getPath("ci.status")) &&
                    !Boolean.TRUE.equals(ctx.getPath("policy.mergeQueueEnabled"));
            }))
            .conflictResolverStrategy(DEEP_MERGE)
            .outcomePolicy(new OutcomePolicy(OutcomeAction.FAULT, OutcomeAction.FAULT, OutcomeAction.FAULT, 0))
            .capability(mergeExecutorCap).build());

        return def;
    }

    private static Binding capBinding(String name, ContextChangeTrigger trigger,
            Predicate<CaseContext> condition, Capability capability) {
        return Binding.builder().name(name).on(trigger)
            .when(new LambdaExpressionEvaluator(condition))
            .capability(capability)
            .conflictResolverStrategy(DEEP_MERGE)
            .outcomePolicy(REROUTE_POLICY)
            .build();
    }

    private static void addEscalation(CaseDefinition def, ContextChangeTrigger trigger,
            String name, String title, String contextKey, boolean hasScopeReduction,
            String candidateGroup, FailurePolicy policy) {
        def.getBindings().add(Binding.builder().name(name).on(trigger)
            .when(new LambdaExpressionEvaluator(ctx -> {
                String status = ctx.getPathAsString(contextKey + ".status");
                if (!"REROUTES_EXHAUSTED".equals(status)) return false;
                if (hasScopeReduction) {
                    return Boolean.TRUE.equals(ctx.getPath(contextKey + ".reducedScope"));
                }
                return true;
            }))
            .conflictResolverStrategy(DEEP_MERGE)
            .humanTask(HumanTaskTarget.inline()
                .title(title)
                .candidateGroups(Set.of(candidateGroup))
                .expiresIn(policy.humanEscalationSla())
                .outputMapping("{ " + contextKey + ": { outcome: . } }")
                .build())
            .build());
    }

    private static Capability cap(String name, String inputSchema, String outputSchema) {
        return Capability.of(name, inputSchema, outputSchema);
    }
}
