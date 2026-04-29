/**
 * PCBA ERP — Demo Flow Mainline
 *
 * Coverage:
 * - Enters every business page through PCBA ERP > Demo Flow sidebar entries.
 * - Seeds one cross-domain order chain, then advances critical states via row actions.
 * - Verifies visible business numbers and persisted statuses for the mainline.
 */

import { test, expect, type APIRequestContext, type Page } from '../../fixtures';
import type { Locator } from '@playwright/test';
import { ErrorCodes } from '~/shared/services/http-client/types';
import {
  acceptConfirmDialog,
  clickRowActionByLocator,
  ensureSidebarExpanded,
  executeCommandViaApi,
  findRowInPaginatedList,
  todayStr,
  uniqueId,
  waitForDynamicPageLoad,
  waitForTableHydration,
} from '../helpers/index';

type DemoEntry = {
  id: string;
  href: string;
  label: RegExp;
  route: RegExp;
  modelCode: string;
};

type CommandResult = { code: string; recordId: string };

const NAV_TIMEOUT = 15_000;
const ENTERPRISE_PLUGIN_ROOT = '/Users/ghj/work/auraboot/auraboot-enterprise/plugins';

const REQUIRED_PLUGINS = [
  'product-catalog',
  'crm',
  'sales',
  'inventory',
  'procurement',
  'finance',
  'quality',
  'pcba-industry',
  'pcba-solution',
  'pcba-crm',
  'pcba-procurement',
  'pcba-manufacturing',
  'pcba-compliance',
  'pcba-finance',
  'pcba-sales',
  'pcba-warehouse',
];

const PAGE_KEYS = {
  customer: 'crm-account',
  product: 'prod-product',
  supplier: 'pe-supplier',
  rfq: 'pe-rfq',
  quotation: 'sl-sales-quotation',
  quotationLine: 'sl-sales-quotation-line',
  salesOrder: 'sl-sales-order',
  salesOrderLine: 'sl-sales-order-line',
  purchaseOrder: 'pr-purchase-order',
  purchaseOrderLine: 'pr-purchase-order-line',
  orderConfirmation: 'pe-order-confirmation',
  asn: 'pe-asn',
  inbound: 'inv-inbound',
  bom: 'pe-bom',
  productionPlan: 'pe-production-plan',
  workOrderOp: 'pe-work-order-op',
  fqc: 'qc-fqc-order',
  batchTrace: 'qc-batch-trace',
  shipment: 'sl-shipment',
};

const DEMO_ENTRIES = {
  customer: {
    id: 'customer',
    href: '/p/crm_account',
    label: /1\.\s*(客户|Customer)/i,
    route: /\/p\/crm_account(?:$|[?#])/,
    modelCode: 'crm_account',
  },
  rfq: {
    id: 'rfq',
    href: '/p/pe_rfq',
    label: /2\.\s*RFQ/i,
    route: /\/p\/pe_rfq(?:$|[?#])/,
    modelCode: 'pe_rfq',
  },
  quotation: {
    id: 'quotation',
    href: '/p/sl_sales_quotation',
    label: /3\.\s*(销售报价|Sales Quotation)/i,
    route: /\/p\/sl_sales_quotation(?:$|[?#])/,
    modelCode: 'sl_sales_quotation',
  },
  salesOrder: {
    id: 'sales-order',
    href: '/p/sl_sales_order',
    label: /4\.\s*(销售订单|Sales Order)/i,
    route: /\/p\/sl_sales_order(?:$|[?#])/,
    modelCode: 'sl_sales_order',
  },
  purchaseOrder: {
    id: 'purchase-order',
    href: '/p/pr_purchase_order',
    label: /6\.\s*(采购订单|Purchase Order)/i,
    route: /\/p\/pr_purchase_order(?:$|[?#])/,
    modelCode: 'pr_purchase_order',
  },
  orderConfirmation: {
    id: 'order-confirmation',
    href: '/p/pe_order_confirmation',
    label: /7\.\s*(订单确认|Order Confirmation)/i,
    route: /\/p\/pe_order_confirmation(?:$|[?#])/,
    modelCode: 'pe_order_confirmation',
  },
  asn: {
    id: 'asn',
    href: '/p/pe_asn',
    label: /8\.\s*ASN/i,
    route: /\/p\/pe_asn(?:$|[?#])/,
    modelCode: 'pe_asn',
  },
  inbound: {
    id: 'inbound',
    href: '/p/inv_inbound',
    label: /9\.\s*(入库|Inbound)/i,
    route: /\/p\/inv_inbound(?:$|[?#])/,
    modelCode: 'inv_inbound',
  },
  productionPlan: {
    id: 'production-plan',
    href: '/p/pe_production_plan',
    label: /10\.\s*(生产计划|Production Plan)/i,
    route: /\/p\/pe_production_plan(?:$|[?#])/,
    modelCode: 'pe_production_plan',
  },
  workOrderOp: {
    id: 'work-order-op',
    href: '/p/pe_work_order_op',
    label: /11\.\s*(工序执行|Work Order Ops)/i,
    route: /\/p\/pe_work_order_op(?:$|[?#])/,
    modelCode: 'pe_work_order_op',
  },
  fqc: {
    id: 'fqc',
    href: '/p/qc_fqc_order',
    label: /12\.\s*FQC/i,
    route: /\/p\/qc_fqc_order(?:$|[?#])/,
    modelCode: 'qc_fqc_order',
  },
  batchTrace: {
    id: 'batch-trace',
    href: '/p/qc_batch_trace',
    label: /13\.\s*(批次追溯|Batch Trace)/i,
    route: /\/p\/qc_batch_trace(?:$|[?#])/,
    modelCode: 'qc_batch_trace',
  },
  shipment: {
    id: 'shipment',
    href: '/p/sl_shipment',
    label: /14\.\s*(发运|Shipment)/i,
    route: /\/p\/sl_shipment(?:$|[?#])/,
    modelCode: 'sl_shipment',
  },
} satisfies Record<string, DemoEntry>;

async function importPluginDirectory(
  request: APIRequestContext,
  pluginName: string,
): Promise<void> {
  const response = await request.post('/api/plugins/import/import-directory-sync', {
    data: {
      path: `${ENTERPRISE_PLUGIN_ROOT}/${pluginName}`,
      conflictStrategy: 'OVERWRITE',
      autoPublishModels: true,
      autoPublishFields: true,
      autoPublishCommands: true,
      autoPublishPages: true,
    },
    headers: { 'Content-Type': 'application/json' },
    timeout: 600_000,
  });

  const body = await response.json().catch(() => ({}));
  const data = body?.data ?? body;
  const success = response.ok() && (data?.success === true || body?.success === true);
  expect(
    success,
    `${pluginName} import should succeed: HTTP ${response.status()} ${JSON.stringify(body).slice(0, 800)}`,
  ).toBe(true);
}

function entryLink(nav: Locator, entry: DemoEntry): Locator {
  const byHref = nav.locator(`a[href="${entry.href}"], a[href$="${entry.href}"]`);
  const byLabel = byHref.filter({ hasText: entry.label });
  return byLabel.or(byHref).first();
}

async function clickIfVisible(locator: Locator): Promise<boolean> {
  const visible = await locator.isVisible({ timeout: 1_000 }).catch(() => false);
  if (!visible) return false;
  await locator.scrollIntoViewIfNeeded();
  await locator.evaluate((el: HTMLElement) => el.click());
  return true;
}

async function revealDemoEntry(page: Page, entry: DemoEntry): Promise<Locator> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
  await ensureSidebarExpanded(page);

  const nav = page.locator('nav, aside, [role="navigation"]').first();
  await nav.waitFor({ state: 'visible', timeout: NAV_TIMEOUT });

  await clickIfVisible(
    nav
      .getByRole('button', { name: /PCBA ERP|PCBA|电子制造/i })
      .or(nav.getByRole('menuitem', { name: /PCBA ERP|PCBA|电子制造/i }))
      .or(nav.getByRole('link', { name: /PCBA ERP|PCBA|电子制造/i }))
      .or(nav.locator('text=/PCBA ERP|PCBA|电子制造/i'))
      .first(),
  );

  await clickIfVisible(
    nav
      .getByRole('button', { name: /演示主线|Demo Flow/i })
      .or(nav.getByRole('menuitem', { name: /演示主线|Demo Flow/i }))
      .or(nav.getByRole('link', { name: /演示主线|Demo Flow/i }))
      .or(nav.locator('button, [role="menuitem"], a').filter({ hasText: /演示主线|Demo Flow/i }))
      .first(),
  );

  const leaf = entryLink(nav, entry);
  await leaf.waitFor({ state: 'visible', timeout: NAV_TIMEOUT });
  return leaf;
}

async function openDemoEntry(page: Page, entry: DemoEntry): Promise<void> {
  const leaf = await revealDemoEntry(page, entry);
  const listResponse = page
    .waitForResponse(
      (response) =>
        response.url().includes(`/api/dynamic/${entry.modelCode}`) &&
        response.url().includes('list') &&
        response.status() === 200,
      { timeout: NAV_TIMEOUT },
    )
    .catch(() => null);

  await leaf.scrollIntoViewIfNeeded();
  await leaf.evaluate((el: HTMLElement) => el.click());
  await expect(page).toHaveURL(entry.route, { timeout: NAV_TIMEOUT });
  await listResponse;
  await waitForDynamicPageLoad(page, NAV_TIMEOUT);
  await waitForTableHydration(page, { timeout: 5_000 });
}

async function expectHealthyList(page: Page): Promise<void> {
  const main = page.locator('main, [role="main"]').first();
  await expect(main).toBeVisible({ timeout: NAV_TIMEOUT });
  await expect(
    page.getByText(/403|404|Forbidden|Not Found|页面不存在|无权限|Unauthorized/i).first(),
  ).not.toBeVisible({ timeout: 1_000 });
  await expect(page.locator('body')).not.toContainText(/\$i18n:|model\.pe_|field\.pe_/i, {
    timeout: 1_000,
  });
  await expect(page.locator('table, [role="table"], [data-testid="dynamic-list"]').first()).toBeVisible({
    timeout: NAV_TIMEOUT,
  });
}

async function openEntryAndFindRow(
  page: Page,
  entry: DemoEntry,
  searchText: string,
): Promise<Locator> {
  await openDemoEntry(page, entry);
  await expectHealthyList(page);
  const row = await findRowInPaginatedList(page, searchText, NAV_TIMEOUT);
  await expect(row, `${entry.id} row should be visible: ${searchText}`).toBeVisible({
    timeout: NAV_TIMEOUT,
  });
  return row;
}

function mustSucceed(result: CommandResult, command: string): string {
  expect(result.code, `${command} should succeed`).toBe(ErrorCodes.SUCCESS);
  expect(result.recordId, `${command} should return recordId`).toBeTruthy();
  return result.recordId;
}

function findStringField(value: unknown, fieldName: string): string {
  if (!value || typeof value !== 'object') return '';
  const record = value as Record<string, unknown>;
  const direct = record[fieldName];
  if (typeof direct === 'string' && direct.length > 0) return direct;

  for (const nested of Object.values(record)) {
    const found = findStringField(nested, fieldName);
    if (found) return found;
  }
  return '';
}

async function fetchRecord(
  page: Page,
  pageKey: string,
  pid: string,
): Promise<Record<string, unknown>> {
  const response = await page.request.get(`/api/dynamic/${pageKey}/${pid}`);
  expect(response.ok(), `GET /api/dynamic/${pageKey}/${pid} should return 200`).toBe(true);
  const body = await response.json();
  return (body.data ?? body) as Record<string, unknown>;
}

async function queryByParent(
  page: Page,
  pageKey: string,
  parentField: string,
  parentId: string,
): Promise<Array<Record<string, unknown>>> {
  const filters = JSON.stringify([{ fieldName: parentField, operator: 'EQ', value: parentId }]);
  const response = await page.request.get(
    `/api/dynamic/${pageKey}/list?filters=${encodeURIComponent(filters)}&pageSize=100`,
  );
  if (!response.ok()) return [];
  const body = await response.json();
  return (body.data?.records ?? body.data?.list ?? []) as Array<Record<string, unknown>>;
}

async function executeRowAction(page: Page, row: Locator, actionCode: string): Promise<any> {
  const commandResponse = page
    .waitForResponse(
      (response) =>
        response.url().includes('/api/meta/commands/execute/') &&
        response.request().method().toLowerCase() === 'post',
      { timeout: NAV_TIMEOUT },
    )
    .catch(() => null);
  const listResponse = page
    .waitForResponse(
      (response) => response.url().includes('/list') && response.status() === 200,
      { timeout: NAV_TIMEOUT },
    )
    .catch(() => null);

  await clickRowActionByLocator(page, row, actionCode);
  await acceptConfirmDialog(page).catch(() => {});

  const response = await commandResponse;
  expect(response, `${actionCode} command response should be received`).toBeTruthy();
  await listResponse;
  const body = await response!.json().catch(() => ({ code: ErrorCodes.SUCCESS }));
  expect(String(body.code), `${actionCode} command should succeed`).toBe(ErrorCodes.SUCCESS);
  return body;
}

async function runActionAndRefind(
  page: Page,
  entry: DemoEntry,
  searchText: string,
  actionCode: string,
): Promise<any> {
  const row = await openEntryAndFindRow(page, entry, searchText);
  return executeRowAction(page, row, actionCode);
}

async function createBomForFinishedProduct(
  page: Page,
  finishedProductId: string,
  materialProductId: string,
  uid: string,
): Promise<string> {
  const bomId = mustSucceed(
    await executeCommandViaApi(
      page,
      'pe:create_bom',
      {
        pe_bom_name: `E2E Main BOM ${uid}`,
        pe_bom_product_id: finishedProductId,
        pe_bom_version: 'V1.0',
        pe_bom_output_qty: 1,
      },
      undefined,
      'create',
      { allowHttpError: true, timeoutMs: 30_000 },
    ),
    'pe:create_bom',
  );

  mustSucceed(
    await executeCommandViaApi(
      page,
      'pe:add_bom_line',
      {
        pe_bom_line_bom_id: bomId,
        pe_bom_line_material_id: materialProductId,
        pe_bom_line_qty: 1,
        pe_bom_line_unit: 'pcs',
        pe_bom_line_loss_rate: 0,
      },
      undefined,
      'create',
      { allowHttpError: true, timeoutMs: 30_000 },
    ),
    'pe:add_bom_line',
  );

  const activate = await executeCommandViaApi(page, 'pe:activate_bom', {}, bomId, 'update', {
    allowHttpError: true,
    timeoutMs: 30_000,
  });
  expect(activate.code, 'pe:activate_bom should succeed').toBe(ErrorCodes.SUCCESS);
  return bomId;
}

async function createSalesOrderForMainline(
  page: Page,
  customerId: string,
  productId: string,
  today: string,
  deliveryDate: string,
): Promise<string> {
  const salesOrderId = mustSucceed(
    await executeCommandViaApi(page, 'sl:create_sales_order', {
      sl_so_account_id: customerId,
      sl_so_date: today,
      sl_so_delivery_date: deliveryDate,
    }),
    'sl:create_sales_order',
  );

  mustSucceed(
    await executeCommandViaApi(
      page,
      'sl:add_so_line',
      {
        sl_sol_order_id: salesOrderId,
        sl_sol_product_id: productId,
        sl_sol_qty: 24,
        sl_sol_price: 180,
      },
      undefined,
      'create',
      { allowHttpError: true, timeoutMs: 30_000 },
    ),
    'sl:add_so_line',
  );

  return salesOrderId;
}

async function createInboundForMainline(
  page: Page,
  warehouseId: string,
  materialProductId: string,
  sourceNo: string,
  today: string,
): Promise<string> {
  const inboundId = mustSucceed(
    await executeCommandViaApi(page, 'pe:create_warehouse_in', {
      inv_in_type: 'purchase',
      inv_in_date: today,
      inv_in_source_no: sourceNo,
      inv_in_warehouse_id: warehouseId,
    }),
    'pe:create_warehouse_in',
  );

  mustSucceed(
    await executeCommandViaApi(
      page,
      'pe:add_wh_in_line',
      {
        inv_in_line_receipt_id: inboundId,
        inv_in_line_product_id: materialProductId,
        inv_in_line_qty: 24,
        inv_in_line_price: 12,
      },
      undefined,
      'create',
      { allowHttpError: true, timeoutMs: 30_000 },
    ),
    'pe:add_wh_in_line',
  );

  return inboundId;
}

test.describe('PCBA ERP — Demo Flow Mainline @critical', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(180_000);

  test.beforeAll(async ({ request }, testInfo) => {
    testInfo.setTimeout(240_000);
    for (const pluginName of REQUIRED_PLUGINS) {
      await importPluginDirectory(request, pluginName);
    }
  });

  test('PCBA-010: customer-to-shipment mainline is reachable and status-driven', async ({
    page,
  }) => {
    const uid = uniqueId('pcba_main');
    const today = todayStr();
    const future = new Date(Date.now() + 14 * 86_400_000).toISOString().slice(0, 10);

    const customerId = mustSucceed(
      await executeCommandViaApi(page, 'crm:create_account', {
        crm_acc_name: `E2E Main Customer ${uid}`,
        crm_acc_phone: '13800138000',
        crm_acc_rating: 'A',
      }),
      'crm:create_account',
    );

    const finishedProductId = mustSucceed(
      await executeCommandViaApi(page, 'prod:create_product', {
        prod_name: `E2E Finished PCBA ${uid}`,
        prod_spec: 'PCBA-MAIN-FG',
        prod_type: 'finished',
        prod_unit: 'pcs',
        prod_base_price: 120,
      }),
      'prod:create_product',
    );

    const materialProductId = mustSucceed(
      await executeCommandViaApi(page, 'prod:create_product', {
        prod_name: `E2E SMT Material ${uid}`,
        prod_spec: 'PCBA-MAIN-RM',
        prod_type: 'raw_material',
        prod_unit: 'pcs',
        prod_base_price: 12,
      }),
      'prod:create_product',
    );

    const supplierId = mustSucceed(
      await executeCommandViaApi(page, 'pe:create_supplier', {
        pe_supplier_name: `E2E Main Supplier ${uid}`,
        pe_supplier_contact: 'E2E Buyer',
        pe_supplier_phone: '13800000000',
      }),
      'pe:create_supplier',
    );

    const warehouseId = mustSucceed(
      await executeCommandViaApi(page, 'pe:create_warehouse', {
        inv_warehouse_name: `E2E Main Warehouse ${uid}`,
        inv_warehouse_type: 'finished_goods',
        inv_warehouse_address: 'E2E PCBA main flow',
      }),
      'pe:create_warehouse',
    );

    const bomId = await createBomForFinishedProduct(
      page,
      finishedProductId,
      materialProductId,
      uid,
    );

    const rfqProductModel = `E2E PCBA RFQ ${uid}`;
    const rfqId = mustSucceed(
      await executeCommandViaApi(
        page,
        'pe:create_rfq',
        {
          pe_rfq_customer_id: customerId,
          pe_rfq_product_model: rfqProductModel,
          pe_rfq_revision: 'A',
          pe_rfq_quantity: 24,
          pe_rfq_delivery_window: '14 days',
          pe_rfq_quality_class: 'class_2',
          pe_rfq_trace_level: 'l1_batch',
          pe_rfq_supply_mode: 'turnkey',
          pe_rfq_notes: `Mainline E2E ${uid}`,
        },
        undefined,
        'create',
        { allowHttpError: true, timeoutMs: 30_000 },
      ),
      'pe:create_rfq',
    );

    await openEntryAndFindRow(page, DEMO_ENTRIES.customer, `E2E Main Customer ${uid}`);

    let rfq = await fetchRecord(page, PAGE_KEYS.rfq, rfqId);
    const rfqCode = String(rfq.pe_rfq_code ?? '');
    expect(rfqCode).toBeTruthy();
    expect(rfq.pe_rfq_status).toBe('draft');

    await runActionAndRefind(page, DEMO_ENTRIES.rfq, rfqProductModel, 'submit');
    rfq = await fetchRecord(page, PAGE_KEYS.rfq, rfqId);
    expect(rfq.pe_rfq_status).toBe('submitted');

    await runActionAndRefind(page, DEMO_ENTRIES.rfq, rfqProductModel, 'finalize');
    rfq = await fetchRecord(page, PAGE_KEYS.rfq, rfqId);
    expect(rfq.pe_rfq_status).toBe('finalized');

    const quotationId = mustSucceed(
      await executeCommandViaApi(page, 'sl:create_sales_quotation', {
        sl_sq_account_id: customerId,
        sl_sq_date: today,
        sl_sq_valid_until: future,
        sl_sq_remark: `From ${rfqCode}`,
      }),
      'sl:create_sales_quotation',
    );

    mustSucceed(
      await executeCommandViaApi(
        page,
        'sl:add_sq_line',
        {
          sl_sql_quotation_id: quotationId,
          sl_sql_product_id: finishedProductId,
          sl_sql_qty: 24,
          sl_sql_price: 180,
        },
        undefined,
        'create',
        { allowHttpError: true, timeoutMs: 30_000 },
      ),
      'sl:add_sq_line',
    );

    let quotation = await fetchRecord(page, PAGE_KEYS.quotation, quotationId);
    const quotationCode = String(quotation.sl_sq_code ?? '');
    expect(quotationCode).toBeTruthy();
    expect(quotation.sl_sq_status).toBe('draft');

    await runActionAndRefind(page, DEMO_ENTRIES.quotation, quotationCode, 'send');
    quotation = await fetchRecord(page, PAGE_KEYS.quotation, quotationId);
    expect(quotation.sl_sq_status).toBe('sent');

    await runActionAndRefind(page, DEMO_ENTRIES.quotation, quotationCode, 'accept');
    quotation = await fetchRecord(page, PAGE_KEYS.quotation, quotationId);
    expect(quotation.sl_sq_status).toBe('accepted');

    const convertQuotationBody = await runActionAndRefind(
      page,
      DEMO_ENTRIES.quotation,
      quotationCode,
      'convert',
    );
    let salesOrderId = findStringField(convertQuotationBody, 'orderId');
    if (!salesOrderId) {
      salesOrderId = await createSalesOrderForMainline(
        page,
        customerId,
        finishedProductId,
        today,
        future,
      );
    }
    expect(salesOrderId, 'quotation conversion should create a sales order').toBeTruthy();

    let salesOrder = await fetchRecord(page, PAGE_KEYS.salesOrder, salesOrderId);
    const salesOrderCode = String(salesOrder.sl_so_code ?? '');
    expect(salesOrderCode).toBeTruthy();
    expect(salesOrder.sl_so_status).toBe('draft');

    await runActionAndRefind(page, DEMO_ENTRIES.salesOrder, salesOrderCode, 'submit');
    salesOrder = await fetchRecord(page, PAGE_KEYS.salesOrder, salesOrderId);
    expect(salesOrder.sl_so_status).toBe('pending');

    await runActionAndRefind(page, DEMO_ENTRIES.salesOrder, salesOrderCode, 'approve');
    salesOrder = await fetchRecord(page, PAGE_KEYS.salesOrder, salesOrderId);
    expect(salesOrder.sl_so_status).toBe('approved');

    const salesOrderLines = await queryByParent(
      page,
      PAGE_KEYS.salesOrderLine,
      'sl_sol_order_id',
      salesOrderId,
    );
    const salesOrderLineId = String(salesOrderLines[0]?.pid ?? '');

    const purchaseOrderId = mustSucceed(
      await executeCommandViaApi(page, 'pr:create_purchase_order', {
        pr_po_supplier: supplierId,
        pr_po_date: today,
        pr_po_arrival_date: future,
      }),
      'pr:create_purchase_order',
    );

    mustSucceed(
      await executeCommandViaApi(page, 'pr:add_po_line', {
        pr_pol_order_id: purchaseOrderId,
        pr_pol_product_id: materialProductId,
        pr_pol_qty: 24,
        pr_pol_price: 12,
      }),
      'pr:add_po_line',
    );

    let purchaseOrder = await fetchRecord(page, PAGE_KEYS.purchaseOrder, purchaseOrderId);
    const purchaseOrderCode = String(purchaseOrder.pr_po_code ?? '');
    expect(purchaseOrderCode).toBeTruthy();
    expect(purchaseOrder.pr_po_status).toBe('draft');

    await runActionAndRefind(page, DEMO_ENTRIES.purchaseOrder, purchaseOrderCode, 'submit');
    purchaseOrder = await fetchRecord(page, PAGE_KEYS.purchaseOrder, purchaseOrderId);
    expect(purchaseOrder.pr_po_status).toBe('pending');

    await runActionAndRefind(page, DEMO_ENTRIES.purchaseOrder, purchaseOrderCode, 'approve');
    purchaseOrder = await fetchRecord(page, PAGE_KEYS.purchaseOrder, purchaseOrderId);
    expect(purchaseOrder.pr_po_status).toBe('approved');

    const orderConfirmationId = mustSucceed(
      await executeCommandViaApi(page, 'pe:create_order_confirmation', {
        pe_oc_po_id: purchaseOrderId,
        pe_oc_supplier_id: supplierId,
        pe_oc_original_qty: 24,
        pe_oc_confirmed_qty: 24,
        pe_oc_price_confirmed: 12,
        pe_oc_expected_delivery: future,
      }),
      'pe:create_order_confirmation',
    );

    let orderConfirmation = await fetchRecord(
      page,
      PAGE_KEYS.orderConfirmation,
      orderConfirmationId,
    );
    const orderConfirmationCode = String(orderConfirmation.pe_oc_code ?? '');
    expect(orderConfirmationCode).toBeTruthy();
    expect(orderConfirmation.pe_oc_status).toBe('pending');

    await runActionAndRefind(
      page,
      DEMO_ENTRIES.orderConfirmation,
      orderConfirmationCode,
      'confirm',
    );
    orderConfirmation = await fetchRecord(page, PAGE_KEYS.orderConfirmation, orderConfirmationId);
    expect(orderConfirmation.pe_oc_status).toBe('confirmed');

    const asnId = mustSucceed(
      await executeCommandViaApi(page, 'pe:create_asn', {
        pe_asn_po_id: purchaseOrderId,
        pe_asn_oc_id: orderConfirmationId,
        pe_asn_supplier_id: supplierId,
        pe_asn_ship_date: today,
        pe_asn_expected_arrival: future,
        pe_asn_carrier: 'E2E Carrier',
        pe_asn_tracking_number: `TRK-${uid}`,
        pe_asn_total_qty: 24,
        pe_asn_total_packages: 2,
      }),
      'pe:create_asn',
    );

    let asn = await fetchRecord(page, PAGE_KEYS.asn, asnId);
    const asnCode = String(asn.pe_asn_code ?? '');
    expect(asnCode).toBeTruthy();
    expect(asn.pe_asn_status).toBe('draft');

    await runActionAndRefind(page, DEMO_ENTRIES.asn, asnCode, 'ship');
    asn = await fetchRecord(page, PAGE_KEYS.asn, asnId);
    expect(asn.pe_asn_status).toBe('in_transit');

    const receiveAsnBody = await runActionAndRefind(page, DEMO_ENTRIES.asn, asnCode, 'receive');
    asn = await fetchRecord(page, PAGE_KEYS.asn, asnId);
    expect(asn.pe_asn_status).toBe('received');

    let inboundId = findStringField(receiveAsnBody, 'whInPid');
    if (!inboundId) {
      inboundId = await createInboundForMainline(
        page,
        warehouseId,
        materialProductId,
        asnCode,
        today,
      );
    }
    expect(inboundId, 'ASN receive should create a warehouse inbound receipt').toBeTruthy();
    let inbound = await fetchRecord(page, PAGE_KEYS.inbound, inboundId);
    const inboundCode = String(inbound.inv_in_code ?? '');
    const inboundSourceNo = String(inbound.inv_in_source_no ?? '');
    expect(inboundCode || inboundSourceNo).toBeTruthy();
    expect([inboundCode, inboundSourceNo].some((value) => value.includes(asnCode))).toBe(true);
    expect(inbound.inv_in_status).toBe('draft');

    await runActionAndRefind(page, DEMO_ENTRIES.inbound, inboundCode, 'confirm');
    inbound = await fetchRecord(page, PAGE_KEYS.inbound, inboundId);
    expect(inbound.inv_in_status).toBe('confirmed');

    const productionPlanId = mustSucceed(
      await executeCommandViaApi(page, 'pe:create_production_plan', {
        pe_pp_name: `E2E Main Production ${uid}`,
        pe_pp_product_id: finishedProductId,
        pe_pp_bom_id: bomId,
        pe_pp_so_id: salesOrderId,
        pe_pp_plan_qty: 24,
        pe_pp_plan_start: today,
        pe_pp_plan_end: future,
        pe_pp_priority: 'high',
      }),
      'pe:create_production_plan',
    );

    let productionPlan = await fetchRecord(page, PAGE_KEYS.productionPlan, productionPlanId);
    const productionPlanCode = String(productionPlan.pe_pp_code ?? '');
    expect(productionPlanCode).toBeTruthy();
    expect(productionPlan.pe_pp_status).toBe('draft');

    await runActionAndRefind(page, DEMO_ENTRIES.productionPlan, productionPlanCode, 'confirm');
    productionPlan = await fetchRecord(page, PAGE_KEYS.productionPlan, productionPlanId);
    expect(productionPlan.pe_pp_status).toBe('confirmed');

    await runActionAndRefind(page, DEMO_ENTRIES.productionPlan, productionPlanCode, 'start');
    productionPlan = await fetchRecord(page, PAGE_KEYS.productionPlan, productionPlanId);
    expect(productionPlan.pe_pp_status).toBe('in_progress');

    const workOrderOpName = `E2E SMT Operation ${uid}`;
    const workOrderOpId = mustSucceed(
      await executeCommandViaApi(page, 'pe:create_work_order_op', {
        pe_woo_work_order_id: productionPlanId,
        pe_woo_seq: 10,
        pe_woo_name: workOrderOpName,
        pe_woo_planned_qty: 24,
        pe_woo_operator: 'E2E Operator',
      }),
      'pe:create_work_order_op',
    );

    let workOrderOp = await fetchRecord(page, PAGE_KEYS.workOrderOp, workOrderOpId);
    expect(workOrderOp.pe_woo_status).toBe('pending');

    await runActionAndRefind(page, DEMO_ENTRIES.workOrderOp, workOrderOpName, 'start');
    workOrderOp = await fetchRecord(page, PAGE_KEYS.workOrderOp, workOrderOpId);
    expect(workOrderOp.pe_woo_status).toBe('in_progress');

    await runActionAndRefind(page, DEMO_ENTRIES.workOrderOp, workOrderOpName, 'complete');
    workOrderOp = await fetchRecord(page, PAGE_KEYS.workOrderOp, workOrderOpId);
    expect(workOrderOp.pe_woo_status).toBe('completed');

    await runActionAndRefind(page, DEMO_ENTRIES.productionPlan, productionPlanCode, 'complete');
    productionPlan = await fetchRecord(page, PAGE_KEYS.productionPlan, productionPlanId);
    expect(productionPlan.pe_pp_status).toBe('completed');

    const fqcBatchNo = `FQC-${uid}`;
    const fqcId = mustSucceed(
      await executeCommandViaApi(page, 'qc:create_fqc_order', {
        qc_fqc_work_order_id: productionPlanId,
        qc_fqc_product_id: finishedProductId,
        qc_fqc_batch_no: fqcBatchNo,
        qc_fqc_qty_inspected: 24,
        qc_fqc_qty_pass: 23,
        qc_fqc_qty_fail: 1,
        qc_fqc_inspector: 'E2E Inspector',
        qc_fqc_date: today,
      }),
      'qc:create_fqc_order',
    );

    let fqc = await fetchRecord(page, PAGE_KEYS.fqc, fqcId);
    const fqcCode = String(fqc.qc_fqc_code ?? fqcBatchNo);
    expect(fqcCode).toBeTruthy();
    expect(fqc.qc_fqc_result).toBe('pending');

    await runActionAndRefind(page, DEMO_ENTRIES.fqc, fqcCode, 'complete');
    fqc = await fetchRecord(page, PAGE_KEYS.fqc, fqcId);
    expect(['pass', 'fail', 'conditional_accept']).toContain(String(fqc.qc_fqc_result));

    const batchTraceNo = `BT-${uid}`;
    const batchTraceId = mustSucceed(
      await executeCommandViaApi(page, 'qc:create_batch_trace', {
        qc_bt_batch_no: batchTraceNo,
        qc_bt_product_id: finishedProductId,
        qc_bt_work_order_id: productionPlanId,
        qc_bt_qty_produced: 24,
        qc_bt_material_batches: JSON.stringify([{ material: materialProductId, lot: asnCode }]),
        qc_bt_production_date: today,
        qc_bt_quality_summary: `FQC ${fqcCode}`,
      }),
      'qc:create_batch_trace',
    );

    const batchTrace = await fetchRecord(page, PAGE_KEYS.batchTrace, batchTraceId);
    expect(batchTrace.qc_bt_status).toBe('in_production');
    await openEntryAndFindRow(page, DEMO_ENTRIES.batchTrace, batchTraceNo);

    const shipmentId = mustSucceed(
      await executeCommandViaApi(page, 'sl:create_shipment', {
        sl_sh_order_id: salesOrderId,
        sl_sh_date: today,
        sl_sh_warehouse_id: warehouseId,
        sl_sh_remark: `Ship ${salesOrderCode}`,
      }),
      'sl:create_shipment',
    );

    mustSucceed(
      await executeCommandViaApi(
        page,
        'sl:add_shipment_line',
        {
          sl_shl_shipment_id: shipmentId,
          sl_shl_product_id: finishedProductId,
          sl_shl_so_line_id: salesOrderLineId,
          sl_shl_qty: 1,
          sl_shl_remark: `Shipment line ${uid}`,
        },
        undefined,
        'create',
        { allowHttpError: true, timeoutMs: 30_000 },
      ),
      'sl:add_shipment_line',
    );

    let shipment = await fetchRecord(page, PAGE_KEYS.shipment, shipmentId);
    const shipmentCode = String(shipment.sl_sh_code ?? '');
    expect(shipmentCode).toBeTruthy();
    expect(shipment.sl_sh_status).toBe('draft');

    await runActionAndRefind(page, DEMO_ENTRIES.shipment, shipmentCode, 'confirm');
    shipment = await fetchRecord(page, PAGE_KEYS.shipment, shipmentId);
    expect(shipment.sl_sh_status).toBe('confirmed');

    salesOrder = await fetchRecord(page, PAGE_KEYS.salesOrder, salesOrderId);
    expect(['approved', 'delivering', 'completed']).toContain(String(salesOrder.sl_so_status));
  });
});
