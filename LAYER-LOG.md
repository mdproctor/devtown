# devtown Agentic Harness — Layer Log

Structured record of what was built at each layer, optimised for LLM consumption and future tutorial generation. Correlates with blog entries in `blog/`, git history, and GitHub issues.

Each entry documents one layer of the adoption sequence. Sections marked `🔲` are placeholders — the work hasn't been done yet; the placeholder captures what will go there so future sessions can fill it in without needing to reconstruct the context.

Cross-references:
- Blog entries: workspace `blog/` (`/Users/mdproctor/claude/public/casehub/devtown/blog/`)
- Design specs: project `docs/specs/`
- Improvement log: `docs/PROGRESS.md` (DT-NNN entries)
- Architecture comparison: `docs/gastown-casehub-analysis-v2.md`
- Tutorial teaching objectives: `../parent/docs/tutorial-strategy.md §7.5`
- AML reference implementation: `../aml/LAYER-LOG.md`

---

## Layer 1 — Naive Java (no CaseHub)

**Completed:** Epic 1 (scaffold) 2026-05-08 `10d0d42`; Epic 2 (vocabulary) 2026-05-11 `ccbe944`; 🔲 naive service not yet built
**Issues:** casehubio/devtown#8 (Epic 1), casehubio/devtown#9 (Epic 2)
**Design specs:** `docs/specs/2026-05-07-epic1-scaffold-design.md`, `docs/specs/2026-05-08-epic2-domain-model-design.md`
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
- 🔲 `app/src/main/java/io/casehub/devtown/app/NaivePrReviewService.java` — not yet built; see §What it shows
- 🔲 `app/src/main/java/io/casehub/devtown/app/PrReviewResource.java` — not yet built; `POST /api/reviews`

### What it shows

Layer 1 has two distinct parts. The first (Epics 1–2, done) establishes the devtown vocabulary — a typed split of what Gastown keeps in a flat namespace. The second (not yet built) shows the naive anti-pattern: direct service calls with no accountability, no SLA, no formal obligation. Together they form the baseline everything else improves upon.

**Part A — Vocabulary and scaffold (Epics 1–2, 2026-05-08 to 2026-05-11)**

The domain model is pure Java, no CaseHub dependencies. The vocabulary split is devtown-specific: Gastown's 13 flat capability tags become four typed classes with distinct routing semantics. This is the first architectural decision an adopter faces and the one with the biggest payoff later — once the foundation wires `TrustGateService`, `ActorType`, and `WorkerSelectionStrategy`, the vocabulary is the application-layer expression of what those foundation capabilities can express. See DT-001 through DT-006 in `docs/PROGRESS.md`.

**Part B — Naive PR review service (🔲 not yet built)**

A `NaivePrReviewService` with `@ApplicationScoped @DefaultBean` that makes direct calls to specialist analysis services. Gap comments name every compliance and accountability gap. A REST endpoint `POST /api/reviews` so the layer is runnable with a single HTTP call. This is the teaching baseline — each subsequent layer displaces it at the CDI level.

Sketch from `../parent/docs/tutorial-strategy.md §7.5.1`:

```java
// LAYER 1 GAP: no attribution — which agent reviewed this code? No record.
SecurityAnalysis security = securityAnalyzer.analyze(pr);
// LAYER 1 GAP: no response SLA — reviewer can sit on this indefinitely.
ArchitectureReview arch = architectureReviewer.review(pr);
// LAYER 1 GAP: no formal decline — if an agent can't review, it silently fails or errors.
// LAYER 1 GAP: no audit trail — cannot trace a production incident back to this review.
String comment = commentService.post(pr, security, arch);
```

### The gap comments

🔲 To be written when `NaivePrReviewService` is built. The gap comments are the primary teaching mechanism per layer. Required gaps to name (from CLAUDE.md domain model):
- No attribution — who reviewed this?
- No response SLA — reviewer can sit indefinitely
- No formal DECLINE — scope boundaries not structurally captured
- No tamper-evident audit trail — cannot trace production incident to missed finding
- No trust weighting — experienced security reviewer not prioritised over novice

### Key wiring

**Module structure — pure Java domain, Quarkus only in `app/`.**
`devtown-domain` has zero framework dependencies. All constants, SPI, and default implementation live there. `devtown-app` owns all CDI and Quarkus wiring. This split is mandatory for the tutorial to work — each layer needs to show domain logic independently of the framework.

**`@DefaultBean` displacement pattern (from AML Layer 1).**
The naive service carries `@DefaultBean`. Each subsequent layer adds an `@ApplicationScoped` implementation without it — CDI displacement means the new one wins, the naive one stays in the build but is inactive. Both classes coexist; no code is deleted across layers.

```java
@ApplicationScoped
@DefaultBean  // displaced by any @ApplicationScoped impl in the same deployment
public class NaivePrReviewService implements PrReviewApplicationService {
```

**`CapabilityRegistry` SPI as vocabulary/registry SPI, not no-op.**
Platform protocol distinguishes operational no-op SPIs (empty default) from vocabulary/registry SPIs (populated default). `DevtownCapabilityRegistry` is the latter — the default is a fully populated implementation, not a stub. `CapabilityRegistryBean` is a one-liner `@ApplicationScoped` subclass that promotes it to CDI without any logic duplication.

**`isKnown()` as a default method on the SPI interface.**
Initially written as a concrete method in `DevtownCapabilityRegistry` calling the backing static field directly — not virtual. Commit `ad75f20` fixed it to call `capabilities()` in the implementation; commit `8b3305e` promoted it to a `default` method on the SPI interface. Any subclass overriding `capabilities()` gets a correct `isKnown()` for free. Caught in review — a test specifically for this case (`isKnown` on a subclass that overrides `capabilities()`) would have caught it earlier.

**Vocabulary split rationale.**
13 flat Gastown tags → 4 typed classes. The split emerged from recognising that `security-review`, `batch-bisect`, and `notify` are not the same kind of thing and should not route through the same trust infrastructure. Full rationale: `docs/gastown-casehub-analysis-v2.md` §DT-001; blog `2026-05-11-mdp01-the-vocabulary-problem.md`.

### Gotchas

- **`isKnown()` bypass bug**: initial implementation called the static field directly, bypassing `capabilities()`. Any subclass overriding `capabilities()` would have gotten wrong results. Fixed by making `isKnown()` a `default` method on the SPI (`8b3305e`). Write a test for this case when adding the first subclass.
- **`NOTIFY` removed from vocabulary**: connector calls are not trust-scored capabilities. Including `notify` in the vocabulary would mean `TrustGateService.meetsThreshold()` gets called on a Slack message. `casehub-connectors` handles delivery; the case plan model calls it directly.
- **`BATCH_BISECT` / `COORDINATED_MERGE` / `COORDINATED_ROLLBACK` deferred**: these are orchestration operations expressed as CasePlanModel binding structures, not capability tags. They were in the original Gastown-derived vocabulary and removed. Naming review still open: devtown#20.
- **`minimumObservations` is a credibility gate, not a capability gate**: a trust score of 0.85 from 2 attestations is noise; from 50 it's signal. `RoutingPolicy.isBootstrap(agentObservations)` makes this explicit. On day 1 of a fresh installation, every agent is in bootstrap — routing is identical to Gastown's GUPP model. Routing quality improves automatically as attestations accumulate. See DT-006 in `docs/PROGRESS.md`.

### Pattern to replicate (in another domain)

1. Create `{domain}-domain` Maven module — zero framework imports, zero JPA, no Quarkus
2. Identify your domain's work types. Ask: are they analytical (trust-scored on quality), operational (trust-scored on outcome), human decisions (with SLA and lifecycle), or system-oversight (triggered by uncertainty)? Create a typed class per category — not a flat string list
3. Define `RoutingPolicy` values for trust-sensitive capabilities: set `threshold` (minimum trust), `minimumObservations` (credibility gate), `borderlineMargin` (uncertainty band → human oversight), `fallbackType`, and `rationale`
4. Implement `CapabilityRegistry` SPI in `{domain}.spi` — `capabilities()`, `policy()`, `isKnown()` as default method calling `capabilities()`
5. Implement the populated default registry in `{domain}` — concrete class, no CDI
6. Create `{domain}-app` Maven module — depends on `{domain}-domain`; owns all Quarkus wiring
7. Add a one-liner `@ApplicationScoped` CDI wrapper in `{domain}-app` extending your registry
8. Implement the naive service with `@ApplicationScoped @DefaultBean` — direct calls, no CaseHub, gap comments for every compliance gap
9. Expose `POST /api/{domain-noun}` via a REST resource injecting the port interface
10. Boot test: verify `@QuarkusTest` starts and CDI discovers the registry bean
11. Test the naive service: plain `new`, no Quarkus — verify gap comments are exercisable

---

## Layer 2 — + casehub-work (PR review WorkItem with reviewer response SLA)

**Completed:** 🔲 not yet built
**Issue:** 🔲 no issue yet — create before implementing
**Tutorial objectives:** `../parent/docs/tutorial-strategy.md §7.5.1` Layer 2 sketch
**Foundation prerequisite:** casehub-work ✅; HITL wiring ⚠️ (WorkItem COMPLETED → case signal, `parent#6`); `casehub-work work#157` GitHub Issues sync ✅

**Key files (expected):**
- 🔲 `app/src/main/java/io/casehub/devtown/app/WorkItemPrReviewService.java` — Layer 2 implementation
- 🔲 `domain/src/main/java/io/casehub/devtown/domain/PrReviewResult.java` — extended to carry `reviewTaskId`

### What it shows

🔲 Adds a formal `WorkItem` for the human reviewer with a response SLA (e.g. 4h for security reviews, 8h for architecture). Closes the "no response SLA" and "no attribution" gaps from Layer 1. Reviewer assignments are now tracked; missed SLAs are visible.

Expected sketch (from `tutorial-strategy.md §7.5.1`):

```java
// LAYER 2: security review with 4-hour response SLA
WorkItemRequest reviewRequest = WorkItemRequest.builder()
    .title("PR #456: Add payment processing endpoint")
    .category(ReviewDomain.SECURITY_REVIEW)
    .candidateGroups("security-reviewers")
    .claimDeadline(Instant.now().plus(4, ChronoUnit.HOURS))
    .payload(pr.toJson())
    .build();
```

### The gap comments addressed

🔲 To fill in when built.

### Key wiring

🔲 To fill in when built. Expected: same Hibernate scan packages gotcha as AML (`io.casehub.work.runtime.model,io.casehub.work.runtime.filter`); same Flyway V2 conflict with qhorus; same reactive workaround. Refer to `../aml/LAYER-LOG.md` Layer 2 §Key wiring — expect identical workarounds until upstream bugs close.

### Gotchas

🔲 To fill in when built.

### Pattern to replicate (in another domain)

🔲 To fill in when built. Will mirror AML Layer 2 §Pattern to replicate with domain-specific SLA values.

---

## Layer 3 — + casehub-qhorus (typed COMMAND to specialist reviewers; formal DECLINE)

**Completed:** 🔲 not yet built
**Issue:** 🔲 no issue yet
**Foundation prerequisite:** P0.1 ✅; P0.2 ✅; qhorus#124 persona mapping ⚠️

### What it shows

🔲 Each specialist reviewer receives a typed `COMMAND`. Agents that cannot handle the work issue a formal `DECLINE` — a first-class normative event, not a timeout or error. The DECLINE is re-routed; the scope boundary is recorded.

Sketch from `tutorial-strategy.md §7.5.1`:

```
[PR Orchestrator] COMMAND → Security Agent
[Security Agent] RESPONSE → "No SQL injection; rate limiting absent on /payment"
[Architecture Agent] DECLINE → "Distributed transaction pattern outside my scope"
[PR Orchestrator] COMMAND → Test Coverage Agent
[Test Coverage Agent] DONE → "Coverage 67%; payment failure path untested"
```

`SCOPE_CALIBRATION` trust dimension (`DevtownTrustDimension.SCOPE_CALIBRATION`) begins accumulating signal here — every DECLINE is a positive data point.

### The gap comments addressed / Key wiring / Gotchas / Pattern

🔲 All to fill in when built.

---

## Layer 4 — + casehub-ledger (tamper-evident review record; causedByEntryId chain)

**Completed:** 🔲 not yet built
**Issue:** 🔲 no issue yet
**Foundation prerequisite:** P1.4 `CaseLedgerEntry` ✅ (2026-04-26); `ActorTrustScore` discriminator ✅

### What it shows

🔲 Every review decision is a ledger entry with `causedByEntryId` linking findings to actions. When a production security incident is traced to a merged PR, the ledger answers: who reviewed it, what did they find, what did they miss, and what was their trust score at the time. Cryptographic Merkle chain — no after-the-fact revision possible.

### The gap comments addressed / Key wiring / Gotchas / Pattern

🔲 All to fill in when built.

---

## Layer 5 — + casehub-engine (adaptive review routing; content-driven bindings)

**Completed:** 🔲 not yet built
**Issue:** casehubio/devtown#10 (Epic 3, active)
**Foundation prerequisite:** P0.1 engine#186 ✅; content-driven routing bindings ✅

### What it shows

🔲 The fixed review pipeline becomes adaptive. Security flag in code analysis triggers a security reviewer binding. Large architectural refactor triggers senior architect binding. Bindings fire based on what the code analysis actually finds — not what the PR author declared.

### The gap comments addressed / Key wiring / Gotchas / Pattern

🔲 All to fill in when built. This is the current epic (#10) — fill in when Epic 3 completes.

---

## Layer 6 — Trust routing (RoutingPolicy enforced; post-merge feedback loop)

**Completed:** 🔲 not yet built
**Issue:** casehubio/devtown#13 (Epic 6, planned)
**Foundation prerequisite:** P1.3 `TrustWeightedSelectionStrategy` wired ⚠️; qhorus#124 persona mapping ⚠️; ledger#76 ⚠️

### What it shows

🔲 `RoutingPolicy` thresholds enforced at assignment. Security reviewers with improving `REVIEW_THOROUGHNESS` scores receive more sensitive PRs. Post-merge production incidents trigger a `FLAGGED` attestation — routing shifts automatically, no manual configuration. The `isBootstrap()` / `isBorderline()` logic designed in Layer 1 becomes active here.

Trust maturity model (DT-006) moves from Phase 0 (identical to Gastown) toward Phases 1–3 as attestations accumulate. Full description: `docs/PROGRESS.md` DT-004, DT-005, DT-006.

### The gap comments addressed / Key wiring / Gotchas / Pattern

🔲 All to fill in when built.

---

## Layer 7 — Comparison vs naive AI code review

**Completed:** 🔲 not yet built
**Issue:** 🔲 no issue yet
**Tutorial objectives:** `../parent/docs/tutorial-strategy.md §7.5.1` Layer 7 table

### What it shows

🔲 Explicit contrast with existing tools (GitHub Copilot, CodeRabbit) on: formal accountability per reviewer, response SLA, formal DECLINE, incident traceability, trust-weighted routing, adaptive routing on code content. From `tutorial-strategy.md`:

| Requirement | GitHub Copilot / CodeRabbit | CaseHub devtown |
|---|---|---|
| Formal accountability per reviewer | Not addressed | COMMAND commitment lifecycle |
| Reviewer response SLA | Not addressed | WorkItem claimDeadline |
| DECLINE when outside expertise | Not addressed | Formal scope boundary, re-routed |
| Trace production incident to missed finding | Not addressed | causedByEntryId chain |
| Trust-weighted routing | Not addressed | EigenTrust from outcome attestations |
| Adaptive routing on code content | Static rules | Engine binding conditions on case context |

🔲 Fill in with actual code comparisons when all prior layers are complete.
