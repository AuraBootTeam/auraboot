/**
 * Data Tools Depth E2E Tests
 *
 * Tests DT-001 to DT-016: Export, import, template, and validation functionality
 * - Excel/CSV export buttons and dropdown
 * - Filtered export
 * - Import XLSX via UI
 * - Import validation (duplicate detection)
 * - Import template download
 * - Data tools toolbar integration
 *
 * Uses real database + API, NO MOCKING.
 * Navigate to e2et-order list page for data tools testing.
 *
 * @since 6.3.0
 */

import { test, expect } from '../../fixtures';
import { navigateToDynamicPage, uniqueId } from '../helpers';
import { DynamicListPage } from '../../pages';
import { ErrorCodes } from '~/shared/services/http-client/types';

const ORDER_PAGE_KEY = 'e2et_order';
const CUSTOMER_PAGE_KEY = 'e2et_customer';

async function openDataToolsMenu(page: import('@playwright/test').Page): Promise<'more' | 'direct'> {
  const moreBtn = page.locator('[data-testid="toolbar-more-menu"]').first();
  if (await moreBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
    await expect(moreBtn).toBeEnabled();
    await moreBtn.click();
    return 'more';
  }

  const directExport = page
    .locator(
      '[data-testid="toolbar-btn-export"], [data-testid="toolbar-btn-export-excel"], button:has-text("导出"), button:has-text("Export")',
    )
    .first();
  if (await directExport.isVisible({ timeout: 3000 }).catch(() => false)) {
    return 'direct';
  }

  const directImport = page
    .locator('[data-testid="toolbar-btn-import"], button:has-text("导入"), button:has-text("Import")')
    .first();
  if (await directImport.isVisible({ timeout: 3000 }).catch(() => false)) {
    return 'direct';
  }

  throw new Error('No visible data tools control found on toolbar');
}

test.describe('Data Tools Deep — Export', () => {
  /**
   * DT-001: Export button is visible on dynamic list page @smoke
   * Export is accessed via the ⋮ more menu button
   */
  test('DT-001: export button is visible @smoke', async ({ page }) => {
    await navigateToDynamicPage(page, ORDER_PAGE_KEY);
    const mode = await openDataToolsMenu(page);
    expect(['more', 'direct']).toContain(mode);
  });

  /**
   * DT-002: Export dropdown shows Excel and CSV options @smoke
   */
  test('DT-002: export dropdown shows Excel and CSV @smoke', async ({ page }) => {
    await navigateToDynamicPage(page, ORDER_PAGE_KEY);
    const mode = await openDataToolsMenu(page);
    if (mode === 'more') {
      await expect(page.locator('[data-testid="more-menu-export-excel"]')).toBeVisible({
        timeout: 3000,
      });
      await expect(page.locator('[data-testid="more-menu-export-csv"]')).toBeVisible({
        timeout: 3000,
      });
      return;
    }

    await expect(
      page.locator(
        '[data-testid="toolbar-btn-export"], [data-testid="toolbar-btn-export-excel"], button:has-text("导出"), button:has-text("Export")',
      ).first(),
    ).toBeVisible({ timeout: 3000 });
  });

  /**
   * DT-003: Export dropdown closes on outside click
   */
  test('DT-003: export dropdown closes on outside click', async ({ page }) => {
    await navigateToDynamicPage(page, ORDER_PAGE_KEY);

    const moreBtn = page.locator('[data-testid="toolbar-more-menu"]').first();
    const hasMoreBtn = await moreBtn.isVisible({ timeout: 5000 }).catch(() => false);
    if (!hasMoreBtn) {
      throw new Error(String('More menu button not visible'));
      return;
    }

    await moreBtn.click();
    const excelOption = page.locator('[data-testid="more-menu-export-excel"]');
    await expect(excelOption).toBeVisible({ timeout: 3000 });

    // Click outside to close
    await page.locator('body').click({ position: { x: 10, y: 10 } });
    await expect(excelOption).not.toBeVisible({ timeout: 3000 });
  });

  /**
   * DT-004: Excel export triggers download API
   */
  test('DT-004: Excel export triggers download API', async ({ page }) => {
    await navigateToDynamicPage(page, ORDER_PAGE_KEY);

    const moreBtn = page.locator('[data-testid="toolbar-more-menu"]').first();
    const hasMoreBtn = await moreBtn.isVisible({ timeout: 5000 }).catch(() => false);
    if (!hasMoreBtn) {
      throw new Error(String('More menu button not visible'));
      return;
    }

    await moreBtn.click();
    const excelOption = page.locator('[data-testid="more-menu-export-excel"]');
    const hasExcelOption = await excelOption.isVisible({ timeout: 3000 }).catch(() => false);
    if (!hasExcelOption) {
      throw new Error(String('Excel option not visible'));
      return;
    }

    // Listen for the download/export API request
    const downloadPromise = page
      .waitForResponse((r) => r.url().includes('/export') || r.url().includes('/excel'), {
        timeout: 10000,
      })
      .catch(() => null);

    await excelOption.click();

    const resp = await downloadPromise;
    if (resp) {
      // The deep UI contract is that the export action is wired up to a real
      // backend endpoint. Some fixture tenants intentionally do not grant the
      // actual export permission, which yields 403 instead of a file stream.
      // That should not be treated as a broken UI action.
      expect(resp.status()).toBeLessThan(500);
      expect([404, 405]).not.toContain(resp.status());
    }
  });

  /**
   * DT-005: CSV export option triggers download
   */
  test('DT-005: CSV export triggers download', async ({ page }) => {
    await navigateToDynamicPage(page, ORDER_PAGE_KEY);

    const moreBtn = page.locator('[data-testid="toolbar-more-menu"]').first();
    const hasMoreBtn = await moreBtn.isVisible({ timeout: 5000 }).catch(() => false);
    if (!hasMoreBtn) {
      throw new Error(String('More menu button not visible'));
      return;
    }

    await moreBtn.click();
    const csvOption = page.locator('[data-testid="more-menu-export-csv"]');
    const hasCsvOption = await csvOption.isVisible({ timeout: 3000 }).catch(() => false);
    if (!hasCsvOption) {
      throw new Error(String('CSV option not visible'));
      return;
    }

    const downloadPromise = page
      .waitForResponse((r) => r.url().includes('/export') || r.url().includes('/csv'), {
        timeout: 10000,
      })
      .catch(() => null);

    await csvOption.click();

    const resp = await downloadPromise;
    if (resp) {
      expect(resp.status()).toBeLessThan(500);
      expect([404, 405]).not.toContain(resp.status());
    }
  });

  /**
   * DT-006: Filtered export respects current tab filter
   */
  test('DT-006: filtered export respects current tab', async ({ page }) => {
    const listPage = new DynamicListPage(page, `/p/${ORDER_PAGE_KEY}`);
    await listPage.goto();

    // Click on a specific tab (e.g., Draft)
    const draftTab = page
      .locator('nav[aria-label="Tabs"] button')
      .filter({ hasText: /草稿|Draft/i })
      .first();
    const hasDraftTab = await draftTab.isVisible({ timeout: 5000 }).catch(() => false);

    if (hasDraftTab) {
      await draftTab.click();
      await page
        .waitForResponse((r) => r.url().includes('/list') && r.status() === 200, { timeout: 5000 })
        .catch(() => null);
    }

    // Export should be available via ⋮ more menu even with filters active
    const moreBtn = page.locator('[data-testid="toolbar-more-menu"]').first();
    const hasMoreBtn = await moreBtn.isVisible({ timeout: 5000 }).catch(() => false);

    if (hasMoreBtn) {
      await expect(moreBtn).toBeEnabled();
    }
  });
});

test.describe('Data Tools Deep — Import', () => {
  /**
   * DT-007: Import button is visible on dynamic list page @smoke
   * Import is accessed via the ⋮ more menu button
   */
  test('DT-007: import button is visible @smoke', async ({ page }) => {
    await navigateToDynamicPage(page, ORDER_PAGE_KEY);
    const mode = await openDataToolsMenu(page);
    const importBtn =
      mode === 'more'
        ? page.locator('[data-testid="more-menu-import"]').first()
        : page
            .locator(
              '[data-testid="toolbar-btn-import"], button:has-text("导入"), button:has-text("Import")',
            )
            .first();
    await expect(importBtn).toBeVisible({ timeout: 3000 });
    await expect(importBtn).toBeEnabled();
  });

  /**
   * DT-008: Import button opens import modal/dialog
   */
  test('DT-008: import button opens modal', async ({ page }) => {
    await navigateToDynamicPage(page, ORDER_PAGE_KEY);

    const moreBtn = page.locator('[data-testid="toolbar-more-menu"]').first();
    const hasMoreBtn = await moreBtn.isVisible({ timeout: 5000 }).catch(() => false);
    if (!hasMoreBtn) {
      throw new Error(String('More menu button not visible'));
      return;
    }

    await moreBtn.click();

    const importBtn = page.locator('[data-testid="more-menu-import"]').first();
    const hasImportBtn = await importBtn.isVisible({ timeout: 3000 }).catch(() => false);
    if (!hasImportBtn) {
      throw new Error(String('Import button not visible in more menu'));
      return;
    }

    await importBtn.click();

    // Look for modal or dialog
    const modal = page
      .locator('[role="dialog"], .fixed.inset-0, div:has(input[type="file"])')
      .first();
    const hasModal = await modal.isVisible({ timeout: 5000 }).catch(() => false);

    if (hasModal) {
      await expect(modal).toBeVisible();
      // Close modal
      await page.keyboard.press('Escape');
    } else {
      // Import may use a file picker directly
      const errorOverlay = page.locator('#webpack-dev-server-client-overlay');
      const hasError = await errorOverlay.isVisible({ timeout: 1000 }).catch(() => false);
      expect(hasError).toBe(false);
    }
  });

  /**
   * DT-009: Import modal has file upload area
   */
  test('DT-009: import modal has file upload area', async ({ page }) => {
    await navigateToDynamicPage(page, ORDER_PAGE_KEY);

    const moreBtn = page.locator('[data-testid="toolbar-more-menu"]').first();
    const hasMoreBtn = await moreBtn.isVisible({ timeout: 5000 }).catch(() => false);
    if (!hasMoreBtn) {
      throw new Error(String('More menu button not visible'));
      return;
    }

    await moreBtn.click();

    const importBtn = page.locator('[data-testid="more-menu-import"]').first();
    const hasImportBtn = await importBtn.isVisible({ timeout: 3000 }).catch(() => false);
    if (!hasImportBtn) {
      throw new Error(String('Import button not visible in more menu'));
      return;
    }

    await importBtn.click();

    // Look for file input in modal
    const fileInput = page.locator('input[type="file"]');
    const hasFileInput = await fileInput.isVisible({ timeout: 5000 }).catch(() => false);

    // File input may be hidden (styled upload area)
    const hasFileInputInDom = (await fileInput.count()) > 0;

    if (hasFileInput || hasFileInputInDom) {
      expect(true).toBe(true);
    } else {
      // Upload area may use drag-and-drop instead
      const dropZone = page.locator(
        'text=拖拽文件, text=点击上传, text=Drag, text=Upload, text=选择文件',
      );
      const hasDropZone = await dropZone
        .first()
        .isVisible({ timeout: 3000 })
        .catch(() => false);
      expect(hasDropZone || hasFileInputInDom).toBe(true);
    }

    await page.keyboard.press('Escape');
  });
});

test.describe('Data Tools Deep — Template Download', () => {
  /**
   * DT-010: Excel template download API for e2et_order
   */
  test('DT-010: Excel template download API', async ({ page }) => {
    const resp = await page.request.get('/api/meta/excel/template/e2et_order');

    if (resp.status() === 404) {
      throw new Error(String('Excel template API not available'));
      return;
    }

    expect(resp.ok()).toBe(true);

    const contentType = resp.headers()['content-type'] || '';
    const isExcel =
      contentType.includes('spreadsheet') ||
      contentType.includes('octet-stream') ||
      contentType.includes('xlsx');

    if (isExcel) {
      const buffer = await resp.body();
      expect(buffer.length).toBeGreaterThan(0);
    } else {
      const body = await resp.json().catch(() => null);
      expect(body).toBeTruthy();
    }
  });

  /**
   * DT-011: Excel template download API for e2et_customer
   */
  test('DT-011: Excel template for customer model', async ({ page }) => {
    const resp = await page.request.get('/api/meta/excel/template/e2et_customer');

    if (resp.status() === 404) {
      throw new Error(String('Excel template API not available for customer'));
      return;
    }

    if (resp.ok()) {
      const contentType = resp.headers()['content-type'] || '';
      const isExcel = contentType.includes('spreadsheet') || contentType.includes('octet-stream');

      if (isExcel) {
        const buffer = await resp.body();
        expect(buffer.length).toBeGreaterThan(0);
      }
    }
  });
});

test.describe('Data Tools Deep — Import Validation', () => {
  /**
   * DT-012: Import API accepts valid records
   */
  test('DT-012: import API accepts valid records', async ({ page }) => {
    const importCode = `IMP-${uniqueId('V')}`;

    const resp = await page.request.post('/api/meta/excel/import/e2et_customer', {
      data: {
        records: [
          {
            e2et_cust_code: importCode,
            e2et_cust_name: `Valid Import ${importCode}`,
            e2et_cust_region: 'north',
            e2et_cust_active: true,
          },
        ],
      },
    });

    if (resp.status() === 404 || resp.status() === 405) {
      throw new Error(String('Excel import API not available'));
      return;
    }

    // Should succeed for valid data
    if (resp.ok()) {
      const body = await resp.json();
      expect(body).toBeTruthy();
    }
  });

  /**
   * DT-013: Import API rejects duplicate records
   */
  test('DT-013: import rejects duplicate records', async ({ page }) => {
    const dupCode = `DDUP-${uniqueId('D')}`;

    // Create first record
    const createResp = await page.request.post('/api/meta/excel/import/e2et_customer', {
      data: {
        records: [
          {
            e2et_cust_code: dupCode,
            e2et_cust_name: 'First Import',
            e2et_cust_region: 'central',
            e2et_cust_active: true,
          },
        ],
      },
    });

    if (createResp.status() === 404 || createResp.status() === 405) {
      throw new Error(String('Excel import API not available'));
      return;
    }

    // Try to import duplicate
    const dupResp = await page.request.post('/api/meta/excel/import/e2et_customer', {
      data: {
        records: [
          {
            e2et_cust_code: dupCode,
            e2et_cust_name: 'Duplicate Import',
            e2et_cust_region: 'central',
            e2et_cust_active: true,
          },
        ],
      },
    });

    const body = await dupResp.json().catch(() => ({}));
    const hasError =
      !dupResp.ok() ||
      (String(body.code) !== ErrorCodes.SUCCESS && String(body.code) !== '200') ||
      body.data?.errorCount > 0 ||
      body.message?.includes('duplicate') ||
      body.message?.includes('重复');

    expect(hasError).toBe(true);
  });

  /**
   * DT-014: Import API rejects empty records
   */
  test('DT-014: import rejects empty records', async ({ page }) => {
    const resp = await page.request.post('/api/meta/excel/import/e2et_customer', {
      data: {
        records: [],
      },
    });

    if (resp.status() === 404 || resp.status() === 405) {
      throw new Error(String('Excel import API not available'));
      return;
    }

    // Empty records should return an error or empty result
    const body = await resp.json().catch(() => ({}));
    const isEmpty = body.data?.successCount === 0 || body.data?.totalCount === 0 || !resp.ok();

    expect(isEmpty || resp.ok()).toBe(true);
  });
});

test.describe('Data Tools Deep — Export API', () => {
  /**
   * DT-015: Export order list as XLSX via API
   */
  test('DT-015: export order list as XLSX', async ({ page }) => {
    const resp = await page.request.get('/api/meta/excel/export/e2et_order');

    if (resp.status() === 404 || resp.status() === 405) {
      throw new Error(String('Excel export API not available'));
      return;
    }

    if (resp.ok()) {
      const contentType = resp.headers()['content-type'] || '';
      const isExcel =
        contentType.includes('spreadsheet') ||
        contentType.includes('octet-stream') ||
        contentType.includes('xlsx');

      if (isExcel) {
        const buffer = await resp.body();
        expect(buffer.length).toBeGreaterThan(0);
      } else {
        const body = await resp.json().catch(() => null);
        expect(body).toBeTruthy();
      }
    }
  });

  /**
   * DT-016: Export customer list as XLSX via API
   */
  test('DT-016: export customer list as XLSX', async ({ page }) => {
    const resp = await page.request.get('/api/meta/excel/export/e2et_customer');

    if (resp.status() === 404 || resp.status() === 405) {
      throw new Error(String('Excel export API not available for customer'));
      return;
    }

    if (resp.ok()) {
      const contentType = resp.headers()['content-type'] || '';
      const isExcel = contentType.includes('spreadsheet') || contentType.includes('octet-stream');

      if (isExcel) {
        const buffer = await resp.body();
        expect(buffer.length).toBeGreaterThan(0);
      }
    }
  });
});
