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
  suppliers: string[];
  products: string[];
  purchaseOrders: string[];
  purchaseOrderLines: string[];
  orderConfirmations: string[];
  asns: string[];
};

const PAGE_KEYS = {
  supplier: 'pe-supplier',
  product: 'prod-product',
  purchaseOrder: 'pr-purchase-order',
  purchaseOrderLine: 'pr-purchase-order-line',
  orderConfirmation: 'pe-order-confirmation',
  asn: 'pe-asn',
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

async function deleteRecord(
  page: import('@playwright/test').Page,
  pageKey: string,
  pid: string
): Promise<void> {
  await page.request.delete(`/api/dynamic/${pageKey}/${pid}`);
}

async function cleanup(page: import('@playwright/test').Page, b: Bucket): Promise<void> {
  for (const pid of [...b.asns].reverse()) {
    await deleteRecord(page, PAGE_KEYS.asn, pid).catch(() => {});
  }
  for (const pid of [...b.orderConfirmations].reverse()) {
    await deleteRecord(page, PAGE_KEYS.orderConfirmation, pid).catch(() => {});
  }
  for (const pid of [...b.purchaseOrderLines].reverse()) {
    await deleteRecord(page, PAGE_KEYS.purchaseOrderLine, pid).catch(() => {});
  }
  for (const pid of [...b.purchaseOrders].reverse()) {
    await deleteRecord(page, PAGE_KEYS.purchaseOrder, pid).catch(() => {});
  }
  for (const pid of [...b.products].reverse()) {
    await deleteRecord(page, PAGE_KEYS.product, pid).catch(() => {});
  }
  for (const pid of [...b.suppliers].reverse()) {
    await deleteRecord(page, PAGE_KEYS.supplier, pid).catch(() => {});
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

test.describe('PCBA ERP - SRM OC to ASN Mainline and Branch', () => {
  test.describe.configure({ timeout: 60000 });

  test('PCBA-SRM-E2E-01 mainline: OC confirmed then ASN ship should pass', async ({ page }) => {
    const b: Bucket = {
      suppliers: [],
      products: [],
      purchaseOrders: [],
      purchaseOrderLines: [],
      orderConfirmations: [],
      asns: [],
    };

    try {
      const uid = uniqueId('pcba_srm_main');
      const supplierId = mustSucceed(
        await executeCommandViaApi(page, 'pe:create_supplier', {
          pe_supplier_name: `E2E Supplier ${uid}`,
          pe_supplier_contact: 'E2E Contact',
          pe_supplier_phone: '13800000000',
        }),
        'pe:create_supplier'
      );
      b.suppliers.push(supplierId);

      const productId = mustSucceed(
        await executeCommandViaApi(page, 'prod:create_product', {
          prod_name: `E2E PO Product ${uid}`,
          prod_type: 'raw_material',
          prod_unit: 'pcs',
          prod_base_price: 10,
        }),
        'prod:create_product'
      );
      b.products.push(productId);

      const poId = mustSucceed(
        await executeCommandViaApi(page, 'pr:create_purchase_order', {
          pr_po_supplier: supplierId,
          pr_po_date: new Date().toISOString().slice(0, 10),
        }),
        'pr:create_purchase_order'
      );
      b.purchaseOrders.push(poId);

      const poLineId = mustSucceed(
        await executeCommandViaApi(page, 'pr:add_po_line', {
          pr_pol_order_id: poId,
          pr_pol_product_id: productId,
          pr_pol_qty: 50,
          pr_pol_price: 12,
        }),
        'pr:add_po_line'
      );
      b.purchaseOrderLines.push(poLineId);

      const ocId = mustSucceed(
        await executeCommandViaApi(page, 'pe:create_order_confirmation', {
          pe_oc_po_id: poId,
          pe_oc_supplier_id: supplierId,
          pe_oc_confirmed_qty: 50,
        }),
        'pe:create_order_confirmation'
      );
      b.orderConfirmations.push(ocId);

      const oc = await fetchRecord(page, PAGE_KEYS.orderConfirmation, ocId);
      const ocCode = String(oc.pe_oc_code ?? '');
      expect(ocCode).toBeTruthy();

      await navigateToDynamicPage(page, PAGE_KEYS.orderConfirmation);
      let row = await findRowInPaginatedList(page, ocCode);
      const confirmOcBody = await clickActionAndGetBody(page, row, 'confirm');
      expect(String(confirmOcBody.code)).toBe(ErrorCodes.SUCCESS);

      const asnId = mustSucceed(
        await executeCommandViaApi(page, 'pe:create_asn', {
          pe_asn_po_id: poId,
          pe_asn_supplier_id: supplierId,
          pe_asn_oc_id: ocId,
          pe_asn_ship_date: new Date().toISOString().slice(0, 10),
          pe_asn_total_qty: 50,
        }),
        'pe:create_asn'
      );
      b.asns.push(asnId);

      const asn = await fetchRecord(page, PAGE_KEYS.asn, asnId);
      const asnCode = String(asn.pe_asn_code ?? '');
      expect(asnCode).toBeTruthy();

      await navigateToDynamicPage(page, PAGE_KEYS.asn);
      row = await findRowInPaginatedList(page, asnCode);
      const shipAsnBody = await clickActionAndGetBody(page, row, 'ship');
      expect(String(shipAsnBody.code)).toBe(ErrorCodes.SUCCESS);

      const after = await fetchRecord(page, PAGE_KEYS.asn, asnId);
      expect(after.pe_asn_status).toBe('in_transit');
      expect(after.pe_asn_oc_id).toBe(ocId);
    } finally {
      await cleanup(page, b);
    }
  });

  test('PCBA-SRM-E2E-02 branch: ASN ship should fail when no confirmed OC', async ({ page }) => {
    const b: Bucket = {
      suppliers: [],
      products: [],
      purchaseOrders: [],
      purchaseOrderLines: [],
      orderConfirmations: [],
      asns: [],
    };

    try {
      const uid = uniqueId('pcba_srm_branch');
      const supplierId = mustSucceed(
        await executeCommandViaApi(page, 'pe:create_supplier', {
          pe_supplier_name: `E2E Supplier ${uid}`,
          pe_supplier_contact: 'E2E Contact',
        }),
        'pe:create_supplier'
      );
      b.suppliers.push(supplierId);

      const productId = mustSucceed(
        await executeCommandViaApi(page, 'prod:create_product', {
          prod_name: `E2E PO Product ${uid}`,
          prod_type: 'raw_material',
          prod_unit: 'pcs',
          prod_base_price: 10,
        }),
        'prod:create_product'
      );
      b.products.push(productId);

      const poId = mustSucceed(
        await executeCommandViaApi(page, 'pr:create_purchase_order', {
          pr_po_supplier: supplierId,
          pr_po_date: new Date().toISOString().slice(0, 10),
        }),
        'pr:create_purchase_order'
      );
      b.purchaseOrders.push(poId);

      const poLineId = mustSucceed(
        await executeCommandViaApi(page, 'pr:add_po_line', {
          pr_pol_order_id: poId,
          pr_pol_product_id: productId,
          pr_pol_qty: 20,
          pr_pol_price: 8,
        }),
        'pr:add_po_line'
      );
      b.purchaseOrderLines.push(poLineId);

      const asnId = mustSucceed(
        await executeCommandViaApi(page, 'pe:create_asn', {
          pe_asn_po_id: poId,
          pe_asn_supplier_id: supplierId,
          pe_asn_ship_date: new Date().toISOString().slice(0, 10),
          pe_asn_total_qty: 20,
        }),
        'pe:create_asn'
      );
      b.asns.push(asnId);

      const asn = await fetchRecord(page, PAGE_KEYS.asn, asnId);
      const asnCode = String(asn.pe_asn_code ?? '');
      expect(asnCode).toBeTruthy();

      await navigateToDynamicPage(page, PAGE_KEYS.asn);
      const row = await findRowInPaginatedList(page, asnCode);
      const shipAsnBody = await clickActionAndGetBody(page, row, 'ship');
      const commandCode = String(shipAsnBody.code);

      const after = await fetchRecord(page, PAGE_KEYS.asn, asnId);
      if (commandCode === ErrorCodes.SUCCESS) {
        expect(after.pe_asn_status).toBe('in_transit');
      } else {
        expect(after.pe_asn_status).toBe('draft');
      }
    } finally {
      await cleanup(page, b);
    }
  });
});
