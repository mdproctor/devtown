# Layer 1 Part B — Naive PR Review Service

**Issue:** devtown#27  
**Epic:** epic-pr-review-case (prerequisite step)  
**Date:** 2026-05-15

---

## What This Is

The second part of Layer 1 in the devtown tutorial. Part A (Epics 1–2) established the domain vocabulary and scaffold. Part B establishes the teaching baseline: the code a team would write without CaseHub. Every subsequent layer displaces it at the CDI level via `@DefaultBean`.

---

## Components

### `PrReviewApplicationService` (port interface)

Location: `app/src/main/java/io/casehub/devtown/app/PrReviewApplicationService.java`

```java
public interface PrReviewApplicationService {
    PrReviewOutcome review(PrPayload pr);
}
```

The CDI displacement boundary. Each subsequent layer provides a non-`@DefaultBean @ApplicationScoped` implementation that displaces the naive one. Both DTOs are plain records — no CaseHub types.

### `PrPayload` (input record)

Location: `app/src/main/java/io/casehub/devtown/app/PrPayload.java`

Fields: `repo` (String), `prNumber` (int), `headSha` (String), `linesChanged` (int).

### `PrReviewOutcome` (output record)

Location: `app/src/main/java/io/casehub/devtown/app/PrReviewOutcome.java`

Fields: `verdict` (String), `findings` (List<String>).

### `NaivePrReviewService` (the anti-pattern)

Location: `app/src/main/java/io/casehub/devtown/app/NaivePrReviewService.java`

`@ApplicationScoped @DefaultBean`. Implements `PrReviewApplicationService`. Makes direct calls via private methods returning stub results. No CDI collaborators — the stubs are intentionally hollow. The gap comments are the pedagogical artifact.

Required gap comments (one per structural gap):
- No attribution — which agent ran this? No record.
- No response SLA — analysis can stall indefinitely.
- No formal DECLINE — if a specialist can't review, it silently fails.
- No tamper-evident audit trail — cannot trace a production incident to this review.
- No trust weighting — novice and expert treated identically.

### `PrReviewResource` (REST dispatcher)

Location: `app/src/main/java/io/casehub/devtown/app/PrReviewResource.java`

`POST /api/reviews`. Injects `PrReviewApplicationService`. No business logic. No auth (auth-retrofit ready: resource method is trivially annotatable with `@RolesAllowed`).

---

## Testing

**Unit test** (`NaivePrReviewServiceTest`): plain `new NaivePrReviewService()`, no Quarkus. Calls `review()` with a stub `PrPayload`. Verifies:
- Returns a non-null `PrReviewOutcome`
- `verdict` is a non-null, non-blank String
- `findings` is a non-null List
- Service is instantiable without a container

**Boot test** (`DevtownBootTest`, already exists): already verifies `@QuarkusTest` starts and CDI wiring is live. No new boot test needed — the existing test covers CDI discovery of `PrReviewApplicationService` once the bean is on the classpath.

---

## Module

All new files go in `app/`. The `domain/` module stays unchanged — no domain types are added for this layer. The interface, DTOs, naive impl, and REST resource are all application-layer concerns.

---

## Displacement Contract

The naive service carries `@DefaultBean`. Any `@ApplicationScoped` implementation of `PrReviewApplicationService` without `@DefaultBean` displaces it at CDI resolution time. The naive class stays in the build across all layers — it is never deleted.

---

## Non-goals

- No real specialist service calls (stubs only)
- No CDI-injected collaborators in the naive impl
- No Jackson/JSON configuration (Quarkus REST handles serialisation)
- No additional Quarkus exten
