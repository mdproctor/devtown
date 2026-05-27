# devtown — Slice-Indexed Architecture Log (SIAL)

This is devtown's LAYER-LOG.md, structured as a SIAL. It serves two purposes:

**1 — LLM replication and teaching.** An LLM reading the layer entries should be able
to reproduce every layer in a different domain harness without asking questions. Each
entry captures what was built, the non-obvious wiring, what went wrong, and
domain-agnostic steps to replicate the pattern.

**2 — Planning and architectural navigation.** The Vertical Slice Index below shows
what the system can DO at each milestone, which architectural patterns are in play, and
how to navigate to the rationale. Enter from a capability (slice) to find the
implementation detail, or enter from a layer to find the architectural context.

**Build approach:** Layer ordering here is for reading — it is the sequence in which
a developer encounters the layers to understand the system. Building follows vertical
slices: identify a slice (a user-visible capability), then implement each layer that
slice requires, one at a time, until the slice is working end-to-end. Layers are the
implementation unit; slices are the planning and delivery unit. Layers 1 and 5 were
built before this guidance existed — the index below retrospectively presents the
correct planning structure.

**Protocol:** `../parent/docs/protocols/universal/vertical-slice-planning.md`

**Architectural references:**
- `../parent/docs/ARCHITECTURE.md` — pattern definitions (Hexagonal, Clean, DDD, Event-Driven, CQRS-lite)
- `../parent/docs/PLATFORM.md` — capability ownership; boundary rules
- `docs/gastown-casehub-analysis-v2.md` — 32-finding Gastown comparison; phase gates
- `docs/orchestration-advantages.md` — 7 ACM advantages over workflow engines
- `../parent/docs/tutorial-strategy.md §7.5` — teaching objectives per layer
- `../aml/LAYER-LOG.md` — AML reference implementation

**Session artifacts:**
- Blog entries: workspace `blog/`
- Design specs: project `docs/specs/`
- Decision record: workspace `DESIGN.md`
- Improvement log: `docs/PROGRESS.md` (DT-NNN entries)

---

## Vertical Slices

| Slice | Capability delivered | Layers | Arch patterns | Status |
|---|---|---|---|---|
| S1 | `POST /api/reviews` → CasePlanModel opens → content-driven routing fires → outcome returned | L1, L5 | Clean, Hexagonal, DDD, Event-Driven | ✅ complete |
| S2 | S1 + human review WorkItem created with SLA; breach escalates when reviewer misses deadline | + L2 | + Strategy, Observer | ✅ complete |
| S3 | S2 + typed COMMAND dispatched to each specialist agent; DECLINE is a formal scope boundary, not an error | + L3 | + Observer | 🔲 pending |
| S4 | S3 + tamper-evident ledger entry per case transition; production incident traceable to review decision | + L4 | + Event-Driven (async ledger capture) | 🔲 pending |
| S5 | S4 + trust-weighted specialist selection from post-merge outcome attestations | + L6 | + Registry, Strategy | 🔲 pending |

**Ordering rationale:**
- S1 before S2: engine runtime established in S1; WorkItem adapter depends on casehub-work-adapter which chains onto engine events
- S2 before S3: SLA and human gate (S2) before formal obligation per agent (S3) — obligation tracking assumes the accountability infrastructure is in place
- S3 before S4: qhorus messaging generates the MessageLedgerEntry chain that makes S4's tamper-evident audit meaningful
- S4 before S5: trust scoring reads attestation data written by ledger — S4 is a hard dependency for S5
- S1/S5 built out of teaching order (S5 before S2–S4): engine CasePlanModel was the architectural priority; vertical slice practice accepts this — LAYER-LOG presents teaching order

---

## Layer 1 — Domain baseline (no CaseHub foundation)

**Participates in:** S1, S2, S3, S4, S5
**Architectural pattern:** Clean (dependency rule — pure Java domain with zero framework imports); Hexagonal (PrReviewApplicationService port; adapters in `app/`) — `../parent/docs/ARCHITECTURE.md §Dependency Rule, §Foundation`
**Key protocols:** `module-tier-structure.md` (three-tier: domain / review / app), `alternative-extension-patterns.md` (@DefaultBean displacement)
**Design refs:** `docs/specs/2026-05-15-layer1-partb-naive-service-design.md`; `docs/gastown-casehub-analysis-v2.md §DT-001` (vocabulary split rationale)
**Completed:** Epic 1 (scaffold) 2026-05-08 `10d0d42`; Epic 2 (vocabulary) 2026-05-11 `ccbe944`; Part B (baseline service) 2026-05-15 `cca6acc` + `18b22e0`
**Issues:** casehubio/devtown#8 (Epic 1), casehubio/devtown#9 (Epic 2), casehubio/devtown#27 (Part B — baseline service)
**Navigation:** `git log --grep="#27" --oneline`
**Design specs:** `docs/specs/2026-05-07-epic1-scaffold-design.md`, `docs/specs/2026-05-08-epic2-domain-model-design.md`, `docs/specs/2026-05-15-layer1-partb-naive-service-design.md`
**Blog:** `blog/2026-05-11-mdp01-the-vocabulary-problem.md` — narrative on the vocabulary split decision
**Improvement log:** `docs/PROGRESS.md` DT-001 through DT-006

**Key files:**
- `domain/src/main/java/io/casehub/devtown/domain/ReviewDomain.java` — 6 analytical review capability constants
- `domain/src/main/java/io/casehub/devtown/domain/AgentQualification.java` — 2 execution capability constants
- `domain/src/main/java/io/casehub/devtown/domain/HumanDecision.java` — formal PR accountability event constant
- `domain/src/main/java/io/casehub/devtown/domain/HumanOversight.java` — routing uncertainty constant
- `domain/src/main/java/io/casehub/devtown/domain/DevtownTrustDimension.java` — 3 quality dimension constants
- `domain/src/main/java/io/casehub/devtown/domain/RoutingPolicy.java` — configurable routing policy record with `isBootstrap()` and `isBorderline()`
- `domain/src/main/java/io/casehub/devtown/domain/DevtownCapabilityRegistry.java` — populated default implementation; 10 capabilities, 4 routing policies
- `domain/src/main/java/io/casehub/devtown/domain/spi/CapabilityRegistry.java` — SPI: `capabilities()`, `policy()`, `isKnown()` default method
- `app/src/main/java/io/casehub/devtown/app/CapabilityRegistryBean.java` — `@ApplicationScoped` CDI wrapper, zero logic
- `app/src/test/java/io/casehub/devtown/app/DevtownBootTest.java` — Quarkus boot + CDI discovery verification
- `review/src/main/java/io/casehub/devtown/review/PrReviewApplicationService.java` — port interface; CDI displacement boundary for Layer 2+
- `review/src/main/java/io/casehub/devtown/review/PrPayload.java` — input record: repo, prNumber, headSha, linesChanged
- `review/src/main/java/io/casehub/devtown/review/PrReviewOutcome.java` — output record: verdict, findings (List<String>)
- `app/src/main/java/io/casehub/devtown/app/PrReviewService.java` — `@ApplicationScoped @DefaultBean`; displaced by Layer 2+
- `app/src/main/java/io/casehub/devtown/app/PrReviewResource.java` — thin REST dispatcher; `POST /api/reviews`
- `app/src/test/java/io/casehub/devtown/app/PrReviewServiceTest.java` — plain unit tests; no Quarkus; 3 contract assertions

### What it shows

Layer 1 has two distinct parts. The first (Epics 1–2, done) establishes the devtown vocabulary — a typed split of what Gastown keeps in a flat namespace. The second shows the domain baseline: direct service calls with no accountability, no SLA, no formal obligation. Together they form the baseline everything else improves upon.

**Part A — Vocabulary and scaffold (Epics 1–2, 2026-05-08 to 2026-05-11)**

The domain model is pure Java, no CaseHub dependencies. The vocabulary split is devtown-specific: Gastown's 13 flat capability tags become four typed classes with distinct routing semantics. This is the first architectural decision an adopter faces and the one with the biggest payoff later — once the foundation wires `TrustGateService`, `ActorType`, and `WorkerSelectionStrategy`, the vocabulary is the application-layer expression of what those foundation capabilities can express. See DT-001 through DT-006 in `docs/PROGRESS.md`.

**Part B — Baseline PR review service (2026-05-15, devtown#27)**

`PrReviewService` with `@ApplicationScoped @DefaultBean` makes direct stub calls via private methods. The accountability gaps are documented in LAYER-LOG.md rather than as code comments (see accountability gaps table). `PrReviewResource` exposes `POST /api/reviews` so the layer is runnable with a single HTTP call. This is the teaching baseline — each subsequent layer displaces it at the CDI level via a non-`@DefaultBean @ApplicationScoped` implementation.

### Accountability gaps

| Gap | What breaks | Closed by |
|-----|-------------|-----------|
| No attribution | Which agent ran this analysis? No record. | Layer 5 (CaseLedgerEntry per binding dispatch) |
| No response SLA | Analysis can stall indefinitely with no escalation. | Layer 2 (casehub-work claimDeadline) |
| No formal DECLINE | If a specialist can't review, it silently fails or errors. | Layer 5 (DECLINED outcome on blackboard) |
| No tamper-evident audit | Cannot trace a production incident back to this review decision. | Layer 4 (casehub-ledger causedByEntryId chain) |
| No trust weighting | A novice and an expert are treated identically. | Layer 6 (TrustWeightedSelectionStrategy) |

### Key wiring

**Module structure — pure Java domain, Quarkus only in `app/`.**
`devtown-domain` has zero framework dependencies. All constants, SPI, and default implementation live there. `devtown-app` owns all CDI and Quarkus wiring. This split is mandatory for the tutorial to work — each layer needs to show domain logic independently of the framework.

**`@DefaultBean` displacement pattern.**
The baseline service carries `@DefaultBean`. Each subsequent layer adds an `@ApplicationScoped` implementation in `review` without it — CDI displacement means the new one wins, the baseline one stays in the build but is inactive. Both classes coexist; no code is deleted across layers.

```java
// app/PrReviewService.java — Layer 1, always present
@ApplicationScoped
@DefaultBean  // displaced by any @ApplicationScoped impl without @DefaultBean
public class PrReviewService implements PrReviewApplicationService {

// review/PrReviewCaseService.java — Layer 5+, displaces the above
@ApplicationScoped  // no @DefaultBean — takes priority
public class PrReviewCaseService implements PrReviewApplicationService {
```

**Port interface in `review/`, not `app/`.**
`PrReviewApplicationService`, `PrPayload`, and `PrReviewOutcome` live in `review` (not `app`). Layer 2+ implementations also live in `review` (they depend on `casehub-engine-api`, `casehub-work-api`, etc. — already in the `review` pom). If the port lived in `app`, any `review`-module implementation would need to depend on `app`, creating a module dependency cycle. Caught in code review and fixed in commit `18b22e0`. See §Gotchas.

**`CapabilityRegistry` SPI as vocabulary/registry SPI, not no-op.**
Platform protocol distinguishes operational no-op SPIs (empty default) from vocabulary/registry SPIs (populated default). `DevtownCapabilityRegistry` is the latter — the default is a fully populated implementation, not a stub. `CapabilityRegistryBean` is a one-liner `@ApplicationScoped` subclass that promotes it to CDI without any logic duplication.

**`isKnown()` as a default method on the SPI interface.**
Initially written as a concrete method in `DevtownCapabilityRegistry` calling the backing static field directly — not virtual. Commit `ad75f20` fixed it to call `capabilities()` in the implementation; commit `8b3305e` promoted it to a `default` method on the SPI interface. Any subclass overriding `capabilities()` gets a correct `isKnown()` for free. Caught in review — a test specifically for this case (`isKnown` on a subclass that overrides `capabilities()`) would have caught it earlier.

**Vocabulary split rationale.**
13 flat Gastown tags → 4 typed classes. The split emerged from recognising that `security-review`, `batch-bisect`, and `notify` are not the same kind of thing and should not route through the same trust infrastructure. Full rationale: `docs/gastown-casehub-analysis-v2.md` §DT-001; blog `2026-05-11-mdp01-the-vocabulary-problem.md`.

**`BATCH_BISECT` / `COORDINATED_MERGE` / `COORDINATED_ROLLBACK` are not capability tags.**
These were in the original Gastown-derived 13-tag vocabulary and removed. They are orchestration operations — expressed as CasePlanModel binding structures in Epics 4/5, not as trust-scored capability strings. Adding them to `DevtownCapabilityRegistry` would mean routing logic evaluating them as agent assignments, which is wrong. Naming review still open: devtown#20.

### Gotchas

- **`isKnown()` returns wrong results on a subclass**
  - Symptom: a `CapabilityRegistry` subclass that overrides `capabilities()` returns incorrect results from `isKnown()` — capabilities that should be known return false, or vice versa
  - Cause: initial implementation called the backing static field directly, bypassing the virtual `capabilities()` method. Subclass override was silently ignored.
  - Fix: `isKnown()` promoted to a `default` method on the SPI interface calling `capabilities()` (`8b3305e`). Write a test for `isKnown()` on a subclass that overrides `capabilities()` before adding the first subclass.

- **Adding `NOTIFY` to the vocabulary causes nonsensical trust threshold checks**
  - Symptom: `TrustGateService.meetsThreshold()` called with a notification capability tag; threshold evaluation makes no semantic sense for a delivery operation
  - Cause: `NOTIFY` looks like a capability but is a connector call — it has no trust-scored actor and no quality dimension
  - Fix: remove from vocabulary; call `casehub-connectors` directly from the case plan model. `NOTIFY` was explicitly removed from the Gastown-derived 13-tag vocabulary for this reason.

- **Port interface placed in `app/` causes a module dependency cycle in Layer 2**
  - Symptom: `review`-module implementation of `PrReviewApplicationService` cannot be compiled — `review` would need to depend on `app` to access the interface, but `app` already depends on `review`, creating a cycle
  - Cause: `app` is the runtime assembly; it should consume SPIs, not define them. Ports belong in the module that owns the domain boundary (`review`), not the module that wires everything together (`app`)
  - Fix: move port interface and DTOs to `review` module (`18b22e0`). Layer 2+ implementations in `review` then implement an interface that also lives in `review` — no cycle

- **On day 1, trust-based routing appears identical to Gastown (no differentiation)**
  - Symptom: routing assigns work to agents without apparent trust weighting; the `RoutingPolicy` thresholds seem to have no effect
  - Cause: all agents are in bootstrap mode (`isBootstrap()` returns true) because `minimumObservations` hasn't been reached. This is correct — a trust score from 2–3 attestations is noise, not signal. Routing falls back to availability, identical to Gastown's GUPP model.
  - Fix: no fix required; this is the Phase 0 maturity model by design. Routing quality improves automatically as attestations accumulate. See DT-006 in `docs/PROGRESS.md` for the four-phase model.

### Pattern to replicate (in another domain)

1. Create `{domain}-domain` Maven module — zero framework imports, zero JPA, no Quarkus
2. Identify your domain's work types. Ask: are they analytical (trust-scored on quality), operational (trust-scored on outcome), human decisions (with SLA and lifecycle), or system-oversight (triggered by uncertainty)? Create a typed class per category — not a flat string list
3. Define `RoutingPolicy` values for trust-sensitive capabilities: set `threshold` (minimum trust), `minimumObservations` (credibility gate), `borderlineMargin` (uncertainty band → human oversight), `fallbackType`, and `rationale`
4. Implement `CapabilityRegistry` SPI in `{domain}.spi` — `capabilities()`, `policy()`, `isKnown()` as default method calling `capabilities()`
5. Implement the populated default registry in `{domain}` — concrete class, no CDI
6. Create `{domain}-app` Maven module — depends on `{domain}-domain`; owns all Quarkus wiring
7. Add a one-liner `@ApplicationScoped` CDI wrapper in `{domain}-app` extending your registry
8. Define port interface + DTOs in `{domain}-review` module (pure Java, no CDI, no Quarkus) — Layer 2+ implementations also live here; keeping them out of `{domain}-app` prevents a module dependency cycle
9. Implement the baseline service with `@ApplicationScoped @DefaultBean` in `{domain}-app` — direct stub calls; document accountability gaps in LAYER-LOG.md (no attribution, no SLA, no formal DECLINE, no audit trail, no trust weighting)
10. Expose `POST /api/{domain-noun}` via a thin REST resource in `{domain}-app` injecting the port interface — auth-retrofit ready (`@RolesAllowed` can be added to the single method without restructuring)
11. Boot test: run the existing `@QuarkusTest` to verify CDI discovers the baseline service and REST resource
12. Unit test the baseline service: plain `new PrReviewService()`, no Quarkus — verify non-null outcome, non-blank verdict, non-null findings list

---

## Layer 2 — casehub-work (SLA-bounded human review gate)

**Participates in:** S2, S3, S4, S5
**Architectural pattern:** Hexagonal (WorkItem as port; SlaBreachPolicy SPI); Event-Driven (`@ObservesAsync SlaBreachEvent`, `WorkItemLifecycleEvent → PlanItem` bridge) — `../parent/docs/ARCHITECTURE.md §Foundation, §Orchestration`
**Key protocols:** `module-tier-structure.md`, `flyway-migration-rules.md` (default datasource for work tables)
**Design refs:** `DESIGN.md §Layer 2 SLA Breach Policy`; `docs/specs/2026-05-22-layer2-sla-breach-policy-design.md`
**Completed:** devtown#41 ✅ devtown#42 ✅; full LAYER-LOG entry 🔲 pending engine#326 (failure goal support — needed to close the breach escalation teaching narrative end-to-end)
**Issues:** casehubio/devtown#41 (work adapter wiring), casehubio/devtown#42 (SLA breach handler wiring test)
**Navigation:** `git log --grep="#41" --oneline`
**Blog:** 🔲 at layer close

### What it shows

🔲 Full entry at layer close (blocked on engine#326). Known content below.

Layer 2 adds `casehub-work` to the PR review case. The gap it closes: analysis can stall indefinitely with no escalation. After Layer 2, every human review assignment is a `WorkItem` with a configurable `claimDeadline`. When the reviewer misses the deadline, `SlaBreachPolicy` fires: escalate to a senior reviewer, extend, or fail — governed by the domain policy, not hardcoded logic.

### Accountability gaps closed

| Gap | What breaks | Closed by |
|-----|-------------|-----------|
| No response SLA | Human reviewer misses a security finding; no escalation, no record | `WorkItem.claimDeadline` + `SlaBreachPolicy` |
| No formal human task lifecycle | Review assigned to a group with no individual accountability | `WorkItem` claim → individual assignment → completion record |

### Key wiring

🔲 Full wiring entry at layer close. Key decisions captured in `DESIGN.md §Layer 2 SLA Breach Policy`:
- `SlaBreachPolicy` SPI placed in `casehub-work-api`, not a new `platform/apps-api` module
- `BreachDecision` sealed interface — `Fail`, `EscalateTo`, `Extend`, `Chained`
- Stateless multi-tier escalation via `candidateGroups` — no state serialization needed
- `DefaultSlaBreachPolicy` lives in `devtown-domain` (pure Java, no Quarkus)

### Gotchas

🔲 At layer close.

### Pattern to replicate

🔲 At layer close.

---

## Layer 5 — casehub-engine (adaptive routing on code content)

**Participates in:** S1, S2, S3, S4, S5
**Architectural pattern:** DDD (CasePlanModel — goals, bindings, capabilities); Event-Driven (`@ObservesAsync CaseLifecycleEvent`, `WorkItemLifecycleEvent → PlanItem`); Hexagonal (`YamlCaseHub` as adapter over `CaseHubRuntime` port); CQRS-lite (startCase is command; binding evaluation reads accumulated CaseContext) — `../parent/docs/ARCHITECTURE.md §Orchestration, §Integration`
**Key protocols:** `case-definition-layers.md` (YAML → schema model → canonical API), `module-tier-structure.md`, `dual-trail-audit-pattern.md` (EventLog + CaseLedgerEntry)
**Design refs:** `docs/specs/2026-05-15-epic3-pr-review-caseplanmodel-design.md`; `docs/orchestration-advantages.md` §1–3; `docs/gastown-casehub-analysis-v2.md §Phase Gates`
**Completed:** Epic 3 (devtown#10) shipped 2026-05-19
**Issues:** casehubio/devtown#10 (Epic 3: PR review CasePlanModel — content-driven routing and parallel checks)
**Navigation:** `git log --grep="#10" --oneline`
**Design specs:** `docs/specs/2026-05-15-epic3-pr-review-caseplanmodel-design.md`
**Blog:** `blog/2026-05-19-mdp01-layer-5-case-definition-lands.md`
**Improvement log:** `docs/PROGRESS.md` — DT entries to be added as improvement decisions are formalised

**Key files:**
- `review/src/main/resources/devtown/pr-review.yaml` — YAML CasePlanModel: 9 bindings (4 groups), 3 goals, 9 capabilities
- `app/src/main/java/io/casehub/devtown/app/PrReviewCaseHub.java` — `@ApplicationScoped extends YamlCaseHub("devtown/pr-review.yaml")`
- `app/src/main/java/io/casehub/devtown/app/PrReviewCaseService.java` — `@ApplicationScoped implements PrReviewApplicationService` (no `@DefaultBean`); displaces `PrReviewService`
- `review/src/test/java/io/casehub/devtown/review/PrReviewCaseDefinition.java` — fluent DSL factory for binding condition unit tests
- `review/src/test/java/io/casehub/devtown/review/MapCaseContext.java` — `CaseContext` test helper backed by `Map<String,Object>`
- `review/src/test/java/io/casehub/devtown/review/PrReviewBindingConditionTest.java` — 28 pure unit tests for all 9 binding conditions; no Quarkus required
- `app/src/test/java/io/casehub/devtown/app/PrReviewCaseHubTest.java` — `@QuarkusTest` YAML round-trip: 5 tests verifying binding/goal/capability counts
- `app/src/test/java/io/casehub/devtown/app/InMemoryLedgerEntryRepository.java` — `@ApplicationScoped` test stub; needed for `@QuarkusTest` CDI resolution when `casehub-ledger` runtime is on the classpath

### What it shows

Layer 5 introduces casehub-engine into devtown. The PR review case becomes an Adaptive Case Management instance with declared goals and content-driven bindings — security review fires only when code analysis finds security-sensitive code, not from author labels. Multiple checks (style, test coverage, performance) fire simultaneously via the ACM binding system without explicit parallelism declaration. Human approval and CI run in parallel via the WAITING state (total time = max, not sum). The routing logic lives in the case definition YAML and applies consistently to every PR, producing an audit trail of every routing decision.

Contrast with Layer 1: `PrReviewService` makes direct calls with no adaptive routing, no formal obligation, and no audit. Layer 5 displaces it with a `@ApplicationScoped` implementation that opens a `CaseInstance` and lets the engine drive coordination.

See `docs/orchestration-advantages.md` §1 (content-driven routing), §2 (parallel human+CI), §3 (automatic parallelism) for the detailed teaching narrative.

### The gap comments

These are the Layer 1 gap comments that Layer 5 addresses:

```java
// LAYER 1 GAP: no attribution — which agent ran this analysis? No record.
// LAYER 1 GAP: no response SLA — analysis can stall indefinitely with no escalation.
// LAYER 1 GAP: no formal DECLINE — if a specialist can't review, it silently fails or errors.
// LAYER 1 GAP: no tamper-evident audit trail — cannot trace a production incident to this review.
// LAYER 1 GAP: no trust weighting — a novice and an expert are treated identically.
```

Layer 5 closes: attribution (`CaseLedgerEntry` records which binding dispatched which agent), formal DECLINE (DECLINED outcome on blackboard triggers a different binding), audit trail (`CaseLedgerEntry` per case state transition). Partial: SLA (requires casehub-work-adapter HITL wiring — known gap in Epic 3 scope, see CLAUDE.md). Trust weighting (requires P1.3 `TrustWeightedSelectionStrategy` — Layer 6).

### Key wiring

1. **YAML lives in `review/`, wired in `app/`.** `pr-review.yaml` lives in `review/src/main/resources/devtown/` — Quarkus classloader picks it up from the review module JAR at test time; no copy to `app/` needed. `PrReviewCaseHub` and `PrReviewCaseService` live in `app/` — consistent with platform convention: all CDI wiring in the Tier 3 app module.

2. **`@DefaultBean` displacement.** `PrReviewCaseService @ApplicationScoped` (no `@DefaultBean`) displaces `PrReviewService @DefaultBean` — no explicit CDI configuration required. Both classes remain in the build; Layer 1 is never deleted.

3. **Initial CaseContext.** `PrReviewCaseService.review(PrPayload)` builds `{ pr: {...}, policy: {...} }` from the payload and `@ConfigProperty`-injected policy values. The `policy` subtree will be replaced by `PreferenceProvider.resolve(scope).asMap()` when casehub-platform-api ships (parent#26).

4. **`casehub-engine` runtime in `app/pom.xml`.** Required for `YamlCaseHub` CDI injection of `CaseHubRuntime`. Without it, Quarkus fails to satisfy the injection point at startup.

5. **Human approval via capability string.** Expressed as `capability: human-decision:pr-approval` in YAML. Full `HumanTaskTarget` wiring (casehub-work-adapter bridging `WorkItemLifecycleEvent → PlanItem`) is deferred to devtown#30.

6. **`casehub-engine` and `casehub-engine-testing` (test scope) added to `app/pom.xml`.** `casehub-engine-testing` provides engine test repos; `casehub-ledger` test repos are not included — see §Gotchas.

### Gotchas

- **`@QuarkusTest` CDI failure with `casehub-ledger` runtime on classpath — misleading error message**
  - Symptom: `@QuarkusTest` fails with `ClassSelector resolution failed` — looks like a class-loading or missing dependency problem, not a CDI error
  - Cause: `casehub-ledger` provides a Panache `ReactiveLedgerEntryRepository` requiring a real datasource. `casehub-engine-testing` provides engine repos but not ledger repos. CDI cannot satisfy the injection point, but the error message does not name the unsatisfied bean.
  - Fix: add `@ApplicationScoped InMemoryLedgerEntryRepository` stub to `{domain}-app/src/test/`. Tracked in garden as GE-20260519-e13b01.

- **Merge binding requires a `securitySensitive == false` short-circuit**
  - Symptom: non-security PRs never satisfy the merge condition — `securityReview` never appears in context, so any `securityReview`-referencing predicate evaluates false or throws
  - Cause: the merge binding initially only checked `securityReview` approval without a guard for the non-security-sensitive case
  - Fix: add `when: (.securitySensitive == false) or (.securityReview.outcome == "APPROVED")` short-circuit in both the YAML and the fluent DSL factory. Test `fires_whenNotSecuritySensitiveAndNoSecurityReview` added to cover this path.

- **`quarkus:build` (production package) fails — foundation CDI SPIs unsatisfied**
  - Symptom: `mvn install` fails during `quarkus:build`; all 108 tests pass; the failure is only in the production packaging phase
  - Cause: engine CDI SPIs are unsatisfied without claudony + persistence module present. This is a known foundation gap — the harness app cannot be packaged standalone without its full dependency stack.
  - Status: tracked in devtown#31. Does not affect test coverage or local development.

- **HITL wiring gap (resolved devtown#30, 2026-05-21)**
  - `HumanTaskTarget` bindings were initially unit-tested only. The full `WorkItemLifecycleEvent → PlanItem` bridge was completed in devtown#30: `HumanApprovalLifecycleTest` verifies the end-to-end lifecycle including `@ObservesAsync` delivery and `outputMapping` → case context update.

### Pattern to replicate (in another domain)

1. Create the YAML CasePlanModel in `{domain}-review/src/main/resources/{domain}/` — not in `{domain}-app/`; Quarkus classloader picks it up from the review module JAR at test time
2. Extend `YamlCaseHub` in `{domain}-app/` with `@ApplicationScoped` — pass the classpath resource path (e.g. `"devtown/pr-review.yaml"`) to `super()`
3. Create `@ApplicationScoped` (no `@DefaultBean`) service implementing the port interface in `{domain}-app/` — inject the CaseHub bean, build initial CaseContext from the domain payload + policy config, call `caseHub.startCase(initialContext)`
4. Define scoped policy values via `@ConfigProperty` for now — replace with `PreferenceProvider.resolve(scope).asMap()` when casehub-platform-api ships (parent#26)
5. Add `casehub-engine` (runtime) and `casehub-engine-testing` (test scope) to `{domain}-app/pom.xml`
6. Add `InMemoryLedgerEntryRepository` stub to `{domain}-app/src/test/` if `casehub-ledger` runtime is on the classpath (see GE-20260519-e13b01 in the garden)
7. Write binding condition unit tests using a fluent DSL factory (+ `MapCaseContext` helper) in `{domain}-review/src/test/` — no Quarkus required; tests are fast and cover every binding predicate including short-circuit paths
8. Write a `@QuarkusTest` in `{domain}-app/src/test/` to verify the YAML loads cleanly, with assertions on expected binding count, goal count, and capability count — catches YAML parse errors and classpath issues at test time

