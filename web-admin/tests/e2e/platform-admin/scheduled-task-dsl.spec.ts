/**
 * Scheduled Task DSL Page (/p/scheduled_task) — Lifecycle E2E
 *
 * Plugin: platform-admin
 * Page keys: scheduled_task_list / scheduled_task_form / scheduled_task_detail
 * Model:    scheduled_task (table ab_scheduled_task)
 * Commands: admin:create_scheduled_task / admin:update_scheduled_task /
 *           admin:delete_scheduled_task
 *
 * Goal — regression-protect three recently-fixed defects on this page:
 *   #2  Row "More actions" dropdown opens reliably (no actionability hack)
 *   #3  i18n keys are resolved everywhere (no `scheduled_task` / `CREATED_AT`
 *       literals leaking into header / column titles)
 *   #4  `delete` row action carries `targetRecordId` and truly removes the row
 *       from both DB and DOM (no "fake success")
 *
 * Patterned on the gold standard
 *   web-admin/tests/e2e/templates/thr-leave-request-lifecycle.spec.ts
 *
 * Discipline (per AGENTS.md §「E2E 测试」):
 *   - Navigate via sidebar menu, not page.goto
 *   - No waitForTimeout, no afterAll cleanup
 *   - Body uses click/fill > page.request (no PUT-API bypass)
 *   - beforeAll cleans previous-run residue via API (the delete UI path is
 *     itself the assertion in ST-005)
 *
 * @since 10.4.0
 */

import { test, expect, type Page } from '../../fixtures';
import type { Locator } from '@playwright/test';
import {
  uniqueId,
  waitForFormReady,
  waitForToast,
  findRowInPaginatedList,
  clickRowActionByLocator,
  ensureSidebarExpanded,
} from '../helpers/index';

// ---------------------------------------------------------------------------
// Serial — tests share a single record through the lifecycle
// ---------------------------------------------------------------------------
test.describe.configure({ mode: 'serial' });

// ---------------------------------------------------------------------------
// Test data — `e2e-dsl-` prefix so beforeAll can purge previous residue
// ---------------------------------------------------------------------------
const UID = uniqueId('e2e-dsl');
const TASK_NAME = `${UID}-create`;
const CRON_INITIAL = '0 30 2 * * ?'; // Spring 6-field cron: at 02:30 every day
const CRON_EDITED = '0 45 3 * * ?'; // edited to 03:45
const HANDLER_BEAN = 'e2eDslHandler';
const HANDLER_METHOD = 'execute';
const TIMEOUT_MS = '60000';

const PAGE_LIST = '/p/scheduled_task';
const API_LIST = '/api/dynamic/scheduled_task/list';
const API_DETAIL = '/api/dynamic/scheduled_task';
const CMD_CREATE = 'admin:create_scheduled_task';
const CMD_UPDATE = 'admin:update_scheduled_task';
const CMD_DELETE = 'admin:delete_scheduled_task';

// ---------------------------------------------------------------------------
// Navigation — sidebar click only (D1)
// ---------------------------------------------------------------------------
async function navigateToScheduledTaskList(page: Page): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
  await ensureSidebarExpanded(page);

  const nav = page.locator('nav, aside, [role="navigation"]').first();
  await nav.waitFor({ state: 'visible', timeout: 10_000 });

  // Open parent group "系统管理" (System Management)
  const parentBtn = nav
    .getByRole('button', { name: /系统管理|System Management/i })
    .or(nav.locator('[title="系统管理"], [title="System Management"]'))
    .first();
  await parentBtn.scrollIntoViewIfNeeded().catch(() => null);
  await parentBtn.evaluate((el: HTMLElement) => el.click());

  // Click leaf "定时任务" — match menu by href to avoid label drift
  const leafLink = nav.locator('a[href="/p/scheduled_task"]').first();
  await leafLink.waitFor({ state: 'attached', timeout: 8_000 });

  const listResp = page.waitForResponse(
    (r) => r.url().includes(API_LIST) && r.status() === 200,
    { timeout: 20_000 },
  );
  await leafLink.evaluate((el: HTMLElement) => el.click());
  await listResp;

  // Smoke: URL switched and table mounted
  await expect(page).toHaveURL(new RegExp(`${PAGE_LIST}(?:\\?|$|#)`));
  await expect(
    page.locator('table, [class*="ant-table"], [data-testid="dynamic-list"]').first(),
  ).toBeVisible({ timeout: 15_000 });
}

// Read total record count from the list API directly. Used to assert
// "list +1 after create" / "list -1 after delete" without depending on the
// UI's own pagination footer rendering.
async function fetchTotal(page: Page, keyword?: string): Promise<number> {
  const url = keyword
    ? `${API_LIST}?pageNum=1&pageSize=1&keyword=${encodeURIComponent(keyword)}`
    : `${API_LIST}?pageNum=1&pageSize=1`;
  const resp = await page.request.get(url, { timeout: 10_000 });
  if (!resp.ok()) return -1;
  const body = (await resp.json().catch(() => ({}))) as any;
  const data = body?.data ?? {};
  return Number(data.total ?? data.totalCount ?? (Array.isArray(data.records) ? data.records.length : 0));
}

// ---------------------------------------------------------------------------
// Test suite
// ---------------------------------------------------------------------------
test.describe('Scheduled Task DSL Page (/p/scheduled_task)', () => {
  test.setTimeout(120_000);

  let createdPid: string;
  let totalBeforeCreate: number;
  let totalAfterCreate: number;

  // -------------------------------------------------------------------------
  // beforeAll — purge `e2e-dsl-*` residue from previous runs via API
  // (we delete via REST so the command path stays unverified until ST-005)
  // -------------------------------------------------------------------------
  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      const resp = await page.request.get(
        `${API_LIST}?pageNum=1&pageSize=100&keyword=${encodeURIComponent('e2e-dsl-')}`,
        { timeout: 10_000 },
      );
      if (!resp.ok()) return;
      const body = (await resp.json().catch(() => ({}))) as any;
      const records: any[] = body?.data?.records ?? [];
      for (const r of records) {
        const pid = String(r.pid ?? r.id ?? '');
        if (!pid) continue;
        await page.request
          .delete(`${API_DETAIL}/${pid}`, { timeout: 8_000 })
          .catch(() => null);
      }
    } finally {
      await ctx.close();
    }
  });

  // =========================================================================
  // ST-001: Sidebar navigation + i18n regression (defends fix #66f19782)
  // =========================================================================
  test('ST-001 @smoke — Navigate via sidebar → page renders with i18n labels', async ({
    page,
  }) => {
    await navigateToScheduledTaskList(page);

    // [#3 regression] Page header shows resolved Chinese label, NOT raw key
    const heading = page
      .locator('h1, h2, [data-testid="page-title"], header')
      .filter({ hasText: /定时任务|Scheduled Tasks/i })
      .first();
    await expect(heading).toBeVisible({ timeout: 5_000 });

    // Title text must be the localized name, never the model code literal
    const headingText = await heading.innerText();
    expect(headingText, 'Page title must NOT leak the raw model code').not.toMatch(
      /\bscheduled_task\b/,
    );

    // [#3 regression] Column headers must show Chinese, NOT the raw field key
    const columnHeaders = page.locator('thead th, [role="columnheader"]');
    await expect(columnHeaders.first()).toBeVisible({ timeout: 5_000 });
    const headerText = (await columnHeaders.allInnerTexts()).join('\n');

    expect(headerText, '"创建时间" column should be localized').toMatch(/创建时间|Created/i);
    expect(
      headerText,
      'Column headers must not contain raw field name `created_at` or `CREATED_AT`',
    ).not.toMatch(/\b(?:CREATED_AT|created_at)\b/);
    expect(headerText, 'Column headers must not contain raw `name` literal as title').not.toMatch(
      /^name$/m,
    );

    // Toolbar primary action must be localized too
    const createBtn = page
      .locator('[data-testid="toolbar-btn-create"]')
      .or(page.getByRole('button', { name: /^(新建|创建|Create|New)$/i }))
      .first();
    await expect(createBtn).toBeVisible({ timeout: 5_000 });
    const createBtnText = (await createBtn.innerText()).trim();
    expect(createBtnText, 'Create button text must be localized').not.toBe('create');
  });

  // =========================================================================
  // ST-002: Create via UI form — list +1 + row carries correct values (D4..D6)
  // =========================================================================
  test('ST-002 @smoke — Create cron task via toolbar → row appears in list', async ({ page }) => {
    await navigateToScheduledTaskList(page);

    totalBeforeCreate = await fetchTotal(page);
    expect(totalBeforeCreate, 'Baseline total must be readable').toBeGreaterThanOrEqual(0);

    // Click "新建" — DSL toolbar emits data-testid="toolbar-btn-create"
    const createBtn = page
      .locator('[data-testid="toolbar-btn-create"]')
      .or(page.getByRole('button', { name: /^(新建|创建|Create|New)$/i }))
      .first();
    await expect(createBtn).toBeVisible();
    await createBtn.evaluate((el: HTMLElement) => el.click());

    // Navigate target = scheduled_task_form with create command in the URL
    await page.waitForURL(
      /\/p\/scheduled_task(?:_form)?\/(?:new|create)\?.*commandCode=admin(?:%3A|:)create_scheduled_task/,
      { timeout: 15_000 },
    );

    await waitForFormReady(page, 15_000);

    // [D5] Form fields rendered (form-section "basic" + "handler")
    const nameInput = page
      .locator('[data-testid="form-field-name"] input, [data-field="name"] input')
      .first();
    await expect(nameInput).toBeVisible({ timeout: 10_000 });

    const cronInput = page
      .locator(
        '[data-testid="form-field-cron_expression"] input, [data-field="cron_expression"] input',
      )
      .first();
    await expect(cronInput, 'cron_expression field must render').toBeVisible({ timeout: 5_000 });

    const handlerBeanInput = page
      .locator('[data-testid="form-field-handler_bean"] input, [data-field="handler_bean"] input')
      .first();
    await expect(handlerBeanInput, 'handler_bean field must render').toBeVisible({
      timeout: 5_000,
    });

    const handlerMethodInput = page
      .locator(
        '[data-testid="form-field-handler_method"] input, [data-field="handler_method"] input',
      )
      .first();
    await expect(handlerMethodInput, 'handler_method field must render').toBeVisible({
      timeout: 5_000,
    });

    // enabled is a boolean — accept either a switch button or a checkbox
    const enabledControl = page
      .locator(
        '[data-testid="form-field-enabled"] [role="switch"], ' +
          '[data-field="enabled"] [role="switch"], ' +
          '[data-testid="form-field-enabled"] input[type="checkbox"], ' +
          '[data-field="enabled"] input[type="checkbox"]',
      )
      .first();
    await expect(enabledControl, 'enabled toggle must render').toBeVisible({ timeout: 5_000 });

    // [D4] Fill ALL load-bearing fields — not just required
    await nameInput.click();
    await nameInput.fill(TASK_NAME);

    // task_type is required + dict-driven — pick whatever first option exists
    const taskTypeCombo = page
      .locator(
        '[data-testid="form-field-task_type"] [role="combobox"], ' +
          '[data-field="task_type"] [role="combobox"]',
      )
      .first();
    if (await taskTypeCombo.isVisible({ timeout: 3_000 }).catch(() => false)) {
      await taskTypeCombo.click({ timeout: 8_000 });
      const firstOpt = page.locator('[role="option"]').first();
      if (await firstOpt.isVisible({ timeout: 3_000 }).catch(() => false)) {
        await firstOpt.click();
      } else {
        await page.keyboard.press('Escape').catch(() => null);
      }
    }

    await cronInput.click();
    await cronInput.fill(CRON_INITIAL);

    await handlerBeanInput.click();
    await handlerBeanInput.fill(HANDLER_BEAN);

    await handlerMethodInput.click();
    await handlerMethodInput.fill(HANDLER_METHOD);

    const timeoutInput = page
      .locator('[data-testid="form-field-timeout_ms"] input, [data-field="timeout_ms"] input')
      .first();
    if (await timeoutInput.isVisible({ timeout: 2_000 }).catch(() => false)) {
      await timeoutInput.click();
      await timeoutInput.fill(TIMEOUT_MS);
    }

    // Ensure enabled = true (most defaults are true; click only if currently false)
    const enabledChecked = await enabledControl
      .getAttribute('aria-checked')
      .catch(() => null);
    if (enabledChecked === 'false') {
      await enabledControl.click().catch(() => null);
    }

    // Submit — wait for the create command response, not a vague navigation
    const cmdResp = page.waitForResponse(
      (r) =>
        r.url().includes(`/api/meta/commands/execute/${CMD_CREATE}`) &&
        r.request().method().toLowerCase() === 'post' &&
        r.status() === 200,
      { timeout: 20_000 },
    );

    const submitBtn = page
      .locator('[data-testid="form-btn-submit"]')
      .or(page.getByRole('button', { name: /^(提交|保存|Submit|Save)$/i }))
      .first();
    await submitBtn.click();
    const resp = await cmdResp;
    const body = (await resp.json().catch(() => ({}))) as any;
    expect(String(body?.code), 'Create command should succeed').toBe('0');

    const resultData = body?.data?.data ?? {};
    createdPid = String(resultData?.recordId ?? resultData?.pid ?? '');
    expect(createdPid, 'Create response must carry a recordId').toBeTruthy();

    // After submit, page should redirect back to list URL
    await page.waitForURL(new RegExp(`${PAGE_LIST}(?:\\?|$|#|/)`), { timeout: 15_000 });
    await page.waitForResponse(
      (r) => r.url().includes(API_LIST) && r.status() === 200,
      { timeout: 10_000 },
    );

    // [D6] List total has incremented and the new row carries our values
    totalAfterCreate = await fetchTotal(page);
    expect(
      totalAfterCreate,
      'List total must increment by 1 after UI create',
    ).toBe(totalBeforeCreate + 1);

    const row: Locator = await findRowInPaginatedList(page, TASK_NAME, 12_000);
    await expect(row).toBeVisible({ timeout: 5_000 });

    const rowText = await row.innerText();
    expect(rowText, 'Row must show the task name').toContain(TASK_NAME);
    expect(rowText, 'Row must show the cron expression we just submitted').toContain(CRON_INITIAL);
    expect(rowText, 'Row must show handler_bean we submitted').toContain(HANDLER_BEAN);
  });

  // =========================================================================
  // ST-003: Detail page via row More-actions dropdown (defends fix #2 ffbfa171)
  // =========================================================================
  test('ST-003 — Open detail via row More-actions dropdown → values match input', async ({
    page,
  }) => {
    expect(createdPid, 'ST-003 requires the record from ST-002').toBeTruthy();

    await navigateToScheduledTaskList(page);
    const row = await findRowInPaginatedList(page, TASK_NAME, 12_000);
    await expect(row).toBeVisible();

    // [#2 regression] Use the real RowActions dropdown helper — opens via
    // a genuine pointer click, NOT page.evaluate(el.click()). The helper
    // hovers + clicks moreBtn + waits for portal dropdown, then clicks
    // [data-testid="row-action-detail"]. If the dropdown silently fails to
    // open (the bug we fixed), this navigation will time out.
    const detailResp = page.waitForResponse(
      (r) =>
        r.url().includes(API_DETAIL) &&
        !r.url().includes('/list') &&
        r.status() === 200,
      { timeout: 15_000 },
    );
    await clickRowActionByLocator(page, row, 'detail');
    await detailResp;

    await page.waitForURL(/\/p\/scheduled_task(?:_detail)?\/view\/[^/]+/, { timeout: 15_000 });

    // [D7] Each input we typed must be re-displayed on the detail page
    const main = page.locator('main, [role="main"]').first();
    await expect(main).toBeVisible({ timeout: 10_000 });
    await expect(main.getByText(TASK_NAME).first()).toBeVisible({ timeout: 8_000 });
    await expect(
      main.getByText(CRON_INITIAL).first(),
      'Detail page must show the original cron expression',
    ).toBeVisible({ timeout: 5_000 });
    await expect(
      main.getByText(HANDLER_BEAN).first(),
      'Detail page must show handler_bean',
    ).toBeVisible({ timeout: 5_000 });
    await expect(
      main.getByText(HANDLER_METHOD).first(),
      'Detail page must show handler_method',
    ).toBeVisible({ timeout: 5_000 });
  });

  // =========================================================================
  // ST-004: Edit via More-actions → updated cron persists in list
  // =========================================================================
  test('ST-004 — Edit cron via row dropdown → list reflects new value', async ({ page }) => {
    expect(createdPid, 'ST-004 requires the record from ST-002').toBeTruthy();

    await navigateToScheduledTaskList(page);
    const row = await findRowInPaginatedList(page, TASK_NAME, 12_000);
    await expect(row).toBeVisible();

    await clickRowActionByLocator(page, row, 'edit');
    await page.waitForURL(
      /\/p\/scheduled_task(?:_form)?\/edit\/[^/]+\?.*commandCode=admin(?:%3A|:)update_scheduled_task/,
      { timeout: 15_000 },
    );

    await waitForFormReady(page, 15_000);

    // [D8] Pre-fill check — the form must come back already populated
    const nameInput = page
      .locator('[data-testid="form-field-name"] input, [data-field="name"] input')
      .first();
    await expect(nameInput).toHaveValue(TASK_NAME, { timeout: 10_000 });

    const cronInput = page
      .locator(
        '[data-testid="form-field-cron_expression"] input, [data-field="cron_expression"] input',
      )
      .first();
    await expect(cronInput).toHaveValue(CRON_INITIAL, { timeout: 5_000 });

    // Edit cron + bump timeout (60000 keeps validators happy — see backlog #4)
    await cronInput.click();
    await cronInput.fill(CRON_EDITED);

    const timeoutInput = page
      .locator('[data-testid="form-field-timeout_ms"] input, [data-field="timeout_ms"] input')
      .first();
    if (await timeoutInput.isVisible({ timeout: 2_000 }).catch(() => false)) {
      await timeoutInput.click();
      await timeoutInput.fill(TIMEOUT_MS);
    }

    const cmdResp = page.waitForResponse(
      (r) =>
        r.url().includes(`/api/meta/commands/execute/${CMD_UPDATE}`) &&
        r.request().method().toLowerCase() === 'post' &&
        r.status() === 200,
      { timeout: 20_000 },
    );
    const submitBtn = page
      .locator('[data-testid="form-btn-submit"]')
      .or(page.getByRole('button', { name: /^(提交|保存|Submit|Save)$/i }))
      .first();
    await submitBtn.click();
    const resp = await cmdResp;
    const body = (await resp.json().catch(() => ({}))) as any;
    expect(String(body?.code), 'Update command should succeed').toBe('0');

    await page.waitForURL(new RegExp(`${PAGE_LIST}(?:\\?|$|#|/)`), { timeout: 15_000 });
    await page.waitForResponse(
      (r) => r.url().includes(API_LIST) && r.status() === 200,
      { timeout: 10_000 },
    );

    // List row must now show the new cron expression and NOT the old one
    const updatedRow = await findRowInPaginatedList(page, TASK_NAME, 12_000);
    await expect(updatedRow).toBeVisible();
    const rowText = await updatedRow.innerText();
    expect(rowText, 'Row must reflect the edited cron').toContain(CRON_EDITED);
    expect(rowText, 'Row must NOT still show the original cron').not.toContain(CRON_INITIAL);
  });

  // =========================================================================
  // ST-005: Delete via UI (defends real-delete fix — list -1 + row gone)
  // =========================================================================
  test('ST-005 @smoke — Delete via row dropdown → list -1 + row gone from DOM', async ({
    page,
  }) => {
    expect(createdPid, 'ST-005 requires the record from ST-002').toBeTruthy();

    await navigateToScheduledTaskList(page);
    const totalBeforeDelete = await fetchTotal(page);
    expect(totalBeforeDelete).toBe(totalAfterCreate);

    const row = await findRowInPaginatedList(page, TASK_NAME, 12_000);
    await expect(row).toBeVisible();

    // Open dropdown + click delete — the helper handles confirm dialog discovery
    // separately (it just clicks the action; we assert the dialog ourselves)
    await clickRowActionByLocator(page, row, 'delete');

    // Confirm dialog (DSL `confirm: "delete.confirm"` — uses standard ConfirmDialog)
    const dialog = page
      .locator('[data-testid="confirm-dialog"], [role="alertdialog"], .ant-modal-confirm')
      .first();
    await dialog.waitFor({ state: 'visible', timeout: 5_000 });

    const cmdResp = page.waitForResponse(
      (r) =>
        r.url().includes(`/api/meta/commands/execute/${CMD_DELETE}`) &&
        r.request().method().toLowerCase() === 'post' &&
        r.status() === 200,
      { timeout: 15_000 },
    );

    const okBtn = page
      .locator('[data-testid="confirm-ok"]')
      .or(
        dialog.locator('button').filter({ hasText: /^(确定|确认|删除|OK|Delete|Yes)$/i }),
      )
      .first();
    await okBtn.click();

    const resp = await cmdResp;
    const body = (await resp.json().catch(() => ({}))) as any;
    expect(
      String(body?.code),
      'Delete command must report success (code === "0")',
    ).toBe('0');

    // Toast feedback (best effort — not all stacks emit one)
    await waitForToast(page, undefined, 3_000).catch(() => null);

    // Wait for list refresh
    await page.waitForResponse(
      (r) => r.url().includes(API_LIST) && r.status() === 200,
      { timeout: 10_000 },
    );

    // [#4 regression — anti "fake success"] Total must decrement
    const totalAfterDelete = await fetchTotal(page);
    expect(
      totalAfterDelete,
      'List total must decrement by 1 after UI delete (regression: silent no-op)',
    ).toBe(totalBeforeDelete - 1);

    // [#4 regression] Row must be gone from the DOM (not just hidden)
    const ghostRow = page.locator('tbody tr').filter({ hasText: TASK_NAME });
    await expect(ghostRow, 'Deleted row must be removed from list DOM').toHaveCount(0, {
      timeout: 8_000,
    });

    // Final defense — direct DB read via list API confirms removal
    const lookup = await fetchTotal(page, TASK_NAME);
    expect(lookup, 'Deleted record must not be queryable by name keyword').toBe(0);
  });
});
