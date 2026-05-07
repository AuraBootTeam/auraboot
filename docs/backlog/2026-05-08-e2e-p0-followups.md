# E2E P0 Coverage Follow-ups (2026-05-08)

Source: commit `b98269e3 test(e2e): cover 4 P0 menu gaps (audit 2026-05-08)`.

After the mechanical menu-vs-spec audit (DB `ab_menu` ⨯ grep `tests/e2e/`),
4 of the 7 zero-reference menus got golden-standard specs. They land with
**18 passed / 7 fixme / 3 fail / 20 cascade-skip** — the failures and skips
are blocked on product/config gaps, not spec quality.

This doc tracks what to fix to unblock the cascade and turn the fixme cases
into real passes.

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
