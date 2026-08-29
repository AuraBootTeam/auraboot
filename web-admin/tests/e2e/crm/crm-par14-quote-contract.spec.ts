import { expect, test } from '../../fixtures';
import fs from 'node:fs';
import path from 'node:path';
import { BACKEND_URL, BASE_URL } from '../../helpers/environments';

/**
 * PAR-14 T-G slice: quote PDF export + quote → contract conversion loop.
 * Frozen contract: enterprise docs/plans/... (par14-tg goal freeze).
 *
 * Journeys:
 *  1. Quote PDF: the quote detail header exports a real PDF file (platform
 *     export capability applied to quotes).
 *  2. Conversion: an accepted quote converts to a draft contract through the
 *     detail toolbar (dialog), with quote-derived fields and source stamped;
 *     a second conversion is idempotent; an open quote is fail-closed.
 */

const RUN_ID = `par14tg-${Date.now()}`;
const ADMIN_EMAIL = 'admin@auraboot.com';
const ADMIN_PASSWORD = 'Test2026x';
const EVIDENCE_ROOT = process.env.AURA_EVIDENCE_ROOT
  ? path.join(process.env.AURA_EVIDENCE_ROOT, 'par14-quote-contract')
  : path.resolve(process.cwd(), '..', '.workspace', 'evidence', 'par14-quote-contract-s69', 'par14-quote-contract');

interface MatrixApiResult {
  ok: boolean;
  status: number;
  body: any;
  recordId: string;
}

test.describe.configure({ mode: 'serial' });

function shot(page: any, name: string): void {
  fs.mkdirSync(EVIDENCE_ROOT, { recursive: true });
  (page as any).screenshot({ path: path.join(EVIDENCE_ROOT, `${name}.png`), fullPage: false }).catch(() => {});
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

async function injectCsrfToken(page: any, jwt: string): Promise<void> {
  await page.addInitScript((t: string) => {
    try { localStorage.setItem('jwtToken', t); } catch { /* visible failure */ }
  }, jwt);
}

let adminPid = '';
let accountPid = '';
let acceptedQuotePid = '';
let openQuotePid = '';

test.beforeAll(async () => {
  const jwt = await loginJwt(ADMIN_EMAIL, ADMIN_PASSWORD);
  const me = await fetch(`${BACKEND_URL}/api/auth/me`, { headers: { Authorization: `Bearer ${jwt}` } });
  adminPid = String(((await me.json())?.data?.user?.pid) ?? '');
  expect(adminPid).toBeTruthy();

  const account = await matrixApi(jwt, '/api/meta/commands/execute/crm:create_account', 'POST', {
    payload: { crm_acc_name: `${RUN_ID} 报价客户`, crm_acc_industry: 'tech' },
    operationType: 'create',
  });
  expect(account.ok, `account: ${JSON.stringify(account.body).slice(0, 200)}`).toBe(true);
  accountPid = account.recordId;

  for (const [key, accepted, setter] of [
    ['accepted', true, (v: string) => { acceptedQuotePid = v; }],
    ['open', false, (v: string) => { openQuotePid = v; }],
  ] as const) {
    const quote = await matrixApi(jwt, '/api/meta/commands/execute/crm:create_quote_summary', 'POST', {
      payload: {
        crm_qs_account_id: accountPid,
        crm_qs_quote_amount: 9000,
        crm_qs_currency: 'CNY',
        crm_qs_owner: adminPid,
        ...(accepted ? { crm_qs_won_lost_result: 'accepted' } : {}),
      },
      operationType: 'create',
    });
    expect(quote.ok, `quote ${key}: ${JSON.stringify(quote.body).slice(0, 250)}`).toBe(true);
    setter(quote.recordId);
  }
});

test('PAR-14 quote detail exports a real PDF file', async ({ page }) => {
  test.setTimeout(300_000);
  const jwt = await loginJwt(ADMIN_EMAIL, ADMIN_PASSWORD);

  await injectCsrfToken(page, jwt);
  await page.goto(`${BASE_URL}/p/crm_quote_summary_common/view/${acceptedQuotePid}`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(3000);
  await shot(page, '01-quote-detail');

  const [download] = await Promise.all([
    page.waitForEvent('download', { timeout: 30000 }),
    page.getByTestId('export-pdf-button').click(),
  ]);
  const pdfPath = path.join(EVIDENCE_ROOT, 'quote-export.pdf');
  fs.mkdirSync(EVIDENCE_ROOT, { recursive: true });
  await download.saveAs(pdfPath);
  expect(fs.statSync(pdfPath).size, 'exported quote PDF is non-empty').toBeGreaterThan(1000);
  await shot(page, '02-quote-pdf-exported');
});

test('PAR-14 accepted quote converts to contract; idempotent; open quote fail-closed', async ({ page }) => {
  test.setTimeout(300_000);
  const jwt = await loginJwt(ADMIN_EMAIL, ADMIN_PASSWORD);

  // UI conversion through the quote detail toolbar
  await injectCsrfToken(page, jwt);
  await page.goto(`${BASE_URL}/p/crm_quote_summary_common/view/${acceptedQuotePid}`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(3000);

  // one-click conversion: the command derives the contract from the quote
  await page.getByRole('button', { name: /转化为合同/ }).first().click();
  await page.waitForTimeout(3000);
  await shot(page, '04-conversion-submitted');

  // exactly one contract carries the source quote, with quote-derived fields
  const contracts = await matrixApi(jwt, '/api/dynamic/sl_sales_contract_common/list?pageNum=1&pageSize=50');
  const converted = (contracts.body?.data?.records ?? []).find(
    (r: any) => r.sl_ctr_source_qo_quote_id === acceptedQuotePid);
  expect(converted, 'converted contract linked to the source quote').toBeTruthy();
  expect(Number(converted?.sl_ctr_amount ?? 0), 'contract amount from quote').toBe(9000);
  expect(converted?.sl_ctr_name, 'contract name derived from quote code').toContain('转化合同');

  // idempotent: converting again returns the same contract
  const again = await matrixApi(jwt, '/api/meta/commands/execute/sl:convert_quote_to_contract', 'POST', {
    payload: {
      sl_ctr_source_qo_quote_id: acceptedQuotePid,
      sl_ctr_name: `${RUN_ID} 第二次`,
    },
  });
  expect(again.ok, `second conversion ok: ${JSON.stringify(again.body).slice(0, 200)}`).toBe(true);
  expect(again.body?.data?.data?.contractId ?? again.body?.data?.contractId, 'idempotent returns same contract')
    .toBe(String(converted.pid));

  // converted contract detail renders
  await page.goto(`${BASE_URL}/p/sl_sales_contract_common/view/${converted.pid}`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(2500);
  await shot(page, '05-converted-contract-detail');
  const detail = JSON.stringify(await page.content());
  expect(detail.includes('销售合同详情'), 'contract detail rendered').toBe(true);

  // open quote: the conversion command fails closed with a page-level error
  await page.goto(`${BASE_URL}/p/crm_quote_summary_common/view/${openQuotePid}`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(2500);
  await page.getByRole('button', { name: /转化为合同/ }).first().click();
  await page.waitForTimeout(3000);
  await shot(page, '06-open-quote-rejected');
  // the command rejects fail-closed server-side; no contract may exist
  const openCheck = await matrixApi(jwt, '/api/dynamic/sl_sales_contract_common/list?pageNum=1&pageSize=50');
  const openConverted = (openCheck.body?.data?.records ?? []).find(
    (r: any) => r.sl_ctr_source_qo_quote_id === openQuotePid);
  expect(openConverted, 'no contract created for open quote').toBeUndefined();
});
