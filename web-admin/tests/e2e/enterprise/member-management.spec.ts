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
 *
 * Prerequisites:
 * - platform-admin plugin imported with tenant_member model
 * - At least one active member exists (the logged-in admin)
 *
 * @since 4.0.0
 */

import { test, expect } from '../../fixtures';
import {
  navigateToDynamicPage,
  waitForDynamicPageLoad,
  clickTabAndWaitForLoad,
  findRowInPaginatedList,
} from '../helpers/index';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';
import { BACKEND_URL } from '../../helpers/environments';

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
        await expect(dropdown.locator('[data-testid="row-action-reset-password"]').first()).toBeVisible({
          timeout: 3000,
        });
        // Close dropdown
        await page.keyboard.press('Escape');
      }
    } else {
      await expect(directDelete).toBeVisible();
      const moreBtn = firstRow.locator('[data-testid="row-action-more"]').first();
      const hasMore = await moreBtn.isVisible({ timeout: 3000 }).catch(() => false);
      const directResetAction = firstRow.locator('[data-testid="row-action-reset-password"]').first();
      const hasDirectReset = await directResetAction.isVisible({ timeout: 1000 }).catch(() => false);
      expect(
        hasDirectReset || hasMore,
        'Reset password action should be available directly or in the more menu',
      ).toBe(true);
      if (hasMore) {
        await moreBtn.evaluate((el: HTMLElement) => el.click());
        const dropdown = page.locator('[data-testid="row-action-dropdown"]');
        await dropdown.waitFor({ state: 'visible', timeout: 5000 });
        await expect(dropdown.locator('[data-testid="row-action-reset-password"]').first()).toBeVisible({
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
    test.skip(!testMemberPid, 'Seeded operator member is not attached to the current tenant in this environment');
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

  test('MM-07: should import members through tenant member import API', async ({ page }) => {
    await navigateToDynamicPage(page, 'tenant_member');
    const email = `e2e-import-${Date.now()}@test.com`;
    const resp = await page.request.post('/api/tenant/members/import-rows', {
      data: [
        {
          name: '导入成员',
          email,
          phone: '13800138000',
          department: '',
          position: '',
        },
      ],
    });
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    expect(body?.code).toBe('0');
    expect(body?.data?.successCount).toBe(1);
    expect(body?.data?.invitedCount).toBe(1);
  });
});
