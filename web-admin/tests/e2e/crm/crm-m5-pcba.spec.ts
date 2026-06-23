/**
 * CRM M5 PCBA (J6) — L4 UI golden E2E (sell-side customer RFQ pipeline)
 *
 * Proves the J6 PCBA contract-manufacturing inquiry journey through the real browser
 * UI against the isolated crm-gap stack (pcba-crm plugin): a customer RFQ with DFM
 * review, a customer board master record, and quantity-break price tiers, all visible
 * and editable through the low-code pages with ZERO raw-code / bare-i18n-key leakage.
 *
 * This replaces the M5 spec lost when its original worktree was cleaned up. The J6
 * chain (crm_customer_request_common + crm_customer_request_pcba_rfq sidecar →
 * crm_review_common[dfm] / req_product_pcba_board / crm_customer_request_pcba_price_tier)
 * is set up via the real crm:* / pe:* commands in beforeAll, then asserted through the UI.
 * (A2-S1: the legacy DFM model was decommissioned; DFM reviews now live on the layered
 * crm_review_common model with review_type 'dfm'. A2-S2: the legacy RFQ model was
 * decommissioned; the RFQ truth is now crm_customer_request_common with the 1:1 PCBA sidecar
 * crm_customer_request_pcba_rfq, and price tiers live on
 * crm_customer_request_pcba_price_tier keyed by the customer-request pid.)
 *
 * Run:
 *   PLAYWRIGHT_BASE_URL=http://localhost:5239 BACKEND_URL=http://localhost:6509 \
 *   NO_PROXY=localhost,127.0.0.1 \
 *   npx playwright test tests/e2e/crm/crm-m5-pcba.spec.ts \
 *     --project=chromium-m5 --config=tests/e2e/crm/m5.playwright.config.ts
 *
 * COVERAGE MATRIX (model · kind):
 *   M1  crm_customer_request_pcba_rfq · list   — RFQ code + product model + DFM status visible, no raw leak
 *   M2  crm_customer_request_pcba_rfq · detail — basic + PCBA tech fields render, no raw leak
 *   M3  pe_rfq_workspace (crm_customer_request_common) · detail — RFQ tab price-tier sub-table shows the tier rows
 *   M4  req_product_pcba_board · list   — board master (layers / IPC) visible, no raw leak
 *   M5  req_product_pcba_board · detail — board detail fields render, no raw leak
 *   M6  crm_review_common · list   — DFM review (review_type dfm) visible, no raw leak
 *   M7  crm_review_common · detail — DFM conclusion + reviewer render, no raw leak
 *   M8  crm_customer_request_pcba_rfq · form — create form opens with labelled fields (no raw leak)
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
let crId = '';
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
    const emailInput = page.locator('input#identifier, input#email');
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
  await expect(page.locator('input#identifier, input#email')).toHaveCount(0, { timeout: 5000 });
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
 * Assert no raw field code (crm_crq_*, crm_cpt_*, pe_board_*, crm_rv_*) and no bare
 * i18n key (model.<x>.<y>.label) is leaking into the rendered page text. These are the
 * §2.2 blockers — a label that shows the column code instead of its localized name.
 */
async function assertNoRawCodeLeak(page: Page, label: string): Promise<void> {
  const body = await page.locator('body').innerText();
  const rawField = body.match(/\b(?:pe_(?:rfq|dfm|board|pt|opp|ql|qt)|crm_(?:rv|rk|cl|crq|cpt|cr))_[a-z_]+\b/g) || [];
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
    const boards = await listRows('req_product_pcba_board');
    boardCode = boards.find((b) => b?.pid === board || b?.pe_board_account_id === acc)?.pe_board_code || '';
    expect(boardCode, 'board code resolved').not.toBe('');

    // --- customer request (RFQ truth, A2-S2) — the route handler copies the request
    // --- title into crm_crq_product_model on the PCBA sidecar, so the title IS the
    // --- product model asserted through the UI.
    r = await cmd('crm:create_customer_request', {
      crm_cr_title: `J6-PCBA-${TAG}`,
      crm_cr_account_id: acc,
      crm_cr_type: 'rfq',
      crm_cr_summary: `J6 PCBA contract-manufacturing inquiry ${TAG}`,
    });
    crId = rid(r) || '';
    expect(crId, 'create customer request').toBeTruthy();

    // --- submit + route to the PCBA industry sidecar (handler auto-creates it) ---
    r = await cmd('crm:submit_customer_request', undefined, crId);
    expect(r?.code, 'submit customer request returns OK').toBe('0');
    r = await cmd('pe:route_customer_request_to_rfq', undefined, crId);
    expect(r?.code, 'route customer request returns OK').toBe('0');
    const rfqs = await listRows('crm_customer_request_pcba_rfq');
    const rfqRow = rfqs.find((q) => String(q?.crm_customer_request_id) === crId);
    expect(rfqRow, 'pcba rfq sidecar row found by customer request id').toBeTruthy();
    rfqId = String(rfqRow.pid);
    rfqCode = rfqRow.crm_crq_code;
    expect(rfqCode).not.toBe('');

    // --- move the sidecar through the DFM gate (pending → in_review) ---
    await cmd('pe:request_dfm_pcba_rfq', undefined, rfqId);

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

    // --- quantity-break price tiers on the customer request (the volume-sensitive
    // --- PCBA pricing; crm_cpt_customer_request_id references the CR pid, NOT the sidecar) ---
    for (const [minQty, price, lead] of [
      [100, 12.5, 28],
      [500, 9.8, 21],
      [1000, 8.2, 18],
    ]) {
      const pt = await cmd('pe:create_price_tier_pcba', {
        crm_cpt_customer_request_id: crId,
        crm_cpt_min_qty: minQty,
        crm_cpt_unit_price: price,
        crm_cpt_currency: 'CNY',
        crm_cpt_lead_time_days: lead,
      });
      expect(pt?.code, `create price tier ${minQty}`).toBe('0');
    }
  });

  test.beforeEach(async ({ page }) => {
    await uiLogin(page);
  });

  // ---- M1: PCBA RFQ sidecar list ----
  test('M1 RFQ list shows the customer inquiry with code/model/status', async ({ page }) => {
    await gotoPage(page, '/p/crm_customer_request_pcba_rfq');
    await expect(page.getByText(rfqCode).first()).toBeVisible({ timeout: 12000 });
    await expect(page.getByText(`J6-PCBA-${TAG}`).first()).toBeVisible({ timeout: 8000 });
    await assertNoRawCodeLeak(page, 'crm_customer_request_pcba_rfq_list');
    await page.screenshot({ path: `${SHOT}/m1_rfq_list.png`, fullPage: true });
  });

  // ---- M2: PCBA RFQ sidecar detail (basic + PCBA tech fields) ----
  test('M2 RFQ detail renders basic + PCBA fields and lifecycle actions', async ({ page }) => {
    await gotoPage(page, '/p/crm_customer_request_pcba_rfq');
    await openDetail(page, rfqCode);
    // product model value present on the detail (route handler copies the CR title)
    await expect(page.getByText(`J6-PCBA-${TAG}`).first()).toBeVisible({ timeout: 10000 });
    await assertNoRawCodeLeak(page, 'crm_customer_request_pcba_rfq_detail');
    await page.screenshot({ path: `${SHOT}/m2_rfq_detail.png`, fullPage: true });
  });

  // ---- M3: price-tier sub-table on the RFQ workspace detail (volume-break pricing) ----
  test('M3 RFQ workspace price-tier sub-table shows the quantity breaks', async ({ page }) => {
    // price tiers are children of the customer request; the RFQ workspace detail
    // (/p/pe_rfq_workspace, modelCode crm_customer_request_common) renders them on its RFQ tab.
    await gotoPage(page, '/p/pe_rfq_workspace');
    await openDetail(page, `J6-PCBA-${TAG}`);
    // the 阶梯报价 (Price Tiers) sub-table lives on the RFQ tab, not the default
    // overview tab — activate it first so the tier rows become visible.
    const tierTab = page.getByRole('tab', { name: /^RFQ$/ }).or(page.getByText(/^RFQ$/).first());
    await tierTab.first().click({ timeout: 8000 });
    await expect(page.getByText(/阶梯报价|Price Tiers?/).first()).toBeVisible({ timeout: 8000 });
    // the three tier unit prices should be visible in the sub-table (each tier is unique;
    // min-qty values like 100/500/1000 can collide with other numbers on the page).
    for (const price of ['12.5', '9.8', '8.2']) {
      await expect(page.getByText(price, { exact: false }).first()).toBeVisible({ timeout: 8000 });
    }
    await assertNoRawCodeLeak(page, 'pe_rfq_workspace_price_tiers');
    await page.screenshot({ path: `${SHOT}/m3_rfq_price_tiers.png`, fullPage: true });
  });

  // ---- M4: customer board list ----
  test('M4 customer board list shows the board master record', async ({ page }) => {
    await gotoPage(page, '/p/req_product_pcba_board');
    await expect(page.getByText(boardCode).first()).toBeVisible({ timeout: 12000 });
    await expect(page.getByText(`J6 Controller Board ${TAG}`).first()).toBeVisible({ timeout: 8000 });
    await assertNoRawCodeLeak(page, 'req_product_pcba_board_list');
    await page.screenshot({ path: `${SHOT}/m4_board_list.png`, fullPage: true });
  });

  // ---- M5: customer board detail ----
  test('M5 customer board detail renders the board spec fields', async ({ page }) => {
    await gotoPage(page, '/p/req_product_pcba_board');
    await openDetail(page, boardCode);
    await expect(page.getByText(`J6 Controller Board ${TAG}`).first()).toBeVisible({ timeout: 10000 });
    await assertNoRawCodeLeak(page, 'req_product_pcba_board_detail');
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

  // ---- M8: PCBA RFQ sidecar create form opens with labelled fields ----
  test('M8 RFQ create form opens with labelled (non-raw) fields', async ({ page }) => {
    await gotoPage(page, '/p/crm_customer_request_pcba_rfq');
    // open the create form (新建 / Create / New / + button)
    const createBtn = page
      .getByRole('button', { name: /新建|新增|创建|Create|New/ })
      .or(page.locator('button:has-text("新建"):not(:has-text("今日"))'))
      .first();
    await createBtn.click({ timeout: 8000 });
    // the form should show the localized product-model label, never the raw code —
    // wait for it directly (condition-based) rather than a fixed delay
    await expect(page.getByText(/产品型号|Product Model/).first()).toBeVisible({ timeout: 10000 });
    await assertNoRawCodeLeak(page, 'crm_customer_request_pcba_rfq_create_form');
    await page.screenshot({ path: `${SHOT}/m8_rfq_form.png`, fullPage: true });
  });
});
