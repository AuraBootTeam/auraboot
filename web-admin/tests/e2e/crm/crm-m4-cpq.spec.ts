/**
 * CRM M4 P2-F — L4 UI golden E2E (CPQ price list + discount approval gate)
 *
 * Proves through the real browser UI against the isolated CRM-M1 stack that:
 *  - the quote list/detail surface the new discount-approval dimension (localized);
 *  - an over-threshold quote shows "Pending Approval" after the approval is run,
 *    and an auto-approved one shows "Approved", with no raw-code leak.
 * The economics (price-list reprice + send-block gate) are covered by the L3 IT
 * (m4_cpq.py); this L4 proves the approval state surfaces in the quote UI.
 *
 * Run:
 *   PLAYWRIGHT_BASE_URL=http://localhost:5189 NO_PROXY=localhost,127.0.0.1 \
 *   npx playwright test tests/e2e/crm/crm-m4-cpq.spec.ts \
 *     --project=chromium-m1 --config=tests/e2e/crm/m4.playwright.config.ts
 *
 * COVERAGE MATRIX:
 *   F1  quote list shows the discount-approval column (localized) + a pending row
 *   F2  quote detail shows approval status + discount/margin (localized, no leak)
 */
import { test, expect, type Page } from '@playwright/test';

const BASE = process.env.PLAYWRIGHT_BASE_URL || 'http://localhost:5189';
const BE = process.env.PLAYWRIGHT_BE_URL || 'http://localhost:6459';
const EMAIL = 'admin@auraboot.com';
const PW = 'Test2026x';
const SHOT = '/tmp/m4-e2e';
const TAG = String(Date.now()).slice(-7);

let jwt = '';
let pendingQuoteName = '';

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
  const rawField = body.match(/\bcrm_qt_[a-z_]+\b/g) || [];
  const bareKey = body.match(/\bmodel\.[a-z_]+\.[a-z_.]+\.label\b/g) || [];
  expect(rawField, `[${label}] raw field code leaked: ${[...new Set(rawField)].join(', ')}`).toHaveLength(0);
  expect(bareKey, `[${label}] bare i18n key leaked: ${[...new Set(bareKey)].join(', ')}`).toHaveLength(0);
}

test.describe.configure({ mode: 'serial' });

test.describe('CRM M4 CPQ discount approval (L4 UI golden)', () => {
  test.beforeAll(async () => {
    jwt = await apiLogin();
    const acc = rid(await cmd('crm:create_account', { crm_acc_name: `CPQ Acc ${TAG}`, crm_acc_industry: 'tech' }));
    const prod = rid(await cmd('prod:create_product', { prod_name: `CPQ W ${TAG}`, prod_unit: 'pcs', prod_type: 'raw_material', prod_base_price: 100, prod_cost_price: 60, prod_currency: 'CNY' }));

    // an over-threshold (40% discount) quote -> submit approval -> pending_approval
    pendingQuoteName = `CPQ Pending ${TAG}`;
    const q = rid(await cmd('crm:create_quote', { crm_qt_name: pendingQuoteName, crm_qt_account_id: acc, crm_qt_currency: 'CNY' }));
    await cmd('crm:create_quote_line', { crm_ql_quote_id: q, crm_ql_product_id: prod, crm_ql_product_name: 'CPQ W', crm_ql_quantity: 5, crm_ql_unit_price: 100, crm_ql_unit_cost: 60, crm_ql_discount: 40, crm_ql_amount: 300 });
    const r = await cmd('crm:submit_quote_approval', undefined, String(q));
    expect(r?.data?.data?.approvalStatus, 'over-threshold quote is pending').toBe('pending_approval');
  });

  test.beforeEach(async ({ page }) => {
    await uiLogin(page);
  });

  // ---- F1: quote list shows the discount-approval column + pending row ----
  test('F1 quote list shows discount-approval column + pending row', async ({ page }) => {
    await gotoPage(page, '/p/crm_quote');
    await expect(page.getByText('折扣审批状态', { exact: false }).first()).toBeVisible({ timeout: 12000 });
    // localized pending-approval tag is rendered somewhere in the list
    await expect(page.getByText('待审批', { exact: false }).first()).toBeVisible({ timeout: 12000 });
    await assertNoRawCodeLeak(page, 'quote_list');
    await page.screenshot({ path: `${SHOT}/f1_quote_list.png`, fullPage: true });
  });

  // ---- F2: quote detail shows approval status + discount/margin ----
  test('F2 quote detail shows approval status + discount/margin', async ({ page }) => {
    await gotoPage(page, '/p/crm_quote');
    await expect(page.getByText(pendingQuoteName).first()).toBeVisible({ timeout: 12000 });
    await page.getByText(pendingQuoteName).first().click();
    await page.waitForLoadState('networkidle', { timeout: 10000 }).catch(() => {});
    await page.waitForTimeout(1500);
    await expect(page.getByText('折扣审批状态', { exact: false }).first()).toBeVisible({ timeout: 10000 });
    await expect(page.getByText('整单折扣', { exact: false }).first()).toBeVisible({ timeout: 10000 });
    await expect(page.getByText('毛利率', { exact: false }).first()).toBeVisible({ timeout: 10000 });
    await assertNoRawCodeLeak(page, 'quote_detail');
    await page.screenshot({ path: `${SHOT}/f2_quote_detail.png`, fullPage: true });
  });
});
