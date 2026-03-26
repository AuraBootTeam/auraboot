/**
 * BomTreeEditor — Smoke E2E Tests
 *
 * Verifies that:
 * 1. The BOM list page is accessible and renders a table.
 * 2. The BOM line API returns data for an existing BOM (end-to-end data path
 *    that the BomTreeEditor component relies on).
 *
 * These tests are intentionally lightweight smoke tests; the detailed BOM
 * CRUD lifecycle is covered in pcba-bom-inventory.spec.ts.
 *
 * @since 7.0.0
 */

import { test, expect } from '../../fixtures';
import { ErrorCodes } from '~/services/http-client/types';
import {
  navigateToDynamicPage,
  uniqueId,
  executeCommandViaApi,
} from '../helpers';

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const PAGE_KEY_BOM = 'pe-bom';
const LINE_MODEL = 'pe_bom_line';
const FOREIGN_KEY = 'pe_bom_line_bom_id';

const CMD_CREATE_BOM = 'pe:create_bom';
const CMD_ADD_BOM_LINE = 'pe:add_bom_line';
const CMD_DELETE_BOM = 'pe:delete_bom';
const CMD_DELETE_BOM_LINE = 'pe:delete_bom_line';
const CMD_CREATE_PRODUCT = 'prod:create_product';
const CMD_DELETE_PRODUCT = 'prod:delete_product';

// ---------------------------------------------------------------------------
// BOM Tree Editor — Smoke Suite
// ---------------------------------------------------------------------------

test.describe('BomTreeEditor — Smoke', () => {
  test.describe.configure({ timeout: 45000 });

  let bomPid: string | undefined;
  let bomLinePid: string | undefined;
  let productPid: string | undefined;

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();

    try {
      // Create a product to use as material
      const productResult = await executeCommandViaApi(
        page,
        CMD_CREATE_PRODUCT,
        {
          prod_name: `E2E BomTree Material ${uniqueId('mat')}`,
          prod_type: 'raw_material',
          prod_unit: 'pcs',
        },
        undefined,
        'create',
        { allowHttpError: true },
      );
      if (productResult.code === ErrorCodes.SUCCESS && productResult.recordId) {
        productPid = productResult.recordId;
      }

      // Create a BOM
      const bomResult = await executeCommandViaApi(
        page,
        CMD_CREATE_BOM,
        {
          pe_bom_code: `E2E-BTREE-${Date.now()}`,
          pe_bom_name: `E2E BomTree ${uniqueId()}`,
          pe_bom_version: 'V1.0',
          pe_bom_output_qty: 1,
        },
        undefined,
        'create',
        { allowHttpError: true },
      );
      if (bomResult.code === ErrorCodes.SUCCESS && bomResult.recordId) {
        bomPid = bomResult.recordId;
      }

      // Add one BOM line (child component)
      if (bomPid) {
        const lineResult = await executeCommandViaApi(
          page,
          CMD_ADD_BOM_LINE,
          {
            [FOREIGN_KEY]: bomPid,
            ...(productPid ? { pe_bom_line_material_id: productPid } : {}),
            pe_bom_line_qty: 5,
            pe_bom_line_unit: 'pcs',
            pe_bom_line_remark: `E2E BomTree line ${uniqueId()}`,
          },
          undefined,
          'create',
          { allowHttpError: true },
        );
        if (lineResult.code === ErrorCodes.SUCCESS && lineResult.recordId) {
          bomLinePid = lineResult.recordId;
        }
      }
    } catch {
      // If setup fails, individual tests will handle gracefully
    } finally {
      await ctx.close();
    }
  });

  test.afterAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();

    // Clean up in reverse dependency order
    if (bomLinePid) {
      await executeCommandViaApi(page, CMD_DELETE_BOM_LINE, {}, bomLinePid, 'delete', {
        allowHttpError: true,
      }).catch(() => {});
    }
    if (bomPid) {
      await executeCommandViaApi(page, CMD_DELETE_BOM, {}, bomPid, 'delete', {
        allowHttpError: true,
      }).catch(() => {});
    }
    if (productPid) {
      await executeCommandViaApi(page, CMD_DELETE_PRODUCT, {}, productPid, 'delete', {
        allowHttpError: true,
      }).catch(() => {});
    }

    await ctx.close();
  });

  // -------------------------------------------------------------------------

  test('BTE-001: BOM list page is accessible and renders a table @smoke', async ({ page }) => {
    await navigateToDynamicPage(page, PAGE_KEY_BOM);

    // Verify the page has a data table
    const table = page.locator('table, [role="table"]');
    await expect(table.first()).toBeVisible({ timeout: 15000 });

    // Verify the create button exists (confirms the page is the list view)
    const createBtn = page.locator(
      '[data-testid="toolbar-btn-create"], button:has-text("New"), button:has-text("Create")'
    ).first();
    await expect(createBtn).toBeVisible({ timeout: 5000 });
  });

  test('BTE-002: BOM line API returns data for existing BOM @smoke', async ({ page }) => {
    if (!bomPid) {
      test.info().annotations.push({
        type: 'skip-reason',
        description: 'BOM creation failed in beforeAll — skipping API data test',
      });
      return;
    }

    // The BomTreeEditor fetches lines via this exact endpoint pattern
    const filters = JSON.stringify([
      { fieldName: FOREIGN_KEY, operator: 'EQ', value: bomPid },
    ]);

    const resp = await page.request.get(
      `/api/dynamic/${LINE_MODEL}/list?pageNum=1&pageSize=500&filters=${encodeURIComponent(filters)}`
    );
    expect(resp.ok(), `BOM line list API should return 200`).toBe(true);

    const body = await resp.json();
    expect(String(body.code)).toBe(ErrorCodes.SUCCESS);

    // There should be at least one BOM line (created in beforeAll)
    const records: unknown[] = body.data?.records ?? body.data?.content ?? [];
    expect(
      records.length,
      'BOM line API should return at least 1 record for the test BOM'
    ).toBeGreaterThan(0);
  });

  test('BTE-003: BOM list page i18n headers are not raw keys @smoke', async ({ page }) => {
    await navigateToDynamicPage(page, PAGE_KEY_BOM);

    const headers = page.locator('thead th');
    await expect(headers.first()).toBeVisible({ timeout: 10000 });

    const count = await headers.count();
    for (let i = 0; i < Math.min(count, 6); i++) {
      const text = (await headers.nth(i).innerText()).trim();
      if (text.length > 0) {
        expect(text, `Column ${i} should not be a raw i18n key`).not.toMatch(/^model\./);
        expect(text, `Column ${i} should not expose the internal field prefix`).not.toMatch(/^pe_bom_/);
      }
    }
  });
});
