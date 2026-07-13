# EvidentialChecker V1-V4 Integration for Low-Trust Attestations

**Issue:** devtown#141  
**Date:** 2026-07-12  
**Branch:** issue-141-evidential-checker-v1-v4  
**Blocked by:** devtown#97 (TrustGatedAttestationPolicy) — ✅ CLOSED

---

## Context

`TrustGatedAttestationPolicy` modulates attestation confidence based on capability trust scores, but always returns `SOUND` for DONE claims — even for agents below the trust threshold. A BELOW_THRESHOLD agent gets SOUND at reduced confidence (`max(0.05, 0.7 × score)`), with no structural verification that the agent actually performed the work.

`EvidentialChecker` (qhorus runtime, `@DefaultBean`) provides V1-V4 benchmark checks that verify message integrity:

| Variant | Check | Input |
|---|---|---|
| V1 | DONE references a real artefact | `artefactUuid` → DataStore lookup |
| V2 | Channel has actual messages | `observedChannelId` → MessageStore count |
| V3 | Not confirming a FAILED obligation | `priorCorrId` → CommitmentStore state |
| V4 | Contains verification token | `expectedToken` + message content |

Currently unused — zero references in devtown.

This spec adds an evidential verification layer: for agents in configurable low-trust phases, run V1-V4 checks before issuing a SOUND attestation. Violations produce FLAGGED at fixed high confidence (0.8).

---

## Design Decisions

**Composition strategy:** Delegation with independent classification (Approach A). `EvidentialAttestationPolicy` wraps `TrustGatedAttestationPolicy` — the existing trust-modulated policy is unchanged. Classification uses `TrustRoutingPolicy`'s public API (~12 lines), not internal knowledge of the delegate.

**Evidential check scope:** Configurable per capability via a new `Set<TrustPhase> evidentialCheckPhases` field on `TrustRoutingPolicy` (engine-api). Empty set = no checks (backward compatible default).

**Failure consequence:** FLAGGED at fixed 0.8 confidence. Evidential violations are structural proof (zero messages on channel, confirming a FAILED obligation), not probabilistic assessments. The trust score influenced whether checks ran; once they prove the claim is invalid, confidence reflects evidence quality.

---

## 1. Foundation Change — `TrustPhase` Enum

**Module:** `casehub-engine-api`  
**Package:** `io.casehub.api.spi.routing`

```java
public enum TrustPhase {
    BOOTSTRAP,        // No history or below minimumObservations
    QUALIFIED,        // Above threshold, passed quality floors
    BORDERLINE,       // Within borderlineMargin of threshold
    BELOW_THRESHOLD,  // Below threshold, not borderline
    QUALITY_FAILED    // Passed threshold but failed a quality floor
}
```

This is the policy-level vocabulary for trust maturity phases. Distinct from `TrustCandidateClassifier.Phase` (engine-ledger), which is routing-specific and includes workload scores.

---

## 2. Foundation Change — `TrustRoutingPolicy` Field

**Module:** `casehub-engine-api`

Add `evidentialCheckPhases` to `TrustRoutingPolicy`:

```java
public record TrustRoutingPolicy(
    double threshold,
    int minimumObservations,
    double borderlineMargin,
    double blendFactor,
    Map<String, Double> qualityFloors,
    boolean bootstrapEscalationRequired,
    String fallbackBinding,
    Set<TrustPhase> evidentialCheckPhases
) { ... }
```

`DEFAULT` updated: `Set.of()` for evidential phases (no checks — backward compatible).

**Breaking change:** All existing constructor calls gain the new parameter. Pre-release — no compatibility overload. Affected callers:
- `TrustRoutingPolicyTest` (engine-api)
- `TrustGatedAttestationPolicyTest` (engine-ledger)
- `TrustCandidateClassifierTest` (engine-ledger)
- `TrustWeightedAgentStrategyTest` (engine-ledger)
- `DevtownTrustRoutingPolicyProvider` (devtown app/)
- `DevtownTrustRoutingPolicyProviderTest` (devtown app/)

---

## 3. Devtown Policy Population

**File:** `app/src/main/java/io/casehub/devtown/app/routing/DevtownTrustRoutingPolicyProvider.java`

Per-capability evidential check configuration:

| Capability | evidentialCheckPhases | Rationale |
|---|---|---|
| `security-review` | `BELOW_THRESHOLD, QUALITY_FAILED, BOOTSTRAP` | High stakes, irreversible. Check everyone unproven. |
| `architecture-review` | `BELOW_THRESHOLD, QUALITY_FAILED` | Expensive mistakes, bootstraps get grace. |
| `style-review` | `Set.of()` (empty) | Low stakes. Trust modulation alone is sufficient. |
| `merge-executor` | `BELOW_THRESHOLD, QUALITY_FAILED, BOOTSTRAP, BORDERLINE` | Irreversible. Most aggressive checking. |

---

## 4. Core Component — `EvidentialAttestationPolicy`

**File:** `app/src/main/java/io/casehub/devtown/app/trust/EvidentialAttestationPolicy.java`  
**CDI:** `@Alternative @Priority(2) @ApplicationScoped`  
**Implements:** `CommitmentAttestationPolicy`

**Dependencies:**
- `TrustGatedAttestationPolicy` — delegate (injected by concrete class)
- `EvidentialChecker` — V1-V4 checks
- `TrustScoreSource` — capability score and decision count lookups
- `TrustRoutingPolicyProvider` — per-capability policy (includes evidentialCheckPhases)

**`attestationFor(MessageType, String, CommitmentContext)` flow:**

```
 1. delegate.attestationFor(type, actorId, ctx) → base outcome
 2. type != DONE? → return base
 3. ctx == null or no capabilityTag? → return base
 4. policy = policyProvider.forCapability(capabilityTag)
 5. policy.evidentialCheckPhases().isEmpty()? → return base
 6. Classify agent trust phase:
      capScore empty or isBootstrap(decCount) → BOOTSTRAP
      isBorderline(score)                    → BORDERLINE
      !passesThresholdCheck(score)           → BELOW_THRESHOLD
      fails any quality floor                → QUALITY_FAILED
      else                                   → QUALIFIED
 7. phase not in evidentialCheckPhases? → return base
 8. Run EvidentialChecker.check() for V1, V2, V3, V4
      BenchmarkContext per variant:
        artefactUuid    = null               (V1 inert)
        observedChannelId = ctx.channelId()  (V2 fires)
        priorCorrId     = ctx.correlationId() (V3 fires)
        expectedToken   = null               (V4 inert)
    Aggregate all violations into a single list
 9. violations.isEmpty()? → return base
10. LOG.warnf("Evidential check failed for %s on %s: %d violations — %s",
              actorId, capabilityTag, violations.size(), violationSummary)
11. return FLAGGED at 0.8 confidence, attestorId="system", ActorType.SYSTEM
```

**Constants:**
- `EVIDENTIAL_FAILURE_CONFIDENCE = 0.8`

**Error handling:** If `EvidentialChecker.check()` throws, log at WARN and return the base outcome. Same defensive pattern as `TrustGatedAttestationPolicy`.

---

## 5. BenchmarkContext Construction

`EvidentialChecker.check(String messageType, String content, BenchmarkContext ctx)` called once per variant (V1-V4).

`content` parameter: `null` — `CommitmentAttestationPolicy.attestationFor()` does not receive message content.

**What fires today vs. what's structurally ready:**

| Variant | Check | Fires? | Why |
|---|---|---|---|
| V1 | DONE references real artefact | No | `artefactUuid` null → skipped |
| V2 | Channel has actual messages | **Yes** | `ctx.channelId()` populated |
| V3 | Not confirming FAILED obligation | **Yes** | `ctx.correlationId()` populated |
| V4 | Contains verification token | No | `expectedToken` null, content null → skipped |

Two of four variants fire immediately. V1 and V4 activate when `CommitmentContext` is enriched upstream.

---

## 6. Testing

### Unit test — `EvidentialAttestationPolicyTest`

**Location:** `app/src/test/java/io/casehub/devtown/app/trust/`  
**Style:** Mocks for all dependencies. Pure logic, no CDI.

| Test | Proves |
|---|---|
| `done_qualifiedAgent_skipsEvidentialCheck` | QUALIFIED phase not in evidentialCheckPhases → delegates unchanged |
| `done_belowThreshold_noViolations_returnsSoundFromDelegate` | Phase matches, checks run clean → base outcome passes through |
| `done_belowThreshold_violations_returnsFlaggedAtHighConfidence` | Phase matches, violations found → FLAGGED at 0.8, system attestor |
| `done_bootstrapAgent_checksConfigured_runsChecks` | BOOTSTRAP in evidentialCheckPhases → checks fire |
| `done_bootstrapAgent_checksNotConfigured_skips` | BOOTSTRAP not in set → no checks |
| `done_emptyEvidentialPhases_alwaysDelegates` | Empty set → pure passthrough |
| `failure_alwaysDelegates` | Non-DONE type → base outcome unchanged |
| `done_nullContext_delegates` | Null context → falls back to delegate |
| `done_checkerThrows_fallsBackToDelegate` | Exception → WARN log, base outcome |
| `done_borderline_inPhases_runsChecks` | BORDERLINE in set → checks fire |
| `done_qualityFailed_inPhases_runsChecks` | QUALITY_FAILED in set → checks fire |

### CDI activation test — `EvidentialAttestationPolicyActivationTest`

**Location:** Same package, `@QuarkusTest @TestProfile(TrustScoringTestProfile.class)`

| Test | Proves |
|---|---|
| `policyIsEvidential` | Injected `CommitmentAttestationPolicy` is `EvidentialAttestationPolicy` |
| `delegateIsTrustGated` | Internal delegate is `TrustGatedAttestationPolicy` |

### Foundation test updates

`TrustRoutingPolicyTest`, `TrustGatedAttestationPolicyTest`, `TrustCandidateClassifierTest`, `TrustWeightedAgentStrategyTest` — add `Set.of()` to all `TrustRoutingPolicy` constructor calls. No logic changes.

---

## 7. Follow-Up Issues

| Issue | Repo | Description |
|---|---|---|
| CommitmentContext enrichment for V1/V4 | qhorus | Add `artefactUuid` and `expectedToken` to `CommitmentContext`; pass message `content` to attestation policy |
| Violation storage in ledger | devtown or ledger | Persist `BenchmarkViolation` details alongside FLAGGED attestation for audit trail |

---

## Done-When

A BELOW_THRESHOLD agent's DONE claim for `security-review` triggers V2 and V3 evidential checks. If the channel has zero messages (V2 violation), the attestation is FLAGGED at 0.8 confidence instead of SOUND at scaled confidence. A QUALIFIED agent's DONE claim for the same capability skips evidential checks entirely and produces the same trust-modulated SOUND attestation as before.
