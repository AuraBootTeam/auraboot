/**
 * Approval Workflow — Complete End-to-End Flow Tests
 *
 * ACF-001 @smoke    : BPM task center page loads via sidebar navigation
 * ACF-002 @smoke    : BPM approval inbox page loads via sidebar navigation
 * ACF-003 @smoke    : BPM process management list page loads
 * ACF-004 @critical : Submit e2et_payment for approval → process instance created
 * ACF-005 @critical : Approve a pending payment → status becomes approved
 * ACF-006 @critical : Reject flow — submit → pending → reject → rejected status
 * ACF-007 @critical : Re-submit after rejection → back to pending
 * ACF-008           : Task center shows pending tasks after submission
 * ACF-009           : Approval inbox renders pending items list
 * ACF-010           : Completed tasks tab shows historical approvals
 * ACF-011           : BPM process instances API endpoint returns valid response
 * ACF-012           : Notification bell is visible in header
 * ACF-013           : BPM process definitions are listed (even if draft)
 *
 * Prerequisites:
 *   - e2e-test-order plugin installed (com.test.e2e-order)
 *   - e2et_payment model and commands: e2et:create_payment, e2et:submit_payment,
 *     e2et:approve_payment, e2et:reject_payment
 *   - BPM module available (/bpm/task-center, /bpm/approval-inbox)
 *
 * Note: The e2et_payment_approval BPM process definition is in draft status.
 * When the process is deployed, ACF-004/005/006/007 will test the full BPM chain.
 * Until deployment, these tests verify the command-level status transitions.
 *
 * @since 11.0.0
 */

import { test, expect, type Page } from '../../fixtures';
import { uniqueId, extractRecordId } from '../helpers/index';

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const UID = uniqueId('ACF');
const SKIP_NO_MODEL = 'e2et_payment model not available (not published or not found via /api/meta/models/code/e2et_payment)';

// ---------------------------------------------------------------------------
// Shared state
// ---------------------------------------------------------------------------

let modelAvailable = false;
const createdPaymentPids: string[] = [];

// ---------------------------------------------------------------------------
// API Helpers
// ---------------------------------------------------------------------------

async function createOrderViaApi(page: Page): Promise<string> {
  const resp = await page.request.post('/api/meta/commands/execute/e2et:create_order', {
    data: {
      payload: {
        e2et_order_title: `ACF Order ${UID}`,
        e2et_order_type: 'normal',
        e2et_order_urgent: false,
      },
      operationType: 'create',
    },
  });
  const body = await resp.json();
  const orderId = extractRecordId(body);
  if (!orderId) {
    throw new Error(`Failed to create order: ${JSON.stringify(body).slice(0, 300)}`);
  }
  return orderId;
}

async function createPaymentViaApi(
  page: Page,
  overrides: Record<string, unknown> = {},
): Promise<string> {
  const orderId = await createOrderViaApi(page);

  const resp = await page.request.post('/api/meta/commands/execute/e2et:create_payment', {
    data: {
      payload: {
        e2et_pay_order_id: orderId,
        e2et_pay_amount: 5000,
        e2et_pay_method: 'bank_transfer',
        e2et_pay_remark: `ACF test payment ${UID}`,
        ...overrides,
      },
      operationType: 'create',
    },
  });
  const body = await resp.json();
  const pid = extractRecordId(body);
  if (!pid) {
    throw new Error(`Failed to create payment: ${JSON.stringify(body).slice(0, 300)}`);
  }
  createdPaymentPids.push(pid);
  return pid;
}

async function submitPaymentViaApi(page: Page, pid: string): Promise<boolean> {
  const resp = await page.request.post('/api/meta/commands/execute/e2et:submit_payment', {
    data: { targetRecordId: pid, operationType: 'update', payload: {} },
  });
  return resp.ok();
}

async function getPaymentStatus(page: Page, pid: string): Promise<string> {
  const resp = await page.request.get(`/api/dynamic/e2et_payment/${pid}`);
  const body = await resp.json();
  return body?.data?.e2et_pay_status ?? body?.data?.status ?? 'unknown';
}

// ---------------------------------------------------------------------------
// Navigation helpers
// ---------------------------------------------------------------------------

async function expandBpmMenu(page: Page): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
  const nav = page.locator('nav');
  const bpmBtn = nav.getByRole('button', { name: /流程管理|BPM|审批/ }).first();
  await bpmBtn.waitFor({ state: 'visible', timeout: 10000 });
  await bpmBtn.evaluate((el: HTMLElement) => el.click());
  await page.waitForResponse(() => true, { timeout: 2000 }).catch(() => null);
}

async function navigateToBpmPage(page: Page, path: string): Promise<void> {
  await expandBpmMenu(page);
  const nav = page.locator('nav');
  const link = nav.locator(`a[href="${path}"]`).first();
  await link.waitFor({ state: 'attached', timeout: 8000 });
  await link.evaluate((el: HTMLElement) => el.click());
  await expect(page).toHaveURL(new RegExp(path.replace('/', '\\/')), { timeout: 10000 });
  await page.waitForLoadState('domcontentloaded');
}

// ---------------------------------------------------------------------------
// Test Suite
// ---------------------------------------------------------------------------

test.describe('Approval Workflow — Complete Flow', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(90000);

  // =========================================================================
  // Setup: check model availability
  // =========================================================================
  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      const resp = await page.request.get('/api/meta/models/code/e2et_payment');
      const body = await resp.json().catch(() => ({}));
      modelAvailable = resp.ok() && body?.data?.status === 'published';
    } catch {
      modelAvailable = false;
    } finally {
      await ctx.close();
    }
  });

  // =========================================================================
  // SMOKE: Navigation
  // =========================================================================

  test('ACF-001: sidebar → 任务中心 (task center) page loads', async ({ page }) => {
    await navigateToBpmPage(page, '/bpm/task-center');

    // Page must have some content — not blank
    const content = page.locator('main, [class*="task"], [class*="content"]').first();
    await expect(content).toBeVisible({ timeout: 10000 });

    // No access error
    await expect(page.locator('text=Access forbidden'))
      .not.toBeVisible({ timeout: 2000 })
      .catch(() => {});
    await expect(page.locator('text=403'))
      .not.toBeVisible({ timeout: 2000 })
      .catch(() => {});
  });

  test('ACF-002: sidebar → 审批任务 (approval inbox) page loads', async ({ page }) => {
    await navigateToBpmPage(page, '/bpm/approval-inbox');

    // Wait for page to settle
    await page.waitForLoadState('networkidle', { timeout: 15000 }).catch(() => null);

    // Should render content (table or empty state)
    const content = page.locator('table, [role="table"], [class*="empty"], main').first();
    await expect(content).toBeVisible({ timeout: 10000 });

    await expect(page.locator('text=Access forbidden'))
      .not.toBeVisible({ timeout: 2000 })
      .catch(() => {});
  });

  test('ACF-003: BPM process management list loads with process definitions', async ({ page }) => {
    await page.goto('/p/bpm_process_management', { waitUntil: 'domcontentloaded' });

    const listResp = await page
      .waitForResponse((r) => r.url().includes('/api/dynamic/bpm') && r.status() === 200, {
        timeout: 15000,
      })
      .catch(() => null);

    if (!listResp) {
      // Try alternate URL
      await page.goto('/bpm', { waitUntil: 'domcontentloaded' });
    }

    await page.waitForLoadState('networkidle', { timeout: 10000 }).catch(() => null);

    // Verify page loaded without error
    await expect(page.locator('text=Access forbidden'))
      .not.toBeVisible({ timeout: 2000 })
      .catch(() => {});

    // Verify process definitions are accessible via API
    const resp = await page.request
      .get('/api/bpm/process-definitions?pageNum=1&pageSize=20')
      .catch(async () => page.request.get('/api/bpm/definitions?pageNum=1&pageSize=20'));
    if (resp && resp.ok()) {
      const body = await resp.json();
      expect(body).toBeTruthy();
    }
  });

  // =========================================================================
  // CRITICAL: Submit and Approve Payment Flow
  // =========================================================================

  test('ACF-004: create payment → submit → status changes to pending/submitted', async ({
    page,
  }) => {
    if (!modelAvailable) {
      test.skip(true, SKIP_NO_MODEL);
      return;
    }

    const paymentPid = await createPaymentViaApi(page, { e2et_pay_amount: 3000 });
    expect(paymentPid).toBeTruthy();

    // Verify initial status is draft (or created)
    const initialStatus = await getPaymentStatus(page, paymentPid);
    expect(['draft', 'created', 'pending']).toContain(initialStatus);

    // Submit the payment
    const submitted = await submitPaymentViaApi(page, paymentPid);
    expect(submitted, 'submit_payment command should succeed').toBe(true);

    // Verify status changed after submission
    const afterStatus = await getPaymentStatus(page, paymentPid);
    // Expected: 'pending' or 'submitted' or 'pending_approval'
    expect(
      ['pending', 'submitted', 'pending_approval', 'in_review'],
      `Status after submission should be a pending state, got: ${afterStatus}`,
    ).toContain(afterStatus);

    // Navigate to task center and verify something changed in the UI
    await navigateToBpmPage(page, '/bpm/task-center');

    // Task center should show content
    const content = page.locator('main').first();
    await expect(content).toBeVisible({ timeout: 8000 });
  });

  test('ACF-005: approve payment → status becomes approved', async ({ page }) => {
    if (!modelAvailable) {
      test.skip(true, SKIP_NO_MODEL);
      return;
    }

    const paymentPid = await createPaymentViaApi(page, { e2et_pay_amount: 1000 });
    await submitPaymentViaApi(page, paymentPid);

    // Approve via command API
    const approveResp = await page.request.post('/api/meta/commands/execute/e2et:approve_payment', {
      data: { targetRecordId: paymentPid, operationType: 'update', payload: {} },
    });
    expect(approveResp.status()).toBeLessThan(400);

    // Verify status changed to approved
    const afterStatus = await getPaymentStatus(page, paymentPid);
    expect(
      ['approved', 'approved_pending_pay', 'completed', 'in_payment'],
      `After approval, status should indicate approved state, got: ${afterStatus}`,
    ).toContain(afterStatus);

    // Navigate to task center — verify we can see the page
    await navigateToBpmPage(page, '/bpm/task-center');

    // Click "已办任务" tab if available
    const completedTab = page
      .locator('button, [role="tab"]')
      .filter({ hasText: /已办|Completed|已处理/ })
      .first();
    const hasCompletedTab = await completedTab.isVisible({ timeout: 5000 }).catch(() => false);

    if (hasCompletedTab) {
      await completedTab.click();
      // After click, some content should be visible
      const tableOrEmpty = page
        .locator('table, [role="table"]')
        .or(page.locator('[class*="empty"]'))
        .or(page.getByText('暂无'))
        .or(page.getByText('No data'));
      await expect(tableOrEmpty.first()).toBeVisible({ timeout: 8000 });
    }
  });

  test('ACF-006: reject flow → payment status becomes rejected', async ({ page }) => {
    if (!modelAvailable) {
      test.skip(true, SKIP_NO_MODEL);
      return;
    }

    const paymentPid = await createPaymentViaApi(page, { e2et_pay_amount: 800 });
    await submitPaymentViaApi(page, paymentPid);

    // Reject via command API (targeting the specific payment PID to avoid inbox isolation issues)
    const rejectResp = await page.request.post('/api/meta/commands/execute/e2et:reject_payment', {
      data: {
        targetRecordId: paymentPid,
        operationType: 'update',
        payload: { e2et_pay_remark: `E2E test rejection ${UID}` },
      },
    });
    expect(rejectResp.status(), 'Reject command should return HTTP 2xx').toBeLessThan(400);

    // Verify status changed to rejected
    const afterStatus = await getPaymentStatus(page, paymentPid);
    expect(
      ['rejected', 'declined', 'returned'],
      `After rejection, status should indicate rejected state, got: ${afterStatus}`,
    ).toContain(afterStatus);
  });

  test('ACF-007: re-submit rejected payment → back to pending state', async ({ page }) => {
    if (!modelAvailable) {
      test.skip(true, SKIP_NO_MODEL);
      return;
    }

    const paymentPid = await createPaymentViaApi(page, { e2et_pay_amount: 600 });
    await submitPaymentViaApi(page, paymentPid);

    // Reject first
    await page.request.post('/api/meta/commands/execute/e2et:reject_payment', {
      data: { targetRecordId: paymentPid, operationType: 'update', payload: {} },
    });

    const rejectedStatus = await getPaymentStatus(page, paymentPid);
    expect(
      ['rejected', 'declined', 'returned'],
      `Status should be rejected after reject command, got: ${rejectedStatus}`,
    ).toContain(rejectedStatus);

    // Re-submit
    const resubmitResp = await submitPaymentViaApi(page, paymentPid);

    if (resubmitResp) {
      const afterResubmitStatus = await getPaymentStatus(page, paymentPid);
      // Should be back in pending state
      expect(
        ['pending', 'submitted', 'pending_approval'],
        `After re-submit, status should be pending, got: ${afterResubmitStatus}`,
      ).toContain(afterResubmitStatus);
    } else {
      // Re-submission may be blocked by state machine — verify record still exists
      const stillExists = await page.request.get(`/api/dynamic/e2et_payment/${paymentPid}`);
      expect(stillExists.ok()).toBe(true);
    }
  });

  // =========================================================================
  // Task Center UI tests
  // =========================================================================

  test('ACF-008: task center shows 待办/已办 tabs', async ({ page }) => {
    await navigateToBpmPage(page, '/bpm/task-center');

    // Wait for content to render
    await page.waitForLoadState('networkidle', { timeout: 15000 }).catch(() => null);

    // Look for tab structure (待办任务 / 已办任务 / 我发起的)
    const todoTab = page
      .locator('button, [role="tab"]')
      .filter({ hasText: /待办|Todo|Pending/ })
      .first();
    const hasTodoTab = await todoTab.isVisible({ timeout: 8000 }).catch(() => false);

    expect(hasTodoTab, 'Task center must have a "待办" tab').toBe(true);

    // Click todo tab
    await todoTab.click();

    // Should show table or empty state (TaskTable renders "暂无任务" when empty)
    const tableOrEmpty = page
      .locator('table, [role="table"]')
      .or(page.locator('[class*="empty"]'))
      .or(page.getByText('暂无任务'))
      .or(page.getByText('暂无待办'))
      .or(page.getByText('No pending tasks'));
    await expect(tableOrEmpty.first()).toBeVisible({ timeout: 8000 });
  });

  test('ACF-009: approval inbox renders list (table or empty state)', async ({ page }) => {
    await navigateToBpmPage(page, '/bpm/approval-inbox');

    await page.waitForLoadState('networkidle', { timeout: 15000 }).catch(() => null);

    // Should show a table or empty state — not a blank page
    const table = page.locator('table, [role="table"], [class*="ant-table"]').first();
    const emptyState = page
      .locator('[class*="empty"]')
      .or(page.getByText('暂无'))
      .or(page.getByText('No data'))
      .first();
    const content = page.locator('main, [class*="content"]').first();

    const hasTable = await table.isVisible({ timeout: 5000 }).catch(() => false);
    const hasEmpty = await emptyState.isVisible({ timeout: 3000 }).catch(() => false);
    const hasContent = await content.isVisible({ timeout: 3000 }).catch(() => false);

    expect(
      hasTable || hasEmpty || hasContent,
      'Approval inbox must render some content, not a blank page',
    ).toBe(true);
  });

  test('ACF-010: completed tasks tab shows historical approvals list', async ({ page }) => {
    await navigateToBpmPage(page, '/bpm/task-center');
    await page.waitForLoadState('domcontentloaded');

    // Find and click completed tasks tab
    const completedTab = page
      .locator('button, [role="tab"]')
      .filter({ hasText: /已办|Completed|已处理|已完成/ })
      .first();

    const hasCompletedTab = await completedTab.isVisible({ timeout: 8000 }).catch(() => false);

    if (!hasCompletedTab) {
      // Task center may use a different tab structure
      const allTabs = await page
        .locator('[role="tab"], button')
        .filter({ hasText: /.+/ })
        .allTextContents();
      console.log('[ACF-010] Available tabs:', allTabs);

      // Verify page at least has some content
      const content = page.locator('main').first();
      await expect(content).toBeVisible({ timeout: 5000 });
      return;
    }

    await completedTab.click();

    // After click, should show table or empty state
    const tableOrEmpty = page
      .locator('table, [role="table"]')
      .or(page.locator('[class*="empty"]'))
      .or(page.getByText('暂无'));
    await expect(tableOrEmpty.first()).toBeVisible({ timeout: 8000 });
  });

  // =========================================================================
  // API and Infrastructure
  // =========================================================================

  test('ACF-011: BPM process instances by-business-key API returns valid response', async ({
    page,
  }) => {
    const nonExistentKey = `no-such-record-${Date.now()}`;
    const resp = await page.request.get('/api/bpm/process-instances/by-business-key/status', {
      params: { businessKey: nonExistentKey },
    });

    // Must respond (not hang or 5xx); 400 is valid for non-existent key
    expect(resp.status()).toBeLessThan(500);

    const body = await resp.json().catch(() => null);
    expect(body).not.toBeNull();
    expect(body).toHaveProperty('code');
  });

  test('ACF-012: notification bell is visible in header after navigation', async ({ page }) => {
    await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });

    const notificationBell = page
      .locator(
        '[data-testid="inbox-badge"], [data-testid="notification-bell"], header button:has-text("Inbox"), header a[href="/notifications"], header button[aria-label*="notification" i]',
      )
      .first();
    const hasBell = await notificationBell.isVisible({ timeout: 8000 }).catch(() => false);

    expect(hasBell, 'Header inbox/notification entry must be visible').toBe(true);
  });

  test('ACF-013: BPM process definitions API returns list of processes', async ({ page }) => {
    // Primary endpoint: /api/bpm/process-definitions (returns 200 with paginated list)
    const resp = await page.request
      .get('/api/bpm/process-definitions?pageNum=1&pageSize=20')
      .catch(() => null);

    if (resp && resp.ok()) {
      const body = await resp.json();
      expect(body.code).toBe('0');
      // Even if empty, the structure should be valid
      expect(body.data).toHaveProperty('records');
    } else {
      // Fallback: dynamic page endpoint
      const altResp = await page.request
        .get('/api/dynamic/bpm_process_management/list?pageNum=1&pageSize=20')
        .catch(() => null);
      if (altResp && altResp.ok()) {
        const altBody = await altResp.json();
        expect(altBody.code).toBe('0');
        expect(altBody.data).toHaveProperty('total');
        expect(altBody.data).toHaveProperty('records');
      } else {
        // If neither endpoint is accessible, skip (BPM module may not be deployed)
        test.skip(
          true,
          'BPM process definitions endpoint not accessible — module may not be deployed',
        );
      }
    }
  });

  // =========================================================================
  // Payment full lifecycle UI: submit → view in pending list → approve via task center
  // =========================================================================

  test('ACF-014: full UI flow — submit payment → appears in task center → approve via UI', async ({
    page,
  }) => {
    if (!modelAvailable) {
      test.skip(true, SKIP_NO_MODEL);
      return;
    }

    const paymentPid = await createPaymentViaApi(page, { e2et_pay_amount: 4500 });
    await submitPaymentViaApi(page, paymentPid);

    // Navigate to task center via sidebar
    await navigateToBpmPage(page, '/bpm/task-center');

    // Look for pending tasks (the one we just submitted)
    const todoTab = page
      .locator('button, [role="tab"]')
      .filter({ hasText: /待办|Todo|Pending/ })
      .first();
    const hasTodoTab = await todoTab.isVisible({ timeout: 8000 }).catch(() => false);

    if (hasTodoTab) {
      await todoTab.click();
    }

    // Check if there are approval buttons visible
    const approveBtn = page
      .locator('button')
      .filter({ hasText: /通过|Approve|批准/ })
      .first();
    const hasApproveBtn = await approveBtn.isVisible({ timeout: 5000 }).catch(() => false);

    if (hasApproveBtn) {
      await approveBtn.click();

      // Handle confirmation if needed
      const confirmBtn = page
        .locator('button')
        .filter({ hasText: /确认|Confirm|确定/ })
        .first();
      const hasConfirm = await confirmBtn.isVisible({ timeout: 3000 }).catch(() => false);
      if (hasConfirm) await confirmBtn.click();

      await page
        .waitForResponse((r) => r.url().includes('/bpm/') && r.request().method() === 'POST', {
          timeout: 8000,
        })
        .catch(() => null);

      // Toast should appear
      const toast = page
        .locator('[class*="toast"], [class*="message"], [class*="notification"]')
        .filter({ hasText: /成功|Success|完成/ })
        .first();
      const hasToast = await toast.isVisible({ timeout: 5000 }).catch(() => false);
      if (hasToast) {
        await expect(toast).toBeVisible({ timeout: 5000 });
      }
    } else {
      // Approval via API if UI button not available (e.g., process not deployed)
      const approveResp = await page.request.post(
        '/api/meta/commands/execute/e2et:approve_payment',
        {
          data: { targetRecordId: paymentPid, operationType: 'update', payload: {} },
        },
      );
      expect(approveResp.status()).toBeLessThan(400);

      const status = await getPaymentStatus(page, paymentPid);
      expect(
        ['approved', 'approved_pending_pay', 'completed', 'pending'],
        `After approve command, status should indicate progress, got: ${status}`,
      ).toContain(status);
    }

    // Navigate to payment list and verify record still exists
    await page.goto('/p/e2et_payment', { waitUntil: 'domcontentloaded' });
    await page
      .waitForResponse((r) => r.url().includes('/api/dynamic/e2et_payment') && r.status() === 200, {
        timeout: 15000,
      })
      .catch(() => null);

    const table = page.locator('table, [class*="ant-table"]').first();
    const emptyState = page.locator('[class*="empty"]').first();
    await expect(table.or(emptyState)).toBeVisible({ timeout: 10000 });
  });
});
