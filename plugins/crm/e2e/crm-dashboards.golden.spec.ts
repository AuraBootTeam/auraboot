import { expect, test, type Page } from '@playwright/test';
import { Buffer } from 'node:buffer';
import { mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';

const BASE = process.env.PLAYWRIGHT_BASE_URL || 'http://127.0.0.1:5196';
const BE = process.env.BACKEND_URL || 'http://127.0.0.1:6466';
const RUN = process.env.CRM_DASHBOARD_RUN_ID || `dashboard-${Date.now()}`;
const EVIDENCE_DIR = process.env.CRM_DASHBOARD_EVIDENCE_DIR
  || path.resolve(process.cwd(), '.workspace', 'evidence', 'crm-dashboard');
const REUSE_DATA = process.env.CRM_DASHBOARD_REUSE_DATA === '1';
const SEED_RECEIPT = process.env.CRM_DASHBOARD_SEED_RECEIPT || '';
const ADMIN_EMAIL = 'admin@auraboot.com';
const PASSWORD = 'Test2026x';

const EXPECTED_COVERAGE = {
  pages: ['crm_dashboard', 'crm_sales_forecast'],
  queries: [
    'crm_dashboard_kpi',
    'crm_dashboard_pending_quotes',
    'crm_dashboard_recent_opportunities',
    'crm_sales_period_overview',
    'crm_lead_pipeline_stats',
    'crm_lead_source_distribution',
    'crm_my_tasks',
    'crm_open_opportunities_detail',
    'crm_opp_lost_reason_breakdown',
    'crm_opp_stale',
    'crm_opportunity_monthly_trend',
    'crm_opportunity_pipeline_stats',
    'crm_recent_activities',
    'crm_sales_forecast_by_category',
    'crm_sales_forecast_by_close_date',
    'crm_sales_forecast_by_owner',
    'crm_sales_forecast_by_stage',
    'crm_sales_forecast_kpi',
    'crm_win_loss_ratio',
  ],
  runtimeEndpoints: ['/api/inbox'],
  dashboardTargets: [
    'crm_dashboard:block_sales_inbox',
    'crm_dashboard:block_sales_period_overview',
    'crm_dashboard:block_sales_plans',
    'crm_dashboard:block_sales_shortcuts',
    'crm_dashboard:block_kpi_cards',
    'crm_dashboard:block_lead_pipeline',
    'crm_dashboard:block_pending_quotes',
    'crm_dashboard:block_recent_activities',
    'crm_dashboard:block_recent_leads',
    'crm_dashboard:block_recent_opportunities',
    'crm_dashboard:block_stale_opportunities',
    'crm_dashboard:chart_lead_source',
    'crm_dashboard:chart_lost_reason_breakdown',
    'crm_dashboard:chart_opp_monthly_trend',
    'crm_dashboard:chart_opp_pipeline',
    'crm_dashboard:chart_win_loss',
    'crm_sales_forecast:block_forecast_kpi',
    'crm_sales_forecast:chart_forecast_by_category',
    'crm_sales_forecast:chart_forecast_by_month',
    'crm_sales_forecast:chart_forecast_by_owner',
    'crm_sales_forecast:chart_forecast_by_stage',
    'crm_sales_forecast:table_forecast_by_category_detail',
    'crm_sales_forecast:table_open_opportunities',
    'crm_sales_forecast:table_stage_detail',
  ],
  uiActions: [
    'crm_dashboard:block_kpi_cards:new_leads_drilldown',
    'crm_dashboard:block_sales_shortcuts:new_account',
    'crm_dashboard:block_sales_shortcuts:new_activity',
    'crm_dashboard:block_sales_shortcuts:new_lead',
    'crm_dashboard:block_sales_shortcuts:new_opportunity',
    'crm_dashboard:block_sales_shortcuts:open_activity_service',
    'crm_dashboard:block_sales_shortcuts:open_my_tasks',
  ],
} as const;
type CoverageAxis = keyof typeof EXPECTED_COVERAGE;

const completedCoverage: Record<CoverageAxis, Set<string>> = Object.fromEntries(
  Object.keys(EXPECTED_COVERAGE).map((axis) => [axis, new Set<string>()]),
) as Record<CoverageAxis, Set<string>>;
const completedScenarios = new Set<string>();
const screenshots: string[] = [];
const recordIds: Record<string, string | string[]> = {};
const failedRuntimeRequests: string[] = [];
let adminJwt = '';
let expectedOwnerDisplayName = '';

function cover(axis: CoverageAxis, ...items: string[]): void {
  for (const item of items) completedCoverage[axis].add(item);
}

function findValue(value: unknown, keys: string[]): unknown {
  if (Array.isArray(value)) {
    for (const child of value) {
      const found = findValue(child, keys);
      if (found !== undefined && found !== null && found !== '') return found;
    }
  } else if (value && typeof value === 'object') {
    const record = value as Record<string, unknown>;
    for (const key of keys) {
      if (record[key] !== undefined && record[key] !== null && record[key] !== '') {
        return record[key];
      }
    }
    for (const child of Object.values(record)) {
      const found = findValue(child, keys);
      if (found !== undefined && found !== null && found !== '') return found;
    }
  }
  return undefined;
}

async function api(pathname: string, init: RequestInit = {}, jwt = adminJwt): Promise<any> {
  const headers = new Headers(init.headers);
  if (jwt) headers.set('Authorization', `Bearer ${jwt}`);
  if (init.body) headers.set('Content-Type', 'application/json');
  const response = await fetch(`${BE}${pathname}`, { ...init, headers });
  const body = await response.json().catch(() => ({}));
  return { response, body };
}

function assertOk(result: any, label: string): any {
  expect(result.response.ok, `${label}: HTTP ${result.response.status} ${JSON.stringify(result.body)}`)
    .toBeTruthy();
  expect(String(result.body?.code), `${label}: ${JSON.stringify(result.body)}`).toBe('0');
  return result.body;
}

async function executeCreate(code: string, payload: Record<string, unknown>): Promise<string> {
  const body = assertOk(await api(`/api/meta/commands/execute/${code}`, {
    method: 'POST',
    body: JSON.stringify({ payload, operationType: 'create' }),
  }), code);
  const pid = findValue(body?.data?.data ?? body?.data, [
    'recordId',
    'recordPid',
    'publicRecordId',
    'pid',
  ]);
  expect(pid, `${code} must return a public record id`).toBeTruthy();
  return String(pid);
}

async function executeTransition(
  code: string,
  targetRecordPid: string,
  payload: Record<string, unknown> = {},
): Promise<void> {
  assertOk(await api(`/api/meta/commands/execute/${code}`, {
    method: 'POST',
    body: JSON.stringify({ payload, targetRecordPid, operationType: 'update' }),
  }), `${code}/${targetRecordPid}`);
}

async function provisionSalesUser(): Promise<string> {
  expectedOwnerDisplayName = '华东区销售代表';
  const email = `crm-dashboard-sales-${RUN.toLowerCase().replace(/[^a-z0-9]+/g, '-')}@auraboot.local`;
  const body = assertOk(await api('/api/admin/users', {
    method: 'POST',
    body: JSON.stringify({
      email,
      displayName: expectedOwnerDisplayName,
      initialPassword: PASSWORD,
      roleCodes: ['crm_sales'],
      sendInviteEmail: false,
    }),
  }), 'provision dashboard sales persona');
  const directPid = findValue(body?.data, ['pid', 'userPid']);
  if (directPid) return String(directPid);

  const searchBody = assertOk(await api(
    `/api/admin/users/search?keyword=${encodeURIComponent(email)}&size=20`,
  ), 'resolve dashboard sales persona');
  const users = searchBody?.data?.content ?? searchBody?.data ?? [];
  const user = Array.isArray(users)
    ? users.find((candidate: Record<string, unknown>) => candidate.email === email)
    : undefined;
  expect(user?.pid, 'dashboard sales persona must expose a public pid').toBeTruthy();
  return String(user.pid);
}

async function seedDashboardData(): Promise<void> {
  const currentUserBody = assertOk(await api('/api/auth/me'), 'read current admin identity')?.data;
  const currentUser = currentUserBody?.user;
  expect(String(currentUser?.pid || ''), 'current admin must expose a public pid').toBeTruthy();
  expect(String(currentUser?.id || ''), 'current admin must expose a numeric user id').toBeTruthy();
  const currentTenantId = currentUser?.tenantId ?? currentUserBody?.tenantId;
  expect(String(currentTenantId || ''), 'current admin must expose a tenant id').toBeTruthy();
  const ownerPid = await provisionSalesUser();

  const account = await executeCreate('crm:create_account', {
    crm_acc_name: '华东智造集团',
    crm_acc_industry: 'manufacturing',
    crm_acc_status: 'active',
    crm_acc_rating: 'A',
  });
  recordIds.account = account;

  const leadSources = ['website', 'exhibition', 'referral'];
  const leadCompanies = ['明澜科技', '启辰供应链', '华东智造集团'];
  recordIds.leads = [];
  for (const [index, source] of leadSources.entries()) {
    const lead = await executeCreate('crm:create_lead', {
      crm_lead_company: leadCompanies[index],
      crm_lead_contact_name: ['林嘉禾', '周启明', '沈思远'][index],
      crm_lead_source: source,
      crm_lead_score: 70 + index * 8,
      crm_lead_assigned_to: ownerPid,
    });
    (recordIds.leads as string[]).push(lead);
  }

  const closeDates = [21, 39, 67, 84, 12, 18].map((days) =>
    new Date(Date.now() + days * 24 * 60 * 60 * 1000).toISOString());
  const opportunities: string[] = [];
  const opportunityNames = [
    '智能工厂数据中台',
    '集团 CRM 升级',
    '渠道协同平台',
    '客户服务智能化',
    '销售预测优化',
    '海外营销拓展',
  ];
  for (const [index, closeDate] of closeDates.entries()) {
    opportunities.push(await executeCreate('crm:create_opportunity', {
      crm_opp_name: opportunityNames[index],
      crm_opp_account_id: account,
      crm_opp_currency_code: 'CNY',
      crm_opp_expected_amount: 360000 + index * 180000,
      crm_opp_expected_close_date: closeDate,
      crm_opp_probability: 20 + index * 20,
      crm_opp_forecast_category: [
        'pipeline', 'best_case', 'commit', 'commit', 'commit', 'best_case',
      ][index],
      crm_opp_owner: ownerPid,
    }));
  }
  await executeTransition('crm:qualify_opportunity', opportunities[1]);
  await executeTransition('crm:qualify_opportunity', opportunities[2]);
  await executeTransition('crm:advance_opp_to_proposal', opportunities[2]);
  await executeTransition('crm:qualify_opportunity', opportunities[3]);
  await executeTransition('crm:advance_opp_to_proposal', opportunities[3]);
  await executeTransition('crm:advance_opp_to_negotiation', opportunities[3]);
  await executeTransition('crm:qualify_opportunity', opportunities[4]);
  await executeTransition('crm:advance_opp_to_proposal', opportunities[4]);
  await executeTransition('crm:advance_opp_to_negotiation', opportunities[4]);
  await executeTransition('crm:win_opportunity', opportunities[4]);
  await executeTransition('crm:lose_opportunity', opportunities[5], {
    crm_opp_lost_reason: '客户本期预算受限，转入后续培育。',
    crm_opp_lost_reason_code: 'no_budget',
  });
  recordIds.opportunities = opportunities;

  recordIds.activities = [];
  for (const [index, type] of ['call', 'meeting', 'email'].entries()) {
    const activity = await executeCreate('crm:create_activity', {
      crm_act_type: type,
      crm_act_subject: [`需求澄清电话`, `方案评审会议`, `商务材料确认`][index],
      crm_act_content: '仪表盘最终业务样本：已记录客户互动结论与下一步行动。',
      crm_act_related_model: 'crm_account_common',
      crm_act_related_id: account,
    });
    (recordIds.activities as string[]).push(activity);
  }

  recordIds.plan = await executeCreate('crm:create_activity', {
    crm_act_type: 'task',
    crm_act_subject: '确认本周客户跟进节奏',
    crm_act_content: '复核重点客户、进行中商机和下一步责任人。',
    crm_act_status: 'open',
    crm_act_priority: 'high',
    crm_act_due_date: new Date(Date.now() + 2 * 24 * 60 * 60 * 1000).toISOString().slice(0, 10),
    crm_act_assignee: String(currentUser.id),
    crm_act_related_model: 'crm_account_common',
    crm_act_related_id: account,
  });

  const inboxFixture = await api('/api/test/fixture', {
    method: 'POST',
    body: JSON.stringify({
      name: 'inbox_mention',
      testRunId: `crm-dashboard-${RUN}`,
      params: {
        count: 1,
        tenantId: currentTenantId,
        userId: currentUser.id,
      },
    }),
  });
  expect(inboxFixture.response.ok, `inbox fixture HTTP ${inboxFixture.response.status}`)
    .toBeTruthy();
  expect(inboxFixture.body?.success, JSON.stringify(inboxFixture.body)).toBe(true);
  expect(inboxFixture.body?.recordPids).toHaveLength(1);
  recordIds.inbox = inboxFixture.body.recordPids;

  recordIds.quotes = [];
  for (const [index, status] of ['draft', 'approval'].entries()) {
    const quote = await executeCreate('crm:create_quote_summary', {
      crm_qs_account_id: account,
      crm_qs_opportunity_id: opportunities[index + 2],
      crm_qs_source_quote_type: 'service_proposal',
      crm_qs_status: status,
      crm_qs_quote_amount: 420000 + index * 180000,
      crm_qs_currency: 'CNY',
      crm_qs_valid_until: new Date(Date.now() + (30 + index * 15) * 24 * 60 * 60 * 1000)
        .toISOString().slice(0, 10),
      crm_qs_summary: index === 0 ? '数字化升级一期方案' : '集团版扩容与服务方案',
    });
    (recordIds.quotes as string[]).push(quote);
  }

  recordIds.complaint = await executeCreate('crm:create_complaint', {
    crm_cmp_account_id: account,
    crm_cmp_type: 'service',
    crm_cmp_severity: 'medium',
    crm_cmp_date: new Date().toISOString(),
    crm_cmp_description: '客户反馈培训排期需提前确认，服务团队已受理。',
  });
}

async function uiLogin(page: Page): Promise<void> {
  const response = await page.request.post(`${BASE}/login`, {
    form: {
      email: ADMIN_EMAIL,
      password: PASSWORD,
      remember: 'on',
      redirectTo: '/',
    },
    maxRedirects: 0,
  });
  expect([302, 303], `UI session login: HTTP ${response.status()}`).toContain(response.status());
  await page.goto(`${BASE}/`, { waitUntil: 'domcontentloaded' });
  if (page.url().includes('tenant-selection')) {
    await page.getByRole('button', { name: /进入|选择|Enter|AuraBoot/ }).first().click();
    await page.waitForURL((url) => !url.pathname.includes('tenant-selection'), { timeout: 15_000 });
  }
  await expect(page.locator('input#email')).toHaveCount(0, { timeout: 10_000 });
}

function observeDashboardRequests(page: Page): void {
  page.on('response', (response) => {
    const request = response.request();
    let signature = `${response.url()} ${request.postData() || ''}`;
    try {
      signature = decodeURIComponent(signature);
    } catch {
      // Keep the undecoded request signature when malformed percent escapes occur.
    }
    for (const query of EXPECTED_COVERAGE.queries) {
      if (response.ok() && signature.includes(query)) cover('queries', query);
    }
    for (const endpoint of EXPECTED_COVERAGE.runtimeEndpoints) {
      if (response.ok() && response.url().includes(endpoint)) cover('runtimeEndpoints', endpoint);
    }
    if (/\/api\/(dashboards|datasource|dynamic|inbox)(\/|\?|$)/.test(response.url())
      && response.status() >= 400) {
      failedRuntimeRequests.push(`${response.status()} ${request.method()} ${response.url()}`);
    }
  });
}

async function openDashboardFromMenu(
  page: Page,
  code: string,
  heading: RegExp,
  blockIds: string[],
): Promise<void> {
  const menuLink = page.locator(`a[href="/dashboards/view/${code}"]`).first();
  await expect(menuLink, `${code} must be reachable from the permission-filtered menu`).toHaveCount(1);
  await menuLink.click();
  await expect(page).toHaveURL(new RegExp(`/dashboards/view/${code}$`));
  await expect(page.getByRole('heading', { name: heading })).toBeVisible({ timeout: 25_000 });
  for (const id of blockIds) {
    await expect(page.getByTestId(`dashboard-block-${id}`)).toBeVisible({ timeout: 25_000 });
  }
  cover('pages', code);
  cover('dashboardTargets', ...blockIds.map((id) => `${code}:${id}`));
}

async function assertDashboardLayout(page: Page, blockIds: string[]): Promise<void> {
  const container = page.locator('.react-grid-layout').first();
  await expect(container).toBeVisible();
  const boxes = await Promise.all(blockIds.map(async (id) => {
    const box = await page.getByTestId(`dashboard-block-${id}`).boundingBox();
    expect(box, `${id} must have a runtime layout box`).not.toBeNull();
    return { id, ...box! };
  }));

  for (const box of boxes) {
    expect(box.width, `${box.id} must not collapse horizontally`).toBeGreaterThan(180);
    expect(box.height, `${box.id} must not collapse vertically`).toBeGreaterThan(90);
  }
  for (let left = 0; left < boxes.length; left += 1) {
    for (let right = left + 1; right < boxes.length; right += 1) {
      const a = boxes[left];
      const b = boxes[right];
      const overlapX = Math.min(a.x + a.width, b.x + b.width) - Math.max(a.x, b.x);
      const overlapY = Math.min(a.y + a.height, b.y + b.height) - Math.max(a.y, b.y);
      expect(
        overlapX > 3 && overlapY > 3,
        `${a.id} and ${b.id} must not overlap at runtime`,
      ).toBeFalsy();
    }
  }

  const rows: Array<typeof boxes> = [];
  for (const box of [...boxes].sort((a, b) => a.y - b.y || a.x - b.x)) {
    const row = rows.find((candidate) => Math.abs(candidate[0].y - box.y) <= 4);
    if (row) row.push(box);
    else rows.push([box]);
  }
  const gridLeft = Math.min(...boxes.map((box) => box.x));
  const gridRight = Math.max(...boxes.map((box) => box.x + box.width));
  for (const row of rows) {
    const left = Math.min(...row.map((box) => box.x));
    const right = Math.max(...row.map((box) => box.x + box.width));
    expect(Math.abs(left - gridLeft), 'each authored dashboard row must start at the grid edge')
      .toBeLessThan(4);
    expect(Math.abs(gridRight - right), 'each authored dashboard row must fill the grid width')
      .toBeLessThan(5);
  }
}

async function assertNoTechnicalLeak(page: Page): Promise<void> {
  const bodyText = await page.locator('body').innerText();
  expect(bodyText).not.toMatch(/加载失败|Page not found|Dashboard not found|Failed to load/i);
  expect(bodyText).not.toMatch(/\bcrm_[a-z0-9_-]+\b/i);
  expect(bodyText).not.toMatch(/\b\d{18,20}\b/);
  expect(bodyText).not.toMatch(/\b[0-9A-HJKMNP-TV-Z]{26}\b/i);
}

async function screenshot(page: Page, name: string): Promise<void> {
  const output = path.join(EVIDENCE_DIR, name);
  await page.screenshot({ path: output, animations: 'disabled' });
  screenshots.push(output);
}

async function waitForUiSettled(page: Page): Promise<void> {
  await page.evaluate(async () => {
    await document.fonts.ready;
    await new Promise<void>((resolve) => {
      requestAnimationFrame(() => requestAnimationFrame(() => resolve()));
    });
  });
}

async function screenshotDashboard(page: Page, stem: string): Promise<void> {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await waitForUiSettled(page);
  await screenshot(page, `${stem}-desktop-top.png`);
  const scrollDelta = await page.evaluate(() => {
    const grid = document.querySelector<HTMLElement>('.react-grid-layout');
    let candidate = grid?.parentElement ?? null;
    while (candidate) {
      const style = window.getComputedStyle(candidate);
      const delta = candidate.scrollHeight - candidate.clientHeight;
      if (/auto|scroll/.test(style.overflowY) && delta > 0) {
        candidate.scrollTop = candidate.scrollHeight;
        return delta;
      }
      candidate = candidate.parentElement;
    }
    return 0;
  });
  expect(scrollDelta, 'dashboard evidence must have a real lower viewport').toBeGreaterThan(150);
  await waitForUiSettled(page);
  await screenshot(page, `${stem}-desktop-bottom.png`);
  await page.evaluate(() => {
    for (const element of document.querySelectorAll<HTMLElement>('*')) element.scrollTop = 0;
    window.scrollTo(0, 0);
  });
  await page.setViewportSize({ width: 1024, height: 900 });
  const collapseSidebar = page.getByTitle('Collapse sidebar');
  if (await collapseSidebar.isVisible().catch(() => false)) {
    await collapseSidebar.click();
    await expect.poll(async () => (await page.getByTestId('sidebar').boundingBox())?.width ?? 999)
      .toBeLessThan(90);
  }
  await waitForUiSettled(page);
  await screenshot(page, `${stem}-compact-top.png`);
  await page.setViewportSize({ width: 1440, height: 1000 });
}

test.describe.configure({ mode: 'serial' });

test.beforeAll(async () => {
  mkdirSync(EVIDENCE_DIR, { recursive: true });
  const login = await api('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify({ email: ADMIN_EMAIL, password: PASSWORD }),
  }, '');
  const body = assertOk(login, 'API login');
  adminJwt = String(findValue(body?.data, ['jwt']) || '');
  expect(adminJwt).toBeTruthy();
  if (REUSE_DATA) {
    expectedOwnerDisplayName = process.env.CRM_DASHBOARD_EXPECTED_OWNER || '华东区销售代表';
    if (SEED_RECEIPT) recordIds.seedReceipt = SEED_RECEIPT;
  } else {
    await seedDashboardData();
  }
});

test.afterAll(() => {
  const coverage = Object.fromEntries(
    Object.entries(EXPECTED_COVERAGE).map(([axis, expected]) => [axis, {
      expected: [...expected].sort(),
      completed: [...completedCoverage[axis as CoverageAxis]].sort(),
    }]),
  );
  const coverageComplete = Object.values(coverage).every(({ expected, completed }) =>
    JSON.stringify(expected) === JSON.stringify(completed));
  const expectedScenarios = ['crm-dashboard-journey', 'sales-forecast-journey'];
  const evidence = {
    schemaVersion: 1,
    release: 'CRM dashboard parity gate',
    runId: RUN,
    baseUrl: BASE,
    backendUrl: BE,
    recordIds,
    screenshots,
    expectedScenarios,
    completedScenarios: [...completedScenarios].sort(),
    coverage,
    failedRuntimeRequests,
    technicalVerdict: expectedScenarios.every((scenario) => completedScenarios.has(scenario))
      && coverageComplete
      && failedRuntimeRequests.length === 0
      ? 'pass'
      : 'incomplete',
    productOwnerScreenshotSignOff: 'pending-human-signature',
    dataMigration: 'out-of-scope-development-stage',
    fixtureMode: REUSE_DATA ? 'reuse-clean-seed' : 'self-seeded',
    seedReceipt: REUSE_DATA ? SEED_RECEIPT || null : null,
  };
  writeFileSync(
    path.join(EVIDENCE_DIR, `crm-dashboard-parity-${RUN}.json`),
    `${JSON.stringify(evidence, null, 2)}\n`,
  );
});

test('销售首页从菜单进入、完整布局并精确下钻', async ({ page }) => {
  observeDashboardRequests(page);
  await uiLogin(page);
  const blockIds = EXPECTED_COVERAGE.dashboardTargets
    .filter((target) => target.startsWith('crm_dashboard:'))
    .map((target) => target.split(':')[1]);
  await openDashboardFromMenu(page, 'crm_dashboard', /^销售首页$/, blockIds);
  await expect(page.getByText('聚合销售节奏、快捷入口、计划、待办和经营分析的一体化个人销售首页'))
    .toBeVisible();
  const periodBody = assertOk(await api(
    '/api/datasource/list?datasourceId=nq%3Acrm_sales_period_overview&format=records&maxItems=10',
  ), 'sales period overview data');
  const periodRows = periodBody?.data?.records ?? periodBody?.data?.rows ?? [];
  expect(periodRows).toHaveLength(4);
  const periodByGroup = new Map<string, Record<string, unknown>>(
    periodRows.map((row: Record<string, unknown>) => [String(row.metric_group), row] as const),
  );
  expect([...periodByGroup.keys()].sort()).toEqual([
    'lead', 'open_opportunity', 'opportunity', 'won',
  ]);
  expect(Number(periodByGroup.get('lead')?.year_count)).toBeGreaterThan(0);
  expect(Number(periodByGroup.get('opportunity')?.year_count)).toBeGreaterThan(0);
  expect(Number(periodByGroup.get('open_opportunity')?.year_count)).toBeGreaterThan(0);
  expect(Number(periodByGroup.get('won')?.year_count)).toBeGreaterThan(0);
  if (!REUSE_DATA) {
    expect(Number(periodByGroup.get('lead')?.today_count)).toBeGreaterThanOrEqual(3);
    expect(Number(periodByGroup.get('opportunity')?.today_count)).toBeGreaterThanOrEqual(6);
    expect(Number(periodByGroup.get('open_opportunity')?.today_count)).toBeGreaterThanOrEqual(4);
    expect(Number(periodByGroup.get('won')?.today_count)).toBeGreaterThanOrEqual(1);
    expect(Number(periodByGroup.get('won')?.today_amount)).toBeGreaterThanOrEqual(1_080_000);
  }
  const periodBlock = page.getByTestId('dashboard-block-block_sales_period_overview');
  await expect(periodBlock).toContainText('新增线索');
  await expect(periodBlock).toContainText('新增商机');
  await expect(periodBlock).toContainText('进行中商机');
  await expect(periodBlock).toContainText('赢单');
  await expect(periodBlock).toContainText('今日数量');
  await expect(periodBlock).toContainText('本年金额');
  await expect(page.getByTestId('dashboard-block-block_sales_plans'))
    .toContainText('确认本周客户跟进节奏');
  await expect(page.getByTestId('dashboard-block-block_sales_inbox'))
    .toContainText('E2E Mention Item');
  await expect(page.getByText('新线索', { exact: true })).toBeVisible();
  await expect(page.getByTestId('dashboard-block-chart_opp_pipeline').locator('canvas'))
    .toBeVisible();
  await expect(page.getByTestId('dashboard-block-chart_lead_source').locator('canvas'))
    .toBeVisible();
  await expect(page.locator('canvas')).toHaveCount(5, { timeout: 25_000 });
  const kpiBody = assertOk(await api(
    '/api/datasource/list?datasourceId=nq%3Acrm_dashboard_kpi&format=records&maxItems=1',
  ), 'dashboard KPI data');
  const kpiRows = kpiBody?.data?.records ?? kpiBody?.data?.rows ?? [];
  const pendingComplaints = Number(kpiRows[0]?.pending_complaints);
  expect(pendingComplaints).toBeGreaterThan(0);
  await expect(page.getByTestId('number-card-pending_complaints-drilldown'))
    .toContainText(String(pendingComplaints));
  const activityBody = assertOk(await api(
    '/api/datasource/list?datasourceId=nq%3Acrm_recent_activities&format=records&maxItems=20',
  ), 'recent activity dashboard data');
  const activityRows = activityBody?.data?.records ?? activityBody?.data?.rows ?? [];
  expect(activityRows.some((row: Record<string, unknown>) => row.crm_ar_object_type === 'account'))
    .toBeTruthy();
  await expect(page.getByTestId('dashboard-block-block_recent_activities')).toContainText('客户');

  const shortcuts = [
    { label: '新建线索', path: '/p/crm_lead_common/new', action: 'new_lead' },
    { label: '新建客户', path: '/p/crm_account_common/new', action: 'new_account' },
    { label: '新建商机', path: '/p/crm_opportunity_common/new', action: 'new_opportunity' },
    { label: '新建计划', path: '/p/crm_activity_common/new', action: 'new_activity' },
    { label: '我的任务', path: '/p/crm_my_tasks', action: 'open_my_tasks' },
    { label: '活动与服务', path: '/p/c/crm_activity_service_desk', action: 'open_activity_service' },
  ];
  for (const shortcut of shortcuts) {
    const link = page.getByTestId('dashboard-block-block_sales_shortcuts')
      .getByRole('link', { name: shortcut.label });
    await expect(link).toHaveAttribute('href', shortcut.path);
    await link.click();
    await expect(page).toHaveURL((url) => url.pathname === shortcut.path);
    cover('uiActions', `crm_dashboard:block_sales_shortcuts:${shortcut.action}`);
    await openDashboardFromMenu(page, 'crm_dashboard', /^销售首页$/, blockIds);
  }

  await expect(periodBlock).toContainText('新增线索', { timeout: 25_000 });
  await expect(page.getByTestId('dashboard-block-block_sales_plans'))
    .toContainText('确认本周客户跟进节奏', { timeout: 25_000 });
  await expect(page.getByTestId('dashboard-block-block_sales_inbox'))
    .toContainText('E2E Mention Item', { timeout: 25_000 });
  await expect(page.getByTestId('dashboard-block-chart_opp_pipeline').locator('canvas'))
    .toBeVisible({ timeout: 25_000 });
  await assertDashboardLayout(page, blockIds);
  await assertNoTechnicalLeak(page);
  await screenshotDashboard(page, `${RUN}-crm-dashboard`);

  await page.getByTestId('number-card-new_leads-drilldown').click();
  await expect(page).toHaveURL((url) => {
    if (url.pathname !== '/p/crm_lead_common') return false;
    const filters = url.searchParams.get('filters') || '';
    try {
      const decoded = JSON.parse(Buffer.from(filters, 'base64url').toString('utf8'));
      return Array.isArray(decoded)
        && decoded.some((filter) => filter.fieldCode === 'crm_lead_status'
          && filter.operator === 'eq'
          && filter.value === 'new');
    } catch {
      return false;
    }
  });
  cover('uiActions', 'crm_dashboard:block_kpi_cards:new_leads_drilldown');
  completedScenarios.add('crm-dashboard-journey');
});

test('销售预测从菜单进入、负责人业务化并覆盖完整预测面', async ({ page }) => {
  observeDashboardRequests(page);
  await uiLogin(page);
  const blockIds = EXPECTED_COVERAGE.dashboardTargets
    .filter((target) => target.startsWith('crm_sales_forecast:'))
    .map((target) => target.split(':')[1]);
  await openDashboardFromMenu(page, 'crm_sales_forecast', /^销售预测$/, blockIds);
  await expect(page.getByText('按阶段、月份、负责人和预测类别统一核对管道与加权预测'))
    .toBeVisible();
  await expect(page.getByText('管道总额', { exact: true })).toBeVisible();
  await expect(page.getByTestId('dashboard-block-chart_forecast_by_stage').locator('canvas'))
    .toBeVisible();
  await expect(page.getByTestId('dashboard-block-chart_forecast_by_owner').locator('canvas'))
    .toBeVisible();
  const ownerBody = assertOk(await api(
    '/api/datasource/list?datasourceId=nq%3Acrm_sales_forecast_by_owner&format=records&maxItems=50',
  ), 'forecast owner dashboard data');
  const ownerRows = ownerBody?.data?.records ?? ownerBody?.data?.rows ?? [];
  expect(ownerRows.length, 'forecast owner query must expose business rows').toBeGreaterThan(0);
  expect(ownerRows.some((row: Record<string, unknown>) => row.owner_name === expectedOwnerDisplayName))
    .toBeTruthy();
  for (const row of ownerRows) {
    expect(String(row.owner_name || '')).not.toMatch(/\bcrm_[a-z0-9_-]+\b/i);
    expect(String(row.owner_name || '')).not.toMatch(/\b[0-9A-HJKMNP-TV-Z]{26}\b/i);
  }
  await expect(page.locator('canvas')).toHaveCount(4, { timeout: 25_000 });
  const stageDetail = await page.getByTestId('dashboard-block-table_stage_detail').innerText();
  expect(stageDetail).toContain('资格确认');
  expect(stageDetail).not.toMatch(/\b(discovery|qualification|proposal|negotiation)\b/);
  const categoryDetail = await page.getByTestId('dashboard-block-table_forecast_by_category_detail')
    .innerText();
  expect(categoryDetail).toContain('承诺');
  expect(categoryDetail).not.toMatch(/\b(commit|best_case|pipeline)\b/);
  const openDetail = await page.getByTestId('dashboard-block-table_open_opportunities').innerText();
  expect(openDetail).toMatch(/2026\/\d{2}\/\d{2}/);
  expect(openDetail).not.toMatch(/\b(discovery|qualification|proposal|negotiation)\b/);
  await assertDashboardLayout(page, blockIds);
  await assertNoTechnicalLeak(page);
  await screenshotDashboard(page, `${RUN}-crm-sales-forecast`);

  await expect.poll(() => completedCoverage.queries.size, { timeout: 25_000 })
    .toBe(EXPECTED_COVERAGE.queries.length);
  expect(failedRuntimeRequests).toEqual([]);
  completedScenarios.add('sales-forecast-journey');
});
