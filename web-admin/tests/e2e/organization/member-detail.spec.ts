/**
 * Member Detail Page E2E Tests
 *
 * Tests the member detail page at /organization/members/:memberPid:
 * - MEMBER-MENU-01: Members menu under Organization, Employees hidden
 * - MEMBER-NAV-01: Row click navigates to member detail
 * - MEMBER-DETAIL-01: Detail page renders header, status, 3 tabs
 * - MEMBER-DETAIL-02: Basic Info tab shows member fields with dates
 * - MEMBER-DETAIL-03: Organization tab shows empty state or employee data
 * - MEMBER-DETAIL-04: Teams tab shows empty state or team list
 * - MEMBER-DETAIL-05: Action buttons match member status
 * - MEMBER-DETAIL-06: Back button returns to member list
 *
 * Uses real database + API, NO MOCKING.
 *
 * @since 6.5.0
 */

import { test, expect } from '../../fixtures';
import { uniqueId, navigateToDynamicPage } from '../helpers';

const BASE_URL = process.env.PLAYWRIGHT_BASE_URL ?? process.env.BASE_URL ?? `http://localhost:${process.env.VITE_PORT ?? '5173'}`;
const MEMBER_PAGE_KEY = 'tenant-member';

// ---------------------------------------------------------------------------
// Shared: get first member pid via API
// ---------------------------------------------------------------------------

async function getFirstMemberPid(page: import('@playwright/test').Page): Promise<string> {
  const resp = await page.request.post(`${BASE_URL}/api/tenant/members/search`, {
    data: { pageNum: 1, pageSize: 1 },
  });
  expect(resp.ok()).toBeTruthy();
  const body = await resp.json();
  const records = body?.data?.records;
  expect(records).toBeDefined();
  expect(records.length).toBeGreaterThan(0);
  return records[0].pid;
}

// ═══════════════════════════════════════════════════════════════════════════
// MEMBER-MENU: Sidebar menu structure
// ═══════════════════════════════════════════════════════════════════════════

test.describe('MEMBER-MENU: Sidebar Organization menu', () => {
  test('MEMBER-MENU-01: Members under Organization, Employees hidden', async ({ page }) => {
    // Ensure sidebar is in expanded (not collapsed icon-only) mode so that submenu
    // links are statically present in the nav DOM (not hidden in hover popovers).
    // Navigate to any page first, clear the sidebar-collapsed flag, then navigate
    // to the member page (which is in org_management submenu) so 组织管理 auto-expands.
    await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
    await page.evaluate(() => { localStorage.removeItem('sidebar-collapsed'); });

    const listResp = page.waitForResponse(
      (r) => r.url().includes('/api/dynamic/tenant-member') && r.status() === 200,
      { timeout: 10000 },
    ).catch(() => null);
    await page.goto('/dynamic/tenant-member', { waitUntil: 'domcontentloaded' });
    await listResp;

    const nav = page.locator('nav');

    // 组织管理 parent menu label should be visible in the expanded sidebar submenu button
    // (SidebarSubmenu renders it as a <button> with the menu name)
    const orgGroupBtn = nav.locator('button').filter({ hasText: /组织管理/ }).first();
    await expect(orgGroupBtn).toBeVisible({ timeout: 10000 });

    // Members should be visible under Organization
    const membersLink = nav.locator('a[href="/dynamic/tenant-member"]');
    await expect(membersLink).toBeVisible();

    // Departments, Positions, Teams should be visible
    await expect(nav.locator('a[href="/dynamic/org-department"]')).toBeVisible();
    await expect(nav.locator('a[href="/dynamic/org-position"]')).toBeVisible();
    await expect(nav.locator('a[href="/organization/teams"]')).toBeVisible();

    // Employees should NOT be visible (hidden menu item)
    const employeesLink = nav.locator('a[href="/dynamic/org-employee"]');
    // In expanded mode the link is either absent or hidden via CSS; in collapsed mode it may still
    // render but as an icon without visible text. Check it's not a visible text link.
    const isEmpVisible = await employeesLink.isVisible({ timeout: 2000 }).catch(() => false);
    expect(isEmpVisible, 'org_employee menu should be hidden').toBe(false);
  });
});

// ═══════════════════════════════════════════════════════════════════════════
// MEMBER-NAV: Row click navigation
// ═══════════════════════════════════════════════════════════════════════════

test.describe('MEMBER-NAV: List row click → detail', () => {
  test('MEMBER-NAV-01: clicking a member row navigates to detail page', async ({ page }) => {
    await navigateToDynamicPage(page, MEMBER_PAGE_KEY);

    // Wait for table rows to be rendered
    const rows = page.locator('table tbody tr');
    await rows.first().waitFor({ state: 'visible', timeout: 10000 });
    const rowCount = await rows.count();
    expect(rowCount).toBeGreaterThan(0);

    // Click the first row
    await rows.first().click();

    // Should navigate to /organization/members/:pid
    await expect(page).toHaveURL(/\/organization\/members\//, { timeout: 10000 });
  });
});

// ═══════════════════════════════════════════════════════════════════════════
// MEMBER-DETAIL: Detail page content
// ═══════════════════════════════════════════════════════════════════════════

test.describe('MEMBER-DETAIL: Detail page', () => {
  let memberPid: string;

  test.beforeAll(async ({ browser }) => {
    const context = await browser.newContext({
      storageState: 'tests/storage/admin.json',
    });
    const page = await context.newPage();
    memberPid = await getFirstMemberPid(page);
    await page.close();
    await context.close();
  });

  test('MEMBER-DETAIL-01: renders header with name, status, avatar', async ({ page }) => {
    await page.goto(`/organization/members/${memberPid}`, { waitUntil: 'domcontentloaded' });

    // Wait for member name to appear
    const memberName = page.locator('[data-testid="member-name"]');
    await expect(memberName).toBeVisible({ timeout: 10000 });
    const nameText = await memberName.textContent();
    expect(nameText).toBeTruthy();
    expect(nameText!.length).toBeGreaterThan(0);

    // Status badge visible
    const statusBadge = page.locator('[data-testid="member-status"]');
    await expect(statusBadge).toBeVisible();
    const statusText = await statusBadge.textContent();
    expect(['active', 'pending', 'suspended', 'rejected', 'inactive']).toContain(statusText);
  });

  test('MEMBER-DETAIL-02: Basic Info tab shows member fields', async ({ page }) => {
    await page.goto(`/organization/members/${memberPid}`, { waitUntil: 'domcontentloaded' });

    // Wait for tab content to load
    const tabContent = page.locator('[data-testid="tab-content"]');
    await expect(tabContent).toBeVisible({ timeout: 10000 });

    // Basic info tab should be active by default
    const basicTab = page.locator('[data-testid="tab-basic"]');
    await expect(basicTab).toBeVisible();

    // Should show key fields
    await expect(tabContent).toContainText(/Email|邮箱/i);
    await expect(tabContent).toContainText(/Status|状态/i);
    await expect(tabContent).toContainText(/Join Date|加入日期/i);
  });

  test('MEMBER-DETAIL-03: Organization tab renders', async ({ page }) => {
    await page.goto(`/organization/members/${memberPid}`, { waitUntil: 'domcontentloaded' });

    // Click Organization tab
    const orgTab = page.locator('[data-testid="tab-org"]');
    await expect(orgTab).toBeVisible({ timeout: 10000 });
    await orgTab.click();

    // Should show either employee info or "no organization info" empty state
    const tabContent = page.locator('[data-testid="tab-content"]');
    await expect(tabContent).toBeVisible();

    // Either has employee data fields or empty state message
    const hasOrgData = await tabContent.locator('dl').count() > 0;
    const hasEmptyState = await tabContent.getByText(/No organization info|暂无组织信息/i).count() > 0;
    expect(hasOrgData || hasEmptyState).toBeTruthy();
  });

  test('MEMBER-DETAIL-04: Teams tab renders', async ({ page }) => {
    await page.goto(`/organization/members/${memberPid}`, { waitUntil: 'domcontentloaded' });

    // Click Teams tab
    const teamsTab = page.locator('[data-testid="tab-teams"]');
    await expect(teamsTab).toBeVisible({ timeout: 10000 });
    await teamsTab.click();

    // Should show either team table or "not a member of any team" empty state
    const tabContent = page.locator('[data-testid="tab-content"]');
    await expect(tabContent).toBeVisible();

    const hasTeamTable = await tabContent.locator('table').count() > 0;
    const hasEmptyState = await tabContent.getByText(/Not a member of any team|暂未加入任何团队/i).count() > 0;
    expect(hasTeamTable || hasEmptyState).toBeTruthy();
  });

  test('MEMBER-DETAIL-05: action buttons match status', async ({ page }) => {
    await page.goto(`/organization/members/${memberPid}`, { waitUntil: 'domcontentloaded' });

    const actionBar = page.locator('[data-testid="action-bar"]');
    await expect(actionBar).toBeVisible({ timeout: 10000 });

    const statusBadge = page.locator('[data-testid="member-status"]');
    const status = await statusBadge.textContent();

    // Delete button always present
    await expect(actionBar.getByText(/Delete|删除/)).toBeVisible();

    if (status === 'pending') {
      await expect(actionBar.getByText(/Approve|审批通过/)).toBeVisible();
      await expect(actionBar.getByText(/Reject|拒绝/)).toBeVisible();
    } else if (status === 'active') {
      await expect(actionBar.getByText(/Suspend|暂停/)).toBeVisible();
      await expect(actionBar.getByText(/Leave|离职/)).toBeVisible();
    } else if (status === 'suspended' || status === 'rejected') {
      await expect(actionBar.getByText(/Restore|恢复/)).toBeVisible();
    }
  });

  test('MEMBER-DETAIL-06: back button returns to member list', async ({ page }) => {
    await page.goto(`/organization/members/${memberPid}`, { waitUntil: 'domcontentloaded' });

    const backBtn = page.locator('[data-testid="back-btn"]');
    await expect(backBtn).toBeVisible({ timeout: 10000 });
    await backBtn.click();

    await expect(page).toHaveURL(/\/dynamic\/tenant-member/, { timeout: 10000 });
  });
});

// ═══════════════════════════════════════════════════════════════════════════
// MEMBER-API: Teams API via member endpoint
// ═══════════════════════════════════════════════════════════════════════════

test.describe('MEMBER-API: Teams endpoint', () => {
  test('MEMBER-API-01: /api/tenant/members/:pid/teams returns 200', async ({ page }) => {
    const memberPid = await getFirstMemberPid(page);

    const resp = await page.request.get(`${BASE_URL}/api/tenant/members/${memberPid}/teams`);
    expect(resp.ok()).toBeTruthy();

    const body = await resp.json();
    expect(body.code).toBe('0');
    expect(Array.isArray(body.data)).toBeTruthy();
  });
});
