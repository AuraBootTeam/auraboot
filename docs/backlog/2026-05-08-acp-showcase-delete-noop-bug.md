# ACP Showcase — `acs:delete_*` Commands Return Success But Don't Delete

- **Date**: 2026-05-08
- **Severity**: P1 (data integrity) — **platform-wide**, not acs-specific
- **Discovered by**: E2E `acp-showcase-lifecycle.spec.ts` — ACS-011 caught it; ACS-007 was hiding it
- **Owner**: TBD
- **Status**: **Fixed** in this commit (FieldMapPhase routing asymmetry). Re-enabling
  ACS-007/011 from `test.fixme` requires backend restart on the host stack to pick up the fix.

## Root Cause

`FieldMapPhase.execute()` had an asymmetric routing check (since
`67f38c8b refactor: extract CommandExecutorImpl into Phase Pipeline architecture`):

```java
boolean isDeleteOp     = "delete".equalsIgnoreCase(ctx.getRequest().getOperationType());  // OLD
String cmdType         = (String) ec.get("type");
boolean isStateTransition = "state_transition".equalsIgnoreCase(cmdType);
boolean isCreateOrUpdate  = "create".equalsIgnoreCase(cmdType) || "update".equalsIgnoreCase(cmdType);
```

`isStateTransition` and `isCreateOrUpdate` fall back to `command.type`, but `isDeleteOp`
only checks `request.operationType`. CLI / API callers using `aura exec <code> --target <pid>`
(or any frontend that doesn't explicitly send `operationType: "delete"`) hit:

- `request.operationType == null` → `isDeleteOp = false`
- All five routing flags false → routes to `executeFieldMapPhase` (explicit-binding-rules path)
- Most plugins have no `field_map` BindingRules with `operationType=delete` → empty for-loop
- No `dynamicDataMapper.delete(...)` call ever fires
- Pipeline reaches `CompletionPhase`, returns `phaseReached: completed`

The `executeImplicitFieldMapPhase` path (which DOES call `dynamicDataMapper.delete`) was
never reached for delete commands missing `operationType`.

## Fix

`FieldMapPhase.java`:

```java
boolean isDeleteOp = "delete".equalsIgnoreCase(ctx.getRequest().getOperationType())
        || "delete".equalsIgnoreCase(cmdType);  // mirror state_transition / create_or_update
```

## Regression Test

`FieldMapPhaseTest#deleteCommandWithoutOperationTypeStillRoutesToImplicitFieldMap` —
asserts that a `type: "delete"` command with `request.operationType == null` routes to
`executeImplicitFieldMapPhase`. Without the fix the test fails (routing falls through
to `executeFieldMapPhase` and the implicit path is never called).

## Reproduction (pre-fix)

```bash
# Pick any inactive safety rule
PID=$(aura query acs_safety_rule -f acs_rule_status=inactive --format json --agent-mode \
  | python3 -c "import sys,json; print(json.load(sys.stdin)[0]['pid'])")

aura exec acs:delete_safety_rule --target "$PID" --format json --agent-mode
# → {"commandCode":"acs:delete_safety_rule","phaseReached":"completed","data":{"recordId":"<PID>"}}

PGPASSWORD=auraboot psql -h localhost -U auraboot -d aura_boot \
  -c "SELECT id, acs_rule_code, acs_rule_status FROM mt_acs_safety_rule WHERE pid='$PID';"
# → row STILL present, status unchanged
```

Same reproduction for `acs:delete_demo_request` against any draft request.

## Observed

- Command response: `phaseReached: "completed"`, returns the recordId, no error.
- DB: row unchanged. No `deleted_flag` column on `mt_acs_safety_rule` or `mt_acs_demo_request` (no soft-delete column → expected to be hard-deleted, but isn't).
- UI delete via row dropdown (ACS-007) appears to "work" because the table optimistically refreshes / re-paginates and the row scrolls off page 1 — but DB row persists.

## Why ACS-007 Was Originally Green

ACS-007 only asserted `tbody tr.not.toBeVisible({ hasText: reqCode })`. After the "delete" returns success, the UI re-fetches the list. If the deleted record sorts off page 1 (default `created_at desc`, page size 10), the row is genuinely not visible **even though it's still in DB**. False negative — caught only when ACS-011's assertion checked DB-side state.

Patch already applied: ACS-007 now also asserts via `page.request.get(/api/dynamic/.../{pid})` returns 404 → both ACS-007 and ACS-011 now correctly report failure.

## Suspected Root Cause

Two hypotheses to check:

1. **Platform-level**: `type: "delete"` Commands on dynamic models (`mt_*` tables) may have a regression in the delete handler. Check `DynamicCommandService` / `DynamicDataMapper.deleteByPid`.
2. **Plugin-level**: `acs:delete_*` JSON has a `preconditions` block. Maybe a precondition validator is short-circuiting and returning success without invoking the actual delete:
   ```json
   "preconditions": [{"field": "acs_rule_status", "operator": "EQ", "value": "inactive"}]
   ```
   Both records meet preconditions, so this shouldn't trigger — but worth checking precondition pipeline branching.

Other plugins (`workflow-demo`, `core-announcement`, `page-manager`, `platform-admin`) also have `type: delete` commands — quick smoke test will tell whether bug is platform-wide or acs-specific.

## Impact

- **acp-showcase plugin**: delete buttons (UI + CLI + API) silently no-op. Records pile up on every E2E run / demo seed re-run / human user click.
- **Downstream**: if bug is platform-wide, every `type: delete` command is broken — that's a data integrity P0.
- **Demo seeding**: `seed-all.sh` re-run after the bug fix should recreate cleanly (no stale duplicates in real env).

## Fix Plan

1. **Triage scope** (1h): smoke-test `wd:delete_leave_request` and one other plugin's delete. Determine platform vs plugin.
2. **Root cause**: read `DynamicCommandService.handle*` for `CommandType.DELETE` branch + audit pipeline.
3. **Patch + integration test**: backend test in `platform/src/test/java/.../DynamicCommandDeleteIntegrationTest.java` covering: precondition met → row deleted; precondition unmet → row NOT deleted + clear error code.
4. **Cleanup**: drop the lingering test rows from local dev DB.
5. **Re-enable ACS-007 / ACS-011**: remove `test.fixme` markers in `acp-showcase-lifecycle.spec.ts` once fix lands.

## Related

- E2E spec with fixme markers: `web-admin/tests/e2e/templates/acp-showcase-lifecycle.spec.ts:329` (ACS-007), `:493` (ACS-011)
- Discovery commit: TBD (this commit)
