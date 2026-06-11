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

## Open — behavioral defects (NOT fixed; characterized in tests)

### F1 — `SecureQueryExecutorImpl.getQueryCache` throws on every cache miss
`getQueryCache` is annotated `@Cacheable(value = "secureQuery", ...)` but its
body is `return null;`. On a cache **miss** Spring invokes the method, gets
`null`, and tries to store it; the `secureQuery` cache (Caffeine, see
`CacheConfig`) does not allow null values, so Spring throws
`IllegalArgumentException: Cache 'secureQuery' is configured to not allow null
values but null was provided`. The cache read path is therefore unusable for
the common (miss) case. `setQueryCache` is likewise a no-op (`@CachePut` is not
wired; the body only logs). The query-result cache feature does not work.
- **Fix direction:** either implement the cache properly (real get/put against
  the `secureQuery` cache, or remove the `@Cacheable` and manage the cache
  manually), or allow null values for this cache, or drop the dead caching API.
- **Test impact:** `SecureQueryExecutorImplIntegrationTest` exercises
  set/clearQueryCache but cannot assert a working get path.

### F2 — `SecureQueryExecutorImpl.executeWithTimeout` loses thread-local context
`executeWithTimeout(supplier, timeoutMs, op)` runs the supplier via
`CompletableFuture.supplyAsync(supplier)` on the common `ForkJoinPool`. That
worker thread does not inherit `MetaContext` (tenant/user) or the security
context, so any query executed with a non-null positive `timeoutMs` throws
`IllegalStateException: MetaContext not initialized` (or a permission failure)
instead of timing out. Every secure-query path that passes a timeout is
affected.
- **Fix direction:** use a context-propagating executor (wrap the supplier to
  copy `MetaContext` / `RequestContext` into the worker, or use a
  `TaskDecorator`-style propagation), or enforce the timeout without handing the
  work to a pool that lacks context.
- **Test impact:** the IT sets `timeoutMs = null` to take the synchronous path;
  the timeout branch is intentionally uncovered.

### F3 — `SchemaAccessProjectorImpl.parseSchemaContent` is an unimplemented stub
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
- **Fix direction:** implement `parseSchemaContent` (parse the page schema JSON
  into the expected `{fields: {...}}` shape) so `filterSchemaFields` actually
  filters, or remove the field-filtering claim from the API if it is not
  intended to be used. Decide whether the 10 uncalled helpers are future work
  or should be deleted.
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
| `rag/entity/KbChunk` | `ab_kb_chunk.metadata` | latent — RAG chunk metadata; fix + KbChunk-persistence IT |
| `meta/entity/InvariantEvaluationLog` | `ab_invariant_evaluation_log.context_snapshot` | latent — invariant-engine eval log; fix + IT |

**Fix recipe (mechanical, proven 3×):** add
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
