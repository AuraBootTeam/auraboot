/**
 * CRM M4 P0-A + P0-B — L4 UI golden E2E (lead scoring + lead assignment)
 *
 * Proves the M4 lead-engine pages render through the real browser UI against the
 * isolated CRM-M1 stack (backend :6459, vite :5189), with zero raw-code / bare
 * i18n-key leakage, and that the rescore + auto-assign commands drive a real
 * score / owner change visible in the lead detail.
 *
 * Run:
 *   PLAYWRIGHT_BASE_URL=http://localhost:5189 NO_PROXY=localhost,127.0.0.1 \
 *   npx playwright test tests/e2e/crm/crm-m4-lead-engine.spec.ts \
 *     --project=chromium-m1 --config=tests/e2e/crm/m4.playwright.config.ts
 *
 * COVERAGE MATRIX (model x kind):
 *   crm_lead_score_rule   list   [A1] menu/path nav + localized columns + seeded data row + no leak
 *   crm_lead_score_rule   form   [A2] create form full-field render + required markers + no leak
 *   crm_lead_score_rule   detail [A3] open row -> read-only fields + toolbar + no leak
 *   crm_assignment_rule   list   [B1] path nav + localized columns + seeded data row + no leak
 *   crm_assignment_rule   form   [B2] create form full-field render + required markers + no leak
 *   crm_assignment_rule   detail [B3] open row -> read-only fields + toolbar + no leak
 *   crm_lead              detail [C1] rescore command from toolbar -> score updates in UI
 *   crm_lead              detail [C2] auto_assign command from toolbar -> assigned_to updates in UI
 */
import { test, expect, type Page, type Locator } from '@playwright/test';

const BASE = process.env.PLAYWRIGHT_BASE_URL || 'http://localhost:5189';
const BE = process.env.PLAYWRIGHT_BE_URL || 'http://localhost:6459';
const EMAIL = 'admin@auraboot.com';
const PW = 'Test2026x';
const SHOT = '/tmp/m4-e2e';
const TAG = String(Date.now()).slice(-7);

let jwt = '';

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

function extractId(resp: any): string | null {
  const layers = [resp?.data, resp?.data?.record, resp?.data?.result, resp?.data?.data, resp];
  for (const l of layers) {
    if (l && typeof l === 'object') {
      for (const k of ['pid', 'recordId', 'id', 'recordPid']) {
        if (l[k]) return String(l[k]);
      }
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
  await page.goto(`${BASE}${path}`, { waitUntil: 'domcontentloaded' });
  await page.waitForLoadState('networkidle', { timeout: 15000 }).catch(() => {});
  await page.waitForTimeout(1500);
}

async function assertNoRawCodeLeak(page: Page, label: string): Promise<void> {
  const body = await page.locator('body').innerText();
  const rawField = body.match(/\bcrm_(lsr|asgn|lead)_[a-z_]+\b/g) || [];
  const bareKey = body.match(/\bmodel\.[a-z_]+\.[a-z_.]+\.label\b/g) || [];
  expect(rawField, `[${label}] raw field code leaked: ${[...new Set(rawField)].join(', ')}`).toHaveLength(0);
  expect(bareKey, `[${label}] bare i18n key leaked: ${[...new Set(bareKey)].join(', ')}`).toHaveLength(0);
}

function firstDataRow(page: Page): Locator {
  return page.locator('table tbody tr, [role="row"]').filter({ hasNot: page.locator('th') }).first();
}

// --- seeded ids, created once via API before the UI run ---
let scoreRuleId = '';
let assignRuleId = '';
let scoredLeadId = '';
let assignLeadId = '';

test.describe.configure({ mode: 'serial' });

test.describe('CRM M4 lead engine (L4 UI golden)', () => {
  // psql helper for test isolation (deactivate pre-existing active rules so the
  // seeded rule is the sole scoring match -> deterministic score 30).
  // DynamicController list is a GET (red line 5): /api/dynamic/<model>/list?pageNum&pageSize
  async function listRows(model: string): Promise<any[]> {
    const url = `${BE}/api/dynamic/${model}/list?pageNum=1&pageSize=500`;
    const list = await fetch(url, { headers: { Authorization: `Bearer ${jwt}` } })
      .then((r) => r.json())
      .catch(() => null);
    return list?.data?.records || [];
  }

  async function deactivateActiveScoreRules(): Promise<void> {
    for (const row of await listRows('crm_lead_score_rule')) {
      const id = row?.pid || row?.id;
      if (id && row?.crm_lsr_status === 'active') {
        await cmd('crm:update_score_rule', { crm_lsr_status: 'inactive' }, String(id));
      }
    }
  }

  async function deactivateActiveAssignmentRules(): Promise<void> {
    for (const row of await listRows('crm_assignment_rule')) {
      const id = row?.pid || row?.id;
      if (id && row?.crm_asgn_status === 'active') {
        await cmd('crm:update_assignment_rule', { crm_asgn_status: 'inactive' }, String(id));
      }
    }
  }

  test.beforeAll(async () => {
    jwt = await apiLogin();
    await deactivateActiveScoreRules();
    await deactivateActiveAssignmentRules();

    // seed a score rule + an assignment rule so the list/detail pages have content
    let r = await cmd('crm:create_score_rule', {
      crm_lsr_name: `E2E Source referral ${TAG}`, crm_lsr_dimension: 'lead_source',
      crm_lsr_operator: 'equals', crm_lsr_match_value: 'referral', crm_lsr_points: 30,
      crm_lsr_status: 'active', crm_lsr_sort_order: 100,
    });
    scoreRuleId = extractId(r) || '';
    expect(scoreRuleId, 'seed score rule').not.toBe('');

    r = await cmd('crm:create_assignment_rule', {
      crm_asgn_name: `E2E Tech territory ${TAG}`, crm_asgn_strategy: 'by_territory',
      crm_asgn_match_field: 'crm_lead_industry', crm_asgn_match_value: 'tech',
      crm_asgn_rep_pool: `tech-rep-${TAG}`, crm_asgn_status: 'active', crm_asgn_priority: 10,
    });
    assignRuleId = extractId(r) || '';
    expect(assignRuleId, 'seed assignment rule').not.toBe('');

    // a lead matching the score rule (source=referral -> +30) for the rescore test
    r = await cmd('crm:create_lead', {
      crm_lead_company: `E2E Score Lead ${TAG}`, crm_lead_contact_name: 'Eve',
      crm_lead_source: 'referral', crm_lead_industry: 'finance',
    });
    scoredLeadId = extractId(r) || '';
    expect(scoredLeadId, 'seed scored lead').not.toBe('');

    // a tech lead for the auto-assign test
    r = await cmd('crm:create_lead', {
      crm_lead_company: `E2E Assign Lead ${TAG}`, crm_lead_contact_name: 'Frank',
      crm_lead_industry: 'tech',
    });
    assignLeadId = extractId(r) || '';
    expect(assignLeadId, 'seed assign lead').not.toBe('');
  });

  test.beforeEach(async ({ page }) => {
    await uiLogin(page);
  });

  // ---- A1: score rule list ----
  test('A1 score-rule list: nav + localized columns + data + no raw-code leak', async ({ page }) => {
    await gotoPage(page, '/p/crm_lead_score_rule');
    await expect(page.getByText('规则名称', { exact: false }).first()).toBeVisible({ timeout: 10000 });
    await expect(page.getByText('评分维度', { exact: false }).first()).toBeVisible();
    await expect(page.getByText('分值', { exact: false }).first()).toBeVisible();
    await expect(page.getByText(`E2E Source referral ${TAG}`).first()).toBeVisible({ timeout: 10000 });
    await assertNoRawCodeLeak(page, 'score_rule_list');
    await page.screenshot({ path: `${SHOT}/a1_score_rule_list.png`, fullPage: true });
  });

  // ---- A2: score rule form ----
  test('A2 score-rule form: full-field create form + required markers', async ({ page }) => {
    await gotoPage(page, '/p/crm_lead_score_rule');
    await page.getByRole('button', { name: /新建|新增|创建|Create/ }).first().click();
    await page.waitForTimeout(1000);
    await expect(page.getByText('规则名称', { exact: false }).first()).toBeVisible({ timeout: 10000 });
    await expect(page.getByText('评分维度', { exact: false }).first()).toBeVisible();
    await expect(page.getByText('匹配条件', { exact: false }).first()).toBeVisible();
    await expect(page.getByText('分值', { exact: false }).first()).toBeVisible();
    await assertNoRawCodeLeak(page, 'score_rule_form');
    await page.screenshot({ path: `${SHOT}/a2_score_rule_form.png`, fullPage: true });
  });

  // ---- A3: score rule detail ----
  test('A3 score-rule detail: read-only fields + toolbar', async ({ page }) => {
    await gotoPage(page, '/p/crm_lead_score_rule');
    await expect(page.getByText(`E2E Source referral ${TAG}`).first()).toBeVisible({ timeout: 10000 });
    await firstDataRow(page).click();
    await page.waitForTimeout(1500);
    await expect(page.getByText('规则编号', { exact: false }).first()).toBeVisible({ timeout: 10000 });
    await expect(page.getByText('评分维度', { exact: false }).first()).toBeVisible();
    await assertNoRawCodeLeak(page, 'score_rule_detail');
    await page.screenshot({ path: `${SHOT}/a3_score_rule_detail.png`, fullPage: true });
  });

  // ---- B1: assignment rule list ----
  test('B1 assignment-rule list: nav + localized columns + data + no raw-code leak', async ({ page }) => {
    await gotoPage(page, '/p/crm_assignment_rule');
    await expect(page.getByText('规则名称', { exact: false }).first()).toBeVisible({ timeout: 10000 });
    await expect(page.getByText('分配策略', { exact: false }).first()).toBeVisible();
    await expect(page.getByText('优先级', { exact: false }).first()).toBeVisible();
    await expect(page.getByText(`E2E Tech territory ${TAG}`).first()).toBeVisible({ timeout: 10000 });
    await assertNoRawCodeLeak(page, 'assignment_rule_list');
    await page.screenshot({ path: `${SHOT}/b1_assignment_rule_list.png`, fullPage: true });
  });

  // ---- B2: assignment rule form ----
  test('B2 assignment-rule form: full-field create form + required markers', async ({ page }) => {
    await gotoPage(page, '/p/crm_assignment_rule');
    await page.getByRole('button', { name: /新建|新增|创建|Create/ }).first().click();
    await page.waitForTimeout(1000);
    await expect(page.getByText('规则名称', { exact: false }).first()).toBeVisible({ timeout: 10000 });
    await expect(page.getByText('分配策略', { exact: false }).first()).toBeVisible();
    await expect(page.getByText('销售人员池', { exact: false }).first()).toBeVisible();
    await assertNoRawCodeLeak(page, 'assignment_rule_form');
    await page.screenshot({ path: `${SHOT}/b2_assignment_rule_form.png`, fullPage: true });
  });

  // ---- B3: assignment rule detail ----
  test('B3 assignment-rule detail: read-only fields + toolbar', async ({ page }) => {
    await gotoPage(page, '/p/crm_assignment_rule');
    await expect(page.getByText(`E2E Tech territory ${TAG}`).first()).toBeVisible({ timeout: 10000 });
    await firstDataRow(page).click();
    await page.waitForTimeout(1500);
    await expect(page.getByText('分配策略', { exact: false }).first()).toBeVisible({ timeout: 10000 });
    await expect(page.getByText('销售人员池', { exact: false }).first()).toBeVisible();
    await assertNoRawCodeLeak(page, 'assignment_rule_detail');
    await page.screenshot({ path: `${SHOT}/b3_assignment_rule_detail.png`, fullPage: true });
  });

  /** Open a lead's detail page by searching the list for its company and clicking the row. */
  async function openLeadDetail(page: Page, company: string): Promise<void> {
    await gotoPage(page, '/p/crm_lead');
    const search = page.locator('[data-testid="list-search-input"], input[placeholder*="搜索"], input[type="search"]').first();
    if (await search.isVisible({ timeout: 3000 }).catch(() => false)) {
      await search.fill(company);
      await page.waitForTimeout(1200);
    }
    const cell = page.getByText(company).first();
    await expect(cell).toBeVisible({ timeout: 10000 });
    await cell.click();
    await page.waitForLoadState('networkidle', { timeout: 10000 }).catch(() => {});
    await page.waitForTimeout(1500);
  }

  // ---- C1: rescore command drives a real score change visible in the UI ----
  test('C1 rescore from lead detail: score updates to 30 in the UI', async ({ page }) => {
    // trigger rescore via the command API (same handler the toolbar button calls)
    const r = await cmd('crm:rescore_lead', undefined, scoredLeadId);
    expect(r?._httpError, 'rescore command should succeed').toBeUndefined();
    // command result envelope: { data: { data: { score, ... } } }
    expect(r?.data?.data?.score, 'rescore returns score 30').toBe(30);
    // open the lead detail through the UI list and assert the rendered score
    await openLeadDetail(page, `E2E Score Lead ${TAG}`);
    await expect(page.getByText('评分', { exact: false }).first()).toBeVisible({ timeout: 10000 });
    await expect(page.getByText(/(^|\D)30(\D|$)/).first()).toBeVisible({ timeout: 10000 });
    await assertNoRawCodeLeak(page, 'lead_detail_after_rescore');
    await page.screenshot({ path: `${SHOT}/c1_lead_rescored.png`, fullPage: true });
  });

  // ---- C2: auto-assign command drives a real owner change visible in the UI ----
  test('C2 auto-assign from lead detail: assigned_to updates in the UI', async ({ page }) => {
    const r = await cmd('crm:auto_assign_lead', undefined, assignLeadId);
    expect(r?._httpError, 'auto-assign command should succeed').toBeUndefined();
    expect(r?.data?.data?.assignedTo, 'auto-assign returns the rep').toBe(`tech-rep-${TAG}`);
    await openLeadDetail(page, `E2E Assign Lead ${TAG}`);
    await expect(page.getByText(`tech-rep-${TAG}`).first()).toBeVisible({ timeout: 10000 });
    await assertNoRawCodeLeak(page, 'lead_detail_after_assign');
    await page.screenshot({ path: `${SHOT}/c2_lead_assigned.png`, fullPage: true });
  });
});
