# E2E P0 Coverage Follow-ups (2026-05-08)

Source: commit `b98269e3 test(e2e): cover 4 P0 menu gaps (audit 2026-05-08)`.

After the mechanical menu-vs-spec audit (DB `ab_menu` ⨯ grep `tests/e2e/`),
4 of the 7 zero-reference menus got golden-standard specs. They land with
**18 passed / 7 fixme / 3 fail / 20 cascade-skip** — the failures and skips
are blocked on product/config gaps, not spec quality.

This doc tracks what to fix to unblock the cascade and turn the fixme cases
into real passes.

## Status snapshot (closing this session)

| Gap | State | Commit / Owner |
|---|---|---|
| G-1 dashboard architecture migration | ✅ resolved | `dbaf043d` + `d945f57c` |
| G-2 scheduled_task detail toolbar | ✅ resolved | `9207ebab` |
| G-3 detail tabs `role="tablist"` | ✅ resolved | `bcd6a0d5` |
| G-4 enable/disable/trigger commands | ⏸ open | — |
| G-5 ACS state machine `block_request` | ⏸ open (low pri) | — |
| G-6 ACS-002 Radix Select persistence | 🔒 latent (acp-showcase removed) | — |
| G-7 cron expression validation | ✅ resolved | `d237a975` |
| G-8 unique scheduled_task.name | ✅ resolved | `d237a975` |
| G-9 delete-row routing | ✅ resolved | `d237a975` |
| G-10 detail toolbar action.command | 🔒 latent (acp-showcase removed) | — |
| G-13 plain `title` in dashboard JSON | ✅ resolved (misdiagnosis) | `d945f57c` |
| G-14 smart-table-chart shorthand | ✅ resolved | `6e9a85ec` + `d497de10` (owner) |
| G-15 JWT 20002 docker stack | ⏸ open (blocks docker reproducer) | — |
| Dead menus (3) | ⏸ open (frontend components missing) | — |

Closing fixme'd specs (DASH-002..005, ST-006/007/009) — owner re-run on
host backend or post-G-15-fix docker stack should flip them to real
passes. Fixme entries already cite the resolving commit.

## Scope change — acp-showcase plugin removed

Commit `59050eed chore(acp-showcase): remove demo plugin per platformization
design P0'` removed the entire `plugins/acp-showcase/config/` directory.
The `acs_demo_request` and `acs_safety_rule` models / pages / commands no
longer exist.

Consequences for this audit:
- **G-3 (detail-page tabs `role="tablist"`)** ✅ remains valid — the fix
  was in the platform renderer (`DetailPageContent.tsx` /
  `TabsBlockRenderer.tsx`), not the plugin. It survives.
- **G-6 / G-10** become latent — they describe real bugs but the test
  cases that surfaced them (`ACS-002` / `ACS-007`) targeted removed pages.
  Keep the entries for record; they will resurface when other plugins
  exercise the same code paths (form-buttons + detail toolbar with
  `action: {type: "command", ...}`).
- The 2 ACS spec files (`acs-demo-request-lifecycle.spec.ts`,
  `acs-safety-rule-lifecycle.spec.ts`) are deleted in the follow-up
  cleanup commit. The 4-spec → 2-spec reduction is reflected in
  `scripts/dev/run-p0-e2e-docker.sh` which now imports acp-showcase as
  optional.

Fully-docker reproducer (after acp-showcase removal):
**8 passed / 1 fail (DASH-002 = G-1) / 6 fixme / 4 cascade**.

## Product config gaps (blocking ~20 cascade-skipped tests)

### G-1. `e2et_order_dashboard_list` renders as single-list, not multi-block grid

- **File**: `plugins/test-fixtures/config/pages.json:1278` (`pageKey=e2et_order_dashboard_list`)
- **Symptom**: page renders the `e2et_order` list with full pagination/filters,
  no distinct block titles for the 3 declared `data-table` blocks.
- **Spec impact**: DASH-001..006 (5 tests) — currently fixme on DASH-001,
  DASH-002 cascade-fails when fixme is removed.
- **Fix**: page_schema needs `kind=dashboard`-style multi-block grid layout,
  or each block needs a visible header so spec can anchor by title.
- **Re-enable specs**: drop `test.fixme` on DASH-001 once block titles render.

### G-2. `scheduled_task_detail` has no edit/delete toolbar

- **File**: `plugins/*/config/pages/scheduled_task_detail.json` (pages.json:1926)
- **Symptom**: detail page has only `form-section` + `execution_logs` sub-table.
  No header toolbar, no `page-actions` block, no edit/delete entry from detail.
- **Spec impact**: ST-004 fixme; ST-005..010 cascade-skipped.
- **Fix**: add `page-actions` block calling `admin:update_scheduled_task`,
  `admin:delete_scheduled_task` commands.
- **Re-enable specs**: drop `test.fixme` on ST-004.

### G-3. `acs_demo_request_detail` declares tabs but renderer doesn't emit `[role="tablist"]`

- **File**: `plugins/acp-showcase/config/pages/acs_demo_request_detail.json`
- **Symptom**: JSON contains `blockType: "tabs"` with 4 tabs
  (overview / pipeline_journey / safety_audit / grounding_plan), but the
  detail-page renderer does not output a `[role="tablist"]` element. End user
  sees a flat scroll instead of tabbed UI.
- **Spec impact**: ACS-004 fixme; ACS-005..014 cascade-skipped.
- **Fix**: detail-page renderer must support `blockType: "tabs"` with proper
  ARIA roles. (Frontend code, not plugin JSON.)
- **Re-enable specs**: drop `test.fixme` on ACS-004.

### G-4. `scheduled_task` enable / disable / trigger commands missing

- **Files**:
  - `plugins/*/config/commands/*scheduled_task*.json` (no `enable_*`, `disable_*`, `trigger_*`)
  - `plugins/*/config/pages/scheduled_task_list.json` (no row-action for these)
- **Symptom**: cannot enable/disable a scheduled task or run it on demand
  through the UI. Original spec had REST-API-tunnel tests; they were removed
  during golden-standard rewrite (PUT-API fake-pass red line).
- **Spec impact**: 2 test cases were deleted from `scheduled-task-lifecycle.spec.ts`
  with backlog notes pointing here.
- **Fix**: add the 3 commands + matching processor beans, then add row-actions
  in `scheduled_task_list.json`. Then add E2E coverage as a follow-up commit.

### G-5. ACS state machine: `block_request` precondition

- **Spec impact**: ACS-009 has a pre-existing `test.skip(true, '…pipeline mock not available')` (legacy, not introduced by this work).
- **Status**: lower priority — needs upstream pipeline state simulator.

## Selector regressions exposed by the run

### G-6. ACS-002 form-buttons rendered but submit click does not create record

- After adding `form-buttons` block to `acs_demo_request_form.json` and reimporting,
  ACS-002 still fails: form fields fill, submit button is now visible and clicked,
  but no `acs:create_demo_request` POST fires (or the response shape doesn't match
  the expected `code === '0'`).
- **Likely cause**: Radix Select values for `category` / `priority` aren't being
  persisted into the form state before submit, so the create command rejects
  with a missing-required-field error (similar to ACS-003 fixme reasoning).
- **Action**: instrument once and add either a real create command field-binding
  fix or a more robust Radix-select fill helper.

## G-7..G-9 — backend validation / delete gaps surfaced by ST cascade

After G-2 was applied (toolbar added) and ST-004 unblocked, three further
backend gaps surfaced one after another in the scheduled_task suite:

### G-7. `admin:create_scheduled_task` accepts arbitrary cron strings

- **Symptom**: posting `cron_expression: "every-minute-please"` returns
  `code=0` (accepted).
- **Fix**: validate with `org.springframework.scheduling.support.CronExpression.parse()`
  in the create/update handler before persisting.
- **Re-enable**: drop fixme on ST-006.

### G-8. No unique constraint on `scheduled_task.name`

- **Symptom**: creating two tasks with the same name both succeed with `code=0`.
- **Fix**: add `@unique` on `scheduled_task.name` in `models.json` AND a
  `UNIQUE INDEX` in `schema.sql`, or enforce duplicate-name check in the
  create handler.
- **Re-enable**: drop fixme on ST-007.

### G-9. Row delete does not remove row from list

- **Symptom**: after row-action delete + confirm, the deleted row remains
  visible (12 retries). Either delete fails silently, soft-delete leaks into
  list query, or list cache is stale.
- **Fix**: investigate `admin:delete_scheduled_task` handler and the list
  endpoint's `deleted_flag` filter.
- **Re-enable**: drop fixme on ST-009.

## G-10 — Detail-page header toolbar buttons no-op for `action.type=command`

Surfaced by the ACS suite once G-3 was applied (tabs renderable) and the
cascade unblocked through ACS-007.

- **Symptom**: ACS detail page renders the header toolbar buttons (Submit,
  Approve, Reject, Edit, Delete) per `acs_demo_request_detail.json`, but
  clicking Submit fires no `acs:submit_request` POST. Spec
  `waitForResponse(/api/meta/commands/execute/acs:submit_request)` times out
  at 20s.
- **Root cause hypothesis**: the inline toolbar in
  `web-admin/app/framework/meta/rendering/pages/DetailPageContent.tsx:525-548`
  uses `handleAction(button, recordData)` from `useActionHandler`. The
  button JSON declares `action: {type: "command", command: "..."}` (object
  form), while `useActionHandler.normalizeAction` is reading
  `button.commandCode` (top-level field) — there is a shape mismatch with
  no fallback. FormButtonsBlockRenderer / ToolbarBlockRenderer accept both
  shapes via different code paths.
- **Fix**: in `useActionHandler` (or its `normalizeAction` helper), accept
  `action.type === 'command'` → use `action.command` as the command code,
  in addition to existing `commandCode` top-level fallback. Add a unit test.
- **Re-enable**: drop fixme on ACS-007. Likely also unblocks ACS-008..014
  cascade-skipped tests.

## G-13 — Misdiagnosed (resolved): dashboards ARE imported, my JSON missed `title`

Initially logged as "PluginResourceImporter ignores dashboards resource
type". Re-investigation while preparing G-13 fix found:

- `PluginImportServiceImpl:1298` already calls `importDashboards()`
  inside the PAGE stage. Resource counts are tracked under PAGE
  (intentional, see comment "emitted as PAGE resource records").
- `PluginResourceImporterImpl:1544 importDashboard()` exists end-to-end.
- The actual silent skip was in `DashboardDefinitionDTO.isValid()` which
  requires a plain `title` string. My initial JSON had only
  `title:zh-CN` / `title:en` (which `@JsonAnySetter` captures into
  `unknownFields`), leaving `title` field null → `isValid() == false` →
  the import loop logs `Skipping invalid dashboard definition` and
  moves on.

**Fix landed**: add `"title": "订单统计仪表盘"` (plain string) alongside the
i18n variants in `plugins/test-fixtures/config/dashboards/e2et_order_dashboard.json`.
Same shape as `crm_overview` which works.

**Workaround removal**: with the title fix, `aura plugin import` should
populate `ab_dashboard` automatically — no more manual
`POST /api/dashboards` + `UPDATE status='published'` needed in
`scripts/dev/run-p0-e2e-docker.sh`. (Verification on a fresh stack
blocked by an unrelated JWT 20002 issue on the docker stack instance —
defer to host-backend re-run.)

## G-14 — `smart-table-chart` widget has no flat-list mode

Surfaced when `e2et_order_dashboard` was migrated from `pages.json` (G-1)
to `config/dashboards/`. The widget JSON adopts the same shape as
`crm-starter`'s `crm_overview` — `config: { modelCode, defaultSort, table: {columns:[...]} }` —
but the widget renders **"No data available"** for both fixtures.

- **Root cause**: `SmartTableChart` reads `props.dataSource` (typed
  `ChartDataSource = aggregate | namedQuery | static`) and
  `DashboardViewer.tsx:116-118` forwards `widget.config.dataSource`. The
  flat shape `config.modelCode + config.table.columns` has no consumer.
  No syntactic-sugar adapter exists in `WidgetRenderer.tsx` either.
- **Impact**: any "list-style" dashboard widget (recent records, filtered
  table) shows empty. crm_overview dashboard is likely affected too.
- **Fix options**:
  - (a) Add a `smart-list-table` widget type that takes
    `{ modelCode, table.columns, defaultSort, defaultFilters }` and queries
    `/api/dynamic/{model}/list` directly.
  - (b) Add a syntactic-sugar adapter in `DashboardViewer` that wraps
    `config.modelCode + config.table.columns` into
    `dataSource: { type: 'aggregate', ...derived }`.
  - (c) Migrate fixtures to `dataSource: { type: 'namedQuery', queryCode }`
    and ship 3 namedQuery defs (least flexible, requires DB query
    coordination).
- **Re-enable**: drop `test.fixme` on DASH-002, DASH-003, DASH-004,
  DASH-005 once any of (a)/(b)/(c) lands.

## G-1 status — partial: architecture plumbing done, widget data binding deferred

After commits in this session (G-1 plumbing + G-13 + G-14 backlog):

- ✅ Menu repointed to `/dashboards/view/e2et_order_dashboard`.
- ✅ pages.json `e2et_order_dashboard_list` removed.
- ✅ `plugins/test-fixtures/config/dashboards/e2et_order_dashboard.json`
  written with 3 widgets (recent orders / pending payments / customers).
- ✅ test-fixtures `plugin.json` declares `dashboards` resource dir.
- ✅ DASH spec navigation rewritten to use the dashboard URL pattern
  (matches `crm-starter-demo-dashboard.spec.ts`).
- ✅ DASH-001 (real test, formerly fixme'd) now passes — sidebar nav +
  3 widget titles + 3 tables visible.
- ✅ DASH-006 (no error alert) passes.
- ⚠️ DASH-002..005 fixme'd to G-14 — widget renders title but inner data is
  empty until `smart-table-chart` flat-list mode lands.

Final docker run on G-1 branch: **5 passed / 4 fixme / 0 fail**.

## G-15 — JWT 20002 "Security credentials changed" intermittent on fresh docker stacks

Surfaced when running `scripts/dev/run-p0-e2e-docker.sh` against a freshly
built isolated stack. First run after `start-isolated.sh` worked
(slug=p0-g3); later attempts (p0-g13b, p0-g14) all fail at the first
plugin import with HTTP 401:

  `{"code":"20002","message":"Security credentials changed, please re-login","context":"/api/plugins/import/execute-direct"}`

Even basic `GET /api/tenants/me` with the freshly-minted JWT returns the
same error. So the JWT is rejected by `JwtAuthenticationFilter` — either
on the `securityVersion` mismatch path or on the
`sessionManagementService.isSessionValid` path.

### Trace evidence
- `LoginCompletionHelper:92-99` calls
  `sessionManagementService.createSession(user.getId(), jwt, ipAddress, userAgent)`
  inside `try { ... } catch (Exception e) { log.warn(...) }`. The catch
  is wide — any insert failure (duplicate key, FK, NOT NULL violation)
  silently logs and proceeds. Login still returns the JWT.
- `JwtAuthenticationFilter:113-118` calls
  `sessionManagementService.isSessionValid(jwt)` — looks up by
  `token_hash`. If the session row never persisted, returns false →
  filter rejects with 20002.

### Fix direction
- Tighten `LoginCompletionHelper:94-99` catch — log the actual exception
  class + message at WARN, so subsequent runs surface the real failure
  instead of silently proceeding. Or fail-fast on session creation
  error rather than swallowing.
- After tightening, re-run on a fresh stack and capture the WARN to
  identify the underlying cause.

### Workaround for now
The host backend works; the docker reproducer is unreliable until G-15
is fixed. P0 verification can fall back to host stack with the caveat
of single-worktree concurrency.

## Dead menus (no React component bound)

These menu entries route to paths that have **no implementation** (frontend
component missing). Writing E2E for them = testing white screens. They were
explicitly excluded from the P0 audit:

| Menu | Backend | Frontend | Action |
|------|---------|----------|--------|
| `/audit-logs` | ❌ no controller | ❌ no component | Either build the page or remove the menu entry from `default-bootstrap.json` |
| `/settings/i18n-coverage` | ✅ `I18nAdminController` + `I18nCoverageService` | ❌ no React component | Build the React page on top of existing API |
| `/settings/i18n-workflow` | ✅ `I18nAdminController` + `I18nWorkflowIntegrationTest` | ❌ no React component | Build the React page on top of existing API |

## Verification commands

After applying any of the G-1..G-4 fixes:

```bash
# 1. Reimport the affected plugin
aura plugin import plugins/<plugin-name> --yes

# 2. Re-run the 4 P0 specs
cd web-admin
PW_SKIP_WEBSERVER=1 NO_PROXY=localhost npx playwright test --project=chromium \
  tests/e2e/admin/scheduled-task-lifecycle.spec.ts \
  tests/e2e/agent-control-plane/acs-demo-request-lifecycle.spec.ts \
  tests/e2e/agent-control-plane/acs-safety-rule-lifecycle.spec.ts \
  tests/e2e/e2et-order/e2et-order-dashboard-lifecycle.spec.ts \
  --workers=1 --reporter=line

# 3. If a fixme'd test now passes, remove the fixme line and re-run.
```

## Out of scope

- The 35 menus that already have ≥3 spec references (covered, depth review is
  a separate `e2e-feature-coverage` skill scope, not this audit).
- The 14 menus with 1-2 spec references (suspected shallow coverage, needs
  manual sample inspection — separate follow-up).
