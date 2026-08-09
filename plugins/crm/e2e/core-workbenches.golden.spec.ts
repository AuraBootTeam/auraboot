import { expect, test, type Page, type TestInfo } from '@playwright/test';
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';

const BASE = process.env.PLAYWRIGHT_BASE_URL || 'http://127.0.0.1:5157';
const BE = process.env.BACKEND_URL || 'http://127.0.0.1:6457';
const RUN = process.env.CORE_WB_RUN_ID || `core-wb-${Date.now()}`;
const EVIDENCE_DIR =
  process.env.CORE_WB_EVIDENCE_DIR || mkdtempSync(path.join(tmpdir(), 'crm-core-workbenches-'));
const ADMIN_EMAIL = 'admin@auraboot.com';
const PASSWORD = 'Test2026x';

type WorkbenchKey =
  | 'customer-360'
  | 'lead-desk'
  | 'opportunity-workspace'
  | 'forecast-cockpit'
  | 'activity-service-desk';

const EXPECTED_SCENARIOS: WorkbenchKey[] = [
  'customer-360',
  'lead-desk',
  'opportunity-workspace',
  'forecast-cockpit',
  'activity-service-desk',
];

const ids = {
  account: '',
  lead: '',
  opportunity: '',
  forecast: '',
  task: '',
  complaint: '',
};
let adminJwt = '';
const screenshots: string[] = [];
const completedScenarios = new Set<WorkbenchKey>();

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

async function loginApi(): Promise<string> {
  const result = await api('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify({ email: ADMIN_EMAIL, password: PASSWORD }),
  }, '');
  const body = assertOk(result, 'API login');
  const jwt = findValue(body?.data, ['jwt']);
  expect(jwt).toBeTruthy();
  return String(jwt);
}

async function executeCreate(code: string, payload: Record<string, unknown>): Promise<string> {
  const result = await api(`/api/meta/commands/execute/${code}`, {
    method: 'POST',
    body: JSON.stringify({ payload, operationType: 'create' }),
  });
  const body = assertOk(result, code);
  const pid = findValue(body?.data?.data ?? body?.data, [
    'recordId',
    'recordPid',
    'publicRecordId',
    'pid',
  ]);
  expect(pid, `${code} must return a public record id`).toBeTruthy();
  return String(pid);
}

async function getRecord(model: string, pid: string): Promise<Record<string, any>> {
  const body = assertOk(await api(`/api/dynamic/${model}/${encodeURIComponent(pid)}`),
    `read ${model}/${pid}`);
  return body.data;
}

async function seedCoreWorkbenchData(): Promise<void> {
  const closeAt = new Date(Date.now() + 14 * 24 * 60 * 60 * 1000).toISOString();
  const today = new Date().toISOString().slice(0, 10);
  ids.account = await executeCreate('crm:create_account', {
    crm_acc_name: `${RUN} Customer`,
    crm_acc_industry: 'technology',
    crm_acc_status: 'active',
    crm_acc_rating: 'A',
  });
  ids.lead = await executeCreate('crm:create_lead', {
    crm_lead_company: `${RUN} Lead`,
    crm_lead_contact_name: 'Core Workbench Contact',
    crm_lead_source: 'referral',
    crm_lead_score: 88,
  });
  ids.opportunity = await executeCreate('crm:create_opportunity', {
    crm_opp_name: `${RUN} Opportunity`,
    crm_opp_account_id: ids.account,
    crm_opp_currency_code: 'CNY',
    crm_opp_expected_amount: 280000,
    crm_opp_expected_close_date: closeAt,
    crm_opp_probability: 35,
    crm_opp_owner: ADMIN_EMAIL,
    crm_opp_forecast_category: 'pipeline',
  });
  ids.forecast = await executeCreate('crm:create_forecast', {
    crm_fcst_period: '2026-08',
    crm_fcst_owner: RUN,
    crm_fcst_commit_amount: 100000,
    crm_fcst_best_case_amount: 200000,
    crm_fcst_pipeline_amount: 300000,
    crm_fcst_notes: RUN,
  });
  ids.task = await executeCreate('crm:create_activity', {
    crm_act_type: 'task',
    crm_act_subject: `${RUN} Follow-up`,
    crm_act_content: 'Core workbench browser evidence',
    crm_act_status: 'open',
    crm_act_priority: 'high',
    crm_act_due_date: today,
    crm_act_related_model: 'crm_account_common',
    crm_act_related_id: ids.account,
  });
  ids.complaint = await executeCreate('crm:create_complaint', {
    crm_cmp_account_id: ids.account,
    crm_cmp_type: 'quality',
    crm_cmp_severity: 'high',
    crm_cmp_date: new Date().toISOString(),
    crm_cmp_description: `${RUN} service recovery`,
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

async function gotoWorkbench(
  page: Page,
  pageKey: string,
  statsQuery: string,
  title: RegExp,
): Promise<void> {
  for (let attempt = 0; attempt < 2; attempt += 1) {
    try {
      await page.goto(`${BASE}/dashboards`, { waitUntil: 'domcontentloaded' });
      break;
    } catch (error) {
      if (attempt > 0 || !String(error).includes('ERR_ABORTED')) throw error;
    }
  }
  const nav = page.locator('nav, aside, [role="navigation"]').first();
  await expect(nav).toBeVisible({ timeout: 15_000 });
  const link = nav.locator(`a[href="/p/c/${pageKey}"]`).first();
  if (!(await link.isVisible().catch(() => false))) {
    await nav.getByRole('button', { name: /客户关系管理|CRM/i }).first().click();
  }
  await expect(link, `${pageKey} must be a visible CRM menu entry`).toBeVisible({ timeout: 10_000 });
  const statsResponsePromise = page.waitForResponse((response) =>
    response.request().method() === 'GET'
      && response.url().includes('/api/datasource/list')
      && new URL(response.url()).searchParams.get('datasourceId') === `nq:${statsQuery}`,
  { timeout: 20_000 });
  await link.click();
  const statsResponse = await statsResponsePromise;
  expect(statsResponse.ok(), `${statsQuery} returned HTTP ${statsResponse.status()}`).toBeTruthy();
  await expect(page).toHaveURL(new RegExp(`/p/c/${pageKey}(?:[?#].*)?$`), { timeout: 15_000 });
  await expect(page.locator('main, [role="main"]').first().getByText(title).first())
    .toBeVisible({ timeout: 20_000 });
}

async function searchNamedQueryQueue(
  page: Page,
  fieldCode: string,
  queueQuery: string,
  keyword: string,
): Promise<void> {
  const input = page.getByTestId(`field-${fieldCode}`).locator('input');
  await expect(input).toBeVisible({ timeout: 15_000 });
  await input.fill(keyword);
  const responsePromise = page.waitForResponse((response) =>
    response.request().method() === 'GET'
      && response.url().includes('/api/datasource/list')
      && new URL(response.url()).searchParams.get('datasourceId') === `nq:${queueQuery}`,
  { timeout: 15_000 });
  await page.getByTestId('filter-btn-search').click();
  const response = await responsePromise;
  expect(response.ok(), `${queueQuery} search returned HTTP ${response.status()}`).toBeTruthy();
}

async function searchForecast(page: Page, keyword: string): Promise<void> {
  const input = page.getByTestId('field-crm_fcst_period').locator('input');
  await expect(input).toBeVisible({ timeout: 15_000 });
  await input.fill(keyword);
  const responsePromise = page.waitForResponse((response) =>
    response.request().method() === 'GET'
      && response.url().includes('/api/dynamic/crm_forecast_submission/list'),
  { timeout: 15_000 });
  await page.getByTestId('filter-btn-search').click();
  const response = await responsePromise;
  expect(response.ok(), `forecast search returned HTTP ${response.status()}`).toBeTruthy();
}

async function selectRow(page: Page, text: string): Promise<void> {
  const row = page.locator('tr').filter({ hasText: text }).first();
  await expect(row, `row ${text} must be visible`).toBeVisible({ timeout: 20_000 });
  const targetCell = row.locator('td').filter({ hasText: text }).first();
  await expect(targetCell, `the primary cell for ${text} must be visible`).toBeVisible();
  await targetCell.click();
}

async function executeWorkbenchAction(
  page: Page,
  actionCode: string,
  commandCode: string,
  confirm = false,
): Promise<void> {
  const responsePromise = page.waitForResponse((response) =>
    response.request().method() === 'POST'
      && response.url().includes(`/api/meta/commands/execute/${commandCode}`),
  { timeout: 20_000 });
  await page.getByTestId(`workbench-action-${actionCode}`).click();
  if (confirm) {
    await expect(page.getByTestId('confirm-dialog')).toBeVisible();
    await page.getByTestId('confirm-ok').click();
  }
  const response = await responsePromise;
  const body = await response.json().catch(() => ({}));
  expect(response.ok(), `${commandCode}: HTTP ${response.status()} ${JSON.stringify(body)}`)
    .toBeTruthy();
  expect(String(body?.code), JSON.stringify(body)).toBe('0');
}

async function assertNoRawCodes(page: Page): Promise<void> {
  const body = await page.locator('main, [role="main"]').first().innerText();
  expect(body).not.toMatch(/\bcrm_(?:acc|lead|opp|fcst|act|cmp)_[a-z_]+\b/);
  expect(body).not.toMatch(/\b\d{18,20}\b/);
  expect(body).not.toMatch(/加载失败|Page not found/i);
}

async function assertMappedSummaryValue(
  page: Page,
  bannerId: string,
  expectedLabel: RegExp,
  rawValues: RegExp,
): Promise<void> {
  const banner = page.getByTestId(bannerId);
  await expect(banner).toContainText(expectedLabel);
  await expect(banner).not.toContainText(rawValues);
}

async function shot(page: Page, testInfo: TestInfo, name: string): Promise<void> {
  const output = testInfo.outputPath(name);
  mkdirSync(path.dirname(output), { recursive: true });
  await page.screenshot({ path: output, fullPage: true });
  await testInfo.attach(name, { path: output, contentType: 'image/png' });
  screenshots.push(output);
}

async function resetPageShellScroll(page: Page): Promise<void> {
  await page.evaluate(() => {
    window.scrollTo(0, 0);
    document.documentElement.scrollTop = 0;
    document.body.scrollTop = 0;
    let candidate: HTMLElement | null = document.querySelector('main, [role="main"]');
    while (candidate) {
      candidate.scrollTo(0, 0);
      candidate = candidate.parentElement;
    }
  });
}

async function dualViewportShots(page: Page, testInfo: TestInfo, stem: string): Promise<void> {
  await resetPageShellScroll(page);
  await expect.poll(async () => page.evaluate(() => ({
    clientWidth: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }))).toEqual({ clientWidth: 1280, scrollWidth: 1280 });
  await shot(page, testInfo, `${stem}-desktop.png`);
  await page.setViewportSize({ width: 960, height: 900 });
  await resetPageShellScroll(page);

  const nav = page.locator('nav, aside, [role="navigation"]').first();
  await expect.poll(async () => (await nav.boundingBox())?.x ?? 0).toBeLessThan(-200);
  await expect.poll(async () => page.evaluate(() => ({
    clientWidth: document.documentElement.clientWidth,
    scrollWidth: document.documentElement.scrollWidth,
  }))).toEqual({ clientWidth: 960, scrollWidth: 960 });
  const main = page.locator('main, [role="main"]').first();
  await expect.poll(async () => {
    const box = await main.boundingBox();
    return box ? { left: Math.round(box.x), right: Math.round(box.x + box.width) } : null;
  }).toEqual({ left: 0, right: 960 });
  await shot(page, testInfo, `${stem}-compact.png`);
  await page.setViewportSize({ width: 1280, height: 720 });
}

test.describe.configure({ mode: 'serial' });

test.beforeAll(async () => {
  mkdirSync(EVIDENCE_DIR, { recursive: true });
  adminJwt = await loginApi();
  await seedCoreWorkbenchData();
});

test.afterAll(() => {
  const evidence = {
    schemaVersion: 1,
    runId: RUN,
    baseUrl: BASE,
    backendUrl: BE,
    recordIds: ids,
    screenshots,
    completedScenarios: [...completedScenarios].sort(),
    expectedScenarios: EXPECTED_SCENARIOS,
    verdict: EXPECTED_SCENARIOS.every((scenario) => completedScenarios.has(scenario))
      ? 'pass'
      : 'incomplete',
  };
  writeFileSync(path.join(EVIDENCE_DIR, `crm-core-workbenches-${RUN}.json`),
    `${JSON.stringify(evidence, null, 2)}\n`);
});

test('Customer 360 exposes a prioritized relationship context and opens the selected record',
  async ({ page }, testInfo) => {
    await uiLogin(page);
    await gotoWorkbench(page, 'crm_customer_360_workbench', 'crm_customer_360_stats', /客户 360|Customer 360/);
    await searchNamedQueryQueue(page, 'crm_acc_name', 'crm_customer_360_queue', RUN);
    await selectRow(page, `${RUN} Customer`);
    await expect(page.getByTestId('status-banner-crm_customer_attention')).toBeVisible();
    for (const metric of ['all_customers', 'active_customers', 'pipeline_customers', 'service_risk']) {
      await expect(page.getByTestId(`metric-strip-item-${metric}`)).toBeVisible();
    }
    await expect(page.getByTestId('workbench-action-open_customer_record')).toBeVisible();
    await assertNoRawCodes(page);
    await dualViewportShots(page, testInfo, 'crm-customer-360');
    await page.getByTestId('workbench-action-open_customer_record').click();
    await expect(page).toHaveURL(new RegExp(`/p/crm_account_common/view/${ids.account}`));
    await expect(page.getByText(`${RUN} Customer`, { exact: true }).first()).toBeVisible();
    await page.goBack({ waitUntil: 'domcontentloaded' });
    await expect(page).toHaveURL(/\/p\/c\/crm_customer_360_workbench/);
    completedScenarios.add('customer-360');
  });

test('Lead Desk executes the next valid lifecycle action and persists the new status',
  async ({ page }, testInfo) => {
    await uiLogin(page);
    await gotoWorkbench(page, 'crm_lead_desk_workbench', 'crm_lead_desk_stats', /线索工作台|Lead Desk/);
    await searchNamedQueryQueue(page, 'crm_lead_company', 'crm_lead_desk_queue', RUN);
    await selectRow(page, `${RUN} Lead`);
    await expect(page.getByTestId('status-banner-crm_lead_next_action_banner')).toBeVisible();
    await expect(page.getByTestId('workbench-action-contact_lead')).toBeVisible();
    await assertMappedSummaryValue(
      page,
      'status-banner-crm_lead_next_action_banner',
      /新建|New/,
      /\b(?:new|contacted|qualified|converted|lost)\b/,
    );
    await assertNoRawCodes(page);
    await dualViewportShots(page, testInfo, 'crm-lead-desk');
    await executeWorkbenchAction(page, 'contact_lead', 'crm:contact_lead');
    await expect.poll(async () => (await getRecord('crm_lead_common', ids.lead)).crm_lead_status)
      .toBe('contacted');
    completedScenarios.add('lead-desk');
  });

test('Opportunity Workspace advances a selected deal and keeps the decision context visible',
  async ({ page }, testInfo) => {
    await uiLogin(page);
    await gotoWorkbench(
      page,
      'crm_opportunity_workspace',
      'crm_opportunity_workspace_stats',
      /商机工作台|Opportunity Workspace/,
    );
    await searchNamedQueryQueue(
      page,
      'crm_opp_name',
      'crm_opportunity_workspace_queue',
      RUN,
    );
    await selectRow(page, `${RUN} Opportunity`);
    await expect(page.getByTestId('status-banner-crm_opportunity_attention')).toBeVisible();
    await expect(page.getByTestId('workbench-action-qualify_opportunity')).toBeVisible();
    await assertMappedSummaryValue(
      page,
      'status-banner-crm_opportunity_attention',
      /发现|Discovery/,
      /\b(?:discovery|qualification|proposal|negotiation|closed_won|closed_lost)\b/,
    );
    await assertNoRawCodes(page);
    await dualViewportShots(page, testInfo, 'crm-opportunity-workspace');
    await executeWorkbenchAction(page, 'qualify_opportunity', 'crm:qualify_opportunity');
    await expect.poll(async () =>
      (await getRecord('crm_opportunity_common', ids.opportunity)).crm_opp_stage)
      .toBe('qualification');
    completedScenarios.add('opportunity-workspace');
  });

test('Forecast Cockpit submits a real forecast and shows both submission and team views',
  async ({ page }, testInfo) => {
    await uiLogin(page);
    await gotoWorkbench(page, 'crm_forecast_cockpit', 'crm_forecast_cockpit_stats', /预测驾驶舱|Forecast Cockpit/);
    await searchForecast(page, RUN);
    await selectRow(page, RUN);
    await expect(page.getByTestId('status-banner-crm_forecast_status')).toBeVisible();
    await expect(page.getByTestId('workbench-action-submit_forecast')).toBeVisible();
    await expect(page.getByText(/团队偏差|Team Rollup/).first()).toBeVisible();
    await assertNoRawCodes(page);
    await dualViewportShots(page, testInfo, 'crm-forecast-cockpit');
    await executeWorkbenchAction(page, 'submit_forecast', 'crm:submit_forecast', true);
    await expect.poll(async () =>
      (await getRecord('crm_forecast_submission', ids.forecast)).crm_fcst_status)
      .toBe('submitted');
    completedScenarios.add('forecast-cockpit');
  });

test('Activity & Service Desk drives both task and complaint recovery actions',
  async ({ page }, testInfo) => {
    await uiLogin(page);
    await gotoWorkbench(
      page,
      'crm_activity_service_desk',
      'crm_activity_service_stats',
      /活动与服务工作台|Activity & Service Desk/,
    );
    await searchNamedQueryQueue(page, 'item_title', 'crm_activity_service_queue', RUN);
    await selectRow(page, `${RUN} Follow-up`);
    await expect(page.getByTestId('status-banner-crm_activity_service_attention')).toBeVisible();
    await expect(page.getByTestId('workbench-action-start_task')).toBeVisible();
    await assertMappedSummaryValue(
      page,
      'status-banner-crm_activity_service_attention',
      /任务|Task/,
      /\b(?:task|complaint|open|in_progress|done|cancelled|investigating|resolved|closed)\b/,
    );
    const taskRow = page.locator('tr').filter({ hasText: `${RUN} Follow-up` }).first();
    await expect(taskRow).toContainText(/高|High/);
    await expect(taskRow).not.toContainText(/\bhigh\b/);
    await expect(taskRow).toContainText(/2026\/8\/9/);
    await assertNoRawCodes(page);
    await dualViewportShots(page, testInfo, 'crm-activity-service-desk');
    await executeWorkbenchAction(page, 'start_task', 'crm:start_task');
    await expect.poll(async () => (await getRecord('crm_activity_common', ids.task)).crm_act_status)
      .toBe('in_progress');

    await selectRow(page, `${RUN} service recovery`);
    await expect(page.getByTestId('workbench-action-investigate_complaint')).toBeVisible();
    await assertMappedSummaryValue(
      page,
      'status-banner-crm_activity_service_attention',
      /投诉|Complaint/,
      /\b(?:task|complaint|open|in_progress|done|cancelled|investigating|resolved|closed)\b/,
    );
    await executeWorkbenchAction(page, 'investigate_complaint', 'crm:investigate_complaint');
    await expect.poll(async () =>
      (await getRecord('crm_complaint', ids.complaint)).crm_cmp_status)
      .toBe('investigating');
    completedScenarios.add('activity-service-desk');
  });
