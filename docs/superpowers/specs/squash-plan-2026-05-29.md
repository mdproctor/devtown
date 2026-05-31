# Squash Plan — devtown main — 2026-05-29

Range: upstream/main..HEAD  |  15 commits → 7 commits  |  8 absorbed

---

## Already Clean — 0 commits
*All commits require action.*

---

## Group 1 — chore(#46): workaround removal
*Keep standalone — 1 commit*

✅ KEEP `91e3dd5` chore(#46): remove validate-unknown=false workaround — qhorus @ConfigRoot BUILD_TIME resolves SRCFG00050

> **Result:** 1 commit (unchanged)

---

## Group 2 — refactor(#48,#49): rename PrReviewService
*Keep standalone — 1 commit*

✅ KEEP `9919e4e` refactor(#48,#49): rename PrReviewService, update LAYER-LOG navigation and terminology

> **Result:** 1 commit (unchanged)

---

## Group 3 — test(review): parallel check guard tests
*Keep standalone — 1 commit*

✅ KEEP `a50ef6e` test(review): add doesNotFire_whenAlreadyDone for testCoverage and performanceAnalysis

> **Result:** 1 commit (unchanged)

---

## Group 4 — docs: LAYER-LOG restructure as SIAL
*Compaction group — 4 commits → 1*
**Final message:** `docs: restructure LAYER-LOG.md as SIAL — vertical-slice index, arch headers, and pattern additions`

| Commit | Action | Curated result |
|--------|--------|----------------|
| `a23b40c` docs: add vertical-slice build approach note to LAYER-LOG.md | 🔽 SQUASH ↑ | *(absorbed — precursor note; context in Final message)* |
| `7251a07` docs: restructure LAYER-LOG.md as SIAL — vertical slice index + arch headers | ✅ KEEP | *(see Final message above)* |
| `d8f8360` docs: fix build approach framing — layers are implementation unit, slices are delivery unit | 🔽 SQUASH ↑ | *(absorbed — minor wording fix)* |
| `d752560` docs: add Vertical Slices arch pattern to S1 in slice index | 🔽 SQUASH ↑ | *(absorbed — additive follow-on to same document)* |

> **Result:** 1 commit.

---

## Group 5 — feat(layer3): port types in review/
*Compaction group — 2 commits → 1*
**Final message:** `feat(layer3): add ReviewerOutcome and ReviewerAgent port types (devtown#52)`

| Commit | Action | Curated result |
|--------|--------|----------------|
| `b6661e3` feat(layer3): add ReviewerOutcome sealed interface (devtown#52) | ✅ KEEP | *(see Final message above)* |
| `cec6e24` feat(layer3): add ReviewerAgent driven-port interface (devtown#52) | 🔀 MERGE ↑ | *(unified — both are new port types in review/; same module, same layer)* |

> **Result:** 1 commit.

---

## Group 6 — refactor(layer3): CDI priority
*Keep standalone — 1 commit*

✅ KEEP `0ff38fa` refactor(layer3): add @Alternative @Priority(2) to PrReviewCaseService (devtown#52)

> **Result:** 1 commit (unchanged) — kept standalone: explains the CDI displacement ordering for tutorial layers

---

## Group 7 — feat(layer3): full Layer 3 implementation
*Compaction group — 5 commits → 1*
**Final message:** `feat(layer3): add QhorusPrReviewService, agent stubs, and lifecycle tests (devtown#52)`

| Commit | Action | Curated result |
|--------|--------|----------------|
| `41e9dd5` feat(layer3): add specialist reviewer agent stubs (devtown#52) | 🔀 MERGE ↑ | *(unified — stubs are direct dependencies of QhorusPrReviewService)* |
| `745125c` feat(layer3): add QhorusPrReviewService and lifecycle tests (devtown#52) | ✅ KEEP | *(see Final message above)* |
| `5dc1522` fix(layer3): use null channel description instead of ORCHESTRATOR id (devtown#52) | 🔽 SQUASH ↑ | *(absorbed — code quality fix to the service)* |
| `19ff202` docs: promote Layer 3 spec and ARC42STORIES.MD (devtown#52) | 🔽 SQUASH ↑ | *(absorbed — delivery artifacts for this layer)* |
| `35c097c` fix(layer3): exclude JpaWorkloadProvider from prod ARC scan (devtown#52) | 🔽 SQUASH ↑ | *(absorbed — required fix for full build to pass)* |

> **Result:** 1 commit.

---

## AFTER — what `git log --oneline` will show

  15  commits (original)
  - 8  absorbed by squash
  ──────────────────────────────────────────────
   7  commits (no content lost)

Sample (oldest to newest):
  ???????  chore(#46): remove validate-unknown=false workaround — qhorus @ConfigRoot BUILD_TIME resolves SRCFG00050
  ???????  refactor(#48,#49): rename PrReviewService, update LAYER-LOG navigation and terminology
  ???????  test(review): add doesNotFire_whenAlreadyDone for testCoverage and performanceAnalysis
  ???????  docs: restructure LAYER-LOG.md as SIAL — vertical-slice index, arch headers, and pattern additions
  ???????  feat(layer3): add ReviewerOutcome and ReviewerAgent port types (devtown#52)
  ???????  refactor(layer3): add @Alternative @Priority(2) to PrReviewCaseService (devtown#52)
  ???????  feat(layer3): add QhorusPrReviewService, agent stubs, and lifecycle tests (devtown#52)
