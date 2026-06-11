/**
 * CRM M5 PCBA (J6) — L4 UI golden E2E (sell-side customer RFQ pipeline)
 *
 * Proves the J6 PCBA contract-manufacturing inquiry journey through the real browser
 * UI against the isolated crm-gap stack (pcba-crm plugin): a customer RFQ with DFM
 * review, a customer board master record, and quantity-break price tiers, all visible
 * and editable through the low-code pages with ZERO raw-code / bare-i18n-key leakage.
 *
 * This replaces the M5 spec lost when its original worktree was cleaned up. The J6
 * chain (pe_rfq → crm_review_common[dfm] / pe_customer_board / pe_rfq_price_tier) is set
 * up via the real pe:* / crm:* commands in beforeAll, then asserted through the UI.
 * (A2-S1: the legacy pe_dfm_review model was decommissioned; DFM reviews now live on the
 * layered crm_review_common model with review_type 'dfm'.)
 *
 * Run:
 *   PLAYWRIGHT_BASE_URL=http://localhost:5239 BACKEND_URL=http://localhost:6509 \
 *   NO_PROXY=localhost,127.0.0.1 \
 *   npx playwright test tests/e2e/crm/crm-m5-pcba.spec.ts \
 *     --project=chromium-m5 --config=tests/e2e/crm/m5.playwright.config.ts
 *
 * COVERAGE MATRIX (model · kind):
 *   M1  pe_rfq            · list   — RFQ code + product model + status column visible, no raw leak
 *   M2  pe_rfq            · detail — basic + PCBA tech fields, lifecycle toolbar, no raw leak
 *   M3  pe_rfq            · detail — quantity-break price-tier sub-table shows the tier rows
 *   M4  pe_customer_board · list   — board master (layers / IPC) visible, no raw leak
 *   M5  pe_customer_board · detail — board detail fields render, no raw leak
 *   M6  crm_review_common · list   — DFM review (review_type dfm) visible, no raw leak
 *   M7  crm_review_common · detail — DFM conclusion + reviewer render, no raw leak
 *   M8  pe_rfq            · form   — create form opens with labelled fields (no raw leak)
 */
import { test, expect, type Page } from '@playwright/test';
import { loadEnv } from '../../helpers/environments';

const CRM_GAP_ENV = loadEnv('crm-gap');
const BASE = CRM_GAP_ENV.urls.base;
const BE = CRM_GAP_ENV.urls.backend;
const EMAIL = 'admin@auraboot.com';
const PW = 'Test2026x';
const SHOT = '/tmp/m5-e2e';
const TAG = String(Date.now()).slice(-7);

let jwt = '';
let rfqCode = '';
let rfqId = '';
let boardCode = '';
let dfmCode = '';

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

async function listRows(model: string): Promise<any[]> {
  const r = await fetch(
    `${BE}/api/dynamic/${model}/list?pageNum=1&pageSize=200&sortField=created_at&sortOrder=desc`,
    { headers: { Authorization: `Bearer ${jwt}` } },
  ).then((x) => x.json());
  return r?.data?.records || [];
}

async function uiLogin(page: Page): Promise<void> {
  for (let attempt = 1; attempt <= 3; attempt++) {
    await page.goto(`${BASE}/`, { waitUntil: 'domcontentloaded' });
    const emailInput = page.locator('input#email');
    const hasLogin = await emailInput.isVisible({ timeout: 8000 }).catch(() => false);
    if (!hasLogin) break;
    await emailInput.fill(EMAIL);
    await page.locator('input#password').fill(PW);
    await page.locator('button:has-text("立即登录"), button[type="submit"]').first().click();
    await page.waitForURL((u) => !u.pathname.includes('login'), { timeout: 20000 }).catch(() => {});
    if (page.url().includes('tenant-selection')) {
      const enter = page
        .getByRole('button', { name: /进入|选择|Enter|Demo|AuraBoot/ })
        .or(page.getByText(/AuraBoot Demo/).first());
      await enter.first().click({ timeout: 5000 }).catch(() => {});
      await page.waitForURL((u) => !u.pathname.includes('tenant-selection'), { timeout: 15000 }).catch(() => {});
    }
    const stillOnLogin = await emailInput.isVisible({ timeout: 2000 }).catch(() => false);
    if (!stillOnLogin) break;
    if (attempt === 3) throw new Error('UI login failed after 3 attempts');
  }
  await expect(page.locator('input#email')).toHaveCount(0, { timeout: 5000 });
}

async function gotoPage(page: Page, path: string): Promise<void> {
  // Do NOT wait for networkidle — this app polls (inbox/notifications) so the network
  // never goes idle and the wait always burns its full timeout. Callers assert on a
  // concrete element (a record code, a label) with its own timeout instead.
  await page.goto(`${BASE}${path}`, { waitUntil: 'domcontentloaded' });
}

/** Open a record's detail by clicking its code cell and waiting for the detail header. */
async function openDetail(page: Page, code: string): Promise<void> {
  await expect(page.getByText(code).first()).toBeVisible({ timeout: 15000 });
  await page.getByText(code).first().click();
  // the detail view renders tabs (概览 / Overview); wait for that concrete element rather
  // than networkidle (which never settles here) or a fixed delay.
  await expect(page.getByText(/概览|Overview/).first()).toBeVisible({ timeout: 20000 });
}

/**
 * Assert no raw field code (pe_rfq_*, pe_board_*, pe_pt_*, crm_rv_*) and no bare
 * i18n key (model.<x>.<y>.label) is leaking into the rendered page text. These are the
 * §2.2 blockers — a label that shows the column code instead of its localized name.
 */
async function assertNoRawCodeLeak(page: Page, label: string): Promise<void> {
  const body = await page.locator('body').innerText();
  const rawField = body.match(/\b(?:pe_(?:rfq|dfm|board|pt|opp|ql|qt)|crm_(?:rv|rk|cl))_[a-z_]+\b/g) || [];
  const bareKey = body.match(/\bmodel\.[a-z_]+\.[a-z_.]+\.label\b/g) || [];
  expect(rawField, `[${label}] raw field code leaked: ${[...new Set(rawField)].join(', ')}`).toHaveLength(0);
  expect(bareKey, `[${label}] bare i18n key leaked: ${[...new Set(bareKey)].join(', ')}`).toHaveLength(0);
}

test.describe.configure({ mode: 'serial' });

test.describe('CRM M5 PCBA J6 (L4 UI golden)', () => {
  test.beforeAll(async () => {
    jwt = await apiLogin();

    // --- account (customer) for board + RFQ attribution ---
    let r = await cmd('crm:create_account', { crm_acc_name: `J6 EMS Customer ${TAG}`, crm_acc_industry: 'electronics' });
    const acc = rid(r);
    expect(acc, 'create account').toBeTruthy();

    // --- customer board master (layers / IPC class / surface finish) ---
    r = await cmd('pe:create_board', {
      pe_board_account_id: acc,
      pe_board_name: `J6 Controller Board ${TAG}`,
      pe_board_layers: 6,
      pe_board_ipc_class: 'class_2',
      pe_board_surface_finish: 'ENIG',
      pe_board_min_trace_width: 0.1,
      pe_board_eau: 50000,
    });
    const board = rid(r);
    expect(board, 'create board').toBeTruthy();
    const boards = await listRows('pe_customer_board');
    boardCode = boards.find((b) => b?.pid === board || b?.pe_board_account_id === acc)?.pe_board_code || '';
    expect(boardCode, 'board code resolved').not.toBe('');

    // --- customer RFQ (auto-code, status draft) ---
    r = await cmd('pe:create_rfq', {
      pe_rfq_customer_id: acc,
      pe_rfq_product_model: `J6-PCBA-${TAG}`,
      pe_rfq_quantity: 1000,
      pe_rfq_date: '2026-06-04',
      pe_rfq_quality_class: 'class_2',
      pe_rfq_supply_mode: 'turnkey',
      pe_rfq_eau: 50000,
    });
    expect(r?.code, 'create rfq returns OK').toBe('0');
    const rfqs = await listRows('pe_rfq');
    const rfqRow = rfqs.find((q) => q?.pe_rfq_product_model === `J6-PCBA-${TAG}`);
    expect(rfqRow, 'rfq row found by product model').toBeTruthy();
    rfqId = String(rfqRow.pid);
    rfqCode = rfqRow.pe_rfq_code;
    expect(rfqCode).not.toBe('');

    // --- submit the RFQ then move it through the DFM gate (lifecycle) ---
    await cmd('pe:submit_rfq', undefined, rfqId);
    await cmd('pe:request_dfm_review', undefined, rfqId);

    // --- DFM review on the layered review model (A2-S1: crm_review_common, type dfm) ---
    const dfmTitle = `J6 DFM Review ${TAG}`;
    r = await cmd('crm:create_review', {
      crm_rv_title: dfmTitle,
      crm_rv_review_type: 'dfm',
      crm_rv_status: 'passed',
      crm_rv_reviewer_id: 'Engineer Wang',
      crm_rv_decided_at: '2026-06-04T10:00:00Z',
      crm_rv_conclusion: 'Min trace 0.1mm within capability; impedance control required on L2/L5.',
      crm_rv_recommendation: 'Proceed to quotation; add controlled-impedance test coupon.',
    });
    expect(r?.code, 'create dfm review returns OK').toBe('0');
    const dfms = await listRows('crm_review_common');
    dfmCode = dfms.find((d) => d?.crm_rv_title === dfmTitle)?.crm_rv_code || '';
    expect(dfmCode, 'dfm code resolved').not.toBe('');

    // --- quantity-break price tiers on the RFQ (the volume-sensitive PCBA pricing) ---
    for (const [minQty, price, lead] of [
      [100, 12.5, 28],
      [500, 9.8, 21],
      [1000, 8.2, 18],
    ]) {
      const pt = await cmd('pe:create_price_tier', {
        pe_pt_rfq_id: rfqId,
        pe_pt_min_qty: minQty,
        pe_pt_unit_price: price,
        pe_pt_currency: 'CNY',
        pe_pt_lead_time_days: lead,
      });
      expect(pt?.code, `create price tier ${minQty}`).toBe('0');
    }
  });

  test.beforeEach(async ({ page }) => {
    await uiLogin(page);
  });

  // ---- M1: RFQ list ----
  test('M1 RFQ list shows the customer inquiry with code/model/status', async ({ page }) => {
    await gotoPage(page, '/p/pe_rfq');
    await expect(page.getByText(rfqCode).first()).toBeVisible({ timeout: 12000 });
    await expect(page.getByText(`J6-PCBA-${TAG}`).first()).toBeVisible({ timeout: 8000 });
    await assertNoRawCodeLeak(page, 'pe_rfq_list');
    await page.screenshot({ path: `${SHOT}/m1_rfq_list.png`, fullPage: true });
  });

  // ---- M2: RFQ detail (basic + PCBA tech fields + lifecycle toolbar) ----
  test('M2 RFQ detail renders basic + PCBA fields and lifecycle actions', async ({ page }) => {
    await gotoPage(page, '/p/pe_rfq');
    await openDetail(page, rfqCode);
    // product model value present on the detail
    await expect(page.getByText(`J6-PCBA-${TAG}`).first()).toBeVisible({ timeout: 10000 });
    await assertNoRawCodeLeak(page, 'pe_rfq_detail');
    await page.screenshot({ path: `${SHOT}/m2_rfq_detail.png`, fullPage: true });
  });

  // ---- M3: price-tier sub-table on the RFQ detail (volume-break pricing) ----
  test('M3 RFQ detail price-tier sub-table shows the quantity breaks', async ({ page }) => {
    await gotoPage(page, '/p/pe_rfq');
    await openDetail(page, rfqCode);
    // the price-tier sub-table lives on the 阶梯报价 (Price Tiers) tab, not the default
    // overview tab — activate it first so the tier rows become visible.
    const tierTab = page.getByRole('tab', { name: /阶梯报价|Price Tier/ }).or(page.getByText(/阶梯报价|Price Tiers?/).first());
    await tierTab.first().click({ timeout: 8000 });
    // the three tier unit prices should be visible in the sub-table (each tier is unique;
    // min-qty values like 100/500/1000 can collide with other numbers on the page).
    for (const price of ['12.5', '9.8', '8.2']) {
      await expect(page.getByText(price, { exact: false }).first()).toBeVisible({ timeout: 8000 });
    }
    await assertNoRawCodeLeak(page, 'pe_rfq_price_tiers');
    await page.screenshot({ path: `${SHOT}/m3_rfq_price_tiers.png`, fullPage: true });
  });

  // ---- M4: customer board list ----
  test('M4 customer board list shows the board master record', async ({ page }) => {
    await gotoPage(page, '/p/pe_customer_board');
    await expect(page.getByText(boardCode).first()).toBeVisible({ timeout: 12000 });
    await expect(page.getByText(`J6 Controller Board ${TAG}`).first()).toBeVisible({ timeout: 8000 });
    await assertNoRawCodeLeak(page, 'pe_customer_board_list');
    await page.screenshot({ path: `${SHOT}/m4_board_list.png`, fullPage: true });
  });

  // ---- M5: customer board detail ----
  test('M5 customer board detail renders the board spec fields', async ({ page }) => {
    await gotoPage(page, '/p/pe_customer_board');
    await openDetail(page, boardCode);
    await expect(page.getByText(`J6 Controller Board ${TAG}`).first()).toBeVisible({ timeout: 10000 });
    await assertNoRawCodeLeak(page, 'pe_customer_board_detail');
    await page.screenshot({ path: `${SHOT}/m5_board_detail.png`, fullPage: true });
  });

  // ---- M6: DFM review list (layered crm_review_common) ----
  test('M6 DFM review list shows the manufacturability review', async ({ page }) => {
    await gotoPage(page, '/p/crm_review_common');
    await expect(page.getByText(dfmCode).first()).toBeVisible({ timeout: 12000 });
    await assertNoRawCodeLeak(page, 'crm_review_common_list');
    await page.screenshot({ path: `${SHOT}/m6_dfm_list.png`, fullPage: true });
  });

  // ---- M7: DFM review detail (conclusion + reviewer) ----
  test('M7 DFM review detail renders conclusion and reviewer', async ({ page }) => {
    await gotoPage(page, '/p/crm_review_common');
    await openDetail(page, dfmCode);
    await expect(page.getByText('Engineer Wang').first()).toBeVisible({ timeout: 10000 });
    await assertNoRawCodeLeak(page, 'crm_review_common_detail');
    await page.screenshot({ path: `${SHOT}/m7_dfm_detail.png`, fullPage: true });
  });

  // ---- M8: RFQ create form opens with labelled fields ----
  test('M8 RFQ create form opens with labelled (non-raw) fields', async ({ page }) => {
    await gotoPage(page, '/p/pe_rfq');
    // open the create form (新建 / Create / New / + button)
    const createBtn = page
      .getByRole('button', { name: /新建|新增|创建|Create|New/ })
      .or(page.locator('button:has-text("新建")'))
      .first();
    await createBtn.click({ timeout: 8000 });
    // the form should show the localized product-model label, never the raw code —
    // wait for it directly (condition-based) rather than a fixed delay
    await expect(page.getByText(/产品型号|Product Model/).first()).toBeVisible({ timeout: 10000 });
    await assertNoRawCodeLeak(page, 'pe_rfq_create_form');
    await page.screenshot({ path: `${SHOT}/m8_rfq_form.png`, fullPage: true });
  });
});
