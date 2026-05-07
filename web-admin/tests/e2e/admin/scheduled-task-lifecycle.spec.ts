// E2E coverage for scheduled_task (P0 gap 2026-05-08, rewrite v2 — no REST assumption)
/**
 * Scheduled Task — Full Lifecycle E2E Test
 *
 * Modeled after gold standard `tests/e2e/templates/thr-leave-request-lifecycle.spec.ts`.
 *
 * Backend reality (verified 2026-05-08 via grep):
 *   - scheduled_task is a DSL-driven model (page_schema kind=list/form/detail published)
 *   - CRUD goes through DynamicController + Command pipeline:
 *       GET  /api/dynamic/scheduled_task/list
 *       GET  /api/dynamic/scheduled_task/{pid}
 *       POST /api/meta/commands/execute/admin:create_scheduled_task
 *       POST /api/meta/commands/execute/admin:update_scheduled_task
 *       POST /api/meta/commands/execute/admin:delete_scheduled_task
 *   - commands.json contains ONLY create/update/delete; there is NO
 *     admin:enable_scheduled_task / disable_scheduled_task / trigger_scheduled_task.
 *   - pages.json row-actions are ONLY view/edit/delete (no enable/disable/trigger button).
 *   - Detail page has a sub-table `execution_logs` with dataSource
 *     `/api/scheduled-tasks/${recordId}/logs?limit=50` — the sub-table CONTAINER
 *     is part of the DSL contract; whether the URL responds is plugin-internal.
 *
 * Therefore enable/disable/trigger lifecycle tests have been REMOVED:
 *   - There is no Command, no UI button, and no proven REST endpoint to drive them.
 *   - Backlog: track product gap "scheduled_task enable/disable/trigger" — needs
 *     Command + row-action + handler bean wiring before E2E can cover it.
 *
 * Covered dimensions (after rewrite):
 *   D1  Sidebar menu navigation (no page.goto direct)
 *   D2  List rendering — column headers + row data assertion
 *   D3  Keyword search filter
 *   D4  Create via full UI form (Command pipeline)
 *   D5  Form field types — Select for task_type, text input for cron_expression
 *   D6  Required-empty validation (UI)
 *   D7  Detail page renders all fields + execution_logs sub-table container
 *   D8  Edit cron + description → reopen → values match
 *   D9  Delete via row action (with confirmation dialog)
 *   D10 Negative: invalid cron rejected (Command-pipeline)
 *   D11 Negative: duplicate name rejected (Command-pipeline)
 *
 * Removed dimensions (backlog):
 *   - Enable/Disable toggle (no admin:enable/disable_scheduled_task command, no UI)
 *   - Trigger now / manual run (no admin:trigger_scheduled_task command, no UI)
 *
 * @since P0-coverage rewrite v2
 */

import { test, expect, type Page } from '../../fixtures';
import {
  uniqueId,
  executeCommandViaApi,
  findRowInPaginatedList,
  waitForFormReady,
  waitForToast,
  ensureFilterFormOpen,
} from '../helpers/index';

// ---------------------------------------------------------------------------
// Serial mode — tests share the created task pid through the lifecycle
// ---------------------------------------------------------------------------
test.describe.configure({ mode: 'serial' });

// ---------------------------------------------------------------------------
// Constants — `unique-` prefix avoids conflicts with seeded system tasks
// ---------------------------------------------------------------------------
const UID = uniqueId('unique-ST');
const TASK_NAME = `${UID}-job`;
const TASK_NAME_DUP = TASK_NAME; // for duplicate-name negative case
const TASK_NAME_DELETE = `${UID}-delete-target`;
const HANDLER_BEAN = 'systemHealthCheckTask';
const HANDLER_METHOD = 'execute';
const CRON_INITIAL = '0 0 2 * * ?'; // every day 02:00 (Spring 6-field)
const CRON_EDITED = '0 30 3 * * ?'; // every day 03:30
const CRON_INVALID = 'every-minute-please';
const TASK_DESCRIPTION = `E2E scheduled task ${UID}`;
const TASK_DESCRIPTION_EDITED = `Edited description ${UID}`;
const PARAMS_JSON = '{"channel":"e2e","tag":"' + UID + '"}';

// ---------------------------------------------------------------------------
// Sidebar navigation — system management → scheduled tasks (D1)
// ---------------------------------------------------------------------------
async function navigateToScheduledTaskList(page: Page): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });

  const nav = page.locator('nav');
  await nav.first().waitFor({ state: 'visible', timeout: 10_000 });

  // Expand parent menu "系统管理" / "System Administration"
  const rootBtn = nav
    .getByRole('button', { name: /系统管理|System|Administration/i })
    .first();
  await rootBtn.scrollIntoViewIfNeeded();
  await rootBtn.evaluate((el: HTMLElement) => el.click());

  // Click leaf menu — its href targets `/p/scheduled_task`
  const leafLink = nav.locator('a[href*="scheduled_task"]').first();
  await leafLink.waitFor({ state: 'attached', timeout: 8_000 });

  const listResponsePromise = page.waitForResponse(
    (r) =>
      r.url().includes('/api/dynamic/scheduled_task') &&
      r.url().includes('list') &&
      r.status() === 200,
    { timeout: 20_000 },
  );
  await leafLink.evaluate((el: HTMLElement) => el.click());
  await listResponsePromise.catch(() => null);

  await expect(
    page.locator('table, [class*="ant-table"], [data-testid="dynamic-list"]').first(),
  ).toBeVisible({ timeout: 15_000 });
}

async function navigateToScheduledTaskDetail(page: Page, pid: string): Promise<void> {
  await navigateToScheduledTaskList(page);
  const detailResponsePromise = page.waitForResponse(
    (r) =>
      r.url().includes('/api/dynamic/scheduled_task') && !r.url().includes('/list'),
    { timeout: 15_000 },
  );
  // Detail route: `/p/:tableName/view/:recordId`
  await page.goto(`/p/scheduled_task/view/${pid}`);
  await detailResponsePromise.catch(() => null);
  await page.waitForLoadState('domcontentloaded');
  await page
    .locator('text=加载中...')
    .first()
    .waitFor({ state: 'hidden', timeout: 15_000 })
    .catch(() => null);
  await expect(page.getByText(/基本信息|Basic Information/i).first()).toBeVisible({
    timeout: 15_000,
  });
}

// ---------------------------------------------------------------------------
// Form-fill helpers
// ---------------------------------------------------------------------------
async function selectTaskType(page: Page, label: RegExp): Promise<void> {
  const trigger = page
    .locator(
      '[data-testid="form-field-task_type"] [role="combobox"], ' +
        '[data-field="task_type"] [role="combobox"]',
    )
    .first();
  await trigger.waitFor({ state: 'visible', timeout: 10_000 });
  await trigger.click();
  await page
    .locator('[role="listbox"]')
    .first()
    .waitFor({ state: 'visible', timeout: 5_000 })
    .catch(() => null);
  const option = page.locator('[role="option"]').filter({ hasText: label }).first();
  if (await option.isVisible({ timeout: 3_000 }).catch(() => false)) {
    await option.click();
  } else {
    await page.locator('[role="option"]').first().click().catch(() => null);
  }
  await page
    .locator('[role="listbox"]')
    .first()
    .waitFor({ state: 'hidden', timeout: 3_000 })
    .catch(() => null);
}

async function fillField(page: Page, fieldCode: string, value: string): Promise<void> {
  const input = page
    .locator(
      `[data-testid="form-field-${fieldCode}"] input, [data-field="${fieldCode}"] input, ` +
        `[data-testid="form-field-${fieldCode}"] textarea, [data-field="${fieldCode}"] textarea`,
    )
    .first();
  await input.waitFor({ state: 'visible', timeout: 10_000 });
  await input.click();
  await input.fill(value);
}

async function clickSubmit(page: Page): Promise<unknown> {
  const submitBtn = page.locator('[data-testid="form-btn-submit"]').first();
  const submitBtnAlt = page.getByRole('button', { name: /提交|保存|Submit|Save/i }).first();
  const btn = (await submitBtn.isVisible({ timeout: 2_000 }).catch(() => false))
    ? submitBtn
    : submitBtnAlt;
  const commandResponsePromise = page.waitForResponse(
    (r) =>
      r.url().includes('/api/meta/commands/execute/') &&
      r.request().method().toLowerCase() === 'post',
    { timeout: 20_000 },
  );
  await btn.click();
  return commandResponsePromise;
}

// ---------------------------------------------------------------------------
// Test suite
// ---------------------------------------------------------------------------

test.describe('Scheduled Task — Full Lifecycle (P0)', () => {
  test.setTimeout(120_000);

  let taskPid: string;

  // =========================================================================
  // ST-001 [D1 + D2]: sidebar nav → list page renders with table + columns
  // =========================================================================
  test('ST-001 @smoke — Navigate via sidebar menu → list page loads with table', async ({
    page,
  }) => {
    await navigateToScheduledTaskList(page);

    const table = page.locator('table, [class*="ant-table"]').first();
    await expect(table).toBeVisible();

    // At least 5 columns rendered (matches DSL: name / task_type / cron / handler / enabled)
    const headerCount = await page.locator('thead th, [role="columnheader"]').count();
    expect(headerCount, 'list table should have ≥ 5 columns').toBeGreaterThanOrEqual(5);
  });

  // =========================================================================
  // ST-002 [D4 + D5]: create scheduled task via full UI form
  // =========================================================================
  test('ST-002 @critical — Create scheduled task via full form → row appears', async ({
    page,
  }) => {
    await navigateToScheduledTaskList(page);

    const createBtn = page
      .locator('[data-testid="toolbar-btn-create"]')
      .or(page.getByRole('button', { name: /^(新建|创建|Create|新增)$/i }))
      .first();
    await expect(createBtn).toBeVisible();
    await createBtn.evaluate((el: HTMLElement) => el.click());

    await page
      .waitForURL(/\/p\/scheduled_task(?:_form)?(?:\/new)?(?:\?|$)/, { timeout: 15_000 })
      .catch(() => null);
    await waitForFormReady(page, 15_000);

    // [D5] task_type renders as combobox (Radix Select)
    const typeCombo = page
      .locator(
        '[data-testid="form-field-task_type"] [role="combobox"], [data-field="task_type"] [role="combobox"]',
      )
      .first();
    await expect(typeCombo, 'task_type should render as Select combobox').toBeVisible({
      timeout: 10_000,
    });

    // [D5] cron_expression must be a text input, not a date picker
    const cronInput = page
      .locator(
        '[data-testid="form-field-cron_expression"] input, [data-field="cron_expression"] input',
      )
      .first();
    await expect(cronInput, 'cron_expression should render as text input').toBeVisible();
    expect(
      await cronInput.getAttribute('type'),
      'cron input must not be a date picker',
    ).not.toBe('date');

    // [D4] Fill ALL fields
    await fillField(page, 'name', TASK_NAME);
    await fillField(page, 'description', TASK_DESCRIPTION);
    await selectTaskType(page, /Cron|表达式/i);
    await fillField(page, 'cron_expression', CRON_INITIAL);
    await fillField(page, 'handler_bean', HANDLER_BEAN);
    await fillField(page, 'handler_method', HANDLER_METHOD);
    await fillField(page, 'params', PARAMS_JSON);

    const maxRetries = page
      .locator('[data-testid="form-field-max_retries"] input, [data-field="max_retries"] input')
      .first();
    if (await maxRetries.isVisible({ timeout: 2_000 }).catch(() => false)) {
      await maxRetries.click();
      await maxRetries.fill('3');
    }
    const timeoutMs = page
      .locator('[data-testid="form-field-timeout_ms"] input, [data-field="timeout_ms"] input')
      .first();
    if (await timeoutMs.isVisible({ timeout: 2_000 }).catch(() => false)) {
      await timeoutMs.click();
      await timeoutMs.fill('30000');
    }

    // enabled — boolean toggle (default may be true; ensure on)
    const enabledToggle = page
      .locator(
        '[data-testid="form-field-enabled"] [role="switch"], ' +
          '[data-testid="form-field-enabled"] input[type="checkbox"], ' +
          '[data-field="enabled"] [role="switch"], ' +
          '[data-field="enabled"] input[type="checkbox"]',
      )
      .first();
    if (await enabledToggle.isVisible({ timeout: 2_000 }).catch(() => false)) {
      const state = await enabledToggle.getAttribute('aria-checked').catch(() => null);
      if (state === 'false') {
        await enabledToggle.click();
      }
    }

    // Submit through Command pipeline
    const commandResponse = await clickSubmit(page);
    const body = await (commandResponse as any).json().catch(() => ({}));
    expect(String(body?.code), 'create command should succeed').toBe('0');
    const result = body?.data?.data ?? body?.data ?? {};
    taskPid = String(result?.recordId ?? result?.pid ?? '');
    expect(taskPid, 'create should return a valid pid').toBeTruthy();

    await waitForToast(page, undefined, 5_000).catch(() => null);
    await page.waitForURL(/\/p\/scheduled_task/, { timeout: 15_000 }).catch(() => null);

    // New row appears with correct values
    const row = await findRowInPaginatedList(page, TASK_NAME, 12_000);
    await expect(row).toBeVisible();
    const rowText = await row.innerText();
    expect(rowText).toContain(TASK_NAME);
    expect(rowText, 'row should show cron expression').toMatch(/0 0 2 \* \* \?/);
    expect(rowText.toLowerCase(), 'row should show handler bean').toContain(
      HANDLER_BEAN.toLowerCase(),
    );
  });

  // =========================================================================
  // ST-003 [D7]: detail page renders all fields + execution_logs sub-table
  // =========================================================================
  test('ST-003 @critical — Detail page shows all fields + execution_logs sub-table container', async ({
    page,
  }) => {
    expect(taskPid, 'ST-003 requires taskPid from ST-002').toBeTruthy();
    await navigateToScheduledTaskDetail(page, taskPid);

    const main = page.locator('main, [role="main"]').first();
    await expect(main).toBeVisible({ timeout: 10_000 });

    // [D7] Field values — assert specific text, not just visibility
    await expect(main.getByText(TASK_NAME).first()).toBeVisible({ timeout: 10_000 });
    await expect(main.getByText(/0 0 2 \* \* \?/).first()).toBeVisible({ timeout: 5_000 });
    await expect(main.getByText(new RegExp(HANDLER_BEAN, 'i')).first()).toBeVisible({
      timeout: 5_000,
    });

    // execution_logs sub-table container must render (DSL contract).
    // Rows may be empty (no schedule has fired yet, manual trigger is
    // product-gap). The title heading IS rendered, but the empty state
    // currently replaces <thead> with a "暂无数据" placeholder so column
    // headers from the DSL never reach the DOM. This is a product-UX gap
    // — column skeleton should remain visible per DSL contract.
    const subTableTitle = page.getByText(/执行日志|Execution Logs/i).first();
    await expect(subTableTitle, 'execution_logs sub-table should render').toBeVisible({
      timeout: 5_000,
    });

    // [PRODUCT-GAP] Empty sub-table drops <thead>; column headers absent.
    // Per task discipline, do NOT weaken to placeholder-text visibility.
    test.fixme(
      true,
      'ST-003: empty execution_logs sub-table drops <thead>, DSL column headers not rendered',
    );
    const triggerTypeHeader = page
      .locator('thead th, [role="columnheader"]')
      .filter({ hasText: /触发方式|Trigger Type/i })
      .first();
    await expect(
      triggerTypeHeader,
      'execution_logs sub-table column "triggerType" must render per DSL',
    ).toBeVisible({ timeout: 5_000 });
  });

  // =========================================================================
  // ST-004 [D8]: edit cron_expression + description → reopen → match
  // =========================================================================
  test('ST-004 @critical — Edit cron + description → save → values updated', async ({ page }) => {
    expect(taskPid, 'ST-004 requires taskPid from ST-002').toBeTruthy();
    await navigateToScheduledTaskDetail(page, taskPid);

    const editBtn = page.getByRole('button', { name: /编辑|Edit/i }).first();
    await editBtn.waitFor({ state: 'visible', timeout: 5_000 });
    await editBtn.click();
    await page.waitForURL(/\/p\/scheduled_task\/edit\/[^/]+$/, { timeout: 15_000 });
    await waitForFormReady(page, 15_000);

    const cronInput = page
      .locator(
        '[data-testid="form-field-cron_expression"] input, [data-field="cron_expression"] input',
      )
      .first();
    await expect(cronInput).toBeVisible({ timeout: 10_000 });
    await expect(cronInput).toHaveValue(/0 0 2/, { timeout: 5_000 });

    await cronInput.click();
    await cronInput.fill(CRON_EDITED);
    await fillField(page, 'description', TASK_DESCRIPTION_EDITED);

    const commandResponse = await clickSubmit(page);
    const body = await (commandResponse as any).json().catch(() => ({}));
    expect(String(body?.code), 'update command should succeed').toBe('0');

    // Reopen detail and verify
    await navigateToScheduledTaskDetail(page, taskPid);
    const main = page.locator('main, [role="main"]').first();
    await expect(main.getByText(/0 30 3 \* \* \?/).first()).toBeVisible({ timeout: 5_000 });
    await expect(main.getByText(new RegExp(UID)).first()).toBeVisible({ timeout: 5_000 });
  });

  // =========================================================================
  // ST-005 [D6]: form validation — submit empty form shows errors
  // =========================================================================
  test('ST-005 — Validation: submit empty form shows required-field errors', async ({ page }) => {
    await navigateToScheduledTaskList(page);

    const createBtn = page
      .locator('[data-testid="toolbar-btn-create"]')
      .or(page.getByRole('button', { name: /^(新建|创建|Create|新增)$/i }))
      .first();
    await createBtn.click();
    await waitForFormReady(page, 15_000);

    const submitBtn = page.locator('[data-testid="form-btn-submit"]').first();
    const submitBtnAlt = page.getByRole('button', { name: /提交|保存|Submit|Save/i }).first();
    const btn = (await submitBtn.isVisible({ timeout: 2_000 }).catch(() => false))
      ? submitBtn
      : submitBtnAlt;
    await btn.click();

    const errorVisible = await page
      .locator(
        '.ant-form-item-explain-error, [data-testid*="error"], .field-error, [role="alert"], .text-red-500, .text-destructive',
      )
      .first()
      .isVisible({ timeout: 5_000 })
      .catch(() => false);
    expect(errorVisible, 'empty form submission should show validation errors').toBeTruthy();
  });

  // =========================================================================
  // ST-006 [D10]: invalid cron rejected via Command pipeline
  // =========================================================================
  test('ST-006 — Negative: invalid cron rejected by create command', async ({ page }) => {
    test.fixme(
      true,
      'product backend gap: admin:create_scheduled_task accepts arbitrary strings as cron_expression (e.g. "every-minute-please" returns code=0). No cron parser validation on the server. Backlog G-7: validate cron_expression with org.springframework.scheduling.support.CronExpression.parse() in the create/update handler before persisting.',
    );
    const invalidResult = await executeCommandViaApi(
      page,
      'admin:create_scheduled_task',
      {
        name: `${UID}-invalid-cron`,
        description: 'invalid cron probe',
        task_type: 'cron',
        cron_expression: CRON_INVALID,
        handler_bean: HANDLER_BEAN,
        handler_method: HANDLER_METHOD,
        params: '{}',
        max_retries: 0,
        timeout_ms: 1000,
        enabled: true,
      },
      undefined,
      'create',
      { allowHttpError: true },
    );
    expect(
      invalidResult.code !== '0',
      `invalid cron "${CRON_INVALID}" must be rejected (got code=${invalidResult.code})`,
    ).toBeTruthy();
  });

  // =========================================================================
  // ST-007 [D11]: duplicate name rejected via Command pipeline
  // =========================================================================
  test('ST-007 — Negative: duplicate name rejected by create command', async ({ page }) => {
    test.fixme(
      true,
      'product backend gap: scheduled_task model has no unique constraint on name (or admin:create_scheduled_task does not enforce one). Creating two tasks with the same name returns code=0 both times. Backlog G-8: add @unique on scheduled_task.name in models.json + UNIQUE INDEX in schema.sql, OR enforce duplicate-name check in the create handler.',
    );
    expect(taskPid, 'ST-007 requires ST-002 to have created TASK_NAME').toBeTruthy();
    const dupResult = await executeCommandViaApi(
      page,
      'admin:create_scheduled_task',
      {
        name: TASK_NAME_DUP,
        description: 'duplicate probe',
        task_type: 'cron',
        cron_expression: CRON_INITIAL,
        handler_bean: HANDLER_BEAN,
        handler_method: HANDLER_METHOD,
        params: '{}',
        max_retries: 0,
        timeout_ms: 1000,
        enabled: true,
      },
      undefined,
      'create',
      { allowHttpError: true },
    );
    expect(
      dupResult.code !== '0',
      `duplicate name "${TASK_NAME_DUP}" must be rejected (got code=${dupResult.code})`,
    ).toBeTruthy();
  });

  // =========================================================================
  // ST-008 [D3]: keyword search filters list
  // =========================================================================
  test('ST-008 — Keyword search filters scheduled task list', async ({ page }) => {
    await navigateToScheduledTaskList(page);
    await ensureFilterFormOpen(page);

    const searchInput = page
      .locator(
        '[data-testid="search-input"], [data-testid="table-search-input"], input[placeholder*="搜索"], input[placeholder*="Search"]',
      )
      .first();
    if (!(await searchInput.isVisible({ timeout: 3_000 }).catch(() => false))) {
      test.fixme(
        true,
        'product gap: scheduled_task list page has no keyword search input — DSL has no toolbar search slot',
      );
      return;
    }
    const listResponsePromise = page.waitForResponse(
      (r) => r.url().includes('scheduled_task') && r.url().includes('list') && r.status() === 200,
      { timeout: 10_000 },
    );
    await searchInput.click();
    await searchInput.fill(UID.slice(0, 10));
    await searchInput.press('Enter');
    await listResponsePromise.catch(() => null);

    const rows = page.locator('tbody tr');
    const rowCount = await rows.count();
    expect(rowCount, 'search by UID prefix should yield ≥ 1 row').toBeGreaterThanOrEqual(1);
    for (let i = 0; i < Math.min(rowCount, 5); i++) {
      const txt = await rows.nth(i).innerText();
      expect(txt, `row ${i} should contain UID prefix`).toContain(UID.slice(0, 10));
    }
  });

  // =========================================================================
  // ST-009 [D9]: delete via row action with confirmation dialog
  // =========================================================================
  test('ST-009 @critical — Delete scheduled task via row action with confirmation', async ({
    page,
  }) => {
    test.fixme(
      true,
      'product gap: delete row-action fires admin:delete_scheduled_task and confirmation completes, but the deleted row remains visible in the list (12 retries, still "visible"). Either (a) delete command silently fails for this model, (b) softDelete is enabled in models.json but list query does not filter deleted_flag, or (c) list cache is not invalidated post-delete. Backlog G-9: investigate why scheduled_task delete does not remove the row.',
    );
    // Create a dedicated record to delete (don't delete taskPid — used by ST-010 trace)
    const seed = await executeCommandViaApi(
      page,
      'admin:create_scheduled_task',
      {
        name: TASK_NAME_DELETE,
        description: 'delete-me',
        task_type: 'cron',
        cron_expression: '0 0 4 * * ?',
        handler_bean: HANDLER_BEAN,
        handler_method: HANDLER_METHOD,
        params: '{}',
        max_retries: 0,
        timeout_ms: 1000,
        enabled: false,
      },
      undefined,
      'create',
    );
    expect(seed.code, 'seed create should succeed').toBe('0');

    await navigateToScheduledTaskList(page);
    const row = await findRowInPaginatedList(page, TASK_NAME_DELETE, 12_000);
    await expect(row).toBeVisible();

    await row.hover();
    const moreActionsBtn = row.locator('[data-testid="row-action-more"]').first();
    if (await moreActionsBtn.isVisible({ timeout: 2_000 }).catch(() => false)) {
      await moreActionsBtn.click();
      await page
        .locator('[data-testid="row-action-dropdown"]')
        .waitFor({ state: 'visible', timeout: 3_000 })
        .catch(() => null);
    }
    const deleteBtn = page.locator('[data-testid="row-action-delete"]').first();
    await deleteBtn.waitFor({ state: 'visible', timeout: 5_000 });

    const commandResponsePromise = page.waitForResponse(
      (r) =>
        r.url().includes('/api/meta/commands/execute/admin:delete_scheduled_task') &&
        r.status() === 200,
      { timeout: 20_000 },
    );
    await deleteBtn.click();

    const confirmDialog = page.locator(
      '[data-testid="confirm-dialog"], [role="alertdialog"], .ant-modal-confirm, .ant-popconfirm',
    );
    await confirmDialog.waitFor({ state: 'visible', timeout: 5_000 });
    const okBtn = page.locator('[data-testid="confirm-ok"]').first();
    const okBtnAlt = confirmDialog
      .locator('button')
      .filter({ hasText: /确定|确认|OK|Yes|删除|Delete/i })
      .first();
    const confirmBtn = (await okBtn.isVisible({ timeout: 1_000 }).catch(() => false))
      ? okBtn
      : okBtnAlt;
    await confirmBtn.click();
    await commandResponsePromise;

    // Row should disappear from list
    await page
      .waitForResponse(
        (r) => r.url().includes('scheduled_task') && r.url().includes('list') && r.status() === 200,
        { timeout: 10_000 },
      )
      .catch(() => null);
    const deletedRow = page.locator('tbody tr', { hasText: TASK_NAME_DELETE }).first();
    await expect(deletedRow).not.toBeVisible({ timeout: 8_000 });
  });

  // =========================================================================
  // ST-010: lifecycle trace — original task still exists with edited values
  // =========================================================================
  test('ST-010 — Lifecycle trace: created task still exists, edited values preserved', async ({
    page,
  }) => {
    expect(taskPid, 'ST-010 requires taskPid from ST-002').toBeTruthy();
    // Read via DynamicController (not the assumed REST endpoint)
    const resp = await page.request.get(`/api/dynamic/scheduled_task/${taskPid}`);
    expect(resp.ok(), 'dynamic get-by-pid should succeed').toBeTruthy();
    const body = await resp.json().catch(() => ({}));
    const data = body?.data ?? {};
    expect(data?.name).toBe(TASK_NAME);
    // After ST-004 cron was edited
    expect(String(data?.cronExpression ?? data?.cron_expression ?? '')).toMatch(
      /0 30 3 \* \* \?/,
    );
  });

  // =========================================================================
  // BACKLOG (product gaps — NOT covered, NOT faked):
  //   - Enable / Disable a scheduled task:
  //       * commands.json has no admin:enable_scheduled_task / disable_scheduled_task
  //       * pages.json row-actions are only view/edit/delete (no toggle button)
  //       * Requires: add Command + handler bean + row-action in DSL, then add E2E.
  //   - Trigger now (manual run) → execution log appears:
  //       * commands.json has no admin:trigger_scheduled_task
  //       * No UI button on list/detail toolbar
  //       * Requires: Command + handler that pushes to scheduler + row/detail
  //         action button + execution_logs sub-table polling, then add E2E.
  //   These are tracked as P1 product gaps; NOT to be backfilled with REST stubs.
  // =========================================================================
});
