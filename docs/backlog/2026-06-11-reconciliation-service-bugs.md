---
type: backlog
status: active
created: 2026-06-11
---

# ReconciliationService — product bugs surfaced by coverage IT (2026-06-11)

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
