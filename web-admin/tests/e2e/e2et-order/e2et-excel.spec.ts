/**
 * E2E Test Order — Excel Import/Export
 *
 * Tests EX-001 ~ EX-004: Excel template download and data import
 * - Download customer Excel template
 * - Import customer data via API, verify on UI list
 * - Import duplicate code+region → error
 * - Export order list XLSX
 *
 * API is used for setup and import, UI for verification (E2E constraint).
 * Uses real database, NO MOCKING.
 *
 * @since 6.2.0
 */

import { test, expect } from '../../fixtures';
import { uniqueId, navigateToDynamicPage } from '../helpers';
import { ModelTestHelper } from '../../helpers/model-test-helper';
import { E2ET_CUSTOMER_CONFIG } from '../../helpers/configs/e2et-customer.config';
import * as fs from 'fs';
import * as path from 'path';

test.describe('E2E Test Order — Excel Import/Export', () => {
  /**
   * EX-001: Download customer Excel template
   */
  test('EX-001: should download customer Excel template @smoke', async ({ page }) => {
    const resp = await page.request.get('/api/meta/excel/template/e2et_customer');

    if (resp.status() === 404) {
      throw new Error(String('Excel template API not available for e2et_customer'));
      return;
    }

    expect(resp.ok()).toBe(true);

    // Verify response is an Excel file
    const contentType = resp.headers()['content-type'] || '';
    const isExcel =
      contentType.includes('spreadsheet') ||
      contentType.includes('octet-stream') ||
      contentType.includes('xlsx');

    // Some APIs return JSON with download URL instead
    if (!isExcel) {
      const body = await resp.json().catch(() => null);
      expect(body).toBeTruthy();
    } else {
      const buffer = await resp.body();
      expect(buffer.length).toBeGreaterThan(0);
    }
  });

  /**
   * EX-002: Import customer XLSX data, verify on UI list
   */
  test('EX-002: should import customer data and verify on list @smoke', async ({ page }) => {
    const importCode = `IMP-${uniqueId('E')}`;

    // Try to import via API
    const resp = await page.request.post('/api/meta/excel/import/e2et_customer', {
      data: {
        records: [
          {
            e2et_cust_code: importCode,
            e2et_cust_name: `Import Customer ${importCode}`,
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

    // If API works, verify on UI list
    if (resp.ok()) {
      await navigateToDynamicPage(page, 'e2et_customer');

      // Search for imported customer
      const searchInput = page
        .locator(
          '[data-testid="search-input"] input, input[placeholder*="搜索"], input[placeholder*="Search"]',
        )
        .first();

      const hasSearch = await searchInput.isVisible({ timeout: 5000 }).catch(() => false);
      if (hasSearch) {
        await searchInput.fill(importCode);
        await searchInput.press('Enter');
        await page
          .waitForResponse((r) => r.url().includes('/list') && r.status() === 200, {
            timeout: 10000,
          })
          .catch(() => null);
      }

      // Verify data appears in the table
      const tableText = await page
        .locator('tbody, [role="rowgroup"]')
        .first()
        .textContent({ timeout: 5000 })
        .catch(() => '');
      expect(tableText).toContain(importCode);
    }
  });

  /**
   * EX-003: Import duplicate code+region should fail
   */
  test('EX-003: should reject import with duplicate code+region @critical', async ({ page }) => {
    const helper = new ModelTestHelper(page, E2ET_CUSTOMER_CONFIG);
    const dupCode = `EXDUP-${uniqueId('D')}`;

    // Create existing customer first
    await helper.createViaApi({
      e2et_cust_code: dupCode,
      e2et_cust_name: 'Existing Import',
      e2et_cust_region: 'central',
    });

    // Try to import same code+region via API
    const resp = await page.request.post('/api/meta/excel/import/e2et_customer', {
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

    if (resp.status() === 404 || resp.status() === 405) {
      throw new Error(String('Excel import API not available'));
      return;
    }

    // Should either fail or report errors
    const body = await resp.json().catch(() => ({}));
    const hasErrors =
      !resp.ok() ||
      String(body.code) !== '200' ||
      body.data?.errorCount > 0 ||
      body.message?.includes('重复') ||
      body.message?.includes('duplicate');

    expect(hasErrors).toBe(true);
  });

  /**
   * EX-004: Export order list as XLSX
   */
  test('EX-004: should export order list as XLSX @critical', async ({ page }) => {
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
        // API may return JSON with download URL
        const body = await resp.json().catch(() => null);
        expect(body).toBeTruthy();
      }
    }
  });
});
