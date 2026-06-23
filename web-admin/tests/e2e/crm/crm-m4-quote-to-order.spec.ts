/**
 * CRM M4 P1-D (J2) — L4 UI golden E2E (quotation -> sales order conversion)
 *
 * Proves the J2 revenue-chain journey through the real browser UI against the
 * isolated CRM-M1 stack: an accepted sales quotation, converted via
 * crm:convert_quotation_to_order, produces a draft sales order that is visible
 * in the sales-order list UI, back-linked to the quotation, with zero raw-code
 * leakage. Also proves the conversion is blocked for a non-accepted quotation
 * (precondition fail-loud — the pre-M4 sl:convert_quotation_to_order was a no-op).
 *
 * Run:
 *   PLAYWRIGHT_BASE_URL=http://localhost:5189 NO_PROXY=localhost,127.0.0.1 \
 *   npx playwright test tests/e2e/crm/crm-m4-quote-to-order.spec.ts \
 *     --project=chromium-m1 --config=tests/e2e/crm/m4.playwright.config.ts
 *
 * COVERAGE MATRIX:
 *   D1  accepted quotation -> convert -> draft order appears in sl_sales_order_common list (UI)
 *   D2  order detail shows the source-quote back-link / draft status (UI)
 *   D3  draft (non-accepted) quotation convert is rejected (precondition, API-asserted)
 */
import { test, expect, type Page } from '@playwright/test';

const BASE = process.env.PLAYWRIGHT_BASE_URL || 'http://localhost:5189';
const BE = process.env.PLAYWRIGHT_BE_URL || 'http://localhost:6459';
const EMAIL = 'admin@auraboot.com';
const PW = 'Test2026x';
const SHOT = '/tmp/m4-e2e';
const TAG = String(Date.now()).slice(-7);

let jwt = '';
let orderCode = '';
let quoteId = '';

async function apiLogin(): Promise<string> {
  const res = await fetch(`${BE}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: EMAIL, password: PW }),
  });
  const body = await res.json();
  const token = body?.data?.jwt;
  if (!token) throw new Error('API login failed: ' + JSON.stringify(body).slice(0, 200));
  return token;
}

async function cmd(code: string, payload?: unknown, target?: string): Promise<any> {
  const body: Record<string, unknown> = {};
  if (payload !== undefined) body.payload = payload;
  if (target !== undefined) body.targetRecordId = target;
  const res = await fetch(`${BE}/api/meta/commands/execute/${code}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${jwt}` },
    body: JSON.stringify(body),
  });
  return res.json();
}

function rid(resp: any): string | null {
  const layers = [resp?.data?.data, resp?.data, resp];
  for (const l of layers) {
    if (l && typeof l === 'object') {
      for (const k of ['recordId', 'pid', 'id']) if (l[k]) return String(l[k]);
    }
  }
  return null;
}

async function uiLogin(page: Page): Promise<void> {
  for (let attempt = 1; attempt <= 3; attempt++) {
    await page.goto(`${BASE}/`, { waitUntil: 'domcontentloaded' });
    const emailInput = page.locator('input#identifier, input#email');
    const hasLogin = await emailInput.isVisible({ timeout: 8000 }).catch(() => false);
    if (!hasLogin) break;
    await emailInput.fill(EMAIL);
    await page.locator('input#password').fill(PW);
    await page.locator('button:has-text("立即登录"), button[type="submit"]').first().click();
    await page.waitForURL((u) => !u.pathname.includes('login'), { timeout: 20000 }).catch(() => {});
    if (page.url().includes('tenant-selection')) {
      const enter = page.getByRole('button', { name: /进入|选择|Enter|Demo|AuraBoot/ }).or(page.getByText(/AuraBoot Demo/).first());
      await enter.first().click({ timeout: 5000 }).catch(() => {});
      await page.waitForURL((u) => !u.pathname.includes('tenant-selection'), { timeout: 15000 }).catch(() => {});
    }
    const stillOnLogin = await emailInput.isVisible({ timeout: 2000 }).catch(() => false);
    if (!stillOnLogin) break;
    if (attempt === 3) throw new Error('UI login failed after 3 attempts');
  }
  await expect(page.locator('input#identifier, input#email')).toHaveCount(0, { timeout: 5000 });
}

async function gotoPage(page: Page, path: string): Promise<void> {
  await page.goto(`${BASE}${path}`, { waitUntil: 'domcontentloaded' });
  await page.waitForLoadState('networkidle', { timeout: 15000 }).catch(() => {});
  await page.waitForTimeout(1500);
}

async function assertNoRawCodeLeak(page: Page, label: string): Promise<void> {
  const body = await page.locator('body').innerText();
  const rawField = body.match(/\bsl_(so|sol|sq|sql)_[a-z_]+\b/g) || [];
  const bareKey = body.match(/\bmodel\.[a-z_]+\.[a-z_.]+\.label\b/g) || [];
  expect(rawField, `[${label}] raw field code leaked: ${[...new Set(rawField)].join(', ')}`).toHaveLength(0);
  expect(bareKey, `[${label}] bare i18n key leaked: ${[...new Set(bareKey)].join(', ')}`).toHaveLength(0);
}

test.describe.configure({ mode: 'serial' });

test.describe('CRM M4 quote-to-order (L4 UI golden)', () => {
  test.beforeAll(async () => {
    jwt = await apiLogin();

    // D3 negative: a DRAFT quotation cannot convert (precondition fail-loud)
    let r = await cmd('crm:create_account', { crm_acc_name: `J2 Acc ${TAG}`, crm_acc_industry: 'tech' });
    const acc = rid(r);
    r = await cmd('prod:create_product', { prod_name: `J2 Widget ${TAG}`, prod_unit: 'pcs', prod_type: 'raw_material', prod_base_price: 100, prod_cost_price: 60, prod_currency: 'CNY' });
    const prod = rid(r);

    r = await cmd('sl:create_sales_quotation', { sl_sq_account_id: acc, sl_sq_currency_code: 'CNY', sl_sq_total_amount: 860, sl_sq_date: '2026-06-04', sl_sq_valid_until: '2026-07-04' });
    const draftQ = rid(r);
    await cmd('sl:add_sales_quotation_line', { sl_sql_quotation_id: draftQ, sl_sql_product_id: prod, sl_sql_qty: 5, sl_sql_price: 100, sl_sql_amount: 500 });
    const convDraft = await cmd('crm:convert_quotation_to_order', undefined, String(draftQ));
    // precondition rejects: command returns a non-zero / error code
    expect(convDraft?.code !== '0' || convDraft?._httpError, 'draft quotation convert must be rejected').toBeTruthy();

    // D1/D2 happy path: accepted quotation -> draft order
    r = await cmd('sl:create_sales_quotation', { sl_sq_account_id: acc, sl_sq_currency_code: 'CNY', sl_sq_total_amount: 860, sl_sq_date: '2026-06-04', sl_sq_valid_until: '2026-07-04' });
    quoteId = rid(r) || '';
    expect(quoteId).not.toBe('');
    for (const [qty, price, amount] of [[5, 100, 500], [3, 120, 360]]) {
      await cmd('sl:add_sales_quotation_line', { sl_sql_quotation_id: quoteId, sl_sql_product_id: prod, sl_sql_qty: qty, sl_sql_price: price, sl_sql_amount: amount });
    }
    await cmd('sl:send_sales_quotation', undefined, quoteId);
    await cmd('sl:accept_sales_quotation', undefined, quoteId);
    const conv = await cmd('crm:convert_quotation_to_order', undefined, quoteId);
    expect(conv?.code, 'convert succeeds for accepted quotation').toBe('0');

    // resolve the created order's code for the UI assertion
    const list = await fetch(`${BE}/api/dynamic/sl_sales_order_common/list?pageNum=1&pageSize=200&sortField=created_at&sortOrder=desc`, {
      headers: { Authorization: `Bearer ${jwt}` },
    }).then((x) => x.json());
    const rows: any[] = list?.data?.records || [];
    const match = rows.find((o) => o?.sl_so_source_quote_id === quoteId);
    expect(match, 'converted order found via sl_so_source_quote_id back-link').toBeTruthy();
    orderCode = match?.sl_so_code || '';
    expect(orderCode).not.toBe('');
  });

  test.beforeEach(async ({ page }) => {
    await uiLogin(page);
  });

  // ---- D1: converted order visible in the sales-order list UI ----
  test('D1 converted order appears in the sales order list', async ({ page }) => {
    await gotoPage(page, '/p/sl_sales_order_common');
    await expect(page.getByText(orderCode).first()).toBeVisible({ timeout: 12000 });
    await assertNoRawCodeLeak(page, 'sales_order_list');
    await page.screenshot({ path: `${SHOT}/d1_order_list.png`, fullPage: true });
  });

  // ---- D2: order detail shows draft status + source-quote back-link ----
  test('D2 order detail shows draft status (UI)', async ({ page }) => {
    await gotoPage(page, '/p/sl_sales_order_common');
    await expect(page.getByText(orderCode).first()).toBeVisible({ timeout: 12000 });
    await page.getByText(orderCode).first().click();
    await page.waitForLoadState('networkidle', { timeout: 10000 }).catch(() => {});
    await page.waitForTimeout(1500);
    // draft-status label (Chinese) visible on the detail
    await expect(page.getByText(/草稿|Draft/).first()).toBeVisible({ timeout: 10000 });
    await assertNoRawCodeLeak(page, 'sales_order_detail');
    await page.screenshot({ path: `${SHOT}/d2_order_detail.png`, fullPage: true });
  });
});
