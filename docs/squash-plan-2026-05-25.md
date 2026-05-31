# Squash Plan — 2026-05-25

Range: `backup/pre-squash-main-20260523..HEAD` (40 commits → 20 commits, 20 absorbed)
Working branch: `squash/wip-main-20260525-150119`

Execution note: `--strategy-option=theirs` on conflict (user: skip conflicts).

---

## Already Clean — 0 commits
All 40 commits are in scope for classification.

---

## Group 1 — Domain refactor
*1 commit — no action*

✅ KEEP `36cd5f7` refactor(domain): promote CapabilityRegistry.isKnown to SPI default method

---

## Group 2 — Design spec promotion
*2 commits → 1*

**Final message:** `docs: promote Epic 1+2 design specs to project repo and add Git Discipline convention`

| Commit | Action | Curated result |
|--------|--------|----------------|
| `1de38f4` docs: promote Epic 1 and Epic 2 design specs to project repo | ✅ KEEP | *(see Final message above)* |
| `8d8d068` docs(claude): add Git Discipline section — workspace/project repo split | 🔽 SQUASH ↑ | *(row 11 docs(claude) — absorbed)* |

> **Result:** 1 commit.

---

## Group 3 — Contributor trust proposal (devtown#24)
*1 commit — no action*

✅ KEEP `ff17666` docs: add contributor trust TL;DR proposal (devtown#24)

---

## Group 4 — Agentic harness goals + LAYER-LOG
*2 commits → 1*

**Final message:** `docs: add agentic harness goals, LAYER-LOG.md obligation, and Layer 1-7 placeholders`

| Commit | Action | Curated result |
|--------|--------|----------------|
| `e6d2415` docs: add agentic harness goals, LAYER-LOG.md obligation, and reference docs | ✅ KEEP | *(see Final message above)* |
| `bee846d` docs: add retroactive LAYER-LOG.md — Layers 1-7 with placeholders | 🔽 SQUASH ↑ | *(same LAYER-LOG document — follow-on)* |

> **Result:** 1 commit.

---

## Group 5 — Layer 1 Part B: naive service + REST dispatcher
*3 commits → 1*

**Final message:** `feat(app): Layer 1 Part B — naive PR review service, gap comments, and REST dispatcher`

| Commit | Action | Curated result |
|--------|--------|----------------|
| `4c32fc2` feat(app): Layer 1 Part B — naive PR review service with gap comments | ✅ KEEP | *(see Final message above)* |
| `7e11bdc` feat(app): PrReviewResource — POST /api/reviews dispatcher | 🔀 MERGE ↑ | *(Part 2 of Layer 1 Part B — same feature, REST layer)* |
| `e494867` docs(specs): Layer 1 Part B naive service design spec | 🔽 SQUASH ↑ | *(pre-implementation spec absorbed into feat)* |

> **Result:** 1 commit.

---

## Group 6 — Layer 1 API stability: PrFinding and PrVerdict
*1 commit — no action*

✅ KEEP `b9a10ca` feat(review): introduce PrFinding and PrVerdict for Layer 1 API stability

---

## Group 7 — Layer 1 integration test
*1 commit — no action*

✅ KEEP `4ac0335` test(app): extract NaivePrReviewService fixture and add PrReviewResource integration test

---

## Group 8 — Epic 3 / Layer 5: CasePlanModel with CDI wiring
*3 commits → 1*

**Final message:** `feat(review): Epic 3 PR review CasePlanModel — YAML definition, CDI wiring, and REST integration`

| Commit | Action | Curated result |
|--------|--------|----------------|
| `4280214` feat(review): PR review CasePlanModel YAML — 9 bindings, 3 goals | ✅ KEEP | *(see Final message above)* |
| `16be0c7` feat(app): PrReviewCaseHub and PrReviewCaseService — displaces naive impl | 🔀 MERGE ↑ | *(CDI wiring for the YAML definition — Part 2 of same Layer 5 capability)* |
| `8219879` docs(specs): Epic 3 PR review CasePlanModel design spec | 🔽 SQUASH ↑ | *(pre-implementation spec absorbed into feat)* |

> **Result:** 1 commit.

---

## Group 9 — 27 binding condition unit tests
*1 commit — no action*

✅ KEEP `a0576d6` test(review): 27 binding condition unit tests — TDD, pure unit, no Quarkus

---

## Group 10 — HITL wiring (#33)
*2 commits → 1*

| Commit | Action | Curated result |
|--------|--------|----------------|
| `22dd729` feat: wire casehub-engine-work-adapter for HITL case resumption (Closes #33) | ✅ KEEP | *(message adequate — unchanged)* |
| `7428908` chore: add DESIGN.md stub — Refs casehubio/parent#31 | 🔽 SQUASH ↑ | *(row 6 chore; ⚠️ proximity-grouped — different concern from HITL, no semantic home in range)* |

> **Result:** 1 commit.

---

## Group 11 — HITL end-to-end test (devtown#30)
*1 commit — no action*

✅ KEEP `a9cd797` test(app): HITL end-to-end integration test for human approval binding (devtown#30)

---

## Group 12 — Non-persistence CDI build fix (#31)
*2 commits → 1*

| Commit | Action | Curated result |
|--------|--------|----------------|
| `cc8af49` fix(build): resolve non-persistence CDI deployment problems (Closes #31, Refs #39) | ✅ KEEP | *(message adequate — unchanged)* |
| `0aaadc0` docs: preserve squash plan artifact from 2026-05-23 session | 🔽 SQUASH ↑ | *(⚠️ workspace methodology artifact — proximity-grouped into nearest KEEP; no project-semantic home)* |

> **Result:** 1 commit.

---

## Group 13 — Layer 2 SLA breach policy design spec (devtown#38)
*1 commit — no action*

✅ KEEP `a5b8551` docs(specs): Layer 2 SLA breach policy design — devtown#38

---

## Group 14 — Layer 2: complete SLA-bounded human review gate (#41)
*8 commits → 1*

**Final message:** `feat: Layer 2 — SLA-bounded human review gate with two-tier escalation (Closes #41)`
*(message adequate — original message covers the full layer; all partial commits absorbed)*

| Commit | Action | Curated result |
|--------|--------|----------------|
| `c65f692` feat: Layer 2 — SLA-bounded human review gate with escalation (Closes #41) | ✅ KEEP | *(see Final message above — PRIMARY; 486 ins across 12 files)* |
| `758ec4d` feat(app): SlaBreachPolicyBean + SlaBreachHandler — two-tier SLA breach signaling — Refs #41 | 🔀 MERGE ↑ | *(CDI wiring for breach signaling — part of same Layer 2 capability)* |
| `dfda3ad` feat(domain): DefaultSlaBreachPolicy — stateless two-tier SLA escalation — Refs #41 | 🔀 MERGE ↑ | *(domain policy implementation — part of same Layer 2 capability)* |
| `5689ee7` feat(domain): add SLA preference types and keys — Refs #41 | 🔀 MERGE ↑ | *(SLA configuration types — part of same Layer 2 capability)* |
| `8c259eb` docs(specs): reconcile Layer 2 spec against shipped APIs — Refs #41 | 🔽 SQUASH ↑ | *(post-implementation spec reconciliation; issue ref preserved via c65f692 Closes #41)* |
| `e5e330d` feat(review): add candidateGroups and expiresIn to human-approval binding — Refs #41 | 🔽 SQUASH ↑ | *(2-line change — row 17 size threshold; issue ref preserved via c65f692)* |
| `fc7c52c` fix(review): code review fixes — Refs #41 | 🔽 SQUASH ↑ | *(<10 lines — review fixup; issue ref preserved via c65f692)* |
| `a72001d` docs(claude): sync layer status — work#212 resolved, Layer 2 issue #41 | 🔽 SQUASH ↑ | *(row 11 docs(claude) — absorbed)* |

> **Result:** 1 commit.

---

## Group 15 — Engine persistence SPIs + CI + docs cleanup (#40)
*5 commits → 1*

| Commit | Action | Curated result |
|--------|--------|----------------|
| `c43965d` fix(build): wire engine persistence SPIs for production Quarkus build (Closes #40) | ✅ KEEP | *(message adequate — unchanged)* |
| `242fe61` ci: use GH_PAT for Maven package resolution — cross-repo packages require PAT | 🔽 SQUASH ↑ | *(row 14 ci: — absorbed into preceding KEEP)* |
| `a789732` fix(ci): add \<repositories\> with casehubio/* wildcard to devtown parent pom | 🔽 SQUASH ↑ | *(row 15 fix(ci): — absorbed into preceding KEEP)* |
| `002096f` docs(claude): update engine SPI persistence approach and selected-alternatives note | 🔽 SQUASH ↑ | *(row 11 docs(claude) — semantic home: same persistence topic)* |
| `af56a1e` docs: fix Layer 5 blog reference in LAYER-LOG.md | 🔽 SQUASH ↑ | *(1 line, no issue ref — row 17; ⚠️ proximity-grouped — semantic home is LAYER-LOG group earlier)* |

> **Result:** 1 commit.

---

## Group 16 — SlaBreachHandlerWiringTest + pre-push hook
*3 commits → 1*

| Commit | Action | Curated result |
|--------|--------|----------------|
| `d12394b` test(app): add SlaBreachHandlerWiringTest — verify CDI displacement and handler event routing | ✅ KEEP | *(message adequate — unchanged)* |
| `418078c` docs(specs): SlaBreachHandlerWiringTest design — direct event fire, no CapturingBreachPolicy | 🔽 SQUASH ↑ | *(pre-implementation spec absorbed into test)* |
| `af56a1e` → already accounted for in Group 15 | | |

> **Result:** 1 commit.

---

## Group 17 — Git hooks infrastructure
*1 commit — promoted to KEEP micro-commit*

✅ KEEP `32b108b` chore: add git hooks — pre-push  no-issue: infrastructure
*(No semantic home found within 5 commits forward — promoted to standalone micro-commit per policy)*

---

## Group 18 — Progress docs sweep (#45)
*1 commit — no action*

✅ KEEP `1fd2c88` docs: sweep all casehub repos and bring progress/gaps docs current — Closes #45

---

## Group 19 — Remove qhorus reactive workaround (#25)
*1 commit — no action*

✅ KEEP `dbd7178` chore(app): remove qhorus reactive workaround from test properties — Closes #25

---

## Group 20 — Test coverage parity (#32)
*1 commit — no action*

✅ KEEP `93adfcc` test(review): add missing doesNotFire_whenAlreadyDone for parallel checks — Closes #32

---

## AFTER — what `git log --oneline` will show

```
  40  commits (original)
 -20  absorbed by squash/merge
  ──────────────────────────────────
  20  commits — no content lost
```

Sample (post-squash, newest first):
```
???  test(review): add missing doesNotFire_whenAlreadyDone for parallel checks — Closes #32
???  chore(app): remove qhorus reactive workaround from test properties — Closes #25
???  docs: sweep all casehub repos and bring progress/gaps docs current — Closes #45
???  chore: add git hooks — pre-push  no-issue: infrastructure
???  test(app): add SlaBreachHandlerWiringTest — verify CDI displacement and handler event routing
???  fix(build): wire engine persistence SPIs for production Quarkus build (Closes #40)
???  feat: Layer 2 — SLA-bounded human review gate with two-tier escalation (Closes #41)
???  docs(specs): Layer 2 SLA breach policy design — devtown#38
???  fix(build): resolve non-persistence CDI deployment problems (Closes #31, Refs #39)
???  test(app): HITL end-to-end integration test for human approval binding (devtown#30)
???  feat: wire casehub-engine-work-adapter for HITL case resumption (Closes #33)
???  test(review): 27 binding condition unit tests — TDD, pure unit, no Quarkus
???  feat(review): Epic 3 PR review CasePlanModel — YAML definition, CDI wiring, and REST integration
???  test(app): extract NaivePrReviewService fixture and add PrReviewResource integration test
???  feat(review): introduce PrFinding and PrVerdict for Layer 1 API stability
???  feat(app): Layer 1 Part B — naive PR review service, gap comments, and REST dispatcher
???  docs: add agentic harness goals, LAYER-LOG.md obligation, and Layer 1-7 placeholders
???  docs: add contributor trust TL;DR proposal (devtown#24)
???  docs: promote Epic 1+2 design specs to project repo and add Git Discipline convention
???  refactor(domain): promote CapabilityRegistry.isKnown to SPI default method
```
(SHAs shown as ??? — assigned after rebase executes)
