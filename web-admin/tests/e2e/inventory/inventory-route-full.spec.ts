/**
 * Inventory Plugin — Route Full Coverage
 *
 * Verifies all inventory-related routes (menu paths + dynamic page paths)
 * render without crashing and show meaningful content.
 */

import { test, expect } from '../../fixtures';

/**
 * Menu paths from menus.json — these are the sidebar navigation targets.
 * They route through the dynamic page system.
 */
const MENU_PATHS = [
  '/inventory/warehouses',
  '/inventory/warehouse-locations',
  '/inventory/warehouse-in',
  '/inventory/warehouse-out',
  '/inventory/inventory',
  '/inventory/lots',
  '/inventory/inventory-holds',
  '/inventory/stock-checks',
  '/inventory/stock-transfers',
  '/inventory/pick-orders',
];

/**
 * Dynamic page paths — the actual URL pattern used by the dynamic renderer.
 * Model codes use hyphens: inv_warehouse -> inv-warehouse
 */
const DYNAMIC_LIST_PATHS = [
  '/dynamic/inv-warehouse',
  '/dynamic/inv-warehouse-location',
  '/dynamic/inv-inbound',
  '/dynamic/inv-outbound',
  '/dynamic/inv-lot',
  '/dynamic/inv-inventory-hold',
  '/dynamic/inv-stock-check',
  '/dynamic/inv-transfer',
  '/dynamic/inv-pick-order',
];

const DYNAMIC_NEW_PATHS = [
  '/dynamic/inv-warehouse/new',
  '/dynamic/inv-warehouse-location/new',
  '/dynamic/inv-inbound/new',
  '/dynamic/inv-outbound/new',
  '/dynamic/inv-lot/new',
  '/dynamic/inv-inventory-hold/new',
  '/dynamic/inv-stock-check/new',
  '/dynamic/inv-transfer/new',
  '/dynamic/inv-pick-order/new',
];

function isCrashText(text: string): boolean {
  return /Application Error|Unhandled Runtime Error|TypeError:|ReferenceError:|500 Internal Server Error/i.test(text);
}

function hasExplicitState(text: string): boolean {
  return /403|Forbidden|无权限|权限不足|404|Not Found|页面不存在|加载失败|Page not found/i.test(text);
}

async function hasVisibleSelector(
  page: import('@playwright/test').Page,
  selectors: string,
): Promise<boolean> {
  return page.waitForFunction(
    (selectorList) => {
      const selectors = selectorList.split(',').map((item) => item.trim()).filter(Boolean);
      return selectors.some((selector) => {
        const el = document.querySelector(selector) as HTMLElement | null;
        if (!el) return false;
        const style = window.getComputedStyle(el);
        const rect = el.getBoundingClientRect();
        return style.visibility !== 'hidden' && style.display !== 'none' && rect.width > 0 && rect.height > 0;
      });
    },
    selectors,
    { timeout: 8_000 },
  ).then(() => true).catch(() => false);
}

async function probeInteraction(page: import('@playwright/test').Page): Promise<void> {
  const candidate = page.locator(
    [
      '[role="tab"]',
      '[data-testid="filter-search"]',
      '[data-testid="filter-btn-search"]',
      'button:has-text("Reset")',
      'button:has-text("重置")',
    ].join(', ')
  ).first();

  if (await candidate.isVisible({ timeout: 1_500 }).catch(() => false)) {
    await candidate.click().catch(() => {});
    await expect(page.locator('body')).toBeVisible();
  }
}

test.describe('Inventory Route Full Coverage', () => {
  test.setTimeout(120_000);

  test('INV-ROUTE-001: all menu paths render without crash', async ({ page }) => {
    let withContent = 0;

    for (const path of MENU_PATHS) {
      await page.goto(path, { waitUntil: 'domcontentloaded' });
      await expect(page.locator('body')).toBeVisible();

      const text = (await page.locator('body').textContent()) || '';
      expect(isCrashText(text), `menu route ${path} should not crash`).toBe(false);

      const hasContent = await page.locator(
        'main, table, [role="table"], form, [role="tablist"], [data-testid="dynamic-list"], [data-testid="dynamic-form"]'
      ).first().isVisible({ timeout: 8_000 }).catch(() => false);

      const hasState = hasExplicitState(text);
      expect(hasContent || hasState, `menu route ${path} should render content or explicit state`).toBe(true);

      if (hasContent) {
        withContent += 1;
        await probeInteraction(page);
      }
    }

    expect(withContent).toBeGreaterThan(0);
  });

  test('INV-ROUTE-002: all dynamic list paths render without crash', async ({ page }) => {
    let rendered = 0;

    for (const path of DYNAMIC_LIST_PATHS) {
      await page.goto(path, { waitUntil: 'domcontentloaded' });
      await expect(page.locator('body')).toBeVisible();

      const text = (await page.locator('body').textContent()) || '';
      expect(isCrashText(text), `dynamic list ${path} should not crash`).toBe(false);

      const hasContent = await hasVisibleSelector(
        page,
        '[data-testid="dynamic-list"], table, [role="table"], main',
      );

      if (hasContent) {
        rendered += 1;
        await probeInteraction(page);
      }
    }

    expect(rendered).toBeGreaterThan(0);
  });

  test('INV-ROUTE-003: all dynamic new/create paths render without crash', async ({ page }) => {
    let rendered = 0;

    for (const path of DYNAMIC_NEW_PATHS) {
      await page.goto(path, { waitUntil: 'domcontentloaded' });
      await expect(page.locator('body')).toBeVisible();

      const text = (await page.locator('body').textContent()) || '';
      expect(isCrashText(text), `dynamic new ${path} should not crash`).toBe(false);

      const hasForm = await hasVisibleSelector(
        page,
        '[data-testid="dynamic-form"], form, .ant-form',
      );

      if (hasForm) {
        rendered += 1;
      }
    }

    expect(rendered).toBeGreaterThan(0);
  });
});
