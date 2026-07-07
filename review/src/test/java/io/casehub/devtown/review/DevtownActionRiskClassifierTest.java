package io.casehub.devtown.review;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.worker.api.PlannedAction;
import io.casehub.api.spi.RiskDecision;
import io.casehub.api.spi.routing.StaticSetStrategy;
import io.casehub.devtown.domain.DevtownActionType;
import io.casehub.devtown.domain.HumanDecision;
import io.casehub.devtown.domain.HumanOversight;
import io.casehub.devtown.domain.preferences.RiskPreferenceKeys;
import io.casehub.platform.api.preferences.MapPreferences;
import io.casehub.platform.api.preferences.Preferences;

import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class DevtownActionRiskClassifierTest {

    private final DevtownActionRiskClassifier classifier = new DevtownActionRiskClassifier();

    private static PlannedAction action(final String actionType, final Map<String, Object> parameters) {
        return new PlannedAction("test action", actionType, parameters);
    }

    private static PlannedAction action(final String actionType) {
        return action(actionType, Map.of());
    }

    private static Preferences defaultPrefs() {
        return new MapPreferences(Map.of());
    }

    private static Preferences disabledPrefs() {
        return new MapPreferences(Map.of(
                RiskPreferenceKeys.ENABLED.qualifiedName(), "false"));
    }

    @Nested
    class AlwaysGate {

        @Test
        void prForceMerge_alwaysGateRequired() {
            RiskDecision result = classifier.classify(
                    action(DevtownActionType.PR_FORCE_MERGE), defaultPrefs());
            assertThat(result).isInstanceOf(RiskDecision.GateRequired.class);
            var gate = (RiskDecision.GateRequired) result;
            assertThat(gate.reversible()).isFalse();
            assertThat(((StaticSetStrategy) gate.candidateGroups()).values()).isEqualTo(java.util.Set.of(HumanDecision.PR_APPROVAL));
            assertThat(gate.scope()).isEqualTo(DevtownActionType.PR_FORCE_MERGE);
        }

        @Test
        void contributorAccessChange_alwaysGateRequired() {
            RiskDecision result = classifier.classify(
                    action(DevtownActionType.CONTRIBUTOR_ACCESS_CHANGE), defaultPrefs());
            assertThat(result).isInstanceOf(RiskDecision.GateRequired.class);
            var gate = (RiskDecision.GateRequired) result;
            assertThat(gate.reversible()).isFalse();
            assertThat(((StaticSetStrategy) gate.candidateGroups()).values()).isEqualTo(java.util.Set.of(HumanOversight.GENERAL));
        }

        @Test
        void prForceMerge_disabled_returnsAutonomous() {
            RiskDecision result = classifier.classify(
                    action(DevtownActionType.PR_FORCE_MERGE), disabledPrefs());
            assertThat(result).isInstanceOf(RiskDecision.Autonomous.class);
        }
    }

    @Nested
    class MinimumRequirement {

        @Test
        void prMergeExecute_zeroApprovals_gateRequired() {
            RiskDecision result = classifier.classify(
                    action(DevtownActionType.PR_MERGE_EXECUTE, Map.of("approvedReviewCount", 0)),
                    defaultPrefs());
            assertThat(result).isInstanceOf(RiskDecision.GateRequired.class);
            var gate = (RiskDecision.GateRequired) result;
            assertThat(gate.reversible()).isFalse();
            assertThat(((StaticSetStrategy) gate.candidateGroups()).values()).isEqualTo(java.util.Set.of(HumanDecision.PR_APPROVAL));
        }

        @Test
        void prMergeExecute_oneApproval_autonomous() {
            RiskDecision result = classifier.classify(
                    action(DevtownActionType.PR_MERGE_EXECUTE, Map.of("approvedReviewCount", 1)),
                    defaultPrefs());
            assertThat(result).isInstanceOf(RiskDecision.Autonomous.class);
        }

        @Test
        void prMergeExecute_twoApprovals_autonomous() {
            RiskDecision result = classifier.classify(
                    action(DevtownActionType.PR_MERGE_EXECUTE, Map.of("approvedReviewCount", 2)),
                    defaultPrefs());
            assertThat(result).isInstanceOf(RiskDecision.Autonomous.class);
        }

        @Test
        void prMergeExecute_missingContext_failSafe() {
            RiskDecision result = classifier.classify(
                    action(DevtownActionType.PR_MERGE_EXECUTE), defaultPrefs());
            assertThat(result).isInstanceOf(RiskDecision.GateRequired.class);
        }

        @Test
        void prMergeExecute_longValue_handledViaNumberIntValue() {
            RiskDecision result = classifier.classify(
                    action(DevtownActionType.PR_MERGE_EXECUTE, Map.of("approvedReviewCount", 2L)),
                    defaultPrefs());
            assertThat(result).isInstanceOf(RiskDecision.Autonomous.class);
        }
    }

    @Nested
    class RiskThreshold {

        @Test
        void securityEscalation_high_gateRequired() {
            RiskDecision result = classifier.classify(
                    action(DevtownActionType.SECURITY_ESCALATION, Map.of("severity", "HIGH")),
                    defaultPrefs());
            assertThat(result).isInstanceOf(RiskDecision.GateRequired.class);
            var gate = (RiskDecision.GateRequired) result;
            assertThat(gate.reversible()).isTrue();
            assertThat(((StaticSetStrategy) gate.candidateGroups()).values()).isEqualTo(java.util.Set.of(HumanOversight.ROUTING_REVIEW));
        }

        @Test
        void securityEscalation_critical_gateRequired() {
            RiskDecision result = classifier.classify(
                    action(DevtownActionType.SECURITY_ESCALATION, Map.of("severity", "CRITICAL")),
                    defaultPrefs());
            assertThat(result).isInstanceOf(RiskDecision.GateRequired.class);
        }

        @Test
        void securityEscalation_medium_autonomous() {
            RiskDecision result = classifier.classify(
                    action(DevtownActionType.SECURITY_ESCALATION, Map.of("severity", "MEDIUM")),
                    defaultPrefs());
            assertThat(result).isInstanceOf(RiskDecision.Autonomous.class);
        }

        @Test
        void securityEscalation_low_autonomous() {
            RiskDecision result = classifier.classify(
                    action(DevtownActionType.SECURITY_ESCALATION, Map.of("severity", "LOW")),
                    defaultPrefs());
            assertThat(result).isInstanceOf(RiskDecision.Autonomous.class);
        }

        @Test
        void securityEscalation_missingSeverity_failSafe() {
            RiskDecision result = classifier.classify(
                    action(DevtownActionType.SECURITY_ESCALATION), defaultPrefs());
            assertThat(result).isInstanceOf(RiskDecision.GateRequired.class);
        }

        @Test
        void securityEscalation_invalidSeverity_failSafe() {
            RiskDecision result = classifier.classify(
                    action(DevtownActionType.SECURITY_ESCALATION, Map.of("severity", "UNKNOWN")),
                    defaultPrefs());
            assertThat(result).isInstanceOf(RiskDecision.GateRequired.class);
        }

        @Test
        void issueCloseInvalid_belowThreshold_autonomous() {
            RiskDecision result = classifier.classify(
                    action(DevtownActionType.ISSUE_CLOSE_INVALID, Map.of("commentCount", 3)),
                    defaultPrefs());
            assertThat(result).isInstanceOf(RiskDecision.Autonomous.class);
        }

        @Test
        void issueCloseInvalid_atThreshold_gateRequired() {
            RiskDecision result = classifier.classify(
                    action(DevtownActionType.ISSUE_CLOSE_INVALID, Map.of("commentCount", 5)),
                    defaultPrefs());
            assertThat(result).isInstanceOf(RiskDecision.GateRequired.class);
            var gate = (RiskDecision.GateRequired) result;
            assertThat(gate.reversible()).isTrue();
            assertThat(((StaticSetStrategy) gate.candidateGroups()).values()).isEqualTo(java.util.Set.of(HumanOversight.GENERAL));
        }

        @Test
        void issueCloseInvalid_nullContext_failSafe() {
            RiskDecision result = classifier.classify(
                    new PlannedAction("test", DevtownActionType.ISSUE_CLOSE_INVALID, null),
                    defaultPrefs());
            assertThat(result).isInstanceOf(RiskDecision.GateRequired.class);
        }

        @Test
        void dependencyRemoval_belowThreshold_autonomous() {
            RiskDecision result = classifier.classify(
                    action(DevtownActionType.DEPENDENCY_REMOVAL, Map.of("transitiveUsageCount", 2)),
                    defaultPrefs());
            assertThat(result).isInstanceOf(RiskDecision.Autonomous.class);
        }

        @Test
        void dependencyRemoval_atThreshold_gateRequired() {
            RiskDecision result = classifier.classify(
                    action(DevtownActionType.DEPENDENCY_REMOVAL, Map.of("transitiveUsageCount", 3)),
                    defaultPrefs());
            assertThat(result).isInstanceOf(RiskDecision.GateRequired.class);
            var gate = (RiskDecision.GateRequired) result;
            assertThat(gate.reversible()).isFalse();
        }

        @Test
        void productionDeploy_belowThreshold_autonomous() {
            RiskDecision result = classifier.classify(
                    action(DevtownActionType.PRODUCTION_DEPLOY, Map.of("modulesAffected", 1)),
                    defaultPrefs());
            assertThat(result).isInstanceOf(RiskDecision.Autonomous.class);
        }

        @Test
        void productionDeploy_atThreshold_gateRequired() {
            RiskDecision result = classifier.classify(
                    action(DevtownActionType.PRODUCTION_DEPLOY, Map.of("modulesAffected", 5)),
                    defaultPrefs());
            assertThat(result).isInstanceOf(RiskDecision.GateRequired.class);
            var gate = (RiskDecision.GateRequired) result;
            assertThat(gate.reversible()).isFalse();
            assertThat(((StaticSetStrategy) gate.candidateGroups()).values()).isEqualTo(java.util.Set.of(HumanOversight.ROUTING_REVIEW));
        }
    }

    @Nested
    class Conditional {

        @Test
        void prReviewOverride_rejected_gateRequired() {
            RiskDecision result = classifier.classify(
                    action(DevtownActionType.PR_REVIEW_OVERRIDE, Map.of("originalVerdict", "REJECTED")),
                    defaultPrefs());
            assertThat(result).isInstanceOf(RiskDecision.GateRequired.class);
            var gate = (RiskDecision.GateRequired) result;
            assertThat(gate.reversible()).isTrue();
            assertThat(((StaticSetStrategy) gate.candidateGroups()).values()).isEqualTo(java.util.Set.of(HumanDecision.PR_APPROVAL));
        }

        @Test
        void prReviewOverride_approved_autonomous() {
            RiskDecision result = classifier.classify(
                    action(DevtownActionType.PR_REVIEW_OVERRIDE, Map.of("originalVerdict", "APPROVED")),
                    defaultPrefs());
            assertThat(result).isInstanceOf(RiskDecision.Autonomous.class);
        }

        @Test
        void prReviewOverride_pending_autonomous() {
            RiskDecision result = classifier.classify(
                    action(DevtownActionType.PR_REVIEW_OVERRIDE, Map.of("originalVerdict", "PENDING")),
                    defaultPrefs());
            assertThat(result).isInstanceOf(RiskDecision.Autonomous.class);
        }

        @Test
        void prReviewOverride_absent_autonomous() {
            RiskDecision result = classifier.classify(
                    action(DevtownActionType.PR_REVIEW_OVERRIDE), defaultPrefs());
            assertThat(result).isInstanceOf(RiskDecision.Autonomous.class);
        }
    }

    @Nested
    class FailSafe {

        @Test
        void unknownActionType_gateRequired() {
            RiskDecision result = classifier.classify(
                    action("unknown-action-type"), defaultPrefs());
            assertThat(result).isInstanceOf(RiskDecision.GateRequired.class);
            var gate = (RiskDecision.GateRequired) result;
            assertThat(gate.reversible()).isTrue();
            assertThat(((StaticSetStrategy) gate.candidateGroups()).values()).isEqualTo(java.util.Set.of(HumanOversight.GENERAL));
            assertThat(gate.scope()).isEqualTo("unknown-action-type");
        }
    }

    @Nested
    class Disabled {

        @Test
        void anyActionType_disabled_returnsAutonomous() {
            String[] allTypes = {
                DevtownActionType.PR_MERGE_EXECUTE,
                DevtownActionType.PR_FORCE_MERGE,
                DevtownActionType.PR_REVIEW_OVERRIDE,
                DevtownActionType.SECURITY_ESCALATION,
                DevtownActionType.ISSUE_CLOSE_INVALID,
                DevtownActionType.DEPENDENCY_REMOVAL,
                DevtownActionType.CONTRIBUTOR_ACCESS_CHANGE,
                DevtownActionType.PRODUCTION_DEPLOY,
            };
            for (final String type : allTypes) {
                RiskDecision result = classifier.classify(action(type), disabledPrefs());
                assertThat(result)
                        .as("disabled %s should be Autonomous", type)
                        .isInstanceOf(RiskDecision.Autonomous.class);
            }
        }
    }
}
