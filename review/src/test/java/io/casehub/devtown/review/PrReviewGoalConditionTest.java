package io.casehub.devtown.review;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.Goal;
import io.casehub.api.model.evaluator.LambdaExpressionEvaluator;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PrReviewGoalConditionTest {

    private static final int THRESHOLD = 500;
    private CaseDefinition def;

    @BeforeEach
    void setup() {
        def = PrReviewCaseDefinition.build(THRESHOLD);
    }

    private LambdaExpressionEvaluator goalCondition(String goalName) {
        return def.getGoals().stream()
            .filter(g -> g.getName().equals(goalName))
            .findFirst()
            .map(g -> (LambdaExpressionEvaluator) g.getCondition())
            .orElseThrow(() -> new AssertionError("Goal not found: " + goalName));
    }

    private MapCaseContext ctx(Map<String, Object> data) {
        return new MapCaseContext(data);
    }

    private Map<String, Object> analysis(boolean securitySensitive, boolean architectureCrossing) {
        return Map.of("complete", true,
            "securitySensitive", securitySensitive,
            "architectureCrossing", architectureCrossing);
    }

    @Nested class PrApproved {
        @Test void satisfied_whenAllChecksApproved_noSecurity_noArch() {
            assertThat(goalCondition("pr-approved").test(ctx(Map.of(
                "codeAnalysis", analysis(false, false),
                "styleCheck", Map.of("outcome", "APPROVED"),
                "testCoverage", Map.of("outcome", "APPROVED"),
                "performanceAnalysis", Map.of("outcome", "APPROVED"))))).isTrue();
        }
        @Test void satisfied_whenSecuritySensitiveAndApproved() {
            assertThat(goalCondition("pr-approved").test(ctx(Map.of(
                "codeAnalysis", analysis(true, false),
                "securityReview", Map.of("outcome", "APPROVED"),
                "styleCheck", Map.of("outcome", "APPROVED"),
                "testCoverage", Map.of("outcome", "APPROVED"),
                "performanceAnalysis", Map.of("outcome", "APPROVED"))))).isTrue();
        }
        @Test void notSatisfied_whenSecuritySensitiveAndNotApproved() {
            assertThat(goalCondition("pr-approved").test(ctx(Map.of(
                "codeAnalysis", analysis(true, false),
                "styleCheck", Map.of("outcome", "APPROVED"),
                "testCoverage", Map.of("outcome", "APPROVED"),
                "performanceAnalysis", Map.of("outcome", "APPROVED"))))).isFalse();
        }
        @Test void satisfied_whenArchCrossingAndReviewApproved() {
            assertThat(goalCondition("pr-approved").test(ctx(Map.of(
                "codeAnalysis", analysis(false, true),
                "architectureReview", Map.of("outcome", "APPROVED"),
                "styleCheck", Map.of("outcome", "APPROVED"),
                "testCoverage", Map.of("outcome", "APPROVED"),
                "performanceAnalysis", Map.of("outcome", "APPROVED"))))).isTrue();
        }
        @Test void notSatisfied_whenStyleFailed() {
            assertThat(goalCondition("pr-approved").test(ctx(Map.of(
                "codeAnalysis", analysis(false, false),
                "styleCheck", Map.of("outcome", "FAILED"),
                "testCoverage", Map.of("outcome", "APPROVED"),
                "performanceAnalysis", Map.of("outcome", "APPROVED"))))).isFalse();
        }
    }

    @Nested class SecurityVerified {
        @Test void satisfied_whenNotSecuritySensitive() {
            assertThat(goalCondition("security-verified").test(ctx(Map.of(
                "codeAnalysis", analysis(false, false))))).isTrue();
        }
        @Test void satisfied_whenSecuritySensitiveAndApproved() {
            assertThat(goalCondition("security-verified").test(ctx(Map.of(
                "codeAnalysis", analysis(true, false),
                "securityReview", Map.of("outcome", "APPROVED"))))).isTrue();
        }
        @Test void notSatisfied_whenSecuritySensitiveAndNotApproved() {
            assertThat(goalCondition("security-verified").test(ctx(Map.of(
                "codeAnalysis", analysis(true, false))))).isFalse();
        }
    }

    @Nested class CiPassing {
        @Test void satisfied_whenCiPassing() {
            assertThat(goalCondition("ci-passing").test(ctx(Map.of(
                "ci", Map.of("status", "passing"))))).isTrue();
        }
        @Test void notSatisfied_whenCiFailing() {
            assertThat(goalCondition("ci-passing").test(ctx(Map.of(
                "ci", Map.of("status", "failing"))))).isFalse();
        }
    }
}
