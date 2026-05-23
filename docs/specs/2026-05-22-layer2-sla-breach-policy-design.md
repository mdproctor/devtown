# Design: Layer 2 — SLA-Bounded Human Review Gate with Escalation

**Issue:** casehubio/devtown#41
**Date:** 2026-05-23 (reconciled from 2026-05-22 draft)
**Status:** Approved

---

## Problem

Layer 1 gap comment in `NaivePrReviewService`:
```java
// LAYER 1 GAP: no response SLA — analysis can stall indefinitely with no escalation.
```

The `human-approval` binding in `pr-review.yaml` creates a WorkItem but sets no deadline,
routes to no group, and has no domain-aware response when the deadline passes. A PR review
can sit unclaimed forever with no observable consequence.

---

## What Layer 2 adds

- Formal completion deadline on the human review WorkItem (`expiresIn` in YAML)
- `candidateGroups` routing — who can claim the task
- `SlaBreachPolicy` SPI implementation in `devtown-domain` — scope-aware, stateless two-tier
- `SlaBreachPolicyBean` in `devtown-app` — CDI displacement of `NoOpSlaBreachPolicy`
- `SlaBreachHandler` in `devtown-app` — `SlaBreachEvent` observer that signals the case on Fail
- `pr-review.yaml` — `candidateGroups` and `expiresIn` on `human-approval` binding

---

## SPI location (reconciled from draft)

The draft assumed `SlaBreachPolicy`, `BreachDecision`, `SlaBreachContext`, `BreachType`, and
`BreachedTask` would live in `casehub-platform/apps-api`. They shipped in `casehub-work-api`
(pure Java, depends only on `casehub-platform-api`). `devtown-domain` can depend on
`casehub-work-api` without violating its pure-Java tier constraint.

`SlaBreachEvent` (`casehub-work-runtime`) carries the leaf `BreachDecision` that actually
executed. Devtown observes this event — not `WorkItemLifecycleEvent` — to signal the case.

---

## Escalation mechanics (why no SlaBreachHandler on WorkItemLifecycleEvent)

`ExpiryLifecycleService` already:
1. Builds `SlaBreachContext` from the expired WorkItem
2. Calls `slaBreachPolicy.onBreach(ctx)` — devtown's bean is the injected impl
3. Executes the returned `BreachDecision`
4. Fires `SlaBreachEvent(context, leafDecision)` for observers

**EscalateTo path:** `executeEscalateTo` mutates the WorkItem in-place: `status = PENDING`,
`candidateGroups = {escalation-group}`, new `expiresAt`. The "ESCALATED" lifecycle event fires
with `workItem.status = PENDING`. `WorkItemLifecycleAdapter` ignores it (only handles
COMPLETED/REJECTED/CANCELLED/EXPIRED/ESCALATED status values — PENDING is none of these).
The PlanItem stays ACTIVE. The case waits. No CONTEXT_CHANGED fires from the adapter.

**Fail path (second expiry):** `executeFail` sets `workItem.status = EXPIRED`,
`workItem.resolution = fail.reason()` (plain string, not JSON). The "EXPIRED" lifecycle event
fires. The adapter calls `item.markFaulted()` on the PlanItem and fires CONTEXT_CHANGED — but
`applyOutputMapping` fails silently (resolution is not valid JSON) so the context is not updated.
`SlaBreachEvent` fires. `SlaBreachHandler` observes it, extracts caseId from
`callerRef` via `CallerRef.parse()`, and calls
`caseHub.signal(caseId, "humanApproval", Map.of("status", fail.reason()))`.
A second CONTEXT_CHANGED fires with the updated context. The failure goal evaluates.

---

## Module structure

```
casehubio/devtown
  domain/pom.xml          add casehub-work-api dependency
  domain/src/…/sla/
    SlaPreferenceKeys       typed preference keys
    DefaultSlaBreachPolicy  pure Java SlaBreachPolicy impl
  app/src/…/
    SlaBreachPolicyBean     @ApplicationScoped (no @DefaultBean) — displaces NoOpSlaBreachPolicy
    SlaBreachHandler        @ApplicationScoped — observes SlaBreachEvent, signals case
```

No new modules. No new SPIs. `review/` is unchanged.

---

## SlaPreferenceKeys

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

`StringPreference` and `IntPreference` are records wrapping a single typed value and
implementing `SingleValuePreference` — defined in `devtown-domain` alongside the keys.
These are devtown's wrappers over the platform preference value type.

---

## DefaultSlaBreachPolicy

Pure Java, no CDI. Stateless two-tier escalation via `candidateGroups` inspection:

```java
public class DefaultSlaBreachPolicy implements SlaBreachPolicy {
    @Override
    public BreachDecision onBreach(SlaBreachContext ctx) {
        Preferences p = ctx.preferences();
        String escalationGroup = p.getOrDefault(SlaPreferenceKeys.ESCALATION_GROUP).value();
        String terminalReason  = p.getOrDefault(SlaPreferenceKeys.BREACH_TERMINAL_REASON).value();
        int    escalationHours = p.getOrDefault(SlaPreferenceKeys.ESCALATION_HOURS).value();

        if (escalationGroup.isBlank()) {
            return new BreachDecision.Fail("escalation-group-not-configured");
        }
        if (ctx.task().candidateGroups().contains(escalationGroup)) {
            // Already escalated — this is the terminal breach
            return new BreachDecision.Fail(terminalReason);
        }
        return EscalateTo.to(escalationGroup)
                .withDeadline(Duration.ofHours(escalationHours));
    }
}
```

The stateless design: `candidateGroups` is the tier indicator. No state storage or decision
serialization. See GE-20260522-f7db12.

---

## SlaBreachPolicyBean

```java
@ApplicationScoped  // no @DefaultBean — displaces casehub-work's NoOpSlaBreachPolicy
public class SlaBreachPolicyBean extends DefaultSlaBreachPolicy {}
```

CDI displacement: `NoOpSlaBreachPolicy` in `casehub-work` is `@ApplicationScoped @DefaultBean`.
This bean, lacking `@DefaultBean`, takes CDI priority and is the single active implementation.
Follows the `@DefaultBean` displacement pattern used throughout devtown.

---

## SlaBreachHandler

```java
@ApplicationScoped
public class SlaBreachHandler {

    @Inject PrReviewCaseHub caseHub;

    void onBreach(@Observes SlaBreachEvent event) {
        CallerRef ref = CallerRef.parse(event.context().task().callerRef());
        if (ref == null) return;  // not a case-managed WorkItem

        switch (event.decision()) {
            case BreachDecision.Fail fail ->
                caseHub.signal(ref.caseId(), "humanApproval",
                    Map.of("status", fail.reason()));
            default -> {}  // EscalateTo: WorkItem is in-place reassigned, case waits
                           // Extend: deferred (work#211)
        }
    }
}
```

`CallerRef` is from `casehub-engine-work-adapter` (already in `app/` deps).
`caseHub.signal()` sets a path in the case context and fires CONTEXT_CHANGED internally.

---

## pr-review.yaml changes

```yaml
- name: human-approval
  on: { contextChange: {} }
  when: ".pr.linesChanged > .policy.humanApprovalThreshold and .humanApproval == null"
  humanTask:
    title: "PR approval required"
    candidateGroups: [pr-reviewers]
    expiresIn: PT24H
    outputMapping: "{ humanApproval: . }"
```

**Failure goal (deferred — engine#326):**
```yaml
- name: pr-sla-breached
  kind: failure
  condition: '.humanApproval.status == "sla-breach"'
```

Without engine#326, the case stalls on SLA breach (merge binding condition requires
`.humanApproval.status == "approved"` — never met). Acceptable for Layer 2; the case
does not reach a false-positive success.

---

## Testing

### Unit — DefaultSlaBreachPolicyTest

Plain JUnit, no Quarkus. `MapPreferences` directly.
- First breach (candidateGroups={pr-reviewers}): → `EscalateTo` to pr-leads, 8h deadline
- Second breach (candidateGroups={pr-leads}): → `Fail("sla-breach")`
- Blank escalation group configured: → `Fail("escalation-group-not-configured")`
- Default preference values require no property override

### SPI wiring — SlaBreachHandlerWiringTest

`@QuarkusTest`. `@Alternative @ApplicationScoped` `CapturingBreachPolicy` (static inner class,
no `@DefaultBean`) captures `SlaBreachContext`. Verifies policy is injected by
`ExpiryLifecycleService` and that bean displaces NoOp. Follows
`spi-testing-alternative-inner-classes.md`.

### Integration — SlaBreachLifecycleTest

Full two-tier breach as `@QuarkusTest`. Pre-seeded per PP-20260521-134c38.
`MemoryPlanItemStore` in `quarkus.arc.selected-alternatives` (PP-20260521-a36692).
`ExpiryCleanupJob` disabled in test properties (scheduler-test-isolation.md).

Checkpoints:
1. Start PR review case with PR exceeding approval threshold
2. WorkItem created by `human-approval` binding (candidateGroups=pr-reviewers)
3. Set `expiresAt` to past; call `expiryService.checkExpired()`
4. WorkItem mutated in-place: candidateGroups=pr-leads, status=PENDING, new expiresAt
5. Case context unchanged (no CONTEXT_CHANGED with case signal fired yet)
6. Set `expiresAt` to past again; call `expiryService.checkExpired()`
7. Case context: `humanApproval.status == "sla-breach"` (via `SlaBreachHandler`)

---

## Deferred concerns

| Issue | What it unblocks |
|-------|-----------------|
| engine#326 — failure goal support | Active case FAILED state on SLA breach |
| engine#325 — `HumanTaskTarget.claimDeadlineHours` | Declarative claim SLA in YAML |
| engine#327 — dynamic `expiresIn` | Per-instance SLA thresholds from `PreferenceProvider` |
| work#211 — `WorkItemService.extend()` | `BreachDecision.Extend` execution |

---

## What this closes

Layer 1 gap: `// LAYER 1 GAP: no response SLA — analysis can stall indefinitely with no escalation.`

After Layer 2: every PR review WorkItem has a formal deadline (24h), routes to `pr-reviewers`,
escalates to `pr-leads` on breach, and signals the case context on terminal failure — observable
and traceable from case history.
