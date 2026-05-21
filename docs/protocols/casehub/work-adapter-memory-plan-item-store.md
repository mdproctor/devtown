---
id: PP-20260521-a36692
title: "MemoryPlanItemStore must be in selected-alternatives when casehub-engine-work-adapter is on the test classpath"
type: rule
scope: application
applies_to: "app/src/test/resources/application.properties — any devtown @QuarkusTest module that indexes casehub-engine-work-adapter"
severity: important
refs:
  - casehub/hitl-runtime-assembly.md
  - casehub/work-adapter-test-subcase-group-repository.md
violation_hint: "WorkAdapterPlanItemEntity (from the work-adapter jar) is not in the default persistence unit. Without MemoryPlanItemStore, HumanTaskScheduleHandler.handleInlineMode() calls planItemStore.save() which throws on the JPA entity, rolls back the transaction, and the WorkItem is never committed — appearing in logs but absent from the DB"
created: 2026-05-21
---

When `casehub-engine-work-adapter` is indexed via `quarkus.index-dependency`, its `WorkAdapterPlanItemEntity` is visible to Hibernate but not mapped to any persistence unit. `HumanTaskScheduleHandler.handleInlineMode()` calls `planItemStore.save()` — if the JPA `PlanItemStore` implementation is active, this throws an `IllegalArgumentException` that silently rolls back the handler's `@Transactional` boundary, undoing the WorkItem creation. Add `io.casehub.persistence.memory.MemoryPlanItemStore` to `quarkus.arc.selected-alternatives` in `src/test/resources/application.properties` (without `%test.` prefix — this is a build-time property). See PP-20260514-d69243 for the parallel SubCaseGroupRepository rule.
