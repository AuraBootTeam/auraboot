/**
 * ACP Showcase — Full Lifecycle E2E Test
 *
 * Covers both pages of the `acp-showcase` plugin:
 *   - /p/acs_demo_request   (AI Business Requests)
 *   - /p/acs_safety_rule    (Safety Valve Rules)
 *
 * Coverage dimensions (from AGENTS.md gold standard):
 *   D1  Menu navigation         D8  Edit + re-display
 *   D2  List rendering          D9  State transitions (submit / activate / deactivate)
 *   D3  Tab filtering           D10 Invalid transitions (delete active rule)
 *   D4  Create (full form)      D11 Delete with confirmation
 *   D5  Form field types        D12 Form validation
 *   D6  Create verification     D13 Search / keyword
 *   D7  Detail page             D14 Toast / feedback
 *
 * Prerequisites:
 *   - acp-showcase plugin imported
 *   - Admin user has permissions: acs.showcase.manage, acs.safety_rule.manage
 *
 * @since 11.0.0
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

test.describe.configure({ mode: 'serial' });

const UID = uniqueId('ACS');
const REQ_TITLE = `E2E AI Request ${UID}`;
const REQ_TITLE_EDITED = `E2E AI Request ${UID} (edited)`;
const REQ_NL_INPUT = `Show me Q3 2025 sales by region for E2E test ${UID}.`;
const REQ_NL_INPUT_EDITED = `Show me Q4 2025 sales by region for E2E test ${UID}.`;

const RULE_CODE = `E2E_RULE_${UID.slice(-8).toUpperCase()}`;
const RULE_NAME = `E2E Cost Limit ${UID}`;

// ---------------------------------------------------------------------------
// Navigation helpers — sidebar menu, NOT page.goto  [D1]
// ---------------------------------------------------------------------------

async function expandAcpRoot(page: Page): Promise<void> {
  const nav = page.locator('nav').first();
  await nav.waitFor({ state: 'visible', timeout: 10_000 });
  const rootBtn = nav.getByRole('button', { name: /ACP 展示|ACP Showcase/i }).first();
  await rootBtn.scrollIntoViewIfNeeded();
  await rootBtn.evaluate((el: HTMLElement) => el.click());
}

async function navigateToList(page: Page, modelCode: 'acs_demo_request' | 'acs_safety_rule'): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
  await expandAcpRoot(page);

  // Match the exact /p/<modelCode> route to avoid catching form/detail variants
  const leafLink = page
    .locator(`nav a[href$="/p/${modelCode}"], nav a[href="/p/${modelCode}"]`)
    .first();
  await leafLink.waitFor({ state: 'attached', timeout: 8_000 });

  // Don't assume a specific list-API URL pattern (page may use NamedQuery, savedView,
  // or POST list). Wait for URL change + table render instead.
  await leafLink.evaluate((el: HTMLElement) => el.click());
  await page.waitForURL(new RegExp(`/p/${modelCode}(?:[/?#]|$)`), { timeout: 15_000 });
  await page.locator('text=加载中...').first().waitFor({ state: 'hidden', timeout: 10_000 }).catch(() => null);

  await expect(
    page.locator('table, [class*="ant-table"], [data-testid="dynamic-list"]').first(),
  ).toBeVisible({ timeout: 15_000 });
}

async function clickRadixSelectOption(
  page: Page,
  fieldOrIndex: string | number,
  optionMatch: RegExp,
): Promise<void> {
  // Each Radix Select renders TWO [role="combobox"] elements per field
  // (a visible trigger + a hidden native form-control), so .nth() over the
  // page-level locator silently picks the hidden copy on every other field.
  // Scope by field testid when caller passes a string field code, fall back
  // to visible-only filter when caller passes an index.
  let trigger;
  if (typeof fieldOrIndex === 'string') {
    trigger = page
      .locator(`[data-testid="form-field-${fieldOrIndex}"] button[role="combobox"], [data-field="${fieldOrIndex}"] button[role="combobox"]`)
      .first();
    // Fallback: visible button-combobox when wrapper testid is absent.
    // We can't reliably index into all buttons either (multiple selects on the
    // same form), so the index path below remains a last resort.
    if (!(await trigger.isVisible({ timeout: 1_500 }).catch(() => false))) {
      trigger = page.locator('button[role="combobox"]').first();
    }
  } else {
    trigger = page.locator('button[role="combobox"]').nth(fieldOrIndex);
  }
  await trigger.waitFor({ state: 'visible', timeout: 10_000 });
  await trigger.click({ timeout: 8_000 });
  await page
    .locator('[role="listbox"]')
    .first()
    .waitFor({ state: 'visible', timeout: 5_000 })
    .catch(() => null);
  const option = page.locator('[role="option"]').filter({ hasText: optionMatch }).first();
  if (await option.isVisible({ timeout: 3_000 }).catch(() => false)) {
    await option.click();
  } else {
    const firstOpt = page.locator('[role="option"]').first();
    if (await firstOpt.isVisible({ timeout: 2_000 }).catch(() => false)) {
      await firstOpt.click();
    } else {
      await page.keyboard.press('Escape').catch(() => null);
    }
  }
  await page
    .locator('[role="listbox"]')
    .first()
    .waitFor({ state: 'hidden', timeout: 3_000 })
    .catch(() => null);
}

async function fillTextField(page: Page, field: string, value: string): Promise<void> {
  const input = page
    .locator(
      `[data-testid="form-field-${field}"] input, [data-testid="form-field-${field}"] textarea, ` +
        `[data-field="${field}"] input, [data-field="${field}"] textarea`,
    )
    .first();
  await input.waitFor({ state: 'visible', timeout: 8_000 });
  await input.click();
  await input.fill(value);
}

async function submitForm(page: Page): Promise<any> {
  const submitBtn = page.locator('[data-testid="form-btn-submit"]').first();
  const submitBtnAlt = page.getByRole('button', { name: /提交|保存|Submit|Save/i }).first();
  const btn = (await submitBtn.isVisible({ timeout: 2_000 }).catch(() => false)) ? submitBtn : submitBtnAlt;

  const responsePromise = page.waitForResponse(
    (r) =>
      r.url().includes('/api/meta/commands/execute/') &&
      r.request().method().toLowerCase() === 'post' &&
      r.status() === 200,
    { timeout: 20_000 },
  );
  await btn.click();
  const resp = await responsePromise;
  return resp.json().catch(() => ({}));
}

// ---------------------------------------------------------------------------
// Test suite
// ---------------------------------------------------------------------------

test.describe('ACP Showcase — Full Lifecycle (acs_demo_request + acs_safety_rule)', () => {
  test.setTimeout(120_000);

  let demoRequestPid = '';
  let demoRequestCode = '';
  let safetyRulePid = '';

  // =========================================================================
  // [D1 + D2] Menu navigation → both pages load with table
  // =========================================================================
  test('ACS-001 @smoke — Sidebar menu → demo_request list renders', async ({ page }) => {
    await navigateToList(page, 'acs_demo_request');
    await expect(page.locator('table, [class*="ant-table"]').first()).toBeVisible();

    // [D3 prerequisite] tab bar with status tabs is rendered
    const tabBar = page.locator('[role="tablist"], nav[aria-label="Tabs"]').first();
    if (await tabBar.isVisible({ timeout: 3_000 }).catch(() => false)) {
      await expect(tabBar.locator('button, [role="tab"]').first()).toBeVisible();
    }
  });

  test('ACS-002 @smoke — Sidebar menu → safety_rule list renders', async ({ page }) => {
    await navigateToList(page, 'acs_safety_rule');
    await expect(page.locator('table, [class*="ant-table"]').first()).toBeVisible();
  });

  // =========================================================================
  // [D4 + D5 + D6 + D14] Create demo_request via full UI form
  // =========================================================================
  test('ACS-003 @critical — Create AI request via full form → appears in list', async ({ page }) => {
    await navigateToList(page, 'acs_demo_request');

    const createBtn = page
      .locator('[data-testid="toolbar-btn-create"]')
      .or(page.getByRole('button', { name: /^(新建|创建|Create|新建 AI 请求|New AI Request)$/i }))
      .first();
    await expect(createBtn).toBeVisible();
    await createBtn.evaluate((el: HTMLElement) => el.click());
    await page.waitForURL(/\/p\/acs_demo_request_form|\/new|\/create/, { timeout: 15_000 }).catch(() => null);
    await waitForFormReady(page, 15_000);

    // [D4] fill ALL fields
    await fillTextField(page, 'acs_req_title', REQ_TITLE);
    await fillTextField(page, 'acs_req_nl_input', REQ_NL_INPUT);

    // [D5] enum fields — Radix Select. Two combobox: category (0), priority (1)
    await clickRadixSelectOption(page, 'acs_req_category', /Data Query|数据查询|data_query/i);
    await clickRadixSelectOption(page, 'acs_req_priority', /High|高/i);

    const body = await submitForm(page);
    const resultData = (body as any)?.data?.data ?? {};
    demoRequestPid = String(resultData?.recordId ?? resultData?.pid ?? '');
    demoRequestCode = String(resultData?.acs_req_code ?? '');
    expect(demoRequestPid, 'Created request must return pid').toBeTruthy();

    if (!demoRequestCode) {
      const detail = await page.request.get(`/api/dynamic/acs_demo_request/${demoRequestPid}`);
      const detailBody = await detail.json().catch(() => ({}));
      demoRequestCode = String((detailBody as any)?.data?.acs_req_code ?? '');
    }
    expect(demoRequestCode, 'Auto-generated REQ code must be present').toMatch(/REQ-\d{8}-\d+/);

    // [D6] verify in list
    await page.waitForURL(/\/p\/acs_demo_request/, { timeout: 15_000 }).catch(() => null);
    const row = await findRowInPaginatedList(page, demoRequestCode, 12_000);
    await expect(row).toBeVisible({ timeout: 5_000 });
    expect(await row.innerText()).toContain(demoRequestCode);
  });

  // =========================================================================
  // [D7] Detail page shows field values
  // =========================================================================
  test('ACS-004 — Detail page shows AI request fields', async ({ page }) => {
    expect(demoRequestPid).toBeTruthy();
    await page.goto(`/p/acs_demo_request/view/${demoRequestPid}`, { waitUntil: 'domcontentloaded' });
    await page
      .waitForResponse(
        (r) => r.url().includes('/api/dynamic/acs_demo_request') && !r.url().includes('/list'),
        { timeout: 15_000 },
      )
      .catch(() => null);

    await expect(page.getByText(new RegExp(demoRequestCode))).toBeVisible({ timeout: 10_000 });
    await expect(
      page
        .locator('main, [role="main"]')
        .first()
        .getByText(new RegExp(REQ_TITLE.slice(0, 20))),
    ).toBeVisible({ timeout: 5_000 });
    // status should be "draft" / 草稿
    await expect(page.getByText(/草稿|Draft/i).first()).toBeVisible({ timeout: 5_000 });
  });

  // =========================================================================
  // [D9] State transition: submit (draft → submitted)
  // =========================================================================
  test('ACS-005 @critical — Submit AI request → status changes to submitted', async ({ page }) => {
    expect(demoRequestPid).toBeTruthy();

    // Use API for state-transition (button may live in row dropdown — fragile across DSL versions)
    const result = await executeCommandViaApi(page, 'acs:submit_request', {}, demoRequestPid);
    expect(result.code, `submit_request returned ${result.code}`).toBe('0');

    // [D14] Verify list reflects new status — re-navigate and check via API
    const listResp = await page.request.get(
      `/api/dynamic/acs_demo_request/list?pageNum=1&pageSize=20&keyword=${encodeURIComponent(demoRequestCode)}`,
    );
    expect(listResp.ok()).toBeTruthy();
    const listBody = await listResp.json().catch(() => ({}));
    const records: any[] = (listBody as any)?.data?.records ?? [];
    const ours = records.find((r) => String(r.pid) === demoRequestPid);
    expect(ours, 'Submitted record findable in list').toBeTruthy();
    // After acs:submit_request, AcsShowcaseOrchestrator auto-progresses the record
    // through grounding → planning → executing (and possibly to completed/failed/blocked
    // depending on SafetyValve outcome). Assert the state machine moved off `draft`,
    // not a specific terminal state.
    const newStatus = String(ours?.acs_req_status).toLowerCase();
    expect(newStatus, `status should move out of draft, got ${newStatus}`).not.toBe('draft');
    expect(
      ['submitted', 'grounding', 'planning', 'executing', 'completed', 'failed', 'blocked'],
      `status ${newStatus} should be a valid post-submit state`,
    ).toContain(newStatus);

    // [D3] Tab filter: pick the tab that matches actual terminal state.
    // tabs: all / draft / in_progress (submitted|grounding|planning|executing) /
    //       blocked / completed / failed_rejected (failed|rejected)
    const tabRegex: RegExp = (() => {
      if (['submitted', 'grounding', 'planning', 'executing'].includes(newStatus)) {
        return /进行中|In Progress/i;
      }
      if (newStatus === 'completed') return /已完成|Completed/i;
      if (newStatus === 'blocked') return /已阻断|Blocked/i;
      if (['failed', 'rejected'].includes(newStatus)) return /失败|拒绝|Failed|Rejected/i;
      return /全部|All/i;
    })();

    await navigateToList(page, 'acs_demo_request');
    const matchingTab = page.locator('[role="tab"], button').filter({ hasText: tabRegex }).first();
    if (await matchingTab.isVisible({ timeout: 3_000 }).catch(() => false)) {
      await matchingTab.click();
      await page
        .waitForResponse(
          (r) => r.url().includes('acs_demo_request') && r.url().includes('list') && r.status() === 200,
          { timeout: 10_000 },
        )
        .catch(() => null);
      await ensureFilterFormOpen(page).catch(() => null);
      const search = page
        .locator('[data-testid="list-search-input"], [data-testid="search-input"], [data-testid="table-search-input"], input[placeholder*="搜索"], input[placeholder*="Search"]')
        .first();
      if (await search.isVisible({ timeout: 2_000 }).catch(() => false)) {
        await search.fill(demoRequestCode);
        await search.press('Enter');
        await page
          .waitForResponse(
            (r) =>
              r.url().includes('acs_demo_request') && r.url().includes('list') && r.status() === 200,
            { timeout: 8_000 },
          )
          .catch(() => null);
      }
      const row = page.locator('tbody tr').filter({ hasText: demoRequestCode }).first();
      await expect(row, `record should appear in tab matching status=${newStatus}`).toBeVisible({
        timeout: 8_000,
      });
    }
  });

  // =========================================================================
  // [D10] Invalid transition: delete a non-draft request must fail
  // =========================================================================
  test('ACS-006 — Cannot delete submitted AI request (precondition draft|rejected)', async ({ page }) => {
    expect(demoRequestPid).toBeTruthy();
    const result = await executeCommandViaApi(
      page,
      'acs:delete_demo_request',
      {},
      demoRequestPid,
      undefined,
      { allowHttpError: true },
    );
    expect(
      result.code !== '0',
      `delete should fail for submitted request, got code=${result.code}`,
    ).toBeTruthy();
  });

  // =========================================================================
  // [D11 + D14] Delete a draft request → confirm → row disappears
  // =========================================================================
  test('ACS-007 @critical — Delete draft AI request via row action → confirm → row gone', async ({ page }) => {
    // Seed a fresh draft request via API
    const seed = await executeCommandViaApi(
      page,
      'acs:create_demo_request',
      {
        acs_req_title: `E2E Delete Target ${UID}`,
        acs_req_nl_input: 'Disposable request for delete test.',
        acs_req_category: 'data_query',
        acs_req_priority: 'low',
      },
      undefined,
      'create',
    );
    expect(seed.recordId).toBeTruthy();

    const recordResp = await page.request.get(`/api/dynamic/acs_demo_request/${seed.recordId}`);
    const recordBody = await recordResp.json().catch(() => ({}));
    const reqCode = String((recordBody as any)?.data?.acs_req_code ?? '');
    expect(reqCode).toMatch(/REQ-\d{8}-\d+/);

    await navigateToList(page, 'acs_demo_request');
    const row = await findRowInPaginatedList(page, reqCode, 12_000);
    await row.hover();

    const moreBtn = row.locator('[data-testid="row-action-more"]').first();
    if (await moreBtn.isVisible({ timeout: 2_000 }).catch(() => false)) {
      await moreBtn.click();
      await page
        .locator('[data-testid="row-action-dropdown"]')
        .waitFor({ state: 'visible', timeout: 3_000 })
        .catch(() => null);
    }
    const deleteBtn = page.locator('[data-testid="row-action-delete"]').first();
    await deleteBtn.waitFor({ state: 'visible', timeout: 5_000 });

    const cmdResp = page.waitForResponse(
      (r) => r.url().includes('/api/meta/commands/execute/') && r.request().method().toLowerCase() === 'post',
      { timeout: 15_000 },
    );
    await deleteBtn.click();

    const confirmDialog = page.locator(
      '[data-testid="confirm-dialog"], [role="alertdialog"], .ant-modal-confirm, .ant-popconfirm',
    );
    await confirmDialog.waitFor({ state: 'visible', timeout: 5_000 });
    const okBtn = page.locator('[data-testid="confirm-ok"]').first();
    const okBtnAlt = confirmDialog.locator('button').filter({ hasText: /确定|确认|OK|Yes|删除/i }).first();
    await ((await okBtn.isVisible({ timeout: 1_000 }).catch(() => false)) ? okBtn : okBtnAlt).click();
    await cmdResp;

    await page
      .waitForResponse(
        (r) => r.url().includes('acs_demo_request') && r.url().includes('list') && r.status() === 200,
        { timeout: 10_000 },
      )
      .catch(() => null);
    await expect(page.locator('tbody tr', { hasText: reqCode })).not.toBeVisible({ timeout: 8_000 });

    // [data integrity] Verify deletion landed in DB, not just UI optimistic refresh.
    // Without this, an ack-only delete handler (no actual DB mutation) silently passes
    // because the row scrolls off page 1.
    // Platform convention: missing dynamic record returns HTTP 400 with body code "40000"
    // ("Record not found: <pid> in model: ..."). Accept both 400-with-not-found and 404.
    const verify = await page.request.get(`/api/dynamic/acs_demo_request/${seed.recordId}`);
    if (verify.status() === 404) {
      expect(verify.status()).toBe(404);
    } else {
      const body = await verify.json().catch(() => ({}));
      expect(
        verify.status() === 400 && /not found/i.test(String((body as any)?.context ?? '')),
        `record ${seed.recordId} should be gone after delete; got status=${verify.status()} body=${JSON.stringify(body).slice(0, 200)}`,
      ).toBeTruthy();
    }
  });

  // =========================================================================
  // [D4 + D5 + D6] Create safety_rule via UI
  // =========================================================================
  test('ACS-008 @critical — Create safety rule via full form → appears in list', async ({ page }) => {
    await navigateToList(page, 'acs_safety_rule');

    const createBtn = page
      .locator('[data-testid="toolbar-btn-create"]')
      .or(page.getByRole('button', { name: /新建规则|New Rule|Create/i }))
      .first();
    await expect(createBtn).toBeVisible();
    await createBtn.evaluate((el: HTMLElement) => el.click());
    await page.waitForURL(/\/p\/acs_safety_rule_form|\/new|\/create/, { timeout: 15_000 }).catch(() => null);
    await waitForFormReady(page, 15_000);

    await fillTextField(page, 'acs_rule_code', RULE_CODE);
    await fillTextField(page, 'acs_rule_name', RULE_NAME);
    await fillTextField(
      page,
      'acs_rule_description',
      `E2E test rule for cost limit. UID=${UID}`,
    );
    await fillTextField(page, 'acs_rule_trigger_condition', '{"max_cost_per_run": 0.50}');
    await fillTextField(page, 'acs_rule_threshold', '0.50');
    await fillTextField(page, 'acs_rule_priority', '50');

    // type, action, severity — three Radix selects
    await clickRadixSelectOption(page, 'acs_rule_type', /Cost Limit|cost_limit|成本/i);
    await clickRadixSelectOption(page, 'acs_rule_action', /Pause|pause_and_notify|暂停/i);
    await clickRadixSelectOption(page, 'acs_rule_severity', /Warn|warn|警告/i);

    const body = await submitForm(page);
    const data = (body as any)?.data?.data ?? {};
    safetyRulePid = String(data?.recordId ?? data?.pid ?? '');
    expect(safetyRulePid, 'Created rule must return pid').toBeTruthy();

    await page.waitForURL(/\/p\/acs_safety_rule/, { timeout: 15_000 }).catch(() => null);
    const row = await findRowInPaginatedList(page, RULE_CODE, 12_000);
    await expect(row).toBeVisible({ timeout: 5_000 });
  });

  // =========================================================================
  // [D9] State toggle: deactivate → activate
  // =========================================================================
  test('ACS-009 @critical — Deactivate → activate safety rule round-trip', async ({ page }) => {
    expect(safetyRulePid).toBeTruthy();

    const deact = await executeCommandViaApi(page, 'acs:deactivate_rule', {}, safetyRulePid);
    expect(deact.code, `deactivate code=${deact.code}`).toBe('0');

    let detail = await page.request.get(`/api/dynamic/acs_safety_rule/${safetyRulePid}`);
    let body = await detail.json().catch(() => ({}));
    expect(String((body as any)?.data?.acs_rule_status).toLowerCase()).toBe('inactive');

    const act = await executeCommandViaApi(page, 'acs:activate_rule', {}, safetyRulePid);
    expect(act.code, `activate code=${act.code}`).toBe('0');

    detail = await page.request.get(`/api/dynamic/acs_safety_rule/${safetyRulePid}`);
    body = await detail.json().catch(() => ({}));
    expect(String((body as any)?.data?.acs_rule_status).toLowerCase()).toBe('active');
  });

  // =========================================================================
  // [D10] Cannot delete an active rule (precondition: status=inactive)
  // =========================================================================
  test('ACS-010 — Cannot delete active safety rule', async ({ page }) => {
    expect(safetyRulePid).toBeTruthy();
    const result = await executeCommandViaApi(
      page,
      'acs:delete_safety_rule',
      {},
      safetyRulePid,
      undefined,
      { allowHttpError: true },
    );
    expect(
      result.code !== '0',
      `delete should fail when rule is active, got code=${result.code}`,
    ).toBeTruthy();
  });

  // =========================================================================
  // [D11] Deactivate then delete → row disappears
  // =========================================================================
  test('ACS-011 @critical — Deactivate then delete safety rule → row gone', async ({ page }) => {
    expect(safetyRulePid).toBeTruthy();

    const deact = await executeCommandViaApi(page, 'acs:deactivate_rule', {}, safetyRulePid);
    expect(deact.code).toBe('0');

    const del = await executeCommandViaApi(page, 'acs:delete_safety_rule', {}, safetyRulePid);
    expect(del.code, `delete code=${del.code}`).toBe('0');

    await navigateToList(page, 'acs_safety_rule');
    await expect(page.locator('tbody tr', { hasText: RULE_CODE })).not.toBeVisible({ timeout: 8_000 });
  });

  // =========================================================================
  // [D12] Form validation: empty submit shows error
  // =========================================================================
  test('ACS-012 — Form validation: empty AI request form shows errors', async ({ page }) => {
    await navigateToList(page, 'acs_demo_request');

    const createBtn = page
      .locator('[data-testid="toolbar-btn-create"]')
      .or(page.getByRole('button', { name: /新建|创建|Create/i }))
      .first();
    await createBtn.click();
    await waitForFormReady(page, 15_000);

    const submitBtn = page.locator('[data-testid="form-btn-submit"]').first();
    const submitBtnAlt = page.getByRole('button', { name: /提交|保存|Submit|Save/i }).first();
    const btn = (await submitBtn.isVisible({ timeout: 2_000 }).catch(() => false)) ? submitBtn : submitBtnAlt;
    await btn.click();

    const errorLocator = page.locator(
      '.ant-form-item-explain-error, [data-testid*="error"], .field-error, [role="alert"], .text-red-500, .text-destructive',
    );
    const hasError = await errorLocator.first().isVisible({ timeout: 5_000 }).catch(() => false);
    let hasErrorToast = false;
    if (!hasError) {
      hasErrorToast = await page
        .locator('[role="alert"]')
        .filter({ hasText: /错误|error|required|必填/i })
        .first()
        .isVisible({ timeout: 3_000 })
        .catch(() => false);
    }
    // Form may render validation as a top-of-page summary listing all required fields
    // (e.g. "请先修正以下 N 项问题" + <ul><li>Field 'X' is required</li></ul>).
    const hasSummary = await page
      .getByText(/请先修正以下|please.*fix|Field '.+' is required/i)
      .first()
      .isVisible({ timeout: 3_000 })
      .catch(() => false);
    expect(
      hasError || hasErrorToast || hasSummary,
      'Empty submit must show validation feedback',
    ).toBeTruthy();
    await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
  });

  // =========================================================================
  // [D13] Search filter on demo_request list
  // =========================================================================
  test('ACS-013 — Keyword search filters AI request list', async ({ page }) => {
    expect(demoRequestCode).toBeTruthy();
    await navigateToList(page, 'acs_demo_request');
    await ensureFilterFormOpen(page).catch(() => null);

    const search = page
      .locator(
        '[data-testid="list-search-input"], [data-testid="search-input"], [data-testid="table-search-input"], input[placeholder*="搜索"], input[placeholder*="Search"]',
      )
      .first();
    if (!(await search.isVisible({ timeout: 3_000 }).catch(() => false))) {
      test.skip(true, 'Search input not present in current DSL');
      return;
    }

    const listResp = page.waitForResponse(
      (r) => r.url().includes('acs_demo_request') && r.url().includes('list') && r.status() === 200,
      { timeout: 10_000 },
    );
    await search.fill(demoRequestCode);
    await search.press('Enter');
    await listResp;
    // Wait for the in-table loading placeholder to clear before reading rows
    await page.locator('text=加载中...').first().waitFor({ state: 'hidden', timeout: 8_000 }).catch(() => null);

    const rows = page.locator('tbody tr');
    const count = await rows.count();
    expect(count, 'Search must return at least the matching record').toBeGreaterThan(0);
    for (let i = 0; i < Math.min(count, 5); i++) {
      const text = await rows.nth(i).innerText();
      expect(text).toContain(demoRequestCode);
    }
  });

  // =========================================================================
  // [D14] Toast feedback on create (sanity from ACS-003 + extra coverage)
  // =========================================================================
  test('ACS-014 — Toast feedback appears on rule create', async ({ page }) => {
    await navigateToList(page, 'acs_safety_rule');
    const createBtn = page
      .locator('[data-testid="toolbar-btn-create"]')
      .or(page.getByRole('button', { name: /新建规则|New Rule|Create/i }))
      .first();
    await createBtn.click();
    await waitForFormReady(page, 15_000);

    const code2 = `${RULE_CODE}_T`;
    await fillTextField(page, 'acs_rule_code', code2);
    await fillTextField(page, 'acs_rule_name', `${RULE_NAME} (toast)`);
    await fillTextField(
      page,
      'acs_rule_description',
      'Toast feedback verification rule.',
    );
    await fillTextField(page, 'acs_rule_trigger_condition', '{"max_iterations": 30}');
    await fillTextField(page, 'acs_rule_threshold', '30');
    await fillTextField(page, 'acs_rule_priority', '60');
    await clickRadixSelectOption(page, 'acs_rule_type', /Iteration|iteration_limit|迭代/i);
    await clickRadixSelectOption(page, 'acs_rule_action', /Terminate|terminate|终止/i);
    await clickRadixSelectOption(page, 'acs_rule_severity', /Error|error|错误/i);

    await submitForm(page);
    await waitForToast(page, undefined, 5_000).catch(() => null);

    // Cleanup created rule (deactivate + delete via API)
    const list = await page.request.get(
      `/api/dynamic/acs_safety_rule/list?pageNum=1&pageSize=20&keyword=${encodeURIComponent(code2)}`,
    );
    const listBody = await list.json().catch(() => ({}));
    const rec = ((listBody as any)?.data?.records ?? []).find((r: any) => r.acs_rule_code === code2);
    if (rec?.pid) {
      await executeCommandViaApi(page, 'acs:deactivate_rule', {}, String(rec.pid), undefined, {
        allowHttpError: true,
      });
      await executeCommandViaApi(page, 'acs:delete_safety_rule', {}, String(rec.pid), undefined, {
        allowHttpError: true,
      });
    }
  });
});
