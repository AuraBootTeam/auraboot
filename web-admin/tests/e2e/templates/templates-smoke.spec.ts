/**
 * App Templates — Smoke E2E Tests (GAP-080)
 *
 * Covers the currently supported application templates:
 * 1. CRM Quick Start     (namespace: tcrm)
 * 2. Project Management  (namespace: tpm)
 * 3. Asset Management    (namespace: tasset)
 * 4. Simple Inventory    (namespace: tinv)
 * 5. HR Essentials       (namespace: thr)
 * 6. Golden Path         (namespace: gp)
 * 7. Enterprise HR       (namespace: ehr)
 * 8. Enterprise Compliance (namespace: ecm)
 * 9. Enterprise Asset    (namespace: eam)
 *
 * Per-template checks:
 * @smoke  Navigate via sidebar menu → page loads → table visible
 * @smoke  Create a primary record → appears in list
 *
 * Prerequisites:
 * - All template plugins must be imported:
 *   `/Users/ghj/work/auraboot/auraboot/scripts/import-templates.sh`
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
  // Templates are inconsistent here: some open full-page forms, some open dialogs,
  // and some mount an inline/drawer form without URL change. Treat any visible submit
  // button as "form opened" to avoid false negatives.
  const opened = await Promise.race([
    page
      .locator('[role="dialog"], [data-testid="command-modal"]')
      .waitFor({ state: 'visible', timeout: 12_000 })
      .then(() => 'dialog')
      .catch(() => null),
    page
      .waitForURL(/\/(new|create)(\?.*)?$/, { timeout: 12_000 })
      .then(() => 'page')
      .catch(() => null),
    page
      .locator('[data-testid="form-btn-submit"]')
      .waitFor({ state: 'visible', timeout: 12_000 })
      .then(() => 'inline')
      .catch(() => null),
  ]);
  expect(opened, 'Create action should open a dialog, drawer, inline form, or full page form').not.toBeNull();
  // For full-page forms: wait for the submit button to be visible + enabled,
  // ensuring React has fully hydrated and all event handlers are attached.
  if (opened === 'page' || opened === 'inline') {
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

async function createViaApiAndVerifyInList(
  page: Page,
  commandCode: string,
  payload: Record<string, unknown>,
  rootMenuName: string,
  leafMenuName: string,
  modelCode: string,
  keyword: string,
  keywordField: string,
): Promise<void> {
  await executeCommandViaApi(page, commandCode, payload, undefined, 'create');
  await navigateToTemplate(page, rootMenuName, leafMenuName, modelCode);
  await expect(page.locator('tbody tr').first()).toBeVisible({ timeout: 8_000 });
  const listResp = await page.request.get(
    `/api/dynamic/${modelCode}/list?pageSize=50&keyword=${encodeURIComponent(keyword)}`,
  );
  expect(listResp.ok()).toBe(true);
  const listBody = await listResp.json();
  const records = listBody?.data?.records ?? listBody?.records ?? [];
  expect(records.length, `Created ${modelCode} record should appear in list`).toBeGreaterThan(0);
  expect(String(records[0]?.[keywordField] ?? ''), `${modelCode}.${keywordField} should match`).toContain(
    keyword,
  );
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
    const leadName = `测试线索 ${ts}`;
    await createViaApiAndVerifyInList(
      page,
      'tcrm:create_lead',
      {
        tcrm_ld_name: leadName,
        tcrm_ld_company: `Test Company ${ts}`,
        tcrm_ld_email: `${ts}@example.com`,
        tcrm_ld_source: 'website',
      },
      rootMenu,
      '线索',
      'tcrm_lead',
      ts,
      'tcrm_ld_name',
    );
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
    const projectName = `测试项目 ${ts}`;
    await createViaApiAndVerifyInList(
      page,
      'tpm:create_project',
      {
        tpm_pj_name: projectName,
        tpm_pj_description: `Smoke project ${ts}`,
        tpm_pj_priority: 'high',
      },
      rootMenu,
      '项目',
      'tpm_project',
      ts,
      'tpm_pj_name',
    );
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

// ===========================================================================
// 6. Golden Path (gp)
// ===========================================================================

test.describe('Template: Golden Path', () => {
  test.setTimeout(45_000);
  const rootMenu = 'Golden Path';
  const ts = uniqueId('gp');

  test.beforeEach(async ({ page }) => {
    const resp = await page.request.get('/api/dynamic/gp_task/list?pageSize=1');
    test.skip(!resp.ok(), 'Golden Path template not installed');
  });

  test('TMP-GP-001 @smoke — 任务列表页可访问', async ({ page }) => {
    await navigateToTemplate(page, rootMenu, '任务', 'gp_task');
    await expect(page.locator('table, [data-testid="dynamic-list"]').first()).toBeVisible();
  });

  test('TMP-GP-002 @smoke — 评论列表页可访问', async ({ page }) => {
    await navigateToTemplate(page, rootMenu, '评论', 'gp_task_comment');
    await expect(page.locator('table, [data-testid="dynamic-list"]').first()).toBeVisible();
  });

  test('TMP-GP-003 @smoke — 审批列表页可访问', async ({ page }) => {
    await navigateToTemplate(page, rootMenu, '审批', 'gp_approval');
    await expect(page.locator('table, [data-testid="dynamic-list"]').first()).toBeVisible();
  });

  test('TMP-GP-004 @critical — 创建任务后出现在列表中', async ({ page }) => {
    const taskTitle = `Golden Path Task ${ts}`;
    await createViaApiAndVerifyInList(
      page,
      'gp:create_task',
      {
        gp_task_title: taskTitle,
        gp_task_description: `Smoke test task ${ts}`,
        gp_task_priority: 'medium',
      },
      rootMenu,
      '任务',
      'gp_task',
      ts,
      'gp_task_title',
    );
  });
});

// ===========================================================================
// 7. Enterprise HR (ehr)
// ===========================================================================

test.describe('Template: Enterprise HR', () => {
  test.setTimeout(45_000);
  const rootMenu = '人力资源';
  const ts = uniqueId('ehr');

  test.beforeEach(async ({ page }) => {
    const resp = await page.request.get('/api/dynamic/ehr_employee/list?pageSize=1');
    test.skip(!resp.ok(), 'Enterprise HR template not installed');
  });

  test('TMP-EHR-001 @smoke — 员工列表页可访问', async ({ page }) => {
    await navigateToTemplate(page, rootMenu, '员工', 'ehr_employee');
    await expect(page.locator('table, [data-testid="dynamic-list"]').first()).toBeVisible();
  });

  test('TMP-EHR-002 @smoke — 部门列表页可访问', async ({ page }) => {
    await navigateToTemplate(page, rootMenu, '部门', 'ehr_department');
    await expect(page.locator('table, [data-testid="dynamic-list"]').first()).toBeVisible();
  });

  test('TMP-EHR-003 @smoke — 职位列表页可访问', async ({ page }) => {
    await navigateToTemplate(page, rootMenu, '职位', 'ehr_position');
    await expect(page.locator('table, [data-testid="dynamic-list"]').first()).toBeVisible();
  });

  test('TMP-EHR-004 @smoke — 薪资列表页可访问', async ({ page }) => {
    await navigateToTemplate(page, rootMenu, '薪资', 'ehr_payroll');
    await expect(page.locator('table, [data-testid="dynamic-list"]').first()).toBeVisible();
  });

  test('TMP-EHR-005 @critical — 创建部门后出现在列表中', async ({ page }) => {
    const deptCode = `EHR-${ts}`.toUpperCase();
    const deptName = `企业人资部门 ${ts}`;
    await createViaApiAndVerifyInList(
      page,
      'ehr:create_department',
      {
        ehr_dp_code: deptCode,
        ehr_dp_name: deptName,
      },
      rootMenu,
      '部门',
      'ehr_department',
      ts,
      'ehr_dp_name',
    );
  });
});

// ===========================================================================
// 8. Enterprise Compliance (ecm)
// ===========================================================================

test.describe('Template: Enterprise Compliance', () => {
  test.setTimeout(45_000);
  const rootMenu = '合规管理';
  const ts = uniqueId('ecm');

  test.beforeEach(async ({ page }) => {
    const resp = await page.request.get('/api/dynamic/ecm_policy/list?pageSize=1');
    test.skip(!resp.ok(), 'Enterprise Compliance template not installed');
  });

  test('TMP-ECM-001 @smoke — 政策列表页可访问', async ({ page }) => {
    await navigateToTemplate(page, rootMenu, '政策', 'ecm_policy');
    await expect(page.locator('table, [data-testid="dynamic-list"]').first()).toBeVisible();
  });

  test('TMP-ECM-002 @smoke — 审计发现列表页可访问', async ({ page }) => {
    await navigateToTemplate(page, rootMenu, '审计发现', 'ecm_audit_finding');
    await expect(page.locator('table, [data-testid="dynamic-list"]').first()).toBeVisible();
  });

  test('TMP-ECM-003 @smoke — 纠正措施列表页可访问', async ({ page }) => {
    await navigateToTemplate(page, rootMenu, '纠正措施', 'ecm_corrective_action');
    await expect(page.locator('table, [data-testid="dynamic-list"]').first()).toBeVisible();
  });

  test('TMP-ECM-004 @smoke — 风险评估列表页可访问', async ({ page }) => {
    await navigateToTemplate(page, rootMenu, '风险评估', 'ecm_risk_assessment');
    await expect(page.locator('table, [data-testid="dynamic-list"]').first()).toBeVisible();
  });

  test('TMP-ECM-005 @critical — 创建政策后出现在列表中', async ({ page }) => {
    const policyTitle = `合规政策 ${ts}`;
    await createViaApiAndVerifyInList(
      page,
      'ecm:create_policy',
      {
        ecm_po_title: policyTitle,
        ecm_po_category: 'general',
        ecm_po_owner: 'Compliance Admin',
        ecm_po_description: `Smoke test policy ${ts}`,
      },
      rootMenu,
      '政策',
      'ecm_policy',
      ts,
      'ecm_po_title',
    );
  });
});

// ===========================================================================
// 9. Enterprise Asset (eam)
// ===========================================================================

test.describe('Template: Enterprise Asset', () => {
  test.setTimeout(45_000);
  const rootMenu = '资产管理';
  const ts = uniqueId('eam');

  test.beforeEach(async ({ page }) => {
    const resp = await page.request.get('/api/dynamic/eam_asset/list?pageSize=1');
    test.skip(!resp.ok(), 'Enterprise Asset template not installed');
  });

  test('TMP-EAM-001 @smoke — 资产列表页可访问', async ({ page }) => {
    await navigateToTemplate(page, rootMenu, '资产', 'eam_asset');
    await expect(page.locator('table, [data-testid="dynamic-list"]').first()).toBeVisible();
  });

  test('TMP-EAM-002 @smoke — 分类列表页可访问', async ({ page }) => {
    await navigateToTemplate(page, rootMenu, '分类', 'eam_asset_category');
    await expect(page.locator('table, [data-testid="dynamic-list"]').first()).toBeVisible();
  });

  test('TMP-EAM-003 @smoke — 维护计划列表页可访问', async ({ page }) => {
    await navigateToTemplate(page, rootMenu, '维护计划', 'eam_maintenance');
    await expect(page.locator('table, [data-testid="dynamic-list"]').first()).toBeVisible();
  });

  test('TMP-EAM-004 @smoke — 工单列表页可访问', async ({ page }) => {
    await navigateToTemplate(page, rootMenu, '工单', 'eam_work_order');
    await expect(page.locator('table, [data-testid="dynamic-list"]').first()).toBeVisible();
  });

  test('TMP-EAM-005 @critical — 创建分类后出现在列表中', async ({ page }) => {
    const categoryCode = `EAM-${ts}`.toUpperCase();
    const categoryName = `企业资产分类 ${ts}`;
    await createViaApiAndVerifyInList(
      page,
      'eam:create_category',
      {
        eam_ct_code: categoryCode,
        eam_ct_name: categoryName,
      },
      rootMenu,
      '分类',
      'eam_asset_category',
      ts,
      'eam_ct_name',
    );
  });
});
