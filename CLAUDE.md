# devtown Workspace

**Name:** casehub-devtown
**Project repo:** /Users/mdproctor/claude/casehub/devtown
**Workspace type:** public

## Session Start

Run `add-dir /Users/mdproctor/claude/casehub/devtown` before any other work.

## Artifact Locations

| Skill | Writes to |
|-------|-----------|
| brainstorming (specs) | `specs/` |
| writing-plans (plans) | `plans/` |
| handover | `HANDOFF.md` |
| idea-log | `IDEAS.md` |
| design-snapshot | `snapshots/` |
| java-update-design / update-primary-doc | `design/JOURNAL.md` (created by `epic`) |
| adr | `adr/` |
| write-blog | `blog/` |

## Structure

- `HANDOFF.md` — session handover (single file, overwritten each session)
- `IDEAS.md` — idea log (single file)
- `specs/` — brainstorming / design specs (superpowers output)
- `plans/` — implementation plans (superpowers output)
- `snapshots/` — design snapshots with INDEX.md (auto-pruned, max 10)
- `adr/` — architecture decision records with INDEX.md
- `blog/` — project diary entries with INDEX.md
- `design/` — epic journal (created by `epic` at branch start)

## Git Discipline

Two git repositories are active in every session:
- **Workspace** (`/Users/mdproctor/claude/public/casehub/devtown`) — methodology artifacts: handover, blog, specs, plans, ADRs
- **Project repo** (`/Users/mdproctor/claude/casehub/devtown`) — source code

Before any git operation, run `git rev-parse --show-toplevel` to confirm which repo is currently active. Do not assume — the session may have opened in either. cd to the correct repo before staging:
- Source code commits → project repo
- Methodology artifacts → workspace


## Rules

- All methodology artifacts go here, not in the project repo
- Promotion to project repo is always explicit — never automatic
- Workspace branches mirror project branches — switch both together

## Routing

| Artifact   | Destination | Notes |
|------------|-------------|-------|
| adr        | project     | lands in `docs/adr/` — promoted at epic close |
| specs      | project     | lands in `docs/specs/` — promoted at epic close |
| blog       | workspace   | staged here; published to mdproctor.github.io via publish-blog |
| plans      | workspace   | stay in workspace permanently |
| design     | workspace   | epic journal stays in workspace |
| snapshots  | workspace   | stay in workspace permanently |
| handover   | workspace   | |

---

# casehub-devtown — Claude Code Project Guide

## Platform Context

This repo is one component of the casehubio multi-repo platform. **Before implementing anything — any feature, SPI, data model, or abstraction — run the Platform Coherence Protocol.**

> **Platform docs:** Local paths use `../parent/docs/` as root. If a path doesn't exist, the parent repo isn't cloned locally — fetch from `https://raw.githubusercontent.com/casehubio/parent/main/docs/<path>` instead.

The protocol asks: Does this already exist elsewhere? Is this the right repo for it? Does this create a consolidation opportunity? Is this consistent with how the platform handles the same concern in other repos?

**Platform architecture (fetch before any implementation decision):**
```
../parent/docs/PLATFORM.md
```

**Foundation repo deep-dives** (fetch the relevant ones when your implementation touches their domain):
- casehub-engine: `../parent/docs/repos/casehub-engine.md`
- casehub-ledger: `../parent/docs/repos/casehub-ledger.md`
- casehub-work: `../parent/docs/repos/casehub-work.md`
- casehub-qhorus: `../parent/docs/repos/casehub-qhorus.md`
- casehub-connectors: `../parent/docs/repos/casehub-connectors.md`
- claudony: `../parent/docs/repos/claudony.md`

---

## Project Type

type: java

**Stack:** Java 21 (on Java 26 JVM), Quarkus 3.32.2, GraalVM 25 (native image target)

---

## Agentic Harness Goals

**Read first:** `../parent/docs/AGENTIC-HARNESS-GUIDE.md`

**Primary goal:** Reference architecture and field showcase for Java developers in software engineering and DevOps — demonstrating formal accountability, tamper-evident review records, and adaptive routing in a domain every developer knows from daily practice.

**Secondary goal:** LLM and human tutorial material, produced as a by-product of building the application correctly. The tutorial structure emerges from the layered adoption sequence — do not design for the tutorial.

**LAYER-LOG.md** (`LAYER-LOG.md` at project root) is the primary new artifact. A layer entry is complete when the layer closes — written in full at that point, not incrementally with placeholders.

**Epics ≠ layers.** Epics organize work by build convenience; layers organize knowledge by teaching progression. One layer may span multiple epics. The layer table in `../parent/docs/repos/casehub-devtown.md` tracks layer status (pending / in progress / complete) — update it when a layer makes meaningful progress, not only when it finishes.

---

## What This Project Is

`casehub-devtown` is an **agentic harness for software engineering coordination** built on the CaseHub platform foundation. It coordinates specialist code reviewers, human review task gates with SLA, and adaptive PR routing based on code content — producing a tamper-evident review record where every missed finding is traceable. Field showcase and tutorial for Java developers in software engineering and DevOps.

It is the CaseHub answer to Gastown — a production software engineering coordination system — but built on a domain-agnostic foundation rather than baked into infrastructure.

The foundation (casehub-engine, casehub-qhorus, casehub-ledger, casehub-work, casehub-connectors) has **no domain knowledge**. It knows about cases, bindings, workers, commitments, trust, and audit. devtown provides the software engineering domain logic on top of those primitives: what a PR review is, what a merge queue does, what capabilities are required for what work, how trust accumulates from review outcomes.

**This is the reference application for the CaseHub platform.** It demonstrates and validates the layered architecture for all future domain applications (healthcare, legal, financial compliance, etc.).

### Why devtown, not just Gastown?

The detailed architectural comparison lives in `docs/gastown-casehub-analysis-v2.md`. Short version:

- Gastown is a production system for the same domain — study it for what it does, not how it does it
- Gastown's merge queue, agent model, and git integration are domain logic baked into infrastructure — they cannot be separated or reused for other domains
- devtown uses CaseHub primitives to provide the same capabilities with: formal obligation tracking, cryptographic audit, trust-weighted routing, GDPR compliance, and adaptive case management that Gastown cannot provide structurally
- The ACM/blackboard paradigm advantages over Gastown's workflow model are documented in `docs/orchestration-advantages.md`

---

## Layering Rule

**This is an application, not a framework.** When in doubt: if the capability requires knowledge of software engineering concepts (PRs, commits, CI, code review, merge queues, GitHub), it belongs here. If it is purely about actors, trust, commitments, cases, or audit records, it belongs in the foundation.

Never add to the foundation what is specific to this domain. Never re-implement foundation primitives here.

---

## Reference Documents (casehub-parent)

| Document | What it covers |
|----------|---------------|
| `../parent/docs/AGENTIC-HARNESS-GUIDE.md` | Goals, what to produce, retroactive work instructions, layer maintenance |
| `../parent/docs/repos/casehub-devtown.md` | Harness structure, tutorial layers table, layer status |
| `../parent/docs/tutorial-strategy.md` | Devtown tutorial layers §7.5 — teaching objectives and code sketches per layer |
| `../garden/docs/protocols/casehub/HARNESS-INDEX.md` | CaseHub app protocols |
| `../garden/docs/protocols/universal/INDEX.md` | Universal Java/Quarkus protocols |

## Reference Documents (this repo)

These live in `docs/` in this repo and should be read before any significant implementation:

| Document | What it covers |
|----------|---------------|
| `docs/gastown-casehub-analysis-v2.md` | Full architectural comparison — foundation vs foundation, application vs application, roadmap, 32-finding coherence audit, phase gates |
| `docs/gastown-casehub-analysis.md` | Earlier version — useful for background and alternative framing |
| `docs/orchestration-advantages.md` | Seven concrete ACM advantages over workflow engines for PR review scenarios — with YAML examples |

---

## Design Phase References

Read these **before designing**, not after. The concern column tells you when each applies.

### Domain model and API design

| Concern | Read first |
|---------|-----------|
| Designing a new entity, record, or SPI | `casehub-devtown.md` — does devtown already own this? `PLATFORM.md` capability ownership table — does the foundation own it? |
| Module placement (`domain/` vs `review/` vs `app/`) | Three-tier rule: `devtown-domain` = pure Java (no Quarkus), `review` = integration logic (casehub-work/engine deps), `app` = all CDI wiring. Port interface (`PrReviewApplicationService`) lives in `review/`, not `app/` — prevents a module dependency cycle |
| Naming capability tags or trust dimensions | `ReviewDomain`, `AgentQualification`, `HumanDecision`, `HumanOversight`, `DevtownTrustDimension` in `devtown-domain` — extend these rather than creating parallel types |
| Mapping features to Gastown parity | `docs/gastown-casehub-analysis-v2.md` Gastown feature parity checklist — which Gastown capability does this correspond to, and does devtown's approach improve on it? |

### Tutorial layer design

| Concern | Read first |
|---------|-----------|
| Deciding which layer a feature belongs in | `tutorial-strategy.md §7.5` — layer teaching objectives and what each layer must NOT include |
| Understanding the `@DefaultBean` displacement pattern | LAYER-LOG.md Layer 1 §Key wiring — `NaivePrReviewService @DefaultBean` is displaced at CDI level by each subsequent layer; the naive class is never deleted |
| Writing gap comments | `NaivePrReviewService.java` — five gap comments model the Layer 1 anti-pattern; each subsequent layer closes exactly one gap |
| Documenting a completed layer | LAYER-LOG.md — write the entry in full when the layer closes; no incremental placeholders |

### Foundation integration

| Concern | Read first |
|---------|-----------|
| Using casehub-work (WorkItem, SLA, escalation) | `../parent/docs/repos/casehub-work.md` |
| Using casehub-qhorus (COMMAND/RESPONSE/DONE/DECLINE) | `../parent/docs/repos/casehub-qhorus.md` |
| Using casehub-ledger (Merkle audit, GDPR, trust scoring) | `../parent/docs/repos/casehub-ledger.md` |
| Using casehub-engine (CasePlanModel, adaptive paths, bindings) | `../parent/docs/repos/casehub-engine.md` |
| Boundary check — foundation or devtown? | `PLATFORM.md` boundary rules; Layering Rule in this file — if it requires knowledge of PRs, code review, CI, or merge queues, it belongs here |
| Implementing a `@Transactional` loop with control-flow exceptions | PP-20260522-f08b62 — private exceptions must not escape the loop boundary; validate eagerly at factory and catch at top-level dispatch |

### Persistence and migrations

| Concern | Read first |
|---------|-----------|
| Writing a new Flyway migration | `../garden/docs/protocols/universal/flyway-migration-rules.md` — naming, H2 MODE=PostgreSQL |
| Assigning a migration version number | V1–V999 devtown domain; V2000+ ledger subclass join tables |
| Adding casehub-work JPA persistence | devtown#34 — `casehub-persistence-hibernate` must be in the production assembly; without it, engine CDI SPIs are unsatisfied at augmentation |

### Testing

| Concern | Read first |
|---------|-----------|
| Writing a `@QuarkusTest` for HITL bindings | PP-20260521-134c38 (pre-seed all parallel check keys with non-null values); PP-20260521-a36692 (MemoryPlanItemStore in `quarkus.arc.selected-alternatives`) |
| Testing SPI wiring | `../garden/docs/protocols/universal/spi-testing-alternative-inner-classes.md` — `@Alternative` static inner classes, not Mockito |
| `@QuarkusTest` database setup | `../garden/docs/protocols/universal/quarkus-test-database.md` — H2 MODE=PostgreSQL, datasource config |

---

## What devtown Must Build

### The Domain Model

**Vocabulary types** — typed constant classes replacing the original flat namespace (Epic 2 ✅):

| Class | Constants | What they represent |
|-------|-----------|---------------------|
| `ReviewDomain` | `code-analysis`, `security-review`, `architecture-review`, `style-review`, `test-coverage`, `performance-analysis` | Analytical work a PR needs; what an AI agent is qualified to perform |
| `AgentQualification` | `ci-runner`, `merge-executor` | Execution capabilities with trust scoring |
| `HumanDecision` | `human-decision:pr-approval` | Formal accountability events requiring human judgment (casehub-work WorkItem lifecycle) |
| `HumanOversight` | `human-oversight:routing-review` | System-level review when automated routing confidence is low (borderline trust, fleet gap, insufficient observations) |

`NOTIFY` removed — connector call, not a trust-scored capability. `BATCH_BISECT`, `COORDINATED_MERGE`, `COORDINATED_ROLLBACK` deferred to CasePlanModel definitions (Epics 4/5, devtown#20).

**Trust dimensions** — how trust is scoped for this domain (Epic 2 ✅):

| Dimension | Measures |
|-----------|---------|
| `review-thoroughness` | Does the agent find issues that later cause incidents? |
| `false-positive-rate` | Does the agent flag issues that turn out to be non-issues? |
| `scope-calibration` | Does the agent correctly DECLINE work outside its capability? |

`security-specialist` removed — per-capability quality expressed via `ScoreType.CAPABILITY` in the ledger (ledger#76 tracks composite per-capability quality scores).

**Routing policies** — `RoutingPolicy` objects with threshold, minimum observations, and borderline margin (Epic 2 ✅):

| Capability | Threshold | Min observations | Borderline margin | Rationale |
|-----------|-----------|-----------------|-------------------|-----------|
| `security-review` | 0.70 | 10 | 0.05 | Security mistakes reach production |
| `architecture-review` | 0.65 | 8 | 0.05 | Design mistakes are expensive to reverse |
| `style-review` | 0.50 | 5 | — | Baseline — any competent agent |
| `merge-executor` | 0.80 | 15 | 0.05 | Merge is irreversible |

**Trust maturity model:** Phase 0 (no observations) → availability routing identical to Gastown. Routing quality improves automatically as attestations accumulate. See `docs/PROGRESS.md` DT-005/DT-006 and parent#14.

### The CasePlanModels

**PR Review Case** — declared goals, not a sequence of steps:
- Goal: `pr-approved` — sufficient APPROVED reviews accumulated
- Goal: `security-verified` — no critical security findings, or specialist approved
- Goal: `ci-passing` — CI green
- Bindings fire based on what code analysis finds, not what the author declared

**Merge Queue Case (casehub-refinery)** — batch-then-bisect:
- Batch of PRs enters as a case
- Tip-of-batch tested first
- If tip passes: merge batch
- If tip fails: spawn bisect sub-case, identify faulty PR, reject it, retry remainder
- Each batch member is a sub-case with its own PR review case

**Cross-Repo Coordinated Change** — parent + per-repo sub-cases:
- Parent case tracks all sub-cases
- Sub-case fault triggers automatic rollback binding
- Every coordination decision is in the EventLog

### Foundation Gates (what must be working before each capability)

| Capability | Foundation prerequisite |
|-----------|------------------------|
| Content-driven routing | P0 complete (engine#186 merged) ✅ DONE |
| Parallel check execution | P0 complete ✅ DONE |
| PR review CasePlanModel (Epic 3) | P0 complete ✅ DONE — devtown#10 shipped 2026-05-19 |
| Scoped policy preferences | casehub-platform ✅ shipped — `PreferenceProvider` with `Path`-based scope hierarchy and JPA persistence; `Path.root()` ✅ DONE (platform#24 closed, work#212 closed 2026-05-22) |
| Human review WorkItem end-to-end | P0 complete ✅ DONE — casehub-work-adapter wired (devtown#33), e2e test complete (devtown#30 ✅ 2026-05-21) |
| Trust-weighted assignment | P1 complete (P1.3 — TrustWeightedSelectionStrategy wired) |
| Merge queue (full) | P1 complete |
| Cryptographic audit | P1.4 ✅ DONE (CaseLedgerEntry merged 2026-04-26) |
| Failure routing (DECLINED vs FAILED) | P0 complete (qhorus#124 claudony persona mapping) |
| Recovery on stuck reviewer | P1.2 RecoveryPolicy SPI |
| Cross-deployment trust | P2.1 TrustExport/ImportService |

**Current foundation status (as of 2026-05-19):**
- P0.1 engine-side ✅ DONE — engine#186 closed
- P0.2 ✅ DONE — qhorus#123, commitment outcomes → trust scoring
- P0.3 ActorTypeResolver ✅ DONE — all consumers updated
- P0.3 InstanceActorIdProvider SPI ✅ DONE — claudony persona mapping still pending (qhorus#124)
- P1.4 CaseLedgerEntry ✅ DONE — merged 2026-04-26
- **Remaining P0:** qhorus#124 claudony persona→session mapping (no end-to-end trust accumulation yet)
- **Remaining P1:** concurrency throttling (P1.1), RecoveryPolicy SPI (P1.2), TrustWeightedSelectionStrategy wired (P1.3), Doltgres backend (P1.5)

### Tutorial Structure (layer-by-layer, from tutorial-strategy.md §7.5)

```
Layer 1: naive Java — vocabulary model, @DefaultBean naive service, REST entry point ✅ (devtown#8, #9, #27)
Layer 2: + casehub-work — SLA-bounded human review gate with escalation (devtown#41, in progress)
Layer 3: + casehub-qhorus — typed COMMAND/RESPONSE/DONE/DECLINE per reviewer agent interaction
Layer 4: + casehub-ledger — tamper-evident merge decision audit trail
Layer 5: + casehub-engine — adaptive paths, CasePlanModel, content-driven PR routing ✅ (devtown#10)
Layer 6: trust routing — trust-weighted reviewer assignment from outcome attestations
Layer 7: comparison vs Gastown (Refinery/Deacon/Witness architecture)
```

**Note on layer ordering vs build order:** Layer 5 was built before Layers 2–4 because the engine CasePlanModel (adaptive routing, HITL binding) was the architectural priority. Tutorial ordering differs from chronological build order — LAYER-LOG.md preserves teaching sequence, not build sequence.

**`@DefaultBean` displacement pattern:** devtown uses CDI displacement throughout. `NaivePrReviewService @DefaultBean` is never deleted — each layer adds an `@ApplicationScoped` implementation (without `@DefaultBean`) in `review/` that takes CDI priority. The naive class remains in the build, inactive. This is the structural mechanism that makes each layer independently teachable.

---

## Gastown Feature Parity Checklist

Features Gastown's Refinery provides that devtown must match or exceed:

| Gastown feature | devtown approach | Status |
|----------------|-----------------|--------|
| Merge queue (Bors batch-then-bisect) | CasePlanModel + bisect sub-case binding | Not started |
| AI coding agent workers | Claudony WorkerProvisioner (already integrated via claudony-casehub) | Foundation ready |
| Human workspaces (Crew) | Human review WorkItem via casehub-work | Foundation ready |
| Cross-rig agent routing | Sub-case orchestration | Foundation ready |
| CLI tooling (`gt feed`, `gt problems`, etc.) | MCP tools + claudony dashboard extensions | Not started |
| Predecessor session context (`gt seance`) | WorkerContextProvider + Doltgres AS OF (P1.5) | Partial |
| Federated reputation (Wasteland) | TrustExport/ImportService (P2.1) | Not started |
| Sandboxed execution | gt-proxy-server equivalent | Not planned |
| Agent concurrency control (Scheduler) | SpawnThrottle in ClaudonyConfig (P1.1) | Not started |
| Hierarchical watchdog (Witness/Deacon/Boot) | RecoveryPolicy SPI (P1.2) | Not started |

**devtown advantages Gastown cannot provide:**
- Formal obligation tracking per code review assignment (qhorus COMMAND → Commitment)
- Cryptographically tamper-evident merge decision audit (Merkle)
- Trust-weighted reviewer routing (automatic, not stamp-curated)
- GDPR-compliant merge audit (ComplianceSupplement)
- Adaptive routing from code content (ACM bindings, not pre-declared workflow)
- Parallel human + automated checks (WAITING state + simultaneous binding fire)

---

## Build and Test

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26) mvn clean install
```

**Use `mvn` not `./mvnw`** — maven wrapper not configured on this machine.

---

## Ecosystem Conventions

**Quarkus version:** All projects use `3.32.2`. When bumping, bump all projects together.

**GitHub Packages — dependency resolution:** Add to `pom.xml` `<repositories>`:
```xml
<repository>
  <id>github</id>
  <url>https://maven.pkg.github.com/casehubio/*</url>
  <snapshots><enabled>true</enabled></snapshots>
</repository>
```
CI must use `server-id: github` + `GITHUB_TOKEN` in `actions/setup-java`.

**Cross-project SNAPSHOT versions:** All casehubio artifacts are `0.2-SNAPSHOT` resolved from GitHub Packages. Declare in `pom.xml` properties and `<dependencyManagement>` — no hardcoded versions in submodule poms.

**Java on this machine:**
```bash
JAVA_HOME=$(/usr/libexec/java_home -v 26)    # Java 26, use for dev and tests
JAVA_HOME=/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home  # GraalVM 25, native only
```

---


## Design Document Convention

The design doc for this project is `design/JOURNAL.md`, created by `epic` when an
epic opens. Between epics this file does not exist.

When `java-git-commit` or `java-update-design` check for DESIGN.md: if no epic is
active (i.e. `design/JOURNAL.md` does not exist), skip the design sync step entirely.
Do not stop or error — just omit it and proceed.

## Development Workflow

Session start: `work-start` (platform coherence, protocols, issue check, IntelliJ MCPs)
Before designing: `superpowers:brainstorming`
Before implementing: `superpowers:test-driven-development`
For all Java work: `java-dev` (loads `testing-principles` + `ide-tooling`)
Before committing: `superpowers:requesting-code-review`
After implementation: `implementation-doc-sync` (scoped doc sweep)

Living docs — check for drift after significant changes:
- `docs/adr/INDEX.md`

## Work Tracking

**Issue tracking:** enabled
**GitHub repo:** casehubio/devtown

**Automatic behaviours (Claude follows these at all times):**
- **Before implementation begins** — check if an active issue exists. If not, run issue-workflow Phase 1 before writing any code.
- **Before any commit** — confirm issue linkage.
- **All commits should reference an issue** — `Refs #N` (ongoing) or `Closes #N` (done).
- **Exception:** housekeeping commits (doc fixes, dependency bumps) may omit issue links.
