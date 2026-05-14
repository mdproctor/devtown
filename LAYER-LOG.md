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

Expected pattern (`NaivePrReviewService` not yet built — see §What it shows):
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

- **On day 1, trust-based routing appears identical to Gastown (no differentiation)**
  - Symptom: routing assigns work to agents without apparent trust weighting; the `RoutingPolicy` thresholds seem to have no effect
  - Cause: all agents are in bootstrap mode (`isBootstrap()` returns true) because `minimumObservations` hasn't been reached. This is correct — a trust score from 2–3 attestations is noise, not signal. Routing falls back to availability, identical to Gastown's GUPP model.
  - Fix: no fix required; this is the Phase 0 maturity model by design. Routing quality improves automatically as attestations accumulate. See DT-006 in `docs/PROGRESS.md` for the four-phase model.

### Pattern to replicate (in another domain)

Steps marked `🔲` do not yet have a devtown reference implementation — the pattern is documented from design; the code will exist once Layer 1 is complete.

1. Create `{domain}-domain` Maven module — zero framework imports, zero JPA, no Quarkus
2. Identify your domain's work types. Ask: are they analytical (trust-scored on quality), operational (trust-scored on outcome), human decisions (with SLA and lifecycle), or system-oversight (triggered by uncertainty)? Create a typed class per category — not a flat string list
3. Define `RoutingPolicy` values for trust-sensitive capabilities: set `threshold` (minimum trust), `minimumObservations` (credibility gate), `borderlineMargin` (uncertainty band → human oversight), `fallbackType`, and `rationale`
4. Implement `CapabilityRegistry` SPI in `{domain}.spi` — `capabilities()`, `policy()`, `isKnown()` as default method calling `capabilities()`
5. Implement the populated default registry in `{domain}` — concrete class, no CDI
6. Create `{domain}-app` Maven module — depends on `{domain}-domain`; owns all Quarkus wiring
7. Add a one-liner `@ApplicationScoped` CDI wrapper in `{domain}-app` extending your registry
8. 🔲 Implement the naive service with `@ApplicationScoped @DefaultBean` — direct calls, no CaseHub, gap comments for every compliance gap
9. 🔲 Expose `POST /api/{domain-noun}` via a REST resource injecting the port interface
10. Boot test: verify `@QuarkusTest` starts and CDI discovers the registry bean
11. 🔲 Test the naive service: plain `new`, no Quarkus — verify gap comments are exercisable

