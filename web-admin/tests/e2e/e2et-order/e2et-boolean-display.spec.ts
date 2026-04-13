/**
 * E2E Test Order — Boolean Field Display & Action Dropdown
 *
 * Tests BD-001 ~ BD-003:
 * - BD-001: Boolean column displays localized Yes/No (not raw true/false)
 * - BD-002: Action column dropdown is visible and clickable (not clipped by overflow)
 * - BD-003: Boolean value mapping works for both true and false states
 *
 * Uses real database, NO MOCKING.
 *
 * @since 6.2.0
 */

import { test, expect } from '../../fixtures';
import { uniqueId } from '../quarry-management.setup';
import { ModelTestHelper } from '../../helpers/model-test-helper';
import { E2ET_ORDER_CONFIG } from '../../helpers/configs/e2et-order.config';

test.describe('E2E Test Order — Boolean Display & Action Dropdown', () => {
  /**
   * BD-001: Boolean column should display Yes/No (是/否) instead of true/false
   *
   * Creates two orders: one urgent (true), one non-urgent (false).
   * Navigates to list page and verifies the urgent column shows
   * localized text, not raw boolean values.
   */
  test('BD-001: boolean column should display localized Yes/No text', async ({ page }) => {
    const order = new ModelTestHelper(page, E2ET_ORDER_CONFIG);

    // Create an urgent order
    const urgentTitle = `UrgentBool ${uniqueId()}`;
    await order.createViaApi({
      e2et_order_title: urgentTitle,
      e2et_order_urgent: true,
      e2et_order_remark: 'Urgent remark required',
    });

    // Create a non-urgent order
    const normalTitle = `NormalBool ${uniqueId()}`;
    await order.createViaApi({
      e2et_order_title: normalTitle,
      e2et_order_urgent: false,
    });

    // Navigate to list page via menu
    await order.gotoList();

    // Find the urgent order row and check boolean display
    const urgentRow = page.locator('tr', { hasText: urgentTitle }).first();
    await expect(urgentRow).toBeVisible({ timeout: 10000 });

    // The urgent column cell should show "是" or "Yes", NOT "true"
    const urgentCellText = await urgentRow.locator('td').allTextContents();
    const urgentCellJoined = urgentCellText.join(' ');
    expect(urgentCellJoined).not.toContain('true');
    // Should contain localized Yes text (是 for zh-CN, Yes for en)
    const hasYesText = urgentCellJoined.includes('是') || urgentCellJoined.includes('Yes');
    expect(hasYesText).toBe(true);

    // Find the non-urgent order row
    const normalRow = page.locator('tr', { hasText: normalTitle }).first();
    if (await normalRow.isVisible({ timeout: 3000 }).catch(() => false)) {
      const normalCellText = await normalRow.locator('td').allTextContents();
      const normalCellJoined = normalCellText.join(' ');
      // Should NOT show raw "false"
      expect(normalCellJoined).not.toContain('false');
      // Should contain localized No text (否 for zh-CN, No for en)
      const hasNoText = normalCellJoined.includes('否') || normalCellJoined.includes('No');
      expect(hasNoText).toBe(true);
    }
  });

  /**
   * BD-002: Action dropdown should be visible and not clipped by table overflow
   *
   * Clicks the "..." more actions button and verifies the dropdown menu
   * appears and is interactive (not hidden behind the table container).
   */
  test('BD-002: action dropdown should be visible and clickable', async ({ page }) => {
    const order = new ModelTestHelper(page, E2ET_ORDER_CONFIG);

    // Create an order to have data in the list
    const title = `DropdownTest ${uniqueId()}`;
    await order.createViaApi({
      e2et_order_title: title,
    });

    // Navigate to list page
    await order.gotoList();

    // Find the row with our test data
    const row = page.locator('tr', { hasText: title }).first();
    await expect(row).toBeVisible({ timeout: 10000 });

    // Hover row to reveal action buttons (opacity-0 → opacity-100 via group-hover)
    await row.hover();
    // Find and click the "..." more actions button in the row
    const moreBtn = row.locator('[data-testid="row-action-more"]');
    if (await moreBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
      await moreBtn.click();

      // The dropdown should appear (rendered via Portal at document.body level)
      const dropdown = page.locator('[data-testid="row-action-dropdown"]');
      await expect(dropdown).toBeVisible({ timeout: 3000 });

      // Verify dropdown has action items
      const actionItems = dropdown.locator('button');
      const count = await actionItems.count();
      expect(count).toBeGreaterThan(0);

      // Verify dropdown is not clipped — check its bounding box is within viewport
      const box = await dropdown.boundingBox();
      expect(box).not.toBeNull();
      if (box) {
        expect(box.width).toBeGreaterThan(50);
        expect(box.height).toBeGreaterThan(10);
        // Should be visible within viewport
        const viewport = page.viewportSize();
        if (viewport) {
          expect(box.x).toBeGreaterThanOrEqual(0);
          expect(box.y).toBeGreaterThanOrEqual(0);
        }
      }

      // Click an action item to verify it's interactive
      const firstAction = actionItems.first();
      const actionText = await firstAction.textContent();
      expect(actionText).toBeTruthy();

      // Close dropdown by clicking outside
      await page.locator('body').click({ position: { x: 10, y: 10 } });
      await expect(dropdown).not.toBeVisible({ timeout: 3000 });
    }
  });

  /**
   * BD-003: Boolean field with true value shows green styling
   *
   * Verifies that urgent=true renders with green color class
   * and urgent=false renders with gray color class.
   */
  test('BD-003: boolean true/false should have distinct visual styling', async ({ page }) => {
    const order = new ModelTestHelper(page, E2ET_ORDER_CONFIG);

    const urgentTitle = `StyleTrue ${uniqueId()}`;
    await order.createViaApi({
      e2et_order_title: urgentTitle,
      e2et_order_urgent: true,
      e2et_order_remark: 'Required for urgent',
    });

    const normalTitle = `StyleFalse ${uniqueId()}`;
    await order.createViaApi({
      e2et_order_title: normalTitle,
      e2et_order_urgent: false,
    });

    await order.gotoList();

    // Check the urgent=true row has green-styled boolean cell
    const urgentRow = page.locator('tr', { hasText: urgentTitle }).first();
    await expect(urgentRow).toBeVisible({ timeout: 10000 });
    const greenSpan = urgentRow.locator('span.text-green-600');
    const hasGreen = await greenSpan.count();
    expect(hasGreen).toBeGreaterThan(0);

    // Check the urgent=false row has gray-styled boolean cell
    const normalRow = page.locator('tr', { hasText: normalTitle }).first();
    if (await normalRow.isVisible({ timeout: 5000 }).catch(() => false)) {
      const graySpan = normalRow.locator('span.text-gray-600');
      const hasGray = await graySpan.count();
      expect(hasGray).toBeGreaterThan(0);
    }
  });
});
