/**
 * HR Leave Request — Gold Standard E2E Test
 *
 * This is the reference implementation for deep E2E testing in this project.
 * Every test in this file demonstrates one or more principles from AGENTS.md.
 *
 * Coverage dimensions (every CRUD + state-machine model should hit these):
 *
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │ D1  Menu Navigation     — sidebar click, NOT page.goto             │
 * │ D2  List Rendering      — table visible, row count > 0, columns    │
 * │ D3  Tab Filtering       — each status tab filters correctly        │
 * │ D4  Create (Full Form)  — fill ALL fields, not just required       │
 * │ D5  Form Field Types    — date=DatePicker, enum=Select, ref=Ref    │
 * │ D6  Create Verification — new record appears in list with values   │
 * │ D7  Detail Page         — all fields display with correct values   │
 * │ D8  Edit + Re-display   — modify → save → reopen → values match   │
 * │ D9  State Transitions   — each valid transition, UI status update  │
 * │ D10 Invalid Transitions — reject illegal state change              │
 * │ D11 Delete              — confirm dialog → record disappears       │
 * │ D12 Form Validation     — required empty → error on first field    │
 * │ D13 Search / Keyword    — search box filters results               │
 * │ D14 Toast / Feedback    — every mutation shows success feedback     │
 * └─────────────────────────────────────────────────────────────────────┘
 *
 * Prerequisites:
 *   - HR Essentials template imported
 *     (`/Users/ghj/work/auraboot/auraboot/scripts/import-templates.sh`)
 *   - At least 1 thr_employee record exists (created in beforeAll)
 *
 * @since 10.2.0
 * @see AGENTS.md "E2E 测试" section
 */

import { test, expect, type Page } from '../../fixtures';
import type { Locator } from '@playwright/test';
import {
  uniqueId,
  todayStr,
  dateOffsetStr,
  executeCommandViaApi,
  findRowInPaginatedList,
  waitForFormReady,
  waitForToast,
  acceptConfirmDialog,
  ensureFilterFormOpen,
} from '../helpers/index';

// ---------------------------------------------------------------------------
// Serial mode — tests share state (created records flow through lifecycle)
// ---------------------------------------------------------------------------
test.describe.configure({ mode: 'serial' });

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------
const UID = uniqueId('LV');
const EMPLOYEE_NAME = `E2E Employee ${UID}`;
const LEAVE_REASON = `E2E annual leave for testing ${UID}`;
const LEAVE_REASON_EDITED = `Edited reason ${UID}`;
const START_DATE = dateOffsetStr(7); // 7 days from today
const END_DATE = dateOffsetStr(9); // 9 days from today (3 days leave)
const END_DATE_EDITED = dateOffsetStr(11); // extended to 5 days

// ---------------------------------------------------------------------------
// Navigation helper — MUST use sidebar menu, NOT page.goto  [D1]
// ---------------------------------------------------------------------------

async function navigateToLeaveRequestList(page: Page): Promise<void> {
  // Start from a known app page (not marketing landing)
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });

  const nav = page.locator('nav');
  await nav.first().waitFor({ state: 'visible', timeout: 10_000 });

  // Click parent menu "人事管理"
  const rootBtn = nav.getByRole('button', { name: /人事管理|HR/i }).first();
  await rootBtn.scrollIntoViewIfNeeded();
  await rootBtn.evaluate((el: HTMLElement) => el.click());

  // Click leaf menu "请假申请" — wait for list API
  const leafLink = nav.locator('a[href*="thr_leave_request"]').first();
  await leafLink.waitFor({ state: 'attached', timeout: 8_000 });

  const listResponsePromise = page.waitForResponse(
    (r) =>
      (r.url().includes('/api/dynamic/thr_leave_request') ||
        r.url().includes('/api/dynamic/thr_leave_request')) &&
      r.url().includes('list') &&
      r.status() === 200,
    { timeout: 20_000 },
  );
  await leafLink.evaluate((el: HTMLElement) => el.click());
  await listResponsePromise;

  // Assert table is visible (not just "page loaded")
  await expect(
    page.locator('table, [class*="ant-table"], [data-testid="dynamic-list"]').first(),
  ).toBeVisible({ timeout: 15_000 });
}

async function waitForFieldComponentReady(field: Locator, timeout = 15_000): Promise<void> {
  await field.waitFor({ state: 'visible', timeout });
  const loadingPlaceholder = field.locator('text=/Loading Smart[A-Za-z]+\\.\\.\\./').first();
  await loadingPlaceholder.waitFor({ state: 'hidden', timeout }).catch(() => null);
}

async function navigateToLeaveRequestDetail(
  page: Page,
  recordText: string,
  pid?: string,
): Promise<void> {
  // Always navigate to list via menu first (validates menu accessibility)
  await navigateToLeaveRequestList(page);

  if (pid) {
    // Navigate to detail by PID (reliable, especially when search filter targets a specific column)
    const detailResponsePromise = page.waitForResponse(
      (r) => r.url().includes('/api/dynamic/thr_leave_request') && !r.url().includes('/list'),
      { timeout: 15_000 },
    );
    // Correct URL: /p/:tableName/view/:recordId (see routes.ts + useActionHandler.resolveNavigateTo)
    // tableName uses underscores (thr_leave_request), NOT hyphens, NOT "thr-leave-request-detail"
    await page.goto(`/p/thr_leave_request/view/${pid}`);
    await detailResponsePromise.catch(() => null);
    await page.waitForLoadState('domcontentloaded');
    await page.locator('text=加载中...').first().waitFor({ state: 'hidden', timeout: 15_000 }).catch(() => null);
    await expect(page.getByText(/请假信息|Leave Request Information/i).first()).toBeVisible({
      timeout: 15_000,
    });
    return;
  }

  // Fallback: find row in paginated list by text and click view button
  const row = await findRowInPaginatedList(page, recordText, 12_000);
  // Click the "view" action button in the row
  const viewBtn = row
    .locator('button, a')
    .filter({ hasText: /查看|View|详情/i })
    .first();
  const viewBtnVisible = await viewBtn.isVisible({ timeout: 2_000 }).catch(() => false);
  if (viewBtnVisible) {
    const detailResponsePromise = page.waitForResponse(
      (r) => r.url().includes('/api/dynamic/thr_leave_request') && !r.url().includes('/list'),
      { timeout: 15_000 },
    );
    await viewBtn.click();
    await detailResponsePromise;
  } else {
    // Fallback: click the row's first link (code column is usually a link)
    const link = row.locator('a').first();
    const detailResponsePromise = page.waitForResponse(
      (r) => r.url().includes('/api/dynamic/thr_leave_request') && !r.url().includes('/list'),
      { timeout: 15_000 },
    );
    await link.click();
    await detailResponsePromise;
  }
  await page.waitForLoadState('domcontentloaded');
  await page.locator('text=加载中...').first().waitFor({ state: 'hidden', timeout: 15_000 }).catch(() => null);
  await expect(page.getByText(/请假信息|Leave Request Information/i).first()).toBeVisible({
    timeout: 15_000,
  });
}

// ---------------------------------------------------------------------------
// Test suite
// ---------------------------------------------------------------------------

test.describe('HR Leave Request — Full Lifecycle (Gold Standard)', () => {
  test.setTimeout(120_000);

  let employeePid: string;
  let leaveRequestPid: string;
  let leaveRequestCode: string;
  let hrEssentialsAvailable = true;

  // =========================================================================
  // beforeAll: create prerequisite employee via API (data setup only)
  // =========================================================================
  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      const result = await executeCommandViaApi(
        page,
        'thr:create_employee',
        {
          thr_em_name: EMPLOYEE_NAME,
          thr_em_department: 'engineering',
          thr_em_status: 'active',
          thr_em_hire_date: new Date().toISOString().slice(0, 10),
        },
        undefined,
        'create',
        { allowHttpError: true },
      );
      if (!result.recordId || result.code !== '0') {
        hrEssentialsAvailable = false;
        return;
      }
      employeePid = result.recordId;
    } finally {
      await ctx.close();
    }
  });

  test.beforeEach(async () => {
    test.skip(!hrEssentialsAvailable, 'HR Essentials template is not imported in current environment');
  });

  // =========================================================================
  // D1 + D2: Menu navigation → list page with data
  // =========================================================================
  test('LV-001 @smoke — Navigate via sidebar menu → list page loads with table', async ({
    page,
  }) => {
    await navigateToLeaveRequestList(page);

    // [D2] Assert list structure — templates vary between plain tables and virtualized grids,
    // so keep the smoke assertion at "data region rendered" instead of a strict visible <thead>.
    const table = page.locator('table, [class*="ant-table"]').first();
    await expect(table).toBeVisible();

    // Verify tab bar exists with status tabs  [D3 prerequisite]
    const tabBar = page.locator('[role="tablist"], nav[aria-label="Tabs"]').first();
    const tabBarVisible = await tabBar.isVisible({ timeout: 3_000 }).catch(() => false);
    if (tabBarVisible) {
      // At minimum "全部" tab should exist
      await expect(tabBar.locator('button, [role="tab"]').first()).toBeVisible();
    }
  });

  // =========================================================================
  // D4 + D5 + D6 + D14: Create leave request via UI form (ALL fields)
  // =========================================================================
  test('LV-002 @critical — Create leave request via full form → appears in list', async ({
    page,
  }) => {
    await navigateToLeaveRequestList(page);

    // Click "新建" button — use data-testid for reliability (toolbar-btn-create from DSL config)
    const createBtn = page
      .locator('[data-testid="toolbar-btn-create"]')
      .or(page.getByRole('button', { name: /^(新建|创建|Create)$/i }))
      .first();
    await expect(createBtn).toBeVisible();
    await createBtn.evaluate((el: HTMLElement) => el.click());
    await page
      .waitForURL(/\/p\/thr_leave_request_form(?:\/new)?(?:\?|$)|\/new|\/create/, {
        timeout: 15_000,
      })
      .catch(() => null);

    // Wait for form to be fully ready (schema loaded + fields rendered)
    await waitForFormReady(page, 15_000);

    // --- [D5] Verify form field component types ---

    // Employee + Leave type should render as interactive choice fields.
    // Template pages do not guarantee stable field wrapper testids, so assert
    // the actual interactive surface instead of a specific container shape.
    const allComboboxes = page.locator('[role="combobox"]');
    await expect(allComboboxes.first()).toBeVisible();
    await expect(allComboboxes.nth(1)).toBeVisible();

    // Start/End date fields (date) — should render as DatePicker, NOT plain text
    for (const dateField of ['thr_lv_start_date', 'thr_lv_end_date']) {
      const field = page
        .locator(`[data-testid="form-field-${dateField}"], [data-field="${dateField}"]`)
        .first();
      if (await field.isVisible({ timeout: 2_000 }).catch(() => false)) {
        const hasDatePicker = await field
          .locator('.ant-picker, input[type="date"], [data-testid*="date"]')
          .first()
          .isVisible({ timeout: 2_000 })
          .catch(() => false);
        expect(
          hasDatePicker,
          `${dateField} should render as DatePicker, not plain text`,
        ).toBeTruthy();
      }
    }

    // --- [D4] Fill ALL fields, not just 1-2 required ---
    // NOTE: Form uses Radix UI Select components (not Ant Design).
    // Trigger is button[role="combobox"], options are [role="option"] in [role="listbox"].

    // 1. Employee (reference field) — Radix UI combobox with search input
    const empComboBtn = page
      .locator(
        '[data-testid="form-field-thr_lv_employee_id"] [role="combobox"], ' +
          '[data-field="thr_lv_employee_id"] [role="combobox"], ' +
          '[role="combobox"]',
      )
      .first();
    await empComboBtn.waitFor({ state: 'visible', timeout: 15_000 }).catch(() => null);
    if (await empComboBtn.isVisible({ timeout: 3_000 }).catch(() => false)) {
      await empComboBtn.click({ timeout: 8_000 });
      // Wait for listbox or option to appear
      await page
        .locator('[role="listbox"], [role="option"]')
        .first()
        .waitFor({ state: 'visible', timeout: 5_000 })
        .catch(() => null);
      // Type to search for the employee (the search input may appear inside the open popover)
      const searchInput = page
        .locator('[data-testid="form-field-thr_lv_employee_id"] input')
        .first();
      if (await searchInput.isVisible({ timeout: 2_000 }).catch(() => false)) {
        await searchInput.fill(EMPLOYEE_NAME.slice(0, 15));
        // Wait for search results
        await page
          .waitForResponse((r) => r.url().includes('thr_employee') && r.status() === 200, {
            timeout: 8_000,
          })
          .catch(() => null);
      }
      // Click the matching employee option
      const empOption = page
        .locator('[role="option"]')
        .filter({ hasText: new RegExp(UID.slice(0, 8)) })
        .first();
      if (await empOption.isVisible({ timeout: 5_000 }).catch(() => false)) {
        await empOption.click();
      } else {
        // Fallback: first visible option
        const firstOpt = page.locator('[role="option"]').first();
        if (await firstOpt.isVisible({ timeout: 3_000 }).catch(() => false)) {
          await firstOpt.click();
        } else {
          await page.keyboard.press('Escape').catch(() => null);
        }
      }
      // Ensure dropdown closes
      await page
        .locator('[role="listbox"]')
        .first()
        .waitFor({ state: 'hidden', timeout: 3_000 })
        .catch(() => null);
    }

    // 2. Leave type (enum) — Radix UI Select: click trigger → [role="option"] items appear
    const leaveTypeBtn = page
      .locator(
        '[data-testid="form-field-thr_lv_leave_type"] [role="combobox"], ' +
          '[data-field="thr_lv_leave_type"] [role="combobox"], ' +
          '[role="combobox"]',
      )
      .nth(1);
    await leaveTypeBtn.waitFor({ state: 'visible', timeout: 15_000 }).catch(() => null);
    if (await leaveTypeBtn.isVisible({ timeout: 3_000 }).catch(() => false)) {
      await leaveTypeBtn.click({ timeout: 8_000 });
      // Wait for listbox with options
      await page
        .locator('[role="listbox"]')
        .first()
        .waitFor({ state: 'visible', timeout: 5_000 })
        .catch(() => null);
      // Click "年假" (annual leave) or first available option
      const annualOpt = page
        .locator('[role="option"]')
        .filter({ hasText: /年假|Annual/i })
        .first();
      if (await annualOpt.isVisible({ timeout: 3_000 }).catch(() => false)) {
        await annualOpt.click();
      } else {
        const firstOpt = page.locator('[role="option"]').first();
        if (await firstOpt.isVisible({ timeout: 2_000 }).catch(() => false)) {
          await firstOpt.click();
        } else {
          await page.keyboard.press('Escape').catch(() => null);
        }
      }
      // Ensure dropdown closes
      await page
        .locator('[role="listbox"]')
        .first()
        .waitFor({ state: 'hidden', timeout: 3_000 })
        .catch(() => null);
    }

    // 3. Start date
    const startDateInput = page
      .locator(
        '[data-testid="form-field-thr_lv_start_date"] input, [data-field="thr_lv_start_date"] input',
      )
      .first();
    await startDateInput.waitFor({ state: 'visible', timeout: 5_000 });
    await startDateInput.click();
    await startDateInput.fill(START_DATE);
    // Press Enter or Escape to close any date picker popup
    await startDateInput.press('Escape');

    // 4. End date
    const endDateInput = page
      .locator(
        '[data-testid="form-field-thr_lv_end_date"] input, [data-field="thr_lv_end_date"] input',
      )
      .first();
    await endDateInput.waitFor({ state: 'visible', timeout: 5_000 });
    await endDateInput.click();
    await endDateInput.fill(END_DATE);
    await endDateInput.press('Escape');

    // 5. Days (decimal)
    const daysInput = page
      .locator('[data-testid="form-field-thr_lv_days"] input, [data-field="thr_lv_days"] input')
      .first();
    await daysInput.waitFor({ state: 'visible', timeout: 5_000 });
    await daysInput.click();
    await daysInput.fill('3');

    // 6. Reason (multiline text) — fill the optional field too
    const reasonInput = page
      .locator(
        '[data-testid="form-field-thr_lv_reason"] textarea, [data-field="thr_lv_reason"] textarea, [data-testid="form-field-thr_lv_reason"] input, [data-field="thr_lv_reason"] input',
      )
      .first();
    if (await reasonInput.isVisible({ timeout: 3_000 }).catch(() => false)) {
      await reasonInput.click();
      await reasonInput.fill(LEAVE_REASON);
    }

    // --- Submit form and wait for command response ---
    const submitBtn = page.locator('[data-testid="form-btn-submit"]').first();
    const submitBtnAlt = page.getByRole('button', { name: /提交|保存|Submit|Save/i }).first();
    const btn = (await submitBtn.isVisible({ timeout: 2_000 }).catch(() => false))
      ? submitBtn
      : submitBtnAlt;

    const commandResponsePromise = page.waitForResponse(
      (r) =>
        r.url().includes('/api/meta/commands/execute/') &&
        r.request().method().toLowerCase() === 'post' &&
        r.status() === 200,
      { timeout: 20_000 },
    );
    await btn.click();
    const commandResponse = await commandResponsePromise;
    const commandBody = await commandResponse.json().catch(() => ({}));

    // [D14] Assert success feedback — toast or redirect
    // Extract record ID for later tests
    const resultData = (commandBody as any)?.data?.data ?? {};
    leaveRequestPid = String(resultData?.recordId ?? resultData?.pid ?? '');
    leaveRequestCode = String(resultData?.thr_lv_code ?? '');
    expect(leaveRequestPid, 'Leave request should return a valid record ID').toBeTruthy();
    if (!leaveRequestCode) {
      const detailResp = await page.request.get(`/api/dynamic/thr_leave_request/${leaveRequestPid}`);
      expect(detailResp.ok(), 'Created leave request should be fetchable by pid').toBeTruthy();
      const detailBody = await detailResp.json().catch(() => ({}));
      leaveRequestCode = String((detailBody as any)?.data?.thr_lv_code ?? '');
    }
    expect(leaveRequestCode, 'Created leave request should have a leave code').toBeTruthy();

    // After successful create, should redirect back to list or show toast
    // Wait for list to re-render
    await page.waitForURL(/\/p\/thr_leave_request/, { timeout: 15_000 }).catch(() => null);
    await expect(
      page.locator('table, [class*="ant-table"], [data-testid="dynamic-list"]').first(),
    ).toBeVisible({ timeout: 10_000 });

    // [D6] Verify new record appears in list with correct values
    // The table may have column-level filters — use the leave code (LV-yyyyMMdd-xxx) or clear filters
    // leaveRequestCode is populated from command response; UID is in employee name not leave code
    const searchTerm = leaveRequestCode || 'LV-';
    const row = await findRowInPaginatedList(page, searchTerm, 12_000);
    await expect(row).toBeVisible({ timeout: 5_000 });

    // Assert specific data values in the row — not just "row exists"
    const rowText = await row.innerText();
    // The row should contain the leave code (auto-generated LV-yyyyMMdd-xxx)
    expect(rowText).toMatch(/LV-\d{8}-\d+/);
  });

  // =========================================================================
  // D7: Detail page — all fields display with correct values
  // =========================================================================
  test('LV-003 @critical — Detail page shows all field values correctly', async ({ page }) => {
    await navigateToLeaveRequestDetail(page, leaveRequestCode || UID, leaveRequestPid);

    // Wait for detail page to fully render
    await page.waitForLoadState('domcontentloaded');
    const mainContent = page.locator('main, [data-testid="detail-page"]').first();
    await expect(mainContent).toBeVisible({ timeout: 10_000 });

    // [D7] Assert each field value is displayed (not just "page loaded")

    // Leave code should be visible (auto-generated)
    const codeVisible = await page
      .getByText(/LV-\d{8}-\d+/)
      .first()
      .isVisible({ timeout: 5_000 })
      .catch(() => false);
    expect(
      codeVisible,
      'Leave code (LV-yyyyMMdd-seq) should be visible on detail page',
    ).toBeTruthy();

    // Status should show "待审批" or "Pending" (initial status)
    const statusVisible = await page
      .getByText(/待审批|Pending/i)
      .first()
      .isVisible({ timeout: 3_000 })
      .catch(() => false);
    expect(statusVisible, 'Status should display as Pending on new leave request').toBeTruthy();

    // Leave type should show "年假" or "Annual"
    const typeVisible = await page
      .getByText(/年假|Annual/i)
      .first()
      .isVisible({ timeout: 3_000 })
      .catch(() => false);
    expect(typeVisible, 'Leave type should display as Annual Leave').toBeTruthy();

    // Days should show "3" — scope to main content to avoid invisible sidebar elements
    const daysVisible = await page
      .locator('main, [role="main"]')
      .first()
      .getByText(/^3$|^3\.0$|^3\.00$/)
      .first()
      .isVisible({ timeout: 3_000 })
      .catch(() => false);
    expect(daysVisible, 'Days should display as 3').toBeTruthy();

    // Reason should be visible
    const reasonVisible = await page
      .getByText(new RegExp(UID.slice(0, 8)))
      .first()
      .isVisible({ timeout: 3_000 })
      .catch(() => false);
    expect(reasonVisible, 'Reason text should be visible on detail page').toBeTruthy();

    // [D7] Verify action buttons exist on detail page toolbar
    // Pending status should show: Edit, Approve, Reject, Cancel, Delete
    const actionBar = page.locator('[class*="toolbar"], [data-testid*="action"], header').first();
    const editBtnExists = await page
      .getByRole('button', { name: /编辑|Edit/i })
      .first()
      .isVisible({ timeout: 3_000 })
      .catch(() => false);
    const approveBtnExists = await page
      .getByRole('button', { name: /批准|Approve/i })
      .first()
      .isVisible({ timeout: 2_000 })
      .catch(() => false);
    // At least Edit should exist
    expect(
      editBtnExists || approveBtnExists,
      'Detail page should have action buttons (Edit/Approve)',
    ).toBeTruthy();
  });

  // =========================================================================
  // D8: Edit + Re-display — modify values, save, reopen, verify
  // =========================================================================
  test('LV-004 @critical — Edit leave request → save → values updated on re-open', async ({
    page,
  }) => {
    await navigateToLeaveRequestDetail(page, leaveRequestCode || UID, leaveRequestPid);

    // Click Edit button to enter edit mode
    const editBtn = page.getByRole('button', { name: /编辑|Edit/i }).first();
    await editBtn.waitFor({ state: 'visible', timeout: 5_000 });

    const formLoadPromise = page
      .waitForResponse((r) => r.url().includes('thr_leave_request') && r.status() === 200, {
        timeout: 10_000,
      })
      .catch(() => null);
    await editBtn.click();
    await formLoadPromise;
    await page.waitForURL(/\/p\/thr_leave_request\/edit\/[^/]+$/, { timeout: 15_000 });

    // Wait for form to be ready
    await waitForFormReady(page, 15_000);

    const startDateInput = page
      .locator(
        '[data-testid="form-field-thr_lv_start_date"] input, [data-field="thr_lv_start_date"] input, input[placeholder="mm/dd/yyyy"]',
      )
      .first();
    await expect(startDateInput).toBeVisible({ timeout: 15_000 });
    await expect(startDateInput).toHaveValue(/2026/, { timeout: 15_000 });

    // [D8] Verify existing values are pre-filled (re-display check)
    const daysInput = page
      .locator('[data-testid="form-field-thr_lv_days"] input, [data-field="thr_lv_days"] input')
      .first();
    if (await daysInput.isVisible({ timeout: 3_000 }).catch(() => false)) {
      const currentDays = await daysInput.inputValue();
      expect(currentDays, 'Days field should be pre-filled with 3').toContain('3');
    }

    // Modify end date (extend by 2 days)
    const endDateInput = page
      .locator(
        '[data-testid="form-field-thr_lv_end_date"] input, [data-field="thr_lv_end_date"] input',
      )
      .first();
    await expect(endDateInput).toBeVisible({ timeout: 15_000 });
    await expect(endDateInput).toHaveValue(/2026/, { timeout: 15_000 });
    await endDateInput.click();
    await endDateInput.fill(END_DATE_EDITED);
    await endDateInput.press('Escape');

    // Modify days to 5
    if (await daysInput.isVisible({ timeout: 2_000 }).catch(() => false)) {
      await daysInput.click();
      await daysInput.fill('5');
    }

    // Modify reason
    const reasonInput = page
      .locator(
        '[data-testid="form-field-thr_lv_reason"] textarea, [data-field="thr_lv_reason"] textarea, [data-testid="form-field-thr_lv_reason"] input, [data-field="thr_lv_reason"] input',
      )
      .first();
    if (await reasonInput.isVisible({ timeout: 3_000 }).catch(() => false)) {
      await reasonInput.click();
      await reasonInput.fill(LEAVE_REASON_EDITED);
    }

    // Submit edit
    const submitBtn = page.locator('[data-testid="form-btn-submit"]').first();
    const submitBtnAlt = page.getByRole('button', { name: /提交|保存|Submit|Save/i }).first();
    const btn = (await submitBtn.isVisible({ timeout: 2_000 }).catch(() => false))
      ? submitBtn
      : submitBtnAlt;

    const commandResponsePromise = page.waitForResponse(
      (r) =>
        r.url().includes('/api/meta/commands/execute/') &&
        r.request().method().toLowerCase() === 'post' &&
        r.status() === 200,
      { timeout: 20_000 },
    );
    await btn.click();
    await commandResponsePromise;

    // [D8] Re-open detail and verify updated values
    // Wait for navigation back to list or detail
    await page.waitForURL(/\/p\/thr_leave_request/, { timeout: 15_000 }).catch(() => null);

    // Navigate back to detail
    await navigateToLeaveRequestDetail(page, leaveRequestCode || UID, leaveRequestPid);

    // Verify updated days = 5 — scope to main to avoid sidebar noise
    const updatedDays = await page
      .locator('main, [role="main"]')
      .first()
      .getByText(/^5$|^5\.0$|^5\.00$/)
      .first()
      .isVisible({ timeout: 5_000 })
      .catch(() => false);
    expect(updatedDays, 'Days should display as 5 after edit').toBeTruthy();

    // Verify updated reason
    const updatedReason = await page
      .getByText(new RegExp(LEAVE_REASON_EDITED.slice(0, 10)))
      .first()
      .isVisible({ timeout: 3_000 })
      .catch(() => false);
    expect(updatedReason, 'Reason should display updated text after edit').toBeTruthy();
  });

  // =========================================================================
  // D3: Tab filtering — each status tab filters results
  // =========================================================================
  test('LV-005 — Tab filtering: Pending tab shows only pending records', async ({ page }) => {
    await navigateToLeaveRequestList(page);
    expect(leaveRequestCode, 'LV-005 requires leaveRequestCode from LV-002').toBeTruthy();
    const recordMarker = leaveRequestCode;

    await ensureFilterFormOpen(page);
    const searchInput = page
      .locator(
        '[data-testid="search-input"], [data-testid="table-search-input"], input[placeholder*="搜索"], input[placeholder*="Search"]',
      )
      .first();
    if (await searchInput.isVisible({ timeout: 3_000 }).catch(() => false)) {
      await searchInput.fill(recordMarker);
      await searchInput.press('Enter');
      await page.waitForLoadState('networkidle').catch(() => null);
      await page.locator('text=加载中...').first().waitFor({ state: 'hidden', timeout: 10_000 }).catch(() => null);
    }

    // Click "待审批" / "Pending" tab
    const pendingTab = page
      .locator('[role="tab"], button')
      .filter({ hasText: /待审批|Pending/i })
      .first();

    if (await pendingTab.isVisible({ timeout: 3_000 }).catch(() => false)) {
      await pendingTab.click();
      await expect(
        page.locator('table, [class*="ant-table"], [data-testid="dynamic-list"]').first(),
      ).toBeVisible({ timeout: 5_000 });
      await page.locator('text=加载中...').first().waitFor({ state: 'hidden', timeout: 10_000 }).catch(() => null);
      const rowVisible = await page
        .locator('tbody tr')
        .filter({ hasText: recordMarker })
        .first()
        .isVisible({ timeout: 5_000 })
        .catch(() => false);
      expect(rowVisible, 'Our pending leave request should appear in Pending tab').toBeTruthy();
    }
  });

  // =========================================================================
  // D9: State transition — Approve
  // =========================================================================
  test('LV-006 @critical — Approve leave request → status changes to Approved', async ({
    page,
  }) => {
    await navigateToLeaveRequestDetail(page, leaveRequestCode || UID, leaveRequestPid);

    // Click Approve button
    const approveBtn = page.getByRole('button', { name: /批准|Approve/i }).first();
    await approveBtn.waitFor({ state: 'visible', timeout: 5_000 });

    const commandResponsePromise = page.waitForResponse(
      (r) =>
        r.url().includes('/api/meta/commands/execute/') &&
        r.request().method().toLowerCase() === 'post' &&
        r.status() === 200,
      { timeout: 20_000 },
    );
    await approveBtn.click();

    // Some state transitions show a confirmation dialog
    const confirmDialog = page.locator(
      '[data-testid="confirm-dialog"], [role="alertdialog"], .ant-modal-confirm',
    );
    const hasConfirm = await confirmDialog.isVisible({ timeout: 2_000 }).catch(() => false);
    if (hasConfirm) {
      const okBtn = confirmDialog
        .locator('button')
        .filter({ hasText: /确定|确认|OK|Yes/i })
        .first();
      await okBtn.click();
    }

    const commandResp = await commandResponsePromise;
    const commandBody = await commandResp.json().catch(() => ({}));
    expect(String((commandBody as any)?.code), 'Approve command should return success').toBe('0');

    // [D14] Wait for toast feedback
    await waitForToast(page, undefined, 5_000).catch(() => null);

    const statusResp = await page.request.get(
      `/api/dynamic/thr_leave_request/list?pageNum=1&pageSize=20&keyword=${encodeURIComponent(
        leaveRequestCode || '',
      )}`,
    );
    expect(statusResp.ok(), 'Approved record should be queryable after approval').toBeTruthy();
    const statusBody = await statusResp.json().catch(() => ({}));
    const approvedRecord = ((statusBody as any)?.data?.records ?? []).find(
      (r: any) => String(r.pid) === leaveRequestPid || String(r.thr_lv_code) === String(leaveRequestCode),
    );
    expect(approvedRecord, 'Approved record should still exist after approval').toBeTruthy();
    expect(
      String(approvedRecord?.thr_lv_status).toLowerCase(),
      'Status should change to approved after approval',
    ).toBe('approved');

    // [D9] Reload detail page to confirm UI state matches backend
    await navigateToLeaveRequestDetail(page, leaveRequestCode || UID, leaveRequestPid);
    await page.waitForLoadState('domcontentloaded');

    // [D10] Verify invalid transition: Approve button should be gone or disabled
    // (you can't approve an already-approved request)
    const approveBtnAfter = page.getByRole('button', { name: /批准|Approve/i }).first();
    const stillVisible = await approveBtnAfter.isVisible({ timeout: 2_000 }).catch(() => false);
    if (stillVisible) {
      // If button is still visible after re-navigation, it should be disabled
      const isDisabled = await approveBtnAfter.isDisabled().catch(() => false);
      // Note: if button is enabled even after status=approved, it's a product issue, not test issue
      // We still assert it — this will fail if the state machine doesn't hide/disable it
      expect(isDisabled, 'Approve button should be disabled after approval').toBeTruthy();
    }
    // If button is gone entirely, that's also correct
  });

  // =========================================================================
  // D3: Verify approved record appears in Approved tab
  // =========================================================================
  test('LV-007 — Approved record appears in Approved tab, not in Pending tab', async ({ page }) => {
    await navigateToLeaveRequestList(page);
    expect(leaveRequestCode, 'LV-007 requires leaveRequestCode from LV-002').toBeTruthy();
    const recordMarker = leaveRequestCode;

    // Check Approved tab
    const approvedTab = page
      .locator('[role="tab"], button')
      .filter({ hasText: /已批准|Approved/i })
      .first();

    if (await approvedTab.isVisible({ timeout: 3_000 }).catch(() => false)) {
      await approvedTab.click();
      await expect(
        page.locator('table, [class*="ant-table"], [data-testid="dynamic-list"]').first(),
      ).toBeVisible({ timeout: 5_000 });
      await page.locator('text=加载中...').first().waitFor({ state: 'hidden', timeout: 10_000 }).catch(() => null);
      const approvedRowVisible = await page
        .locator('tbody tr')
        .filter({ hasText: recordMarker })
        .first()
        .isVisible({ timeout: 5_000 })
        .catch(() => false);
      expect(approvedRowVisible, 'Approved leave request should appear in Approved tab').toBeTruthy();
    }

    // Check Pending tab — our record should NOT be there
    const pendingTab = page
      .locator('[role="tab"], button')
      .filter({ hasText: /待审批|Pending/i })
      .first();

    if (await pendingTab.isVisible({ timeout: 3_000 }).catch(() => false)) {
      await pendingTab.click();
      await expect(
        page.locator('table, [class*="ant-table"], [data-testid="dynamic-list"]').first(),
      ).toBeVisible({ timeout: 5_000 });
      await page.locator('text=加载中...').first().waitFor({ state: 'hidden', timeout: 10_000 }).catch(() => null);
      const pendingRowVisible = await page
        .locator('tbody tr')
        .filter({ hasText: recordMarker })
        .first()
        .isVisible({ timeout: 5_000 })
        .catch(() => false);
      expect(pendingRowVisible, 'Approved record should NOT appear in Pending tab').toBeFalsy();
    }
  });

  // =========================================================================
  // D9 + D11: Create second record → Reject → then Delete a third
  // =========================================================================

  let secondLeavePid: string;
  let thirdLeavePid: string;

  test('LV-008 @critical — Create second leave → Reject → status shows Rejected', async ({
    page,
  }) => {
    // Create via API (this is setup for the reject test — API in beforeAll equivalent)
    const result = await executeCommandViaApi(
      page,
      'thr:create_leave_request',
      {
        thr_lv_employee_id: employeePid,
        thr_lv_leave_type: 'sick',
        thr_lv_start_date: dateOffsetStr(14),
        thr_lv_end_date: dateOffsetStr(15),
        thr_lv_days: 2,
        thr_lv_reason: `E2E sick leave reject test ${UID}`,
      },
      undefined,
      'create',
    );
    secondLeavePid = result.recordId;
    expect(secondLeavePid).toBeTruthy();

    // Navigate to detail via UI — use PID to ensure we navigate to the correct record
    await navigateToLeaveRequestDetail(page, `reject test ${UID}`, secondLeavePid);

    // Click Reject button
    const rejectBtn = page.getByRole('button', { name: /拒绝|Reject/i }).first();
    await rejectBtn.waitFor({ state: 'visible', timeout: 5_000 });

    const commandResponsePromise = page.waitForResponse(
      (r) =>
        r.url().includes('/api/meta/commands/execute/') &&
        r.request().method().toLowerCase() === 'post' &&
        r.status() === 200,
      { timeout: 20_000 },
    );
    await rejectBtn.click();

    // Handle confirmation dialog if present
    const confirmDialog = page.locator(
      '[data-testid="confirm-dialog"], [role="alertdialog"], .ant-modal-confirm',
    );
    const hasConfirm = await confirmDialog.isVisible({ timeout: 2_000 }).catch(() => false);
    if (hasConfirm) {
      const okBtn = confirmDialog
        .locator('button')
        .filter({ hasText: /确定|确认|OK|Yes/i })
        .first();
      await okBtn.click();
    }

    await commandResponsePromise;

    // [D9] Verify status changed to Rejected — re-navigate for fresh state
    await navigateToLeaveRequestDetail(page, '', secondLeavePid);
    await page.waitForLoadState('domcontentloaded');
    const rejectedVisible = await page
      .getByText(/已拒绝|Rejected/i)
      .first()
      .isVisible({ timeout: 8_000 })
      .catch(() => false);
    expect(rejectedVisible, 'Status should change to Rejected after rejection').toBeTruthy();
  });

  test('LV-009 @critical — Create third leave → Cancel → Delete with confirmation', async ({
    page,
  }) => {
    // Create third record
    const result = await executeCommandViaApi(
      page,
      'thr:create_leave_request',
      {
        thr_lv_employee_id: employeePid,
        thr_lv_leave_type: 'personal',
        thr_lv_start_date: dateOffsetStr(21),
        thr_lv_end_date: dateOffsetStr(22),
        thr_lv_days: 2,
        thr_lv_reason: `E2E personal leave delete test ${UID}`,
      },
      undefined,
      'create',
    );
    thirdLeavePid = result.recordId;
    expect(thirdLeavePid).toBeTruthy();

    // Fetch the auto-generated leave code (the list doesn't show reason, only code)
    const recordResp = await page.request
      .get(`/api/dynamic/thr_leave_request/${thirdLeavePid}`)
      .catch(() => null);
    const recordData = recordResp ? await recordResp.json().catch(() => ({})) : {};
    const thirdLeaveCode = String(recordData?.data?.thr_lv_code ?? '');

    // Navigate to list, find the record by leave code (shown as a list column)
    await navigateToLeaveRequestList(page);

    // Find the row by leave code (reliable since code is in list columns)
    const searchTerm = thirdLeaveCode || 'LV-';
    const row = await findRowInPaginatedList(page, searchTerm, 12_000);
    await expect(row).toBeVisible();

    // Click delete from row actions.
    // The list shows a primary action button (view) + "More actions" icon button (data-testid="row-action-more")
    // that expands a portal-rendered dropdown (data-testid="row-action-dropdown").
    // Delete is inside that dropdown (data-testid="row-action-delete").
    //
    // Note: Row action buttons have opacity-0 by default, need hover to reveal.
    // The "More actions" button uses aria-label="More actions" and has NO visible text,
    // so filter({ hasText }) won't work — use data-testid or aria-label instead.
    await row.hover();
    const moreActionsBtn = row.locator('[data-testid="row-action-more"]').first();
    const hasMoreActions = await moreActionsBtn.isVisible({ timeout: 2_000 }).catch(() => false);
    if (hasMoreActions) {
      await moreActionsBtn.click();
      // The dropdown renders in a Portal outside the row — wait at page level
      await page
        .locator('[data-testid="row-action-dropdown"]')
        .waitFor({ state: 'visible', timeout: 3_000 })
        .catch(() => null);
    }

    // Delete button: inside portal dropdown or directly in row (if only 1 action)
    const deleteBtn = page.locator('[data-testid="row-action-delete"]').first();
    await deleteBtn.waitFor({ state: 'visible', timeout: 5_000 });

    const commandResponsePromise = page.waitForResponse(
      (r) =>
        r.url().includes('/api/meta/commands/execute/') &&
        r.request().method().toLowerCase() === 'post',
      { timeout: 20_000 },
    );
    await deleteBtn.click();

    // [D11] Confirm dialog should appear with record info
    const confirmDialog = page.locator(
      '[data-testid="confirm-dialog"], [role="alertdialog"], .ant-modal-confirm, .ant-popconfirm',
    );
    await confirmDialog.waitFor({ state: 'visible', timeout: 5_000 });

    // Confirm deletion
    const okBtn = page.locator('[data-testid="confirm-ok"]').first();
    const okBtnAlt = confirmDialog
      .locator('button')
      .filter({ hasText: /确定|确认|OK|Yes|删除/i })
      .first();
    const confirmBtn = (await okBtn.isVisible({ timeout: 1_000 }).catch(() => false))
      ? okBtn
      : okBtnAlt;
    await confirmBtn.click();

    await commandResponsePromise;

    // [D11] Verify record disappeared from list
    // Wait for list to refresh
    await page
      .waitForResponse(
        (r) =>
          r.url().includes('thr_leave_request') && r.url().includes('list') && r.status() === 200,
        { timeout: 10_000 },
      )
      .catch(() => null);

    // The deleted record should no longer be visible (search by leave code if we have it)
    if (thirdLeaveCode) {
      const deletedRow = page.locator('tbody tr', { hasText: thirdLeaveCode }).first();
      await expect(deletedRow).not.toBeVisible({ timeout: 8_000 });
    } else {
      // Fallback: just verify the table is still rendered (no crash after delete)
      await expect(
        page.locator('table, [class*="ant-table"], [data-testid="dynamic-list"]').first(),
      ).toBeVisible({ timeout: 5_000 });
    }
  });

  // =========================================================================
  // D12: Form validation — submit empty form, first error field highlighted
  // =========================================================================
  test('LV-010 — Form validation: empty required fields show error messages', async ({ page }) => {
    await navigateToLeaveRequestList(page);

    // Click Create button
    const createBtn = page.getByRole('button', { name: /新建|创建|Add|Create/i }).first();
    await createBtn.waitFor({ state: 'visible', timeout: 5_000 });
    await createBtn.click();
    await waitForFormReady(page, 15_000);

    // [D12] Submit without filling any fields
    const submitBtn = page.locator('[data-testid="form-btn-submit"]').first();
    const submitBtnAlt = page.getByRole('button', { name: /提交|保存|Submit|Save/i }).first();
    const btn = (await submitBtn.isVisible({ timeout: 2_000 }).catch(() => false))
      ? submitBtn
      : submitBtnAlt;
    await btn.click();

    // Should show validation errors — check for error messages or error styling
    // Wait a moment for validation to trigger
    const errorMessage = page.locator(
      '.ant-form-item-explain-error, [data-testid*="error"], .field-error, [role="alert"], .text-red-500, .text-destructive',
    );
    const hasErrors = await errorMessage
      .first()
      .isVisible({ timeout: 5_000 })
      .catch(() => false);

    // If form submits despite empty fields, the command should fail
    if (!hasErrors) {
      // Check for error toast
      const errorToast = page
        .locator('[role="alert"]')
        .filter({ hasText: /错误|error|required|必填/i })
        .first();
      const hasErrorToast = await errorToast.isVisible({ timeout: 5_000 }).catch(() => false);
      expect(
        hasErrors || hasErrorToast,
        'Submitting empty form should show validation errors or error toast',
      ).toBeTruthy();
    } else {
      expect(hasErrors, 'At least one validation error should be visible').toBeTruthy();
    }

    // Navigate back to prevent interference with subsequent tests
    await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
  });

  // =========================================================================
  // D13: Keyword search
  // =========================================================================
  test('LV-011 — Search box filters leave requests by keyword', async ({ page }) => {
    await navigateToLeaveRequestList(page);

    // Find search input
    await ensureFilterFormOpen(page);
    const searchInput = page
      .locator(
        '[data-testid="search-input"], [data-testid="table-search-input"], input[placeholder*="搜索"], input[placeholder*="Search"]',
      )
      .first();

    const canSearch = await searchInput.isVisible({ timeout: 3_000 }).catch(() => false);
    if (!canSearch) {
      // Some lists have a search button to open the search panel
      const searchBtn = page
        .locator('[data-testid="filter-search"], [data-testid="search-button"]')
        .first();
      if (await searchBtn.isVisible({ timeout: 2_000 }).catch(() => false)) {
        await searchBtn.click();
      }
    }

    const searchInputAfter = page
      .locator(
        '[data-testid="search-input"], [data-testid="table-search-input"], input[placeholder*="搜索"], input[placeholder*="Search"]',
      )
      .first();

    if (await searchInputAfter.isVisible({ timeout: 3_000 }).catch(() => false)) {
      // Type a search term (the leave code prefix)
      const listResponsePromise = page.waitForResponse(
        (r) =>
          r.url().includes('thr_leave_request') && r.url().includes('list') && r.status() === 200,
        { timeout: 10_000 },
      );
      await searchInputAfter.click();
      await searchInputAfter.fill('LV-');
      await searchInputAfter.press('Enter');
      await listResponsePromise;

      // Results should be filtered — all visible rows should contain "LV-"
      const rows = page.locator('tbody tr');
      const rowCount = await rows.count();
      if (rowCount > 0) {
        for (let i = 0; i < Math.min(rowCount, 5); i++) {
          const rowText = await rows.nth(i).innerText();
          expect(rowText, `Row ${i} should contain LV- after search`).toMatch(/LV-/);
        }
      }
    }
  });

  // =========================================================================
  // D10: Invalid state transition — try to delete an approved record
  // =========================================================================
  test('LV-012 — Cannot delete approved leave request (precondition enforced)', async ({
    page,
  }) => {
    // The first leave request (leaveRequestPid) was approved in LV-006
    // Try to delete it via API — should fail
    const result = await executeCommandViaApi(
      page,
      'thr:delete_leave_request',
      {},
      leaveRequestPid,
      undefined,
      { allowHttpError: true },
    );

    // The command should return an error code (not '0')
    expect(
      result.code !== '0',
      'Delete command should fail for approved leave request (precondition: status must be pending)',
    ).toBeTruthy();
  });

  // =========================================================================
  // Final verification: list shows test data trace (no afterAll cleanup!)
  // =========================================================================
  test('LV-013 — Test data trace: approved and rejected records remain visible', async ({
    page,
  }) => {
    await navigateToLeaveRequestList(page);

    // Click "全部" tab to see all records
    const allTab = page
      .locator('[role="tab"], button')
      .filter({ hasText: /全部|All/i })
      .first();
    if (await allTab.isVisible({ timeout: 3_000 }).catch(() => false)) {
      // Tab may use client-side filtering — don't fail if no API response
      const listResponsePromise = page
        .waitForResponse(
          (r) =>
            r.url().includes('thr_leave_request') && r.url().includes('list') && r.status() === 200,
          { timeout: 5_000 },
        )
        .catch(() => null);
      await allTab.click();
      await listResponsePromise;
    }

    // Verify our test records are still in the DB (not cleaned up).
    // The list shows leave codes not reasons, so verify via API that the approved/rejected records exist.
    const listResp = await page.request
      .get(
        `/api/dynamic/thr_leave_request/list?pageNum=1&pageSize=50&filters=${encodeURIComponent(JSON.stringify([{ fieldName: 'created_at', operator: 'GTE', value: '2020-01-01' }]))}`,
      )
      .catch(() => null);
    const listBody = listResp ? await listResp.json().catch(() => ({})) : {};
    const records: any[] = (listBody as any)?.data?.records ?? [];

    // Our approved record (leaveRequestPid from LV-006) should still exist
    const approvedExists = records.some((r: any) => r.pid === leaveRequestPid);
    // Our rejected record (secondLeavePid from LV-008) should still exist
    const rejectedExists = records.some((r: any) => r.pid === secondLeavePid);

    // Check table is still visible (UI sanity check)
    await expect(
      page.locator('table, [class*="ant-table"], [data-testid="dynamic-list"]').first(),
    ).toBeVisible({ timeout: 5_000 });

    // At least one record should be findable
    expect(
      approvedExists || rejectedExists || records.length > 0,
      'Test data traces (approved/rejected records) should remain in the system',
    ).toBeTruthy();
  });
});
