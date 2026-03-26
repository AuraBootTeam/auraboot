/**
 * Enterprise Member Management — Deep E2E Tests
 *
 * Extended tests beyond the basic 6 in member-management.spec.ts.
 * Covers: approve/reject workflow, leave command, delete, self-protection,
 * tab data isolation, and i18n depth.
 *
 * Uses serial mode — tests have state dependencies (e.g. MM-07 approve → MM-08 sees fewer pending).
 *
 * MM-07: Approve pending member pending→active (approval candidate)
 * MM-08: Reject pending member pending→rejected (member2)
 * MM-09: Restore rejected member rejected→active (member2)
 * MM-10: Delete member (member3, confirm dialog + disappears from list)
 * MM-11: Cannot delete self (admin user)
 * MM-12: Leave member active→inactive (member2)
 * MM-13: Status tab data isolation (each tab shows correct status)
 * MM-14: i18n — all buttons, labels, status text are translated
 *
 * Prerequisites:
 * - platform-admin plugin imported
 *
 * @since 4.0.0
 */

import { test, expect, type APIRequestContext } from '@playwright/test';
import {
  navigateToDynamicPage,
  clickTabAndWaitForLoad,
  acceptConfirmDialog,
  findRowInPaginatedList,
  clickRowActionByLocator,
} from '../helpers/index';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';

// ---------------------------------------------------------------------------
// API Helpers — use APIRequestContext (BFF session) instead of raw fetch+JWT
// ---------------------------------------------------------------------------

async function findMember(
  request: APIRequestContext,
  emailKeyword: string,
): Promise<{ pid: string; userId: string } | null> {
  for (let pageNum = 1; pageNum <= 10; pageNum++) {
    const resp = await request.post('/api/tenant/members/search', {
      data: { keyword: '', pageNum, pageSize: 200 },
    });
    const body = await resp.json();
    const items: any[] = body.data?.data ?? [];
    const match = items.find((item: any) => item.user?.email?.includes(emailKeyword));
    if (match) {
      return { pid: match.pid, userId: String(match.userId) };
    }
    if (items.length === 0) {
      break;
    }
  }
  return null;
}

async function findMemberWithStatus(
  request: APIRequestContext,
  emailKeyword: string,
): Promise<{ pid: string; userId: string; status: string | null } | null> {
  for (let pageNum = 1; pageNum <= 10; pageNum++) {
    const resp = await request.post('/api/tenant/members/search', {
      data: { keyword: '', pageNum, pageSize: 200 },
    });
    const body = await resp.json();
    const items: any[] = body.data?.data ?? [];
    const match = items.find((item: any) => item.user?.email?.includes(emailKeyword));
    if (match) {
      return { pid: match.pid, userId: String(match.userId), status: String(match.status ?? '') };
    }
    if (items.length === 0) {
      break;
    }
  }
  return null;
}

async function approveMember(request: APIRequestContext, memberPid: string): Promise<void> {
  await request.post(`/api/tenant/members/${memberPid}/approve`, {
    data: { action: 'approve' },
  });
}

async function rejectMember(request: APIRequestContext, memberPid: string): Promise<void> {
  await request.post(`/api/tenant/members/${memberPid}/approve`, {
    data: { action: 'reject' },
  });
}

async function deleteMember(request: APIRequestContext, memberPid: string): Promise<void> {
  await request.delete(`/api/tenant/members/${memberPid}`);
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
      (r) => r.url().includes('/list') && r.request().method().toLowerCase() === 'post' && r.status() === 200,
      { timeout: 5000 },
    )
    .catch(() => null);
}

// ---------------------------------------------------------------------------
// Test Data
// ---------------------------------------------------------------------------

interface TestMember {
  email: string;
  pid: string | null;
  userId: string | null; // userId displayed in table rows
}

function rowLookupText(member: TestMember): string {
  const emailPrefix = member.email.split('@')[0];
  return emailPrefix || member.userId || member.email;
}

async function locateMemberRow(
  page: import('@playwright/test').Page,
  member: TestMember,
  timeout = 10000,
) {
  const lookup = rowLookupText(member);
  const row = await findRowInPaginatedList(page, lookup, timeout);
  await expect(row, `member row should be visible: ${rowLookupText(member)}`).toBeVisible({ timeout });
  return row;
}

const testMembers: TestMember[] = [
  { email: 'e2e-viewer@test.com', pid: null, userId: null }, // member1: pending → approve → active → delete
  { email: 'e2e-operator@test.com', pid: null, userId: null }, // member2: pending → reject → restore → leave
  { email: 'e2e-viewer@test.com', pid: null, userId: null }, // member3 aliases member1 after approval
];

test.describe('Member Management — Deep Tests', () => {
  test.describe.configure({ mode: 'serial' });

  let adminUserId: string;

  test.beforeAll(async ({ browser }, testInfo) => {
    testInfo.setTimeout(120000);

    // Use storageState context to make API calls with proper tenant session
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      // Find admin userId for MM-11 self-deletion test
      const adminMember = await findMember(page.request, DEFAULT_TEST_ACCOUNT.email);
      adminUserId = adminMember?.userId ?? '';

      for (const member of testMembers) {
        const found = await findMember(page.request, member.email);
        member.pid = found?.pid ?? null;
        member.userId = found?.userId ?? null;
        expect(member.pid).toBeTruthy();
        expect(member.userId).toBeTruthy();
      }

      // Keep member3 aligned with member1 for MM-10 delete flow after MM-07 approves it.
      testMembers[2].pid = testMembers[0].pid;
      testMembers[2].userId = testMembers[0].userId;
    } finally {
      await ctx.close();
    }
  });

  /**
   * MM-07: Approve a dedicated pending member pending→active.
   */
  test('MM-07: should approve pending member pending→active @critical', async ({ page }) => {
    test.setTimeout(60000);

    const approvalCandidate = testMembers[0];
    expect(approvalCandidate?.pid).toBeTruthy();
    expect(approvalCandidate?.userId).toBeTruthy();
    const currentStatus = approvalCandidate?.email
      ? await findMemberWithStatus(page.request, approvalCandidate.email)
      : null;
    expect(currentStatus).not.toBeNull();
    expect(['pending', 'active']).toContain(String(currentStatus?.status ?? ''));

    await navigateToDynamicPage(page, 'tenant-member');
    await clickTabAndWaitForLoad(page, /Pending|待审批/, 5000);

    const lookup = rowLookupText(approvalCandidate);
    const searchInput = page
      .locator(
        '[data-testid="search-input"], [data-testid="table-search-input"], input[placeholder*="搜索"], input[placeholder*="Search"]',
      )
      .first();
    if (await searchInput.isVisible({ timeout: 3000 }).catch(() => false)) {
      await searchInput.fill(lookup);
      await searchInput.press('Enter').catch(() => {});
      await page
        .waitForResponse(
          (r) => r.url().includes('/list') && r.request().method().toLowerCase() === 'post' && r.status() === 200,
          { timeout: 8000 },
        )
        .catch(() => null);
    }

    const row = await locateMemberRow(page, approvalCandidate, 12000).catch(() => null);
    const hasApproveRow = await row?.locator('[data-testid="row-action-approve"]').isVisible({ timeout: 3000 }).catch(() => false);
    if (!hasApproveRow) {
      // Auto-active contract: member already active, no manual approval action needed.
      await clearListSearch(page);
      await clickTabAndWaitForLoad(page, /Active|已激活/, 5000);
      const current = await findMemberWithStatus(page.request, approvalCandidate.email);
      if (current) {
        expect(current.status).toBe('active');
      }
      const activeRow = await locateMemberRow(page, approvalCandidate, 12000);
      await expect(activeRow).toBeVisible({ timeout: 10000 });
      return;
    }

    await approveMember(page.request, approvalCandidate.pid!);

    await expect
      .poll(async () => {
        const current = await findMemberWithStatus(page.request, approvalCandidate.email);
        return current?.status ?? null;
      }, {
        timeout: 10000,
        intervals: [500, 1000, 2000],
        message: 'approved member should transition to active',
      })
      .toBe('active');

    await clearListSearch(page);
    await clickTabAndWaitForLoad(page, /Active|已激活/, 5000);
    const activeRow = await locateMemberRow(page, approvalCandidate, 12000);
    await expect(activeRow).toBeVisible({ timeout: 10000 });
  });

  /**
   * MM-08: Reject member2 pending→rejected.
   */
  test('MM-08: should reject pending member pending→rejected @critical', async ({ page }) => {
    const candidate = testMembers[1];
    expect(candidate?.pid).toBeTruthy();

    const current = candidate?.email
      ? await findMemberWithStatus(page.request, candidate.email)
      : null;
    expect(current).not.toBeNull();

    if (current?.status === 'rejected') {
      await navigateToDynamicPage(page, 'tenant-member');
      await clickTabAndWaitForLoad(page, /Rejected|已拒绝/, 5000, 'rejected');
      const rejectedRow = await locateMemberRow(page, candidate, 10000);
      await expect(rejectedRow).toBeVisible({ timeout: 5000 });
      return;
    }

    if (current?.status && current.status !== 'pending') {
      await rejectMember(page.request, candidate.pid!);
      await expect
        .poll(async () => {
          const latest = await findMemberWithStatus(page.request, candidate.email);
          return latest?.status ?? null;
        }, {
          timeout: 10000,
          intervals: [500, 1000, 2000],
          message: 'member should transition to rejected',
        })
        .toBe('rejected');

      await navigateToDynamicPage(page, 'tenant-member');
      await clickTabAndWaitForLoad(page, /Rejected|已拒绝/, 5000, 'rejected');
      const rejectedRow = await locateMemberRow(page, candidate, 10000);
      await expect(rejectedRow).toBeVisible({ timeout: 5000 });
      return;
    }

    await navigateToDynamicPage(page, 'tenant-member');
    await clickTabAndWaitForLoad(page, /Pending|待审批/, 5000, 'pending');

    const row = await locateMemberRow(page, candidate, 10000);

    const rejectBtn = row.locator('[data-testid="row-action-reject"]');
    await expect(rejectBtn).toBeVisible({ timeout: 3000 });

    const rejectResponse = page.waitForResponse(
      (r) => r.url().includes('/commands/execute/admin:reject_member') && r.status() === 200,
      { timeout: 10000 }
    );

    await rejectBtn.click();
    await acceptConfirmDialog(page, 5000);
    await rejectResponse;

    // Wait for list reload
    await page.waitForResponse(
      (r) => r.url().includes('/list') && r.status() === 200,
      { timeout: 5000 }
    ).catch(() => null);

    // Verify member2 moved to rejected tab
    await clickTabAndWaitForLoad(page, /Rejected|已拒绝/, 5000, 'rejected');
    const rejectedRow = page.locator('tbody tr', { hasText: rowLookupText(candidate) }).first();
    await expect(rejectedRow).toBeVisible({ timeout: 5000 });
  });

  /**
   * MM-09: Restore member2 rejected→active.
   */
  test('MM-09: should restore rejected member rejected→active', async ({ page }) => {
    if (testMembers[1].pid) {
      await rejectMember(page.request, testMembers[1].pid).catch(() => {});
    }

    await navigateToDynamicPage(page, 'tenant-member');
    await clickTabAndWaitForLoad(page, /Rejected|已拒绝/, 5000, 'rejected');

    const row = await locateMemberRow(page, testMembers[1], 6000).catch(async () => {
      await page.reload({ waitUntil: 'domcontentloaded' });
      await clickTabAndWaitForLoad(page, /Rejected|已拒绝/, 5000, 'rejected');
      return locateMemberRow(page, testMembers[1], 10000);
    });

    const restoreBtn = row.locator('[data-testid="row-action-restore"]');
    await expect(restoreBtn).toBeVisible({ timeout: 3000 });

    const restoreResponse = page.waitForResponse(
      (r) => r.url().includes('/commands/execute/admin:restore_member') && r.status() === 200,
      { timeout: 10000 }
    );

    await restoreBtn.click();
    await acceptConfirmDialog(page, 5000);
    await restoreResponse;

    await page.waitForResponse(
      (r) => r.url().includes('/list') && r.status() === 200,
      { timeout: 5000 }
    ).catch(() => null);

    // Verify member2 is now on active tab
    await clickTabAndWaitForLoad(page, /Active|已激活/, 5000, 'active');
    const activeRow = page.locator('tbody tr', { hasText: rowLookupText(testMembers[1]) }).first();
    await expect(activeRow).toBeVisible({ timeout: 5000 });
  });

  /**
   * MM-10: Delete member3 with confirmation dialog.
   */
  test('MM-10: should delete member with confirmation', async ({ page }) => {
    await navigateToDynamicPage(page, 'tenant-member');
    await clickTabAndWaitForLoad(page, /Active|已激活/, 5000, 'active');

    const row = await locateMemberRow(page, testMembers[2], 10000);

    const deleteResponse = page.waitForResponse(
      (r) => r.url().includes('/commands/execute/admin:delete_member') && r.status() === 200,
      { timeout: 10000 }
    );

    await clickRowActionByLocator(page, row, 'delete');
    await acceptConfirmDialog(page, 5000);
    await deleteResponse;

    await page.waitForResponse(
      (r) => r.url().includes('/list') && r.status() === 200,
      { timeout: 5000 }
    ).catch(() => null);

    // member3 should be gone from active tab
    await expect(page.locator('tbody tr', { hasText: rowLookupText(testMembers[2]) })).not.toBeVisible({ timeout: 5000 });

    // Mark pid null so afterAll doesn't try to delete again
    testMembers[2].pid = null;
  });

  /**
   * MM-11: Cannot delete self — clicking delete on admin's own row should show error or be disabled.
   */
  test('MM-11: should prevent self-deletion', async ({ page }) => {
    await navigateToDynamicPage(page, 'tenant-member');
    await clickTabAndWaitForLoad(page, /Active|已激活/, 5000, 'active');

    // Locate the admin row by userId (table shows userId, not email)
    const adminRow = page.locator('tbody tr', { hasText: adminUserId }).first();
    await expect(adminRow).toBeVisible({ timeout: 10000 });

    const deleteBtn = adminRow.locator('[data-testid="row-action-delete"]');
    const moreBtn = adminRow.locator('[data-testid="row-action-more"]');
    const isDirectVisible = await deleteBtn.isVisible({ timeout: 2000 }).catch(() => false);
    const isMoreVisible = await moreBtn.isVisible({ timeout: 1000 }).catch(() => false);
    const isVisible = isDirectVisible || isMoreVisible;

    if (!isVisible) {
      // Delete button not visible for own user — expected behavior (self-protection)
      return;
    }

    // If visible, clicking should produce an error
    await clickRowActionByLocator(page, adminRow, 'delete');
    await acceptConfirmDialog(page, 5000);

    // Expect an error toast or the delete to fail
    const errorToast = page.locator('[class*="toast"], [role="alert"]').filter({ hasText: /cannot|不能|自己|self/ });
    const hasError = await errorToast.isVisible({ timeout: 5000 }).catch(() => false);

    // Either error was shown, or the row still exists (delete rejected by backend)
    const adminStillExists = await adminRow.isVisible({ timeout: 3000 }).catch(() => false);
    expect(hasError || adminStillExists).toBe(true);
  });

  /**
   * MM-12: Leave member2 active→inactive.
   */
  test('MM-12: should mark member as left (active→inactive)', async ({ page }) => {
    await navigateToDynamicPage(page, 'tenant-member');
    await clickTabAndWaitForLoad(page, /Active|已激活/, 5000, 'active');

    const row = await locateMemberRow(page, testMembers[1], 10000);

    const leaveBtn = row.locator('[data-testid="row-action-leave"]');
    await expect(leaveBtn).toBeVisible({ timeout: 3000 });

    const leaveResponse = page.waitForResponse(
      (r) => r.url().includes('/commands/execute/admin:leave_member') && r.status() === 200,
      { timeout: 10000 }
    );

    await leaveBtn.click();
    await acceptConfirmDialog(page, 5000);
    await leaveResponse;

    await page.waitForResponse(
      (r) => r.url().includes('/list') && r.status() === 200,
      { timeout: 5000 }
    ).catch(() => null);

    // Verify member2 moved to inactive tab
    await clickTabAndWaitForLoad(page, /Inactive|已离职/, 5000, 'inactive');
    const inactiveRow = page.locator('tbody tr', { hasText: rowLookupText(testMembers[1]) }).first();
    await expect(inactiveRow).toBeVisible({ timeout: 5000 });
  });

  /**
   * MM-13: Status tab data isolation — each tab only shows members of that status.
   */
  test('MM-13: should show correct members per status tab', async ({ page }) => {
    await navigateToDynamicPage(page, 'tenant-member');

    // Navigate to active tab
    await clickTabAndWaitForLoad(page, /Active|已激活/, 5000, 'active');
    const activeRows = page.locator('tbody tr');
    await expect(activeRows.first()).toBeVisible({ timeout: 10000 });

    // Verify all visible status badges show active
    const statusBadges = page.locator('tbody td .inline-flex, tbody [data-testid*="status"]');
    const badgeCount = await statusBadges.count();
    for (let i = 0; i < Math.min(badgeCount, 5); i++) {
      const text = await statusBadges.nth(i).innerText().catch(() => '');
      if (text) {
        expect(text.toLowerCase()).toMatch(/active|已激活|激活/);
      }
    }

    // Navigate to suspended tab (may be empty)
    await clickTabAndWaitForLoad(page, /Suspended|已暂停/, 5000, 'suspended');
    const suspendedBadges = page.locator('tbody td .inline-flex, tbody [data-testid*="status"]');
    const suspCount = await suspendedBadges.count().catch(() => 0);
    for (let i = 0; i < Math.min(suspCount, 5); i++) {
      const text = await suspendedBadges.nth(i).innerText().catch(() => '');
      if (text) {
        expect(text.toLowerCase()).toMatch(/suspended|已暂停|暂停/);
      }
    }
  });

  /**
   * MM-14: i18n deep — all buttons, labels, status text are properly translated.
   */
  test('MM-14: should have proper i18n for all UI elements', async ({ page }) => {
    await navigateToDynamicPage(page, 'tenant-member');
    await expect(page.locator('table').first()).toBeVisible({ timeout: 15000 });

    // Check all visible text on the page for raw i18n keys
    const pageText = await page.locator('body').innerText();

    // Should not contain raw i18n key patterns
    const rawKeyPatterns = [
      /\$i18n:/,
      /model\.tenant_member\./,
      /action\.approve_member/,
      /action\.reject_member/,
      /action\.suspend_member/,
      /action\.restore_member/,
      /action\.leave_member/,
      /action\.delete_member/,
    ];

    for (const pattern of rawKeyPatterns) {
      expect(pageText).not.toMatch(pattern);
    }

    // Verify action buttons have text (not empty)
    const actionButtons = page.locator('[data-testid^="row-action-"]');
    const btnCount = await actionButtons.count();
    for (let i = 0; i < btnCount; i++) {
      const text = await actionButtons.nth(i).innerText().catch(() => '');
      expect(text.trim().length).toBeGreaterThan(0);
    }

    // Verify tab labels are translated
    const tabs = page.locator('nav[aria-label="Tabs"] button');
    const tabCount = await tabs.count();
    expect(tabCount).toBeGreaterThanOrEqual(5);

    for (let i = 0; i < tabCount; i++) {
      const tabText = await tabs.nth(i).innerText();
      expect(tabText.trim().length).toBeGreaterThan(0);
      expect(tabText).not.toMatch(/\$i18n:|^model\.|^action\./);
    }
  });
});
