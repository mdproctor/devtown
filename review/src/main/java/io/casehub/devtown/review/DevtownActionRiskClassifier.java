package io.casehub.devtown.review;

import io.casehub.worker.api.PlannedAction;
import io.casehub.api.spi.RiskDecision;
import io.casehub.api.spi.routing.CandidateSetStrategy;
import io.casehub.api.spi.routing.StaticSetStrategy;
import io.casehub.devtown.domain.DevtownActionType;
import io.casehub.devtown.domain.HumanDecision;
import io.casehub.devtown.domain.HumanOversight;
import io.casehub.devtown.domain.IncidentSeverity;
import io.casehub.devtown.domain.preferences.IntPreference;
import io.casehub.devtown.domain.preferences.RiskPreferenceKeys;
import io.casehub.devtown.domain.sla.StringPreference;
import io.casehub.platform.api.preferences.PreferenceKey;
import io.casehub.platform.api.preferences.Preferences;
import java.time.Duration;
import java.util.Map;

public class DevtownActionRiskClassifier {

    public RiskDecision classify(final PlannedAction action, final Preferences prefs) {
        if (!isEnabled(prefs)) {
            return new RiskDecision.Autonomous();
        }
        return switch (action.actionType()) {
            case DevtownActionType.PR_FORCE_MERGE -> alwaysGate(action, prefs,
                    "Bypasses safety checks — requires explicit human approval",
                    false, StaticSetStrategy.of(HumanDecision.PR_APPROVAL));
            case DevtownActionType.CONTRIBUTOR_ACCESS_CHANGE -> alwaysGate(action, prefs,
                    "Access changes are consequential regardless of scope",
                    false, StaticSetStrategy.of(HumanOversight.GENERAL));
            case DevtownActionType.PR_MERGE_EXECUTE -> classifyMergeExecute(action, prefs);
            case DevtownActionType.SECURITY_ESCALATION -> classifySecurityEscalation(action, prefs);
            case DevtownActionType.ISSUE_CLOSE_INVALID -> classifyIntThreshold(action, prefs,
                    "commentCount", RiskPreferenceKeys.ISSUE_CLOSE_COMMENT_THRESHOLD,
                    true, StaticSetStrategy.of(HumanOversight.GENERAL),
                    "High-engagement issue — confirm closure");
            case DevtownActionType.DEPENDENCY_REMOVAL -> classifyIntThreshold(action, prefs,
                    "transitiveUsageCount", RiskPreferenceKeys.DEPENDENCY_USAGE_THRESHOLD,
                    false, StaticSetStrategy.of(HumanOversight.GENERAL),
                    "Removing widely-used dependency");
            case DevtownActionType.PRODUCTION_DEPLOY -> classifyIntThreshold(action, prefs,
                    "modulesAffected", RiskPreferenceKeys.DEPLOY_MODULE_THRESHOLD,
                    false, StaticSetStrategy.of(HumanOversight.ROUTING_REVIEW),
                    "Production deploy affecting multiple modules");
            case DevtownActionType.PR_REVIEW_OVERRIDE -> classifyReviewOverride(action, prefs);
            default -> failSafe(action);
        };
    }

    private static boolean isEnabled(final Preferences prefs) {
        return prefs.getOrDefault(RiskPreferenceKeys.ENABLED).value();
    }

    private RiskDecision alwaysGate(final PlannedAction action, final Preferences prefs,
            final String reason, final boolean reversible, final CandidateSetStrategy candidateGroups) {
        return new RiskDecision.GateRequired(reason, reversible, candidateGroups,
                expiresIn(prefs, reversible), action.actionType());
    }

    private RiskDecision classifyMergeExecute(final PlannedAction action, final Preferences prefs) {
        final int minimum = prefs.getOrDefault(RiskPreferenceKeys.MERGE_MIN_APPROVED_REVIEWS).value();
        final Integer actual = extractInt(action.parameters(), "approvedReviewCount");
        if (actual == null || actual < minimum) {
            return new RiskDecision.GateRequired(
                    "Merge requires at least " + minimum + " approved review(s)",
                    false, StaticSetStrategy.of(HumanDecision.PR_APPROVAL),
                    expiresIn(prefs, false), action.actionType());
        }
        return new RiskDecision.Autonomous();
    }

    private RiskDecision classifySecurityEscalation(final PlannedAction action, final Preferences prefs) {
        final StringPreference thresholdPref = prefs.getOrDefault(RiskPreferenceKeys.SECURITY_SEVERITY_THRESHOLD);
        IncidentSeverity threshold;
        try {
            threshold = IncidentSeverity.valueOf(thresholdPref.value());
        } catch (IllegalArgumentException e) {
            return failSafe(action);
        }

        final Object rawSeverity = action.parameters() != null ? action.parameters().get("severity") : null;
        if (rawSeverity == null) {
            return failSafeForAction(action, prefs, true, StaticSetStrategy.of(HumanOversight.ROUTING_REVIEW));
        }

        IncidentSeverity actual;
        try {
            actual = IncidentSeverity.valueOf(rawSeverity.toString());
        } catch (IllegalArgumentException e) {
            return failSafeForAction(action, prefs, true, StaticSetStrategy.of(HumanOversight.ROUTING_REVIEW));
        }

        if (actual.confidence() >= threshold.confidence()) {
            return new RiskDecision.GateRequired(
                    "Security finding (" + actual + ") requires human confirmation",
                    true, StaticSetStrategy.of(HumanOversight.ROUTING_REVIEW),
                    expiresIn(prefs, true), action.actionType());
        }
        return new RiskDecision.Autonomous();
    }

    private RiskDecision classifyIntThreshold(final PlannedAction action, final Preferences prefs,
            final String contextKey,
            final PreferenceKey<IntPreference> thresholdKey,
            final boolean reversible, final CandidateSetStrategy candidateGroups, final String reason) {
        final int threshold = prefs.getOrDefault(thresholdKey).value();
        final Integer actual = extractInt(action.parameters(), contextKey);
        if (actual == null || actual >= threshold) {
            return new RiskDecision.GateRequired(reason, reversible, candidateGroups,
                    expiresIn(prefs, reversible), action.actionType());
        }
        return new RiskDecision.Autonomous();
    }

    private RiskDecision classifyReviewOverride(final PlannedAction action, final Preferences prefs) {
        final Object verdict = action.parameters() != null ? action.parameters().get("originalVerdict") : null;
        if ("REJECTED".equals(verdict)) {
            return new RiskDecision.GateRequired(
                    "Overriding a rejection requires human approval",
                    true, StaticSetStrategy.of(HumanDecision.PR_APPROVAL),
                    expiresIn(prefs, true), action.actionType());
        }
        return new RiskDecision.Autonomous();
    }

    private RiskDecision failSafe(final PlannedAction action) {
        return new RiskDecision.GateRequired(
                "Unknown action type — manual review required",
                true, StaticSetStrategy.of(HumanOversight.GENERAL),
                Duration.ofHours(24), action.actionType());
    }

    private RiskDecision failSafeForAction(final PlannedAction action, final Preferences prefs,
            final boolean reversible, final CandidateSetStrategy candidateGroups) {
        return new RiskDecision.GateRequired(
                "Missing or invalid context — manual review required",
                reversible, candidateGroups,
                expiresIn(prefs, reversible), action.actionType());
    }

    private static Duration expiresIn(final Preferences prefs, final boolean reversible) {
        int minutes = reversible
                ? prefs.getOrDefault(RiskPreferenceKeys.EXPIRES_IN_MINUTES_REVERSIBLE).value()
                : prefs.getOrDefault(RiskPreferenceKeys.EXPIRES_IN_MINUTES_IRREVERSIBLE).value();
        return Duration.ofMinutes(minutes);
    }

    private static Integer extractInt(final Map<String, Object> context, final String key) {
        if (context == null) return null;
        Object value = context.get(key);
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
