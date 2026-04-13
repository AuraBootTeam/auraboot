/**
 * Inventory Plugin — Menu Full Coverage
 *
 * Verifies all inventory menu codes exist in the user menu API
 * and each leaf menu path renders without crashing.
 */

import { test, expect } from '../../fixtures';
import { ensureFilterFormOpen } from '../helpers/index';

type MenuNode = {
  code?: string;
  path?: string;
  name?: string;
  children?: MenuNode[];
};

/** Expected inventory menu codes and their paths (from menus.json). */
const INVENTORY_MENUS: Array<{ code: string; path: string; leaf: boolean }> = [
  { code: 'inv_root', path: '/inventory', leaf: false },
  { code: 'inv_warehouses', path: '/inventory/warehouses', leaf: true },
  { code: 'inv_warehouse_locations', path: '/inventory/warehouse-locations', leaf: true },
  { code: 'inv_inbound', path: '/inventory/warehouse-in', leaf: true },
  { code: 'inv_outbound', path: '/inventory/warehouse-out', leaf: true },
  { code: 'inv_balance', path: '/inventory/inventory', leaf: true },
  { code: 'inv_lots', path: '/inventory/lots', leaf: true },
  { code: 'inv_inventory_holds', path: '/inventory/inventory-holds', leaf: true },
  { code: 'inv_stock_checks', path: '/inventory/stock-checks', leaf: true },
  { code: 'inv_transfers', path: '/inventory/stock-transfers', leaf: true },
  { code: 'inv_pick_orders', path: '/inventory/pick-orders', leaf: true },
];

function flattenMenus(nodes: MenuNode[] = [], out: MenuNode[] = []): MenuNode[] {
  for (const node of nodes) {
    out.push(node);
    if (Array.isArray(node.children) && node.children.length > 0) {
      flattenMenus(node.children, out);
    }
  }
  return out;
}

function isCrashText(text: string): boolean {
  return /Application Error|Unhandled Runtime Error|TypeError:|ReferenceError:|500 Internal Server Error/i.test(
    text,
  );
}

test.describe('Inventory Menu Full Coverage', () => {
  test.setTimeout(90_000);

  test('INV-MENU-001: all inventory menu codes present in /api/menu/user', async ({ page }) => {
    const menuResp = await page.request.get('/api/menu/user');
    expect(menuResp.ok()).toBe(true);

    const menuJson = await menuResp.json();
    const tree: MenuNode[] = menuJson.data || menuJson;
    const allMenus = flattenMenus(tree);
    const apiMenuCodes = new Set(allMenus.map((m) => m.code).filter(Boolean));

    for (const menu of INVENTORY_MENUS) {
      expect(apiMenuCodes.has(menu.code), `menu code ${menu.code} should exist in user menus`).toBe(
        true,
      );
    }

    // Verify paths match
    for (const menu of INVENTORY_MENUS) {
      const found = allMenus.find((m) => m.code === menu.code);
      expect(found, `menu ${menu.code} should exist`).toBeTruthy();
      expect(found!.path, `menu ${menu.code} path should be ${menu.path}`).toBe(menu.path);
    }
  });

  test('INV-MENU-002: all leaf menu paths render without crash', async ({ page }) => {
    const leafMenus = INVENTORY_MENUS.filter((m) => m.leaf);
    let visited = 0;
    let withContent = 0;

    for (const menu of leafMenus) {
      await page.goto(menu.path, { waitUntil: 'domcontentloaded' });
      await expect(page.locator('body')).toBeVisible();

      const text = (await page.locator('body').textContent()) || '';
      expect(isCrashText(text), `menu ${menu.code} (${menu.path}) should not crash`).toBe(false);

      const hasContent = await page
        .locator(
          'main, table, [role="table"], form, [role="tablist"], [data-testid="dynamic-list"], [data-testid="dynamic-form"]',
        )
        .first()
        .isVisible({ timeout: 8_000 })
        .catch(() => false);

      if (hasContent) {
        withContent += 1;

        // Probe a non-destructive interaction
        await ensureFilterFormOpen(page);
        const searchBtn = page
          .locator('[data-testid="filter-search"], [data-testid="filter-btn-search"]')
          .first();
        if (await searchBtn.isVisible({ timeout: 1_500 }).catch(() => false)) {
          await searchBtn.click().catch(() => {});
        }
      }

      visited += 1;
    }

    expect(visited).toBe(leafMenus.length);
    expect(withContent).toBeGreaterThan(0);
  });
});
