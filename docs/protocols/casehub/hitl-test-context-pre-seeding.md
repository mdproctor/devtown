---
id: PP-20260521-134c38
title: "Pre-seed parallel check keys with non-null values in HITL integration tests"
type: rule
scope: application
applies_to: "@QuarkusTest classes exercising humanTask bindings in devtown CasePlanModels"
severity: important
refs:
  - casehub/hitl-runtime-assembly.md
  - casehub/yaml-humantask-binding-type.md
violation_hint: "Missing non-null values for styleCheck/testCoverage/performanceAnalysis/ci causes those capability bindings to fire, triggering tryProvision() calls that block the Vert.x event loop and prevent WorkItemLifecycleAdapter from completing its context update within the 5-second timeout"
created: 2026-05-21
---

In HITL @QuarkusTest integration tests, the initial case context must include non-null values for all parallel check keys (styleCheck, testCoverage, performanceAnalysis, ci) whose bindings would otherwise fire and call tryProvision(). The values must be non-APPROVED (e.g. `{outcome: "PENDING"}`) to keep the pr-approved goal unsatisfied while the humanTask WorkItem lifecycle plays out. This prevents capability binding churn on the Vert.x event loop — churn that causes the WorkItemLifecycleAdapter to time out before it can apply the outputMapping and fire CONTEXT_CHANGED. See GE-20260521-9188c1: YAML when: conditions are not evaluated at runtime, so all contextChange bindings fire unconditionally.
