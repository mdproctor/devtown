---
id: PP-20260522-f08b62
title: "Control-flow exceptions in @Transactional item loops must not escape the loop boundary"
type: rule
scope: application
applies_to: "@Transactional service methods in casehub-work (and devtown) that iterate over WorkItems or domain entities and use private RuntimeExceptions for internal control flow"
severity: critical
refs:
  - casehub/HARNESS-INDEX.md
violation_hint: "Private RuntimeException (e.g. BreachExecutionFailed) thrown inside executeBreachDecision() for a control-flow case (EscalateTo empty groups) is only caught by one branch (Chained) — if the top-level dispatch receives the triggering input directly, the exception escapes to the @Transactional boundary, rolls back all items processed in that tick, and causes silent infinite retry (GE-20260522-4e806e)"
created: 2026-05-22
---

Any private RuntimeException used as a control-flow mechanism (as opposed to a genuine error) inside a helper called from a `@Transactional` loop must be caught before it can escape the loop body. The fix is two-layered: (1) validate eagerly at the factory or input site to prevent the triggering condition from being created, and (2) add a catch at the top-level dispatch within the loop to apply a safe fallback if the exception still escapes. A transaction rollback caused by an escaped control-flow exception leaves every item in the batch un-processed, produces no audit entry, and triggers re-processing on every subsequent scheduler tick — with no log evidence at the point of failure.
