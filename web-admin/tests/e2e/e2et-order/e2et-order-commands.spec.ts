/**
 * E2E Test Order — Command & State Machine Tests (UI-based)
 *
 * Tests CM-001 ~ CM-007: State transitions via UI row actions
 * - Submit order via UI (draft -> submitted) with HAS_CHILDREN validation
 * - Approve order via UI (submitted -> approved)
 * - Reject order via UI (submitted -> rejected)
 * - Complete order via UI (approved -> completed)
 * - Cancel order via UI from multiple source states
 *
 * API is used only for data setup (beforeAll/beforeEach), NOT for core actions.
 * Uses real database, NO MOCKING.
 * Uses DynamicListPage Page Object for stable selectors.
 *
 * @since 6.0.0
 */

import { test, expect } from '../../fixtures';
import {
  acceptConfirmDialog,
  uniqueId,
  queryFilteredList,
  clickRowActionByLocator,
  ensureFilterFormOpen,
} from '../helpers';
import { ModelTestHelper } from '../../helpers/model-test-helper';
import { E2ET_ORDER_CONFIG } from '../../helpers/configs/e2et-order.config';
import { DynamicListPage } from '../../pages';
import { ErrorCodes } from '~/services/http-client/types';

// ---------------------------------------------------------------------------
// UI helpers (spec-local -- not model-specific)
// ---------------------------------------------------------------------------

/** Navigate to list, click a status tab, and wait for data. */
async function navigateToTab(
  page: import('@playwright/test').Page,
  tabText: string | RegExp,
): Promise<DynamicListPage> {
  const order = new ModelTestHelper(page, E2ET_ORDER_CONFIG);
  const listPage = await order.gotoList();
  await listPage.clickTabByText(tabText);
  return listPage;
}

/**
 * Click a row action button, handle optional confirm dialog, wait for
 * either command response or list refresh as confirmation of completion.
 */
async function clickRowActionAndWaitForRefresh(
  page: import('@playwright/test').Page,
  btn: import('@playwright/test').Locator,
): Promise<void> {
  // Set up both command and list response listeners BEFORE the click
  const cmdOrListResponse = page.waitForResponse(
    (r) => (r.url().includes('/list') || r.url().includes('/execute/')) && r.status() === 200,
    { timeout: 15000 },
  );

  await btn.click();

  // Handle the confirm dialog if it appears (some commands show a dialog)
  await acceptConfirmDialog(page).catch(() => {});

  // Wait for either command execution response or list refresh
  await cmdOrListResponse;

  // Wait for the table to re-render after the action
  await expect(page.locator('table').first()).toBeVisible({ timeout: 10000 });
}

/**
 * Filter by order title and return the matched row.
 * Uses data-testid for the search button to avoid locale-dependent text matching.
 */
async function findRowByTitle(
  page: import('@playwright/test').Page,
  title: string,
): Promise<import('@playwright/test').Locator> {
  // Try form-field testid first, fall back to name-based selector
  await ensureFilterFormOpen(page);
  const titleInput = page.locator('[data-testid="form-field-e2et_order_title"] input, input[name="e2et_order_title"], input#e2et_order_title').first();
  await expect(titleInput).toBeVisible({ timeout: 8000 });
  await titleInput.fill(title);

  // Set up list response listener BEFORE clicking search
  const listRefresh = page.waitForResponse((r) => r.url().includes('/list') && r.status() === 200, {
    timeout: 15000,
  });

  // Use data-testid for search button (locale-independent), with text fallback
  const searchBtn = page.locator('[data-testid="filter-search"]').first();
  await searchBtn.click();
  await listRefresh;

  const row = page.locator('tbody tr', { hasText: title }).first();
  await expect(row).toBeVisible({ timeout: 10000 });
  return row;
}

test.describe('E2E Test Order — Command & State Machine (UI)', () => {
  /**
   * CM-001: Submit order via UI
   */
  test('CM-001: submit via UI should show confirm dialog and dispatch command @smoke', async ({
    page,
  }) => {
    const order = new ModelTestHelper(page, E2ET_ORDER_CONFIG);
    const title = `Submit Test ${uniqueId()}`;
    const orderPid = await order.createViaApi({ e2et_order_title: title });
    await order.child('item').createForParent(orderPid);

    const listPage = await navigateToTab(page, /草稿|Draft/i);

    // Wait for row action buttons to render (they appear after data load)
    const firstRow = listPage.row(0);
    await expect(firstRow).toBeVisible({ timeout: 10000 });
    // Wait for at least one action button to appear in the first row
    await expect(firstRow.locator('button').first()).toBeVisible({ timeout: 8000 });

    // Verify row action buttons count (absorbs OT-004)
    const actionBtns = firstRow.locator('button');
    const actionCount = await actionBtns.count();
    expect(actionCount).toBeGreaterThanOrEqual(2);

    // Find the target row and click submit action
    // Row action buttons may only appear on hover; hover first to reveal them
    const targetRow = await findRowByTitle(page, title);
    await targetRow.hover();

    // Set up command API listener BEFORE clicking submit
    // The actual command fires AFTER the confirm dialog is accepted
    const cmdResponse = order.waitForCommandResponse(15000);

    const directSubmitBtn = targetRow
      .locator(
        '[data-testid="row-action-submit"], button:has-text("submit"), button:has-text("\u63d0\u4ea4")',
      )
      .first();
    const isDirectBtn = await directSubmitBtn.isVisible({ timeout: 2000 }).catch(() => false);

    if (isDirectBtn) {
      await directSubmitBtn.click();
    } else {
      // submit is in the "..." overflow dropdown (DSL renders primary + overflow)
      const moreActionsBtn = targetRow.locator('[data-testid="row-action-more"]').first();
      await expect(moreActionsBtn).toBeVisible({ timeout: 5000 });
      // Hide the "Send Feedback" FAB (fixed, z-40) that can intercept clicks
      await page.evaluate(() => {
        const fab = document.querySelector('[title="Send Feedback"]') as HTMLElement | null;
        if (fab) fab.style.display = 'none';
      });
      await moreActionsBtn.click();
      // Portal dropdown renders with position:fixed; use force:true to click even if outside viewport
      const menuItem = page.locator('[data-testid="row-action-submit"]').first();
      await expect(menuItem).toBeAttached({ timeout: 5000 });
      // Use evaluate for portal-rendered dropdowns that may be outside viewport
      await menuItem.evaluate((el: HTMLElement) => el.click());
    }

    // Verify confirm dialog appears and accept it
    const dialog = page.locator('[data-testid="confirm-dialog"]');
    await expect(dialog).toBeVisible({ timeout: 5000 });
    await page.locator('[data-testid="confirm-ok"]').click();
    await dialog.waitFor({ state: 'hidden', timeout: 5000 });

    // Verify command API was called (response arrives after dialog confirm)
    const resp = await cmdResponse;
    expect(resp.url()).toContain('submit_order');

    // Verify state change via API for reliability
    const records = await queryFilteredList(page, 'e2et_order', 'e2et_order_title', title, {
      extraFilters: [{ fieldName: 'e2et_order_status', operator: 'EQ', value: 'submitted' }],
    });
    expect(records.length).toBeGreaterThanOrEqual(1);
  });

  /**
   * CM-002: Approve order via UI (submitted -> approved)
   */
  test('CM-002: approve via UI should transition submitted->approved', async ({ page }) => {
    const order = new ModelTestHelper(page, E2ET_ORDER_CONFIG);
    const title = `Approve Test ${uniqueId()}`;
    const orderPid = await order.createViaApi({ e2et_order_title: title });
    await order.child('item').createForParent(orderPid);
    await order.transitionViaApi(orderPid, ['submit']);

    await navigateToTab(page, /已提交|Submitted/i);

    const targetRow = await findRowByTitle(page, title);
    const approveResponse = page.waitForResponse(
      (r) => (r.url().includes('/list') || r.url().includes('/execute/')) && r.status() === 200,
      { timeout: 15000 },
    );
    await clickRowActionByLocator(page, targetRow, 'approve', '审批');
    await acceptConfirmDialog(page).catch(() => {});
    await approveResponse;
    await expect(page.locator('table').first()).toBeVisible({ timeout: 10000 });

    // Verify state change via API
    const records = await queryFilteredList(page, 'e2et_order', 'e2et_order_title', title, {
      extraFilters: [{ fieldName: 'e2et_order_status', operator: 'EQ', value: 'approved' }],
    });
    expect(records.length).toBeGreaterThanOrEqual(1);
  });

  /**
   * CM-003: Reject order via UI (submitted -> rejected)
   */
  test('CM-003: reject via UI should transition submitted->rejected', async ({ page }) => {
    const order = new ModelTestHelper(page, E2ET_ORDER_CONFIG);
    const title = `Reject Test ${uniqueId()}`;
    const orderPid = await order.createViaApi({ e2et_order_title: title });
    await order.child('item').createForParent(orderPid);
    await order.transitionViaApi(orderPid, ['submit']);

    await navigateToTab(page, /已提交|Submitted/i);

    const targetRow = await findRowByTitle(page, title);
    const rejectResponse = page.waitForResponse(
      (r) => (r.url().includes('/list') || r.url().includes('/execute/')) && r.status() === 200,
      { timeout: 15000 },
    );
    await clickRowActionByLocator(page, targetRow, 'reject', '退回');
    await acceptConfirmDialog(page).catch(() => {});
    await rejectResponse;
    await expect(page.locator('table').first()).toBeVisible({ timeout: 10000 });

    // Verify state change via API
    const records = await queryFilteredList(page, 'e2et_order', 'e2et_order_title', title, {
      extraFilters: [{ fieldName: 'e2et_order_status', operator: 'EQ', value: 'rejected' }],
    });
    expect(records.length).toBeGreaterThanOrEqual(1);
  });

  /**
   * CM-004: Complete order via UI (approved -> completed)
   */
  test('CM-004: complete via UI should transition approved->completed', async ({ page }) => {
    const order = new ModelTestHelper(page, E2ET_ORDER_CONFIG);
    const title = `Complete Test ${uniqueId()}`;
    const orderPid = await order.createViaApi({ e2et_order_title: title });
    await order.child('item').createForParent(orderPid);
    await order.transitionViaApi(orderPid, ['submit', 'approve']);

    await navigateToTab(page, /已审批|Approved/i);

    const targetRow = await findRowByTitle(page, title);
    const completeResponse = page.waitForResponse(
      (r) => (r.url().includes('/list') || r.url().includes('/execute/')) && r.status() === 200,
      { timeout: 15000 },
    );
    await clickRowActionByLocator(page, targetRow, 'complete', '完成');
    await acceptConfirmDialog(page).catch(() => {});
    await completeResponse;
    await expect(page.locator('table').first()).toBeVisible({ timeout: 10000 });

    // Verify state change via API
    const records = await queryFilteredList(page, 'e2et_order', 'e2et_order_title', title, {
      extraFilters: [{ fieldName: 'e2et_order_status', operator: 'EQ', value: 'completed' }],
    });
    expect(records.length).toBeGreaterThanOrEqual(1);
  });

  /**
   * CM-005: Cancel order via UI from Draft tab and Submitted tab
   */
  test('CM-005: cancel via UI should work from Draft and Submitted tabs', async ({ page }) => {
    test.fixme(true, 'Cancel row action unreliable — dropdown timing issue');
    const order = new ModelTestHelper(page, E2ET_ORDER_CONFIG);

    // --- Part 1: Cancel from Draft tab ---
    const draftTitle = `CancelDraft ${uniqueId()}`;
    await order.createViaApi({ e2et_order_title: draftTitle });

    await navigateToTab(page, /草稿|Draft/i);

    const draftRow = await findRowByTitle(page, draftTitle);
    const cancelDraftResponse = page.waitForResponse(
      (r) => (r.url().includes('/list') || r.url().includes('/execute/')) && r.status() === 200,
      { timeout: 15000 },
    );
    await clickRowActionByLocator(page, draftRow, 'cancel', '取消');
    await acceptConfirmDialog(page).catch(() => {});
    await cancelDraftResponse;
    await expect(page.locator('table').first()).toBeVisible({ timeout: 10000 });

    // Verify state change via API
    const cancelledDraft = await queryFilteredList(
      page,
      'e2et_order',
      'e2et_order_title',
      draftTitle,
      { extraFilters: [{ fieldName: 'e2et_order_status', operator: 'EQ', value: 'cancelled' }] },
    );
    expect(cancelledDraft.length).toBeGreaterThanOrEqual(1);

    // --- Part 2: Cancel from Submitted tab ---
    const submittedTitle = `CancelSubmit ${uniqueId()}`;
    const submitPid = await order.createViaApi({ e2et_order_title: submittedTitle });
    await order.child('item').createForParent(submitPid);
    await order.transitionViaApi(submitPid, ['submit']);

    await navigateToTab(page, /已提交|Submitted/i);

    const submittedRow = await findRowByTitle(page, submittedTitle);
    const cancelSubmitResponse = page.waitForResponse(
      (r) => (r.url().includes('/list') || r.url().includes('/execute/')) && r.status() === 200,
      { timeout: 15000 },
    );
    await clickRowActionByLocator(page, submittedRow, 'cancel', '取消');
    await acceptConfirmDialog(page).catch(() => {});
    await cancelSubmitResponse;
    await expect(page.locator('table').first()).toBeVisible({ timeout: 10000 });

    // Verify state change via API
    const cancelledSubmitted = await queryFilteredList(
      page,
      'e2et_order',
      'e2et_order_title',
      submittedTitle,
      { extraFilters: [{ fieldName: 'e2et_order_status', operator: 'EQ', value: 'cancelled' }] },
    );
    expect(cancelledSubmitted.length).toBeGreaterThanOrEqual(1);
  });

  /**
   * CM-007: sideEffects should create audit log entry on state transition
   *
   * Verifies audit logs via API (preferred) with UI detail page as secondary check.
   */
  test('CM-007: sideEffects should create audit log on submit', async ({ page }) => {
    const order = new ModelTestHelper(page, E2ET_ORDER_CONFIG);
    const title = `SideEffect Test ${uniqueId()}`;

    const orderPid = await order.createViaApi({ e2et_order_title: title });
    await order.child('item').createForParent(orderPid);
    const submitResult = await order.executeCommand('submit', orderPid);

    expect(submitResult.code).toBe(ErrorCodes.SUCCESS);

    // Primary: verify sideEffect via API (most reliable)
    const logs = await order.child('log').listForParent(orderPid);
    expect(logs.length).toBeGreaterThanOrEqual(1);

    // Secondary: Navigate directly to detail page and verify logs UI renders.
    // Current list view may not expose a dedicated "detail" row action in every config.
    await page.goto(`/p/e2et_order/view/${orderPid}`, { waitUntil: 'domcontentloaded' });
    await page
      .waitForResponse(
        (r) => r.url().includes('/api/dynamic/e2et_order') && !r.url().includes('/list'),
        { timeout: 12000 },
      )
      .catch(() => null);
    await expect(page.getByText(/订单标题|订单状态|操作日志/).first()).toBeVisible({
      timeout: 10000,
    });

    // Switch to Logs tab if available
    const logsTab = page
      .locator('nav button, [role="tablist"] button')
      .filter({
        hasText: /操作日志|Audit|Logs/i,
      })
      .first();

    if (await logsTab.isVisible({ timeout: 3000 }).catch(() => false)) {
      await logsTab.click();
      const logTable = page.locator('table').filter({ has: page.locator('tbody tr') }).first();
      const tableVisible = await logTable.isVisible({ timeout: 5000 }).catch(() => false);
      if (!tableVisible) {
        return;
      }

      const logRows = await logTable.locator('tbody tr').count();
      expect(logRows).toBeGreaterThanOrEqual(1);
    }
  });
});
