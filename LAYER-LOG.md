# devtown Agentic Harness — Layer Log

Structured record of what was built at each layer, optimised for LLM consumption and future tutorial generation. Correlates with blog entries in `blog/`, git history, and GitHub issues.

Each entry documents one layer of the adoption sequence. Entries are written when work on that layer begins — not before. Sections marked `🔲` within an entry are placeholders: the work for that section isn't done yet, but the expected content or pointer is included so future sessions can fill it in without reconstructing context.

Cross-references:
- Blog entries: workspace `blog/` (staged; published to mdproctor.github.io/_notes/ via `publish-blog`)
- Design specs: project `docs/specs/`
- Improvement log: `docs/PROGRESS.md` (DT-NNN entries)
- Architecture comparison: `docs/gastown-casehub-analysis-v2.md`
- Tutorial teaching objectives: `../parent/docs/tutorial-strategy.md §7.5`
- AML reference implementation: `../aml/LAYER-LOG.md`

---

## Layer 1 — Naive Java (no CaseHub)

**Completed:** Epic 1 (scaffold) 2026-05-08 `10d0d42`; Epic 2 (vocabulary) 2026-05-11 `ccbe944`; Part B (naive service) 2026-05-15 `cca6acc` + `18b22e0`
**Issues:** casehubio/devtown#8 (Epic 1), casehubio/devtown#9 (Epic 2), casehubio/devtown#27 (Part B — naive service)
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
- `app/src/main/java/io/casehub/devtown/app/NaivePrReviewService.java` — `@ApplicationScoped @DefaultBean`; 5 gap comments; displaced by Layer 2+
- `app/src/main/java/io/casehub/devtown/app/PrReviewResource.java` — thin REST dispatcher; `POST /api/reviews`
- `app/src/test/java/io/casehub/devtown/app/NaivePrReviewServiceTest.java` — plain unit tests; no Quarkus; 3 contract assertions

### What it shows

Layer 1 has two distinct parts. The first (Epics 1–2, done) establishes the devtown vocabulary — a typed split of what Gastown keeps in a flat namespace. The second (not yet built) shows the naive anti-pattern: direct service calls with no accountability, no SLA, no formal obligation. Together they form the baseline everything else improves upon.

**Part A — Vocabulary and scaffold (Epics 1–2, 2026-05-08 to 2026-05-11)**

The domain model is pure Java, no CaseHub dependencies. The vocabulary split is devtown-specific: Gastown's 13 flat capability tags become four typed classes with distinct routing semantics. This is the first architectural decision an adopter faces and the one with the biggest payoff later — once the foundation wires `TrustGateService`, `ActorType`, and `WorkerSelectionStrategy`, the vocabulary is the application-layer expression of what those foundation capabilities can express. See DT-001 through DT-006 in `docs/PROGRESS.md`.

**Part B — Naive PR review service (2026-05-15, devtown#27)**

`NaivePrReviewService` with `@ApplicationScoped @DefaultBean` makes direct stub calls via private methods. Gap comments name every compliance and accountability gap. `PrReviewResource` exposes `POST /api/reviews` so the layer is runnable with a single HTTP call. This is the teaching baseline — each subsequent layer displaces it at the CDI level via a non-`@DefaultBean @ApplicationScoped` implementation.

### The gap comments

From `app/src/main/java/io/casehub/devtown/app/NaivePrReviewService.java`:

```java
// LAYER 1 GAP: no attribution — which agent ran this analysis? No record.
var securityFindings = analyzeSecurityDirectly(pr);
// LAYER 1 GAP: no response SLA — analysis can stall indefinitely with no escalation.
var architectureFindings = reviewArchitectureDirectly(pr);
// LAYER 1 GAP: no formal DECLINE — if a specialist can't review, it silently fails or errors.
// LAYER 1 GAP: no tamper-evident audit trail — cannot trace a production incident to this review.
// LAYER 1 GAP: no trust weighting — a novice and an expert are treated identically.
```

### Key wiring

**Module structure — pure Java domain, Quarkus only in `app/`.**
`devtown-domain` has zero framework dependencies. All constants, SPI, and default implementation live there. `devtown-app` owns all CDI and Quarkus wiring. This split is mandatory for the tutorial to work — each layer needs to show domain logic independently of the framework.

**`@DefaultBean` displacement pattern.**
The naive service carries `@DefaultBean`. Each subsequent layer adds an `@ApplicationScoped` implementation in `review` without it — CDI displacement means the new one wins, the naive one stays in the build but is inactive. Both classes coexist; no code is deleted across layers.

```java
// app/NaivePrReviewService.java — Layer 1, always present
@ApplicationScoped
@DefaultBean  // displaced by any @ApplicationScoped impl without @DefaultBean
public class NaivePrReviewService implements PrReviewApplicationService {

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
9. Implement the naive service with `@ApplicationScoped @DefaultBean` in `{domain}-app` — direct stub calls, gap comment per compliance gap (no attribution, no SLA, no formal DECLINE, no audit trail, no trust weighting)
10. Expose `POST /api/{domain-noun}` via a thin REST resource in `{domain}-app` injecting the port interface — auth-retrofit ready (`@RolesAllowed` can be added to the single method without restructuring)
11. Boot test: run the existing `@QuarkusTest` to verify CDI discovers the naive service and REST resource
12. Unit test the naive service: plain `new NaiveService()`, no Quarkus — verify non-null outcome, non-blank verdict, non-null findings list

---

## Layer 5 — casehub-engine (adaptive routing on code content)

**Completed:** 🔲 in progress — Epic 3 (devtown#10) started 2026-05-15, brainstorming in progress
**Issues:** casehubio/devtown#10 (Epic 3: PR review CasePlanModel — content-driven routing and parallel checks)
**Design specs:** 🔲 to be written — will land in `docs/specs/` when brainstorming completes this session
**Blog:** 🔲
**Improvement log:** 🔲 — DT entries to be added as implementation decisions are made

**Key files:**
- 🔲 `review/src/main/resources/devtown/pr-review.yaml` — YAML CasePlanModel definition; expected when Epic 3 ships. See §What it shows.
- 🔲 `review/src/main/java/io/casehub/devtown/review/PrReviewCaseService.java` — expected: `@ApplicationScoped` (no `@DefaultBean`); displaces `NaivePrReviewService`. See §Key wiring.
- 🔲 `review/src/test/java/io/casehub/devtown/review/PrReviewBindingConditionTest.java` — expected: plain unit tests for JQ binding `when` conditions using `LambdaExpressionEvaluator`

### What it shows

Layer 5 introduces casehub-engine into devtown. The PR review case becomes an Adaptive Case Management instance with declared goals and content-driven bindings — security review fires only when code analysis finds security-sensitive code, not from author labels. Multiple checks (style, test coverage, performance) fire simultaneously via the ACM binding system without explicit parallelism declaration. Human approval and CI run in parallel via the WAITING state (total time = max, not sum). The routing logic lives in the case definition YAML and applies consistently to every PR, producing an audit trail of every routing decision.

Contrast with Layer 1: `NaivePrReviewService` makes direct calls with no adaptive routing, no formal obligation, and no audit. Layer 5 displaces it with a `@ApplicationScoped` implementation that opens a `CaseInstance` and lets the engine drive coordination.

See `docs/orchestration-advantages.md` §1 (content-driven routing), §2 (parallel human+CI), §3 (automatic parallelism) for the detailed teaching narrative.

### The gap comments

🔲 To fill when Layer 5 code ships. These are the Layer 1 gap comments that Layer 5 addresses:

```java
// LAYER 1 GAP: no attribution — which agent ran this analysis? No record.
// LAYER 1 GAP: no response SLA — analysis can stall indefinitely with no escalation.
// LAYER 1 GAP: no formal DECLINE — if a specialist can't review, it silently fails or errors.
// LAYER 1 GAP: no tamper-evident audit trail — cannot trace a production incident to this review.
// LAYER 1 GAP: no trust weighting — a novice and an expert are treated identically.
```

Layer 5 closes: attribution (`CaseLedgerEntry` records which binding dispatched which agent), formal DECLINE (DECLINED outcome on blackboard triggers a different binding), audit trail (`CaseLedgerEntry` per case state transition). Partial: SLA (requires casehub-work-adapter HITL wiring — known gap in Epic 3 scope, see CLAUDE.md). Trust weighting (requires P1.3 `TrustWeightedSelectionStrategy` — Layer 6).

### Key wiring

🔲 To fill as Epic 3 implementation decisions are made. Expected areas:

- **YAML loading at startup** — classpath resource loader pattern; check engine test `YamlSimpleCaseHubBeanTest` for how `CaseDefinition` is loaded from YAML and registered as a `CaseMetaModel`
- **`@DefaultBean` displacement** — `PrReviewCaseService @ApplicationScoped` (no `@DefaultBean`) in `review` displaces `NaivePrReviewService @DefaultBean` in `app`. Same CDI pattern as Layer 1 established; no extra wiring needed
- **`HumanTaskTarget` binding** — use `HumanTaskTarget.inline()` (not template — casehub-work templates not yet configured in devtown). API: `io.casehub.api.model.HumanTaskTarget`
- **Binding `when` conditions** — JQ expressions over `CaseContext`; need a committed CaseContext schema defining PR payload fields and code-analysis output fields. Schema design is the first open question in Epic 3 brainstorming (in progress)
- **`@DefaultBean` on engine no-op SPIs** — any new SPI no-ops must use `@DefaultBean @ApplicationScoped`, not bare `@ApplicationScoped`; see `parent/docs/protocols/engine-spi-noops-defaultbean.md`

### Gotchas

🔲 To fill as encountered during Epic 3 implementation. Known risks:

- **`@ApplicationScoped` no-op SPI in engine without `@DefaultBean`** — collides with devtown's implementation when indexed together via `casehub-testing`. Symptom: CDI ambiguity error on `@QuarkusTest`. Fix: `@DefaultBean @ApplicationScoped` on all engine no-ops. Protocol: `parent/docs/protocols/engine-spi-noops-defaultbean.md`
- **HITL wiring gap** — `HumanTaskTarget` bindings in the YAML compile but the casehub-work-adapter does not yet bridge `WorkItemLifecycleEvent → PlanItem` transitions for devtown. Tests must use mock workers; document this explicitly in the LAYER-LOG when encountered

### Pattern to replicate (in another domain)

🔲 To fill when Epic 3 ships. Anticipated steps (check `../aml/LAYER-LOG.md §Layer 5` when available as the reference):

1. Add `casehub-engine` runtime dependency to `{domain}-app` pom
2. Define goals in YAML: each goal is a JQ predicate over `CaseContext` (e.g. `.reviews | length >= 2`)
3. Define capabilities in YAML: one per specialist work type, matching `{domain}.ReviewDomain` constants
4. Define bindings in YAML: `on: { contextChange: {} }`, `when: <JQ predicate over CaseContext>`, `capability: <name>`
5. Define completion policy: `success: allOf: [goal1, goal2, ...]`
6. Load YAML at startup via classpath loader — register as `CaseMetaModel` in the engine
7. Implement `@ApplicationScoped` service (no `@DefaultBean`) in `{domain}-review` that opens a `CaseInstance` when a domain event arrives — displaces the naive service
8. Unit test binding conditions: use `LambdaExpressionEvaluator` + mock `CaseContext` — no Quarkus required

