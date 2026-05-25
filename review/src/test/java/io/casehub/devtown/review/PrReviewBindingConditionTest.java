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

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.evaluator.LambdaExpressionEvaluator;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PrReviewBindingConditionTest {

    private static final int THRESHOLD = 500;
    private CaseDefinition def;

    @BeforeEach
    void setup() {
        def = PrReviewCaseDefinition.build(THRESHOLD);
    }

    private LambdaExpressionEvaluator condition(String bindingName) {
        return def.getBindings().stream()
            .filter(b -> b.getName().equals(bindingName))
            .findFirst()
            .map(b -> (LambdaExpressionEvaluator) b.getWhen())
            .orElseThrow(() -> new AssertionError("Binding not found: " + bindingName));
    }

    private MapCaseContext ctx(Map<String, Object> data) {
        return new MapCaseContext(data);
    }

    private Map<String, Object> pr(int linesChanged) {
        return Map.of("id", "42", "repo", "casehubio/devtown",
            "linesChanged", linesChanged, "baseRef", "main", "headSha", "abc123");
    }

    private Map<String, Object> policy() {
        return Map.of("humanApprovalThreshold", THRESHOLD);
    }

    private Map<String, Object> analysis(boolean securitySensitive, boolean architectureCrossing) {
        return Map.of("complete", true,
            "securitySensitive", securitySensitive,
            "architectureCrossing", architectureCrossing);
    }

    @Nested class InitialAnalysis {
        @Test void fires_whenPrArrivesAndNoAnalysis() {
            assertThat(condition("initial-analysis").test(ctx(Map.of("pr", pr(100))))).isTrue();
        }
        @Test void doesNotFire_whenAnalysisAlreadyPresent() {
            assertThat(condition("initial-analysis").test(ctx(Map.of(
                "pr", pr(100), "codeAnalysis", analysis(false, false))))).isFalse();
        }
        @Test void doesNotFire_whenNoPr() {
            assertThat(condition("initial-analysis").test(ctx(Map.of()))).isFalse();
        }
    }

    @Nested class RunCi {
        @Test void fires_whenPrArrivesAndNoCi() {
            assertThat(condition("run-ci").test(ctx(Map.of("pr", pr(100))))).isTrue();
        }
        @Test void doesNotFire_whenCiAlreadyPresent() {
            assertThat(condition("run-ci").test(ctx(Map.of(
                "pr", pr(100), "ci", Map.of("status", "pending"))))).isFalse();
        }
    }

    @Nested class SecurityReview {
        @Test void fires_whenAnalysisCompleteAndSecuritySensitive() {
            assertThat(condition("security-review").test(ctx(Map.of(
                "pr", pr(100), "codeAnalysis", analysis(true, false))))).isTrue();
        }
        @Test void doesNotFire_whenNotSecuritySensitive() {
            assertThat(condition("security-review").test(ctx(Map.of(
                "pr", pr(100), "codeAnalysis", analysis(false, false))))).isFalse();
        }
        @Test void doesNotFire_whenAlreadyReviewed() {
            assertThat(condition("security-review").test(ctx(Map.of(
                "pr", pr(100),
                "codeAnalysis", analysis(true, false),
                "securityReview", Map.of("outcome", "APPROVED"))))).isFalse();
        }
        @Test void doesNotFire_whenAnalysisNotComplete() {
            assertThat(condition("security-review").test(ctx(Map.of(
                "pr", pr(100),
                "codeAnalysis", Map.of("complete", false,
                    "securitySensitive", true, "architectureCrossing", false))))).isFalse();
        }
    }

    @Nested class ArchitectureReview {
        @Test void fires_whenAnalysisCompleteAndArchitectureCrossing() {
            assertThat(condition("architecture-review").test(ctx(Map.of(
                "pr", pr(100), "codeAnalysis", analysis(false, true))))).isTrue();
        }
        @Test void doesNotFire_whenNoArchitectureCrossing() {
            assertThat(condition("architecture-review").test(ctx(Map.of(
                "pr", pr(100), "codeAnalysis", analysis(false, false))))).isFalse();
        }
        @Test void doesNotFire_whenAlreadyReviewed() {
            assertThat(condition("architecture-review").test(ctx(Map.of(
                "pr", pr(100),
                "codeAnalysis", analysis(false, true),
                "architectureReview", Map.of("outcome", "APPROVED"))))).isFalse();
        }
        @Test void doesNotFire_whenAnalysisNotComplete() {
            assertThat(condition("architecture-review").test(ctx(Map.of(
                "pr", pr(100),
                "codeAnalysis", Map.of("complete", false,
                    "securitySensitive", false, "architectureCrossing", true))))).isFalse();
        }
    }

    @Nested class ParallelChecks {
        @Test void styleCheck_fires_whenAnalysisComplete() {
            assertThat(condition("style-check").test(ctx(Map.of(
                "pr", pr(100), "codeAnalysis", analysis(false, false))))).isTrue();
        }
        @Test void styleCheck_doesNotFire_whenAlreadyDone() {
            assertThat(condition("style-check").test(ctx(Map.of(
                "pr", pr(100),
                "codeAnalysis", analysis(false, false),
                "styleCheck", Map.of("outcome", "APPROVED"))))).isFalse();
        }
        @Test void styleCheck_doesNotFire_whenAnalysisNotComplete() {
            assertThat(condition("style-check").test(ctx(Map.of(
                "pr", pr(100),
                "codeAnalysis", Map.of("complete", false,
                    "securitySensitive", false, "architectureCrossing", false))))).isFalse();
        }
        @Test void testCoverage_fires_whenAnalysisComplete() {
            assertThat(condition("test-coverage").test(ctx(Map.of(
                "pr", pr(100), "codeAnalysis", analysis(false, false))))).isTrue();
        }
        @Test void testCoverage_doesNotFire_whenAlreadyDone() {
            assertThat(condition("test-coverage").test(ctx(Map.of(
                "pr", pr(100),
                "codeAnalysis", analysis(false, false),
                "testCoverage", Map.of("outcome", "APPROVED"))))).isFalse();
        }
        @Test void testCoverage_doesNotFire_whenAnalysisNotComplete() {
            assertThat(condition("test-coverage").test(ctx(Map.of(
                "pr", pr(100),
                "codeAnalysis", Map.of("complete", false,
                    "securitySensitive", false, "architectureCrossing", false))))).isFalse();
        }
        @Test void performanceAnalysis_fires_whenAnalysisComplete() {
            assertThat(condition("performance-analysis").test(ctx(Map.of(
                "pr", pr(100), "codeAnalysis", analysis(false, false))))).isTrue();
        }
        @Test void performanceAnalysis_doesNotFire_whenAlreadyDone() {
            assertThat(condition("performance-analysis").test(ctx(Map.of(
                "pr", pr(100),
                "codeAnalysis", analysis(false, false),
                "performanceAnalysis", Map.of("outcome", "APPROVED"))))).isFalse();
        }
        @Test void performanceAnalysis_doesNotFire_whenAnalysisNotComplete() {
            assertThat(condition("performance-analysis").test(ctx(Map.of(
                "pr", pr(100),
                "codeAnalysis", Map.of("complete", false,
                    "securitySensitive", false, "architectureCrossing", false))))).isFalse();
        }
    }

    @Nested class HumanApproval {
        @Test void fires_whenLinesExceedThreshold() {
            assertThat(condition("human-approval").test(ctx(Map.of(
                "pr", pr(THRESHOLD + 1), "policy", policy())))).isTrue();
        }
        @Test void doesNotFire_whenLinesAtThreshold() {
            assertThat(condition("human-approval").test(ctx(Map.of(
                "pr", pr(THRESHOLD), "policy", policy())))).isFalse();
        }
        @Test void doesNotFire_whenLinesBelow() {
            assertThat(condition("human-approval").test(ctx(Map.of(
                "pr", pr(100), "policy", policy())))).isFalse();
        }
        @Test void doesNotFire_whenAlreadyApproved() {
            assertThat(condition("human-approval").test(ctx(Map.of(
                "pr", pr(THRESHOLD + 1),
                "policy", policy(),
                "humanApproval", Map.of("status", "approved"))))).isFalse();
        }
    }

    @Nested class Merge {
        private Map<String, Object> allApproved() {
            return Map.of(
                "pr", pr(100),
                "policy", policy(),
                "codeAnalysis", analysis(false, false),
                "securityReview", Map.of("outcome", "APPROVED"),
                "styleCheck", Map.of("outcome", "APPROVED"),
                "testCoverage", Map.of("outcome", "APPROVED"),
                "performanceAnalysis", Map.of("outcome", "APPROVED"),
                "ci", Map.of("status", "passing")
            );
        }

        @Test void fires_whenAllConditionsSatisfied_noArchCrossing_noHumanRequired() {
            assertThat(condition("merge").test(ctx(allApproved()))).isTrue();
        }
        @Test void doesNotFire_whenCiNotPassing() {
            var data = new java.util.HashMap<>(allApproved());
            data.put("ci", Map.of("status", "failing"));
            assertThat(condition("merge").test(ctx(data))).isFalse();
        }
        @Test void doesNotFire_whenStyleCheckNotApproved() {
            var data = new java.util.HashMap<>(allApproved());
            data.put("styleCheck", Map.of("outcome", "FAILED"));
            assertThat(condition("merge").test(ctx(data))).isFalse();
        }
        @Test void doesNotFire_whenArchCrossingAndNoArchReview() {
            var data = new java.util.HashMap<>(allApproved());
            data.put("codeAnalysis", analysis(false, true));
            assertThat(condition("merge").test(ctx(data))).isFalse();
        }
        @Test void fires_whenArchCrossingAndArchReviewApproved() {
            var data = new java.util.HashMap<>(allApproved());
            data.put("codeAnalysis", analysis(false, true));
            data.put("architectureReview", Map.of("outcome", "APPROVED"));
            assertThat(condition("merge").test(ctx(data))).isTrue();
        }
        @Test void doesNotFire_whenHumanRequiredButNotApproved() {
            var data = new java.util.HashMap<>(allApproved());
            data.put("pr", pr(THRESHOLD + 1));
            assertThat(condition("merge").test(ctx(data))).isFalse();
        }
        @Test void fires_whenHumanRequiredAndApproved() {
            var data = new java.util.HashMap<>(allApproved());
            data.put("pr", pr(THRESHOLD + 1));
            data.put("humanApproval", Map.of("status", "approved"));
            assertThat(condition("merge").test(ctx(data))).isTrue();
        }
        @Test void fires_whenNotSecuritySensitiveAndNoSecurityReview() {
            var data = new java.util.HashMap<>(allApproved());
            data.remove("securityReview");
            // codeAnalysis already has securitySensitive=false in allApproved() via analysis(false, false)
            assertThat(condition("merge").test(ctx(data))).isTrue();
        }
    }
}
