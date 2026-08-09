import { expect, test, type Page, type TestInfo } from '@playwright/test';
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';

const BASE = process.env.PLAYWRIGHT_BASE_URL || 'http://127.0.0.1:5157';
const BE = process.env.BACKEND_URL || 'http://127.0.0.1:6457';
const RUN = process.env.CORE_WB_RUN_ID || `core-wb-${Date.now()}`;
const FORECAST_PERIOD = `P${Date.now()}`;
const EVIDENCE_DIR =
  process.env.CORE_WB_EVIDENCE_DIR || mkdtempSync(path.join(tmpdir(), 'crm-core-workbenches-'));
const ADMIN_EMAIL = 'admin@auraboot.com';
const PASSWORD = 'Test2026x';
const SALES_EMAIL = `crm-sales-${Date.now()}@e2e.local`;
const SERVICE_EMAIL = `crm-service-${Date.now()}@e2e.local`;
const VIEWER_EMAIL = `crm-viewer-${Date.now()}@e2e.local`;

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

const EXPECTED_ACTIONS = [
  'open_customer_record',
  'create_customer',
  'open_lead_record',
  'create_lead',
  'contact_lead',
  'qualify_lead',
  'convert_lead',
  'lose_lead',
  'open_opportunity_record',
  'create_opportunity',
  'qualify_opportunity',
  'advance_to_proposal',
  'advance_to_negotiation',
  'win_opportunity',
  'lose_opportunity',
  'open_forecast_record',
  'create_forecast',
  'submit_forecast',
  'open_task_record',
  'open_complaint_record',
  'create_task',
  'start_task',
  'complete_task',
  'investigate_complaint',
  'resolve_complaint',
  'close_complaint',
] as const;

const ids = {
  account: '',
  lead: '',
  leadQualify: '',
  leadConvert: '',
  leadLose: '',
  opportunity: '',
  opportunityProposal: '',
  opportunityNegotiation: '',
  opportunityWin: '',
  opportunityLose: '',
  forecast: '',
  task: '',
  taskComplete: '',
  complaint: '',
  complaintResolve: '',
  complaintClose: '',
};
let adminJwt = '';
let salesJwt = '';
let serviceJwt = '';
let viewerJwt = '';
let salesUserPid = '';
let salesLeadPid = '';
let adminControlLeadPid = '';
let serviceComplaintPid = '';
const screenshots: string[] = [];
const completedScenarios = new Set<WorkbenchKey>();
const completedActions = new Set<(typeof EXPECTED_ACTIONS)[number]>();

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

async function loginApi(email = ADMIN_EMAIL): Promise<string> {
  const result = await api('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify({ email, password: PASSWORD }),
  }, '');
  const body = assertOk(result, `API login ${email}`);
  const jwt = findValue(body?.data, ['jwt']);
  expect(jwt).toBeTruthy();
  return String(jwt);
}

async function executeCreate(
  code: string,
  payload: Record<string, unknown>,
  jwt = adminJwt,
): Promise<string> {
  const result = await api(`/api/meta/commands/execute/${code}`, {
    method: 'POST',
    body: JSON.stringify({ payload, operationType: 'create' }),
  }, jwt);
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

async function executeSetupTransition(
  code: string,
  targetRecordPid: string,
  payload: Record<string, unknown> = {},
): Promise<void> {
  assertOk(await api(`/api/meta/commands/execute/${code}`, {
    method: 'POST',
    body: JSON.stringify({ payload, targetRecordPid, operationType: 'update' }),
  }), `setup ${code}`);
}

async function createLead(label: string): Promise<string> {
  return executeCreate('crm:create_lead', {
    crm_lead_company: `${RUN} ${label}`,
    crm_lead_contact_name: `${label} Contact`,
    crm_lead_source: 'referral',
    crm_lead_score: 88,
  });
}

async function createOpportunity(label: string, closeAt: string): Promise<string> {
  return executeCreate('crm:create_opportunity', {
    crm_opp_name: `${RUN} ${label}`,
    crm_opp_account_id: ids.account,
    crm_opp_currency_code: 'CNY',
    crm_opp_expected_amount: 280000,
    crm_opp_expected_close_date: closeAt,
    crm_opp_probability: 35,
    crm_opp_forecast_category: 'pipeline',
  });
}

async function createTask(label: string, today: string): Promise<string> {
  return executeCreate('crm:create_activity', {
    crm_act_type: 'task',
    crm_act_subject: `${RUN} ${label}`,
    crm_act_content: 'Core workbench browser evidence',
    crm_act_status: 'open',
    crm_act_priority: 'high',
    crm_act_due_date: today,
    crm_act_related_model: 'crm_account_common',
    crm_act_related_id: ids.account,
  });
}

async function createComplaint(label: string): Promise<string> {
  return executeCreate('crm:create_complaint', {
    crm_cmp_account_id: ids.account,
    crm_cmp_type: 'quality',
    crm_cmp_severity: 'high',
    crm_cmp_date: new Date().toISOString(),
    crm_cmp_description: `${RUN} ${label}`,
  });
}

async function getRecord(model: string, pid: string): Promise<Record<string, any>> {
  const body = assertOk(await api(`/api/dynamic/${model}/${encodeURIComponent(pid)}`),
    `read ${model}/${pid}`);
  return body.data;
}

async function provisionRoleUser(email: string, roleCode: string): Promise<string> {
  const body = assertOk(await api('/api/admin/users', {
    method: 'POST',
    body: JSON.stringify({
      email,
      displayName: `${roleCode} ${RUN}`.slice(0, 50),
      initialPassword: PASSWORD,
      roleCodes: [roleCode],
      sendInviteEmail: false,
    }),
  }), `provision ${roleCode}`);
  const directPid = findValue(body?.data, ['pid', 'userPid']);
  if (directPid) return String(directPid);

  const searchBody = assertOk(await api(
    `/api/admin/users/search?keyword=${encodeURIComponent(email)}&size=20`,
  ), `resolve ${roleCode} user`);
  const users = searchBody?.data?.content ?? searchBody?.data ?? [];
  const user = Array.isArray(users)
    ? users.find((candidate: any) => candidate?.email === email)
    : undefined;
  expect(user?.pid, `${roleCode} user must expose a public pid`).toBeTruthy();
  return String(user.pid);
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
  ids.lead = await createLead('Lead Contact');
  ids.leadQualify = await createLead('Lead Qualify');
  ids.leadConvert = await createLead('Lead Convert');
  ids.leadLose = await createLead('Lead Lose');
  await executeSetupTransition('crm:qualify_lead', ids.leadConvert);

  ids.opportunity = await createOpportunity('Opportunity Qualify', closeAt);
  ids.opportunityProposal = await createOpportunity('Opportunity Proposal', closeAt);
  ids.opportunityNegotiation = await createOpportunity('Opportunity Negotiation', closeAt);
  ids.opportunityWin = await createOpportunity('Opportunity Win', closeAt);
  ids.opportunityLose = await createOpportunity('Opportunity Lose', closeAt);
  await executeSetupTransition('crm:qualify_opportunity', ids.opportunityProposal);
  await executeSetupTransition('crm:qualify_opportunity', ids.opportunityNegotiation);
  await executeSetupTransition('crm:advance_opp_to_proposal', ids.opportunityNegotiation);
  await executeSetupTransition('crm:qualify_opportunity', ids.opportunityWin);
  await executeSetupTransition('crm:advance_opp_to_proposal', ids.opportunityWin);
  await executeSetupTransition('crm:advance_opp_to_negotiation', ids.opportunityWin);

  ids.forecast = await executeCreate('crm:create_forecast', {
    crm_fcst_period: FORECAST_PERIOD,
    crm_fcst_commit_amount: 100000,
    crm_fcst_best_case_amount: 200000,
    crm_fcst_pipeline_amount: 300000,
    crm_fcst_notes: RUN,
  });
  ids.task = await createTask('Task Start', today);
  ids.taskComplete = await createTask('Task Complete', today);
  ids.complaint = await createComplaint('Complaint Investigate');
  ids.complaintResolve = await createComplaint('Complaint Resolve');
  ids.complaintClose = await createComplaint('Complaint Close');
  await executeSetupTransition('crm:investigate_complaint', ids.complaintResolve);
  await executeSetupTransition('crm:investigate_complaint', ids.complaintClose);
  await executeSetupTransition('crm:resolve_complaint', ids.complaintClose, {
    crm_cmp_root_cause: 'Verified fixture root cause',
    crm_cmp_corrective_action: 'Verified fixture corrective action',
    crm_cmp_resolution_date: new Date().toISOString(),
  });
}

async function uiLogin(page: Page, email = ADMIN_EMAIL): Promise<void> {
  const response = await page.request.post(`${BASE}/login`, {
    form: {
      email,
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

async function assertWorkbenchMenuMatrix(
  page: Page,
  visiblePageKeys: string[],
  hiddenPageKeys: string[],
): Promise<void> {
  const nav = page.locator('nav, aside, [role="navigation"]').first();
  for (const pageKey of visiblePageKeys) {
    await expect(nav.locator(`a[href="/p/c/${pageKey}"]`).first(), `${pageKey} role menu positive`)
      .toBeVisible();
  }
  for (const pageKey of hiddenPageKeys) {
    await expect(nav.locator(`a[href="/p/c/${pageKey}"]`), `${pageKey} role menu negative`)
      .toHaveCount(0);
  }
}

async function expectCommandRejected(
  jwt: string,
  commandCode: string,
  targetRecordPid: string,
): Promise<void> {
  const result = await api(`/api/meta/commands/execute/${commandCode}`, {
    method: 'POST',
    body: JSON.stringify({ payload: {}, targetRecordPid, operationType: 'update' }),
  }, jwt);
  expect(
    !result.response.ok || String(result.body?.code) !== '0',
    `${commandCode} must reject unauthorized execution: HTTP ${result.response.status} ${JSON.stringify(result.body)}`,
  ).toBeTruthy();
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

async function openCreateRouteWithKeyboard(
  page: Page,
  actionCode: (typeof EXPECTED_ACTIONS)[number],
  route: RegExp,
): Promise<void> {
  const action = page.getByTestId(`workbench-action-${actionCode}`);
  await expect(action).toBeVisible();
  await action.focus();
  await expect(action).toBeFocused();
  await action.press('Enter');
  await expect(page).toHaveURL(route, { timeout: 15_000 });
  completedActions.add(actionCode);
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
  salesUserPid = await provisionRoleUser(SALES_EMAIL, 'crm_sales');
  await provisionRoleUser(SERVICE_EMAIL, 'crm_service');
  await provisionRoleUser(VIEWER_EMAIL, 'crm_viewer');
  salesJwt = await loginApi(SALES_EMAIL);
  serviceJwt = await loginApi(SERVICE_EMAIL);
  viewerJwt = await loginApi(VIEWER_EMAIL);
  await seedCoreWorkbenchData();
  salesLeadPid = await executeCreate('crm:create_lead', {
    crm_lead_company: `${RUN} Sales Owned Lead`,
    crm_lead_contact_name: 'Sales Scope Contact',
    crm_lead_source: 'referral',
    crm_lead_score: 91,
    crm_lead_assigned_to: salesUserPid,
  }, salesJwt);
  adminControlLeadPid = await executeCreate('crm:create_lead', {
    crm_lead_company: `${RUN} Admin Control Lead`,
    crm_lead_contact_name: 'Admin Scope Contact',
    crm_lead_source: 'referral',
    crm_lead_score: 91,
  });
  serviceComplaintPid = await executeCreate('crm:create_complaint', {
    crm_cmp_account_id: ids.account,
    crm_cmp_type: 'service',
    crm_cmp_severity: 'medium',
    crm_cmp_date: new Date().toISOString(),
    crm_cmp_description: `${RUN} Service Role Complaint`,
  }, serviceJwt);
});

test.afterAll(() => {
  const evidence = {
    schemaVersion: 1,
    runId: RUN,
    baseUrl: BASE,
    backendUrl: BE,
    recordIds: { ...ids, salesLeadPid, adminControlLeadPid, serviceComplaintPid },
    screenshots,
    completedScenarios: [...completedScenarios].sort(),
    expectedScenarios: EXPECTED_SCENARIOS,
    completedActions: [...completedActions].sort(),
    expectedActions: EXPECTED_ACTIONS,
    verdict: EXPECTED_SCENARIOS.every((scenario) => completedScenarios.has(scenario))
      && EXPECTED_ACTIONS.every((action) => completedActions.has(action))
      ? 'pass'
      : 'incomplete',
  };
  writeFileSync(path.join(EVIDENCE_DIR, `crm-core-workbenches-${RUN}.json`),
    `${JSON.stringify(evidence, null, 2)}\n`);
});

test('all eleven workbench navigation actions are menu-driven and keyboard reachable',
  async ({ page }) => {
    await uiLogin(page);

    await gotoWorkbench(page, 'crm_customer_360_workbench', 'crm_customer_360_stats', /客户 360|Customer 360/);
    await searchNamedQueryQueue(page, 'crm_acc_name', 'crm_customer_360_queue', RUN);
    await selectRow(page, `${RUN} Customer`);
    await page.getByTestId('workbench-action-open_customer_record').click();
    await expect(page).toHaveURL(new RegExp(`/p/crm_account_common/view/${ids.account}`));
    completedActions.add('open_customer_record');
    await gotoWorkbench(page, 'crm_customer_360_workbench', 'crm_customer_360_stats', /客户 360|Customer 360/);
    await openCreateRouteWithKeyboard(page, 'create_customer', /\/p\/crm_account_common\/new$/);

    await gotoWorkbench(page, 'crm_lead_desk_workbench', 'crm_lead_desk_stats', /线索工作台|Lead Desk/);
    await searchNamedQueryQueue(page, 'crm_lead_company', 'crm_lead_desk_queue', RUN);
    await selectRow(page, `${RUN} Lead Contact`);
    await page.getByTestId('workbench-action-open_lead_record').click();
    await expect(page).toHaveURL(new RegExp(`/p/crm_lead_common/view/${ids.lead}`));
    completedActions.add('open_lead_record');
    await gotoWorkbench(page, 'crm_lead_desk_workbench', 'crm_lead_desk_stats', /线索工作台|Lead Desk/);
    await openCreateRouteWithKeyboard(page, 'create_lead', /\/p\/crm_lead_common\/new$/);

    await gotoWorkbench(
      page,
      'crm_opportunity_workspace',
      'crm_opportunity_workspace_stats',
      /商机工作台|Opportunity Workspace/,
    );
    await searchNamedQueryQueue(page, 'crm_opp_name', 'crm_opportunity_workspace_queue', RUN);
    await selectRow(page, `${RUN} Opportunity Qualify`);
    await page.getByTestId('workbench-action-open_opportunity_record').click();
    await expect(page).toHaveURL(new RegExp(`/p/crm_opportunity_common/view/${ids.opportunity}`));
    completedActions.add('open_opportunity_record');
    await gotoWorkbench(
      page,
      'crm_opportunity_workspace',
      'crm_opportunity_workspace_stats',
      /商机工作台|Opportunity Workspace/,
    );
    await openCreateRouteWithKeyboard(page, 'create_opportunity', /\/p\/crm_opportunity_common\/new$/);

    await gotoWorkbench(page, 'crm_forecast_cockpit', 'crm_forecast_cockpit_stats', /预测驾驶舱|Forecast Cockpit/);
    await searchForecast(page, FORECAST_PERIOD);
    await selectRow(page, FORECAST_PERIOD);
    await page.getByTestId('workbench-action-open_forecast_record').click();
    await expect(page).toHaveURL(new RegExp(`/p/crm_forecast_submission/view/${ids.forecast}`));
    completedActions.add('open_forecast_record');
    await gotoWorkbench(page, 'crm_forecast_cockpit', 'crm_forecast_cockpit_stats', /预测驾驶舱|Forecast Cockpit/);
    await openCreateRouteWithKeyboard(page, 'create_forecast', /\/p\/crm_forecast_submission\/new$/);

    await gotoWorkbench(
      page,
      'crm_activity_service_desk',
      'crm_activity_service_stats',
      /活动与服务工作台|Activity & Service Desk/,
    );
    await searchNamedQueryQueue(page, 'item_title', 'crm_activity_service_queue', RUN);
    await selectRow(page, `${RUN} Task Start`);
    await page.getByTestId('workbench-action-open_task_record').click();
    await expect(page).toHaveURL(new RegExp(`/p/crm_activity_common/view/${ids.task}`));
    completedActions.add('open_task_record');

    await gotoWorkbench(
      page,
      'crm_activity_service_desk',
      'crm_activity_service_stats',
      /活动与服务工作台|Activity & Service Desk/,
    );
    await searchNamedQueryQueue(page, 'item_title', 'crm_activity_service_queue', RUN);
    await selectRow(page, `${RUN} Complaint Investigate`);
    await page.getByTestId('workbench-action-open_complaint_record').click();
    await expect(page).toHaveURL(new RegExp(`/p/crm_complaint/view/${ids.complaint}`));
    completedActions.add('open_complaint_record');

    await gotoWorkbench(
      page,
      'crm_activity_service_desk',
      'crm_activity_service_stats',
      /活动与服务工作台|Activity & Service Desk/,
    );
    await openCreateRouteWithKeyboard(page, 'create_task', /\/p\/crm_activity_common\/new$/);
  });

test('formal roles enforce menu, query, action, and self-scope boundaries on the live stack',
  async ({ browser }, testInfo) => {
    const viewerContext = await browser.newContext();
    const viewerPage = await viewerContext.newPage();
    try {
      await uiLogin(viewerPage, VIEWER_EMAIL);
      await gotoWorkbench(
        viewerPage,
        'crm_customer_360_workbench',
        'crm_customer_360_stats',
        /客户 360|Customer 360/,
      );
      await assertWorkbenchMenuMatrix(viewerPage, [
        'crm_customer_360_workbench',
        'crm_lead_desk_workbench',
        'crm_opportunity_workspace',
        'crm_forecast_cockpit',
        'crm_activity_service_desk',
      ], []);
      await searchNamedQueryQueue(
        viewerPage,
        'crm_acc_name',
        'crm_customer_360_queue',
        RUN,
      );
      await selectRow(viewerPage, `${RUN} Customer`);
      await expect(viewerPage.getByTestId('workbench-action-open_customer_record')).toBeVisible();
      await expect(viewerPage.getByTestId('workbench-action-create_customer')).toHaveCount(0);

      await gotoWorkbench(viewerPage, 'crm_lead_desk_workbench', 'crm_lead_desk_stats', /线索工作台|Lead Desk/);
      await searchNamedQueryQueue(viewerPage, 'crm_lead_company', 'crm_lead_desk_queue', RUN);
      await selectRow(viewerPage, `${RUN} Lead Contact`);
      await expect(viewerPage.getByTestId('workbench-action-open_lead_record')).toBeVisible();
      for (const action of ['create_lead', 'contact_lead', 'qualify_lead', 'lose_lead']) {
        await expect(viewerPage.getByTestId(`workbench-action-${action}`)).toHaveCount(0);
      }

      await gotoWorkbench(
        viewerPage,
        'crm_opportunity_workspace',
        'crm_opportunity_workspace_stats',
        /商机工作台|Opportunity Workspace/,
      );
      await searchNamedQueryQueue(
        viewerPage,
        'crm_opp_name',
        'crm_opportunity_workspace_queue',
        RUN,
      );
      await selectRow(viewerPage, `${RUN} Opportunity Qualify`);
      await expect(viewerPage.getByTestId('workbench-action-open_opportunity_record')).toBeVisible();
      for (const action of ['create_opportunity', 'qualify_opportunity', 'lose_opportunity']) {
        await expect(viewerPage.getByTestId(`workbench-action-${action}`)).toHaveCount(0);
      }

      await gotoWorkbench(
        viewerPage,
        'crm_forecast_cockpit',
        'crm_forecast_cockpit_stats',
        /预测驾驶舱|Forecast Cockpit/,
      );
      await searchForecast(viewerPage, FORECAST_PERIOD);
      await selectRow(viewerPage, FORECAST_PERIOD);
      await expect(viewerPage.getByTestId('workbench-action-open_forecast_record')).toBeVisible();
      await expect(viewerPage.getByTestId('workbench-action-create_forecast')).toHaveCount(0);
      await expect(viewerPage.getByTestId('workbench-action-submit_forecast')).toHaveCount(0);

      await gotoWorkbench(
        viewerPage,
        'crm_activity_service_desk',
        'crm_activity_service_stats',
        /活动与服务工作台|Activity & Service Desk/,
      );
      await searchNamedQueryQueue(viewerPage, 'item_title', 'crm_activity_service_queue', RUN);
      await selectRow(viewerPage, `${RUN} Task Start`);
      await expect(viewerPage.getByTestId('workbench-action-open_task_record')).toBeVisible();
      for (const action of ['create_task', 'start_task', 'complete_task']) {
        await expect(viewerPage.getByTestId(`workbench-action-${action}`)).toHaveCount(0);
      }
      await selectRow(viewerPage, `${RUN} Complaint Investigate`);
      await expect(viewerPage.getByTestId('workbench-action-open_complaint_record')).toBeVisible();
      await expect(viewerPage.getByTestId('workbench-action-investigate_complaint')).toHaveCount(0);
      await shot(viewerPage, testInfo, 'crm-role-viewer-read-only.png');

      await expectCommandRejected(viewerJwt, 'crm:contact_lead', ids.lead);
      expect((await getRecord('crm_lead_common', ids.lead)).crm_lead_status).toBe('new');
    } finally {
      await viewerContext.close();
    }

    const salesContext = await browser.newContext();
    const salesPage = await salesContext.newPage();
    try {
      await uiLogin(salesPage, SALES_EMAIL);
      await gotoWorkbench(salesPage, 'crm_lead_desk_workbench', 'crm_lead_desk_stats', /线索工作台|Lead Desk/);
      await assertWorkbenchMenuMatrix(salesPage, [
        'crm_customer_360_workbench',
        'crm_lead_desk_workbench',
        'crm_opportunity_workspace',
        'crm_forecast_cockpit',
        'crm_activity_service_desk',
      ], []);
      await searchNamedQueryQueue(salesPage, 'crm_lead_company', 'crm_lead_desk_queue', RUN);
      await expect(salesPage.locator('tr').filter({ hasText: `${RUN} Sales Owned Lead` }).first())
        .toBeVisible();
      await expect(salesPage.locator('tr').filter({ hasText: `${RUN} Admin Control Lead` }))
        .toHaveCount(0);
      await selectRow(salesPage, `${RUN} Sales Owned Lead`);
      await expect(salesPage.getByTestId('workbench-action-contact_lead')).toBeVisible();
      await executeWorkbenchAction(salesPage, 'contact_lead', 'crm:contact_lead');
      await expect.poll(async () =>
        (await getRecord('crm_lead_common', salesLeadPid)).crm_lead_status)
        .toBe('contacted');
      await expectCommandRejected(salesJwt, 'crm:contact_lead', adminControlLeadPid);
      expect((await getRecord('crm_lead_common', adminControlLeadPid)).crm_lead_status).toBe('new');
      await shot(salesPage, testInfo, 'crm-role-sales-self-scope.png');
    } finally {
      await salesContext.close();
    }

    const serviceContext = await browser.newContext();
    const servicePage = await serviceContext.newPage();
    try {
      await uiLogin(servicePage, SERVICE_EMAIL);
      await gotoWorkbench(
        servicePage,
        'crm_activity_service_desk',
        'crm_activity_service_stats',
        /活动与服务工作台|Activity & Service Desk/,
      );
      await assertWorkbenchMenuMatrix(servicePage, [
        'crm_customer_360_workbench',
        'crm_activity_service_desk',
      ], [
        'crm_lead_desk_workbench',
        'crm_opportunity_workspace',
        'crm_forecast_cockpit',
      ]);
      await searchNamedQueryQueue(
        servicePage,
        'item_title',
        'crm_activity_service_queue',
        `${RUN} Service Role`,
      );
      await selectRow(servicePage, `${RUN} Service Role Complaint`);
      await expect(servicePage.getByTestId('workbench-action-investigate_complaint')).toBeVisible();
      await executeWorkbenchAction(
        servicePage,
        'investigate_complaint',
        'crm:investigate_complaint',
      );
      await expect.poll(async () =>
        (await getRecord('crm_complaint', serviceComplaintPid)).crm_cmp_status)
        .toBe('investigating');
      await expectCommandRejected(serviceJwt, 'crm:contact_lead', adminControlLeadPid);
      expect((await getRecord('crm_lead_common', adminControlLeadPid)).crm_lead_status).toBe('new');
      await shot(servicePage, testInfo, 'crm-role-service-boundary.png');
    } finally {
      await serviceContext.close();
    }
  });

test('all five workbenches expose an explicit no-match empty state', async ({ page }, testInfo) => {
  const noMatch = `NO${Date.now()}`;
  await uiLogin(page);

  await gotoWorkbench(page, 'crm_customer_360_workbench', 'crm_customer_360_stats', /客户 360|Customer 360/);
  await searchNamedQueryQueue(page, 'crm_acc_name', 'crm_customer_360_queue', noMatch);
  await expect(page.getByTestId('table-block').first()).toContainText(/暂无数据|No data/);
  await shot(page, testInfo, 'crm-customer-360-empty.png');

  await gotoWorkbench(page, 'crm_lead_desk_workbench', 'crm_lead_desk_stats', /线索工作台|Lead Desk/);
  await searchNamedQueryQueue(page, 'crm_lead_company', 'crm_lead_desk_queue', noMatch);
  await expect(page.getByTestId('table-block').first()).toContainText(/暂无数据|No data/);
  await shot(page, testInfo, 'crm-lead-desk-empty.png');

  await gotoWorkbench(
    page,
    'crm_opportunity_workspace',
    'crm_opportunity_workspace_stats',
    /商机工作台|Opportunity Workspace/,
  );
  await searchNamedQueryQueue(page, 'crm_opp_name', 'crm_opportunity_workspace_queue', noMatch);
  await expect(page.getByTestId('table-block').first()).toContainText(/暂无数据|No data/);
  await shot(page, testInfo, 'crm-opportunity-workspace-empty.png');

  await gotoWorkbench(page, 'crm_forecast_cockpit', 'crm_forecast_cockpit_stats', /预测驾驶舱|Forecast Cockpit/);
  await searchForecast(page, noMatch);
  await expect(page.getByTestId('table-block').first()).toContainText(/暂无数据|No data/);
  await shot(page, testInfo, 'crm-forecast-cockpit-empty.png');

  await gotoWorkbench(
    page,
    'crm_activity_service_desk',
    'crm_activity_service_stats',
    /活动与服务工作台|Activity & Service Desk/,
  );
  await searchNamedQueryQueue(page, 'item_title', 'crm_activity_service_queue', noMatch);
  await expect(page.getByTestId('table-block').first()).toContainText(/暂无数据|No data/);
  await shot(page, testInfo, 'crm-activity-service-empty.png');
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
    await selectRow(page, `${RUN} Lead Contact`);
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
    completedActions.add('contact_lead');

    await selectRow(page, `${RUN} Lead Qualify`);
    await executeWorkbenchAction(page, 'qualify_lead', 'crm:qualify_lead');
    await expect.poll(async () =>
      (await getRecord('crm_lead_common', ids.leadQualify)).crm_lead_status)
      .toBe('qualified');
    completedActions.add('qualify_lead');

    await selectRow(page, `${RUN} Lead Convert`);
    await executeWorkbenchAction(page, 'convert_lead', 'crm:convert_lead', true);
    await expect.poll(async () =>
      (await getRecord('crm_lead_common', ids.leadConvert)).crm_lead_status)
      .toBe('converted');
    const convertedLead = await getRecord('crm_lead_common', ids.leadConvert);
    for (const field of [
      'crm_lead_converted_account_id',
      'crm_lead_converted_contact_id',
      'crm_lead_converted_opportunity_id',
      'crm_lead_converted_request_id',
      'crm_lead_converted_at',
    ]) {
      expect(convertedLead[field], `converted lead must persist ${field}`).toBeTruthy();
    }
    completedActions.add('convert_lead');

    await selectRow(page, `${RUN} Lead Lose`);
    await executeWorkbenchAction(page, 'lose_lead', 'crm:lose_lead', true);
    await expect.poll(async () =>
      (await getRecord('crm_lead_common', ids.leadLose)).crm_lead_status)
      .toBe('lost');
    completedActions.add('lose_lead');
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
    await selectRow(page, `${RUN} Opportunity Qualify`);
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
    completedActions.add('qualify_opportunity');

    await selectRow(page, `${RUN} Opportunity Proposal`);
    await executeWorkbenchAction(page, 'advance_to_proposal', 'crm:advance_opp_to_proposal');
    await expect.poll(async () =>
      (await getRecord('crm_opportunity_common', ids.opportunityProposal)).crm_opp_stage)
      .toBe('proposal');
    completedActions.add('advance_to_proposal');

    await selectRow(page, `${RUN} Opportunity Negotiation`);
    await executeWorkbenchAction(
      page,
      'advance_to_negotiation',
      'crm:advance_opp_to_negotiation',
    );
    await expect.poll(async () =>
      (await getRecord('crm_opportunity_common', ids.opportunityNegotiation)).crm_opp_stage)
      .toBe('negotiation');
    completedActions.add('advance_to_negotiation');

    await selectRow(page, `${RUN} Opportunity Win`);
    await executeWorkbenchAction(page, 'win_opportunity', 'crm:win_opportunity', true);
    await expect.poll(async () =>
      (await getRecord('crm_opportunity_common', ids.opportunityWin)).crm_opp_stage)
      .toBe('closed_won');
    completedActions.add('win_opportunity');

    await selectRow(page, `${RUN} Opportunity Lose`);
    await executeWorkbenchAction(page, 'lose_opportunity', 'crm:lose_opportunity', true);
    await expect.poll(async () =>
      (await getRecord('crm_opportunity_common', ids.opportunityLose)).crm_opp_stage)
      .toBe('closed_lost');
    completedActions.add('lose_opportunity');
    completedScenarios.add('opportunity-workspace');
  });

test('Forecast Cockpit submits a real forecast and shows both submission and team views',
  async ({ page }, testInfo) => {
    await uiLogin(page);
    await gotoWorkbench(page, 'crm_forecast_cockpit', 'crm_forecast_cockpit_stats', /预测驾驶舱|Forecast Cockpit/);
    await searchForecast(page, FORECAST_PERIOD);
    await selectRow(page, FORECAST_PERIOD);
    await expect(page.getByTestId('status-banner-crm_forecast_status')).toBeVisible();
    await expect(page.getByTestId('workbench-action-submit_forecast')).toBeVisible();
    await expect(page.getByText(/团队偏差|Team Rollup/).first()).toBeVisible();
    await assertNoRawCodes(page);
    await dualViewportShots(page, testInfo, 'crm-forecast-cockpit');
    await executeWorkbenchAction(page, 'submit_forecast', 'crm:submit_forecast', true);
    await expect.poll(async () =>
      (await getRecord('crm_forecast_submission', ids.forecast)).crm_fcst_status)
      .toBe('submitted');
    completedActions.add('submit_forecast');
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
    await selectRow(page, `${RUN} Task Start`);
    await expect(page.getByTestId('status-banner-crm_activity_service_attention')).toBeVisible();
    await expect(page.getByTestId('workbench-action-start_task')).toBeVisible();
    await assertMappedSummaryValue(
      page,
      'status-banner-crm_activity_service_attention',
      /任务|Task/,
      /\b(?:task|complaint|open|in_progress|done|cancelled|investigating|resolved|closed)\b/,
    );
    const taskRow = page.locator('tr').filter({ hasText: `${RUN} Task Start` }).first();
    await expect(taskRow).toContainText(/高|High/);
    await expect(taskRow).not.toContainText(/\bhigh\b/);
    await expect(taskRow).toContainText(/2026\/8\/9/);
    await assertNoRawCodes(page);
    await dualViewportShots(page, testInfo, 'crm-activity-service-desk');
    await executeWorkbenchAction(page, 'start_task', 'crm:start_task');
    await expect.poll(async () => (await getRecord('crm_activity_common', ids.task)).crm_act_status)
      .toBe('in_progress');
    completedActions.add('start_task');

    await selectRow(page, `${RUN} Task Complete`);
    await expect(page.getByTestId('workbench-action-complete_task')).toBeVisible();
    await executeWorkbenchAction(page, 'complete_task', 'crm:complete_task', true);
    await expect.poll(async () =>
      (await getRecord('crm_activity_common', ids.taskComplete)).crm_act_status)
      .toBe('done');
    completedActions.add('complete_task');

    await selectRow(page, `${RUN} Complaint Investigate`);
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
    completedActions.add('investigate_complaint');

    await selectRow(page, `${RUN} Complaint Resolve`);
    await expect(page.getByTestId('workbench-action-resolve_complaint')).toBeVisible();
    await executeWorkbenchAction(page, 'resolve_complaint', 'crm:resolve_complaint', true);
    await expect.poll(async () =>
      (await getRecord('crm_complaint', ids.complaintResolve)).crm_cmp_status)
      .toBe('resolved');
    completedActions.add('resolve_complaint');

    await selectRow(page, `${RUN} Complaint Close`);
    await expect(page.getByTestId('workbench-action-close_complaint')).toBeVisible();
    await executeWorkbenchAction(page, 'close_complaint', 'crm:close_complaint', true);
    await expect.poll(async () =>
      (await getRecord('crm_complaint', ids.complaintClose)).crm_cmp_status)
      .toBe('closed');
    completedActions.add('close_complaint');
    completedScenarios.add('activity-service-desk');
  });

test('terminal and advanced states reject repeated lifecycle commands without mutation',
  async ({ page }, testInfo) => {
    await uiLogin(page);

    await gotoWorkbench(page, 'crm_lead_desk_workbench', 'crm_lead_desk_stats', /线索工作台|Lead Desk/);
    await searchNamedQueryQueue(page, 'crm_lead_company', 'crm_lead_desk_queue', RUN);
    await selectRow(page, `${RUN} Lead Contact`);
    await expect(page.getByTestId('workbench-action-contact_lead')).toHaveCount(0);
    await expectCommandRejected(adminJwt, 'crm:contact_lead', ids.lead);
    expect((await getRecord('crm_lead_common', ids.lead)).crm_lead_status).toBe('contacted');

    await gotoWorkbench(
      page,
      'crm_opportunity_workspace',
      'crm_opportunity_workspace_stats',
      /商机工作台|Opportunity Workspace/,
    );
    await searchNamedQueryQueue(page, 'crm_opp_name', 'crm_opportunity_workspace_queue', RUN);
    await selectRow(page, `${RUN} Opportunity Qualify`);
    await expect(page.getByTestId('workbench-action-qualify_opportunity')).toHaveCount(0);
    await expectCommandRejected(adminJwt, 'crm:qualify_opportunity', ids.opportunity);
    expect((await getRecord('crm_opportunity_common', ids.opportunity)).crm_opp_stage)
      .toBe('qualification');

    await gotoWorkbench(page, 'crm_forecast_cockpit', 'crm_forecast_cockpit_stats', /预测驾驶舱|Forecast Cockpit/);
    await searchForecast(page, FORECAST_PERIOD);
    await selectRow(page, FORECAST_PERIOD);
    await expect(page.getByTestId('workbench-action-submit_forecast')).toHaveCount(0);
    await expectCommandRejected(adminJwt, 'crm:submit_forecast', ids.forecast);
    expect((await getRecord('crm_forecast_submission', ids.forecast)).crm_fcst_status)
      .toBe('submitted');

    await gotoWorkbench(
      page,
      'crm_activity_service_desk',
      'crm_activity_service_stats',
      /活动与服务工作台|Activity & Service Desk/,
    );
    await searchNamedQueryQueue(page, 'item_title', 'crm_activity_service_queue', RUN);
    await selectRow(page, `${RUN} Task Start`);
    await expect(page.getByTestId('workbench-action-start_task')).toHaveCount(0);
    await expectCommandRejected(adminJwt, 'crm:start_task', ids.task);
    expect((await getRecord('crm_activity_common', ids.task)).crm_act_status).toBe('in_progress');

    await selectRow(page, `${RUN} Complaint Investigate`);
    await expect(page.getByTestId('workbench-action-investigate_complaint')).toHaveCount(0);
    await expectCommandRejected(adminJwt, 'crm:investigate_complaint', ids.complaint);
    expect((await getRecord('crm_complaint', ids.complaint)).crm_cmp_status).toBe('investigating');
    await shot(page, testInfo, 'crm-illegal-transition-controls.png');
  });

test('concurrent lead update fails visibly and preserves the winner', async ({ page }, testInfo) => {
  const staleLeadPid = await createLead('Stale Conflict');
  await uiLogin(page);
  await gotoWorkbench(page, 'crm_lead_desk_workbench', 'crm_lead_desk_stats', /线索工作台|Lead Desk/);
  await searchNamedQueryQueue(page, 'crm_lead_company', 'crm_lead_desk_queue', `${RUN} Stale Conflict`);
  await selectRow(page, `${RUN} Stale Conflict`);
  await expect(page.getByTestId('workbench-action-contact_lead')).toBeVisible();

  await executeSetupTransition('crm:contact_lead', staleLeadPid);
  const rejectedResponse = page.waitForResponse((response) =>
    response.request().method() === 'POST'
      && response.url().includes('/api/meta/commands/execute/crm:contact_lead'),
  { timeout: 20_000 });
  await page.getByTestId('workbench-action-contact_lead').click();
  const response = await rejectedResponse;
  const body = await response.json().catch(() => ({}));
  expect(!response.ok() || String(body?.code) !== '0', JSON.stringify(body)).toBeTruthy();
  await expect(page.locator('[role="alert"], [data-sonner-toast]').last())
    .toBeVisible({ timeout: 10_000 });
  expect((await getRecord('crm_lead_common', staleLeadPid)).crm_lead_status).toBe('contacted');
  await shot(page, testInfo, 'crm-lead-stale-conflict.png');
});
