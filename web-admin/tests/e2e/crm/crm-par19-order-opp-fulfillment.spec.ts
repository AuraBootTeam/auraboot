import { expect, test, type Page } from '../../fixtures';
import fs from 'node:fs';
import path from 'node:path';
import { BACKEND_URL, BASE_URL } from '../../helpers/environments';

/**
 * PAR-19 T-D slice: order → opportunity stage linkage + fulfillment detail.
 * Frozen contract: enterprise docs/plans/2026-08/2026-08-29-par19-order-stage-fulfillment-goal-freeze.md.
 *
 * Journeys:
 *  1. Direct order linked to an OPEN opportunity → UI approve → opportunity
 *     stage advances to closed_won (UI detail verified).
 *  2. Shipment lifecycle: draft shipment with an order-line reference → UI
 *     确认发货 → order line shipped qty backfilled + fulfillment records
 *     sub-table on the order detail.
 *  3. Over-shipment fails closed: confirm attempt renders a page-level
 *     business error and the shipment stays draft.
 */

const RUN_ID = `par19td-${Date.now()}`;
const ADMIN_EMAIL = 'admin@auraboot.com';
const ADMIN_PASSWORD = 'Test2026x';
const EVIDENCE_ROOT = process.env.AURA_EVIDENCE_ROOT
  ? path.join(process.env.AURA_EVIDENCE_ROOT, 'par19-order-fulfillment')
  : path.resolve(process.cwd(), '..', '.workspace', 'evidence', 'par19-order-fulfillment-s66', 'par19-order-fulfillment');

interface MatrixApiResult {
  ok: boolean;
  status: number;
  body: any;
  recordId: string;
}

test.describe.configure({ mode: 'serial' });

function shot(page: Page, name: string): void {
  fs.mkdirSync(EVIDENCE_ROOT, { recursive: true });
  page.screenshot({ path: path.join(EVIDENCE_ROOT, `${name}.png`), fullPage: false }).catch(() => {});
}

async function loginJwt(email: string, password: string): Promise<string> {
  const resp = await fetch(`${BACKEND_URL}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password }),
  });
  const body: any = await resp.json().catch(() => ({}));
  expect(resp.status === 200 && Boolean(body?.data?.jwt), `login ${email}`).toBe(true);
  return body.data.jwt;
}

async function matrixApi(jwt: string, apiPath: string, method: 'GET' | 'POST' | 'DELETE' = 'GET', payload?: unknown): Promise<MatrixApiResult> {
  const resp = await fetch(`${BACKEND_URL}${apiPath}`, {
    method,
    headers: { Authorization: `Bearer ${jwt}`, 'Content-Type': 'application/json' },
    body: payload === undefined ? undefined : JSON.stringify(payload),
  });
  const body: any = await resp.json().catch(() => null);
  const recordId: string = body?.data?.data?.recordPid ?? body?.data?.data?.recordId ?? body?.data?.data?.pid ?? '';
  return { ok: resp.ok && body?.code === '0', status: resp.status, body, recordId };
}

async function injectCsrfToken(page: Page, jwt: string): Promise<void> {
  await page.addInitScript((token) => {
    try {
      localStorage.setItem('jwtToken', token);
    } catch {
      // storage unavailable — auth will fail visibly in the journey
    }
  }, jwt);
}

let adminPid = '';
let accountPid = '';
let opportunityPid = '';
let orderPid = '';
let orderLinePid = '';
let productPid = '';
let warehousePid = '';

test.beforeAll(async () => {
  const jwt = await loginJwt(ADMIN_EMAIL, ADMIN_PASSWORD);
  const me = await fetch(`${BACKEND_URL}/api/auth/me`, { headers: { Authorization: `Bearer ${jwt}` } });
  adminPid = String(((await me.json())?.data?.user?.pid) ?? '');
  expect(adminPid).toBeTruthy();

  const account = await matrixApi(jwt, '/api/meta/commands/execute/crm:create_account', 'POST', {
    payload: { crm_acc_name: `${RUN_ID} 履约客户`, crm_acc_industry: 'tech' },
    operationType: 'create',
  });
  expect(account.ok, `account: ${JSON.stringify(account.body).slice(0, 200)}`).toBe(true);
  accountPid = account.recordId;

  const opportunity = await matrixApi(jwt, '/api/meta/commands/execute/crm:create_opportunity', 'POST', {
    payload: {
      crm_opp_name: `${RUN_ID} 联动商机`,
      crm_opp_account_id: accountPid,
      crm_opp_owner: adminPid,
    },
    operationType: 'create',
  });
  expect(opportunity.ok, `opportunity: ${JSON.stringify(opportunity.body).slice(0, 200)}`).toBe(true);
  opportunityPid = opportunity.recordId;

  const warehouse = await matrixApi(jwt, '/api/meta/commands/execute/inv:create_warehouse', 'POST', {
    payload: { inv_warehouse_name: `${RUN_ID} 仓库`, inv_warehouse_type: 'standard', inv_warehouse_address: 'e2e' },
    operationType: 'create',
  });
  expect(warehouse.ok, `warehouse: ${JSON.stringify(warehouse.body).slice(0, 200)}`).toBe(true);
  warehousePid = warehouse.recordId;

  const product = await matrixApi(jwt, '/api/meta/commands/execute/prod:create_product', 'POST', {
    payload: { prod_name: `${RUN_ID} 履约商品`, prod_unit: '台', prod_type: 'raw_material', prod_base_price: 100 },
    operationType: 'create',
  });
  expect(product.ok, `product: ${JSON.stringify(product.body).slice(0, 200)}`).toBe(true);
  productPid = product.recordId;

  const order = await matrixApi(jwt, '/api/meta/commands/execute/sl:create_sales_order', 'POST', {
    payload: {
      sl_so_account_id: accountPid,
      sl_so_date: '2026-09-10',
      sl_so_delivery_date: '2026-10-10',
      sl_so_source_opp_id: opportunityPid,
    },
    operationType: 'create',
  });
  expect(order.ok, `order: ${JSON.stringify(order.body).slice(0, 200)}`).toBe(true);
  orderPid = order.recordId;

  const line = await matrixApi(jwt, '/api/meta/commands/execute/sl:add_so_line', 'POST', {
    payload: { sl_sol_order_id: orderPid, sl_sol_product_id: productPid, sl_sol_qty: 10, sl_sol_price: 100 },
    operationType: 'create',
  });
  expect(line.ok, `line: ${JSON.stringify(line.body).slice(0, 200)}`).toBe(true);
  orderLinePid = line.recordId;
});

async function openOrderDetail(page: Page, jwt: string): Promise<void> {
  await injectCsrfToken(page, jwt);
  await page.goto(`${BASE_URL}/p/sl_sales_order_common/view/${orderPid}`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(2500);
}

test('PAR-19 order approval advances the open source opportunity to won', async ({ page }) => {
  test.setTimeout(300_000);
  const jwt = await loginJwt(ADMIN_EMAIL, ADMIN_PASSWORD);

  const before = await matrixApi(jwt, `/api/dynamic/crm_opportunity_common/${opportunityPid}`);
  const stageBefore = String(before.body?.data?.crm_opp_stage ?? '');
  expect(['discovery', 'qualification', 'proposal', 'negotiation']).toContain(stageBefore);

  await openOrderDetail(page, jwt);
  await shot(page, '01-order-draft-detail');

  // draft → pending (提交审核) then pending → approved (审核通过)
  for (const label of [/提交审核/, /审核通过/]) {
    await page.getByRole('button', { name: label }).first().click();
    await page.waitForTimeout(1200);
    const confirm = page.getByRole('button', { name: /确 定|确定|确认/ }).first();
    if (await confirm.isVisible().catch(() => false)) {
      await confirm.click();
    }
    await page.waitForTimeout(2500);
  }
  await shot(page, '02-order-approved');

  const after = await matrixApi(jwt, `/api/dynamic/crm_opportunity_common/${opportunityPid}`);
  expect(String(after.body?.data?.crm_opp_stage ?? ''), 'source opportunity advanced to closed_won')
    .toBe('closed_won');

  await page.goto(`${BASE_URL}/p/crm_opportunity_common/view/${opportunityPid}`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(2500);
  await shot(page, '03-opportunity-won-detail');
  const detail = JSON.stringify(await page.content());
  expect(detail.includes('已赢单') || detail.includes('closed_won'), 'opportunity UI shows won stage').toBe(true);
});

test('PAR-19 shipment confirm backfills shipped qty and fulfillment records render', async ({ page }) => {
  test.setTimeout(300_000);
  const jwt = await loginJwt(ADMIN_EMAIL, ADMIN_PASSWORD);

  const shipment = await matrixApi(jwt, '/api/meta/commands/execute/sl:create_shipment', 'POST', {
    payload: { sl_sh_order_id: orderPid, sl_sh_date: '2026-09-15', sl_sh_warehouse_id: warehousePid },
    operationType: 'create',
  });
  expect(shipment.ok, `shipment: ${JSON.stringify(shipment.body).slice(0, 200)}`).toBe(true);
  const shipmentPid = shipment.recordId;

  const shipLine = await matrixApi(jwt, '/api/meta/commands/execute/sl:add_ship_line', 'POST', {
    payload: {
      sl_shl_shipment_id: shipmentPid,
      sl_shl_product_id: productPid,
      sl_shl_qty: 4,
      sl_shl_so_line_id: orderLinePid,
    },
    operationType: 'create',
  });
  expect(shipLine.ok, `ship line: ${JSON.stringify(shipLine.body).slice(0, 200)}`).toBe(true);

  // confirm through the real UI toolbar entry
  await injectCsrfToken(page, jwt);
  await page.goto(`${BASE_URL}/p/sl_shipment_common/view/${shipmentPid}`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(2500);
  await shot(page, '04-shipment-draft');
  await page.getByRole('button', { name: /确认发货/ }).first().click();
  await page.waitForTimeout(1200);
  const confirm = page.getByRole('button', { name: /确 定|确定|确认/ }).first();
  if (await confirm.isVisible().catch(() => false)) {
    await confirm.click();
  }
  await page.waitForTimeout(2500);
  await shot(page, '05-shipment-confirmed');

  const line = await matrixApi(jwt, `/api/dynamic/sl_sales_order_line_common/${orderLinePid}`);
  expect(Number(line.body?.data?.sl_sol_shipped_qty ?? 0), 'shipped qty backfilled to order line').toBe(4);

  // order detail renders the fulfillment records sub-table with the shipment
  await page.goto(`${BASE_URL}/p/sl_sales_order_common/view/${orderPid}`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(2500);
  await shot(page, '06-order-fulfillment-detail');
  const content = JSON.stringify(await page.content());
  expect(content.includes('发货记录'), 'fulfillment records section present').toBe(true);
  const listResp = await fetch(`${BACKEND_URL}/api/dynamic/sl_shipment_common/list?pageNum=1&pageSize=20`, {
    headers: { Authorization: `Bearer ${jwt}` },
  });
  const listBody: any = await (listResp.json().catch(() => ({})));
  const rows = listBody?.data?.records ?? [];
  const mine = rows.find((r: any) => r.pid === shipmentPid);
  expect(String(mine?.sl_sh_status ?? ''), 'shipment shows confirmed').toBe('confirmed');
});

test('PAR-19 over-shipment fails closed and keeps the shipment draft', async ({ page }) => {
  test.setTimeout(300_000);
  const jwt = await loginJwt(ADMIN_EMAIL, ADMIN_PASSWORD);

  const shipment = await matrixApi(jwt, '/api/meta/commands/execute/sl:create_shipment', 'POST', {
    payload: { sl_sh_order_id: orderPid, sl_sh_date: '2026-09-16', sl_sh_warehouse_id: warehousePid },
    operationType: 'create',
  });
  expect(shipment.ok, 'over-shipment created').toBe(true);
  const shipmentPid = shipment.recordId;
  // ordered 10, already shipped 4 → attempting 7 more exceeds the order line
  const shipLine = await matrixApi(jwt, '/api/meta/commands/execute/sl:add_ship_line', 'POST', {
    payload: {
      sl_shl_shipment_id: shipmentPid,
      sl_shl_product_id: productPid,
      sl_shl_qty: 7,
      sl_shl_so_line_id: orderLinePid,
    },
    operationType: 'create',
  });
  expect(shipLine.ok, 'over-shipment line').toBe(true);

  await injectCsrfToken(page, jwt);
  await page.goto(`${BASE_URL}/p/sl_shipment_common/view/${shipmentPid}`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(2500);
  await page.getByRole('button', { name: /确认发货/ }).first().click();
  await page.waitForTimeout(1500);
  const confirm = page.getByRole('button', { name: /确 定|确定|确认/ }).first();
  if (await confirm.isVisible().catch(() => false)) {
    await confirm.click();
  }
  await page.waitForTimeout(2000);
  await shot(page, '07-over-shipment-rejected');

  const after = await matrixApi(jwt, `/api/dynamic/sl_shipment_common/${shipmentPid}`);
  expect(String(after.body?.data?.sl_sh_status ?? ''), 'shipment stays draft after failed confirm').toBe('draft');

  const line = await matrixApi(jwt, `/api/dynamic/sl_sales_order_line_common/${orderLinePid}`);
  expect(Number(line.body?.data?.sl_sol_shipped_qty ?? 0), 'shipped qty unchanged after failed confirm').toBe(4);
});
