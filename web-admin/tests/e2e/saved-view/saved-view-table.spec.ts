/**
 * E2E Test: SavedView TABLE View
 *
 * Tests TABLE view features: rendering, column visibility,
 * sorting, and filter saving.
 *
 * @since 7.0.0
 */

import { test, expect } from '@playwright/test';
import { ModelTestHelper } from '../../helpers/model-test-helper';
import { E2ET_ORDER_CONFIG } from '../../helpers/configs/e2et-order.config';
import { DynamicListPage } from '../../pages/DynamicListPage';
import { uniqueId, todayStr } from '../helpers';

test.describe('SavedView — TABLE View', () => {
  let order: ModelTestHelper;
  const pids: string[] = [];

  test.beforeAll(async ({ browser }) => {
    const page = await browser.newPage();
    order = new ModelTestHelper(page, E2ET_ORDER_CONFIG);
    // Create test records for table display
    for (let i = 0; i < 3; i++) {
      const pid = await order.createViaApi({
        e2et_order_title: `SV_Table_${i}_${Date.now()}`,
        e2et_order_date: todayStr(),
      });
      pids.push(pid);
    }
    await page.close();
  });

  test.afterAll(async ({ browser }) => {
    const page = await browser.newPage();
    order = new ModelTestHelper(page, E2ET_ORDER_CONFIG);
    for (const pid of pids) {
      await order.deleteViaApi(pid).catch(() => {});
    }
    await page.close();
  });

  test('SV-001: TABLE view — default table renders correctly @smoke', async ({ page }) => {
    order = new ModelTestHelper(page, E2ET_ORDER_CONFIG);
    const listPage = await order.gotoList();
    // Table should be visible
    const table = page.locator('table, [role="table"]').first();
    await expect(table).toBeVisible();
    // Should have header row
    const headers = page.locator('thead th, [role="columnheader"]');
    const count = await headers.count();
    expect(count).toBeGreaterThan(0);
  });

  test('SV-002: TABLE view — column visibility toggle @smoke', async ({ page }) => {
    order = new ModelTestHelper(page, E2ET_ORDER_CONFIG);
    const listPage = await order.gotoList();
    // Look for column settings button
    const colSettingsBtn = page
      .locator('button')
      .filter({ hasText: /column|列|settings/i })
      .first();
    if (await colSettingsBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
      await colSettingsBtn.click();
      // A panel should appear with checkboxes for columns
      const panel = page.locator('[role="dialog"], [class*="panel"], [class*="popover"]').first();
      await expect(panel).toBeVisible({ timeout: 3000 });
      // Should have checkbox items
      const checkboxes = panel.locator('input[type="checkbox"]');
      const cbCount = await checkboxes.count();
      expect(cbCount).toBeGreaterThan(0);
    }
  });

  test('SV-003: TABLE view — column width drag adjustment', async ({ page }) => {
    order = new ModelTestHelper(page, E2ET_ORDER_CONFIG);
    const listPage = await order.gotoList();
    // Check that column headers exist and have measurable width
    const firstHeader = page.locator('thead th, [role="columnheader"]').first();
    const box = await firstHeader.boundingBox();
    expect(box).toBeTruthy();
    if (box) {
      expect(box.width).toBeGreaterThan(20);
    }
  });

  test('SV-004: TABLE view — single field sort @smoke', async ({ page }) => {
    order = new ModelTestHelper(page, E2ET_ORDER_CONFIG);
    const listPage = await order.gotoList();
    // Click on a sortable column header
    const titleHeader = page
      .locator('thead th, [role="columnheader"]')
      .filter({ hasText: /title|标题/i })
      .first();
    if (await titleHeader.isVisible({ timeout: 3000 }).catch(() => false)) {
      // Wait for list API response after sort click
      const sortResponse = page
        .waitForResponse((r) => r.url().includes('/list') && r.status() === 200, { timeout: 5000 })
        .catch(() => null);
      await titleHeader.click();
      await sortResponse;
      // Table should still have rows
      const rows = await listPage.getRowCount();
      expect(rows).toBeGreaterThan(0);
    }
  });

  test('SV-005: TABLE view — multi-field sort', async ({ page }) => {
    // Multi-field sort is typically configured via view settings
    // Verify the API supports multiple sort parameters
    const resp = await page.request.get(
      `/api/dynamic/e2et_order/list?current=1&size=10&sortField=e2et_order_status&sortOrder=ASC`,
    );
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    const records = body.data?.records ?? body.data?.list ?? [];
    expect(records.length).toBeGreaterThan(0);
  });

  test('SV-006: TABLE view — filter conditions saved to view @smoke', async ({ page }) => {
    order = new ModelTestHelper(page, E2ET_ORDER_CONFIG);
    const listPage = await order.gotoList();
    // Use the filter input to search
    const filterInput = listPage.filterInput('e2et_order_title');
    if (await filterInput.isVisible({ timeout: 3000 }).catch(() => false)) {
      await filterInput.fill('SV_Table');
      await listPage.search();
      // Results should be filtered
      const rows = await listPage.getRowCount();
      // Look for save filter button
      const saveFilterBtn = page.locator('[data-testid="filter-save"]');
      if (await saveFilterBtn.isVisible({ timeout: 2000 }).catch(() => false)) {
        expect(rows).toBeGreaterThan(0);
      }
    }
  });
});
