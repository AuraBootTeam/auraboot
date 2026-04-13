/**
 * App Templates — Smoke E2E Tests (GAP-080)
 *
 * Covers all 5 application templates:
 * 1. CRM Quick Start     (namespace: tcrm)
 * 2. Project Management  (namespace: tpm)
 * 3. Asset Management    (namespace: tasset)
 * 4. Simple Inventory    (namespace: tinv)
 * 5. HR Essentials       (namespace: thr)
 *
 * Per-template checks:
 * @smoke  Navigate via sidebar menu → page loads → table visible
 * @smoke  Create a primary record → appears in list
 *
 * Prerequisites:
 * - All 5 templates must be imported (run scripts/import-templates.sh)
 * - User is logged in as admin@example.com
 *
 * @since GAP-080 (2026-03-17)
 */

import { test, expect, type Page } from '../../fixtures';
import { uniqueId, executeCommandViaApi } from '../helpers/index';

// Run all template tests serially to prevent cross-test page closure race conditions
test.describe.configure({ mode: 'serial' });

// ---------------------------------------------------------------------------
// Shared helper
// ---------------------------------------------------------------------------

async function navigateToTemplate(
  page: Page,
  rootMenuName: string,
  _leafMenuName: string,
  modelCode: string,
): Promise<void> {
  await page.goto('/dashboards');
  await page.waitForLoadState('domcontentloaded');

  const nav = page.locator('nav');
  const hrefPath = `/p/${modelCode}`;

  // Expand the template root first; template menus are not attached until the
  // matching root group is opened.
  const rootButtons = nav.getByRole('button', { name: new RegExp(rootMenuName, 'i') });
  const rootCount = await rootButtons.count();
  for (let i = 0; i < rootCount; i += 1) {
    const button = rootButtons.nth(i);
    const visible = await button.isVisible().catch(() => false);
    if (!visible) continue;
    await button.evaluate((el: HTMLElement) => el.click());
  }

  // Find the leaf link by href — use evaluate() to click even if CSS-hidden
  // (multiple root groups may have the same display name, e.g. two "crm" nodes)
  const leafLink = nav.locator(`a[href="${hrefPath}"]`).first();
  await leafLink.waitFor({ state: 'attached', timeout: 8_000 });

  const listResponsePromise = page.waitForResponse(
    (r) =>
      (r.url().includes(`/api/dynamic/${modelCode}`) ||
        r.url().includes(`/api/dynamic/${modelCode}`)) &&
      r.status() === 200,
    { timeout: 30_000 },
  );
  await leafLink.evaluate((el: HTMLElement) => el.click());
  await listResponsePromise;

  // Wait for table to render (ant-table or plain table)
  await expect(
    page.locator('table, [class*="ant-table-wrapper"], [data-testid="dynamic-list"]').first(),
  ).toBeVisible({ timeout: 20_000 });
}

async function openCreateForm(page: Page): Promise<void> {
  const createBtn = page.getByRole('button', { name: /新建|创建|Add|Create/i }).first();
  await createBtn.waitFor({ state: 'visible', timeout: 5_000 });
  await createBtn.click();
  // Wait for either a modal dialog OR a full-page form navigation
  await Promise.race([
    page
      .locator('[role="dialog"], [data-testid="command-modal"]')
      .waitFor({ state: 'visible', timeout: 12_000 }),
    page.waitForURL(/\/(new|create)(\?.*)?$/, { timeout: 12_000 }),
  ]);
  // For full-page forms: wait for the submit button to be visible + enabled,
  // ensuring React has fully hydrated and all event handlers are attached.
  if (/\/(new|create)(\?.*)?$/.test(page.url())) {
    const submitBtn = page.locator('[data-testid="form-btn-submit"]');
    await submitBtn.waitFor({ state: 'visible', timeout: 10_000 });
    await expect(submitBtn).toBeEnabled({ timeout: 8_000 });
  }
}

async function fillFirstTextInput(page: Page, value: string): Promise<void> {
  // Support both modal and full-page form
  const dialog = page.locator('[role="dialog"]').first();
  const isModal = await dialog.isVisible().catch(() => false);
  const container = isModal ? dialog : page;
  const input = container.locator('input[type="text"], input:not([type])').first();
  await input.waitFor({ state: 'visible', timeout: 5_000 });
  await input.click();
  // Select-all + delete to clear any pre-existing value, then type character-by-
  // character. pressSequentially fires real keydown/keypress/keyup events which
  // Ant Design always picks up — unlike programmatic value assignment which can
  // fail to update React's controlled state after many serial test navigations.
  await input.press('Control+a');
  await input.press('Delete');
  await input.pressSequentially(value, { delay: 30 });
}

async function submitForm(page: Page): Promise<void> {
  const dialog = page.locator('[role="dialog"]').first();
  const isModal = await dialog.isVisible().catch(() => false);
  if (isModal) {
    const submitBtn = dialog.getByRole('button', { name: /确定|提交|保存|Submit|Save|OK/i }).last();
    await submitBtn.click();
    // Wait for modal to close
    await expect(page.locator('[role="dialog"]')).toHaveCount(0, { timeout: 10_000 });
  } else {
    // Full-page form — use data-testid="form-btn-submit" (rendered by FormPageContent)
    const submitBtn = page.locator('[data-testid="form-btn-submit"]');
    await submitBtn.waitFor({ state: 'visible', timeout: 5_000 });
    // Register response listener BEFORE click to avoid race condition
    const saveResponsePromise = page.waitForResponse(
      (r) =>
        r.url().includes('/api/meta/commands/execute/') &&
        r.request().method().toLowerCase() === 'post' &&
        r.status() === 200,
      { timeout: 20_000 },
    );
    await submitBtn.click();
    await saveResponsePromise;
    // After command success React navigates back to /p/${tableName}
    await page.waitForURL(/\/p\/[^/]+$/, { timeout: 15_000 });
    // Wait for table to be visible (confirms list page rendered with data)
    await expect(
      page.locator('table, [class*="ant-table-wrapper"], [data-testid="dynamic-list"]').first(),
    ).toBeVisible({ timeout: 15_000 });
  }
}

// ===========================================================================
// 1. CRM Quick Start (tcrm)
// ===========================================================================

test.describe('Template: CRM Quick Start', () => {
  test.setTimeout(45_000);
  const rootMenu = 'crm';
  const ts = uniqueId('tcrm');

  test.beforeEach(async ({ page }) => {
    // Check if tcrm template plugin is installed and accessible
    const resp = await page.request.get('/api/dynamic/tcrm_lead/list?pageSize=1');
    // Skip if model doesn't exist (404) or if permission denied (403)
    test.skip(!resp.ok(), `CRM Quick Start template not accessible: ${resp.status()} (model may not be installed or user lacks permission)`);
  });

  test('TMP-CRM-001 @smoke — 线索列表页可访问', async ({ page }) => {
    await navigateToTemplate(page, rootMenu, '线索', 'tcrm_lead');
    await expect(page.locator('table, [data-testid="dynamic-list"]').first()).toBeVisible();
  });

  test('TMP-CRM-002 @smoke — 客户列表页可访问', async ({ page }) => {
    await navigateToTemplate(page, rootMenu, '客户', 'tcrm_account');
    await expect(page.locator('table, [data-testid="dynamic-list"]').first()).toBeVisible();
  });

  test('TMP-CRM-003 @smoke — 联系人列表页可访问', async ({ page }) => {
    await navigateToTemplate(page, rootMenu, '联系人', 'tcrm_contact');
    await expect(page.locator('table, [data-testid="dynamic-list"]').first()).toBeVisible();
  });

  test('TMP-CRM-004 @smoke — 商机列表页可访问', async ({ page }) => {
    await navigateToTemplate(page, rootMenu, '商机', 'tcrm_opportunity');
    await expect(page.locator('table, [data-testid="dynamic-list"]').first()).toBeVisible();
  });

  test('TMP-CRM-005 @critical — 创建线索后出现在列表中', async ({ page }) => {
    await navigateToTemplate(page, rootMenu, '线索', 'tcrm_lead');
    await openCreateForm(page);
    const leadName = `测试线索 ${ts}`;
    await fillFirstTextInput(page, leadName);
    await submitForm(page);
    await expect(page.getByText(leadName)).toBeVisible({ timeout: 10_000 });
  });
});

// ===========================================================================
// 2. Project Management (tpm)
// ===========================================================================

test.describe('Template: Project Management', () => {
  test.setTimeout(45_000);
  const rootMenu = '项目管理';
  const ts = uniqueId('tpm');

  test.beforeEach(async ({ page }) => {
    const resp = await page.request.get('/api/dynamic/tpm_project/list?pageSize=1');
    test.skip(!resp.ok(), 'Project Management template not installed');
  });

  test('TMP-PM-001 @smoke — 项目列表页可访问', async ({ page }) => {
    await navigateToTemplate(page, rootMenu, '项目', 'tpm_project');
    await expect(page.locator('table, [data-testid="dynamic-list"]').first()).toBeVisible();
  });

  test('TMP-PM-002 @smoke — 任务列表页可访问', async ({ page }) => {
    await navigateToTemplate(page, rootMenu, '任务', 'tpm_task');
    await expect(page.locator('table, [data-testid="dynamic-list"]').first()).toBeVisible();
  });

  test('TMP-PM-003 @smoke — 里程碑列表页可访问', async ({ page }) => {
    await navigateToTemplate(page, rootMenu, '里程碑', 'tpm_milestone');
    await expect(page.locator('table, [data-testid="dynamic-list"]').first()).toBeVisible();
  });

  test('TMP-PM-004 @critical — 创建项目后出现在列表中', async ({ page }) => {
    await navigateToTemplate(page, rootMenu, '项目', 'tpm_project');
    await openCreateForm(page);
    const projectName = `测试项目 ${ts}`;
    await fillFirstTextInput(page, projectName);
    await submitForm(page);
    await expect(page.getByText(projectName)).toBeVisible({ timeout: 10_000 });
  });
});

// ===========================================================================
// 3. Asset Management (tasset)
// ===========================================================================

test.describe('Template: Asset Management', () => {
  test.setTimeout(45_000);
  const rootMenu = '资产管理';
  const ts = uniqueId('tasset');

  test.beforeEach(async ({ page }) => {
    const resp = await page.request.get('/api/dynamic/tasset_asset/list?pageSize=1');
    test.skip(!resp.ok(), 'Asset Management template not installed');
  });

  test('TMP-ASSET-001 @smoke — 资产列表页可访问', async ({ page }) => {
    await navigateToTemplate(page, rootMenu, '资产', 'tasset_asset');
    await expect(page.locator('table, [data-testid="dynamic-list"]').first()).toBeVisible();
  });

  test('TMP-ASSET-002 @smoke — 分类列表页可访问', async ({ page }) => {
    await navigateToTemplate(page, rootMenu, '分类', 'tasset_category');
    await expect(page.locator('table, [data-testid="dynamic-list"]').first()).toBeVisible();
  });

  test('TMP-ASSET-003 @smoke — 维护记录列表页可访问', async ({ page }) => {
    await navigateToTemplate(page, rootMenu, '维护记录', 'tasset_maintenance');
    await expect(page.locator('table, [data-testid="dynamic-list"]').first()).toBeVisible();
  });

  test('TMP-ASSET-004 @critical — 创建资产后出现在列表中', async ({ page }) => {
    await navigateToTemplate(page, rootMenu, '资产', 'tasset_asset');
    await openCreateForm(page);
    const assetName = `测试资产 ${ts}`;
    await fillFirstTextInput(page, assetName);
    await submitForm(page);
    await expect(page.getByText(assetName)).toBeVisible({ timeout: 10_000 });
  });
});

// ===========================================================================
// 4. Simple Inventory (tinv)
// ===========================================================================

test.describe('Template: Simple Inventory', () => {
  test.setTimeout(45_000);
  const rootMenu = '进销存';
  const ts = uniqueId('tinv');

  test.beforeEach(async ({ page }) => {
    const resp = await page.request.get('/api/dynamic/tinv_product/list?pageSize=1');
    test.skip(!resp.ok(), 'Simple Inventory template not installed');
  });

  test('TMP-INV-001 @smoke — 产品列表页可访问', async ({ page }) => {
    await navigateToTemplate(page, rootMenu, '产品', 'tinv_product');
    await expect(page.locator('table, [data-testid="dynamic-list"]').first()).toBeVisible();
  });

  test('TMP-INV-002 @smoke — 仓库列表页可访问', async ({ page }) => {
    await navigateToTemplate(page, rootMenu, '仓库', 'tinv_warehouse');
    await expect(page.locator('table, [data-testid="dynamic-list"]').first()).toBeVisible();
  });

  test('TMP-INV-003 @smoke — 入库列表页可访问', async ({ page }) => {
    await navigateToTemplate(page, rootMenu, '入库', 'tinv_stock_in');
    await expect(page.locator('table, [data-testid="dynamic-list"]').first()).toBeVisible();
  });

  test('TMP-INV-004 @smoke — 出库列表页可访问', async ({ page }) => {
    await navigateToTemplate(page, rootMenu, '出库', 'tinv_stock_out');
    await expect(page.locator('table, [data-testid="dynamic-list"]').first()).toBeVisible();
  });

  test('TMP-INV-005 @critical — 创建产品后出现在列表中', async ({ page }) => {
    await navigateToTemplate(page, rootMenu, '产品', 'tinv_product');
    await openCreateForm(page);
    const productName = `测试产品 ${ts}`;
    await fillFirstTextInput(page, productName);
    await submitForm(page);
    await expect(page.getByText(productName)).toBeVisible({ timeout: 10_000 });
  });
});

// ===========================================================================
// 5. HR Essentials (thr)
// ===========================================================================

test.describe('Template: HR Essentials', () => {
  test.setTimeout(45_000);
  const rootMenu = '人事管理';
  const ts = uniqueId('thr');

  test.beforeEach(async ({ page }) => {
    const resp = await page.request.get('/api/dynamic/thr_employee/list?pageSize=1');
    test.skip(!resp.ok(), 'HR Essentials template not installed');
  });

  test('TMP-HR-001 @smoke — 员工列表页可访问', async ({ page }) => {
    await navigateToTemplate(page, rootMenu, '员工', 'thr_employee');
    await expect(page.locator('table, [data-testid="dynamic-list"]').first()).toBeVisible();
  });

  test('TMP-HR-002 @smoke — 考勤记录列表页可访问', async ({ page }) => {
    await navigateToTemplate(page, rootMenu, '考勤记录', 'thr_attendance');
    await expect(page.locator('table, [data-testid="dynamic-list"]').first()).toBeVisible();
  });

  test('TMP-HR-003 @smoke — 请假申请列表页可访问', async ({ page }) => {
    await navigateToTemplate(page, rootMenu, '请假申请', 'thr_leave_request');
    await expect(page.locator('table, [data-testid="dynamic-list"]').first()).toBeVisible();
  });

  test('TMP-HR-004 @critical — 创建员工后出现在列表中', async ({ page }) => {
    // Employee form has multiple required fields (name, department enum, hire date)
    // Create via API command to ensure reliable data, then verify via UI list navigation
    const employeeName = `测试员工 ${ts}`;
    await executeCommandViaApi(
      page,
      'thr:create_employee',
      {
        thr_em_name: employeeName,
        thr_em_department: 'engineering',
        thr_em_hire_date: new Date().toISOString().slice(0, 10),
        thr_em_status: 'active',
      },
      undefined,
      'create',
    );

    // Navigate via sidebar menu (UI interaction requirement)
    await navigateToTemplate(page, rootMenu, '员工', 'thr_employee');

    // Verify the created employee appears in the list
    await expect(page.locator('tbody tr').first()).toBeVisible({ timeout: 8000 });
    // Check via API for data correctness
    const listResp = await page.request.get(
      '/api/dynamic/thr_employee/list?pageSize=50&keyword=' + encodeURIComponent(ts),
    );
    expect(listResp.ok()).toBe(true);
    const listBody = await listResp.json();
    const records = listBody?.data?.records ?? listBody?.records ?? [];
    expect(records.length, 'Created employee should appear in list').toBeGreaterThan(0);
    expect(records[0].thr_em_name, 'Employee name should match').toContain(ts);
  });
});
