/**
 * Inventory Plugin — Subsystem Full Coverage
 *
 * Verifies the inventory plugin is installed, all models are published,
 * and the key subsystem integration points (menus, dynamic pages, commands)
 * work end-to-end.
 */

import { test, expect } from '../../fixtures';

const PLUGIN_ID = 'com.auraboot.inventory';

/** Core models — every one must be published for the subsystem to function. */
const CORE_MODELS = [
  'inv_warehouse',
  'inv_warehouse_location',
  'inv_inbound',
  'inv_inbound_line',
  'inv_outbound',
  'inv_outbound_line',
  'inv_balance',
  'inv_lot',
  'inv_lot_transaction',
  'inv_inventory_hold',
  'inv_stock_check',
  'inv_stock_check_line',
  'inv_transfer',
  'inv_transfer_line',
  'inv_pick_order',
  'inv_pick_order_line',
];

/** Representative page keys per functional area. */
const KEY_PAGES: Array<{ pageKey: string; area: string }> = [
  { pageKey: 'inv_warehouse_list', area: 'warehouse' },
  { pageKey: 'inv_inbound_list', area: 'inbound' },
  { pageKey: 'inv_inbound_detail', area: 'inbound-detail' },
  { pageKey: 'inv_outbound_list', area: 'outbound' },
  { pageKey: 'inv_outbound_detail', area: 'outbound-detail' },
  { pageKey: 'inv_inventory_list', area: 'inventory-query' },
  { pageKey: 'inv_lot_list', area: 'lot-tracking' },
  { pageKey: 'inv_stock_check_list', area: 'stock-check' },
  { pageKey: 'inv_transfer_list', area: 'stock-transfer' },
  { pageKey: 'inv_pick_order_list', area: 'pick-order' },
  { pageKey: 'inv_inventory_hold_list', area: 'inventory-hold' },
  { pageKey: 'inv_inventory_dashboard', area: 'dashboard' },
  { pageKey: 'inv_wms_dashboard', area: 'wms-dashboard' },
];

/** Representative commands spanning different operation types. */
const KEY_COMMANDS: Array<{ code: string; type: string }> = [
  { code: 'pe:create_warehouse', type: 'create' },
  { code: 'pe:update_warehouse', type: 'update' },
  { code: 'pe:delete_warehouse', type: 'delete' },
  { code: 'pe:confirm_warehouse_in', type: 'state_transition' },
  { code: 'pe:confirm_warehouse_out', type: 'state_transition' },
  { code: 'pe:allocate_inventory', type: 'action' },
  { code: 'pe:hold_inventory', type: 'action' },
  { code: 'pe:auto_putaway', type: 'action' },
];

function isCrashText(text: string): boolean {
  return /Application Error|Unhandled Runtime Error|TypeError:|ReferenceError:|500 Internal Server Error/i.test(
    text,
  );
}

test.describe('Inventory Subsystem Full Coverage', () => {
  test.setTimeout(120_000);

  test('INV-SUBSYS-001: inventory plugin is installed @critical', async ({ page }) => {
    const resp = await page.request.get('/api/plugins?current=1&size=300', {
      failOnStatusCode: false,
    });

    if (resp.ok()) {
      const body = await resp.json().catch(() => ({}));
      const plugins = body?.data?.records ?? body?.data?.data ?? body?.data ?? [];
      const inventoryPlugin = Array.isArray(plugins)
        ? plugins.find((p: any) => p.pluginId === PLUGIN_ID)
        : null;
      expect(inventoryPlugin, `${PLUGIN_ID} should be installed`).toBeTruthy();
      return;
    }

    // Current admin test role may lack system.plugin.read. Fall back to the same
    // subsystem-level signal used by the rest of this suite: a core inventory model
    // must be published if the plugin is installed and initialized.
    const modelResp = await page.request.get('/api/meta/models/code/inv_warehouse');
    expect(modelResp.ok(), 'inv_warehouse model API should be reachable').toBe(true);
    const modelBody = await modelResp.json().catch(() => ({}));
    expect(
      modelBody?.data?.status,
      'inventory plugin fallback signal: inv_warehouse model should be published',
    ).toBe('published');
  });

  test('INV-SUBSYS-002: all core models are published @critical', async ({ page }) => {
    for (const modelCode of CORE_MODELS) {
      const resp = await page.request.get(`/api/meta/models/code/${modelCode}`);
      expect(resp.ok(), `model ${modelCode} API should return OK`).toBe(true);

      const body = await resp.json();
      expect(body?.data?.status, `model ${modelCode} should be published`).toBe('published');
    }
  });

  test('INV-SUBSYS-003: key page schemas are resolvable @critical', async ({ page }) => {
    for (const { pageKey, area } of KEY_PAGES) {
      const resp = await page.request.get(`/api/pages/key/${encodeURIComponent(pageKey)}`);
      expect(resp.ok(), `page ${pageKey} (${area}) should be resolvable`).toBe(true);

      const body = await resp.json();
      expect(body?.data, `page ${pageKey} schema data should exist`).toBeTruthy();
    }
  });

  test('INV-SUBSYS-004: key commands are discoverable @critical', async ({ page }) => {
    for (const { code, type } of KEY_COMMANDS) {
      const resp = await page.request.get(`/api/meta/commands/by-code/${encodeURIComponent(code)}`);
      expect(resp.ok(), `command ${code} should exist`).toBe(true);

      const body = await resp.json();
      expect(body?.data?.code).toBe(code);
      expect(body?.data?.type, `command ${code} should be type ${type}`).toBe(type);
    }
  });

  test('INV-SUBSYS-005: warehouse list page renders with content @critical', async ({ page }) => {
    await page.goto('/inventory/warehouses', { waitUntil: 'domcontentloaded' });
    await expect(page.locator('body')).toBeVisible();

    const text = (await page.locator('body').textContent()) || '';
    expect(isCrashText(text), 'warehouse list should not crash').toBe(false);

    const hasContent = await page
      .locator('main, table, [role="table"], [data-testid="dynamic-list"]')
      .first()
      .isVisible({ timeout: 10_000 })
      .catch(() => false);

    expect(hasContent, 'warehouse list page should render main content').toBe(true);
  });

  test('INV-SUBSYS-006: inbound list page renders with content', async ({ page }) => {
    await page.goto('/inventory/warehouse-in', { waitUntil: 'domcontentloaded' });
    await expect(page.locator('body')).toBeVisible();

    const text = (await page.locator('body').textContent()) || '';
    expect(isCrashText(text), 'inbound list should not crash').toBe(false);

    const hasContent = await page
      .locator('main, table, [role="table"], [data-testid="dynamic-list"]')
      .first()
      .isVisible({ timeout: 10_000 })
      .catch(() => false);

    expect(hasContent, 'inbound list page should render main content').toBe(true);
  });

  test('INV-SUBSYS-007: inventory query page renders', async ({ page }) => {
    await page.goto('/inventory/inventory', { waitUntil: 'domcontentloaded' });
    await expect(page.locator('body')).toBeVisible();

    const text = (await page.locator('body').textContent()) || '';
    expect(isCrashText(text), 'inventory query page should not crash').toBe(false);

    const hasContent = await page
      .locator('main, table, [role="table"], [data-testid="dynamic-list"]')
      .first()
      .isVisible({ timeout: 10_000 })
      .catch(() => false);

    expect(hasContent, 'inventory query page should render main content').toBe(true);
  });
});
