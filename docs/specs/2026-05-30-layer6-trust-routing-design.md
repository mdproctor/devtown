# Layer 6 — Trust-Weighted Reviewer Routing

**Issue:** devtown#57  
**Date:** 2026-05-30  
**Branch:** issue-57-layer6-trust-routing

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

---

## Trust Dimension Rename

`DevtownTrustDimension.FALSE_POSITIVE_RATE = "false-positive-rate"` is renamed to
`PRECISION = "precision"`.

**Why:** Industry convention (Bayesian reputation systems, ML evaluation frameworks) normalises
all trust dimensions to higher = better. `false-positive-rate` as a raw rate has inverted
semantics — higher means worse. Stored as precision (TP/(TP+FP)), higher = fewer false positives
= better. The rename is free: no production trust score data exists against this dimension yet.

All three devtown dimensions are now consistently higher = better:
- `review-thoroughness` — recall semantics; finds more real issues
- `precision` — inverted FPR; fewer false positives
- `scope-calibration` — correct DECLINE rate; boundary-respect accuracy

---

## Per-Capability Routing Policies

| Capability | threshold | minObs | borderlineMargin | blendFactor | qualityFloors |
|---|---|---|---|---|---|
| `security-review` | 0.70 | 10 | 0.05 | 0.70 | `review-thoroughness ≥ 0.60` |
| `architecture-review` | 0.65 | 8 | 0.05 | 0.70 | `review-thoroughness ≥ 0.60` |
| `style-review` | 0.50 | 5 | 0.0 | 0.50 | — |
| `merge-executor` | 0.80 | 15 | 0.05 | 0.80 | `precision ≥ 0.70` |
| others | DEFAULT | — | — | — | — |

**Rationale:**
- `blendFactor` is higher for irreversible/critical capabilities (trust dominates workload),
  lower for baseline reviews (any qualified agent is adequate)
- Quality floor on `review-thoroughness` for security and architecture: agents that miss real
  issues are excluded even if their aggregate CAPABILITY score passes the threshold
- Quality floor on `precision` for merge-executor: irreversible operations require low
  false-positive rate; a merge executor that blocks valid merges is dangerous
- `style-review` borderlineMargin = 0.0: low-stakes; no human oversight escalation needed
  for borderline scores
- `scope-calibration` not used as a floor in this layer — it maps to DECLINE behaviour
  captured through the Qhorus commitment lifecycle; adding it as a routing floor would
  double-count until the Qhorus trust gate is wired (devtown#58)
- Cold start: quality floors are inert when no dimension data exists (no data = no penalty,
  per `TrustCandidateClassifier` Phase 3 semantics)

---

## Policy Configuration — YAML File

Policies live in `src/main/resources/casehub/devtown/trust-routing.yaml`, loaded at startup
by `casehub-platform-config`. Values are externally tunable without recompilation.

**Format:** one typed `PreferenceKey<T>` per field, resolved against
`SettingsScope.of("casehubio", "devtown", "trust-routing", capabilityName)`.
This replaces AML's broken `UnsupportedOperationException` single-string pattern.
AML cleanup tracked in parent#115.

```yaml
# devtown trust routing policies
# All scores 0.0–1.0 where higher = better (industry-standard trust dimension convention)
entries:
  - scope: casehubio/devtown/trust-routing/security-review
    casehubio.devtown.trust-routing.threshold: "0.70"
    casehubio.devtown.trust-routing.min-observations: "10"
    casehubio.devtown.trust-routing.borderline-margin: "0.05"
    casehubio.devtown.trust-routing.blend-factor: "0.70"
    casehubio.devtown.trust-routing.floor.review-thoroughness: "0.60"

  - scope: casehubio/devtown/trust-routing/architecture-review
    casehubio.devtown.trust-routing.threshold: "0.65"
    casehubio.devtown.trust-routing.min-observations: "8"
    casehubio.devtown.trust-routing.borderline-margin: "0.05"
    casehubio.devtown.trust-routing.blend-factor: "0.70"
    casehubio.devtown.trust-routing.floor.review-thoroughness: "0.60"

  - scope: casehubio/devtown/trust-routing/style-review
    casehubio.devtown.trust-routing.threshold: "0.50"
    casehubio.devtown.trust-routing.min-observations: "5"
    casehubio.devtown.trust-routing.borderline-margin: "0.0"
    casehubio.devtown.trust-routing.blend-factor: "0.50"

  - scope: casehubio/devtown/trust-routing/merge-executor
    casehubio.devtown.trust-routing.threshold: "0.80"
    casehubio.devtown.trust-routing.min-observations: "15"
    casehubio.devtown.trust-routing.borderline-margin: "0.05"
    casehubio.devtown.trust-routing.blend-factor: "0.80"
    casehubio.devtown.trust-routing.floor.precision: "0.70"
```

---

## Module Placement

### `domain/` — rename only
- `DevtownTrustDimension`: `FALSE_POSITIVE_RATE → PRECISION`, `"false-positive-rate" → "precision"`
- `DevtownTrustDimensionTest`: update assertion

### `review/` — two new classes
- `DevtownTrustRoutingPolicyProvider` — `@ApplicationScoped @Alternative @Priority(1)`,
  implements `TrustRoutingPolicyProvider`. Injects `PreferenceProvider`. Resolves
  per-capability scope, builds `TrustRoutingPolicy` from per-field `PreferenceKey`
  resolution. Falls back to `TrustRoutingPolicy.DEFAULT` when no YAML entry found.
- `TrustRoutingPolicyKeys` — package-private constant class holding the six `PreferenceKey`
  statics. Not a CDI bean.

### `app/` — dependencies and config
**`pom.xml` additions:**
- `casehub-engine-ledger` compile — activates `TrustWeightedAgentStrategy @Alternative @Priority(1)`
  and `WorkerDecisionEventCapture` by classpath presence
- `casehub-platform-config` compile — activates `ConfigFilePreferenceProvider`, displaces
  `MockPreferenceProvider @DefaultBean`

**`src/main/resources/application.properties` additions:**
```properties
casehub.platform.config.files=classpath:casehub/devtown/trust-routing.yaml
quarkus.flyway.qhorus.locations=classpath:db/qhorus/migration,classpath:db/ledger/migration,classpath:db/engine-ledger/migration
quarkus.index-dependency.engine-ledger.group-id=io.casehub
quarkus.index-dependency.engine-ledger.artifact-id=casehub-engine-ledger
```

**`src/test/resources/application.properties` addition:**
```properties
quarkus.index-dependency.engine-ledger.group-id=io.casehub
quarkus.index-dependency.engine-ledger.artifact-id=casehub-engine-ledger
```

**`src/main/resources/casehub/devtown/trust-routing.yaml`** — the policy file above.

---

## CDI Wiring

No explicit wiring required. CDI priority chain is automatic:
- `TrustWeightedAgentStrategy @Alternative @Priority(1)` beats `LeastLoadedAgentStrategy @Priority(0)`
- `DevtownTrustRoutingPolicyProvider @Alternative @Priority(1)` beats `DefaultTrustRoutingPolicyProvider @DefaultBean`
- `ConfigFilePreferenceProvider @ApplicationScoped` displaces `MockPreferenceProvider @DefaultBean`

No `quarkus.arc.selected-alternatives` entries — that pattern is explicitly wrong for this use case.

---

## Flyway and Hibernate

`casehub-engine-ledger` ships V2000 (`case_ledger_entry`) and V2001 (`worker_decision_entry`)
at `classpath:db/engine-ledger/migration`. These are ledger subclass join tables on the
`qhorus` datasource. Explicit Flyway location required (not auto-registered — engine-ledger
is a plain jar, not a Quarkus extension).

Hibernate package `io.casehub.ledger.model` already in
`quarkus.hibernate-orm.qhorus.packages` — no change needed.

Do **not** create local V2002/V2003 migrations. AML's local copies are a known error
(pending engine#395 scoping fix).

---

## Tests

### `DevtownTrustDimensionTest` (update, `domain/`)
Update `FALSE_POSITIVE_RATE` → `PRECISION` assertion.

### `DevtownTrustRoutingPolicyProviderTest` (new unit test, `review/`)
Tests provider in isolation with `MockPreferenceProvider`. No Quarkus.
- Capability with full YAML entry → correct `TrustRoutingPolicy` fields
- Capability with no entry → `TrustRoutingPolicy.DEFAULT`
- Quality floor present → included in returned policy
- Quality floor absent → empty `qualityFloors` map
- All six devtown capabilities resolve without exception

### `TrustRoutingActivationTest` (new `@QuarkusTest`, `app/`)
Verifies CDI wiring end-to-end:
- `AgentRoutingStrategy` resolves to `TrustWeightedAgentStrategy`
- `TrustRoutingPolicyProvider` resolves to `DevtownTrustRoutingPolicyProvider`
- `security-review` returns threshold 0.70 and `review-thoroughness` floor 0.60

---

## Out of Scope (filed)

- **devtown#58** — Qhorus trust gate configuration. Needs bootstrap exemption design before
  enabling `casehub.qhorus.commitment.min-obligor-trust`. Implement after this layer.
- **parent#115** — Replace AML hardcoded policy pattern with per-field `PreferenceKey`.
  devtown#57 is the reference implementation.

---

## Protocol Gap

`trust-maturity-model.md` is referenced in `PLATFORM.MD` but does not exist.
Create at `docs/protocols/casehub/trust-maturity-model.md` in `casehub-parent`
during this session.
