# Design: Layer 2 — SLA-Bounded Human Review Gate with Escalation

**Issue:** casehubio/devtown#38
**Date:** 2026-05-22
**Status:** Approved

---

## Problem

Layer 1 gap comment in `NaivePrReviewService`:
```java
// LAYER 1 GAP: no response SLA — analysis can stall indefinitely with no escalation.
```

The `human-approval` binding in `pr-review.yaml` creates a WorkItem but sets no deadline, routes to no group, and has no domain-aware response when the deadline passes. A PR review can sit unclaimed forever with no observable consequence.

---

## What Layer 2 adds

- Formal completion deadline on the human review WorkItem (`expiresIn` in YAML)
- `candidateGroups` routing — who can claim the task
- `SlaBreachPolicy` SPI — scope-aware, typed, chainable breach response
- `SlaBreachHandler` — CDI observer that executes the policy when WorkItems expire
- `pr-review.yaml` failure goal — case terminates with a named reason rather than stalling

---

## Module structure

```
casehubio/platform
  apps-api/   (NEW — platform#24) pure Java SPI contracts: SlaBreachPolicy, BreachDecision,
              BreachType, BreachedTask, SlaBreachContext
  apps/       (NEW — platform#24) Quarkus: NoOpSlaBreachPolicy @ApplicationScoped @DefaultBean

casehubio/devtown
  devtown-domain/   SlaPreferenceKeys, StringPreference, IntPreference, DefaultSlaBreachPolicy
  devtown-app/      SlaBreachHandler, SlaBreachPolicyBean (CDI wrapper)
```

`apps-api` depends on `platform-api` only (Path, Preferences, PreferenceKey). `devtown-domain` depends on `apps-api` and `platform-api`. `devtown-app` depends on `devtown-domain`, `casehub-work-runtime`, `casehub-engine-work-adapter`.

**Sequencing:** `casehubio/platform` must ship `apps-api` and `apps` modules (platform#24) before devtown depends on them.

---

## SPI contract (in `platform/apps-api`)

### BreachType

```java
public enum BreachType {
    CLAIM_EXPIRED,       // nobody claimed within the claim deadline
    COMPLETION_EXPIRED   // claimed but not completed within the completion deadline
}
```

### BreachedTask

Zero-dependency projection of `WorkItem` — `apps-api` never touches `casehub-work-runtime`:

```java
public record BreachedTask(
    String taskId,
    String callerRef,       // "case:{caseId}/pi:{planItemId}"
    String title,
    String candidateGroups  // CSV as stored in WorkItem
) {}
```

### SlaBreachContext

```java
public record SlaBreachContext(
    BreachType breachType,
    BreachedTask task,
    Path scope,             // resolved scope path — available for logging or further lookup
    Preferences preferences // pre-resolved at Path.of("casehubio","devtown","pr-review",caseId)
) {}
```

`Preferences` is pre-resolved by the framework so SPI implementations are pure Java — no CDI injection required.

### BreachDecision

Sealed, chainable. `EscalateTo` and `Extend` carry `thenOnBreach` — what the framework executes if the escalated or extended task also breaches:

```java
public sealed interface BreachDecision
    permits BreachDecision.Fail, BreachDecision.EscalateTo, BreachDecision.Extend {

    record Fail(String reason) implements BreachDecision {}

    record EscalateTo(
        String candidateGroup,
        Duration deadline,
        BreachDecision thenOnBreach
    ) implements BreachDecision {}

    record Extend(
        Duration additionalTime,
        BreachDecision thenOnBreach
    ) implements BreachDecision {}
}
```

A two-tier escalation chain:
```java
BreachDecision.escalateTo("pr-leads", Duration.ofHours(4),
    new BreachDecision.Fail("sla-breach-final"))
```

### SlaBreachPolicy

```java
public interface SlaBreachPolicy {
    BreachDecision onBreach(SlaBreachContext context);
}
```

`NoOpSlaBreachPolicy` in `apps/` is `@ApplicationScoped @DefaultBean` — system is functional with no implementation wired.

---

## Scope-aware preference resolution

Preferences resolve at `Path.of("casehubio", "devtown", "pr-review", caseId.toString())`.

`JpaPreferenceProvider` walks ancestors: `["casehubio", "casehubio/devtown", "casehubio/devtown/pr-review", "casehubio/devtown/pr-review/<caseId>"]` — child overrides parent. This gives org → app → case-type → case-instance scope out of the box. RBAC on which level an actor may write to is a future concern (platform#6 persistence backend in place; write-side ACL not yet implemented).

In tests, `MockPreferenceProvider` (`@DefaultBean`) is active. Values configured via `casehub.platform.preferences.defaults.devtown.sla.*` in `application.properties`.

---

## devtown-domain: SlaPreferenceKeys and DefaultSlaBreachPolicy

```java
public final class SlaPreferenceKeys {
    public static final PreferenceKey<IntPreference> ESCALATION_HOURS =
        new PreferenceKey<>("devtown.sla", "escalation-hours",
            IntPreference.of(8), IntPreference::parse);
    public static final PreferenceKey<StringPreference> ESCALATION_GROUP =
        new PreferenceKey<>("devtown.sla", "escalation-group",
            StringPreference.of("pr-leads"), StringPreference::parse);
    public static final PreferenceKey<StringPreference> BREACH_TERMINAL_REASON =
        new PreferenceKey<>("devtown.sla", "breach-terminal-reason",
            StringPreference.of("sla-breach"), StringPreference::parse);
    public static final PreferenceKey<IntPreference> COMPLETION_HOURS =
        new PreferenceKey<>("devtown.sla", "completion-hours",
            IntPreference.of(24), IntPreference::parse);
    public static final PreferenceKey<StringPreference> CANDIDATE_GROUP =
        new PreferenceKey<>("devtown.sla", "candidate-group",
            StringPreference.of("pr-reviewers"), StringPreference::parse);
}
```

`StringPreference` and `IntPreference` are simple records implementing `SingleValuePreference` — defined in `devtown-domain` alongside the keys.

`DefaultSlaBreachPolicy` — pure Java, no CDI:
```java
public class DefaultSlaBreachPolicy implements SlaBreachPolicy {
    @Override
    public BreachDecision onBreach(SlaBreachContext ctx) {
        Preferences p = ctx.preferences();
        return new BreachDecision.EscalateTo(
            p.getOrDefault(SlaPreferenceKeys.ESCALATION_GROUP).value(),
            Duration.ofHours(p.getOrDefault(SlaPreferenceKeys.ESCALATION_HOURS).value()),
            new BreachDecision.Fail(
                p.getOrDefault(SlaPreferenceKeys.BREACH_TERMINAL_REASON).value()));
    }
}
```

CDI registration in `devtown-app`:
```java
@ApplicationScoped  // no @DefaultBean — displaces platform NoOpSlaBreachPolicy
public class SlaBreachPolicyBean extends DefaultSlaBreachPolicy {}
```

---

## SlaBreachHandler (devtown-app)

```java
@ApplicationScoped
public class SlaBreachHandler {

    @Inject SlaBreachPolicy slaBreachPolicy;
    @Inject PreferenceProvider preferenceProvider;
    @Inject PrReviewCaseHub caseHub;
    @Inject WorkItemService workItemService;

    void onExpiry(@ObservesAsync WorkItemLifecycleEvent event) {
        if (!isBreachEvent(event)) return;
        WorkItem item = (WorkItem) event.source();
        if (!isManagedCallerRef(item.callerRef)) return;

        UUID caseId = CallerRef.parse(item.callerRef).caseId();
        BreachType breachType = toBreachType(event.eventType());
        Preferences prefs = preferenceProvider.resolve(
            SettingsScope.of("casehubio", "devtown", "pr-review", caseId.toString()));

        BreachDecision decision = resolveDecision(item, prefs, breachType);
        executeDecision(decision, caseId, item);
    }
}
```

**Filtering:** `isManagedCallerRef` accepts `callerRef != null && callerRef.startsWith("case:")` — only PR review WorkItems. Breach events: `EXPIRED` → `COMPLETION_EXPIRED`, `CLAIM_EXPIRED` → `CLAIM_EXPIRED`.

**Decision resolution:** If the WorkItem carries a `breach-tier=terminal` label, the handler skips the policy and executes `Fail` directly using `SlaPreferenceKeys.BREACH_TERMINAL_REASON`. Otherwise calls `slaBreachPolicy.onBreach(ctx)`.

**Decision execution:**

| Decision | Action |
|----------|--------|
| `Fail(reason)` | `caseHub.signal(caseId, "humanApproval", Map.of("status", reason))` |
| `EscalateTo(group, deadline, thenOnBreach)` | Create escalated WorkItem with new `candidateGroups`, `expiresAt = now + deadline`, same `callerRef`, label `breach-tier=terminal`. Signal case: `humanApproval.status = "escalating"` |
| `Extend(duration, thenOnBreach)` | Requires `WorkItemService.extend()` — out of scope (work#211) |

**Error handling:**

| Condition | Response |
|-----------|----------|
| `callerRef` null or not a case ref | Log warn, return |
| Case not found | Log warn, return |
| `WorkItemService.create()` fails during `EscalateTo` | Log error, execute `Fail` directly |
| `caseHub.signal()` throws | Log error — case won't terminate; known gap |

---

## pr-review.yaml changes

Add `expiresIn` and `candidateGroups` to `human-approval` binding:
```yaml
- name: human-approval
  on: { contextChange: {} }
  when: ".pr.linesChanged > .policy.humanApprovalThreshold and .humanApproval == null"
  humanTask:
    title: "PR approval required"
    candidateGroups: [pr-reviewers]
    expiresIn: 24h
    outputMapping: "{ humanApproval: . }"
```

Add failure goal (pending engine#326 — verify engine support):
```yaml
- name: pr-sla-breached
  kind: failure
  condition: >-
    .humanApproval.status == "sla-breach" or
    .humanApproval.status == "sla-breach-final"
```

If `kind: failure` is not yet supported, the case stalls on SLA breach (no merge possible) — acceptable for Layer 2; failure goal support tracked in engine#326.

---

## Testing

### Unit — DefaultSlaBreachPolicy

Plain JUnit, no Quarkus. Build `MapPreferences` directly. Cover:
- `COMPLETION_EXPIRED` → returns `EscalateTo` with chained `Fail`
- `CLAIM_EXPIRED` → appropriate decision
- Default preference values with no overrides
- `thenOnBreach` is a `Fail` with configured terminal reason

### SPI wiring — SlaBreachHandlerWiringTest

`@Alternative @ApplicationScoped` `CapturingBreachPolicy` records the `SlaBreachContext` delivered by the handler. Verifies scope path, breach type, and `BreachedTask` fields. Follows `spi-testing-alternative-inner-classes.md`.

### Integration — SlaBreachLifecycleTest

Full two-tier breach lifecycle as `@QuarkusTest`. Checkpoints:
1. Start case (pre-seeded per PP-20260521-134c38)
2. WorkItem created by `human-approval` binding
3. Trigger expiry: set `WorkItem.expiresAt` to past, call `ExpiryLifecycleService.checkExpired()` directly
4. Escalated WorkItem created (`candidateGroups=pr-leads`); case context `humanApproval.status = "escalating"`
5. Trigger expiry on escalated WorkItem
6. Case context `humanApproval.status = "sla-breach"`; no further WorkItems created

Protocol compliance:
- **PP-20260521-134c38** — pre-seed all parallel check keys
- **PP-20260521-a36692** — `MemoryPlanItemStore` in `selected-alternatives`
- **`scheduler-test-isolation.md`** — disable `ExpiryCleanupJob` in test properties

---

## Deferred concerns

| Issue | What it unblocks |
|-------|-----------------|
| engine#325 — `HumanTaskTarget.claimDeadlineHours` | Declarative claim SLA in YAML |
| engine#326 — failure goal support | Active case FAILED state on SLA breach |
| engine#327 — dynamic `expiresIn` | Per-instance SLA thresholds from `PreferenceProvider` |
| work#211 — `WorkItemService.extend()` | `BreachDecision.Extend` execution |
| platform#24 — `apps-api` / `apps` modules | Prerequisite — must ship before devtown#38 implementation |

---

## What this closes

Layer 1 gap: `// LAYER 1 GAP: no response SLA — analysis can stall indefinitely with no escalation.`

After Layer 2: every PR review WorkItem has a formal deadline, routes to a named candidate group, and produces an observable case outcome on breach — either escalation to a senior group or case termination with a named reason.
