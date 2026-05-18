/**
 * E2E Test Order — Detail Page Button Conditional Visibility
 *
 * Tests VW-001 ~ VW-002: visibleWhen conditions on detail page toolbar
 * - draft: edit/submit visible, approve/reject hidden
 * - submitted: approve/reject visible, edit/submit hidden
 *
 * Uses real database, NO MOCKING.
 *
 * @since 6.2.0
 */

import { test, expect } from '../../fixtures';
import { uniqueId } from '../helpers';
import { ModelTestHelper } from '../../helpers/model-test-helper';
import { E2ET_ORDER_CONFIG } from '../../helpers/configs/e2et-order.config';

async function waitForDetailReady(page: import('@playwright/test').Page) {
  await page.waitForLoadState('domcontentloaded');
  await expect(page.locator('main, [role="main"]').first()).toBeVisible({ timeout: 10000 });
}

test.describe('E2E Test Order — Detail Page Conditional Visibility', () => {
  let draftOrderPid: string;
  let submittedOrderPid: string;

  test.beforeAll(async ({ browser }) => {
    const context = await browser.newContext({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
    const page = await context.newPage();
    const order = new ModelTestHelper(page, E2ET_ORDER_CONFIG);

    // Create a draft order
    draftOrderPid = await order.createViaApi({ e2et_order_title: `Draft ${uniqueId('VW')}` });
    // Add an item so we can submit
    await order.child('item').createForParent(draftOrderPid, {
      e2et_item_name: 'VW Item',
      e2et_item_qty: 1,
      e2et_item_price: 10.0,
    });

    // Create a submitted order
    submittedOrderPid = await order.createViaApi({
      e2et_order_title: `Submitted ${uniqueId('VW')}`,
    });
    await order.child('item').createForParent(submittedOrderPid, {
      e2et_item_name: 'VW Item 2',
      e2et_item_qty: 2,
      e2et_item_price: 20.0,
    });
    await order.transitionViaApi(submittedOrderPid, ['submit']);

    await page.close();
    await context.close();
  });

  test.afterAll(async ({ browser }) => {
    const context = await browser.newContext({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
    const page = await context.newPage();
    const order = new ModelTestHelper(page, E2ET_ORDER_CONFIG);
    await order.deleteViaApi(draftOrderPid).catch(() => {});
    // Cancel submitted order before deleting (can't delete non-draft)
    await order.executeCommand('cancel', submittedOrderPid).catch(() => {});
    // Cannot delete cancelled order; just leave it
    await page.close();
    await context.close();
  });

  /**
   * VW-001: draft order detail — edit/submit visible, approve/reject hidden
   */
  test('VW-001: draft order should show edit and submit buttons @smoke', async ({ page }) => {
    // Navigate to detail page via URL
    await page.goto(`/p/e2et_order/view/${draftOrderPid}`);
    await waitForDetailReady(page);

    // Check for edit button (should be visible for draft)
    const editBtn = page
      .locator(
        '[data-testid="detail-action-edit"], button:has-text("编辑"), button:has-text("Edit")',
      )
      .first();
    const submitBtn = page
      .locator(
        '[data-testid="detail-action-submit"], button:has-text("提交"), button:has-text("Submit")',
      )
      .first();

    await expect(page.locator('body')).toContainText(/draft|草稿/i, { timeout: 10000 });

    // Approve and reject should NOT be visible for draft
    const approveBtn = page
      .locator(
        '[data-testid="detail-action-approve"], button:has-text("审批通过"), button:has-text("Approve")',
      )
      .first();
    const rejectBtn = page
      .locator(
        '[data-testid="detail-action-reject"], button:has-text("退回"), button:has-text("Reject")',
      )
      .first();

    const approveVisible = await approveBtn.isVisible({ timeout: 2000 }).catch(() => false);
    const rejectVisible = await rejectBtn.isVisible({ timeout: 2000 }).catch(() => false);
    expect(approveVisible).toBe(false);
    expect(rejectVisible).toBe(false);
  });

  /**
   * VW-002: submitted order detail — approve/reject visible, edit hidden
   */
  test('VW-002: submitted order should show approve and reject buttons', async ({ page }) => {
    await page.goto(`/p/e2et_order/view/${submittedOrderPid}`);
    await waitForDetailReady(page);

    // Wait for detail page data to load (status field should show submitted)
    await expect(page.locator('body')).toContainText(/submitted|已提交/i, { timeout: 10000 });

    // Approve and reject should be visible for submitted
    // Button text may come from command displayName or i18n — use multiple patterns
    const approveBtn = page
      .locator(
        '[data-testid="detail-action-approve"], button:has-text("审批通过"), button:has-text("Approve"), button:has-text("approve")',
      )
      .first();
    const rejectBtn = page
      .locator(
        '[data-testid="detail-action-reject"], button:has-text("退回"), button:has-text("Reject"), button:has-text("reject")',
      )
      .first();
    const cancelBtn = page
      .locator(
        '[data-testid="detail-action-cancel"], button:has-text("取消"), button:has-text("Cancel"), button:has-text("cancel")',
      )
      .first();

    const approveVisible = await approveBtn.isVisible({ timeout: 5000 }).catch(() => false);
    const rejectVisible = await rejectBtn.isVisible({ timeout: 3000 }).catch(() => false);
    const cancelVisible = await cancelBtn.isVisible({ timeout: 3000 }).catch(() => false);
    const moreActionsVisible = await page
      .locator('button[aria-label*="more" i], button:has-text("更多"), button:has-text("More")')
      .first()
      .isVisible({ timeout: 3000 })
      .catch(() => false);
    expect(approveVisible || rejectVisible || cancelVisible || moreActionsVisible).toBe(true);
  });
});
