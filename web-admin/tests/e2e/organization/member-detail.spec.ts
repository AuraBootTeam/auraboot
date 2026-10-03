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
import { uniqueId, navigateToDynamicPage, acceptConfirmDialog } from '../helpers';
import { BASE_URL } from '../../helpers/playwright-env';
const MEMBER_PAGE_KEY = 'tenant-member';
test.use({ locale: 'zh-CN' });

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
    await page.evaluate(() => {
      localStorage.removeItem('sidebar-collapsed');
    });

    const listResp = page
      .waitForResponse(
        (r) => r.url().includes('/api/dynamic/tenant-member') && r.status() === 200,
        { timeout: 10000 },
      )
      .catch(() => null);
    await page.goto('/p/tenant_member', { waitUntil: 'domcontentloaded' });
    await listResp;

    const nav = page.locator('nav');

    // 组织管理 parent menu label should be visible in the expanded sidebar submenu button
    // (SidebarSubmenu renders it as a <button> with the menu name)
    const orgGroupBtn = nav
      .locator('button')
      .filter({ hasText: /组织管理/ })
      .first();
    await expect(orgGroupBtn).toBeVisible({ timeout: 10000 });

    // Members should be visible under Organization
    const membersLink = nav.locator('a[href="/p/tenant_member"]');
    await expect(membersLink).toBeVisible();

    // Departments, Positions, Teams should be visible
    await expect(nav.locator('a[href="/p/org_department"]')).toBeVisible();
    await expect(nav.locator('a[href="/p/org_position"]')).toBeVisible();
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
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
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
    const statusText = await statusBadge.getAttribute('data-status');
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
    const hasOrgData = (await tabContent.locator('dl').count()) > 0;
    const hasEmptyState =
      (await tabContent.getByText(/No organization info|暂无组织信息/i).count()) > 0;
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

    const hasTeamTable = (await tabContent.locator('table').count()) > 0;
    const hasEmptyState =
      (await tabContent.getByText(/Not a member of any team|暂未加入任何团队/i).count()) > 0;
    expect(hasTeamTable || hasEmptyState).toBeTruthy();
  });

  test('MEMBER-DETAIL-05: action buttons match status', async ({ page }) => {
    await page.goto(`/organization/members/${memberPid}`, { waitUntil: 'domcontentloaded' });

    const actionBar = page.locator('[data-testid="action-bar"]');
    await expect(actionBar).toBeVisible({ timeout: 10000 });

    const statusBadge = page.locator('[data-testid="member-status"]');
    const status = await statusBadge.getAttribute('data-status');

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

    await expect(page).toHaveURL(/\/p\/tenant_member/, { timeout: 10000 });
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

// Dedicated records are created through the public admin API as fixtures. Every
// lifecycle mutation below is driven by the native detail button, never by API fallback.
test('MEMBER-DETAIL-07: native lifecycle buttons execute commands and persist state @critical', async ({
  page,
}, testInfo) => {
  test.setTimeout(120000);
  const stamp = uniqueId('native-member');
  const email = `${stamp}@e2e.local`;
  const created = await page.request.post('/api/admin/users', {
    data: {
      email,
      displayName: stamp,
      initialPassword: 'MemberE2e!2026',
      roleCodes: ['crm_sales'],
      sendInviteEmail: false,
    },
  });
  expect(created.ok()).toBe(true);
  expect((await created.json()).code).toBe('0');
  const found = await page.request.post('/api/tenant/members/search', {
    data: { keyword: email, pageNum: 1, pageSize: 50 },
  });
  expect(found.ok()).toBe(true);
  const body = await found.json();
  expect(body.code).toBe('0');
  const rows = body.data.records as Array<{ pid: string; status: string; user: { email: string } }>;
  const member = rows.find((row) => row.user?.email === email);
  expect(member, 'newly provisioned lifecycle fixture').toBeDefined();
  const pid = member!.pid;
  expect(member!.status).toBe('active');

  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
  await page.evaluate(() => localStorage.removeItem('sidebar-collapsed'));
  await page.reload();
  const membersLink = page.locator('nav a[href="/p/tenant_member"]');
  if (!(await membersLink.isVisible())) {
    await page.locator('nav button').filter({ hasText: '组织管理' }).first().click();
  }
  await expect(membersLink).toBeVisible();
  await membersLink.click();
  await expect(page).toHaveURL(/\/p\/tenant_member/);
  const row = page.locator('table tbody tr').filter({ hasText: stamp });
  await expect(row).toHaveCount(1, { timeout: 20000 });
  await expect(row).toBeVisible();
  await row.click();
  await expect(page).toHaveURL(new RegExp(`/organization/members/${pid}$`));
  await expect(page.getByTestId('member-status')).toHaveAttribute('data-status', 'active');
  await expect(page.getByTestId('member-status')).toHaveText('已激活');

  const capture = async (name: string) => {
    const path = testInfo.outputPath(`${name}.png`);
    await page.screenshot({ path, fullPage: true });
    await testInfo.attach(name, { path, contentType: 'image/png' });
  };
  await capture('native-member-active');
  const act = async (
    label: string,
    command: string,
    status?: string,
    offboardingAction?: string,
  ) => {
    const commandResponse = page.waitForResponse(
      (response) =>
        response.url().endsWith(`/api/meta/commands/execute/${command}`) &&
        response.request().method() === 'POST',
    );
    const impactResponse = offboardingAction
      ? page.waitForResponse((response) => {
          const url = new URL(response.url());
          return (
            url.pathname === `/api/tenant/members/${pid}/offboarding-impact` &&
            url.searchParams.get('action') === offboardingAction
          );
        })
      : null;
    await page.getByTestId('action-bar').getByRole('button', { name: label, exact: true }).click();
    if (impactResponse) {
      const impact = await impactResponse;
      expect(impact.ok()).toBe(true);
      const result = await impact.json();
      expect(result.code).toBe('0');
      expect(result.data.transferRequired, 'fixture owns no resources').toBe(false);
    }
    if (command === 'admin:suspend_member' || command === 'admin:leave_member') {
      await expect(page.getByTestId('form-dialog')).toBeVisible();
      await expect(page.getByTestId('form-dialog').getByRole('heading')).toHaveText(
        command === 'admin:suspend_member' ? '暂停成员' : '办理离职',
      );
      await page.getByTestId('form-dialog-field-reason').fill(`E2E ${command}`);
      await capture(`${command.split(':')[1]}-input`);
      await page.getByTestId('form-dialog-submit').click();
    } else {
      await expect(page.getByTestId('confirm-dialog')).toBeVisible();
      await capture(`${command.split(':')[1]}-confirm`);
      await acceptConfirmDialog(page);
    }
    const response = await commandResponse;
    expect(response.ok()).toBe(true);
    const payload = response.request().postDataJSON();
    expect(payload.targetRecordPid).toBe(pid);
    expect((await response.json()).code).toBe('0');
    if (status) {
      await expect(page.getByTestId('member-status')).toHaveAttribute('data-status', status);
      await expect(page.getByTestId('member-status')).toHaveText(
        ({ active: '已激活', suspended: '已暂停', inactive: '已离职' } as Record<string, string>)[
          status
        ],
      );
      const persisted = await page.request.get(`/api/tenant/members/${pid}`);
      expect(persisted.ok()).toBe(true);
      const result = await persisted.json();
      expect(result.code).toBe('0');
      expect(result.data.status).toBe(status);
      await page.reload();
      await expect(page.getByTestId('member-status')).toHaveAttribute('data-status', status);
      await expect(page.getByTestId('member-status')).toHaveText(
        ({ active: '已激活', suspended: '已暂停', inactive: '已离职' } as Record<string, string>)[
          status
        ],
      );
      await capture(
        status === 'active' ? 'native-member-active-restored' : `native-member-${status}`,
      );
    }
  };
  await act('暂停', 'admin:suspend_member', 'suspended', 'suspend');
  await act('恢复', 'admin:restore_member', 'active');
  await act('离职', 'admin:leave_member', 'inactive', 'deactivate');
  await act('删除', 'admin:delete_member', undefined, 'remove');
  await expect(page).toHaveURL(/\/p\/tenant_member$/);
  const after = await page.request.post('/api/tenant/members/search', {
    data: { keyword: email, pageNum: 1, pageSize: 50 },
  });
  expect(after.ok()).toBe(true);
  const result = await after.json();
  expect(result.code).toBe('0');
  expect(result.data.records.some((entry: { pid: string }) => entry.pid === pid)).toBe(false);
  await capture('native-member-removed-list');
});
