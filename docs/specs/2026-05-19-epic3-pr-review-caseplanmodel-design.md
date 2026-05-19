# Epic 3: PR Review CasePlanModel Design

**Issue:** devtown#10
**Date:** 2026-05-19
**Layer:** 5 — casehub-engine (adaptive routing on code content)

---

## Architecture

The PR review case definition follows the three-layer architecture prescribed by
`casehub-parent/docs/protocols/casehub/case-definition-layers.md`:

- **YAML** (`review/src/main/resources/devtown/pr-review.yaml`) — runtime artifact,
  configurable per-repo without redeployment
- **`CaseDefinitionYamlMapper`** — converts YAML to canonical `CaseDefinition`
- **Canonical model** (`io.casehub.api.model.CaseDefinition`) — what the engine operates on
- **Fluent DSL** — used in tests only; supports `LambdaExpressionEvaluator` which YAML cannot express

`PrReviewCaseService` (`@ApplicationScoped`, no `@DefaultBean`) displaces
`NaivePrReviewService` (`@DefaultBean`) at CDI resolution time — the Layer 1 teaching
baseline is never deleted, just made inactive.

### Module placement

```
review/src/main/resources/devtown/pr-review.yaml
review/src/main/java/.../review/PrReviewCaseHub.java         extends YamlCaseHub
review/src/main/java/.../review/PrReviewCaseService.java     @ApplicationScoped, no @DefaultBean
review/src/test/java/.../review/PrReviewCaseDefinition.java  fluent DSL factory, test-only
review/src/test/java/.../review/PrReviewBindingConditionTest.java
review/src/test/java/.../review/PrReviewCaseHubTest.java     @QuarkusTest
```

---

## CaseContext Schema

Built by `PrReviewCaseService` at case-open time. Two top-level objects injected
into the initial context:

```json
{
  "pr": {
    "id": "456",
    "repo": "casehubio/devtown",
    "linesChanged": 342,
    "baseRef": "main",
    "headSha": "abc123"
  },
  "policy": {
    "humanApprovalThreshold": 500,
    "securityReviewRequired": true,
    "requireSeniorApproval": false
  }
}
```

Workers write results back to the context. Full schema after a complete run:

```json
{
  "pr": { ... },
  "policy": { ... },
  "codeAnalysis": {
    "complete": true,
    "securitySensitive": true,
    "architectureCrossing": false,
    "findings": {}
  },
  "securityReview":      { "outcome": "APPROVED | DECLINED | FAILED" },
  "architectureReview":  { "outcome": "APPROVED | DECLINED | FAILED" },
  "styleCheck":          { "outcome": "APPROVED | DECLINED | FAILED" },
  "testCoverage":        { "outcome": "APPROVED | DECLINED | FAILED" },
  "performanceAnalysis": { "outcome": "APPROVED | DECLINED | FAILED" },
  "ci":                  { "status": "passing | failing | pending" },
  "humanApproval":       { "status": "approved | rejected" }
}
```

All agent-performed checks use `outcome` (three-value enum). This is intentional:
a style linter that doesn't support the PR's language DECLINES rather than failing.
Epic 7 (devtown#14) routes differently on DECLINED vs FAILED — the schema supports
this from day one. `ci` uses `status` (external system, not a capability agent).
`humanApproval` uses `status` (WorkItem resolution semantics).

---

## Goals and Completion

```yaml
goals:
  - name: pr-approved
    kind: success
    condition: >
      .securityReview.outcome == "APPROVED" and
      (.codeAnalysis.architectureCrossing == false or .architectureReview.outcome == "APPROVED") and
      .styleCheck.outcome == "APPROVED" and
      .testCoverage.outcome == "APPROVED" and
      .performanceAnalysis.outcome == "APPROVED"

  - name: security-verified
    kind: success
    condition: >
      .codeAnalysis.securitySensitive == false or
      .securityReview.outcome == "APPROVED"

  - name: ci-passing
    kind: success
    condition: ".ci.status == \"passing\""

completion:
  success:
    allOf:
      - pr-approved
      - security-verified
      - ci-passing
```

Architecture review is folded into `pr-approved` with a conditional expression
(`.codeAnalysis.architectureCrossing == false or ...`) because the binding only fires
when analysis finds a cross-API change. Making it a standalone goal would leave it
permanently unsatisfied on PRs where it never fires.

---

## Bindings

Nine bindings in four groups. All trigger on `contextChange` — automatic parallelism
means all satisfied bindings fire simultaneously on every context update.

### Group 1 — Entry (fire immediately on PR arrival, in parallel)

```yaml
- name: initial-analysis
  on: { contextChange: {} }
  when: ".pr != null and .codeAnalysis == null"
  capability: code-analysis

- name: run-ci
  on: { contextChange: {} }
  when: ".pr != null and .ci == null"
  capability: ci-runner
```

### Group 2 — Content-driven (fire after analysis completes, all in parallel)

```yaml
- name: security-review
  on: { contextChange: {} }
  when: ".codeAnalysis.complete == true and .codeAnalysis.securitySensitive == true and .securityReview == null"
  capability: security-review

- name: architecture-review
  on: { contextChange: {} }
  when: ".codeAnalysis.complete == true and .codeAnalysis.architectureCrossing == true and .architectureReview == null"
  capability: architecture-review

- name: style-check
  on: { contextChange: {} }
  when: ".codeAnalysis.complete == true and .styleCheck == null"
  capability: style-review

- name: test-coverage
  on: { contextChange: {} }
  when: ".codeAnalysis.complete == true and .testCoverage == null"
  capability: test-coverage

- name: performance-analysis
  on: { contextChange: {} }
  when: ".codeAnalysis.complete == true and .performanceAnalysis == null"
  capability: performance-analysis
```

Style, test-coverage, and performance-analysis fire simultaneously — automatic
parallelism from the binding system. No explicit parallel declaration needed.

### Group 3 — Human gate (fires alongside CI when PR exceeds threshold)

```yaml
- name: human-approval
  on: { contextChange: {} }
  when: ".pr.linesChanged > .policy.humanApprovalThreshold and .humanApproval == null"
  humanTask:
    title: "PR #{{ .pr.id }} requires senior approval"
    candidateGroups: [ "senior-architects" ]
    expiresIn: PT48H
```

Fires immediately if the PR exceeds the threshold — in parallel with CI, not after it.
Total time = max(human review, CI), not sum. The threshold is configurable via
`devtown.policy.human-approval-threshold` (default 500 lines).

HITL wiring gap: `casehub-work-adapter` is not yet configured for devtown — the
WorkItem is created but the case does not resume automatically on human completion.
See devtown#30 for the follow-up integration test once wiring lands.

### Group 4 — Merge (fires when all conditions are satisfied)

```yaml
- name: merge
  on: { contextChange: {} }
  when: >
    .securityReview.outcome == "APPROVED" and
    (.codeAnalysis.architectureCrossing == false or .architectureReview.outcome == "APPROVED") and
    .styleCheck.outcome == "APPROVED" and
    .testCoverage.outcome == "APPROVED" and
    .performanceAnalysis.outcome == "APPROVED" and
    (.pr.linesChanged <= .policy.humanApprovalThreshold or .humanApproval.status == "approved") and
    .ci.status == "passing"
  capability: merge-executor
```

---

## Scoped Settings Stub

Policy values come from `@ConfigProperty` until `casehub-platform-api` (parent#26)
delivers `PreferenceProvider`. The CaseContext shape does not change when the SPI
arrives — only the injection point in `PrReviewCaseService` changes.

```java
@ConfigProperty(name = "devtown.policy.human-approval-threshold", defaultValue = "500")
int humanApprovalThreshold;

@ConfigProperty(name = "devtown.policy.security-review-required", defaultValue = "true")
boolean securityReviewRequired;

@ConfigProperty(name = "devtown.policy.require-senior-approval", defaultValue = "false")
boolean requireSeniorApproval;
// TODO(parent#26): replace with PreferenceProvider.resolve(scope).asMap()
```

---

## Testing Strategy

### Tier 1 — Binding condition unit tests (`PrReviewBindingConditionTest`)

Pure Java, no Quarkus, no YAML parsing. Uses `PrReviewCaseDefinition` fluent DSL
factory with `LambdaExpressionEvaluator`. Tests that each binding fires at exactly
the right moment.

Required cases:
- `initialAnalysis` fires when PR arrives and no analysis yet
- `initialAnalysis` does not fire again when analysis already present
- `securityReview` fires only when `securitySensitive == true`
- `securityReview` does not fire when `securitySensitive == false`
- `architectureReview` fires only when `architectureCrossing == true`
- `styleCheck`, `testCoverage`, `performanceAnalysis` all fire when analysis complete
- `humanApproval` fires only when `linesChanged > threshold`
- `humanApproval` does not fire when `linesChanged <= threshold`
- `merge` does not fire until all checks approved and CI passing
- `merge` fires when all conditions simultaneously satisfied
- `merge` does not fire when `architectureCrossing == true` but `architectureReview` absent

### Tier 2 — YAML round-trip (`PrReviewCaseHubTest`)

Single `@QuarkusTest`. Verifies the YAML parses cleanly and the definition has the
expected structure.

```java
assertThat(def.getBindings()).hasSize(9);
assertThat(def.getGoals()).hasSize(3);
assertThat(def.getCapabilities()).hasSize(8);  // code-analysis, security-review,
                                                // architecture-review, style-review,
                                                // test-coverage, performance-analysis,
                                                // ci-runner, merge-executor
```

---

## Foundation Gates and Deferred Concerns

| Concern | Status | Tracked |
|---------|--------|---------|
| casehub-engine P0 (engine#186) | ✅ Done | — |
| HITL wiring (`casehub-work-adapter`) | ⚠️ Gap — bindings defined, integration test deferred | devtown#30 |
| Scoped preferences SPI | ⚠️ `@ConfigProperty` stub — replace when parent#26 lands | parent#26 |
| Pluggable expression evaluator factory | ⚠️ Engine gap — mapper hardcodes JQ | engine#289 |
| Trust-weighted worker selection | 🔲 Epic 6 | devtown#13 |
| DECLINED/FAILED routing | 🔲 Epic 7 | devtown#14 |

---

## Out of Scope

- Failure bindings (DECLINED/FAILED routing and backup agent cascading) — Epic 7
- Trust-weighted reviewer routing — Epic 6
- GitHub webhook integration (PR arrival trigger) — Epic 8
- Merge queue (casehub-refinery) — Epic 4
