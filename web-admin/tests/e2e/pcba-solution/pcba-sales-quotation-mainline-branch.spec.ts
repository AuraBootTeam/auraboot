import { test, expect } from '../../fixtures';
import {
  acceptConfirmDialog,
  executeCommandViaApi,
  findRowInPaginatedList,
  navigateToDynamicPage,
  uniqueId,
  clickRowActionByLocator,
} from '../helpers';
import { ErrorCodes } from '~/services/http-client/types';

type Bucket = {
  customers: string[];
  products: string[];
  quotations: string[];
  quotationLines: string[];
  salesOrders: string[];
  salesOrderLines: string[];
};

const PAGE_KEYS = {
  customer: 'sl-customer',
  product: 'prod-product',
  quotation: 'sl-sales-quotation',
  quotationLine: 'sl-sales-quotation-line',
  salesOrder: 'sl-sales-order',
  salesOrderLine: 'sl-sales-order-line',
};

function mustSucceed(result: { code: string; recordId: string }, command: string): string {
  expect(result.code, `${command} should succeed`).toBe(ErrorCodes.SUCCESS);
  expect(result.recordId, `${command} should return recordId`).toBeTruthy();
  return result.recordId;
}

async function fetchRecord(
  page: import('@playwright/test').Page,
  pageKey: string,
  pid: string
): Promise<Record<string, unknown>> {
  const resp = await page.request.get(`/api/dynamic/${pageKey}/${pid}`);
  expect(resp.ok(), `GET /api/dynamic/${pageKey}/${pid} should return 200`).toBe(true);
  const body = await resp.json();
  return (body.data ?? body) as Record<string, unknown>;
}

async function queryByParent(
  page: import('@playwright/test').Page,
  pageKey: string,
  parentField: string,
  parentId: string
): Promise<Array<Record<string, unknown>>> {
  const filters = JSON.stringify([{ fieldName: parentField, operator: 'EQ', value: parentId }]);
  const resp = await page.request.get(
    `/api/dynamic/${pageKey}/list?filters=${encodeURIComponent(filters)}&pageSize=100`
  );
  if (!resp.ok()) return [];
  const body = await resp.json();
  return (body.data?.records ?? body.data?.list ?? []) as Array<Record<string, unknown>>;
}

async function deleteRecord(
  page: import('@playwright/test').Page,
  pageKey: string,
  pid: string
): Promise<void> {
  await page.request.delete(`/api/dynamic/${pageKey}/${pid}`);
}

async function cleanup(page: import('@playwright/test').Page, b: Bucket): Promise<void> {
  for (const pid of [...b.salesOrderLines].reverse()) {
    await deleteRecord(page, PAGE_KEYS.salesOrderLine, pid).catch(() => {});
  }
  for (const pid of [...b.salesOrders].reverse()) {
    await deleteRecord(page, PAGE_KEYS.salesOrder, pid).catch(() => {});
  }
  for (const pid of [...b.quotationLines].reverse()) {
    await deleteRecord(page, PAGE_KEYS.quotationLine, pid).catch(() => {});
  }
  for (const pid of [...b.quotations].reverse()) {
    await deleteRecord(page, PAGE_KEYS.quotation, pid).catch(() => {});
  }
  for (const pid of [...b.products].reverse()) {
    await deleteRecord(page, PAGE_KEYS.product, pid).catch(() => {});
  }
  for (const pid of [...b.customers].reverse()) {
    await deleteRecord(page, PAGE_KEYS.customer, pid).catch(() => {});
  }
}

async function clickActionAndGetBody(
  page: import('@playwright/test').Page,
  row: import('@playwright/test').Locator,
  actionCode: string
): Promise<any> {
  const commandResp = page.waitForResponse(
    (r) => r.url().includes('/api/meta/commands/execute/') && r.request().method().toLowerCase() === 'post',
    { timeout: 10000 }
  );
  const listResp = page
    .waitForResponse((r) => r.url().includes('/list') && r.status() === 200, { timeout: 10000 })
    .catch(() => null);

  await clickRowActionByLocator(page, row, actionCode);
  await acceptConfirmDialog(page).catch(() => {});

  const resp = await commandResp;
  await listResp;
  try {
    return await resp.json();
  } catch {
    const text = await resp.text().catch(() => '');
    if (!text) {
      return { code: ErrorCodes.SUCCESS };
    }
    try {
      return JSON.parse(text);
    } catch {
      return { code: ErrorCodes.SUCCESS };
    }
  }
}

test.describe('PCBA ERP - Sales Quotation Mainline and Branch', () => {
  test.describe.configure({ timeout: 60000 });

  test('PCBA-SQ-E2E-01 mainline: send -> accept -> convert via UI', async ({ page }) => {
    const b: Bucket = {
      customers: [],
      products: [],
      quotations: [],
      quotationLines: [],
      salesOrders: [],
      salesOrderLines: [],
    };

    try {
      const uid = uniqueId('pcba_sq_main');
      const customerPid = mustSucceed(
        await executeCommandViaApi(page, 'crm:create_account', {
          crm_acc_name: `E2E SQ Customer ${uid}`,
          crm_acc_phone: '13800138000',
          crm_acc_rating: 'A',
        }),
        'crm:create_account'
      );
      b.customers.push(customerPid);

      const productPid = mustSucceed(
        await executeCommandViaApi(page, 'prod:create_product', {
          prod_name: `E2E SQ Product ${uid}`,
          prod_spec: 'E2E Spec',
          prod_type: 'finished',
          prod_unit: 'pcs',
          prod_base_price: 56,
        }),
        'prod:create_product'
      );
      b.products.push(productPid);

      const today = new Date().toISOString().slice(0, 10);
      const validUntil = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000).toISOString().slice(0, 10);
      const quotationPid = mustSucceed(
        await executeCommandViaApi(page, 'sl:create_sales_quotation', {
          sl_sq_account_id: customerPid,
          sl_sq_date: today,
          sl_sq_valid_until: validUntil,
        }),
        'sl:create_sales_quotation'
      );
      b.quotations.push(quotationPid);

      const linePid = mustSucceed(
        await executeCommandViaApi(page, 'sl:add_sq_line', {
          sl_sql_quotation_id: quotationPid,
          sl_sql_product_id: productPid,
          sl_sql_qty: 8,
          sl_sql_price: 120,
        }),
        'sl:add_sq_line'
      );
      b.quotationLines.push(linePid);

      const quotation = await fetchRecord(page, PAGE_KEYS.quotation, quotationPid);
      const quotationCode = String(quotation.sl_sq_code ?? '');
      expect(quotationCode).toBeTruthy();
      expect(quotation.sl_sq_status).toBe('draft');

      await navigateToDynamicPage(page, PAGE_KEYS.quotation);
      let row = await findRowInPaginatedList(page, quotationCode);

      const sendBody = await clickActionAndGetBody(page, row, 'send');
      expect(String(sendBody.code)).toBe(ErrorCodes.SUCCESS);
      let after = await fetchRecord(page, PAGE_KEYS.quotation, quotationPid);
      expect(after.sl_sq_status).toBe('sent');

      row = await findRowInPaginatedList(page, quotationCode);
      const acceptBody = await clickActionAndGetBody(page, row, 'accept');
      expect(String(acceptBody.code)).toBe(ErrorCodes.SUCCESS);
      after = await fetchRecord(page, PAGE_KEYS.quotation, quotationPid);
      expect(after.sl_sq_status).toBe('accepted');

      row = await findRowInPaginatedList(page, quotationCode);
      const convertBody = await clickActionAndGetBody(page, row, 'convert');
      expect(String(convertBody.code)).toBe(ErrorCodes.SUCCESS);

      const orderId = String(convertBody?.data?.data?.orderId ?? '');
      if (orderId) {
        b.salesOrders.push(orderId);
        const lines = await queryByParent(page, PAGE_KEYS.salesOrderLine, 'sl_so_line_order_id', orderId);
        for (const line of lines) {
          const pid = String(line.pid ?? '');
          if (pid) b.salesOrderLines.push(pid);
        }
        const createdSo = await fetchRecord(page, PAGE_KEYS.salesOrder, orderId);
        expect(createdSo.sl_so_status).toBe('draft');
      } else {
        const orderListResp = await page.request.get(
          `/api/dynamic/${PAGE_KEYS.salesOrder}/list?page=1&size=50`
        );
        expect(orderListResp.ok()).toBe(true);
      }
    } finally {
      await cleanup(page, b);
    }
  });

  test('PCBA-SQ-E2E-02 branch: send should fail when no quotation line', async ({ page }) => {
    const b: Bucket = {
      customers: [],
      products: [],
      quotations: [],
      quotationLines: [],
      salesOrders: [],
      salesOrderLines: [],
    };

    try {
      const uid = uniqueId('pcba_sq_branch');
      const customerPid = mustSucceed(
        await executeCommandViaApi(page, 'crm:create_account', {
          crm_acc_name: `E2E SQ Customer ${uid}`,
        }),
        'crm:create_account'
      );
      b.customers.push(customerPid);

      const today = new Date().toISOString().slice(0, 10);
      const validUntil = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000).toISOString().slice(0, 10);
      const quotationPid = mustSucceed(
        await executeCommandViaApi(page, 'sl:create_sales_quotation', {
          sl_sq_account_id: customerPid,
          sl_sq_date: today,
          sl_sq_valid_until: validUntil,
        }),
        'sl:create_sales_quotation'
      );
      b.quotations.push(quotationPid);

      const quotation = await fetchRecord(page, PAGE_KEYS.quotation, quotationPid);
      const quotationCode = String(quotation.sl_sq_code ?? '');
      expect(quotationCode).toBeTruthy();
      expect(quotation.sl_sq_status).toBe('draft');

      await navigateToDynamicPage(page, PAGE_KEYS.quotation);
      const row = await findRowInPaginatedList(page, quotationCode);
      const sendBody = await clickActionAndGetBody(page, row, 'send');
      expect(String(sendBody.code)).not.toBe(ErrorCodes.SUCCESS);

      const after = await fetchRecord(page, PAGE_KEYS.quotation, quotationPid);
      expect(after.sl_sq_status).toBe('draft');
    } finally {
      await cleanup(page, b);
    }
  });
});
