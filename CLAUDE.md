# devtown Workspace

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

## Rules

- All methodology artifacts go here, not in the project repo
- Promotion to project repo is always explicit — never automatic
- Workspace branches mirror project branches — switch both together

## Routing

| Artifact   | Destination | Notes |
|------------|-------------|-------|
| adr        | workspace   | |
| blog       | workspace   | |
| design     | workspace   | |
| snapshots  | workspace   | |
| specs      | workspace   | |
| handover   | workspace   | |

---

# casehub-devtown — Claude Code Project Guide

## Platform Context

This repo is one component of the casehubio multi-repo platform. **Before implementing anything — any feature, SPI, data model, or abstraction — run the Platform Coherence Protocol.**

> **Platform docs:** Paths below are local (use `Read`). If the path does not exist — standalone clone on another machine — replace `/Users/mdproctor/claude/casehub/parent/docs/` with `https://raw.githubusercontent.com/casehubio/parent/main/docs/` and use `WebFetch`.

The protocol asks: Does this already exist elsewhere? Is this the right repo for it? Does this create a consolidation opportunity? Is this consistent with how the platform handles the same concern in other repos?

**Platform architecture (fetch before any implementation decision):**
```
/Users/mdproctor/claude/casehub/parent/docs/PLATFORM.md
```

**Foundation repo deep-dives** (fetch the relevant ones when your implementation touches their domain):
- casehub-engine: `/Users/mdproctor/claude/casehub/parent/docs/repos/casehub-engine.md`
- casehub-ledger: `/Users/mdproctor/claude/casehub/parent/docs/repos/casehub-ledger.md`
- casehub-work: `/Users/mdproctor/claude/casehub/parent/docs/repos/casehub-work.md`
- casehub-qhorus: `/Users/mdproctor/claude/casehub/parent/docs/repos/casehub-qhorus.md`
- casehub-connectors: `/Users/mdproctor/claude/casehub/parent/docs/repos/casehub-connectors.md`
- claudony: `/Users/mdproctor/claude/casehub/parent/docs/repos/claudony.md`

---

## Project Type

type: java

**Stack:** Java 21 (on Java 26 JVM), Quarkus 3.32.2, GraalVM 25 (native image target)

---

## What This Project Is

`casehub-devtown` is the **AI-assisted software development application** built on the CaseHub platform foundation. It is the CaseHub answer to Gastown — a production software engineering coordination system — but built on a domain-agnostic foundation rather than baked into infrastructure.

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

## Reference Documents

These live in `docs/` in this repo and should be read before any significant implementation:

| Document | What it covers |
|----------|---------------|
| `docs/gastown-casehub-analysis-v2.md` | Full architectural comparison — foundation vs foundation, application vs application, roadmap, 32-finding coherence audit, phase gates |
| `docs/gastown-casehub-analysis.md` | Earlier version — useful for background and alternative framing |
| `docs/orchestration-advantages.md` | Seven concrete ACM advantages over workflow engines for PR review scenarios — with YAML examples |

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
| Content-driven routing | P0 complete (engine#186 merged) |
| Parallel check execution | P0 complete |
| Trust-weighted assignment | P1 complete (P1.3 — TrustWeightedSelectionStrategy wired) |
| Human review WorkItem | P0 complete, casehub-work-adapter HITL wiring |
| Merge queue (full) | P1 complete |
| Cryptographic audit | P1.4 ✅ DONE (CaseLedgerEntry merged 2026-04-26) |
| Failure routing (DECLINED vs FAILED) | P0 complete (qhorus#124 claudony persona mapping) |
| Recovery on stuck reviewer | P1.2 RecoveryPolicy SPI |
| Cross-deployment trust | P2.1 TrustExport/ImportService |

**Current foundation status (as of 2026-05-11):**
- P0.1 engine-side ✅ DONE — engine#186 closed
- P0.2 ✅ DONE — qhorus#123, commitment outcomes → trust scoring
- P0.3 ActorTypeResolver ✅ DONE — all consumers updated
- P0.3 InstanceActorIdProvider SPI ✅ DONE — claudony persona mapping still pending (qhorus#124)
- P1.4 CaseLedgerEntry ✅ DONE — merged 2026-04-26
- **Remaining P0:** qhorus#124 claudony persona→session mapping (no end-to-end trust accumulation yet)
- **Remaining P1:** concurrency throttling (P1.1), RecoveryPolicy SPI (P1.2), TrustWeightedSelectionStrategy wired (P1.3), Doltgres backend (P1.5)

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


## Development Workflow

Before designing: `superpowers:brainstorming`
Before implementing: `superpowers:test-driven-development`
Before committing: `superpowers:requesting-code-review`

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
