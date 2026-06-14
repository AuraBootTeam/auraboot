/**
 * Jiejia Integration — Price Snapshot UI Smoke
 *
 * Verifies the real DSL list page renders price snapshot data synced from
 * Kingdee after currency normalization. The test imports the local plugin,
 * runs the purchase price sync command, then opens the menu-backed dynamic
 * list page and asserts both Kingdee price paths are visible.
 */

import path from 'node:path';
import { test, expect, type Page, type APIRequestContext } from '../../fixtures';
import {
  executeCommandViaApi,
  navigateToDynamicPage,
  waitForDynamicPageLoad,
  waitForTableHydration,
} from '../helpers';

const PLUGIN_DIR = process.env.JIEJIA_PLUGIN_DIR
  || path.resolve(process.cwd(), '../../plugins/jiejia-integration');
const PAGE_KEY = 'jiejia_price_snapshot';
const MODEL_CODE = 'jiejia_price_snapshot';

async function importJiejiaPlugin(request: APIRequestContext): Promise<void> {
  const response = await request.post('/api/plugins/import/import-directory-sync', {
    data: {
      path: PLUGIN_DIR,
      conflictStrategy: 'OVERWRITE',
      autoPublishModels: true,
      autoPublishFields: true,
      autoPublishCommands: true,
      autoPublishPages: true,
      createResourcePermissions: true,
    },
    headers: { 'Content-Type': 'application/json' },
    timeout: 120_000,
  });

  const body = await response.json().catch(() => ({}));
  const data = body?.data ?? body;
  const success = response.ok() && (data?.success === true || body?.success === true);
  expect(
    success,
    `jiejia-integration import should succeed: HTTP ${response.status()} ${JSON.stringify(body).slice(0, 800)}`,
  ).toBe(true);
}

async function priceRecords(page: Page, materialCode: string): Promise<unknown[]> {
  const filters = encodeURIComponent(
    JSON.stringify([{ fieldName: 'jiejia_price_material_code', operator: 'EQ', value: materialCode }]),
  );
  const response = await page.request.get(
    `/api/dynamic/${MODEL_CODE}/list?pageNum=1&pageSize=10&filters=${filters}`,
  );
  expect(response.ok()).toBe(true);
  const body = await response.json().catch(() => ({}));
  return body?.data?.records ?? body?.data?.list ?? [];
}

test.describe('Jiejia Integration — Price Snapshot UI', () => {
  test.describe.configure({ mode: 'serial', timeout: 60_000 });

  const materialCode = 'MAT-0603-10K';
  const materialName = 'Resistor 10K 0603';
  const poHistorySourceBillNo = 'PO202605001';
  const priceListSourceBillNo = 'CGJM000001';

  test.beforeAll(async ({ browser }) => {
    const context = await browser.newContext({
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
    });
    const page = await context.newPage();
    try {
      await importJiejiaPlugin(page.request);

      const result = await executeCommandViaApi(
        page,
        'jiejia_int:sync_latest_purchase_prices',
        {},
        undefined,
        'custom',
        { timeoutMs: 60_000 },
      );
      expect(result.code).toBe('0');

      const records = await priceRecords(page, materialCode);
      expect(records.length).toBeGreaterThan(0);
      expect(records.some((record) => (record as Record<string, unknown>).jiejia_price_source_type === 'purchase_order_history')).toBe(true);
      expect(records.some((record) => (record as Record<string, unknown>).jiejia_price_source_type === 'price_list')).toBe(true);
    } finally {
      await context.close();
    }
  });

  test('JJ-PRICE-UI-01 @smoke: price snapshot list shows both Kingdee price paths after sync', async ({ page }) => {
    const listResponse = page.waitForResponse(
      (response) =>
        response.url().includes(`/api/dynamic/${MODEL_CODE}/list`) && response.status() === 200,
      { timeout: 15_000 },
    );
    await navigateToDynamicPage(page, PAGE_KEY);
    await listResponse;
    await waitForDynamicPageLoad(page);
    await waitForTableHydration(page);

    const poHistoryRow = page
      .locator('tbody tr')
      .filter({ hasText: poHistorySourceBillNo })
      .filter({ hasText: materialCode })
      .first();
    await expect(poHistoryRow).toContainText(materialName);
    await expect(poHistoryRow).toContainText('CNY');

    const priceListRow = page
      .locator('tbody tr')
      .filter({ hasText: priceListSourceBillNo })
      .filter({ hasText: materialCode })
      .first();
    await expect(priceListRow).toContainText(materialName);
    await expect(priceListRow).toContainText('CNY');
  });
});
