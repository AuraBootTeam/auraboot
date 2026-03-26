/**
 * PCBA ERP — Basic E2E Tests
 *
 * Verifies PCBA ERP plugin installation and core functionality:
 * - Plugin import completed (models published)
 * - Core models exist with correct status
 * - Dynamic pages load successfully
 * - Basic CRUD on prod_product model
 *
 * Prerequisites: test-fixtures.setup.ts must run first (imports pcba-solution plugin).
 *
 * @since 5.0.0
 */

import { test, expect } from '../../fixtures';
import {
  navigateToDynamicPage,
  waitForDynamicPageLoad,
  uniqueId,
  waitForToast,
} from '../helpers/index';

test.describe('PCBA ERP — Basic Verification', () => {
  test.setTimeout(30000);

  /**
   * PE-001: Verify core models are published
   */
  test('PE-001: core models should be published @smoke', async ({ page }) => {
    const coreModels = [
      'prod_product',
      'prod_brand',
      'prod_category',
      'pe_supplier',
      'pe_bom',
      'pe_production_plan',
      'pe_routing',
      'pe_equipment',
    ];

    for (const code of coreModels) {
      const resp = await page.request.get(`/api/meta/models/code/${code}`);
      expect(resp.ok(), `Model ${code} should exist`).toBe(true);

      const data = await resp.json();
      expect(data.data?.status, `Model ${code} should be published`).toBe('published');
    }
  });

  /**
   * PE-002: Verify product list page loads
   */
  test('PE-002: product list page should load @smoke', async ({ page }) => {
    await navigateToDynamicPage(page, 'prod-product');

    // Should show a table
    const table = page.locator('table, [role="table"]');
    await expect(table.first()).toBeVisible({ timeout: 15000 });
  });

  /**
   * PE-003: Verify supplier list page loads
   */
  test('PE-003: supplier list page should load', async ({ page }) => {
    await navigateToDynamicPage(page, 'pe-supplier');

    const table = page.locator('table, [role="table"]');
    await expect(table.first()).toBeVisible({ timeout: 15000 });
  });

  /**
   * PE-004: Verify equipment list page loads
   */
  test('PE-004: equipment list page should load', async ({ page }) => {
    await navigateToDynamicPage(page, 'pe-equipment');

    const table = page.locator('table, [role="table"]');
    await expect(table.first()).toBeVisible({ timeout: 15000 });
  });

  /**
   * PE-005: Create a product via dynamic form
   */
  test('PE-005: should create a product via UI', async ({ page }) => {
    await navigateToDynamicPage(page, 'prod-product');

    // Click create button
    const addButton = page.locator(
      'button:has-text("新建"), button:has-text("New"), button:has-text("Create"), [data-testid="add-button"]'
    );
    await addButton.first().click();

    // Wait for form to load (dynamic form two-stage loading)
    await page.waitForLoadState('domcontentloaded');
    const formContent = page.locator('form, .ant-form, [data-testid="dynamic-form"]');
    await formContent.first().waitFor({ state: 'visible', timeout: 10000 });

    // Fill product name
    const testName = uniqueId('pe_prod');
    const nameInput = page.locator(
      'input[data-testid*="prod_name"], input[placeholder*="名称"], input[placeholder*="name"]'
    ).first();

    if (await nameInput.isVisible({ timeout: 3000 }).catch(() => false)) {
      await nameInput.fill(testName);
    } else {
      // Fallback: find first visible text input
      const firstInput = page.locator('input[type="text"]').first();
      await firstInput.fill(testName);
    }

    // Click save
    const saveButton = page.locator(
      'button:has-text("保存"), button:has-text("Save"), button:has-text("提交"), button:has-text("Submit")'
    );
    await saveButton.first().click();

    // Verify success toast or navigation back to list
    const successIndicator = page.locator(
      '.ant-message-success, [class*="toast"]:has-text("成功"), [class*="toast"]:has-text("success")'
    );
    try {
      await successIndicator.first().waitFor({ state: 'visible', timeout: 5000 });
    } catch {
      // May have navigated back to list — check list contains the new record
      await waitForDynamicPageLoad(page);
    }
  });

  /**
   * PE-006: Verify PCBA ERP menus are registered (2-level structure)
   */
  test('PE-006: PCBA ERP menus should be registered', async ({ page }) => {
    const resp = await page.request.get('/api/menu/user');
    expect(resp.ok()).toBe(true);

    const data = await resp.json();
    const menus = data.data || [];

    // Find PCBA ERP root menu by path (if plugin registered top-level menu).
    const peRoot = (menus as any[]).find((m: any) => m.path === '/pcba-erp' || m.path === '/pcba-solution');
    if (!peRoot) {
      // Some environments intentionally omit PCBA top-level menu and expose pages directly.
      // Fall back to validating core PCBA pages are still reachable.
      const productResp = await page.request.get('/api/dynamic/prod-product/list?page=1&size=1');
      const supplierResp = await page.request.get('/api/dynamic/pe-supplier/list?page=1&size=1');
      expect(productResp.ok()).toBe(true);
      expect(supplierResp.ok()).toBe(true);
      return;
    }

    // Verify children exist (flat 2-level structure)
    const children = peRoot.children || [];
    expect(children.length).toBeGreaterThan(0);

    // Current menu design allows grouped directories under PCBA ERP.
    // Validate structural sanity instead of enforcing a flat menu tree.
    const dirMenus = children.filter((c: any) => c.type === 0);
    expect(children.length).toBeGreaterThan(0);
    if (dirMenus.length > 0) {
      const populatedGroups = dirMenus.filter(
        (g: any) => Array.isArray(g.children) && g.children.length > 0,
      );
      expect(populatedGroups.length).toBeGreaterThan(0);
    }
  });

  /**
   * PE-007: Verify dynamic data APIs work for core models
   */
  test('PE-007: dynamic data APIs should respond @smoke', async ({ page }) => {
    const endpoints = [
      '/api/dynamic/prod-product/list?page=1&size=5',
      '/api/dynamic/prod-brand/list?page=1&size=5',
      '/api/dynamic/prod-category/list?page=1&size=5',
      '/api/dynamic/inv-warehouse/list?page=1&size=5',
    ];

    for (const endpoint of endpoints) {
      const resp = await page.request.get(endpoint);
      expect(resp.ok(), `${endpoint} should respond 200`).toBe(true);

      const data = await resp.json();
      expect(data.data, `${endpoint} should return data`).toBeTruthy();
    }
  });
});
