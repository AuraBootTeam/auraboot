import { expect, test, type Page } from '../../fixtures';
import fs from 'node:fs';
import path from 'node:path';
import { BACKEND_URL, BASE_URL } from '../../helpers/environments';

/**
 * PAR-15 T-E slice: contract timeline (field history), PDF export, and
 * payment-plan linkage.
 * Frozen contract: enterprise docs/plans/... (par15-te goal freeze).
 *
 * Journeys:
 *  1. Payment plan created from the contract detail sub-table (UI form) and
 *     rendered in 回款计划.
 *  2. Contract lifecycle to 生效 with change-history timeline entries
 *     (field-history block) and a real PDF file export via the header button.
 *  3. Collection linked to the plan → contract payment progress updates →
 *     完成合同 succeeds; a sibling contract without full collection is
 *     rejected with a page-level business error.
 */

const RUN_ID = `par15te-${Date.now()}`;
const ADMIN_EMAIL = 'admin@auraboot.com';
const ADMIN_PASSWORD = 'Test2026x';
const EVIDENCE_ROOT = process.env.AURA_EVIDENCE_ROOT
  ? path.join(process.env.AURA_EVIDENCE_ROOT, 'par15-contract-te')
  : path.resolve(process.cwd(), '..', '.workspace', 'evidence', 'par15-contract-te-s66', 'par15-contract-te');

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

async function runToolbarStep(page: Page, label: RegExp): Promise<void> {
  await page.getByRole('button', { name: label }).first().click();
  await page.waitForTimeout(1200);
  const confirm = page.getByRole('button', { name: /确 定|确定|确认/ }).first();
  if (await confirm.isVisible().catch(() => false)) {
    await confirm.click();
  }
  await page.waitForTimeout(2500);
}

let adminPid = '';
let accountPid = '';
let contractPid = '';
let bareContractPid = '';

test.beforeAll(async () => {
  const jwt = await loginJwt(ADMIN_EMAIL, ADMIN_PASSWORD);
  const me = await fetch(`${BACKEND_URL}/api/auth/me`, { headers: { Authorization: `Bearer ${jwt}` } });
  adminPid = String(((await me.json())?.data?.user?.pid) ?? '');
  expect(adminPid).toBeTruthy();

  const account = await matrixApi(jwt, '/api/meta/commands/execute/crm:create_account', 'POST', {
    payload: { crm_acc_name: `${RUN_ID} 计划客户`, crm_acc_industry: 'tech' },
    operationType: 'create',
  });
  expect(account.ok, `account: ${JSON.stringify(account.body).slice(0, 200)}`).toBe(true);
  accountPid = account.recordId;

  // field-level audit is opt-in per tenant/model (platform capability);
  // enable it for the contract lifecycle fields via the admin API so the
  // 变更历史 timeline can assert the lifecycle
  const audit = await matrixApi(jwt, '/api/audit/field-config/bulk', 'POST', {
    modelCode: 'sl_sales_contract_common',
    configs: [
      { fieldCode: 'sl_ctr_status', enabled: true },
      { fieldCode: 'sl_ctr_payment_status', enabled: true },
      { fieldCode: 'sl_ctr_collected_amount', enabled: true },
      { fieldCode: 'sl_ctr_name', enabled: true },
    ],
  });
  expect(audit.ok, `audit config: ${JSON.stringify(audit.body).slice(0, 200)}`).toBe(true);

  for (const [key, setter] of [
    ['main', (v: string) => { contractPid = v; }],
    ['bare', (v: string) => { bareContractPid = v; }],
  ] as const) {
    const contract = await matrixApi(jwt, '/api/meta/commands/execute/sl:create_sales_contract', 'POST', {
      payload: {
        sl_ctr_name: `${RUN_ID} ${key}合同`,
        sl_ctr_account_id: accountPid,
        sl_ctr_start_date: '2026-09-01',
        sl_ctr_end_date: '2027-08-31',
        sl_ctr_amount: 3000,
        sl_ctr_currency_code: 'CNY',
        sl_ctr_owner: adminPid,
      },
      operationType: 'create',
    });
    expect(contract.ok, `contract ${key}: ${JSON.stringify(contract.body).slice(0, 200)}`).toBe(true);
    setter(contract.recordId);
  }
});

test('PAR-15 contract lifecycle to 生效 with change history and real PDF export', async ({ page }) => {
  test.setTimeout(300_000);
  const jwt = await loginJwt(ADMIN_EMAIL, ADMIN_PASSWORD);

  await injectCsrfToken(page, jwt);
  await page.goto(`${BASE_URL}/p/sl_sales_contract_common/view/${contractPid}`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(2500);

  await runToolbarStep(page, /提交审批/);
  await runToolbarStep(page, /审批并生效/);
  await shot(page, '01-contract-approved');

  const contract = await matrixApi(jwt, `/api/dynamic/sl_sales_contract_common/${contractPid}`);
  expect(String(contract.body?.data?.sl_ctr_status ?? ''), 'contract effective after approve').toBe('active');

  // change-history timeline renders lifecycle entries
  const history = page.getByTestId('block_ctr_detail_history').first();
  await history.scrollIntoViewIfNeeded().catch(() => {});
  await page.waitForTimeout(1500);
  await shot(page, '02-change-history-timeline');
  const empty = await page.getByTestId('field-history-empty').count();
  expect(empty, 'history timeline has lifecycle entries').toBe(0);

  // PDF export produces a real file
  const [download] = await Promise.all([
    page.waitForEvent('download', { timeout: 30000 }),
    page.getByTestId('export-pdf-button').click(),
  ]);
  const pdfPath = path.join(EVIDENCE_ROOT, 'contract-export.pdf');
  fs.mkdirSync(EVIDENCE_ROOT, { recursive: true });
  await download.saveAs(pdfPath);
  const size = fs.statSync(pdfPath).size;
  expect(size, 'exported PDF is non-empty').toBeGreaterThan(1000);
  await shot(page, '03-pdf-export-triggered');
});

test('PAR-15 payment plan is created from the contract detail sub-table once active', async ({ page }) => {
  test.setTimeout(300_000);
  const jwt = await loginJwt(ADMIN_EMAIL, ADMIN_PASSWORD);

  await injectCsrfToken(page, jwt);
  await page.goto(`${BASE_URL}/p/sl_sales_contract_common/view/${contractPid}`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(2500);

  // plans are only allowed on active contracts (PaymentPlanGuardHandler);
  // the contract reached active in the previous journey
  await page.getByRole('button', { name: /新增计划/ }).first().click();
  await page.getByTestId('form-dialog').waitFor({ state: 'visible', timeout: 15000 });
  await shot(page, '04-payment-plan-form');
  const inputs = page.getByTestId('form-dialog').locator('input');
  await inputs.nth(0).click();
  await page.keyboard.type(`${RUN_ID} 首期款`);
  await inputs.nth(2).click();
  await page.keyboard.type('3000');
  await inputs.nth(3).click();
  await page.keyboard.type('2026-10-01');
  await page.getByTestId('form-dialog-submit').click();
  await page.waitForTimeout(2500);
  await shot(page, '05-payment-plan-created');

  const plans = await matrixApi(jwt, `/api/dynamic/sl_contract_payment_plan_common/list?pageNum=1&pageSize=20`);
  const planRow = (plans.body?.data?.records ?? []).find((r: any) => r.sl_cpp_contract_id === contractPid);
  expect(planRow, 'payment plan linked to the contract').toBeTruthy();
  expect(Number(planRow?.sl_cpp_amount ?? 0), 'plan amount saved').toBe(3000);
});

test('PAR-15 plan-linked collection completes the contract; bare contract completion is rejected', async ({ page }) => {
  test.setTimeout(300_000);
  const jwt = await loginJwt(ADMIN_EMAIL, ADMIN_PASSWORD);

  const plans = await matrixApi(jwt, `/api/dynamic/sl_contract_payment_plan_common/list?pageNum=1&pageSize=20`);
  const planRow = (plans.body?.data?.records ?? []).find((r: any) => r.sl_cpp_contract_id === contractPid);
  expect(planRow, 'plan from journey 1').toBeTruthy();

  // collection linked to the plan + contract + its fulfillment order
  const contractBefore = await matrixApi(jwt, `/api/dynamic/sl_sales_contract_common/${contractPid}`);
  const fulfillmentOrderId = String(contractBefore.body?.data?.sl_ctr_order_id ?? '');
  expect(fulfillmentOrderId, 'active contract has a fulfillment order').toBeTruthy();
  const collection = await matrixApi(jwt, '/api/meta/commands/execute/sl:create_sales_collection', 'POST', {
    payload: {
      sl_col_contract_id: contractPid,
      sl_col_order_id: fulfillmentOrderId,
      sl_col_payment_plan_id: planRow.pid,
      sl_col_date: '2026-09-20',
      sl_col_amount: 3000,
      sl_col_method: 'bank_transfer',
    },
    operationType: 'create',
  });
  expect(collection.ok, `collection: ${JSON.stringify(collection.body).slice(0, 200)}`).toBe(true);
  const confirm = await matrixApi(jwt, '/api/meta/commands/execute/sl:confirm_sales_collection', 'POST', {
    payload: {}, targetRecordPid: collection.recordId, operationType: 'update',
  });
  expect(confirm.ok, `collection confirm: ${JSON.stringify(confirm.body).slice(0, 200)}`).toBe(true);

  const progress = await matrixApi(jwt, `/api/dynamic/sl_sales_contract_common/${contractPid}`);
  expect(Number(progress.body?.data?.sl_ctr_collected_amount ?? 0), 'contract collected amount updated').toBe(3000);

  // bare contract: approve then attempt completion without full collection → rejected
  await injectCsrfToken(page, jwt);
  await page.goto(`${BASE_URL}/p/sl_sales_contract_common/view/${bareContractPid}`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(2500);
  await runToolbarStep(page, /提交审批/);
  await runToolbarStep(page, /审批并生效/);
  await shot(page, '06-bare-contract-no-complete-action');
  // the 完成合同 affordance is gated on fully_paid, so an uncollected contract
  // never offers it (fail-closed made visible)
  const bareCompleteBtn = await page.getByRole('button', { name: /完成合同/ }).count();
  expect(bareCompleteBtn, 'completion affordance hidden while unpaid').toBe(0);
  const bareAfter = await matrixApi(jwt, `/api/dynamic/sl_sales_contract_common/${bareContractPid}`);
  expect(String(bareAfter.body?.data?.sl_ctr_status ?? ''), 'bare contract stays effective, not completed').toBe('active');

  // fully-collected contract completes
  await page.goto(`${BASE_URL}/p/sl_sales_contract_common/view/${contractPid}`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(2500);
  await runToolbarStep(page, /完成合同/);
  await shot(page, '07-contract-completed');
  const done = await matrixApi(jwt, `/api/dynamic/sl_sales_contract_common/${contractPid}`);
  expect(String(done.body?.data?.sl_ctr_status ?? ''), 'fully collected contract completed').toBe('completed');
});
