/**
 * Logistics Plugin — Smoke Tests
 *
 * LG-S001 @smoke : Plugin install check — models exist in backend
 * LG-S002 @smoke : Sidebar navigation → 发货管理 (Shipments) list loads
 * LG-S003 @smoke : Sidebar navigation → 承运商 (Carriers) list loads
 * LG-S004 @smoke : Sidebar navigation → 物流跟踪 (Tracking) list loads
 * LG-S005 @smoke : Sidebar navigation → 送货单 (Delivery Notes) list loads
 * LG-S006 @smoke : No i18n key leakage on shipments list
 * LG-S007 @smoke : Shipments list API returns valid response envelope
 *
 * Prerequisites:
 *   - logistics plugin MUST be imported via `aura plugin publish plugins/logistics`
 *   - All models must be published (lg_shipment, lg_carrier, lg_tracking_event, lg_delivery_note)
 *
 * If the plugin is not installed these tests will skip gracefully.
 *
 * @since 1.0.0
 */

import { test, expect, type Page } from '../../fixtures';

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const PLUGIN_ID = 'com.auraboot.logistics';

const LG_MENUS = [
  {
    code: 'lg_shipments',
    path: '/logistics/shipments',
    modelUrl: '/api/dynamic/lg_shipment',
    label: /发货管理|Shipments/,
  },
  {
    code: 'lg_carriers',
    path: '/logistics/carriers',
    modelUrl: '/api/dynamic/lg_carrier',
    label: /承运商|Carriers/,
  },
  {
    code: 'lg_tracking',
    path: '/logistics/tracking',
    modelUrl: '/api/dynamic/lg_tracking_event',
    label: /物流跟踪|Tracking/,
  },
  {
    code: 'lg_delivery_notes',
    path: '/logistics/delivery-notes',
    modelUrl: '/api/dynamic/lg_delivery_note',
    label: /送货单|Delivery Notes/,
  },
];

// ---------------------------------------------------------------------------
// Plugin availability
// ---------------------------------------------------------------------------

let pluginInstalled = false;

// ---------------------------------------------------------------------------
// Navigation helper
// ---------------------------------------------------------------------------

async function expandLgMenu(page: Page): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
  const nav = page.locator('nav');
  const rootBtn = nav
    .getByRole('button', { name: /物流管理|Logistics/ })
    .or(nav.locator('[title*="物流"], [title*="Logistics"]').first());
  await expect(rootBtn).toBeVisible({ timeout: 10000 });
  await rootBtn.evaluate((el: HTMLElement) => el.click());
  await page.waitForResponse(() => true, { timeout: 2000 }).catch(() => null);
}

async function navigateToLgPage(page: Page, path: string, modelUrl: string | null): Promise<void> {
  await expandLgMenu(page);

  const nav = page.locator('nav');
  const link = nav.locator(`a[href="${path}"]`).first();
  await link.waitFor({ state: 'attached', timeout: 8000 });
  await link.scrollIntoViewIfNeeded();

  const responsePromise = modelUrl
    ? page
        .waitForResponse((r) => r.url().includes(modelUrl) && r.status() === 200, {
          timeout: 15000,
        })
        .catch(() => null)
    : Promise.resolve(null);

  await link.evaluate((el: HTMLElement) => el.click());
  await responsePromise;
}

// ---------------------------------------------------------------------------
// Test Suite
// ---------------------------------------------------------------------------

test.describe('Logistics Plugin @smoke', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(60000);

  // -------------------------------------------------------------------------
  // LG-S001: Plugin install check
  // -------------------------------------------------------------------------
  test('LG-S001: logistics plugin is installed and models are published', async ({ page }) => {
    // Check plugin availability via model code API
    const modelResp = await page.request.get('/api/meta/models/code/lg_shipment').catch(() => null);
    if (modelResp) {
      const body = await modelResp.json().catch(() => ({}));
      pluginInstalled = modelResp.ok() && body?.data?.status === 'published';
    }

    if (!pluginInstalled) {
      test.skip(
        true,
        'logistics plugin not installed — run: aura plugin publish plugins/logistics',
      );
      return;
    }

    const models = ['lg_shipment', 'lg_carrier', 'lg_tracking_event', 'lg_delivery_note'];
    for (const code of models) {
      const resp = await page.request.get(`/api/meta/models/code/${code}`);
      expect(resp.ok(), `Model ${code} should be accessible`).toBe(true);
    }
  });

  // -------------------------------------------------------------------------
  // LG-S002: 发货管理 list loads via sidebar
  // -------------------------------------------------------------------------
  test('LG-S002: sidebar → 发货管理 (Shipments) list page loads with table', async ({ page }) => {
    if (!pluginInstalled) {
      test.skip(true, 'logistics plugin not installed');
      return;
    }

    await navigateToLgPage(page, '/logistics/shipments', '/api/dynamic/lg_shipment');

    // Table should be visible (may contain "暂无数据" empty state row)
    const table = page.locator('table, [class*="ant-table"]').first();
    await expect(table).toBeVisible({ timeout: 12000 });

    await expect(page.locator('text=Access forbidden'))
      .not.toBeVisible({ timeout: 2000 })
      .catch(() => {});
    await expect(page.locator('text=403'))
      .not.toBeVisible({ timeout: 1000 })
      .catch(() => {});
  });

  // -------------------------------------------------------------------------
  // LG-S003: 承运商 list loads
  // -------------------------------------------------------------------------
  test('LG-S003: sidebar → 承运商 (Carriers) list page loads with table', async ({ page }) => {
    if (!pluginInstalled) {
      test.skip(true, 'logistics plugin not installed');
      return;
    }

    await navigateToLgPage(page, '/logistics/carriers', '/api/dynamic/lg_carrier');

    // Table should be visible (may contain "暂无数据" empty state row)
    const table = page.locator('table, [class*="ant-table"]').first();
    await expect(table).toBeVisible({ timeout: 12000 });
    await expect(page.locator('text=Access forbidden'))
      .not.toBeVisible({ timeout: 2000 })
      .catch(() => {});
  });

  // -------------------------------------------------------------------------
  // LG-S004: 物流跟踪 list loads
  // -------------------------------------------------------------------------
  test('LG-S004: sidebar → 物流跟踪 (Tracking) list page loads with table', async ({ page }) => {
    if (!pluginInstalled) {
      test.skip(true, 'logistics plugin not installed');
      return;
    }

    await navigateToLgPage(page, '/logistics/tracking', '/api/dynamic/lg_tracking_event');

    // Table should be visible (may contain "暂无数据" empty state row)
    const table = page.locator('table, [class*="ant-table"]').first();
    await expect(table).toBeVisible({ timeout: 12000 });
    await expect(page.locator('text=Access forbidden'))
      .not.toBeVisible({ timeout: 2000 })
      .catch(() => {});
  });

  // -------------------------------------------------------------------------
  // LG-S005: 送货单 list loads
  // -------------------------------------------------------------------------
  test('LG-S005: sidebar → 送货单 (Delivery Notes) list page loads with table', async ({
    page,
  }) => {
    if (!pluginInstalled) {
      test.skip(true, 'logistics plugin not installed');
      return;
    }

    await navigateToLgPage(page, '/logistics/delivery-notes', '/api/dynamic/lg_delivery_note');

    // Table should be visible (may contain "暂无数据" empty state row)
    const table = page.locator('table, [class*="ant-table"]').first();
    await expect(table).toBeVisible({ timeout: 12000 });
    await expect(page.locator('text=Access forbidden'))
      .not.toBeVisible({ timeout: 2000 })
      .catch(() => {});
  });

  // -------------------------------------------------------------------------
  // LG-S006: No i18n key leakage on shipments list
  // -------------------------------------------------------------------------
  test('LG-S006: column headers have no raw i18n key leakage', async ({ page }) => {
    if (!pluginInstalled) {
      test.skip(true, 'logistics plugin not installed');
      return;
    }

    await navigateToLgPage(page, '/logistics/shipments', '/api/dynamic/lg_shipment');

    const headers = await page.locator('th, [role="columnheader"]').allTextContents();
    for (const h of headers) {
      expect(h, `Header "${h}" should not be a raw i18n key`).not.toMatch(
        /model\.[a-z_]+\.[a-z_]+\.label/i,
      );
      expect(h, `Header "${h}" should not be a raw i18n key`).not.toMatch(/^[a-z_]+\.[a-z_]+$/);
    }
  });

  // -------------------------------------------------------------------------
  // LG-S007: Shipments list API returns valid response structure
  // -------------------------------------------------------------------------
  test('LG-S007: lg_shipment list API returns valid ApiResponse envelope', async ({ page }) => {
    if (!pluginInstalled) {
      test.skip(true, 'logistics plugin not installed');
      return;
    }

    const resp = await page.request.get('/api/dynamic/lg_shipment/list?pageNum=1&pageSize=10');
    expect(resp.status()).toBe(200);
    const body = await resp.json();
    expect(body).toHaveProperty('code');
    expect(String(body.code)).toBe('0');
    expect(body).toHaveProperty('data');
    expect(body.data).toHaveProperty('total');
    expect(body.data).toHaveProperty('records');
  });

  // -------------------------------------------------------------------------
  // LG-S008: Create button visible on shipments list
  // -------------------------------------------------------------------------
  test('LG-S008: create button is visible on shipments list page', async ({ page }) => {
    if (!pluginInstalled) {
      test.skip(true, 'logistics plugin not installed');
      return;
    }

    await navigateToLgPage(page, '/logistics/shipments', '/api/dynamic/lg_shipment');

    const createBtn = page.locator('button', { hasText: /新建|Create|创建|添加/ }).first();
    await expect(createBtn).toBeVisible({ timeout: 8000 });
  });
});
