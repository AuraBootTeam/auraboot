---
type: ddr
status: active
created: 2026-06-21
relates_to:
  - docs/architecture/overview.md
---

# DDR-2026-06-21: Atomic counter primitive on BackgroundDataAccessor SPI

## Context — the over-crawl defect

`CrawlBudgetGate.checkAndIncrement` (crawler plugin) guarded the per-job URL budget
with a **read-modify-write** sequence:

```
read  cr_cj_discovered_count  ← observed value X
add n
if X+n > max_urls → deny
write X+n          ← last-writer-wins
```

Two concurrent callers both reading `X` both write `X+n`; the counter advances by `n`
instead of `2n`. The job crawls past `cr_cj_max_urls` (**over-crawl / over-spend**, P1).
The `concurrency=1` single-replica mitigation does not hold for multi-replica deployment.

## Decision — atomic primitive on the BackgroundDataAccessor SPI

**Chosen option:** add two new methods directly to
`com.auraboot.framework.plugin.extension.BackgroundDataAccessor`:

```java
Optional<Long> incrementWithinCap(long tenantId, String modelCode, String recordId,
                                  String counterField, long delta, String capField);
Optional<Long> increment(long tenantId, String modelCode, String recordId,
                         String counterField, long delta);
```

with mirror methods (tenant-from-context) on `DynamicDataService`.

### Why on the SPI (not plugin-local)

Under PF4J classloader isolation a background plugin component has **no direct
access to `DynamicDataMapper` or `DynamicDataServiceImpl`** — and must not, as
those are host-side internals. The only sanctioned data path is `BackgroundDataAccessor`.
A plugin-local CAS loop (compare-and-swap in application code) would race across
replicas, so it is not a valid alternative. The SPI is the only correct boundary.

### Why not keep the read-modify-write

Keeping the RMW with `concurrency=1` only defers the bug: it is a single-replica
constraint that will not survive horizontal scale. Fixing it at the data layer
eliminates the race for both single-replica and multi-replica deployment permanently.

### Why UPDATE … RETURNING (not affected-rows + follow-up SELECT)

A single `UPDATE … RETURNING` yields the post-commit counter value produced by **this
exact statement**, atomically:

```sql
UPDATE <table>
   SET <counterCol> = COALESCE(<counterCol>, 0) + #{delta},
       updated_at = now(), updated_by = #{currentUserId}
 WHERE pid = #{recordId}
   AND tenant_id = #{tenantId}
   AND COALESCE(<counterCol>, 0) + #{delta} <= <capCol>
RETURNING <counterCol> AS new_value
```

The alternative — `@UpdateProvider` returning affected-row-count (1 = granted) plus
a follow-up `SELECT` — is wrong: the follow-up read, in a separate statement, can
observe a value already advanced by a concurrent writer, so the returned value would
not be the caller's resulting counter.

Under `READ COMMITTED`, the `UPDATE` takes a row-level write lock. A concurrent
writer blocks on the lock; when it re-evaluates the `WHERE` against the committed
post-update value, the `counter + delta <= cap` predicate is checked against the
**new** counter, so no two writers can jointly exceed the cap.

`COALESCE(<counterCol>, 0)` ensures a fresh row (counter still NULL) starts from 0
rather than being wrongly denied. The cap side is intentionally not coalesced: NULL
cap → 0 rows → `Optional.empty()` (deny, safe-by-default).

### New @SelectProvider seam — why a new mapper method

The three existing raw-SQL seams (`selectByQuery`, `countByQuery`, `executeCustomSql`)
all call `SqlSafetyUtils.validateSelectOnlySql(...)` and **reject** any `UPDATE`.
A new `DynamicDataMapper` method annotated `@SelectProvider` (not `@UpdateProvider`)
is required because MyBatis executes a `@SelectProvider` via `executeQuery`, which
reads the `RETURNING` ResultSet. The existing SELECT-only seams cannot be reused.

**Injection safety** — the new provider method does NOT call the SELECT-only guard;
safety is enforced one layer up in `DynamicDataServiceImpl`:
- Table name: `ModelDefinition.getTableName()` (resolved from meta-model registry,
  never from raw caller string).
- Column identifiers: resolved via `ModelDefinition.getFields()` by field code;
  unknown code or non-numeric type → `IllegalArgumentException` (hard fail).
- `delta`, `recordId`, `tenantId`: bound as `#{}` parameters (never interpolated).

This is equivalent safety to the existing update path's whitelist, just applied
in the service layer rather than by SQL guard.

The tenant interceptor is not bypassed: `tenantId` is passed explicitly in the
`WHERE tenant_id = #{tenantId}` predicate, which is the same mechanism used by
`BackgroundDataAccessorImpl`'s existing methods (which call `withTenant()`).

### No per-increment change-log — deliberate tradeoff

`DynamicDataService.update` writes an `ab_data_change_log` row per call. The atomic
path intentionally omits this: background counters fire on every discovered-URL batch
(potentially hundreds per job-second); writing a change-log row per increment would
bloat `ab_data_change_log` with no operator value. The `updated_at`/`updated_by`
columns are still kept current by the `UPDATE` itself, so row freshness and
background-system attribution are preserved.

This is a conscious tradeoff, not an oversight. If per-tick audit is needed in future,
a dedicated event table (not the generic change log) is the right venue.

## Consequences

- Every plugin that implements `BackgroundDataAccessor` must add the two new methods
  (additive to the SPI interface).
- The real-PG concurrency IT (`AtomicIncrementConcurrencyIT`) proves "final ==
  cap, never exceeds" and "N·delta, no lost updates" under N concurrent threads.
- Crawler `CrawlBudgetGate` consumes the primitives via PR2 once this PR is merged
  and `platform-plugin-api` is published.

## References

- Design spec (long-form): `docs/superpowers/specs/2026-06-21-crawl-budget-cas-atomic-and-audit-sink-design.md`
  (in the `crawler-budget-cas` worktree / crawler repo)
- Implementing PR: `feat/background-atomic-counter` (this commit)
- Tests: `AtomicIncrementConcurrencyIT`, `DynamicSqlProviderAtomicIncrementTest`,
  `DynamicDataServiceAtomicIncrementGuardTest`, `BackgroundDataAccessorImplTest`
