# Layer 6 — Trust-Weighted Reviewer Routing

**Issue:** devtown#57  
**Date:** 2026-05-30  
**Branch:** issue-57-layer6-trust-routing

---

## Prerequisites (must exist before implementation)

1. **`DoublePreference` in `domain/trust/`** — mirrors `domain/sla/IntPreference`. Required by
   `TrustRoutingPolicyKeys` for threshold, borderlineMargin, blendFactor, and quality floor fields.
2. **`trust-maturity-model.md` protocol in `casehub-parent`** — referenced in `PLATFORM.MD` but
   absent (tracked: parent#116). Create before implementation.

---

## What This Adds

Layer 6 activates trust-weighted reviewer selection in devtown. The engine already implements
the four-phase trust maturity model (`TrustWeightedAgentStrategy` in `casehub-engine-ledger`);
this layer wires it into devtown with per-capability routing policies that reflect the risk
profile of each review domain.

**Foundation gates (all closed):**
- engine#336 — `SelectionContext` carries trust scores
- engine#337 — `WorkOrchestrator` resolves strategy via CDI priority
- qhorus#199 — `TrustGateService` wired into `MessageService`

**Layer 4 dependency (Phase 0 behaviour):**
`TrustScoreJob` computes trust scores from attestations written by `WorkerDecisionEventCapture`
(part of `casehub-engine-ledger`). Until Layer 4 (ledger audit trail) is wired, no attestations
exist and all agents remain in BOOTSTRAP (Phase 0) — routing is availability-based, identical to
Gastown parity. This is architecturally correct. Layer 6 wires the routing infrastructure; trust
score accumulation begins once Layer 4 is complete.

---

## Trust Dimension Rename

`DevtownTrustDimension.FALSE_POSITIVE_RATE = "false-positive-rate"` is renamed to
`PRECISION = "precision"`.

**Why:** Industry convention (Bayesian reputation systems, ML evaluation frameworks) normalises
all trust dimensions to higher = better. `false-positive-rate` as a raw rate has inverted
semantics — higher means worse. Stored as precision (TP/(TP+FP)), higher = fewer false positives
= better. The rename is free: no production trust score data exists against this dimension yet.

All three devtown dimensions are consistently higher = better:
- `review-thoroughness` — recall semantics; finds more real issues
- `precision` — inverted FPR; fewer false positives
- `scope-calibration` — correct DECLINE rate; boundary-respect accuracy

**`RoutingPolicy.isBorderline()` is dead code with wrong semantics.** The method is one-sided
(`score >= threshold && score < threshold + margin`); the engine's `TrustRoutingPolicy.isBorderline()`
is symmetric (`Math.abs(score - threshold) <= margin`). The routing path exclusively uses
`TrustRoutingPolicy` — `RoutingPolicy.isBorderline()` is never called. Mark `@Deprecated` with
a comment pointing to `TrustRoutingPolicy` as the operative implementation.

---

## Per-Capability Routing Policies

`DevtownCapabilityRegistry.POLICIES` already holds threshold/minimumObservations/borderlineMargin
for the four trust-sensitive capabilities. `DevtownTrustRoutingPolicyProvider` reads these fields
from the registry and supplements with `blendFactor` and quality floors from YAML configuration.
Single source of truth in the domain layer — no duplicate values.

| Capability | threshold | minObs | borderlineMargin | blendFactor | qualityFloors |
|---|---|---|---|---|---|
| `security-review` | 0.70 (registry) | 10 (registry) | 0.05 (registry) | 0.70 (YAML) | `review-thoroughness ≥ 0.60` (YAML) |
| `architecture-review` | 0.65 (registry) | 8 (registry) | 0.05 (registry) | 0.70 (YAML) | `review-thoroughness ≥ 0.60` (YAML) |
| `style-review` | 0.50 (registry) | 5 (registry) | 0.0 (registry, `OptionalDouble.empty()`) | 0.50 (YAML) | — |
| `merge-executor` | 0.80 (registry) | 15 (registry) | 0.05 (registry) | 0.80 (YAML) | `precision ≥ 0.70` (YAML) |
| others | DEFAULT | — | — | DEFAULT | — |

**Rationale:**
- `blendFactor` is higher for irreversible/critical capabilities (trust dominates workload),
  lower for baseline reviews (any qualified agent is adequate)
- Quality floor on `review-thoroughness` for security and architecture: agents that miss real
  issues are excluded even if their aggregate CAPABILITY score passes the threshold. Inert during
  cold start — no data = no penalty (per `TrustCandidateClassifier` Phase 3 semantics)
- Quality floor on `precision` for merge-executor: irreversible operations require low
  false-positive rate; a merge executor that blocks valid merges is dangerous
- `scope-calibration` not used as a floor in this layer — maps to DECLINE behaviour captured
  through the Qhorus commitment lifecycle; floor enforcement here would double-count until the
  Qhorus trust gate is wired (devtown#58)

**`style-review` borderlineMargin = 0.0 note:** `RoutingPolicy.borderlineMargin` for style-review
is `OptionalDouble.empty()`, which the provider maps to 0.0 in `TrustRoutingPolicy`. With
borderlineMargin=0.0, `TrustRoutingPolicy.isBorderline()` (`Math.abs(score - 0.50) <= 0.0`) is
true only when score equals exactly 0.50. A candidate at exactly 0.50 is classified BORDERLINE
(score 0.0); if all candidates were borderline, the engine would escalate to oversight. In
practice, Bayesian Beta trust scores are continuous floats computed from Beta(α,β) — realizing
exactly 0.50 is not achievable. This zone is effectively unreachable.

**Bootstrap agent behaviour at merge-executor:** `TrustWeightedAgentStrategy` assigns bootstrap
candidates a positive availability score (`1/(1+runningJobs)`), which always outscores borderline
candidates (score 0.0). Consequently, a new agent with zero decision history beats an established
agent whose trust score is borderline (0.75–0.80 for merge-executor). This is intentional: new
agents need trust-building opportunities, and the engine's Phase 0 availability routing is the
platform's mechanism for earning history. Operators who want to prevent unknown agents from
executing merges must configure `casehub.qhorus.commitment.min-obligor-trust` (devtown#58).

---

## Policy Configuration — YAML File

`blendFactor` and quality floors live in `src/main/resources/casehub/devtown/trust-routing.yaml`,
loaded at startup by `casehub-platform-config`. Threshold/minimumObservations/borderlineMargin
come from `DevtownCapabilityRegistry` — YAML carries only what the registry does not.

**Runtime override:** `casehub.platform.config.files` sets the YAML path (deployed with the
application — requires redeploy to change). For runtime tuning without redeploy, set
`casehub.platform.preferences.defaults.<key>=<value>` via environment variable or Kubernetes
ConfigMap — SmallRye Config overrides apply at highest priority above the YAML file.

```yaml
# devtown trust routing — blendFactor and quality floors only
# threshold / minimumObservations / borderlineMargin come from DevtownCapabilityRegistry
# All dimension scores 0.0–1.0 where higher = better
entries:
  - scope: casehubio/devtown/trust-routing/security-review
    casehubio.devtown.trust-routing.blend-factor: "0.70"
    casehubio.devtown.trust-routing.floor.review-thoroughness: "0.60"

  - scope: casehubio/devtown/trust-routing/architecture-review
    casehubio.devtown.trust-routing.blend-factor: "0.70"
    casehubio.devtown.trust-routing.floor.review-thoroughness: "0.60"

  - scope: casehubio/devtown/trust-routing/style-review
    casehubio.devtown.trust-routing.blend-factor: "0.50"

  - scope: casehubio/devtown/trust-routing/merge-executor
    casehubio.devtown.trust-routing.blend-factor: "0.80"
    casehubio.devtown.trust-routing.floor.precision: "0.70"
```

---

## Module Placement

### `domain/` — new class + rename + deprecation
- `domain/trust/DoublePreference` — mirrors `domain/sla/IntPreference`; implements
  `SingleValuePreference`; provides `of(double)` and `parse(String)`
- `domain/trust/TrustRoutingPolicyKeys` — package-private constant class with four
  `PreferenceKey` statics (blendFactor, floor.review-thoroughness, floor.precision,
  floor.scope-calibration). No CDI.
- `DevtownTrustDimension`: rename `FALSE_POSITIVE_RATE → PRECISION`, `"false-positive-rate" → "precision"`
- `RoutingPolicy.isBorderline()`: add `@Deprecated`; add comment: "use TrustRoutingPolicy.isBorderline() — this implementation is one-sided and not called by routing"
- `DevtownTrustDimensionTest`: update assertion; add inline comment:
  `// precision = TP/(TP+FP); stored as higher = better, unlike raw FPR`

### `app/` — new class
- `DevtownTrustRoutingPolicyProvider` — `@ApplicationScoped` (no `@DefaultBean`, no `@Alternative`).
  Displaces `DefaultTrustRoutingPolicyProvider @DefaultBean` automatically. Injects
  `PreferenceProvider` and `CapabilityRegistry`. Reads threshold/minObs/borderlineMargin from
  `CapabilityRegistry.policy(capability)`; reads blendFactor and quality floors from
  `TrustRoutingPolicyKeys` via preference resolution. Falls back to `TrustRoutingPolicy.DEFAULT`
  for capabilities with no registry entry.

  Quality floor assembly: `if (value != null && value.value() > 0.0) qualityFloors.put(dimension, value.value())`
  — skip floors that are absent or zero.

### `app/pom.xml` — two new compile deps
- `casehub-engine-ledger` — activates `TrustWeightedAgentStrategy @Alternative @Priority(1)`
  and `WorkerDecisionEventCapture` by classpath presence
- `casehub-platform-config` — activates `ConfigFilePreferenceProvider`, displaces
  `MockPreferenceProvider @DefaultBean`

### `app/src/main/resources/`
- `casehub/devtown/trust-routing.yaml` — the policy file above
- `application.properties` additions:

```properties
casehub.platform.config.files=classpath:casehub/devtown/trust-routing.yaml
quarkus.flyway.qhorus.locations=classpath:db/qhorus/migration,classpath:db/ledger/migration,classpath:db/engine-ledger/migration
quarkus.index-dependency.engine-ledger.group-id=io.casehub
quarkus.index-dependency.engine-ledger.artifact-id=casehub-engine-ledger
```

### `app/src/test/resources/application.properties` addition:

```properties
quarkus.index-dependency.engine-ledger.group-id=io.casehub
quarkus.index-dependency.engine-ledger.artifact-id=casehub-engine-ledger
```

---

## CDI Wiring

No explicit wiring required. CDI priority chain is automatic:
- `TrustWeightedAgentStrategy @Alternative @Priority(1)` beats `LeastLoadedAgentStrategy @Priority(0)`
- `DevtownTrustRoutingPolicyProvider @ApplicationScoped` displaces `DefaultTrustRoutingPolicyProvider @DefaultBean`
- `ConfigFilePreferenceProvider @ApplicationScoped` displaces `MockPreferenceProvider @DefaultBean`

No `quarkus.arc.selected-alternatives` entries.

---

## Flyway and Hibernate

`casehub-engine-ledger` ships V2000 (`case_ledger_entry`) and V2001 (`worker_decision_entry`)
at `classpath:db/engine-ledger/migration`. These are ledger subclass join tables on the
`qhorus` datasource. Explicit Flyway location required — engine-ledger is a plain jar, not a
Quarkus extension, and does not auto-register migrations.

Hibernate package `io.casehub.ledger.model` already in
`quarkus.hibernate-orm.qhorus.packages` — no change needed.

Do **not** create local V2002/V2003 migrations. AML's local copies are a known error
(pending engine#395 scoping fix).

---

## Tests

### `DevtownTrustDimensionTest` (update, `domain/`)
Update assertion: `FALSE_POSITIVE_RATE → PRECISION`, add inline semantic comment.

### `DevtownTrustRoutingPolicyProviderTest` (new unit test, `app/`)
Tests provider in isolation. No Quarkus. Two categories:

**Fallback path (MockPreferenceProvider returning null/defaults):**
- `security-review` → threshold 0.70, minObs 10, borderlineMargin 0.05 (from registry)
- Capability with no registry entry → `TrustRoutingPolicy.DEFAULT`
- Quality floor absent → empty `qualityFloors` map

**Parsing path (MockPreferenceProvider returning specific test values):**
- Capability with blendFactor=0.42, floor.review-thoroughness=0.88 → correct assembly
- Verifies parsing and field-to-policy assembly independent of YAML loading

### `TrustRoutingActivationTest` (new `@QuarkusTest`, `app/`)
Verifies CDI wiring and YAML loading end-to-end:
- `AgentRoutingStrategy` resolves to `TrustWeightedAgentStrategy`
- `TrustRoutingPolicyProvider` resolves to `DevtownTrustRoutingPolicyProvider`
- **YAML proof:** `style-review` threshold = 0.50 (differs from DEFAULT 0.70) — proves YAML loaded
- **Negative check:** `architecture-review` threshold = 0.65 ≠ DEFAULT 0.70
- `security-review` has `review-thoroughness` quality floor 0.60

---

## Out of Scope (filed)

- **devtown#58** — Qhorus trust gate configuration. Needs bootstrap exemption design before
  enabling `casehub.qhorus.commitment.min-obligor-trust`. Note: bootstrap agent at merge-executor
  behaviour (see above) is intentional until this gate is configured. Implement after this layer.
- **parent#115** — Replace AML hardcoded policy pattern with per-field `PreferenceKey`.
  devtown#57 is the reference implementation. AML's `TrustPolicyPreference` record can be deleted.
- **parent#116** — Create `trust-maturity-model.md` protocol in `casehub-parent/docs/protocols/casehub/`.
  Referenced in `PLATFORM.MD` but absent.
