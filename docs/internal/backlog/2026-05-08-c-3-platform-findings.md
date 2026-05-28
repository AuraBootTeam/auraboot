# C-3 ModelCreateSkill — Platform contract findings (2026-05-08)

Surfaced while building the `model:create` aurabot skill (T4 + T5). Each item
documents a platform contract that the skill had to work around. Carry these
into a follow-up cleanup task once C-3 lands; do not block C-3 closure on them.

## F-1 — `MetaModelService.create()` ignores `request.autoPublish` and `request.fields`

`MetaModelCreateRequest` exposes `autoPublish` and `fields` setters, but
`MetaModelServiceImpl.create()` persists only the meta row and auto-binds
system fields (id/pid/created_at/updated_at). It never reads `request.fields`
nor invokes `publish()` even when `autoPublish=true`. Effect: callers who set
those fields silently get a draft, unpublished model with no user fields.

**Workaround in skill (T4):** call `MetaFieldService.create` for a `name_<suffix>`
field with `modelPid` wired so the field service auto-binds, then explicitly
call `metaModelService.publish(pid, ...)`.

**Cleanup:** Either honour the fields/autoPublish contract in `create()`, or
delete the dead setters from the DTO and update its javadoc.

## F-2 — `MetaModelService.findByCode()` throws on miss; `findByPid()` returns null on miss

Inconsistent existence-probe semantics: `findByCode(code)` raises
`ValidationException("模型不存在")` for unknown codes (used as control flow on
the create happy path), while `findByPid(pid)` returns null for unknown pids.

**Workaround in skill (T4):** `findByCodeOrNull(code)` helper that catches
`ValidationException` and returns null.

**Cleanup:** Pick one (probably nullable for both) and migrate callers, or
expose a dedicated `existsByCode(String)` method.

## F-3 — `MetaModelService.delete()` is soft-delete only, blocks code reuse

`MetaModelServiceImpl.delete(pid)` calls `metaModelMapper.deleteById` which is
a logical delete (sets `deleted_flag=TRUE`). But the unique constraint
`uq_meta_model_code_ver(tenant_id, code, version)` is unconditional — soft-deleted
rows still occupy `(code, version=1)`, so re-creating the same code after
delete fails with a duplicate-key error.

**Workaround in skill (T5 undo):** bypass `MetaModelService.delete` and run a
raw `DELETE FROM ab_meta_model WHERE pid = ...` via `DynamicDataMapper.alterTable`.

**Cleanup:** Either change the unique constraint to scope by `deleted_flag=false`
(matches `ux_meta_model_current` pattern), or expose a hard-delete API for undo
use cases. Same problem applies to `ab_meta_field` (see F-5).

## F-4 — `MetaModelService.delete()` blocks deletion of any model with bound user fields

`validateCanDelete` raises `IllegalStateException` when any non-system field
binding exists. There is no force-delete / cascade option. For the undo path
on a freshly-created model with one user field (the `name_<suffix>` we wire
up to satisfy publish's "≥1 field" rule), we have to manually clear bindings
first, which means digging into `MetaModelFieldBindingMapper`.

**Workaround in skill (T5 undo):** inject `MetaModelFieldBindingMapper`,
hard-delete bindings via `deleteByModelId`, then drop the table.

**Cleanup:** Add a `deleteCascade(String pid)` to `MetaModelService` that
clears bindings + drops the table + deletes the row in one transactional
unit, owned by the meta layer.

## F-5 — `ab_meta_field` unique constraint blocks ULID-prefixed reuse

The `name_<suffix>` field code is built from `modelPid.substring(0, 8)`. ULIDs
are time-monotonic, so two consecutive skill runs against new models produce
the same 8-char prefix and therefore the same field code. Combined with F-3's
soft-delete-blocks-reuse, the second `execute()` after `undo()` fails with
"Field code 'name_xxx' already exists".

**Workaround in skill (T5 undo):** also hard-delete `ab_meta_field` rows for
user fields whose only binding was just cleared.

**Cleanup:** Same as F-3 — either scope the unique constraint by `deleted_flag`,
or expose a hard-delete on `MetaFieldService`.

## F-6 — `SchemaManagementService.dropTableByModel()` swallows exceptions

The DDL drop path is wrapped in a blanket `try/catch (Exception e)` that
returns `SchemaOperationResult{success=false, errorMessage=...}` instead of
throwing. For a transactional caller (e.g. an undo that needs the drop to
either succeed or roll back), this is a silent-failure trap.

**Workaround in skill (T5 undo):** issue `DROP TABLE IF EXISTS` directly via
`DynamicDataMapper.alterTable`, surface failures to the caller.

**Cleanup:** Add a `dropTableByModelOrThrow(String code)` companion method
and migrate undo-style callers to it.

## F-7 — `@Transactional` IT + DDL on missing table aborts the entire test transaction

Skill's `undoByModel` originally guarded the data-row pre-check with
`try/catch (RuntimeException tableMissing)` to make follow-up undos a no-op
on already-dropped tables. Under `@Transactional` integration tests, the
PostgreSQL transaction enters `25P02` (aborted) state on the first failed
SELECT, and **catching the exception in Java does not reset the PG state** —
all subsequent statements fail with "current transaction is aborted".

**Workaround in skill (T5 undo):** probe `information_schema.tables` first,
only run the data-row SELECT if the table is known to exist.

**Cleanup:** Documentation-only — add to the engineering-gotchas register.
This is general PG semantics, not platform-specific, but it bites every
integration test that does conditional SQL based on schema state.
