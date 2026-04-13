/**
 * Inventory Plugin — Model & Page Full Coverage
 *
 * Verifies all inventory models exist and are published,
 * and all page schemas are resolvable with working dynamic routes.
 */

import { test, expect } from '../../fixtures';

const INVENTORY_MODELS = [
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

const INVENTORY_PAGE_KEYS = [
  'inv_warehouse_list',
  'inv_warehouse_form',
  'inv_inbound_list',
  'inv_inbound_form',
  'inv_inbound_detail',
  'inv_outbound_list',
  'inv_outbound_form',
  'inv_outbound_detail',
  'inv_warehouse_location_list',
  'inv_warehouse_location_form',
  'inv_inventory_hold_list',
  'inv_inventory_hold_form',
  'inv_lot_list',
  'inv_lot_form',
  'inv_stock_check_list',
  'inv_stock_check_form',
  'inv_transfer_list',
  'inv_transfer_form',
  'inv_pick_order_list',
  'inv_pick_order_form',
  'inv_inventory_list',
  'inv_inventory_dashboard',
  'inv_wms_dashboard',
];

/** Model codes that have top-level list pages (not child/line models). */
const NAVIGABLE_MODELS = [
  'inv_warehouse',
  'inv_warehouse_location',
  'inv_inbound',
  'inv_outbound',
  'inv_lot',
  'inv_inventory_hold',
  'inv_stock_check',
  'inv_transfer',
  'inv_pick_order',
];

function toDynamicRoute(modelCode: string): string {
  return modelCode;
}

function isCrashText(text: string): boolean {
  return /Application Error|Unhandled Runtime Error|TypeError:|ReferenceError:|500 Internal Server Error/i.test(
    text,
  );
}

async function hasVisibleSelector(
  page: import('@playwright/test').Page,
  selectors: string,
): Promise<boolean> {
  return page
    .waitForFunction(
      (selectorList) => {
        const selectors = selectorList
          .split(',')
          .map((item) => item.trim())
          .filter(Boolean);
        return selectors.some((selector) => {
          const el = document.querySelector(selector) as HTMLElement | null;
          if (!el) return false;
          const style = window.getComputedStyle(el);
          const rect = el.getBoundingClientRect();
          return (
            style.visibility !== 'hidden' &&
            style.display !== 'none' &&
            rect.width > 0 &&
            rect.height > 0
          );
        });
      },
      selectors,
      { timeout: 8_000 },
    )
    .then(() => true)
    .catch(() => false);
}

test.describe('Inventory Model & Page Full Coverage', () => {
  test.setTimeout(120_000);

  test('INV-MODEL-001: all inventory models exist and are published', async ({ page }) => {
    for (const modelCode of INVENTORY_MODELS) {
      const resp = await page.request.get(`/api/meta/models/code/${modelCode}`);
      expect(resp.ok(), `model ${modelCode} should exist`).toBe(true);

      const body = await resp.json();
      expect(body?.data?.code, `model response should contain code ${modelCode}`).toBe(modelCode);
      expect(body?.data?.status, `model ${modelCode} should be published`).toBe('published');
    }
  });

  test('INV-PAGE-001: all inventory page schemas are resolvable', async ({ page }) => {
    for (const pageKey of INVENTORY_PAGE_KEYS) {
      const resp = await page.request.get(`/api/pages/key/${encodeURIComponent(pageKey)}`);
      expect(resp.ok(), `page schema should exist for key: ${pageKey}`).toBe(true);

      const body = await resp.json();
      expect(body?.data, `page schema data should be present: ${pageKey}`).toBeTruthy();
    }
  });

  test('INV-MODEL-002: navigable models have working list pages', async ({ page }) => {
    let withContent = 0;

    for (const modelCode of NAVIGABLE_MODELS) {
      const slug = toDynamicRoute(modelCode);
      const listPath = `/p/${slug}`;

      await page.goto(listPath, { waitUntil: 'domcontentloaded' });
      await expect(page.locator('body')).toBeVisible();

      const text = (await page.locator('body').textContent()) || '';
      expect(isCrashText(text), `list page ${listPath} should not crash`).toBe(false);

      const hasContent = await hasVisibleSelector(
        page,
        '[data-testid="dynamic-list"], table, [role="table"], main',
      );

      if (hasContent) {
        withContent += 1;
      }
    }

    expect(withContent).toBeGreaterThan(0);
  });

  test('INV-MODEL-003: navigable models have working new/create pages', async ({ page }) => {
    let withContent = 0;

    for (const modelCode of NAVIGABLE_MODELS) {
      const slug = toDynamicRoute(modelCode);
      const newPath = `/p/${slug}/new`;

      await page.goto(newPath, { waitUntil: 'domcontentloaded' });
      await expect(page.locator('body')).toBeVisible();

      const text = (await page.locator('body').textContent()) || '';
      expect(isCrashText(text), `new page ${newPath} should not crash`).toBe(false);

      const hasForm = await hasVisibleSelector(
        page,
        '[data-testid="dynamic-form"], form, .ant-form',
      );

      if (hasForm) {
        withContent += 1;

        // Probe: check that at least one input field is rendered
        const inputVisible = await page
          .waitForFunction(
            () => {
              const container =
                document.querySelector('[data-testid="dynamic-form"]') ||
                document.querySelector('form');
              if (!container) return false;
              const field = container.querySelector(
                'input:not([type="hidden"]), textarea',
              ) as HTMLElement | null;
              if (!field) return false;
              const style = window.getComputedStyle(field);
              const rect = field.getBoundingClientRect();
              return (
                style.visibility !== 'hidden' &&
                style.display !== 'none' &&
                rect.width > 0 &&
                rect.height > 0
              );
            },
            { timeout: 2_000 },
          )
          .then(() => true)
          .catch(() => false);
        expect(inputVisible, `new page ${newPath} should render form inputs`).toBe(true);
      }
    }

    expect(withContent).toBeGreaterThan(0);
  });
});
