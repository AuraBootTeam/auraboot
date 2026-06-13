---
type: backlog
status: active
created: 2026-06-11
---

# Product findings surfaced by the meta/service/impl coverage IT wave 2 (2026-06-11)

While adding real-stack integration tests for six near-zero `meta/service/impl`
classes (OSS coverage → 80% initiative, tracker
`2026-06-10-oss-coverage-to-80-tracker.md`), the tests surfaced real product
defects. Per AGENTS.md coverage discipline (G-cov-10: *"覆盖 IT 撞 bug = 特征化标
PRODUCT BUG + backlog,别在覆盖 PR 修"*), the **mechanical** jsonb-typeHandler
omissions were fixed inline (they were one-line annotations matching the
established codebase pattern and otherwise blocked all coverage), but the
**behavioral** defects below were left characterized / worked-around in the
tests and are filed here for a dedicated fix.

Each finding was verified by reading the source (not inferred from a test
failure alone).

## Fixed inline (mechanical, jsonb persistence — the feature was broken at runtime)

These columns are `jsonb` but their entity fields lacked
`@TableField(typeHandler = JsonbStringTypeHandler.class)`, which every sibling
jsonb-string field uses. Without it, any insert/update of a non-null value
threw `column "..." is of type jsonb but expression is of type character
varying`. The host DB URL has no `stringtype=unspecified`, so this fails in
production too.

- **EdiPartner.authConfig / EdiMessageType.mappingTemplate / EdiMessageType.validationRules / EdiTransaction.parsedData** — the live `EdiController` create/update/send/receive endpoints 500 on any non-null jsonb payload. Fixed (commit `fix(meta): apply JsonbStringTypeHandler to EDI jsonb columns`).
- **QueryAuditLog.conditions** — every `queryAuditLogMapper.insert(...)` (all four `@Async` audit-log writers) threw and the exception was swallowed by the `catch (Exception)` in the async methods, so **query audit logging was silently non-functional at runtime**. Fixed (commit `fix(meta): add JsonbStringTypeHandler to QueryAuditLog.conditions column`).
  - *Verified:* `conditions` is the **only** jsonb column on `ab_query_audit_log`
    (the `query_conditions` / `select_fields` / `sort_fields` / `pagination_info`
    columns are varchar/text, not jsonb), so this single-field fix is complete.
    The coverage IT seeds those non-null paths sparingly — a coverage gap, not a
    persistence bug.

## Behavioral defects (F1/F2 FIXED 2026-06-11; F3 confirmed dead code — won't implement)

> **Wiring note:** within OSS, no controller/service calls `SecureQueryExecutor` (only a
> `PerformanceMonitoringAspect` pointcut + a comment reference) or `SchemaAccessProjector`
> (zero references). They are framework/interface APIs. F1/F2 were still fixed because a
> framework service should be correct for any (enterprise/plugin) consumer; F3's service is
> unwired *and* implementing the stub would be building unused functionality, so it is left
> dead and flagged for removal.

### F1 — `SecureQueryExecutorImpl.getQueryCache` throws on every cache miss — **FIXED**
`getQueryCache` is annotated `@Cacheable(value = "secureQuery", ...)` but its
body is `return null;`. On a cache **miss** Spring invokes the method, gets
`null`, and tries to store it; the `secureQuery` cache (Caffeine, see
`CacheConfig`) does not allow null values, so Spring throws
`IllegalArgumentException: Cache 'secureQuery' is configured to not allow null
values but null was provided`. The cache read path is therefore unusable for
the common (miss) case. `setQueryCache` is likewise a no-op (`@CachePut` is not
wired; the body only logs). The query-result cache feature does not work.
- **Fix applied:** removed `@Cacheable`; `getQueryCache`/`setQueryCache`/`clearQueryCache`
  now access the `secureQuery` cache directly via the `CacheManager` (get/put/evict), keyed by
  `generateCacheKey(request)`. This also fixes the self-invocation problem (the cache check at
  `executeSecureQuery` is an internal call that bypassed the proxy entirely, so caching never
  worked). A miss returns `null` cleanly. Regression: `cacheRoundTrip` / `getQueryCache_missReturnsNull`
  / `clearQueryCache_evicts` in `SecureQueryExecutorImplIntegrationTest`.

### F2 — `SecureQueryExecutorImpl.executeWithTimeout` loses thread-local context — **FIXED**
`executeWithTimeout(supplier, timeoutMs, op)` runs the supplier via
`CompletableFuture.supplyAsync(supplier)` on the common `ForkJoinPool`. That
worker thread does not inherit `MetaContext` (tenant/user) or the security
context, so any query executed with a non-null positive `timeoutMs` throws
`IllegalStateException: MetaContext not initialized` (or a permission failure)
instead of timing out. Every secure-query path that passes a timeout is
affected.
- **Fix applied:** a `withMetaContext(supplier)` wrapper captures the caller thread's
  `MetaContext` (tenant/user/userPid/username/roleIds + memberId + environmentId) and
  re-establishes it on the worker thread, clearing it in a `finally`. Regression:
  `executeSecureQuery_withTimeout_propagatesMetaContext` asserts a positive-`timeoutMs` query
  does not throw `IllegalStateException` (MetaContext not initialized).

### F3 — `SchemaAccessProjectorImpl.parseSchemaContent` is an unimplemented stub — **WON'T IMPLEMENT (dead/unwired)**
`parseSchemaContent(String)` ignores its argument and always returns
`new HashMap<>()` (TODO: *"实际实现需要 JSON 解析"*). Both
`filterSchemaFields(...)` and `calculateFieldPermissions(...)` then read
`schemaContent.get("fields")`, which is always `null`, so the entire
field-level filtering / permission-projection body is **dead code** — schema
field filtering silently does nothing. In addition, ~10 private helpers
(`filterSchemaOperations`, `calculateFieldPermissions`,
`hasSchemaOperationPermission`, `convertToEntity`, `serializeSchemaContent`,
`getFieldCount`, `isFieldMaskingRequired`, `getFrequentSchemaPids`,
`clearUserPermissionCache`, `findAffectedSchemas`) are never called from any of
the 11 public interface methods — dead code.
- **Decision:** `SchemaAccessProjector` has **zero callers in OSS** (verified by grep), so
  implementing `parseSchemaContent` would be building functionality nobody invokes — the wrong
  fix per the configuration/architecture discipline. Left as-is and flagged for **removal**
  (the whole service + its 10 uncalled private helpers) in a dedicated cleanup, OR a real
  implementation if/when a caller is actually wired. Not implemented here.
- **Test impact:** `SchemaAccessProjectorImplIntegrationTest` reaches 57.2%
  line — the rest is the structurally-unreachable dead code above.

## Wave 3 (2026-06-11) — fourth jsonb-typeHandler bug + repo-wide audit

Wave 3 IT surfaced a **third** in-band-fixed instance of the same jsonb omission:
`OtDevice.connectionConfig`/`dataMapping` + `OtDataLog.rawData`/`parsedData` lacked
`JsonbStringTypeHandler` → `registerDevice`/`processDeviceData` 500'd on any non-null
payload (fixed in #585). Three live instances in two days (EDI, QueryAuditLog, OtDevice),
plus an independent fix by another session the same day (#586,
`PlanService.loadPlanFromRun` PGobject jsonb return), make this a systemic pattern.

**Repo-wide audit done** (resolve each entity `@TableName` → check that *own* table's
column type; name-only matching has false positives — 33 raw candidates → 6 true → 4 were
the OtDevice/OtDataLog ones fixed in #585). **Two latent instances remain** (no obvious
non-null writer today, so not actively breaking — but a 500 time-bomb the moment the field
is written non-null):

| Entity | jsonb column | status |
|---|---|---|
| `rag/entity/KbChunk` | `ab_kb_chunk.metadata` | **FIXED** — `BaseMapper` auto-insert (no cast); typeHandler added + `KbChunkMetadataJsonbIntegrationTest` regression IT |
| `meta/entity/InvariantEvaluationLog` | `ab_invariant_evaluation_log.context_snapshot` | **FALSE POSITIVE** — `InvariantEvaluationLogMapper` inserts with an explicit `#{contextSnapshot}::jsonb` cast (custom mapper, not BaseMapper), so no typeHandler is needed. The static audit over-reported; the per-mapper cast is the safe alternative to the typeHandler. |

> **Audit lesson:** name+type matching (and even per-table column-type matching) is necessary
> but **not sufficient** — an entity can map a jsonb column safely if its *mapper* casts
> `::jsonb` explicitly (custom XML/annotation insert) instead of relying on the `@TableField`
> typeHandler. A correct lint must check both: BaseMapper auto-insert/update of a String→jsonb
> field **without** the typeHandler is the real defect; a custom mapper with `::jsonb` is fine.

**Fix recipe (mechanical, proven 4×):** add
`@TableField(value = "<col>", typeHandler = com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler.class)`
to the String field. Each fix should land with a regression IT (which also lifts that
class's coverage — natural future-wave targets). **Worth promoting to canonical** (a
`@TableField` String mapped to a jsonb column MUST declare the handler) + ideally a
`check-*.sh` lint that resolves entity table/column types and flags missing handlers.

## Notes
- `FieldForkServiceImpl` / `FieldImpactAnalysisServiceImpl` revealed only a
  docs gap, not a bug: `MetaFieldService.create` rejects `dataType` `"int"` /
  `"boolean"` primitives (valid set is `"integer"`, `"string"`, `"decimal"`,
  `"boolean"`, …); the valid types are only discoverable from the validator
  error message. Consider documenting the legal `dataType` set on the DTO.
