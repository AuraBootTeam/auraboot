import { expect, test } from '../../fixtures';
import fs from 'node:fs';
import path from 'node:path';
import { BACKEND_URL, BASE_URL } from '../../helpers/environments';

/**
 * PAR-18 T-F slice: collection statistics on the sales dashboard.
 * Frozen contract: enterprise docs/plans/... (par18-collection-dashboard goal freeze).
 *
 * Journeys:
 *  - Real command seed: account → active contract (auto fulfillment order) →
 *    collection 777 confirmed (base amount auto-backfilled) + a cancelled
 *    collection that must NOT count.
 *  - Dashboard: 已收款金额 KPI equals the DB aggregate; the new monthly
 *    collection trend and status distribution charts render with the seeded
 *    data.
 */

const RUN_ID = `par18tf-${Date.now()}`;
const ADMIN_EMAIL = 'admin@auraboot.com';
const ADMIN_PASSWORD = 'Test2026x';
const EVIDENCE_ROOT = process.env.AURA_EVIDENCE_ROOT
  ? path.join(process.env.AURA_EVIDENCE_ROOT, 'par18-collection-dashboard')
  : path.resolve(process.cwd(), '..', '.workspace', 'evidence', 'par18-collection-dashboard-s68', 'par18-collection-dashboard');

interface MatrixApiResult {
  ok: boolean;
  status: number;
  body: any;
  recordId: string;
}

test.describe.configure({ mode: 'serial' });

function shot(page: any, name: string): void {
  fs.mkdirSync(EVIDENCE_ROOT, { recursive: true });
  (page as any).screenshot({ path: path.join(EVIDENCE_ROOT, `${name}.png`), fullPage: true }).catch(() => {});
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

async function namedQuery(jwt: string, code: string, extra: Record<string, string> = {}): Promise<any> {
  const qs = new URLSearchParams({ datasourceId: `nq:${code}`, format: 'records', ...extra });
  const resp = await fetch(`${BACKEND_URL}/api/datasource/list?${qs.toString()}`, {
    headers: { Authorization: `Bearer ${jwt}` },
  });
  const body: any = await resp.json().catch(() => null);
  const data = body?.data;
  return data?.records ?? data;
}

let jwt = '';
let accountPid = '';
let contractPid = '';

test.beforeAll(async () => {
  jwt = await loginJwt(ADMIN_EMAIL, ADMIN_PASSWORD);

  const account = await matrixApi(jwt, '/api/meta/commands/execute/crm:create_account', 'POST', {
    payload: { crm_acc_name: `${RUN_ID} 回款客户`, crm_acc_industry: 'tech' },
    operationType: 'create',
  });
  expect(account.ok, `account: ${JSON.stringify(account.body).slice(0, 200)}`).toBe(true);
  accountPid = account.recordId;

  const contract = await matrixApi(jwt, '/api/meta/commands/execute/sl:create_sales_contract', 'POST', {
    payload: {
      sl_ctr_name: `${RUN_ID} 回款合同`,
      sl_ctr_account_id: accountPid,
      sl_ctr_start_date: '2026-09-01',
      sl_ctr_end_date: '2027-08-31',
      sl_ctr_amount: 2000,
      sl_ctr_currency_code: 'CNY',
      sl_ctr_owner: (await (await fetch(`${BACKEND_URL}/api/auth/me`, { headers: { Authorization: `Bearer ${jwt}` } })).json()).data.user.pid,
    },
    operationType: 'create',
  });
  expect(contract.ok, `contract: ${JSON.stringify(contract.body).slice(0, 200)}`).toBe(true);
  contractPid = contract.recordId;

  for (const code of ['sl:submit_sales_contract', 'sl:approve_sales_contract']) {
    const step = await matrixApi(jwt, `/api/meta/commands/execute/${code}`, 'POST', {
      payload: {}, targetRecordPid: contractPid, operationType: 'update',
    });
    expect(step.ok, `${code}: ${JSON.stringify(step.body).slice(0, 200)}`).toBe(true);
  }
});

test('PAR-18 confirmed collection backfills base amount and KPI aggregates it', async () => {
  test.setTimeout(300_000);

  const contract = await matrixApi(jwt, `/api/dynamic/sl_sales_contract_common/${contractPid}`);
  const fulfillmentOrderId = String(contract.body?.data?.sl_ctr_order_id ?? '');
  expect(fulfillmentOrderId, 'fulfillment order linked').toBeTruthy();

  // confirmed 777
  const collection = await matrixApi(jwt, '/api/meta/commands/execute/sl:create_sales_collection', 'POST', {
    payload: {
      sl_col_contract_id: contractPid,
      sl_col_order_id: fulfillmentOrderId,
      sl_col_date: '2026-09-21',
      sl_col_amount: 777,
      sl_col_method: 'bank_transfer',
    },
    operationType: 'create',
  });
  expect(collection.ok, `collection: ${JSON.stringify(collection.body).slice(0, 200)}`).toBe(true);
  const confirm = await matrixApi(jwt, '/api/meta/commands/execute/sl:confirm_sales_collection', 'POST', {
    payload: {}, targetRecordPid: collection.recordId, operationType: 'update',
  });
  expect(confirm.ok, `confirm: ${JSON.stringify(confirm.body).slice(0, 200)}`).toBe(true);

  // base amount auto-backfilled by the command
  const row = await matrixApi(jwt, `/api/dynamic/sl_sales_collection_common/${collection.recordId}`);
  expect(Number(row.body?.data?.sl_col_amount_base ?? 0), 'base amount backfilled from amount').toBe(777);

  // cancelled 500 — must NOT count into the KPI
  const cancelled = await matrixApi(jwt, '/api/meta/commands/execute/sl:create_sales_collection', 'POST', {
    payload: {
      sl_col_contract_id: contractPid,
      sl_col_order_id: fulfillmentOrderId,
      sl_col_date: '2026-09-22',
      sl_col_amount: 500,
      sl_col_method: 'bank_transfer',
    },
    operationType: 'create',
  });
  expect(cancelled.ok, 'second collection created').toBe(true);
  const cancel = await matrixApi(jwt, '/api/meta/commands/execute/sl:cancel_sales_collection', 'POST', {
    payload: {}, targetRecordPid: cancelled.recordId, operationType: 'update',
  });
  expect(cancel.ok, `cancel: ${JSON.stringify(cancel.body).slice(0, 200)}`).toBe(true);

  // this contract's own confirmed contribution is exactly 777
  const own = await matrixApi(jwt, `/api/dynamic/sl_sales_collection_common/${collection.recordId}`);
  expect(Number(own.body?.data?.sl_col_amount ?? 0)).toBe(777);

  // KPI named query aggregates confirmed collections only; expected value is
  // computed from the collection list so reruns on a reused DB stay correct
  const cols = await matrixApi(jwt, '/api/dynamic/sl_sales_collection_common/list?pageNum=1&pageSize=100');
  const expectedSum = (cols.body?.data?.records ?? [])
    .filter((r: any) => r.sl_col_status === 'confirmed')
    .reduce((acc: number, r: any) => acc + Number(r.sl_col_amount ?? 0), 0);
  expect(expectedSum).toBeGreaterThan(0);

  const kpi = await namedQuery(jwt, 'sales_dashboard_kpi', { tenantId: '' });
  const kpiRow = Array.isArray(kpi) ? kpi[0] : kpi?.records?.[0] ?? kpi?.[0];
  const amount = Number(kpiRow?.collection_amount ?? -1);
  expect(amount, `KPI collection_amount aggregates confirmed only (got ${JSON.stringify(kpiRow).slice(0, 200)})`).toBe(expectedSum);
});

test('PAR-18 dashboard renders collection trend, status chart and KPI', async ({ page }) => {
  test.setTimeout(300_000);
  const token = await loginJwt(ADMIN_EMAIL, ADMIN_PASSWORD);
  await page.addInitScript((t) => {
    try { localStorage.setItem('jwtToken', t); } catch { /* auth fails visibly */ }
  }, token);

  await page.goto(`${BASE_URL}/dashboards/view/sales_dashboard`, { waitUntil: 'domcontentloaded' });
  await page.getByText('已收款金额').first().waitFor({ state: 'visible', timeout: 20000 });
  const gridItems = page.locator('.layout > div');
  await gridItems.last().waitFor({ state: 'attached', timeout: 20000 });
  await page.waitForTimeout(2000);
  await shot(page, '01-sales-dashboard');

  // collection statistics widgets render as grid cells bound to echarts
  const gridCount = await gridItems.count();
  expect(gridCount, 'all dashboard widgets render (9 original + 2 collection)').toBe(11);
  const trendNode = page.locator('[data-widget-id="chart_collection_monthly_trend"] .echarts-for-react');
  await expect(trendNode.first()).toBeAttached({ timeout: 15000 });
  const statusNode = page.locator('[data-widget-id="chart_collection_status"] .echarts-for-react');
  await expect(statusNode.first()).toBeAttached({ timeout: 15000 });

  // KPI card renders the collected amount label
  const content = JSON.stringify(await page.content());
  expect(content, 'KPI renders collected amount').toMatch(/已收款金额/);

  // named queries behind the new charts return the seeded aggregates
  const trend = await namedQuery(jwt, 'sales_collection_monthly_trend', { tenantId: '' });
  const trendRows = Array.isArray(trend) ? trend : trend?.records ?? [];
  // the current month's data point equals the KPI aggregate (all confirmed)
  const kpi = await namedQuery(jwt, 'sales_dashboard_kpi', { tenantId: '' });
  const kpiRow = Array.isArray(kpi) ? kpi[0] : kpi?.records?.[0] ?? kpi?.[0];
  const monthRow = trendRows.find((r: any) => Number(r.total_amount) === Number(kpiRow?.collection_amount));
  expect(monthRow, 'trend has current data point matching KPI').toBeTruthy();

  const status = await namedQuery(jwt, 'sales_collection_status', { tenantId: '' });
  const statusRows = Array.isArray(status) ? status : status?.records ?? [];
  const confirmedRow = statusRows.find((r: any) => r.status === 'confirmed');
  expect(Number(confirmedRow?.count ?? 0), 'status distribution counts confirmed').toBeGreaterThanOrEqual(1);
  const cancelledRow = statusRows.find((r: any) => r.status === 'cancelled');
  expect(Number(cancelledRow?.count ?? 0), 'cancelled collection present in distribution').toBeGreaterThanOrEqual(1);
});
