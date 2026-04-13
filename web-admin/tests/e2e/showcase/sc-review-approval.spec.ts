/**
 * Workflow Showcase — Approval Flow E2E Test
 *
 * Tests the sc_review approval workflow:
 * - Creating requests and submitting them triggers review creation
 * - Approving, rejecting, and requesting revision on reviews
 * - Status propagation between review and request
 * - Review detail page with decision/comment fields
 * - Review records visible in request detail sub-table
 *
 * Dimensions covered:
 * D1  Menu Navigation     — sidebar click to review list
 * D2  List Rendering      — review table visible with rows
 * D3  Tab Filtering       — pending/approved/rejected tabs
 * D7  Detail Page         — review fields + action buttons
 * D9  State Transitions   — approve, reject, request_revision
 * D10 Invalid Transitions — approved review has no approve button
 * D14 Toast / Feedback    — operations show success feedback
 *
 * Note: Reviews are created via API (sc:create_review) since the BPM
 * automation is not active in E2E. Approval/rejection use UI toolbar buttons.
 *
 * @since 1.0.0
 */

import { test, expect, type Page } from '@playwright/test';
import {
  uniqueId,
  executeCommandViaApi,
  acceptConfirmDialog,
} from '../helpers/index';

// ---------------------------------------------------------------------------
// Serial mode — tests share state (records flow through lifecycle)
// ---------------------------------------------------------------------------
test.describe.configure({ mode: 'serial' });

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------
const UID = uniqueId('RVW');

// Menu labels — i18n key fallback
const ROOT_MENU = '工作流展示';
const REVIEW_MENU = '审批记录';
const REQUEST_MENU = '申请管理';

// ---------------------------------------------------------------------------
// Navigation helpers
// ---------------------------------------------------------------------------

async function clickSidebarItem(page: Page, label: string) {
  const nav = page.locator('nav, aside, [role="navigation"]').first();
  const item = nav.locator(`text="${label}"`).first();
  await item.waitFor({ state: 'visible', timeout: 8_000 });
  await item.scrollIntoViewIfNeeded();
  await item.click({ force: true });
  await page.waitForLoadState('domcontentloaded').catch(() => {});
}

async function navigateToReviewList(page: Page): Promise<void> {
  // Navigate to a known page first to reset state
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
  // Small wait for sidebar to render
  await page.locator('nav, aside, [role="navigation"]').first().waitFor({ state: 'visible', timeout: 8_000 });

  // Click the root menu first (may already be expanded)
  await clickSidebarItem(page, ROOT_MENU);
  // Then click the review menu item
  await clickSidebarItem(page, REVIEW_MENU);

  await expect(
    page.locator('table, [class*="ant-table"], [data-testid="dynamic-list"]').first(),
  ).toBeVisible({ timeout: 15_000 });
}

async function navigateToReviewDetail(page: Page, pid: string): Promise<void> {
  const detailResponsePromise = page.waitForResponse(
    (r) =>
      r.url().includes('/api/dynamic/sc_review') &&
      !r.url().includes('/list') &&
      r.status() === 200,
    { timeout: 15_000 },
  );
  await page.goto(`/p/sc_review/view/${pid}`, { waitUntil: 'domcontentloaded' });
  await detailResponsePromise.catch(() => null);
  await page.waitForLoadState('domcontentloaded');

  await expect(
    page.locator('main, [data-testid="detail-page"]').first(),
  ).toBeVisible({ timeout: 10_000 });
}

async function navigateToRequestDetail(page: Page, pid: string): Promise<void> {
  const detailResponsePromise = page.waitForResponse(
    (r) =>
      r.url().includes('/api/dynamic/sc_request') &&
      !r.url().includes('/list') &&
      r.status() === 200,
    { timeout: 15_000 },
  );
  await page.goto(`/p/sc_request/view/${pid}`, { waitUntil: 'domcontentloaded' });
  await detailResponsePromise.catch(() => null);
  await page.waitForLoadState('domcontentloaded');

  await expect(
    page.locator('main, [data-testid="detail-page"]').first(),
  ).toBeVisible({ timeout: 10_000 });
}

// ---------------------------------------------------------------------------
// Test suite
// ---------------------------------------------------------------------------

test.describe('Workflow Showcase — Approval Flow', () => {
  // sc_* models and commands are all in draft status (not published).
  // sc:create_request fails with "Command is not published". Showcase plugin needs republishing.
  test.fixme(true, 'Showcase plugin sc_* models/commands not published — reimport needed');

  test.use({ storageState: 'tests/storage/admin.json' });
  test.setTimeout(90_000);

  // Shared state across tests
  let userPid: string;

  // Approval flow: request1 -> review1 (approve)
  let request1Pid: string;
  let review1Pid: string;

  // Rejection flow: request2 -> review2 (reject)
  let request2Pid: string;
  let review2Pid: string;

  // Revision flow: request3 -> review3 (request_revision)
  let request3Pid: string;
  let review3Pid: string;

  // =========================================================================
  // beforeAll: Create requests, submit them, and create review records
  // =========================================================================
  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      // Get current user PID
      const meResp = await page.request.get('/api/auth/me');
      const meBody = await meResp.json();
      userPid = (meBody as any)?.data?.user?.pid ?? '';
      expect(userPid, 'Should get current user PID').toBeTruthy();

      // --- Request 1: for approval ---
      const r1 = await executeCommandViaApi(
        page,
        'sc:create_request',
        {
          sc_req_title: `Approve-me ${UID}`,
          sc_req_priority: 'high',
          sc_req_category: 'technical',
          sc_req_amount: 5000,
          sc_req_requester: userPid,
        },
        undefined,
        'create',
      );
      request1Pid = r1.recordId;
      expect(request1Pid).toBeTruthy();

      // Submit request1
      const s1 = await executeCommandViaApi(page, 'sc:submit_request', {}, request1Pid, 'state_transition');
      expect(s1.code).toBe('0');

      // Create review for request1
      const rv1 = await executeCommandViaApi(
        page,
        'sc:create_review',
        {
          sc_rev_request: request1Pid,
          sc_rev_reviewer: userPid,
          sc_rev_level: 1,
        },
        undefined,
        'create',
      );
      review1Pid = rv1.recordId;
      expect(review1Pid).toBeTruthy();

      // --- Request 2: for rejection ---
      const r2 = await executeCommandViaApi(
        page,
        'sc:create_request',
        {
          sc_req_title: `Reject-me ${UID}`,
          sc_req_priority: 'low',
          sc_req_category: 'general',
          sc_req_amount: 3000,
          sc_req_requester: userPid,
        },
        undefined,
        'create',
      );
      request2Pid = r2.recordId;
      expect(request2Pid).toBeTruthy();

      // Submit request2
      const s2 = await executeCommandViaApi(page, 'sc:submit_request', {}, request2Pid, 'state_transition');
      expect(s2.code).toBe('0');

      // Create review for request2
      const rv2 = await executeCommandViaApi(
        page,
        'sc:create_review',
        {
          sc_rev_request: request2Pid,
          sc_rev_reviewer: userPid,
          sc_rev_level: 1,
        },
        undefined,
        'create',
      );
      review2Pid = rv2.recordId;
      expect(review2Pid).toBeTruthy();

      // --- Request 3: for revision ---
      const r3 = await executeCommandViaApi(
        page,
        'sc:create_request',
        {
          sc_req_title: `Revise-me ${UID}`,
          sc_req_priority: 'medium',
          sc_req_category: 'financial',
          sc_req_amount: 8000,
          sc_req_requester: userPid,
        },
        undefined,
        'create',
      );
      request3Pid = r3.recordId;
      expect(request3Pid).toBeTruthy();

      // Submit request3
      const s3 = await executeCommandViaApi(page, 'sc:submit_request', {}, request3Pid, 'state_transition');
      expect(s3.code).toBe('0');

      // Create review for request3
      const rv3 = await executeCommandViaApi(
        page,
        'sc:create_review',
        {
          sc_rev_request: request3Pid,
          sc_rev_reviewer: userPid,
          sc_rev_level: 1,
        },
        undefined,
        'create',
      );
      review3Pid = rv3.recordId;
      expect(review3Pid).toBeTruthy();
    } finally {
      await ctx.close();
    }
  });

  // =========================================================================
  // D1 + D2: Review list loads via menu navigation
  // =========================================================================
  test('RVW-001 @smoke — Review list loads via menu with pending reviews', async ({ page }) => {
    await navigateToReviewList(page);

    // [D2] Table is visible with rows
    const table = page.locator('table, [class*="ant-table"]').first();
    await expect(table).toBeVisible();

    const rows = page.locator('tbody tr');
    const rowCount = await rows.count();
    expect(rowCount, 'Review list should have at least 1 row').toBeGreaterThanOrEqual(1);

    // Verify our review records exist via API
    const resp = await page.request.get(
      `/api/dynamic/sc_review/list?pageNum=1&pageSize=50`,
    );
    const body = await resp.json();
    const records = (body as any)?.data?.records ?? [];
    expect(records.length, 'API should return review records').toBeGreaterThanOrEqual(3);

    // Verify at least one review is in pending status
    const pendingReviews = records.filter((r: any) => r.sc_rev_status === 'pending');
    expect(pendingReviews.length, 'Should have pending reviews').toBeGreaterThanOrEqual(3);
  });

  // =========================================================================
  // D7: Review detail page shows fields and action buttons
  // =========================================================================
  test('RVW-002 @critical — Review detail shows fields and approve/reject buttons', async ({ page }) => {
    await navigateToReviewDetail(page, review1Pid);

    const mainContent = page.locator('main, [data-testid="detail-page"]').first();
    await expect(mainContent).toBeVisible({ timeout: 10_000 });

    // Verify status is pending via API
    const resp = await page.request.get(`/api/dynamic/sc_review/${review1Pid}`);
    const body = await resp.json();
    expect((body as any)?.data?.sc_rev_status).toBe('pending');
    expect((body as any)?.data?.sc_rev_level).toBe(1);

    // [D7] Toolbar should have approve and reject buttons for pending review
    const approveBtn = page
      .locator('[data-testid="toolbar-btn-approve"]')
      .or(page.getByRole('button', { name: /^批准$|^审批$|^Approve$/i }))
      .first();
    await expect(approveBtn, 'Approve button should be visible for pending review').toBeVisible({ timeout: 5_000 });

    const rejectBtn = page
      .locator('[data-testid="toolbar-btn-reject"]')
      .or(page.getByRole('button', { name: /^拒绝$|^驳回$|^Reject$/i }))
      .first();
    await expect(rejectBtn, 'Reject button should be visible for pending review').toBeVisible({ timeout: 5_000 });
  });

  // =========================================================================
  // D9: Approve review -> status changes to approved
  // =========================================================================
  test('RVW-003 @critical — Approve review: pending -> approved', async ({ page }) => {
    await navigateToReviewDetail(page, review1Pid);

    const approveBtn = page
      .locator('[data-testid="toolbar-btn-approve"]')
      .or(page.getByRole('button', { name: /^批准$|^审批$|^Approve$/i }))
      .first();
    await approveBtn.waitFor({ state: 'visible', timeout: 8_000 });

    // Wait for command execution response
    const commandResponsePromise = page.waitForResponse(
      (r) =>
        r.url().includes('/api/meta/commands/execute/') &&
        r.request().method().toLowerCase() === 'post' &&
        r.status() === 200,
      { timeout: 20_000 },
    );
    await approveBtn.click();

    // Handle potential confirm dialog or form dialog
    // The approve command may show a form for comment input
    const dialog = page.locator('.ant-modal, [role="dialog"]').first();
    const hasDialog = await dialog.isVisible({ timeout: 3_000 }).catch(() => false);
    if (hasDialog) {
      // If there's a comment field in the dialog, fill it
      const commentInput = dialog.locator('textarea, input[type="text"]').first();
      const hasComment = await commentInput.isVisible({ timeout: 2_000 }).catch(() => false);
      if (hasComment) {
        await commentInput.fill(`Approved by E2E test ${UID}`);
      }
      // Click the confirm/submit button in the dialog
      const confirmBtn = dialog.getByRole('button', { name: /确[定认]|OK|Submit|提交/i }).first();
      const hasConfirm = await confirmBtn.isVisible({ timeout: 2_000 }).catch(() => false);
      if (hasConfirm) {
        await confirmBtn.click();
      }
    }

    const resp = await commandResponsePromise;
    const body = await resp.json();
    expect((body as any)?.code, 'Approve command should succeed').toBe('0');

    // [D14] Verify status changed to approved via API
    const verifyResp = await page.request.get(`/api/dynamic/sc_review/${review1Pid}`);
    const verifyBody = await verifyResp.json();
    expect((verifyBody as any)?.data?.sc_rev_status).toBe('approved');
    expect((verifyBody as any)?.data?.sc_rev_decision).toBe('approved');
  });

  // =========================================================================
  // D10: Invalid transition — approved review cannot be approved again
  // =========================================================================
  test('RVW-004 — Approved review rejects re-approval via API', async ({ page }) => {
    // Verify the review is already approved
    const resp = await page.request.get(`/api/dynamic/sc_review/${review1Pid}`);
    const body = await resp.json();
    expect((body as any)?.data?.sc_rev_status).toBe('approved');
    expect((body as any)?.data?.sc_rev_decision).toBe('approved');

    // Attempt to approve again — should fail due to state precondition
    const result = await executeCommandViaApi(
      page,
      'sc:approve_review',
      { sc_rev_comment: 'Trying to re-approve' },
      review1Pid,
      'state_transition',
      { allowHttpError: true },
    );
    // The command should fail (non-zero code) because status is already approved
    expect(result.code, 'Re-approval of approved review should fail').not.toBe('0');
  });

  // =========================================================================
  // D9: Reject review -> status changes to rejected
  // =========================================================================
  test('RVW-005 @critical — Reject review: pending -> rejected', async ({ page }) => {
    await navigateToReviewDetail(page, review2Pid);

    const rejectBtn = page
      .locator('[data-testid="toolbar-btn-reject"]')
      .or(page.getByRole('button', { name: /^拒绝$|^驳回$|^Reject$/i }))
      .first();
    await rejectBtn.waitFor({ state: 'visible', timeout: 8_000 });

    const commandResponsePromise = page.waitForResponse(
      (r) =>
        r.url().includes('/api/meta/commands/execute/') &&
        r.request().method().toLowerCase() === 'post' &&
        r.status() === 200,
      { timeout: 20_000 },
    );
    await rejectBtn.click();

    // Handle potential dialog
    const dialog = page.locator('.ant-modal, [role="dialog"]').first();
    const hasDialog = await dialog.isVisible({ timeout: 3_000 }).catch(() => false);
    if (hasDialog) {
      const commentInput = dialog.locator('textarea, input[type="text"]').first();
      const hasComment = await commentInput.isVisible({ timeout: 2_000 }).catch(() => false);
      if (hasComment) {
        await commentInput.fill(`Rejected by E2E test ${UID}`);
      }
      const confirmBtn = dialog.getByRole('button', { name: /确[定认]|OK|Submit|提交/i }).first();
      const hasConfirm = await confirmBtn.isVisible({ timeout: 2_000 }).catch(() => false);
      if (hasConfirm) {
        await confirmBtn.click();
      }
    }

    const resp = await commandResponsePromise;
    const body = await resp.json();
    expect((body as any)?.code, 'Reject command should succeed').toBe('0');

    // Verify status changed to rejected via API
    const verifyResp = await page.request.get(`/api/dynamic/sc_review/${review2Pid}`);
    const verifyBody = await verifyResp.json();
    expect((verifyBody as any)?.data?.sc_rev_status).toBe('rejected');
    expect((verifyBody as any)?.data?.sc_rev_decision).toBe('rejected');
  });

  // =========================================================================
  // D9: Request revision -> status = rejected, decision = need_revision
  // =========================================================================
  test('RVW-006 @critical — Request revision: pending -> rejected (need_revision)', async ({ page }) => {
    await navigateToReviewDetail(page, review3Pid);

    // The "request revision" button
    const revisionBtn = page
      .locator('[data-testid="toolbar-btn-request_revision"]')
      .or(page.getByRole('button', { name: /^要求修订$|^request_revision$|^Request Revision$/i }))
      .first();
    await revisionBtn.waitFor({ state: 'visible', timeout: 8_000 });

    const commandResponsePromise = page.waitForResponse(
      (r) =>
        r.url().includes('/api/meta/commands/execute/') &&
        r.request().method().toLowerCase() === 'post' &&
        r.status() === 200,
      { timeout: 20_000 },
    );
    await revisionBtn.click();

    // Handle potential dialog
    const dialog = page.locator('.ant-modal, [role="dialog"]').first();
    const hasDialog = await dialog.isVisible({ timeout: 3_000 }).catch(() => false);
    if (hasDialog) {
      const commentInput = dialog.locator('textarea, input[type="text"]').first();
      const hasComment = await commentInput.isVisible({ timeout: 2_000 }).catch(() => false);
      if (hasComment) {
        await commentInput.fill(`Revision needed - E2E test ${UID}`);
      }
      const confirmBtn = dialog.getByRole('button', { name: /确[定认]|OK|Submit|提交/i }).first();
      const hasConfirm = await confirmBtn.isVisible({ timeout: 2_000 }).catch(() => false);
      if (hasConfirm) {
        await confirmBtn.click();
      }
    }

    const resp = await commandResponsePromise;
    const body = await resp.json();
    expect((body as any)?.code, 'Request revision command should succeed').toBe('0');

    // Verify status and decision via API
    const verifyResp = await page.request.get(`/api/dynamic/sc_review/${review3Pid}`);
    const verifyBody = await verifyResp.json();
    expect((verifyBody as any)?.data?.sc_rev_status).toBe('rejected');
    expect((verifyBody as any)?.data?.sc_rev_decision).toBe('need_revision');
  });

  // =========================================================================
  // D3: Tab filtering — tabs exist and filter by status
  // =========================================================================
  test('RVW-007 — Tab filtering: status tabs exist and filter correctly', async ({ page }) => {
    await navigateToReviewList(page);

    const tabBar = page.locator('nav[aria-label="Tabs"]').first();
    await expect(tabBar).toBeVisible({ timeout: 5_000 });

    // Verify all expected tabs exist
    await expect(tabBar.getByRole('button', { name: /全部|All/i })).toBeVisible();
    await expect(tabBar.getByRole('button', { name: /待审批|Pending/i })).toBeVisible({ timeout: 3_000 });
    await expect(tabBar.getByRole('button', { name: /已拒绝|Rejected/i })).toBeVisible({ timeout: 3_000 });
    await expect(tabBar.getByRole('button', { name: /已通过|Approved/i })).toBeVisible({ timeout: 3_000 });

    // Verify tab filtering via API: the pending tab should only contain pending records
    const pendingResp = await page.request.get(
      `/api/dynamic/sc_review/list?pageNum=1&pageSize=50&filters=${encodeURIComponent(
        JSON.stringify([{ fieldName: 'sc_rev_status', operator: 'EQ', value: 'pending' }]),
      )}`,
    );
    const pendingBody = await pendingResp.json();
    const pendingRecords = (pendingBody as any)?.data?.records ?? [];
    for (const record of pendingRecords) {
      expect(record.sc_rev_status, 'Pending filter should only return pending records').toBe('pending');
    }

    // None of our test reviews should be in pending (they were all approved/rejected)
    const ourPending = pendingRecords.filter(
      (r: any) => r.pid === review1Pid || r.pid === review2Pid || r.pid === review3Pid,
    );
    expect(ourPending.length, 'Our test reviews should NOT be in pending tab').toBe(0);
  });

  // =========================================================================
  // D7: Verify review data on request detail page (sub-table)
  // =========================================================================
  test('RVW-008 — Request detail shows review records in sub-table', async ({ page }) => {
    await navigateToRequestDetail(page, request1Pid);

    const mainContent = page.locator('main, [data-testid="detail-page"]').first();
    await expect(mainContent).toBeVisible({ timeout: 10_000 });

    // Click the reviews tab (scoped to main content, not sidebar)
    const mainArea = page.locator('main, [data-testid="detail-page"]').first();
    const reviewTab = mainArea.getByText(/审批记录|Review Records/i).first();
    await expect(reviewTab).toBeVisible({ timeout: 5_000 });
    await reviewTab.click({ force: true });

    // Wait for sub-table data to load
    await page.waitForLoadState('domcontentloaded');

    // Verify review record exists via API (sub-table may use different API)
    const listResp = await page.request.get(
      `/api/dynamic/sc_review/list?pageNum=1&pageSize=10&filters=${encodeURIComponent(
        JSON.stringify([{ fieldName: 'sc_rev_request', operator: 'EQ', value: request1Pid }]),
      )}`,
    );
    const listBody = await listResp.json();
    const reviews = (listBody as any)?.data?.records ?? [];
    expect(reviews.length, 'Should have at least 1 review for this request').toBeGreaterThanOrEqual(1);

    // The review should be approved (from test RVW-003)
    const approvedReview = reviews.find((r: any) => r.pid === review1Pid);
    expect(approvedReview, 'Should find our approved review').toBeTruthy();
    expect(approvedReview.sc_rev_status).toBe('approved');
    expect(approvedReview.sc_rev_decision).toBe('approved');
  });

  // =========================================================================
  // Verify all three review outcomes coexist in the list
  // =========================================================================
  test('RVW-009 — All review outcomes visible in review list', async ({ page }) => {
    await navigateToReviewList(page);

    // Verify all three reviews via API
    const resp = await page.request.get(`/api/dynamic/sc_review/list?pageNum=1&pageSize=50`);
    const body = await resp.json();
    const records = (body as any)?.data?.records ?? [];

    // Find our three reviews
    const approved = records.find((r: any) => r.pid === review1Pid);
    const rejected = records.find((r: any) => r.pid === review2Pid);
    const revision = records.find((r: any) => r.pid === review3Pid);

    expect(approved, 'Approved review should exist in list').toBeTruthy();
    expect(approved.sc_rev_status).toBe('approved');
    expect(approved.sc_rev_decision).toBe('approved');

    expect(rejected, 'Rejected review should exist in list').toBeTruthy();
    expect(rejected.sc_rev_status).toBe('rejected');
    expect(rejected.sc_rev_decision).toBe('rejected');

    expect(revision, 'Revision review should exist in list').toBeTruthy();
    expect(revision.sc_rev_status).toBe('rejected');
    expect(revision.sc_rev_decision).toBe('need_revision');

    // Table should show at least 3 rows
    const rows = page.locator('tbody tr');
    const rowCount = await rows.count();
    expect(rowCount, 'Table should show at least 3 rows').toBeGreaterThanOrEqual(3);
  });
});
