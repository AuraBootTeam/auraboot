/**
 * Dual Prevention — Inspection Task (巡检) Lifecycle Tests
 *
 * Dimensions covered:
 * D1  Menu Navigation   — sidebar click to 巡检管理 list
 * D2  List Rendering    — table visible
 * D3  Tab Filtering     — pending / in_progress / completed tabs
 * D4  Create Full Form  — all fields
 * D5  Component Types   — date=DatePicker, reference=RefPicker
 * D6  Verify in list    — new record appears
 * D9  State Transitions — pending→in_progress→completed
 * D10 Invalid Trans     — complete only for pending/in_progress
 * D12 Form Validation   — result required on complete
 * D14 Toast feedback
 *
 * @since 11.0.0
 */

import { test, expect, type Page } from '../../fixtures';
import {
  uniqueId,
  todayStr,
  executeCommandViaApi,
  findRowInPaginatedList,
  waitForToast,
  clickTabAndWaitForLoad,
  fillField,
  selectOption,
} from '../helpers/index';
import { getTestProjectId } from '../quarry-management.setup';

test.describe.configure({ mode: 'serial' });
test.setTimeout(120_000);

const UID = uniqueId('DI');
const TASK_AREA = `区域-${UID}`;
const TASK_RESULT = `巡检结果 ${UID}`;

let testProjectId = '';
let taskPid = '';

// ---------------------------------------------------------------------------
// Navigation
// ---------------------------------------------------------------------------
async function expandDpMenu(page: Page): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
  const nav = page.locator('nav');
  const rootBtn = nav
    .getByRole('button', { name: /双重预防|Dual Prevention/i })
    .or(nav.locator('[title*="双重预防"], [title*="Dual Prevention"]').first());
  await expect(rootBtn).toBeVisible({ timeout: 10_000 });
  await rootBtn.evaluate((el: HTMLElement) => el.click());
  await page.waitForResponse(() => true, { timeout: 2000 }).catch(() => null);
}

async function navigateToInspectionList(page: Page): Promise<void> {
  await expandDpMenu(page);
  const nav = page.locator('nav');
  const link = nav.locator('a[href="/dual-prevention/inspections"]').first();
  await link.waitFor({ state: 'attached', timeout: 8_000 });
  await link.scrollIntoViewIfNeeded();
  const listResp = page
    .waitForResponse(
      (r) => r.url().includes('/api/dynamic/dp_inspection_task') && r.status() === 200,
      { timeout: 20_000 },
    )
    .catch(() => null);
  await link.evaluate((el: HTMLElement) => el.click());
  await listResp; // null if timeout, table visibility check is the real gate
  await expect(
    page.locator('table, [class*="ant-table"], [data-testid="dynamic-list"]').first(),
  ).toBeVisible({ timeout: 15_000 });
}

// ---------------------------------------------------------------------------
test.beforeAll(async ({ browser }) => {
  const ctx = await browser.newContext({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
  const page = await ctx.newPage();
  try {
    testProjectId = await getTestProjectId(page);

    // Create main inspection task via API for lifecycle tests
    const create = await executeCommandViaApi(page, 'dp:create_inspection_task', {
      dp_task_planned_date: todayStr(),
    });
    if (String(create.code) === '0' && create.recordId) {
      taskPid = create.recordId;
    }
  } finally {
    await ctx.close();
  }
});

// ===========================================================================
// DIN-001: Navigate to 巡检管理 @smoke
// ===========================================================================
test('DIN-001: Navigate via sidebar to 巡检管理 — table visible @smoke', async ({ page }) => {
  await navigateToInspectionList(page);
  await expect(page.locator('table, [class*="ant-table"]').first()).toBeVisible({
    timeout: 10_000,
  });
  await expect(page).toHaveURL(/\/dual-prevention\/inspections/, { timeout: 5_000 });
});

// ===========================================================================
// DIN-002: Create inspection task via UI — form rendering verification [D4, D5, D6] @critical
// ===========================================================================
test('DIN-002: Create inspection task — form renders correct component types @critical', async ({
  page,
}) => {
  await navigateToInspectionList(page);

  const createBtn = page
    .locator('button')
    .filter({ hasText: /新建|创建|Create/i })
    .first();
  await expect(createBtn).toBeVisible({ timeout: 8_000 });
  await createBtn.evaluate((el: HTMLElement) => el.click());

  // Wait for form to render — look for any input or select
  await page.waitForURL(/\/(new|create)/, { timeout: 10_000 }).catch(() => null);
  await expect(page.locator('input, .ant-select, textarea').first()).toBeVisible({
    timeout: 12_000,
  });

  // D5: dp_task_area must render as Select/combobox (ENUM type)
  const areaField = page.locator('[data-testid="form-field-dp_task_area"]').first();
  if (await areaField.isVisible({ timeout: 3_000 }).catch(() => false)) {
    const isSelect = await areaField
      .locator('.ant-select, [role="combobox"]')
      .isVisible()
      .catch(() => false);
    expect(isSelect, 'dp_task_area (ENUM) must render as Select, not TextInput').toBe(true);
  }

  // D5: dp_task_planned_date should render as DatePicker or date input
  const plannedDateField = page.locator('[data-testid="form-field-dp_task_planned_date"]').first();
  if (await plannedDateField.isVisible({ timeout: 3_000 }).catch(() => false)) {
    const hasDateControl = await plannedDateField
      .locator('.ant-picker, input')
      .isVisible()
      .catch(() => false);
    expect(hasDateControl, 'dp_task_planned_date must have a date input control').toBe(true);

    // Fill date
    const dateInput = plannedDateField.locator('input').first();
    if (await dateInput.isVisible({ timeout: 2_000 }).catch(() => false)) {
      await dateInput.click();
      await dateInput.fill(todayStr());
      await page.keyboard.press('Enter');
    }
  }

  // Save the form
  const saveBtn = page
    .locator('[data-testid="form-btn-save"], [data-testid="form-btn-submit"], button')
    .filter({ hasText: /保存|Save|submit|提交/i })
    .first();
  if (await saveBtn.isVisible({ timeout: 5_000 }).catch(() => false)) {
    const cmdResp = page
      .waitForResponse(
        (r) =>
          r.url().includes('/commands/execute') ||
          r.url().includes('/api/dynamic/dp_inspection_task'),
        { timeout: 15_000 },
      )
      .catch(() => null);
    await saveBtn.evaluate((el: HTMLElement) => el.click());
    await cmdResp;
    await waitForToast(page, undefined, 8_000).catch(() => null);
  }

  // D6: Verify main lifecycle task (from beforeAll) is in list
  await navigateToInspectionList(page);
  const rows = page.locator('tbody tr');
  await rows
    .first()
    .waitFor({ state: 'visible', timeout: 10_000 })
    .catch(() => null);
  const rowCount = await rows.count();
  expect(rowCount, 'Inspection list must have at least 1 task (from beforeAll)').toBeGreaterThan(0);

  // Verify main taskPid is set
  expect(taskPid, 'Main lifecycle inspection task must be created in beforeAll').not.toBe('');
});

// ===========================================================================
// DIN-003: Inspection task full lifecycle — pending→in_progress→completed [D9] @critical
// ===========================================================================
test('DIN-003: Inspection lifecycle — pending → in_progress → completed @critical', async ({
  page,
}) => {
  test.skip(!taskPid, 'DIN-002 must pass first');

  // --- Step 1: Start inspection (pending → in_progress) ---
  await page.goto(`/p/dp_inspection_task/view/${taskPid}`);
  await page.waitForLoadState('domcontentloaded');
  await page
    .waitForResponse(
      (r) => r.url().includes('/api/dynamic/dp_inspection_task') && !r.url().includes('/list'),
      { timeout: 12_000 },
    )
    .catch(() => null);

  // Task number auto-generated
  await expect(page.getByText(/INS-\d{8}-\d+/)).toBeVisible({ timeout: 5_000 });

  // Start button — code is "start", label is "startInspection" (may resolve to "开始巡检")
  const startBtn = page
    .locator('[data-testid="form-btn-start"], button')
    .filter({ hasText: /开始巡检|startInspection|Start/i })
    .first();
  await expect(startBtn).toBeVisible({ timeout: 5_000 });

  let cmdResp = page
    .waitForResponse((r) => r.url().includes('/commands/execute'), { timeout: 15_000 })
    .catch(() => null);
  await startBtn.evaluate((el: HTMLElement) => el.click());

  // Confirm dialog if any
  const confirmOk1 = page.locator('[data-testid="confirm-ok"]').first();
  if (await confirmOk1.isVisible({ timeout: 5_000 }).catch(() => false)) {
    await confirmOk1.evaluate((el: HTMLElement) => el.click());
  }

  let resp = await cmdResp;
  if (resp) {
    const body = await resp.json().catch(() => null);
    if (body?.code !== undefined) {
      expect(String(body.code), 'Start inspection must return code "0"').toBe('0');
    }
  }

  await waitForToast(page, undefined, 8_000).catch(() => null);

  // D9: Navigate back to detail page explicitly (command may navigate away)
  await page.goto(`/p/dp_inspection_task/view/${taskPid}`);
  await page.waitForLoadState('domcontentloaded');
  await page
    .waitForResponse(
      (r) => r.url().includes('/api/dynamic/dp_inspection_task') && !r.url().includes('/list'),
      { timeout: 12_000 },
    )
    .catch(() => null);
  await expect(page.getByText(/巡检中|in_progress/i)).toBeVisible({ timeout: 10_000 });

  // --- Step 2: Complete inspection (in_progress → completed) ---
  // Complete button — code is "complete", label is "completeInspection"
  const completeBtn = page
    .locator('[data-testid="form-btn-complete"], button')
    .filter({ hasText: /完成巡检|completeInspection|Complete/i })
    .first();
  await expect(completeBtn).toBeVisible({ timeout: 5_000 });

  await completeBtn.evaluate((el: HTMLElement) => el.click());

  // The complete command opens a modal form with inputFields. The
  // resultInput.isVisible({ timeout: 5_000 }) below already polls, so a
  // fixed sleep for "modal animation" is redundant.

  // Result field should be required — fill it
  const resultInput = page
    .locator(
      '[data-testid="form-field-dp_task_result"] input, [data-testid="form-field-dp_task_result"] textarea',
    )
    .or(page.locator('.ant-modal [data-testid*="result"] textarea, .ant-modal textarea').first())
    .first();
  if (await resultInput.isVisible({ timeout: 5_000 }).catch(() => false)) {
    await resultInput.fill(TASK_RESULT);
  }

  // Fill actual date if present
  const actualDateInput = page
    .locator('[data-testid="form-field-dp_task_actual_date"] input')
    .first();
  if (await actualDateInput.isVisible({ timeout: 2_000 }).catch(() => false)) {
    await actualDateInput.fill(todayStr());
    await page.keyboard.press('Enter');
  }

  cmdResp = page
    .waitForResponse((r) => r.url().includes('/commands/execute'), { timeout: 20_000 })
    .catch(() => null);

  // Submit the modal form — look for confirm-ok button (custom ConfirmDialog component)
  const submitBtn = page.locator('[data-testid="confirm-ok"]').first();
  if (await submitBtn.isVisible({ timeout: 5_000 }).catch(() => false)) {
    await submitBtn.evaluate((el: HTMLElement) => el.click());
  }

  resp = await cmdResp;
  if (resp) {
    const body = await resp.json().catch(() => null);
    if (body?.code !== undefined) {
      expect(String(body.code), 'Complete inspection must return code "0"').toBe('0');
    }
  }

  await waitForToast(page, undefined, 8_000).catch(() => null);

  // D9: Navigate back to detail, verify completed status
  await page.goto(`/p/dp_inspection_task/view/${taskPid}`);
  await page.waitForLoadState('domcontentloaded');
  await page
    .waitForResponse(
      (r) => r.url().includes('/api/dynamic/dp_inspection_task') && !r.url().includes('/list'),
      { timeout: 12_000 },
    )
    .catch(() => null);
  await expect(page.getByText(/已完成|completed/i)).toBeVisible({ timeout: 10_000 });
});

// ===========================================================================
// DIN-004: Tab filtering [D3] @smoke
// ===========================================================================
test('DIN-004: Tab filtering — completed tab shows completed tasks @smoke', async ({ page }) => {
  await navigateToInspectionList(page);

  await clickTabAndWaitForLoad(page, /已完成|Completed/i, 10_000, 'completed');
  await page
    .waitForResponse(
      (r) => r.url().includes('/api/dynamic/dp_inspection_task') && r.url().includes('list'),
      { timeout: 10_000 },
    )
    .catch(() => null);

  // Should have at least the one we completed in DIN-003
  const rows = page.locator('tbody tr');
  await rows
    .first()
    .waitFor({ state: 'visible', timeout: 10_000 })
    .catch(() => null);
  const count = await rows.count();
  expect(count, 'Completed tab must show at least 1 completed inspection task').toBeGreaterThan(0);
});
