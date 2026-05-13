# Contributor Trust for PR Routing

**The problem:** AI-generated PRs are flooding open source projects. Reviewers can't keep up. Manual triage doesn't scale.

**The idea:** Route PRs by contributor reputation — built automatically from outcomes, not assigned manually.

---

## How it works

**Contributors earn a trust score from PR history:**

| Event | Score impact |
|-------|-------------|
| PR merged, first submission | Rises |
| PR returned for rework | Drops |
| PR rejected | Drops faster |

**Score feeds directly into queue priority:**

| Queue | Who lands here |
|-------|---------------|
| Fast-track | High-trust contributors |
| Borderline | Promising but limited history |
| Triage | New or low-trust accounts — ordered by file-path risk |
| Security | Any PR touching sensitive paths, regardless of trust |

**No history = triage. Always.** A slop generator that creates a new account every PR can never escape triage — their score never builds because their PRs don't merge.

---

## The scoring model

- Uses **Bayesian Beta** — the same model used in medical trial analysis and A/B testing
- Tracks *confidence* alongside success rate — 2 PRs merged ≠ 200 PRs merged
- Requires a configurable minimum number of observations before acting on a score
- **Bootstrapped from existing history on day one** — GitHub's API gives us years of PR data before the system goes live

---

## Vouching

Genuine newcomers have no history. Vouching solves this.

- A high-trust contributor sponsors a newcomer → newcomer gets a score boost
- **The voucher's own score is at risk** if the vouchee's PRs get returned
- Uses **EigenTrust** (same family as PageRank) — a vouch from a 10-year committer carries more weight than one from a recent joiner
- Can only vouch for contributors with lower trust than you — no inflation rings

---

## How it fits DevTown

DevTown already routes PRs to the right *reviewer* by trust. This adds the same model to the *intake* side.

```
PR arrives → contributor trust → queue priority
                                       ↓
                            reviewer routing (existing)
                                       ↓
                            outcomes update both scores
```

One trust ledger. One pipeline. Not two apps.

**Internal teams:** set minimum observations to zero — everyone pre-trusted. System operates as reviewer routing only.  
**Open source:** both sides active. Full pipeline.

---

## What's already built

| Capability | Status |
|-----------|--------|
| Bayesian Beta scoring model | ✅ In platform |
| EigenTrust propagation | ✅ In platform |
| Work queues + labels + SLA tracking | ✅ In platform |
| Cryptographic audit trail | ✅ In platform |
| GitHub event integration | 🔲 To build |
| History mining (bootstrap) | 🔲 To build |
| Vouching UI | 🔲 To build |
| Contributor score visibility | 🔲 To build |

---

## Open questions

- Score scope: per-project, per-org, or portable across projects?
- Vouching: maintainers only, or any trusted contributor?
- Score decay: does dormancy reduce a score over time?
- Transparency: do contributors see their score? Can they dispute events?
