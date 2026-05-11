/**
 * PCBA ERP - Demo Flow business role permissions.
 *
 * Coverage:
 * - Imports the PCBA pilot plugin set.
 * - Provisions one real user per PCBA business role.
 * - Logs in through the real app session path, enters Demo Flow from the sidebar,
 *   sees role-owned business data, executes one allowed command, and verifies one
 *   out-of-role command is forbidden.
 */

import { test, expect, type APIRequestContext, type Page } from '../../fixtures';
import type { Browser, Locator } from '@playwright/test';
import { basename } from 'node:path';
import { readFile } from 'node:fs/promises';
import { ErrorCodes } from '~/shared/services/http-client/types';
import { loginViaUI } from '../../helpers/wd-fixtures';
import {
  dateOffsetStr,
  ensureSidebarExpanded,
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

type CommandResult = {
  code: string;
  recordId: string;
  body: Record<string, any>;
  status: number;
};

type RoleUser = {
  roleCode: string;
  email: string;
  password: string;
};

type FixtureData = {
  rfqId: string;
  rfqSearch: string;
  purchaseOrderId: string;
  purchaseOrderCode: string;
  blockedPurchaseOrderId: string;
  inboundId: string;
  inboundCode: string;
  productionPlanId: string;
  productionPlanCode: string;
  blockedProductionPlanId: string;
  fqcId: string;
  fqcCode: string;
  blockedFqcId: string;
  paymentId: string;
  paymentCode: string;
};

const NAV_TIMEOUT = 15_000;
const PASSWORD = 'Test2026x';
const ENTERPRISE_PLUGIN_ROOT = '/Users/ghj/work/auraboot/auraboot-enterprise/plugins';

const BACKEND_PLUGIN_JARS = [
  'pcba-solution/backend/build/libs/pcba-solution-plugin-1.1.0.jar',
  'pcba-procurement/backend/build/libs/pcba-procurement-plugin-1.0.0.jar',
  'pcba-manufacturing/backend/build/libs/pcba-manufacturing-plugin-1.0.0.jar',
  'pcba-compliance/backend/build/libs/pcba-compliance-plugin-1.0.0.jar',
  'pcba-finance/backend/build/libs/pcba-finance-plugin-1.0.0.jar',
  'pcba-sales/backend/build/libs/pcba-sales-plugin-1.0.0.jar',
  'pcba-warehouse/backend/build/libs/pcba-warehouse-plugin-1.0.0.jar',
];

const REQUIRED_PLUGINS = [
  'product-catalog',
  'crm',
  'inventory',
  'finance',
  'quality',
  'sales',
  'procurement',
  'pcba-solution',
  'pcba-industry',
  'pcba-crm',
  'pcba-procurement',
  'pcba-manufacturing',
  'pcba-compliance',
  'pcba-finance',
  'pcba-sales',
  'pcba-warehouse',
];

const PAGE_KEYS = {
  rfq: 'pe-rfq',
  purchaseOrder: 'pr-purchase-order',
  inbound: 'inv-inbound',
  productionPlan: 'pe-production-plan',
  fqc: 'qc-fqc-order',
  purchasePayment: 'pr-purchase-payment',
};

const DEMO_ENTRIES = {
  rfq: {
    id: 'rfq',
    href: '/p/pe_rfq',
    label: /2\.\s*RFQ/i,
    route: /\/p\/pe_rfq(?:$|[?#])/,
    modelCode: 'pe_rfq',
  },
  purchaseOrder: {
    id: 'purchase-order',
    href: '/p/pr_purchase_order',
    label: /6\.\s*(采购订单|Purchase Order)/i,
    route: /\/p\/pr_purchase_order(?:$|[?#])/,
    modelCode: 'pr_purchase_order',
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
  fqc: {
    id: 'fqc',
    href: '/p/qc_fqc_order',
    label: /12\.\s*FQC/i,
    route: /\/p\/qc_fqc_order(?:$|[?#])/,
    modelCode: 'qc_fqc_order',
  },
  purchasePayment: {
    id: 'purchase-payment',
    href: '/p/pr_purchase_payment',
    label: /16\.\s*(采购付款|Purchase Payment)/i,
    route: /\/p\/pr_purchase_payment(?:$|[?#])/,
    modelCode: 'pr_purchase_payment',
  },
} satisfies Record<string, DemoEntry>;

const ROLE_EMAIL_SLUGS: Record<string, string> = {
  pe_sales: 'sales',
  pe_purchaser: 'purch',
  pe_warehouse: 'wh',
  pe_production: 'prod',
  pe_quality_engineer: 'qe',
  pe_finance: 'fin',
};

async function loginAdmin(request: APIRequestContext): Promise<string> {
  const response = await request.post('/api/auth/login', {
    data: { email: 'admin@auraboot.com', password: PASSWORD },
    headers: { 'Content-Type': 'application/json' },
  });
  expect(response.ok(), `admin login should succeed: HTTP ${response.status()}`).toBe(true);
  const body = await response.json();
  const jwt = body?.data?.jwt;
  expect(typeof jwt, `admin login should return jwt: ${JSON.stringify(body).slice(0, 500)}`).toBe(
    'string',
  );
  return jwt;
}

function authHeaders(jwt: string): Record<string, string> {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${jwt}` };
}

async function hotloadBackendPlugin(
  request: APIRequestContext,
  jarRelativePath: string,
  headers: Record<string, string>,
): Promise<void> {
  const jarPath = `${ENTERPRISE_PLUGIN_ROOT}/${jarRelativePath}`;
  const response = await request.post('/api/plugins/hotload/upload', {
    multipart: {
      file: {
        name: basename(jarPath),
        mimeType: 'application/java-archive',
        buffer: await readFile(jarPath),
      },
    },
    headers: { Authorization: headers.Authorization },
    timeout: 120_000,
  });

  const body = await response.json().catch(() => ({}));
  expect(
    response.ok() && body?.success === true,
    `${jarRelativePath} hotload should succeed: HTTP ${response.status()} ${JSON.stringify(body).slice(0, 800)}`,
  ).toBe(true);
}

async function importPluginDirectory(
  request: APIRequestContext,
  pluginName: string,
  headers: Record<string, string>,
): Promise<void> {
  const response = await request.post('/api/plugins/import/import-directory-sync', {
    data: {
      path: `${ENTERPRISE_PLUGIN_ROOT}/${pluginName}`,
      conflictStrategy: 'OVERWRITE_SAFE',
      autoPublishModels: true,
      autoPublishFields: true,
      autoPublishCommands: true,
      autoPublishPages: true,
    },
    headers,
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

async function executeCommand(
  request: APIRequestContext,
  headers: Record<string, string>,
  commandCode: string,
  payload: Record<string, unknown> = {},
  targetRecordId?: string,
  operationType?: string,
): Promise<CommandResult> {
  const data: Record<string, unknown> = { payload };
  if (targetRecordId) data.targetRecordId = targetRecordId;
  if (operationType) data.operationType = operationType;

  const response = await request.post(`/api/meta/commands/execute/${commandCode}`, {
    data,
    headers,
    timeout: 30_000,
  });
  const body = await response.json().catch(() => ({}));
  const resultData = body?.data?.data ?? {};
  const recordId = resultData?.recordId ?? resultData?.pid ?? resultData?.id ?? '';
  return {
    code: String(body?.code ?? ''),
    recordId: String(recordId),
    body,
    status: response.status(),
  };
}

function mustSucceed(result: CommandResult, commandCode: string): string {
  expect(
    result.code,
    `${commandCode} should succeed: HTTP ${result.status} ${JSON.stringify(result.body).slice(0, 800)}`,
  ).toBe(ErrorCodes.SUCCESS);
  expect(result.recordId, `${commandCode} should return a record id`).toBeTruthy();
  return result.recordId;
}

async function fetchRecord(
  request: APIRequestContext,
  headers: Record<string, string>,
  pageKey: string,
  pid: string,
): Promise<Record<string, any>> {
  const response = await request.get(`/api/dynamic/${pageKey}/${pid}`, { headers });
  expect(response.ok(), `GET /api/dynamic/${pageKey}/${pid} should return 200`).toBe(true);
  const body = await response.json();
  return (body.data ?? body) as Record<string, any>;
}

async function provisionRoleUser(
  request: APIRequestContext,
  headers: Record<string, string>,
  roleCode: string,
  uid: string,
): Promise<RoleUser> {
  const emailSlug = ROLE_EMAIL_SLUGS[roleCode] ?? roleCode.replace(/[^a-z0-9]/gi, '').slice(0, 8);
  const email = `e2e-pcba-${emailSlug}-${uid}@test.local`;
  const response = await request.post('/api/admin/users', {
    headers,
    data: {
      email,
      displayName: `E2E PCBA ${roleCode}`,
      initialPassword: PASSWORD,
      roleCodes: [roleCode],
      sendInviteEmail: false,
    },
  });
  const body = await response.json().catch(() => ({}));
  expect(
    body?.data?.email,
    `${roleCode} user should be provisioned: HTTP ${response.status()} ${JSON.stringify(body).slice(0, 800)}`,
  ).toBe(email);
  return { roleCode, email, password: PASSWORD };
}

async function createBom(
  request: APIRequestContext,
  headers: Record<string, string>,
  finishedProductId: string,
  materialProductId: string,
  uid: string,
): Promise<string> {
  const bomId = mustSucceed(
    await executeCommand(request, headers, 'pe:create_bom', {
      pe_bom_name: `E2E Role BOM ${uid}`,
      pe_bom_product_id: finishedProductId,
      pe_bom_version: 'V1.0',
      pe_bom_output_qty: 1,
    }),
    'pe:create_bom',
  );

  mustSucceed(
    await executeCommand(
      request,
      headers,
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
    ),
    'pe:add_bom_line',
  );

  const activate = await executeCommand(request, headers, 'pe:activate_bom', {}, bomId, 'update');
  expect(activate.code, `pe:activate_bom should succeed: ${JSON.stringify(activate.body)}`).toBe(
    ErrorCodes.SUCCESS,
  );
  return bomId;
}

async function createPurchaseOrder(
  request: APIRequestContext,
  headers: Record<string, string>,
  supplierId: string,
  productId: string,
  today: string,
  future: string,
): Promise<{ id: string; code: string }> {
  const id = mustSucceed(
    await executeCommand(request, headers, 'pr:create_purchase_order', {
      pr_po_supplier: supplierId,
      pr_po_date: today,
      pr_po_arrival_date: future,
    }),
    'pr:create_purchase_order',
  );
  mustSucceed(
    await executeCommand(
      request,
      headers,
      'pr:add_po_line',
      {
        pr_pol_order_id: id,
        pr_pol_product_id: productId,
        pr_pol_qty: 2,
        pr_pol_price: 10,
      },
      undefined,
      'create',
    ),
    'pr:add_po_line',
  );
  const record = await fetchRecord(request, headers, PAGE_KEYS.purchaseOrder, id);
  return { id, code: String(record.pr_po_code ?? '') };
}

async function createProductionPlan(
  request: APIRequestContext,
  headers: Record<string, string>,
  productId: string,
  bomId: string,
  today: string,
  future: string,
  uid: string,
  suffix: string,
): Promise<{ id: string; code: string }> {
  const id = mustSucceed(
    await executeCommand(request, headers, 'pe:create_production_plan', {
      pe_pp_name: `E2E Role Production ${suffix} ${uid}`,
      pe_pp_product_id: productId,
      pe_pp_bom_id: bomId,
      pe_pp_plan_qty: 8,
      pe_pp_plan_start: today,
      pe_pp_plan_end: future,
      pe_pp_priority: 'normal',
    }),
    'pe:create_production_plan',
  );
  const record = await fetchRecord(request, headers, PAGE_KEYS.productionPlan, id);
  return { id, code: String(record.pe_pp_code ?? '') };
}

async function createFqc(
  request: APIRequestContext,
  headers: Record<string, string>,
  productionPlanId: string,
  productId: string,
  today: string,
  uid: string,
  suffix: string,
): Promise<{ id: string; code: string }> {
  const batchNo = `FQC-ROLE-${suffix}-${uid}`;
  const id = mustSucceed(
    await executeCommand(request, headers, 'qc:create_fqc_order', {
      qc_fqc_work_order_id: productionPlanId,
      qc_fqc_product_id: productId,
      qc_fqc_batch_no: batchNo,
      qc_fqc_qty_inspected: 8,
      qc_fqc_qty_pass: 8,
      qc_fqc_qty_fail: 0,
      qc_fqc_inspector: 'E2E Role QA',
      qc_fqc_date: today,
    }),
    'qc:create_fqc_order',
  );
  const record = await fetchRecord(request, headers, PAGE_KEYS.fqc, id);
  return { id, code: String(record.qc_fqc_code ?? batchNo) };
}

async function createFixtureData(
  request: APIRequestContext,
  headers: Record<string, string>,
  uid: string,
): Promise<FixtureData> {
  const today = todayStr();
  const future = dateOffsetStr(14);

  const customerId = mustSucceed(
    await executeCommand(request, headers, 'crm:create_account', {
      crm_acc_name: `E2E PCBA Role Customer ${uid}`,
      crm_acc_phone: '13800138000',
      crm_acc_rating: 'A',
    }),
    'crm:create_account',
  );

  const finishedProductId = mustSucceed(
    await executeCommand(request, headers, 'prod:create_product', {
      prod_name: `E2E Role Finished PCBA ${uid}`,
      prod_spec: 'PCBA-ROLE-FG',
      prod_type: 'finished',
      prod_unit: 'pcs',
      prod_base_price: 120,
    }),
    'prod:create_product',
  );

  const materialProductId = mustSucceed(
    await executeCommand(request, headers, 'prod:create_product', {
      prod_name: `E2E Role Material ${uid}`,
      prod_spec: 'PCBA-ROLE-RM',
      prod_type: 'raw_material',
      prod_unit: 'pcs',
      prod_base_price: 12,
    }),
    'prod:create_product',
  );

  const supplierId = mustSucceed(
    await executeCommand(request, headers, 'pe:create_supplier', {
      pe_supplier_name: `E2E Role Supplier ${uid}`,
      pe_supplier_contact: 'E2E Buyer',
      pe_supplier_phone: '13800000000',
    }),
    'pe:create_supplier',
  );

  const warehouseId = mustSucceed(
    await executeCommand(request, headers, 'pe:create_warehouse', {
      inv_warehouse_name: `E2E Role Warehouse ${uid}`,
      inv_warehouse_type: 'finished_goods',
      inv_warehouse_address: 'E2E PCBA role permission flow',
    }),
    'pe:create_warehouse',
  );

  const bomId = await createBom(request, headers, finishedProductId, materialProductId, uid);

  const rfqSearch = `E2E PCBA Role RFQ ${uid}`;
  const rfqId = mustSucceed(
    await executeCommand(request, headers, 'pe:create_rfq', {
      pe_rfq_customer_id: customerId,
      pe_rfq_product_model: rfqSearch,
      pe_rfq_revision: 'A',
      pe_rfq_quantity: 8,
      pe_rfq_delivery_window: '14 days',
      pe_rfq_quality_class: 'class_2',
      pe_rfq_trace_level: 'l1_batch',
      pe_rfq_supply_mode: 'turnkey',
      pe_rfq_notes: `Role permission E2E ${uid}`,
    }),
    'pe:create_rfq',
  );

  const purchaseOrder = await createPurchaseOrder(
    request,
    headers,
    supplierId,
    materialProductId,
    today,
    future,
  );
  const blockedPurchaseOrder = await createPurchaseOrder(
    request,
    headers,
    supplierId,
    materialProductId,
    today,
    future,
  );

  const inboundId = mustSucceed(
    await executeCommand(request, headers, 'pe:create_warehouse_in', {
      inv_in_type: 'purchase',
      inv_in_date: today,
      inv_in_source_no: `ROLE-IN-${uid}`,
      inv_in_warehouse_id: warehouseId,
    }),
    'pe:create_warehouse_in',
  );
  mustSucceed(
    await executeCommand(
      request,
      headers,
      'pe:add_wh_in_line',
      {
        inv_in_line_receipt_id: inboundId,
        inv_in_line_product_id: materialProductId,
        inv_in_line_qty: 3,
        inv_in_line_price: 12,
      },
      undefined,
      'create',
    ),
    'pe:add_wh_in_line',
  );
  const inbound = await fetchRecord(request, headers, PAGE_KEYS.inbound, inboundId);

  const productionPlan = await createProductionPlan(
    request,
    headers,
    finishedProductId,
    bomId,
    today,
    future,
    uid,
    'main',
  );
  const blockedProductionPlan = await createProductionPlan(
    request,
    headers,
    finishedProductId,
    bomId,
    today,
    future,
    uid,
    'blocked',
  );

  const fqc = await createFqc(
    request,
    headers,
    productionPlan.id,
    finishedProductId,
    today,
    uid,
    'main',
  );
  const blockedFqc = await createFqc(
    request,
    headers,
    blockedProductionPlan.id,
    finishedProductId,
    today,
    uid,
    'blocked',
  );

  const financePurchaseOrder = await createPurchaseOrder(
    request,
    headers,
    supplierId,
    materialProductId,
    today,
    future,
  );
  const paymentId = mustSucceed(
    await executeCommand(request, headers, 'pr:create_purchase_payment', {
      pr_pay_po_id: financePurchaseOrder.id,
      pr_pay_date: today,
      pr_pay_amount: 10,
      pr_pay_method: 'bank_transfer',
      pr_pay_bank_ref: `ROLE-PAY-${uid}`,
      pr_pay_remark: 'PCBA role permission E2E',
    }),
    'pr:create_purchase_payment',
  );
  const payment = await fetchRecord(request, headers, PAGE_KEYS.purchasePayment, paymentId);

  return {
    rfqId,
    rfqSearch,
    purchaseOrderId: purchaseOrder.id,
    purchaseOrderCode: purchaseOrder.code,
    blockedPurchaseOrderId: blockedPurchaseOrder.id,
    inboundId,
    inboundCode: String(inbound.inv_in_code ?? inbound.inv_in_source_no ?? ''),
    productionPlanId: productionPlan.id,
    productionPlanCode: productionPlan.code,
    blockedProductionPlanId: blockedProductionPlan.id,
    fqcId: fqc.id,
    fqcCode: fqc.code,
    blockedFqcId: blockedFqc.id,
    paymentId,
    paymentCode: String(payment.pr_pay_code ?? ''),
  };
}

async function clickIfVisible(locator: Locator): Promise<boolean> {
  const visible = await locator.isVisible({ timeout: 1_000 }).catch(() => false);
  if (!visible) return false;
  await locator.scrollIntoViewIfNeeded();
  await locator.evaluate((el: HTMLElement) => el.click());
  return true;
}

function entryLink(nav: Locator, entry: DemoEntry): Locator {
  const byHref = nav.locator(`a[href="${entry.href}"], a[href$="${entry.href}"]`);
  const byLabel = byHref.filter({ hasText: entry.label });
  return byLabel.or(byHref).first();
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
  await expect(
    page.locator('table, [role="table"], [data-testid="dynamic-list"]').first(),
  ).toBeVisible({
    timeout: NAV_TIMEOUT,
  });
}

async function openEntryAndFindRow(
  page: Page,
  entry: DemoEntry,
  searchText: string,
): Promise<void> {
  await openDemoEntry(page, entry);
  await expectHealthyList(page);
  const row = await findRowInPaginatedList(page, searchText, NAV_TIMEOUT);
  await expect(row, `${entry.id} row should be visible: ${searchText}`).toBeVisible({
    timeout: NAV_TIMEOUT,
  });
}

async function newRolePage(browser: Browser, user: RoleUser): Promise<Page> {
  const context = await browser.newContext({ storageState: { cookies: [], origins: [] } });
  const page = await context.newPage();
  await loginViaUI(page, user.email, user.password);
  return page;
}

async function executeCommandAsRole(
  page: Page,
  commandCode: string,
  targetRecordId: string,
): Promise<{ status: number; body: Record<string, any>; code: string }> {
  const response = await page.request.post(`/api/meta/commands/execute/${commandCode}`, {
    data: { payload: {}, targetRecordId, operationType: 'update' },
    timeout: 30_000,
  });
  const body = await response.json().catch(() => ({}));
  return { status: response.status(), body, code: String(body?.code ?? '') };
}

async function expectAllowedCommand(
  page: Page,
  commandCode: string,
  targetRecordId: string,
): Promise<void> {
  const result = await executeCommandAsRole(page, commandCode, targetRecordId);
  expect(
    result.code,
    `${commandCode} should be allowed: HTTP ${result.status} ${JSON.stringify(result.body).slice(0, 800)}`,
  ).toBe(ErrorCodes.SUCCESS);
}

async function expectForbiddenCommand(
  page: Page,
  commandCode: string,
  targetRecordId: string,
): Promise<void> {
  const result = await executeCommandAsRole(page, commandCode, targetRecordId);
  const message = JSON.stringify(result.body).toLowerCase();
  expect(
    result.status === 403 ||
      result.code === ErrorCodes.FORBIDDEN ||
      message.includes('forbidden') ||
      message.includes('permission') ||
      message.includes('无权限'),
    `${commandCode} should be forbidden, got HTTP ${result.status} ${JSON.stringify(result.body).slice(0, 800)}`,
  ).toBe(true);
}

test.describe('PCBA-011 - Demo Flow business role permissions @critical', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(240_000);

  let users: Record<string, RoleUser>;
  let fixtures: FixtureData;

  test.beforeAll(async ({ request }, testInfo) => {
    testInfo.setTimeout(360_000);
    const adminJwt = await loginAdmin(request);
    const headers = authHeaders(adminJwt);

    for (const jarRelativePath of BACKEND_PLUGIN_JARS) {
      await hotloadBackendPlugin(request, jarRelativePath, headers);
    }

    for (const pluginName of REQUIRED_PLUGINS) {
      await importPluginDirectory(request, pluginName, headers);
    }

    const uid = uniqueId('pcba_role').replace(/_/g, '-');
    const roleCodes = [
      'pe_sales',
      'pe_purchaser',
      'pe_warehouse',
      'pe_production',
      'pe_quality_engineer',
      'pe_finance',
    ];
    users = {};
    for (const roleCode of roleCodes) {
      users[roleCode] = await provisionRoleUser(request, headers, roleCode, uid);
    }
    fixtures = await createFixtureData(request, headers, uid);
  });

  test('pe_sales can work RFQ and is blocked from warehouse confirmation', async ({ browser }) => {
    const page = await newRolePage(browser, users.pe_sales);
    await openEntryAndFindRow(page, DEMO_ENTRIES.rfq, fixtures.rfqSearch);
    await expectAllowedCommand(page, 'pe:submit_rfq', fixtures.rfqId);
    await expectForbiddenCommand(page, 'pe:confirm_warehouse_in', fixtures.inboundId);
    await page.context().close();
  });

  test('pe_purchaser can work purchase orders and is blocked from production confirmation', async ({
    browser,
  }) => {
    const page = await newRolePage(browser, users.pe_purchaser);
    await openEntryAndFindRow(page, DEMO_ENTRIES.purchaseOrder, fixtures.purchaseOrderCode);
    await expectAllowedCommand(page, 'pr:submit_purchase_order', fixtures.purchaseOrderId);
    await expectForbiddenCommand(page, 'pe:confirm_production', fixtures.blockedProductionPlanId);
    await page.context().close();
  });

  test('pe_warehouse can confirm inbound and is blocked from production confirmation', async ({
    browser,
  }) => {
    const page = await newRolePage(browser, users.pe_warehouse);
    await openEntryAndFindRow(page, DEMO_ENTRIES.inbound, fixtures.inboundCode);
    await expectAllowedCommand(page, 'pe:confirm_warehouse_in', fixtures.inboundId);
    await expectForbiddenCommand(page, 'pe:confirm_production', fixtures.blockedProductionPlanId);
    await page.context().close();
  });

  test('pe_production can confirm production and is blocked from purchase submission', async ({
    browser,
  }) => {
    const page = await newRolePage(browser, users.pe_production);
    await openEntryAndFindRow(page, DEMO_ENTRIES.productionPlan, fixtures.productionPlanCode);
    await expectAllowedCommand(page, 'pe:confirm_production', fixtures.productionPlanId);
    await expectForbiddenCommand(page, 'pr:submit_purchase_order', fixtures.blockedPurchaseOrderId);
    await page.context().close();
  });

  test('pe_quality_engineer can complete FQC and is blocked from purchase submission', async ({
    browser,
  }) => {
    const page = await newRolePage(browser, users.pe_quality_engineer);
    await openEntryAndFindRow(page, DEMO_ENTRIES.fqc, fixtures.fqcCode);
    await expectAllowedCommand(page, 'qc:complete_fqc', fixtures.fqcId);
    await expectForbiddenCommand(page, 'pr:submit_purchase_order', fixtures.blockedPurchaseOrderId);
    await page.context().close();
  });

  test('pe_finance can confirm purchase payment and is blocked from FQC completion', async ({
    browser,
  }) => {
    const page = await newRolePage(browser, users.pe_finance);
    await openEntryAndFindRow(page, DEMO_ENTRIES.purchasePayment, fixtures.paymentCode);
    await expectAllowedCommand(page, 'pr:confirm_purchase_payment', fixtures.paymentId);
    await expectForbiddenCommand(page, 'qc:complete_fqc', fixtures.blockedFqcId);
    await page.context().close();
  });
});
