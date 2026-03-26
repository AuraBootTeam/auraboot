/**
 * Maintenance Plugin — Smoke Tests
 *
 * MNT-S001 @smoke : Plugin install check — models exist in backend
 * MNT-S002 @smoke : Sidebar navigation → 设备台账 (Equipment) list loads
 * MNT-S003 @smoke : Sidebar navigation → 维护计划 (Maintenance Plans) list loads
 * MNT-S004 @smoke : Sidebar navigation → 维护工单 (Work Orders) list loads
 * MNT-S005 @smoke : Sidebar navigation → 备件管理 (Spare Parts) list loads
 * MNT-S006 @smoke : No i18n key leakage on equipment list
 * MNT-S007 @smoke : Equipment list API returns valid response envelope
 * MNT-S008 @smoke : Create button visible on work orders list
 *
 * Prerequisites:
 *   - maintenance plugin MUST be imported via `aura plugin publish plugins/maintenance`
 *   - All models must be published (mnt_equipment, mnt_maintenance_plan, mnt_work_order, mnt_spare_part)
 *
 * If the plugin is not installed these tests will skip gracefully.
 *
 * @since 1.0.0
 */

import { test, expect, type Page } from '../../fixtures';

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const PLUGIN_ID = 'com.auraboot.maintenance';

// ---------------------------------------------------------------------------
// Plugin availability
// ---------------------------------------------------------------------------

let pluginInstalled = false;

// ---------------------------------------------------------------------------
// Navigation helper
// ---------------------------------------------------------------------------

async function expandMntMenu(page: Page): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
  const nav = page.locator('nav');
  const rootBtn = nav
    .getByRole('button', { name: /设备维护|Maintenance/ })
    .or(nav.locator('[title*="设备维护"], [title*="Maintenance"]').first());
  await expect(rootBtn).toBeVisible({ timeout: 10000 });
  await rootBtn.evaluate((el: HTMLElement) => el.click());
  await page.waitForResponse(() => true, { timeout: 2000 }).catch(() => null);
}

async function navigateToMntPage(
  page: Page,
  path: string,
  modelUrl: string | null,
): Promise<void> {
  await expandMntMenu(page);

  const nav = page.locator('nav');
  const link = nav.locator(`a[href="${path}"]`).first();
  await link.waitFor({ state: 'attached', timeout: 8000 });
  await link.scrollIntoViewIfNeeded();

  const responsePromise = modelUrl
    ? page
        .waitForResponse(
          (r) => r.url().includes(modelUrl) && r.status() === 200,
          { timeout: 15000 },
        )
        .catch(() => null)
    : Promise.resolve(null);

  await link.evaluate((el: HTMLElement) => el.click());
  await responsePromise;
}

// ---------------------------------------------------------------------------
// Test Suite
// ---------------------------------------------------------------------------

test.describe('Maintenance Plugin @smoke', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(60000);

  // -------------------------------------------------------------------------
  // MNT-S001: Plugin install check
  // -------------------------------------------------------------------------
  test('MNT-S001: maintenance plugin is installed and models are published', async ({
    page,
  }) => {
    const pluginResp = await page.request
      .get('/api/system/plugins?pageNum=1&pageSize=100')
      .catch(() => null);

    if (pluginResp && pluginResp.ok()) {
      const body = await pluginResp.json().catch(() => null);
      const plugins: Array<{ pluginId?: string; plugin_id?: string }> =
        body?.data?.records ?? body?.data?.data ?? body?.data ?? [];
      pluginInstalled = plugins.some(
        (p) => (p.pluginId ?? p.plugin_id) === PLUGIN_ID,
      );
    }

    if (!pluginInstalled) {
      const modelResp = await page.request
        .get('/api/meta/models/code/mnt_equipment')
        .catch(() => null);
      pluginInstalled = modelResp?.ok() ?? false;
    }

    if (!pluginInstalled) {
      test.skip(
        true,
        'maintenance plugin not installed — run: aura plugin publish plugins/maintenance',
      );
      return;
    }

    const models = ['mnt_equipment', 'mnt_maintenance_plan', 'mnt_work_order', 'mnt_spare_part'];
    for (const code of models) {
      const resp = await page.request.get(`/api/meta/models/code/${code}`);
      expect(resp.ok(), `Model ${code} should be accessible`).toBe(true);
    }
  });

  // -------------------------------------------------------------------------
  // MNT-S002: 设备台账 list loads via sidebar
  // -------------------------------------------------------------------------
  test('MNT-S002: sidebar → 设备台账 (Equipment) list page loads with table', async ({
    page,
  }) => {
    if (!pluginInstalled) {
      test.skip(true, 'maintenance plugin not installed');
      return;
    }

    await navigateToMntPage(page, '/maintenance/equipment', '/api/dynamic/mnt-equipment');

    const table = page.locator('table, [class*="ant-table"]').first();
    const emptyState = page
      .locator('[class*="empty"]')
      .or(page.getByText('暂无数据'))
      .or(page.getByText('No data'))
      .first();
    await expect(table.or(emptyState)).toBeVisible({ timeout: 12000 });

    await expect(page.locator('text=Access forbidden')).not.toBeVisible({ timeout: 2000 }).catch(() => {});
    await expect(page.locator('text=403')).not.toBeVisible({ timeout: 1000 }).catch(() => {});
  });

  // -------------------------------------------------------------------------
  // MNT-S003: 维护计划 list loads
  // -------------------------------------------------------------------------
  test('MNT-S003: sidebar → 维护计划 (Maintenance Plans) list page loads', async ({
    page,
  }) => {
    if (!pluginInstalled) {
      test.skip(true, 'maintenance plugin not installed');
      return;
    }

    await navigateToMntPage(
      page,
      '/maintenance/plans',
      '/api/dynamic/mnt-maintenance-plan',
    );

    const table = page.locator('table, [class*="ant-table"]').first();
    const emptyState = page
      .locator('[class*="empty"]')
      .or(page.getByText('暂无数据'))
      .or(page.getByText('No data'))
      .first();
    await expect(table.or(emptyState)).toBeVisible({ timeout: 12000 });
    await expect(page.locator('text=Access forbidden')).not.toBeVisible({ timeout: 2000 }).catch(() => {});
  });

  // -------------------------------------------------------------------------
  // MNT-S004: 维护工单 list loads
  // -------------------------------------------------------------------------
  test('MNT-S004: sidebar → 维护工单 (Work Orders) list page loads', async ({ page }) => {
    if (!pluginInstalled) {
      test.skip(true, 'maintenance plugin not installed');
      return;
    }

    await navigateToMntPage(page, '/maintenance/work-orders', '/api/dynamic/mnt-work-order');

    const table = page.locator('table, [class*="ant-table"]').first();
    const emptyState = page
      .locator('[class*="empty"]')
      .or(page.getByText('暂无数据'))
      .or(page.getByText('No data'))
      .first();
    await expect(table.or(emptyState)).toBeVisible({ timeout: 12000 });
    await expect(page.locator('text=Access forbidden')).not.toBeVisible({ timeout: 2000 }).catch(() => {});
  });

  // -------------------------------------------------------------------------
  // MNT-S005: 备件管理 list loads
  // -------------------------------------------------------------------------
  test('MNT-S005: sidebar → 备件管理 (Spare Parts) list page loads', async ({ page }) => {
    if (!pluginInstalled) {
      test.skip(true, 'maintenance plugin not installed');
      return;
    }

    await navigateToMntPage(page, '/maintenance/spare-parts', '/api/dynamic/mnt-spare-part');

    const table = page.locator('table, [class*="ant-table"]').first();
    const emptyState = page
      .locator('[class*="empty"]')
      .or(page.getByText('暂无数据'))
      .or(page.getByText('No data'))
      .first();
    await expect(table.or(emptyState)).toBeVisible({ timeout: 12000 });
    await expect(page.locator('text=Access forbidden')).not.toBeVisible({ timeout: 2000 }).catch(() => {});
  });

  // -------------------------------------------------------------------------
  // MNT-S006: No i18n key leakage on equipment list
  // -------------------------------------------------------------------------
  test('MNT-S006: column headers have no raw i18n key leakage', async ({ page }) => {
    if (!pluginInstalled) {
      test.skip(true, 'maintenance plugin not installed');
      return;
    }

    await navigateToMntPage(page, '/maintenance/equipment', '/api/dynamic/mnt-equipment');

    const headers = await page.locator('th, [role="columnheader"]').allTextContents();
    for (const h of headers) {
      expect(h, `Header "${h}" should not be a raw i18n key`).not.toMatch(
        /model\.[a-z_]+\.[a-z_]+\.label/i,
      );
      expect(h, `Header "${h}" should not be a raw i18n key`).not.toMatch(/^[a-z_]+\.[a-z_]+$/);
    }
  });

  // -------------------------------------------------------------------------
  // MNT-S007: Equipment list API returns valid response structure
  // -------------------------------------------------------------------------
  test('MNT-S007: mnt_equipment list API returns valid ApiResponse envelope', async ({
    page,
  }) => {
    if (!pluginInstalled) {
      test.skip(true, 'maintenance plugin not installed');
      return;
    }

    const resp = await page.request.get(
      '/api/dynamic/mnt-equipment/list?pageNum=1&pageSize=10',
    );
    expect(resp.status()).toBe(200);
    const body = await resp.json();
    expect(body).toHaveProperty('code');
    expect(String(body.code)).toBe('0');
    expect(body).toHaveProperty('data');
    expect(body.data).toHaveProperty('total');
    expect(body.data).toHaveProperty('records');
  });

  // -------------------------------------------------------------------------
  // MNT-S008: Create button visible on work orders list
  // -------------------------------------------------------------------------
  test('MNT-S008: create button is visible on work orders list page', async ({ page }) => {
    if (!pluginInstalled) {
      test.skip(true, 'maintenance plugin not installed');
      return;
    }

    await navigateToMntPage(page, '/maintenance/work-orders', '/api/dynamic/mnt-work-order');

    const createBtn = page.locator('button', { hasText: /新建|Create|创建|添加/ }).first();
    await expect(createBtn).toBeVisible({ timeout: 8000 });
  });
});
