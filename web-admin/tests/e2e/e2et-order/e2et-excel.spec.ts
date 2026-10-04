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
import type { Page } from '@playwright/test';
import { uniqueId, navigateToDynamicPage } from '../helpers';
import { ModelTestHelper } from '../../helpers/model-test-helper';
import { E2ET_CUSTOMER_CONFIG } from '../../helpers/configs/e2et-customer.config';
import { E2ET_ORDER_CONFIG } from '../../helpers/configs/e2et-order.config';
import * as XLSX from 'xlsx';

async function downloadExportRows(
  page: Page,
  payload: Record<string, unknown>,
): Promise<Record<string, unknown>[]> {
  const response = await page.request.post('/api/dynamic/e2et_order/export', { data: payload });
  expect(response.ok(), await response.text()).toBe(true);
  const body = await response.json();
  const result = body?.data ?? body;
  expect(result.downloadUrl, JSON.stringify(body)).toBeTruthy();

  const download = await page.request.get(String(result.downloadUrl));
  expect(download.ok(), await download.text()).toBe(true);
  expect(download.headers()['content-disposition']).toContain('attachment');
  const workbook = XLSX.read(await download.body(), { type: 'buffer', raw: true });
  expect(workbook.SheetNames).toHaveLength(1);
  return XLSX.utils.sheet_to_json(workbook.Sheets[workbook.SheetNames[0]], { defval: null });
}

function workbookText(rows: Record<string, unknown>[]): string {
  return rows.flatMap((row) => Object.values(row)).join('\n');
}

function documentWorkbook(
  orders: Record<string, unknown>[],
  lines: Record<string, unknown>[],
): Buffer {
  const workbook = XLSX.utils.book_new();
  XLSX.utils.book_append_sheet(workbook, XLSX.utils.json_to_sheet(orders), 'Orders');
  XLSX.utils.book_append_sheet(workbook, XLSX.utils.json_to_sheet(lines), 'Lines');
  return XLSX.write(workbook, { type: 'buffer', bookType: 'xlsx' });
}

async function queryModelRows(
  page: Page,
  modelCode: string,
  fieldName: string,
  value: unknown,
): Promise<Record<string, unknown>[]> {
  const filters = encodeURIComponent(JSON.stringify([{ fieldName, operator: 'EQ', value }]));
  const response = await page.request.get(
    `/api/dynamic/${modelCode}/list?pageNum=1&pageSize=20&filters=${filters}`,
  );
  expect(response.ok(), await response.text()).toBe(true);
  const body = await response.json();
  return body.data?.records ?? body.data?.data ?? [];
}

async function importDocumentWorkbook(page: Page, buffer: Buffer) {
  const response = await page.request.post(
    '/api/meta/excel/document-import/e2et_order?dryRun=false&skipErrors=false',
    {
      multipart: {
        file: {
          name: 'e2et-order-document.xlsx',
          mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
          buffer,
        },
      },
    },
  );
  expect(response.ok(), await response.text()).toBe(true);
  const body = await response.json();
  expect(String(body.code)).toBe('0');
  return body.data;
}

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
   * EX-004: Selected and filtered exports preserve their explicit scope.
   */
  test('EX-004: should export only selected or filtered order rows @critical', async ({ page }, testInfo) => {
    const helper = new ModelTestHelper(page, E2ET_ORDER_CONFIG);
    const selectedTitle = `E2E Export Selected ${uniqueId('S')}`;
    const controlTitle = `E2E Export Control ${uniqueId('C')}`;
    const selectedPid = await helper.createViaApi({ e2et_order_title: selectedTitle });
    const controlPid = await helper.createViaApi({ e2et_order_title: controlTitle });

    try {
      const selectedRows = await downloadExportRows(page, {
        format: 'xlsx',
        scope: 'selected',
        selectedPids: [selectedPid],
        fields: ['e2et_order_title'],
      });
      expect(selectedRows).toHaveLength(1);
      const selectedText = workbookText(selectedRows);
      expect(selectedText).toContain(selectedTitle);
      expect(selectedText).not.toContain(controlTitle);

      const filteredRows = await downloadExportRows(page, {
        format: 'xlsx',
        scope: 'filtered',
        conditions: [
          { field: 'e2et_order_title', operator: 'EQ', value: controlTitle },
        ],
        fields: ['e2et_order_title'],
      });
      expect(filteredRows).toHaveLength(1);
      const filteredText = workbookText(filteredRows);
      expect(filteredText).toContain(controlTitle);
      expect(filteredText).not.toContain(selectedTitle);

      await page.goto('/');
      const dataJobsMenu = page.getByText('数据任务', { exact: true }).first();
      await expect(dataJobsMenu).toBeVisible();
      await dataJobsMenu.click();
      await expect(page).toHaveURL(/\/p\/c\/exchange_job_center/);
      await expect(page.getByRole('heading', { name: '数据交换任务', exact: true })).toBeVisible();
      await expect(page.getByText(/已处理 1 \/ 1/).first()).toBeVisible();
      await expect(page.getByRole('link', { name: /下载/ }).first()).toBeVisible();
      const refresh = page.getByRole('button', { name: /刷新任务/ });
      await expect(refresh).toBeVisible();
      await Promise.all([
        page.waitForResponse((response) =>
          response.url().includes('/api/exchange/jobs') && response.status() === 200),
        refresh.click(),
      ]);
      await page.screenshot({
        path: testInfo.outputPath('job-center-desktop-completed.png'),
        fullPage: true,
      });

      await page.setViewportSize({ width: 390, height: 844 });
      await expect(page.getByTestId('sidebar')).not.toBeInViewport();
      await expect(page.getByRole('heading', { name: '数据交换任务', exact: true })).toBeVisible();
      await expect(page.getByRole('link', { name: /下载/ }).first()).toBeVisible();
      await page.screenshot({
        path: testInfo.outputPath('job-center-narrow-completed.png'),
        fullPage: true,
      });
    } finally {
      await helper.deleteViaApi(selectedPid);
      await helper.deleteViaApi(controlPid);
    }
  });

  /**
   * EX-005: A two-sheet document workbook executes one idempotent aggregate Command.
   */
  test('EX-005: should import an order and its lines as one document @critical', async ({ page }) => {
    const helper = new ModelTestHelper(page, E2ET_ORDER_CONFIG);
    const group = `E2E Document ${uniqueId('DOC')}`;
    const invalidGroup = `${group}-orphan`;

    const invalid = await importDocumentWorkbook(
      page,
      documentWorkbook(
        [{ e2et_order_title: group, e2et_order_desc: 'aggregate import guard' }],
        [{
          e2et_item_spec: invalidGroup,
          e2et_item_name: 'Orphan line',
          e2et_item_qty: 1,
          e2et_item_price: 9.5,
        }],
      ),
    );
    expect(invalid.successCount).toBe(0);
    expect(invalid.errorCount).toBeGreaterThan(0);
    expect(await queryModelRows(page, 'e2et_order', 'e2et_order_title', group)).toHaveLength(0);

    const workbook = documentWorkbook(
      [{ e2et_order_title: group, e2et_order_desc: 'aggregate import accepted' }],
      [
        {
          e2et_item_spec: group,
          e2et_item_name: 'Line A',
          e2et_item_qty: 2,
          e2et_item_price: 10.5,
        },
        {
          e2et_item_spec: group,
          e2et_item_name: 'Line B',
          e2et_item_qty: 3,
          e2et_item_price: 20,
        },
      ],
    );

    const first = await importDocumentWorkbook(page, workbook);
    expect(first.successCount).toBe(1);
    expect(first.errorCount).toBe(0);
    expect(first.taskId).toBeTruthy();

    const replay = await importDocumentWorkbook(page, workbook);
    expect(replay.successCount).toBe(1);
    expect(replay.errorCount).toBe(0);
    expect(replay.taskId).toBeTruthy();

    const orders = await queryModelRows(page, 'e2et_order', 'e2et_order_title', group);
    expect(orders).toHaveLength(1);
    const orderPid = String(orders[0].pid);
    try {
      const lines = await queryModelRows(page, 'e2et_order_item', 'e2et_order_id', orderPid);
      expect(lines).toHaveLength(2);
      expect(lines.map((line) => line.e2et_item_name).sort()).toEqual(['Line A', 'Line B']);

      const jobsResponse = await page.request.get('/api/exchange/jobs?limit=50');
      expect(jobsResponse.ok(), await jobsResponse.text()).toBe(true);
      const jobsBody = await jobsResponse.json();
      const jobs = jobsBody.data ?? jobsBody;
      expect(jobs.some((job: Record<string, unknown>) => job.pid === first.taskId)).toBe(true);
      expect(jobs.some((job: Record<string, unknown>) => job.pid === replay.taskId)).toBe(true);
    } finally {
      await helper.deleteViaApi(orderPid);
    }
  });
});
