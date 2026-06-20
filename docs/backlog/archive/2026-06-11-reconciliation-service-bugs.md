---
type: backlog
status: closed
created: 2026-06-11
---

<!-- no-precipitation: all 4 bugs fixed (PR #927 bugs 1/2/3, PR #938 bug 4); the durable lesson (REQUIRES_NEW failed-audit deadlock in a @Transactional catch) is codified in auraboot-enterprise/docs/agent-rules/engineering-gotchas/backend-spring-db.md (ENT #601). This doc stays as the historical bug record. -->

# ReconciliationService — product bugs surfaced by coverage IT (2026-06-11)

> ✅ **ALL 4 BUGS FIXED — CLOSED (2026-06-20).**
> - **Bugs 1/2/3 (PR #927)** — type-validation case mismatch, null-type clean ValidationException, count-with-ORDER-BY SQL.
> - **Bug 4 (PR #938)** — persist the FAILED-run audit despite the outer `@Transactional` rollback. **Done correctly** via `TransactionSynchronization.afterCompletion(ROLLED_BACK)` + a fresh `TransactionTemplate`: by afterCompletion the outer tx's locks are released, so NO deadlock. (The earlier `REQUIRES_NEW`-inside-the-open-tx attempt deadlocked >1h — see the gotcha in `engineering-gotchas/backend-spring-db.md`.) IT flipped to assert the FAILED-run IS persisted; independent re-run 35 tests green, completes in ~69s.

Found while writing `ReconciliationServiceIntegrationTest` (OSS coverage #8/#9). The IT
**documents** these as current behavior (so coverage is real and characterization-stable);
they are NOT fixed here — fixing is separate scope. Each test that encodes a bug is marked
`PRODUCT BUG` in its `@DisplayName`.

## 1. `validateProfileType` always rejects every valid type (high severity)
`validateProfileType` compares `type.toUpperCase()` against `Set.of("supplier","bank","intercompany")`
(all lowercase). `"supplier".toUpperCase()` = `"SUPPLIER"` ∉ set, so **every** `createProfile`
with a documented valid type throws. `createProfile` is effectively unusable from the service
path (the IT seeds profiles via the mapper to exercise the rest of the surface).
Fix: compare case-insensitively, or normalize the allowed set to upper-case, or accept the
canonical casing the callers actually send.

## 2. `createProfile` allows null `profileType` past validation, then the DB rejects it
`validateProfileType` short-circuits on `null` (returns without error), but `profile_type`
is `NOT NULL` in the table — so a null type passes Java validation and throws a raw
`DataIntegrityViolation` at insert instead of a clean `ValidationException`.
Fix: explicit non-null check in `validateProfileType`.

## 3. `listRuns` and unfiltered `getRunItems` generate invalid SQL
Both call MyBatis-Plus `selectCount(qw)` on a `QueryWrapper` that also has `orderByDesc`/
`orderByAsc` set, producing `SELECT COUNT(*) ... ORDER BY ...` which PostgreSQL rejects.
Fix: build a separate count wrapper without the ORDER BY, or clear ordering before the count.

## 4. `startReconciliation` `@Transactional` rolls back the FAILED-run audit record
The method is `@Transactional`; on failure the catch block persists the run with
`STATUS_FAILED` and re-throws `MetaServiceException` (a `RuntimeException`). Spring then
rolls back the whole transaction — including the just-inserted FAILED run — so the intended
failure audit trail is never persisted.
Fix: persist the failed-run record in a `REQUIRES_NEW`/`NOT_SUPPORTED` boundary, or record
the failure outside the rolled-back transaction.
