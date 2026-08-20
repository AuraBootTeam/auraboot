/**
 * Enterprise Member Management E2E Tests
 *
 * Tests the DSL-driven tenant member management page at /p/tenant-member.
 *
 * MM-01: Page loads with table rendered
 * MM-02: Status tabs (All, Pending, Active, Suspended, Rejected) exist and switch
 * MM-03: Table displays correct column structure
 * MM-04: Row action visibility — active member shows suspend/delete, not approve/reject
 * MM-05: Suspend then restore cycle (uses self-created test member)
 * MM-06: i18n — labels use translated text, not raw keys
 * MM-07: Six-column no-email workbook happy path + one-time credentials
 * MM-08: Unknown organization code blocks commit
 * MM-09: Duplicate login name / employee code blocks commit
 * MM-10: Existing unbound employee is linked by unique codes
 * MM-11: Linked employee / cross-department position blocks commit
 * MM-12: Baseline member cannot see or call account import
 *
 * Prerequisites:
 * - platform-admin plugin imported with tenant_member model
 * - At least one active member exists (the logged-in admin)
 *
 * @since 4.0.0
 */

import { test, expect } from '../../fixtures';
import type { Page } from '@playwright/test';
import { readFile } from 'node:fs/promises';
import AdmZip from 'adm-zip';
import * as XLSX from 'xlsx';
import {
  navigateToDynamicPage,
  waitForDynamicPageLoad,
  clickTabAndWaitForLoad,
  findRowInPaginatedList,
  ensureSidebarExpanded,
  queryFilteredList,
  uniqueId,
} from '../helpers/index';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';
import { BACKEND_URL } from '../../helpers/environments';
import { ensureRoleUser, makeRoleUser, openAsRole } from '../rbac/rbac-helpers';

const MEMBER_IMPORT_HEADERS = ['姓名*', '登录名', '手机号', '工号', '部门编码', '岗位编码'];

type MemberImportRow = [string, string, string, string, string, string];

function escapeRegExp(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function memberRowLookup(email: string, userId?: string | null): string {
  return email.split('@')[0] || userId || email;
}

function isCommandSuccess(body: any): boolean {
  const code = body?.code ?? body?.data?.code;
  if (code === 0 || code === '0' || code === '00000') return true;
  if (body?.success === true || body?.data?.success === true) return true;
  return false;
}

function buildMemberImportWorkbook(rows: MemberImportRow[]): Buffer {
  const workbook = XLSX.utils.book_new();
  XLSX.utils.book_append_sheet(
    workbook,
    XLSX.utils.aoa_to_sheet([
      ['填写说明：姓名必填；登录名为空时默认使用姓名。'],
      MEMBER_IMPORT_HEADERS,
      ...rows,
    ]),
    '账号导入',
  );
  return XLSX.write(workbook, { type: 'buffer', bookType: 'xlsx' }) as Buffer;
}

async function openMemberImportDialog(page: Page): Promise<void> {
  await navigateToDynamicPage(page, 'tenant_member');
  await page.getByTestId('member-import-entry').click();
  await expect(page.getByTestId('member-import-dialog')).toBeVisible();
}

async function uploadAndPreviewMemberImport(
  page: Page,
  rows: MemberImportRow[],
  name = 'employee-account-import.xlsx',
): Promise<any> {
  await page.getByTestId('member-import-file-input').setInputFiles({
    name,
    mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    buffer: buildMemberImportWorkbook(rows),
  });
  const previewResponsePromise = page.waitForResponse(
    (response) =>
      response.url().includes('/api/admin/users/employee-accounts/import/preview') &&
      response.request().method() === 'POST',
  );
  await page.getByTestId('member-import-preview').click();
  const previewResponse = await previewResponsePromise;
  expect(previewResponse.ok()).toBe(true);
  const previewBody = await previewResponse.json();
  expect(previewBody?.code).toBe('0');
  return previewBody;
}

async function createImportOrganizationFixture(page: Page, suffix: string) {
  const departmentCode = `DEPT-${suffix}`;
  const positionCode = `POS-${suffix}`;
  const departmentResponse = await page.request.post('/api/org/departments', {
    data: {
      org_dept_name: `导入部门${suffix}`,
      org_dept_code: departmentCode,
      org_dept_status: 'active',
      org_dept_order: 1,
    },
  });
  expect(departmentResponse.ok()).toBe(true);
  const departmentPid = String((await departmentResponse.json())?.data?.pid ?? '');
  expect(departmentPid).toBeTruthy();

  const positionResponse = await page.request.post('/api/dynamic/org_position/create', {
    data: {
      org_pos_name: `导入岗位${suffix}`,
      org_pos_code: positionCode,
      org_pos_level: 'P1',
      org_pos_status: 'active',
      org_pos_dept_id: departmentPid,
    },
  });
  expect(positionResponse.ok()).toBe(true);
  const positionBody = await positionResponse.json();
  const positionPid = String(positionBody?.data?.pid ?? positionBody?.data?.data?.pid ?? '');
  expect(positionPid).toBeTruthy();

  return { departmentCode, departmentPid, positionCode, positionPid };
}

/** Helper: get admin JWT from backend */
async function getAdminJwt(): Promise<string> {
  const resp = await fetch(`${BACKEND_URL}/api/auth/login`, {
    method: 'post',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      email: DEFAULT_TEST_ACCOUNT.email,
      password: DEFAULT_TEST_ACCOUNT.password,
    }),
  });
  const body = await resp.json();
  return body.data?.jwt;
}

/** Helper: search members by keyword, return first match { pid, userId } */
async function findMember(
  adminJwt: string,
  keyword: string,
  expectedUserId?: string | null,
): Promise<{ pid: string; userId: string } | null> {
  for (let attempt = 0; attempt < 10; attempt++) {
    for (let pageNum = 1; pageNum <= 10; pageNum++) {
      const resp = await fetch(`${BACKEND_URL}/api/tenant/members/search`, {
        method: 'post',
        headers: { Authorization: `Bearer ${adminJwt}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ keyword: '', pageNum, pageSize: 200 }),
      });
      const body = await resp.json().catch(() => ({}) as any);
      const items = body.data?.data ?? [];
      const match = items.find((item: any) => {
        const itemUserId = String(item.userId ?? item.user?.id ?? '');
        const email = String(item.user?.email ?? '');
        if (expectedUserId && itemUserId === expectedUserId) return true;
        return email.includes(keyword);
      });
      if (match) {
        return { pid: match.pid, userId: String(match.userId ?? match.user?.id ?? '') };
      }
      if (items.length === 0) break;
    }
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
  return null;
}

/** Helper: approve a member */
async function approveMember(adminJwt: string, memberPid: string): Promise<void> {
  await fetch(`${BACKEND_URL}/api/tenant/members/${memberPid}/approve`, {
    method: 'post',
    headers: { Authorization: `Bearer ${adminJwt}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ action: 'approve' }),
  });
}

async function restoreMember(adminJwt: string, memberPid: string): Promise<void> {
  await fetch(`${BACKEND_URL}/api/tenant/members/${memberPid}/approve`, {
    method: 'post',
    headers: { Authorization: `Bearer ${adminJwt}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ action: 'approve' }),
  });
}

async function suspendMember(adminJwt: string, memberPid: string): Promise<void> {
  await fetch(`${BACKEND_URL}/api/tenant/members/${memberPid}/status`, {
    method: 'put',
    headers: { Authorization: `Bearer ${adminJwt}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ action: 'suspended' }),
  });
}

/** Helper: delete a member */
async function deleteMember(adminJwt: string, memberPid: string): Promise<void> {
  await fetch(`${BACKEND_URL}/api/tenant/members/${memberPid}`, {
    method: 'delete',
    headers: { Authorization: `Bearer ${adminJwt}` },
  });
}

async function getMemberStatus(adminJwt: string, memberPid: string): Promise<string | null> {
  const resp = await fetch(`${BACKEND_URL}/api/tenant/members/${memberPid}`, {
    headers: { Authorization: `Bearer ${adminJwt}` },
  });
  if (!resp.ok) return null;
  const body = await resp.json().catch(() => null);
  return (body?.data?.status ?? null) as string | null;
}

async function clearListSearch(page: import('@playwright/test').Page): Promise<void> {
  const searchInput = page
    .locator(
      '[data-testid="search-input"], [data-testid="table-search-input"], input[placeholder*="搜索"], input[placeholder*="Search"]',
    )
    .first();
  if (!(await searchInput.isVisible({ timeout: 1500 }).catch(() => false))) {
    return;
  }
  await searchInput.fill('');
  await searchInput.press('Enter').catch(() => null);
  await page
    .waitForResponse(
      (r) =>
        r.url().includes('/list') &&
        r.request().method().toLowerCase() === 'post' &&
        r.status() === 200,
      { timeout: 5000 },
    )
    .catch(() => null);
}

const MM05_TARGET_EMAIL = 'e2e-operator@test.com';

test.describe('Member Management — DSL Page', () => {
  // --- Data for MM-05: reusable seeded member ---
  const testMemberEmail = MM05_TARGET_EMAIL;
  let testMemberPid: string | null = null;
  let testMemberUserId: string | null = null; // userId displayed in table
  let adminJwt: string;

  test.beforeAll(async ({}, testInfo) => {
    testInfo.setTimeout(120000);
    adminJwt = await getAdminJwt();

    const member = await findMember(adminJwt, testMemberEmail);
    testMemberPid = member?.pid ?? null;
    testMemberUserId = member?.userId ?? null;
    // Note: testMemberPid may be null if e2e-operator account hasn't been added to this tenant yet.
    // MM-01 through MM-03 don't require it. MM-04/MM-05 will skip if it's absent.

    // Seed member starts as pending after reset; make sure MM-05 always has an active non-admin row.
    const currentStatus = testMemberPid ? await getMemberStatus(adminJwt, testMemberPid) : null;
    if (testMemberPid && currentStatus === 'pending') {
      await approveMember(adminJwt, testMemberPid);
    } else if (testMemberPid && (currentStatus === 'suspended' || currentStatus === 'rejected')) {
      await restoreMember(adminJwt, testMemberPid);
    }
  });

  /**
   * MM-01: Page loads — navigate to /p/tenant-member, verify table renders.
   */
  test('MM-01: should load member management DSL page with table @smoke', async ({ page }) => {
    await navigateToDynamicPage(page, 'tenant_member');

    // Verify no error page
    const errorMsg = page.locator('text=Page Unavailable');
    const hasError = await errorMsg.isVisible({ timeout: 3000 }).catch(() => false);
    expect(hasError).toBe(false);

    // Verify the data table is rendered
    const table = page.locator('table, [role="table"]');
    await expect(table.first()).toBeVisible({ timeout: 15000 });

    // Verify table has at least header row
    const headerCells = page.locator('thead th');
    await expect(headerCells.first()).toBeVisible({ timeout: 5000 });
  });

  /**
   * MM-02: Status tabs — verify tabs exist and can be clicked.
   */
  test('MM-02: should display and switch status tabs', async ({ page }) => {
    await navigateToDynamicPage(page, 'tenant_member');

    // Verify the tab navigation area exists
    const tabNav = page.locator('nav[aria-label="Tabs"]');
    await expect(tabNav).toBeVisible({ timeout: 15000 });

    // Verify key tabs are present
    await expect(page.locator('[data-testid="tab-all"]')).toBeVisible({ timeout: 3000 });
    await expect(page.locator('[data-testid="tab-active"]')).toBeVisible({ timeout: 3000 });
    await expect(page.locator('[data-testid="tab-pending"]')).toBeVisible({ timeout: 3000 });
    await expect(page.locator('[data-testid="tab-suspended"]')).toBeVisible({ timeout: 3000 });
    await expect(page.locator('[data-testid="tab-rejected"]')).toBeVisible({ timeout: 3000 });

    // Click "active" tab and verify list refreshes
    await clickTabAndWaitForLoad(page, /Active|已激活/, 5000);
    await expect(page.locator('table').first()).toBeVisible({ timeout: 5000 });

    // Click "all" tab to return to full list
    await clickTabAndWaitForLoad(page, /All|全部/, 5000, 'all');
    await expect(page.locator('table').first()).toBeVisible({ timeout: 5000 });
  });

  /**
   * MM-03: Table has correct column structure.
   * Verifies the DSL-defined columns are rendered (user_id, status, join_date, etc.)
   */
  test('MM-03: should display correct table columns', async ({ page }) => {
    await navigateToDynamicPage(page, 'tenant_member');

    // Wait for table to render
    await expect(page.locator('table').first()).toBeVisible({ timeout: 15000 });

    // Verify column headers exist (DSL defines 5 columns + action = 6+)
    const headers = page.locator('thead th');
    const headerCount = await headers.count();
    expect(headerCount).toBeGreaterThanOrEqual(4);

    // Verify at least one data row exists (the admin member)
    const dataRows = page.locator('tbody tr');
    await expect(dataRows.first()).toBeVisible({ timeout: 5000 });
    expect(await dataRows.count()).toBeGreaterThan(0);
  });

  /**
   * MM-04: Row action visibility — active member shows suspend/delete.
   */
  test('MM-04: should show correct row actions for active member', async ({ page }) => {
    await navigateToDynamicPage(page, 'tenant_member');

    // Switch to active tab
    await clickTabAndWaitForLoad(page, /Active|已激活/, 5000, 'active');

    const rows = page.locator('tbody tr');
    await expect(rows.first()).toBeVisible({ timeout: 10000 });

    const firstRow = rows.first();
    // Hover row to reveal action buttons (opacity-0 → opacity-100 via group-hover)
    await firstRow.hover();

    // For an active member, suspend should be visible as a direct action
    const suspendAction = firstRow.locator('[data-testid="row-action-suspend"]').first();
    await expect(suspendAction).toBeVisible({ timeout: 5000 });

    // Delete may be in the "more" dropdown — open it to verify
    const directDelete = firstRow.locator('[data-testid="row-action-delete"]').first();
    const hasDirectDelete = await directDelete.isVisible({ timeout: 1000 }).catch(() => false);

    if (!hasDirectDelete) {
      // Open "more actions" dropdown to check for delete
      const moreBtn = firstRow.locator('[data-testid="row-action-more"]').first();
      const hasMore = await moreBtn.isVisible({ timeout: 3000 }).catch(() => false);
      expect(
        hasMore,
        'Either direct delete button or "more" dropdown must be present for active member',
      ).toBe(true);

      if (hasMore) {
        await moreBtn.evaluate((el: HTMLElement) => el.click());
        const dropdown = page.locator('[data-testid="row-action-dropdown"]');
        await dropdown.waitFor({ state: 'visible', timeout: 5000 });
        const deleteInDropdown = dropdown.locator('[data-testid="row-action-delete"]').first();
        await expect(deleteInDropdown).toBeVisible({ timeout: 3000 });
        await expect(
          dropdown.locator('[data-testid="row-action-reset-password"]').first(),
        ).toBeVisible({
          timeout: 3000,
        });
        // Close dropdown
        await page.keyboard.press('Escape');
      }
    } else {
      await expect(directDelete).toBeVisible();
      const moreBtn = firstRow.locator('[data-testid="row-action-more"]').first();
      const hasMore = await moreBtn.isVisible({ timeout: 3000 }).catch(() => false);
      const directResetAction = firstRow
        .locator('[data-testid="row-action-reset-password"]')
        .first();
      const hasDirectReset = await directResetAction
        .isVisible({ timeout: 1000 })
        .catch(() => false);
      expect(
        hasDirectReset || hasMore,
        'Reset password action should be available directly or in the more menu',
      ).toBe(true);
      if (hasMore) {
        await moreBtn.evaluate((el: HTMLElement) => el.click());
        const dropdown = page.locator('[data-testid="row-action-dropdown"]');
        await dropdown.waitFor({ state: 'visible', timeout: 5000 });
        await expect(
          dropdown.locator('[data-testid="row-action-reset-password"]').first(),
        ).toBeVisible({
          timeout: 3000,
        });
        await page.keyboard.press('Escape');
      } else {
        await expect(directResetAction).toBeVisible();
      }
    }

    // For an active member, approve should NOT be visible (only for pending)
    const approveAction = page.locator('[data-testid="row-action-approve"]').first();
    const hasApprove = await approveAction.isVisible({ timeout: 1000 }).catch(() => false);
    expect(hasApprove).toBe(false);
  });

  /**
   * MM-05: Suspend then restore cycle on a self-created test member.
   */
  test('MM-05: should complete suspend-restore cycle', async ({ page }) => {
    test.setTimeout(60000);
    test.skip(
      !testMemberPid,
      'Seeded operator member is not attached to the current tenant in this environment',
    );
    await navigateToDynamicPage(page, 'tenant_member');

    // Switch to active tab
    await clickTabAndWaitForLoad(page, /Active|已激活/, 5000);
    await clearListSearch(page);

    const targetLookup = memberRowLookup(testMemberEmail, testMemberUserId);
    const targetRow = await findRowInPaginatedList(page, targetLookup, 12000);
    await expect(targetRow).toBeVisible({ timeout: 10000 });

    // --- Step 1: Suspend ---
    await targetRow.hover();
    const suspendBtn = targetRow.locator('[data-testid="row-action-suspend"]');
    await expect(suspendBtn).toBeVisible({ timeout: 3000 });
    if (testMemberPid) {
      await suspendMember(adminJwt, testMemberPid);
    }

    await expect
      .poll(async () => (testMemberPid ? await getMemberStatus(adminJwt, testMemberPid) : null), {
        timeout: 10000,
        intervals: [400, 600, 800, 1000],
        message: 'test member should transition to suspended after suspend action',
      })
      .toBe('suspended');

    // --- Step 2: Switch to suspended tab and restore ---
    await clickTabAndWaitForLoad(page, /Suspended|已暂停/, 5000);
    await clearListSearch(page);
    const suspendedRow = await findRowInPaginatedList(page, targetLookup, 12000);
    await expect(suspendedRow).toBeVisible({ timeout: 5000 });

    const restoreBtn = suspendedRow.locator('[data-testid="row-action-restore"]');
    await expect(restoreBtn).toBeVisible({ timeout: 3000 });
    if (testMemberPid) {
      await restoreMember(adminJwt, testMemberPid);
    }

    await expect
      .poll(async () => (testMemberPid ? await getMemberStatus(adminJwt, testMemberPid) : null), {
        timeout: 10000,
        intervals: [400, 600, 800, 1000],
        message: 'test member should transition back to active after restore action',
      })
      .toBe('active');

    // --- Step 3: Verify member is back on active tab ---
    await clickTabAndWaitForLoad(page, /Active|已激活/, 5000);
    await clearListSearch(page);
    const restoredRow = await findRowInPaginatedList(page, targetLookup, 12000);
    await expect(restoredRow).toBeVisible({ timeout: 5000 });
    await expect(restoredRow.locator('[data-testid="row-action-suspend"]')).toBeVisible({
      timeout: 3000,
    });
  });

  /**
   * MM-06: i18n — field labels should use translated text, not raw keys.
   */
  test('MM-06: should display translated labels, not raw i18n keys', async ({ page }) => {
    await navigateToDynamicPage(page, 'tenant_member');

    // Check column headers are not raw i18n keys (e.g. "model.tenant_member.user_id.label")
    const headers = page.locator('thead th');
    await expect(headers.first()).toBeVisible({ timeout: 15000 });

    const headerCount = await headers.count();
    for (let i = 0; i < headerCount; i++) {
      const text = await headers.nth(i).innerText();
      const trimmed = text.trim();
      if (trimmed === '') continue;

      // i18n keys follow patterns like "model.xxx.yyy.label"
      const isRawKey = /^model\.|^field\.|^action\.|\.label$|\.placeholder$/.test(trimmed);
      expect(isRawKey).toBe(false);
    }

    // Check tab labels are not raw keys
    const tabs = page.locator('nav[aria-label="Tabs"] button');
    const tabCount = await tabs.count();
    for (let i = 0; i < tabCount; i++) {
      const tabText = await tabs.nth(i).innerText();
      const trimmedTab = tabText.trim();
      expect(trimmedTab.length).toBeGreaterThan(0);
      const isRawTabKey = /^model\.|^tab\.|^status\./.test(trimmedTab);
      expect(isRawTabKey).toBe(false);
    }
  });

  test('MM-07: imports a no-email account through the visible workbook flow', async ({
    page,
    browser,
  }, testInfo) => {
    testInfo.setTimeout(90_000);
    const suffix = uniqueId('MM07')
      .replace(/[^A-Za-z0-9]/g, '')
      .slice(-14);
    const name = `导入成员${suffix}`;
    const loginName = `mm07_${suffix}`;
    const employeeCode = `EMP-${suffix}`;
    const departmentCode = `DEPT-${suffix}`;
    const positionCode = `POS-${suffix}`;

    const departmentResponse = await page.request.post('/api/org/departments', {
      data: {
        org_dept_name: `导入销售部${suffix}`,
        org_dept_code: departmentCode,
        org_dept_status: 'active',
        org_dept_order: 1,
      },
    });
    expect(departmentResponse.ok()).toBe(true);
    const departmentPid = String((await departmentResponse.json())?.data?.pid ?? '');
    expect(departmentPid).toBeTruthy();

    const positionResponse = await page.request.post('/api/dynamic/org_position/create', {
      data: {
        org_pos_name: `销售岗位${suffix}`,
        org_pos_code: positionCode,
        org_pos_level: 'P1',
        org_pos_status: 'active',
        org_pos_dept_id: departmentPid,
      },
    });
    expect(positionResponse.ok()).toBe(true);
    const positionBody = await positionResponse.json();
    const positionPid = String(positionBody?.data?.pid ?? positionBody?.data?.data?.pid ?? '');
    expect(positionPid).toBeTruthy();

    await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
    await ensureSidebarExpanded(page);
    const memberLink = page.locator('nav a[href="/p/tenant_member"]').first();
    if (!(await memberLink.isVisible({ timeout: 2_000 }).catch(() => false))) {
      await page
        .locator('nav button')
        .filter({ hasText: /组织管理|Organization/ })
        .first()
        .click();
    }
    await expect(memberLink).toBeVisible({ timeout: 10_000 });
    await memberLink.click();
    await expect(page).toHaveURL(/\/p\/tenant_member/);
    await waitForDynamicPageLoad(page);

    await page.getByTestId('member-import-entry').click();
    await expect(page.getByTestId('member-import-dialog')).toBeVisible();

    const templateDownloadPromise = page.waitForEvent('download');
    await page.getByTestId('member-import-download-template').click();
    const templateDownload = await templateDownloadPromise;
    expect(templateDownload.suggestedFilename()).toMatch(/\.xlsx$/);
    const templatePath = testInfo.outputPath('用户导入模板.xlsx');
    await templateDownload.saveAs(templatePath);
    const templateBytes = await readFile(templatePath);
    expect(Array.from(templateBytes.subarray(0, 2))).toEqual([0x50, 0x4b]);
    const templateEntries = new Set(
      new AdmZip(templateBytes).getEntries().map((entry) => entry.entryName),
    );
    expect(templateEntries).toContain('xl/workbook.xml');
    expect(templateEntries).toContain('xl/worksheets/sheet1.xml');

    const workbook = XLSX.read(templateBytes, { type: 'buffer' });
    expect(workbook.SheetNames).toContain('账号导入');
    const templateRows = XLSX.utils.sheet_to_json<unknown[]>(workbook.Sheets['账号导入'], {
      header: 1,
      raw: false,
    });
    expect(templateRows[1]?.slice(0, 6)).toEqual([
      '姓名*',
      '登录名',
      '手机号',
      '工号',
      '部门编码',
      '岗位编码',
    ]);

    workbook.Sheets['账号导入'] = XLSX.utils.aoa_to_sheet([
      ['填写说明：姓名必填；登录名为空时默认使用姓名。'],
      ['姓名*', '登录名', '手机号', '工号', '部门编码', '岗位编码'],
      [name, loginName, '13800138000', employeeCode, departmentCode, positionCode],
    ]);
    const uploadBytes = XLSX.write(workbook, { type: 'buffer', bookType: 'xlsx' }) as Buffer;
    await page.getByTestId('member-import-file-input').setInputFiles({
      name: 'employee-account-import.xlsx',
      mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      buffer: uploadBytes,
    });

    const previewResponsePromise = page.waitForResponse(
      (response) =>
        response.url().includes('/api/admin/users/employee-accounts/import/preview') &&
        response.request().method() === 'POST',
    );
    await page.getByTestId('member-import-preview').click();
    const previewResponse = await previewResponsePromise;
    expect(previewResponse.ok()).toBe(true);
    const previewBody = await previewResponse.json();
    expect(previewBody?.code).toBe('0');
    expect(previewBody?.data?.errorCount).toBe(0);
    expect(previewBody?.data?.rows?.[0]?.action).toBe('CREATE_EMPLOYEE');
    await expect(page.getByTestId('member-import-preview-result')).toContainText(employeeCode);
    await expect(page.getByTestId('member-import-confirm')).toBeEnabled();

    const importResponsePromise = page.waitForResponse(
      (response) =>
        response.url().endsWith('/api/admin/users/employee-accounts/import') &&
        response.request().method() === 'POST',
    );
    const memberListReloadPromise = page.waitForResponse(
      (response) =>
        response.url().includes('/api/dynamic/tenant_member/list') &&
        response.request().method() === 'GET',
    );
    await page.getByTestId('member-import-confirm').click();
    const importResponse = await importResponsePromise;
    expect(importResponse.ok()).toBe(true);
    const importBody = await importResponse.json();
    expect(importBody?.code).toBe('0');
    const account = importBody?.data?.accounts?.[0];
    expect(account?.userName).toBe(loginName);
    expect(account?.initialPassword).toMatch(/^jjzz@\d{4}$/);
    expect(account?.assignedRoles).toEqual([]);
    expect(account?.mustChangePassword).toBe(false);
    expect(account?.organizationAction).toBe('CREATED');
    expect(account?.memberPid).toBeTruthy();
    expect(account?.employeePid).toBeTruthy();
    const memberListReload = await memberListReloadPromise;
    expect(memberListReload.ok()).toBe(true);
    await expect(page.getByTestId('member-import-credential-row')).toContainText(loginName);
    await expect(page.getByTestId('member-import-credential-row')).toContainText(
      account.initialPassword,
    );

    const credentialDownloadPromise = page.waitForEvent('download');
    await page.getByTestId('member-import-download-credentials').click();
    const credentialDownload = await credentialDownloadPromise;
    const credentialPath = testInfo.outputPath('employee-account-credentials.csv');
    await credentialDownload.saveAs(credentialPath);
    const credentialCsv = await readFile(credentialPath, 'utf8');
    expect(credentialCsv).toContain(loginName);
    expect(credentialCsv).toContain(account.initialPassword);

    const employees = await queryFilteredList(page, 'org_employee', 'org_emp_code', employeeCode, {
      operator: 'EQ',
    });
    expect(employees).toHaveLength(1);
    expect(employees[0]?.pid).toBe(account.employeePid);
    expect(employees[0]?.org_emp_member_id).toBe(account.memberPid);
    expect(employees[0]?.org_emp_dept_id).toBe(departmentPid);
    expect(employees[0]?.org_emp_position_id).toBe(positionPid);

    const credentialDialog = page.getByTestId('member-import-dialog');
    await expect(credentialDialog).toBeVisible();
    await expect(page.getByTestId('member-import-credential-row')).toContainText(loginName);
    await credentialDialog.screenshot({
      path: testInfo.outputPath('member-import-credentials.png'),
    });

    const accountContext = await browser.newContext({ storageState: { cookies: [], origins: [] } });
    const accountPage = await accountContext.newPage();
    try {
      await loginWithIdentifier(accountPage, loginName, account.initialPassword);
      await expect(accountPage).not.toHaveURL(/\/login/);
      expect(accountPage.url()).not.toContain('forceChangePassword=true');
    } finally {
      await accountContext.close();
    }

    await credentialDialog
      .getByRole('button', { name: /Close|关闭/ })
      .last()
      .click();
    await expect(credentialDialog).toBeHidden();
    await page.getByTestId('member-import-entry').click();
    await expect(page.getByTestId('member-import-dialog')).toBeVisible();
    await expect(page.getByTestId('member-import-file-input')).toBeVisible();
    await expect(page.getByTestId('member-import-credential-row')).toHaveCount(0);
    await expect(page.getByTestId('member-import-download-credentials')).toHaveCount(0);
    await expect(page.getByText(account.initialPassword, { exact: true })).toHaveCount(0);
  });

  test('MM-08: blocks import when an organization code cannot be resolved', async ({ page }) => {
    const suffix = uniqueId('MM08')
      .replace(/[^A-Za-z0-9]/g, '')
      .slice(-14);
    const missingDepartmentCode = `MISSING-${suffix}`;
    const workbook = XLSX.utils.book_new();
    XLSX.utils.book_append_sheet(
      workbook,
      XLSX.utils.aoa_to_sheet([
        ['填写说明：姓名必填；登录名为空时默认使用姓名。'],
        ['姓名*', '登录名', '手机号', '工号', '部门编码', '岗位编码'],
        [`错误成员${suffix}`, `mm08_${suffix}`, '', '', missingDepartmentCode, ''],
      ]),
      '账号导入',
    );
    const uploadBytes = XLSX.write(workbook, { type: 'buffer', bookType: 'xlsx' }) as Buffer;

    await navigateToDynamicPage(page, 'tenant_member');
    await page.getByTestId('member-import-entry').click();
    await page.getByTestId('member-import-file-input').setInputFiles({
      name: 'employee-account-import-invalid.xlsx',
      mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      buffer: uploadBytes,
    });

    const previewResponsePromise = page.waitForResponse(
      (response) =>
        response.url().includes('/api/admin/users/employee-accounts/import/preview') &&
        response.request().method() === 'POST',
    );
    await page.getByTestId('member-import-preview').click();
    const previewResponse = await previewResponsePromise;
    expect(previewResponse.ok()).toBe(true);
    const previewBody = await previewResponse.json();
    expect(previewBody?.code).toBe('0');
    expect(previewBody?.data?.errorCount).toBe(1);
    expect(previewBody?.data?.rows?.[0]?.action).toBe('ERROR');
    await expect(page.getByTestId('member-import-preview-result')).toContainText(
      missingDepartmentCode,
    );
    await expect(page.getByTestId('member-import-confirm')).toBeDisabled();
  });

  test('MM-09: reports duplicate login names and employee codes before commit', async ({
    page,
  }) => {
    const suffix = uniqueId('MM09')
      .replace(/[^A-Za-z0-9]/g, '')
      .slice(-14);
    const organization = await createImportOrganizationFixture(page, suffix);
    const duplicateLogin = `mm09_${suffix}`;
    const duplicateEmployeeCode = `EMP-${suffix}`;

    await openMemberImportDialog(page);
    const previewBody = await uploadAndPreviewMemberImport(page, [
      [`重复登录甲${suffix}`, duplicateLogin, '', '', '', ''],
      [`重复登录乙${suffix}`, duplicateLogin, '', '', '', ''],
      [
        `重复工号甲${suffix}`,
        `mm09_emp_a_${suffix}`,
        '',
        duplicateEmployeeCode,
        organization.departmentCode,
        organization.positionCode,
      ],
      [
        `重复工号乙${suffix}`,
        `mm09_emp_b_${suffix}`,
        '',
        duplicateEmployeeCode,
        organization.departmentCode,
        organization.positionCode,
      ],
    ]);

    expect(previewBody?.data?.errorCount).toBe(2);
    const errors = previewBody.data.rows.flatMap((row: any) => row.errors ?? []);
    expect(errors).toContain(`Duplicate login name in workbook: ${duplicateLogin}`);
    expect(errors).toContain(`Duplicate employee code in workbook: ${duplicateEmployeeCode}`);
    await expect(page.getByTestId('member-import-preview-result')).toContainText(
      'Duplicate login name in workbook',
    );
    await expect(page.getByTestId('member-import-preview-result')).toContainText(
      'Duplicate employee code in workbook',
    );
    await expect(page.getByTestId('member-import-confirm')).toBeDisabled();
  });

  test('MM-10: links an existing unbound employee by unique organization codes', async ({
    page,
  }) => {
    const suffix = uniqueId('MM10')
      .replace(/[^A-Za-z0-9]/g, '')
      .slice(-14);
    const organization = await createImportOrganizationFixture(page, suffix);
    const employeeCode = `EMP-${suffix}`;
    const loginName = `mm10_${suffix}`;
    const employeeResponse = await page.request.post('/api/dynamic/org_employee/create', {
      data: {
        org_emp_name: `已有人员${suffix}`,
        org_emp_code: employeeCode,
        org_emp_dept_id: organization.departmentPid,
        org_emp_position_id: organization.positionPid,
        org_emp_status: 'active',
        org_emp_type: 'human',
      },
    });
    expect(employeeResponse.ok()).toBe(true);
    const employeeBody = await employeeResponse.json();
    const employeePid = String(employeeBody?.data?.pid ?? employeeBody?.data?.data?.pid ?? '');
    expect(employeePid).toBeTruthy();

    await openMemberImportDialog(page);
    const previewBody = await uploadAndPreviewMemberImport(page, [
      [
        `已有人员${suffix}`,
        loginName,
        '',
        employeeCode,
        organization.departmentCode,
        organization.positionCode,
      ],
    ]);
    expect(previewBody?.data?.errorCount).toBe(0);
    expect(previewBody?.data?.rows?.[0]?.action).toBe('LINK_EXISTING_EMPLOYEE');
    await expect(page.getByTestId('member-import-confirm')).toBeEnabled();

    const importResponsePromise = page.waitForResponse(
      (response) =>
        response.url().endsWith('/api/admin/users/employee-accounts/import') &&
        response.request().method() === 'POST',
    );
    await page.getByTestId('member-import-confirm').click();
    const importResponse = await importResponsePromise;
    expect(importResponse.ok()).toBe(true);
    const importBody = await importResponse.json();
    const account = importBody?.data?.accounts?.[0];
    expect(account?.userName).toBe(loginName);
    expect(account?.organizationAction).toBe('LINKED');
    expect(account?.employeePid).toBe(employeePid);

    const employees = await queryFilteredList(page, 'org_employee', 'org_emp_code', employeeCode, {
      operator: 'EQ',
    });
    expect(employees).toHaveLength(1);
    expect(employees[0]?.pid).toBe(employeePid);
    expect(employees[0]?.org_emp_member_id).toBe(account.memberPid);
  });

  test('MM-11: blocks linked employees and positions outside the selected department', async ({
    page,
  }) => {
    const suffix = uniqueId('MM11')
      .replace(/[^A-Za-z0-9]/g, '')
      .slice(-14);
    const sales = await createImportOrganizationFixture(page, `${suffix}A`);
    const support = await createImportOrganizationFixture(page, `${suffix}B`);
    const linkedEmployeeCode = `EMP-LINKED-${suffix}`;
    const seedResponse = await page.request.post('/api/admin/users/employee-accounts', {
      data: {
        employees: [
          {
            name: `已关联人员${suffix}`,
            userName: `mm11_seed_${suffix}`,
            employeeCode: linkedEmployeeCode,
            departmentCode: sales.departmentCode,
            positionCode: sales.positionCode,
          },
        ],
      },
    });
    expect(seedResponse.ok(), await seedResponse.text()).toBe(true);

    await openMemberImportDialog(page);
    const previewBody = await uploadAndPreviewMemberImport(page, [
      [
        `岗位错配${suffix}`,
        `mm11_mismatch_${suffix}`,
        '',
        `EMP-MISMATCH-${suffix}`,
        support.departmentCode,
        sales.positionCode,
      ],
      [
        `重复关联${suffix}`,
        `mm11_linked_${suffix}`,
        '',
        linkedEmployeeCode,
        sales.departmentCode,
        sales.positionCode,
      ],
    ]);

    expect(previewBody?.data?.errorCount).toBe(2);
    const errors = previewBody.data.rows.flatMap((row: any) => row.errors ?? []);
    expect(errors).toContain(
      `Position ${sales.positionCode} does not belong to department ${support.departmentCode}`,
    );
    expect(errors).toContain(
      `Employee is already linked to a tenant member: ${linkedEmployeeCode}`,
    );
    await expect(page.getByTestId('member-import-confirm')).toBeDisabled();
  });

  test('MM-12: hides import from a baseline member and rejects the admin API', async ({
    page,
    browser,
  }) => {
    const suffix = uniqueId('MM12')
      .replace(/[^A-Za-z0-9]/g, '')
      .slice(-14);
    const restrictedUser = makeRoleUser(`member-import-${suffix}`, ['tenant_member']);
    await ensureRoleUser(page, restrictedUser);

    const { context, page: restrictedPage } = await openAsRole(
      browser,
      restrictedUser.email,
      restrictedUser.password,
    );
    try {
      await restrictedPage.goto('/p/tenant_member', { waitUntil: 'domcontentloaded' });
      await expect(restrictedPage.getByRole('banner')).toBeVisible({ timeout: 30_000 });
      await expect(restrictedPage.getByTestId('member-import-entry')).toHaveCount(0);

      const deniedResponse = await restrictedPage.request.post(
        '/api/admin/users/employee-accounts/import/preview',
        {
          multipart: {
            file: {
              name: 'employee-accounts.xlsx',
              mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
              buffer: buildMemberImportWorkbook([
                [`无权限成员${suffix}`, `mm12_${suffix}`, '', '', '', ''],
              ]),
            },
          },
        },
      );
      const deniedText = await deniedResponse.text();
      expect(deniedResponse.status(), deniedText).toBe(200);
      const deniedBody = JSON.parse(deniedText);
      expect(deniedBody?.code).toBe('409');
      expect(deniedBody?.message).toBe('admin role required');
    } finally {
      await context.close();
    }
  });
});

async function loginWithIdentifier(
  page: import('@playwright/test').Page,
  identifier: string,
  password: string,
) {
  await page.goto('/login', { waitUntil: 'domcontentloaded' });
  await expect(page.getByTestId('login-page-root')).toHaveAttribute('data-hydrated', 'true', {
    timeout: 5_000,
  });
  const passwordTab = page.getByTestId('login-tab-email_password');
  if (await passwordTab.isVisible().catch(() => false)) {
    await passwordTab.click();
  }
  const identifierInput = page.locator('#identifier');
  await identifierInput.fill(identifier);
  await expect(identifierInput).toHaveValue(identifier);
  const passwordInput = page.locator('#password');
  await passwordInput.fill(password);
  await expect(passwordInput).toHaveValue(password);

  const loginResponsePromise = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/login' && response.request().method() === 'POST',
  );
  const loginNavigationPromise = page.waitForURL((url) => !url.pathname.includes('/login'), {
    timeout: 20_000,
    waitUntil: 'domcontentloaded',
  });
  await passwordInput.press('Enter');
  const [loginResponse] = await Promise.all([loginResponsePromise, loginNavigationPromise]);
  expect(loginResponse.status()).toBe(302);
}
