---
id: PP-20260522-fe93b6
title: "Verify peer-repo state before citing it as fact in a design document"
type: rule
scope: platform
applies_to: "all casehubio design documents and specs that reference symbols, methods, or commits in peer repos"
severity: important
refs:
  - casehub/HARNESS-INDEX.md
violation_hint: "Design doc states a symbol, method, or commit 'is already committed', 'ships in', or 'is available in' a peer repo without a verification step — Path.root() was described as 'already committed to platform main' when it existed in none of the platform branches (GE-20260522-9cd6d5)"
created: 2026-05-22
---

Before citing a peer repo's state as fact — a method exists, a feature shipped, a commit landed — verify it with `git -C <repo> grep <symbol> HEAD` or by reading the file. Aspirational or planned state must be marked explicitly: 'planned', 'to be committed', or 'blocked on <issue>'. Unverified claims in design documents become compile blockers discovered only during implementation review, when fixing them requires cross-session coordination.
