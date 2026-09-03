import { expect, test, type Page, type TestInfo } from '@playwright/test';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';

const BASE = process.env.PLAYWRIGHT_BASE_URL || 'http://127.0.0.1:5191';
const BE = process.env.BACKEND_URL || 'http://127.0.0.1:6491';
const RUN = process.env.OPPORTUNITY_EFFICIENCY_RUN_ID || `opp-eff-${Date.now()}`;
const EVIDENCE_DIR =
  process.env.OPPORTUNITY_EFFICIENCY_EVIDENCE_DIR ||
  path.join('/tmp', `crm-opportunity-efficiency-${RUN}`);
const ADMIN_EMAIL = 'admin@auraboot.com';
const PASSWORD = 'Test2026x';
const OTHER_OWNER_EMAIL = `${RUN.slice(-18)}-owner@crm.example`;
const VIEWER_EMAIL = `${RUN.slice(-18)}-viewer@crm.example`;
const TODAY = new Date().toISOString().slice(0, 10);
const NEXT_MONTH = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000).toISOString();

const names = {
  discovery: `${RUN} 华东智造云发现阶段`,
  proposal: `${RUN} 华东智造云方案提报`,
  negotiation: `${RUN} 华东智造云商务谈判`,
  otherOwned: `${RUN} 非本人负责商机`,
  won: `${RUN} 华东智造云已赢单`,
  task: `${RUN} 完成技术方案复核`,
  cancelTask: `${RUN} 取消过期拜访计划`,
  contact: `${RUN} 王敏`,
  bulkDiscovery: `${RUN} 华东智造云批量资格确认`,
  forecastPeriod: `FY26Q3-${RUN.slice(-10)}`,
};

const ids = {
  account: '',
  discovery: '',
  bulkDiscovery: '',
  proposal: '',
  negotiation: '',
  otherOwned: '',
  won: '',
  task: '',
  cancelTask: '',
  quote: '',
  forecast: '',
  personalView: '',
  pipelineBoard: '',
  allOpportunitiesView: '',
  myOpportunitiesView: '',
  wonOpportunitiesView: '',
  adminUser: '',
  adminUserDisplay: '',
  otherOwner: '',
  contact: '',
};

const expectedScenarios = [
  'cordys-preset-view-operating-matrix',
  'shared-list-kanban-fact',
  'personal-view-persistence',
  'personal-view-column-configuration',
  'advanced-filter-saved-view-export',
  'current-view-self-service-analysis',
  'opportunity-plan-quote-context',
  'opportunity-follow-up-task-lifecycle',
  'forecast-first-screen-hierarchy',
  'merged-customer-activity-timeline',
  'account-dashboard-drilldown-fact',
  'safe-bulk-opportunity-lifecycle',
] as const;
const completedScenarios = new Set<(typeof expectedScenarios)[number]>();
const expectedCoverage = {
  pages: [
    'crm_contact_common_list',
    'crm_forecast_cockpit',
    'crm_opportunity_common_detail',
    'crm_opportunity_common_list',
  ],
  commands: [
    'crm:advance_opp_to_negotiation',
    'crm:advance_opp_to_proposal',
    'crm:create_account',
    'crm:create_contact',
    'crm:create_forecast',
    'crm:create_opp_task',
    'crm:create_opportunity',
    'crm:create_quote_summary',
    'crm:qualify_opportunity',
    'crm:start_task',
    'crm:complete_task',
    'crm:cancel_task',
    'crm:win_opportunity',
  ],
  queries: ['crm_account_stats', 'crm_account_timeline'],
  permissions: ['crm.activity.manage'],
  dashboardTargets: [
    'crm_account_360:recent_activities:recent_activities',
    'crm_account_360:recent_opportunities:recent_opportunities',
    'crm_account_360:stats_contacts:stats_contacts',
  ],
  uiActions: [
    'crm_opportunity_common_detail:crm_opp_plan_quote_actions:create_plan_task',
    'crm_opportunity_common_detail:crm_opp_plan_quote_actions:create_quote_summary',
    'crm_opportunity_common_detail:block_opportunity_plan:view_task',
    'crm_opportunity_common_detail:block_opportunity_plan:start_task',
    'crm_opportunity_common_detail:block_opportunity_plan:complete_task',
    'crm_opportunity_common_detail:block_opportunity_plan:cancel_task',
    'crm_opportunity_common_detail:crm_opportunity_tabs:activities',
    'crm_opportunity_common_detail:crm_opportunity_tabs:plan_and_quotes',
    'crm_opportunity_common_list:crm_opp_table:bulk_qualify',
    'crm_opportunity_common_list:platform:add_advanced_filter',
    'crm_opportunity_common_list:platform:configure_view_columns',
    'crm_opportunity_common_list:platform:export_filtered_csv',
    'crm_opportunity_common_list:platform:analyze_current_view',
    'crm_opportunity_common_list:platform:select_preset_view',
    'crm_opportunity_common_list:platform:drill_chart_to_list',
    'crm_opportunity_common_list:platform:save_advanced_filters',
    'crm_opportunity_common_list:crm_opp_tabs:proposal',
  ],
  blocks: [
    'crm_contact_common_list:crm_contact_table',
    'crm_forecast_cockpit:crm_forecast_execution_metrics',
    'crm_forecast_cockpit:crm_forecast_metrics',
    'crm_forecast_cockpit:crm_forecast_owner_queue',
    'crm_forecast_cockpit:crm_forecast_submission_queue',
    'crm_forecast_cockpit:crm_forecast_tabs',
    'crm_opportunity_common_detail:block_activities',
    'crm_opportunity_common_detail:block_opportunity_plan',
    'crm_opportunity_common_detail:block_opportunity_quotes',
    'crm_opportunity_common_detail:crm_opp_plan_quote_actions',
    'crm_opportunity_common_detail:crm_opp_stage_rail',
    'crm_opportunity_common_detail:crm_opportunity_tabs',
    'crm_opportunity_common_list:crm_opp_table',
    'crm_opportunity_common_list:crm_opp_tabs',
  ],
  fields: [
    'crm_contact_common_list:crm_contact_table:crm_ct_name',
    'crm_forecast_cockpit:crm_forecast_submission_queue:crm_fcst_best_case_amount',
    'crm_forecast_cockpit:crm_forecast_submission_queue:crm_fcst_commit_amount',
    'crm_forecast_cockpit:crm_forecast_submission_queue:crm_fcst_period',
    'crm_forecast_cockpit:crm_forecast_submission_queue:crm_fcst_pipeline_amount',
    'crm_opportunity_common_detail:block_activities:crm_act_subject',
    'crm_opportunity_common_detail:block_opportunity_plan:crm_act_subject',
    'crm_opportunity_common_detail:block_opportunity_quotes:crm_qs_quote_amount',
    'crm_opportunity_common_list:crm_opp_table:crm_opp_expected_amount',
    'crm_opportunity_common_list:crm_opp_table:crm_opp_expected_close_date',
    'crm_opportunity_common_list:crm_opp_table:crm_opp_name',
    'crm_opportunity_common_list:crm_opp_table:crm_opp_probability',
    'crm_opportunity_common_list:crm_opp_table:crm_opp_stage',
  ],
} as const;
type CoverageAxis = keyof typeof expectedCoverage;
const completedCoverage: Record<CoverageAxis, Set<string>> = Object.fromEntries(
  Object.keys(expectedCoverage).map((axis) => [axis, new Set<string>()]),
) as Record<CoverageAxis, Set<string>>;
const screenshots: string[] = [];
let adminJwt = '';
let viewerJwt = '';

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
  cover('commands', code);
  return String(pid);
}

async function provisionUser(email: string, roleCode: string, displayName: string): Promise<string> {
  const body = assertOk(await api('/api/admin/users', {
    method: 'POST',
    body: JSON.stringify({
      email,
      displayName,
      initialPassword: PASSWORD,
      roleCodes: [roleCode],
      sendInviteEmail: false,
    }),
  }), `provision ${roleCode}`);
  const pid = findValue(body?.data, ['pid', 'userPid']);
  expect(pid, `${roleCode} user must return a public pid`).toBeTruthy();
  return String(pid);
}

async function loginApi(email: string): Promise<string> {
  const body = assertOk(await api('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify({ email, password: PASSWORD }),
  }, ''), `API login ${email}`);
  const jwt = findValue(body?.data, ['jwt']);
  expect(jwt, `${email} API login must return a JWT`).toBeTruthy();
  return String(jwt);
}

async function executeTransition(code: string, targetRecordPid: string): Promise<void> {
  assertOk(await api(`/api/meta/commands/execute/${code}`, {
    method: 'POST',
    body: JSON.stringify({ payload: {}, targetRecordPid, operationType: 'update' }),
  }), code);
  cover('commands', code);
}

async function listRecords(
  model: string,
  filters: Array<{ fieldName: string; operator: string; value: unknown }>,
): Promise<Record<string, any>[]> {
  const params = new URLSearchParams({
    pageNum: '1',
    pageSize: '50',
    filters: JSON.stringify(filters),
  });
  const body = assertOk(await api(`/api/dynamic/${model}/list?${params}`), `list ${model}`);
  return body?.data?.records ?? body?.data?.content ?? [];
}

async function getRecord(model: string, pid: string): Promise<Record<string, any>> {
  return assertOk(
    await api(`/api/dynamic/${model}/${encodeURIComponent(pid)}`),
    `read ${model}/${pid}`,
  ).data;
}

function decodeListFilters(url: string): Array<{
  fieldCode: string;
  operator: string;
  value: unknown;
}> {
  const encoded = new URL(url).searchParams.get('filters');
  expect(encoded, 'drill-down URL must carry exact list filters').toBeTruthy();
  return JSON.parse(Buffer.from(String(encoded), 'base64').toString('utf8'));
}

async function seedJourney(): Promise<void> {
  ids.account = await executeCreate('crm:create_account', {
    crm_acc_name: `${RUN} 华东智造集团`,
    crm_acc_industry: 'manufacturing',
    crm_acc_status: 'active',
    crm_acc_rating: 'A',
  });

  ids.discovery = await executeCreate('crm:create_opportunity', {
    crm_opp_name: names.discovery,
    crm_opp_account_id: ids.account,
    crm_opp_currency_code: 'CNY',
    crm_opp_expected_amount: 480000,
    crm_opp_expected_close_date: NEXT_MONTH,
    crm_opp_probability: 25,
    crm_opp_owner: ids.adminUser,
    crm_opp_forecast_category: 'pipeline',
  });
  ids.bulkDiscovery = await executeCreate('crm:create_opportunity', {
    crm_opp_name: names.bulkDiscovery,
    crm_opp_account_id: ids.account,
    crm_opp_currency_code: 'CNY',
    crm_opp_expected_amount: 260000,
    crm_opp_expected_close_date: NEXT_MONTH,
    crm_opp_probability: 20,
    crm_opp_owner: ids.adminUser,
    crm_opp_forecast_category: 'pipeline',
  });
  ids.proposal = await executeCreate('crm:create_opportunity', {
    crm_opp_name: names.proposal,
    crm_opp_account_id: ids.account,
    crm_opp_currency_code: 'CNY',
    crm_opp_expected_amount: 320000,
    crm_opp_expected_close_date: NEXT_MONTH,
    crm_opp_probability: 60,
    crm_opp_owner: ids.adminUser,
    crm_opp_forecast_category: 'best_case',
    crm_opp_competitor: 'CordysCRM',
  });
  ids.negotiation = await executeCreate('crm:create_opportunity', {
    crm_opp_name: names.negotiation,
    crm_opp_account_id: ids.account,
    crm_opp_currency_code: 'CNY',
    crm_opp_expected_amount: 180000,
    crm_opp_expected_close_date: NEXT_MONTH,
    crm_opp_probability: 85,
    crm_opp_owner: ids.adminUser,
    crm_opp_forecast_category: 'commit',
  });
  await executeTransition('crm:qualify_opportunity', ids.proposal);
  await executeTransition('crm:advance_opp_to_proposal', ids.proposal);
  await executeTransition('crm:qualify_opportunity', ids.negotiation);
  await executeTransition('crm:advance_opp_to_proposal', ids.negotiation);
  await executeTransition('crm:advance_opp_to_negotiation', ids.negotiation);

  ids.otherOwned = await executeCreate('crm:create_opportunity', {
    crm_opp_name: names.otherOwned,
    crm_opp_account_id: ids.account,
    crm_opp_currency_code: 'CNY',
    crm_opp_expected_amount: 210000,
    crm_opp_expected_close_date: NEXT_MONTH,
    crm_opp_probability: 30,
    crm_opp_owner: ids.otherOwner,
    crm_opp_forecast_category: 'pipeline',
  });
  ids.won = await executeCreate('crm:create_opportunity', {
    crm_opp_name: names.won,
    crm_opp_account_id: ids.account,
    crm_opp_currency_code: 'CNY',
    crm_opp_expected_amount: 680000,
    crm_opp_expected_close_date: NEXT_MONTH,
    crm_opp_probability: 100,
    crm_opp_owner: ids.adminUser,
    crm_opp_forecast_category: 'commit',
  });
  await executeTransition('crm:qualify_opportunity', ids.won);
  await executeTransition('crm:advance_opp_to_proposal', ids.won);
  await executeTransition('crm:advance_opp_to_negotiation', ids.won);
  await executeTransition('crm:win_opportunity', ids.won);

  ids.task = await executeCreate('crm:create_opp_task', {
    sourceRecordPid: ids.proposal,
    crm_act_subject: names.task,
    crm_act_content: '与售前、交付共同复核 QDP 范围和报价假设。',
    crm_act_due_date: TODAY,
    crm_act_priority: 'high',
  });
  ids.cancelTask = await executeCreate('crm:create_opp_task', {
    sourceRecordPid: ids.proposal,
    crm_act_subject: names.cancelTask,
    crm_act_content: '客户时间冲突，取消旧计划并重新安排。',
    crm_act_due_date: TODAY,
    crm_act_priority: 'medium',
  });
  ids.quote = await executeCreate('crm:create_quote_summary', {
    crm_qs_account_id: ids.account,
    crm_qs_opportunity_id: ids.proposal,
    crm_qs_source_quote_type: 'service_proposal',
    crm_qs_source_quote_id: `${RUN}-QUOTE-01`,
    crm_qs_status: 'sent',
    crm_qs_quote_amount: 328000,
    crm_qs_currency: 'CNY',
    crm_qs_valid_until: NEXT_MONTH.slice(0, 10),
    crm_qs_approval_status: 'approved',
    crm_qs_customer_feedback_status: 'viewed',
    crm_qs_won_lost_result: 'open',
    crm_qs_summary: '华东智造云 CRM Release B 方案报价',
  });
  ids.forecast = await executeCreate('crm:create_forecast', {
    crm_fcst_period: names.forecastPeriod,
    crm_fcst_commit_amount: 180000,
    crm_fcst_best_case_amount: 500000,
    crm_fcst_pipeline_amount: 980000,
    crm_fcst_notes: 'Commit 由商务谈判商机组成，Best Case 包含方案提报商机。',
  });
  ids.contact = await executeCreate('crm:create_contact', {
    crm_ct_account_id: ids.account,
    crm_ct_name: names.contact,
    crm_ct_title: '信息化负责人',
    crm_ct_email: `${RUN}@customer.example`,
    crm_ct_is_primary: true,
  });
}

async function uiLogin(page: Page, email = ADMIN_EMAIL): Promise<void> {
  const response = await page.request.post(`${BASE}/login`, {
    form: { email, password: PASSWORD, remember: 'on', redirectTo: '/' },
    maxRedirects: 0,
  });
  expect([302, 303], `UI login: HTTP ${response.status()}`).toContain(response.status());
  // Wait for the authenticated shell's full load (including the / -> /home redirect)
  // before a scenario starts its own navigation. Starting a second navigation while
  // that redirect is still completing makes Chromium abort the scenario URL.
  await page.goto(`${BASE}/`, { waitUntil: 'load' });
  if (page.url().includes('tenant-selection')) {
    await page.getByRole('button', { name: /进入|选择|Enter|AuraBoot/ }).first().click();
    await page.waitForURL((url) => !url.pathname.includes('tenant-selection'), { timeout: 15_000 });
  }
  await expect(page.locator('input#email')).toHaveCount(0, { timeout: 10_000 });
}

async function gotoOpportunityList(page: Page, viewPid?: string): Promise<void> {
  const query = viewPid ? `?view=${encodeURIComponent(viewPid)}` : '';
  await page.goto(`${BASE}/p/crm_opportunity_common${query}`, { waitUntil: 'domcontentloaded' });
  await expect(page.getByTestId('dynamic-list')).toBeVisible({ timeout: 20_000 });
  await expect(page.getByTestId('list-view-mode-switcher')).toBeVisible();
}

async function assertNoRawCodes(page: Page): Promise<void> {
  const text = await page.locator('main, [role="main"]').first().innerText();
  expect(text).not.toMatch(/\bcrm_(?:opp|act|qs|fcst)_[a-z_]+\b/);
  expect(text).not.toMatch(/\b(?:created_at|updated_at|created_by|updated_by)\b/);
  expect(text).not.toMatch(/\b[0-9A-HJKMNP-TV-Z]{26}\b/);
  expect(text).not.toMatch(/加载失败|Page not found/i);
}

async function shot(page: Page, testInfo: TestInfo, name: string): Promise<void> {
  const output = path.join(EVIDENCE_DIR, name);
  mkdirSync(path.dirname(output), { recursive: true });
  await page.screenshot({ path: output, fullPage: true });
  await testInfo.attach(name, { path: output, contentType: 'image/png' });
  screenshots.push(output);
}

async function assertNoPageOverflow(page: Page): Promise<void> {
  await expect.poll(async () => page.evaluate(() =>
    document.documentElement.scrollWidth - document.documentElement.clientWidth,
  )).toBeLessThanOrEqual(0);
}

async function closeCompactNavigation(page: Page): Promise<void> {
  const toggle = page.getByTestId('header-sidebar-toggle');
  if (await toggle.isVisible() && await toggle.getAttribute('aria-expanded') === 'true') {
    await toggle.click();
  }
  if (await toggle.isVisible()) {
    await expect(toggle).toHaveAttribute('aria-expanded', 'false');
  }
  await expect(page.getByTestId('sidebar-mobile-backdrop')).toHaveCount(0);
  const sidebar = page.getByTestId('sidebar');
  const collapse = page.getByTitle('Collapse sidebar');
  if (!String(await sidebar.getAttribute('class')).includes('w-[68px]')) {
    await collapse.evaluate((element: HTMLElement) => element.click());
    await expect(sidebar).toHaveClass(/w-\[68px\]/);
  }
  await expect.poll(async () => {
    const box = await sidebar.boundingBox();
    if (!box || box.x + box.width <= 0) return 0;
    return box.width;
  }, { message: 'compact sidebar must finish collapsing before visual assertions' })
    .toBeLessThanOrEqual(80);
}

async function compactShot(page: Page, testInfo: TestInfo, name: string): Promise<void> {
  await page.setViewportSize({ width: 960, height: 900 });
  await closeCompactNavigation(page);
  await assertNoPageOverflow(page);
  await shot(page, testInfo, name);
  await page.setViewportSize({ width: 1280, height: 720 });
}

test.describe.configure({ mode: 'serial' });

test.beforeAll(async () => {
  mkdirSync(EVIDENCE_DIR, { recursive: true });
  const login = assertOk(await api('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify({ email: ADMIN_EMAIL, password: PASSWORD }),
  }, ''), 'API login');
  adminJwt = String(findValue(login?.data, ['jwt']) || '');
  expect(adminJwt).toBeTruthy();
  const currentUser = assertOk(await api('/api/auth/me'), 'read current admin identity')?.data?.user;
  ids.adminUser = String(currentUser?.pid || '');
  ids.adminUserDisplay = String(currentUser?.name || 'Admin');
  expect(ids.adminUser, 'current admin must expose a public pid').toBeTruthy();
  ids.otherOwner = await provisionUser(OTHER_OWNER_EMAIL, 'crm_sales', `${RUN} 异地销售`);
  await provisionUser(VIEWER_EMAIL, 'crm_viewer', `${RUN} 只读观察者`);
  viewerJwt = await loginApi(VIEWER_EMAIL);
  const priorPersonalViews = assertOk(await api(
    '/api/views/personal?modelCode=crm_opportunity_common&pageKey=crm_opportunity_common_list',
  ), 'prior personal opportunity views').data ?? [];
  for (const view of priorPersonalViews) {
    if (view.isImplicit === true) continue;
    assertOk(await api(`/api/views/${encodeURIComponent(String(view.pid))}`, {
      method: 'DELETE',
    }), `delete prior personal opportunity view ${view.pid}`);
  }
  const accessibleViews = assertOk(await api(
    '/api/views/accessible?modelCode=crm_opportunity_common&pageKey=crm_opportunity_common_list',
  ), 'accessible opportunity views').data ?? [];
  const resolveViewPid = (viewKey: string): string => String(
    accessibleViews.find((view: any) =>
      (view.viewKey || view.viewConfig?.meta?.viewKey) === viewKey)?.pid || '',
  );
  ids.allOpportunitiesView = resolveViewPid('crm_opportunity_all_table');
  ids.myOpportunitiesView = resolveViewPid('crm_opportunity_my_table');
  ids.wonOpportunitiesView = resolveViewPid('crm_opportunity_won_table');
  ids.pipelineBoard = String(
    accessibleViews.find((view: any) =>
      view.viewKey === 'crm_opportunity_pipeline_board'
        || view.name === '$i18n:crm.saved_view.pipeline_board'
        || view.name === 'Pipeline Board')?.pid || '',
  );
  expect(ids.pipelineBoard).toBeTruthy();
  expect(ids.allOpportunitiesView).toBeTruthy();
  expect(ids.myOpportunitiesView).toBeTruthy();
  expect(ids.wonOpportunitiesView).toBeTruthy();
  await seedJourney();
});

test.afterAll(() => {
  const coverage = Object.fromEntries(
    Object.entries(expectedCoverage).map(([axis, expected]) => [axis, {
      expected: [...expected].sort(),
      completed: [...completedCoverage[axis as CoverageAxis]].sort(),
    }]),
  );
  const coverageComplete = Object.values(coverage).every(({ expected, completed }) =>
    JSON.stringify(expected) === JSON.stringify(completed),
  );
  const evidence = {
    schemaVersion: 1,
    release: 'CRM Release B',
    runId: RUN,
    baseUrl: BASE,
    backendUrl: BE,
    recordIds: ids,
    screenshots,
    expectedScenarios,
    completedScenarios: [...completedScenarios].sort(),
    coverage,
    technicalVerdict: expectedScenarios.every((scenario) => completedScenarios.has(scenario))
      && coverageComplete
      ? 'pass'
      : 'incomplete',
    productOwnerScreenshotSignOff: 'pending-human-signature',
    dataMigration: 'out-of-scope-development-stage',
  };
  writeFileSync(
    path.join(EVIDENCE_DIR, `crm-opportunity-efficiency-${RUN}.json`),
    `${JSON.stringify(evidence, null, 2)}\n`,
  );
});

test('Cordys-aligned preset views expose real all, self and won opportunity facts', async ({ page }, testInfo) => {
  const accessibleViews = assertOk(await api(
    '/api/views/accessible?modelCode=crm_opportunity_common&pageKey=crm_opportunity_common_list',
  ), 're-read accessible opportunity views').data ?? [];
  const byKey = new Map(accessibleViews.map((view: any) => [
    view.viewKey || view.viewConfig?.meta?.viewKey,
    view,
  ]));
  expect(byKey.get('crm_opportunity_my_table')?.viewConfig?.filters).toEqual([
    expect.objectContaining({
      fieldCode: 'crm_opp_owner',
      operator: 'eq',
      isExpression: true,
      expression: '#currentUser',
    }),
  ]);
  expect(byKey.get('crm_opportunity_won_table')?.viewConfig?.filters).toEqual([
    expect.objectContaining({ fieldCode: 'crm_opp_stage', operator: 'eq', value: 'closed_won' }),
  ]);

  await uiLogin(page);
  await gotoOpportunityList(page, ids.allOpportunitiesView);
  await expect(page.getByTestId('view-selector-trigger')).toContainText('全部商机');
  await expect(page.locator('tr').filter({ hasText: names.discovery })).toBeVisible();
  await expect(page.locator('tr').filter({ hasText: names.otherOwned })).toBeVisible();

  await page.getByTestId('view-selector-trigger').click();
  const selector = page.getByRole('listbox');
  for (const label of ['全部商机', '我的商机', '赢单商机', '销售管道看板']) {
    await expect(selector.getByRole('option').filter({ hasText: label })).toBeVisible();
  }
  await shot(page, testInfo, 'release-d-opportunity-preset-matrix-desktop.png');

  const selfResponse = page.waitForResponse((response) => {
    if (response.request().method() !== 'GET'
      || !response.url().includes('/api/dynamic/crm_opportunity_common/list')) return false;
    const raw = new URL(response.url()).searchParams.get('filters');
    if (!raw) return false;
    const conditions = JSON.parse(raw);
    return conditions.some((condition: any) =>
      condition.fieldName === 'crm_opp_owner' && condition.value === ids.adminUser);
  }, { timeout: 20_000 });
  await selector.getByRole('option').filter({ hasText: '我的商机' }).click();
  const resolvedSelfResponse = await selfResponse;
  expect(resolvedSelfResponse.ok()).toBeTruthy();
  const selfPayload = await resolvedSelfResponse.json();
  const selfRecords = selfPayload?.data?.records ?? selfPayload?.data?.content ?? [];
  expect(selfRecords.length).toBeGreaterThan(0);
  expect(selfRecords.every((record: any) => record.crm_opp_owner === ids.adminUser)).toBeTruthy();
  await expect(page.getByTestId('view-selector-trigger')).toContainText('我的商机');

  // Repeated true-stack runs deliberately leave business records behind. The
  // preset sorts by expected close date ascending, so the current run may be on
  // the last page even though the owner filter is correct. Traverse through the
  // visible pagination contract instead of assuming a pristine database.
  const discoveryRow = page.locator('tr').filter({ hasText: names.discovery });
  if (!(await discoveryRow.isVisible())) {
    const lastPage = page.getByTestId('pagination-last');
    await expect(lastPage).toBeVisible();
    const lastPageResponse = page.waitForResponse((response) => {
      if (response.request().method() !== 'GET'
        || !response.url().includes('/api/dynamic/crm_opportunity_common/list')) return false;
      const raw = new URL(response.url()).searchParams.get('filters');
      if (!raw) return false;
      const conditions = JSON.parse(raw);
      return conditions.some((condition: any) =>
        condition.fieldName === 'crm_opp_owner' && condition.value === ids.adminUser);
    }, { timeout: 20_000 });
    await lastPage.click();
    expect((await lastPageResponse).ok()).toBeTruthy();
  }
  await expect(discoveryRow).toBeVisible();
  await expect(page.locator('tr').filter({ hasText: names.otherOwned })).toHaveCount(0);
  await expect(page.getByText(ids.adminUserDisplay, { exact: true }).first()).toBeVisible();
  await expect(page.getByTestId(`quick-filter-view-${ids.myOpportunitiesView}`)).toContainText(
    '我的商机',
  );
  await expect(page.getByTestId(`quick-filter-view-${ids.myOpportunitiesView}`)).not.toContainText(
    '$i18n:',
  );
  await assertNoRawCodes(page);
  await shot(page, testInfo, 'release-d-my-opportunities-desktop.png');

  await page.getByTestId('view-selector-trigger').click();
  await page.getByRole('listbox').getByRole('option').filter({ hasText: '赢单商机' }).click();
  await expect(page.getByTestId('view-selector-trigger')).toContainText('赢单商机');
  await expect(page.locator('tr').filter({ hasText: names.won })).toBeVisible();
  await expect(page.locator('tr').filter({ hasText: names.discovery })).toHaveCount(0);
  await assertNoRawCodes(page);
  await compactShot(page, testInfo, 'release-d-won-opportunities-compact.png');

  cover('pages', 'crm_opportunity_common_list');
  cover('blocks', 'crm_opportunity_common_list:crm_opp_table');
  cover(
    'fields',
    'crm_opportunity_common_list:crm_opp_table:crm_opp_name',
    'crm_opportunity_common_list:crm_opp_table:crm_opp_stage',
    'crm_opportunity_common_list:crm_opp_table:crm_opp_expected_amount',
    'crm_opportunity_common_list:crm_opp_table:crm_opp_expected_close_date',
  );
  cover('commands', 'crm:win_opportunity');
  cover('uiActions', 'crm_opportunity_common_list:platform:select_preset_view');
  completedScenarios.add('cordys-preset-view-operating-matrix');
});

test('list and kanban share the same stage-filtered opportunity fact', async ({ page }, testInfo) => {
  await uiLogin(page);
  await gotoOpportunityList(page, ids.pipelineBoard);

  await expect(page.getByTestId('view-selector-trigger')).toContainText('销售管道看板');
  await expect(page.getByTestId('view-selector-trigger')).not.toContainText('Pipeline Board');
  await expect(page.getByTestId('view-selector-trigger')).toContainText('预置');
  await expect(page.getByTestId('list-view-mode-kanban')).toHaveAttribute('aria-checked', 'true');
  for (const name of Object.values(names).slice(0, 3)) {
    await expect(page.getByTestId('kanban-card').filter({ hasText: name })).toBeVisible();
  }
  const boardText = await page.getByTestId('kanban-board').innerText();
  expect(boardText).not.toMatch(/Pipeline Value|\bAccount:|\bAmount:|\bProbability:|\bClose:|\bOwner:|Drop here/);
  expect(boardText).toMatch(/管道总额|关联客户|预期金额|成功概率|预计成交日期|负责人|拖放到这里/);
  await assertNoRawCodes(page);
  await assertNoPageOverflow(page);
  await shot(page, testInfo, 'release-b-opportunity-board-desktop.png');

  const kanbanMode = page.getByTestId('list-view-mode-kanban');
  await kanbanMode.focus();
  await expect(kanbanMode).toBeFocused();
  await kanbanMode.press('ArrowLeft');
  await expect(page.getByTestId('list-view-mode-table')).toHaveAttribute('aria-checked', 'true');
  await expect(page.locator('tr').filter({ hasText: names.proposal })).toBeVisible();

  const proposalResponse = page.waitForResponse((response) =>
    response.request().method() === 'GET'
      && response.url().includes('/api/dynamic/crm_opportunity_common/list')
      && response.url().includes('crm_opp_stage'),
  { timeout: 20_000 });
  await page.getByRole('button', { name: '方案提报', exact: true }).click();
  expect((await proposalResponse).ok()).toBeTruthy();
  await expect(page.locator('tr').filter({ hasText: names.proposal })).toBeVisible();
  await expect(page.locator('tr').filter({ hasText: names.discovery })).toHaveCount(0);

  const tableMode = page.getByTestId('list-view-mode-table');
  await tableMode.focus();
  await tableMode.press('ArrowRight');
  await expect(page.getByTestId('list-view-mode-kanban')).toHaveAttribute('aria-checked', 'true');
  await expect(page.getByTestId('kanban-card').filter({ hasText: names.proposal })).toBeVisible();
  await expect(page.getByTestId('kanban-card').filter({ hasText: names.discovery })).toHaveCount(0);
  await assertNoRawCodes(page);
  await compactShot(page, testInfo, 'release-b-opportunity-board-compact.png');
  cover('pages', 'crm_opportunity_common_list');
  cover(
    'blocks',
    'crm_opportunity_common_list:crm_opp_tabs',
    'crm_opportunity_common_list:crm_opp_table',
  );
  cover(
    'fields',
    'crm_opportunity_common_list:crm_opp_table:crm_opp_name',
    'crm_opportunity_common_list:crm_opp_table:crm_opp_stage',
    'crm_opportunity_common_list:crm_opp_table:crm_opp_expected_amount',
    'crm_opportunity_common_list:crm_opp_table:crm_opp_probability',
    'crm_opportunity_common_list:crm_opp_table:crm_opp_expected_close_date',
  );
  cover('uiActions', 'crm_opportunity_common_list:crm_opp_tabs:proposal');
  completedScenarios.add('shared-list-kanban-fact');
});

test('a personal saved view persists while list and board presentation changes', async ({ page }, testInfo) => {
  await uiLogin(page);
  await gotoOpportunityList(page);
  await page.getByTestId('view-selector-trigger').click();
  await page.getByTestId('view-selector-create').click();
  await expect(page.getByTestId('saved-view-manage-panel')).toBeVisible();
  await page.getByTestId('saved-view-type-table').click();
  await expect(page.getByTestId('saved-view-manage-panel')).toHaveCount(0);
  await expect(page.getByTestId('view-selector-trigger')).toContainText(/表格视图|Table View/);
  await expect(page.getByTestId('view-selector-trigger')).toContainText(/我的|Personal/);
  await expect.poll(() => new URL(page.url()).searchParams.get('view')).not.toBeNull();
  ids.personalView = new URL(page.url()).searchParams.get('view') || '';
  expect(ids.personalView).toBeTruthy();

  const personalViews = assertOk(await api(
    '/api/views/personal?modelCode=crm_opportunity_common&pageKey=crm_opportunity_common_list',
  ), 'personal views').data ?? [];
  expect(personalViews.some((view: any) => view.pid === ids.personalView)).toBeTruthy();

  await page.reload({ waitUntil: 'domcontentloaded' });
  await expect(page.getByTestId('view-selector-trigger')).toContainText(/表格视图|Table View/);
  await expect(page.getByTestId('list-view-mode-table')).toHaveAttribute('aria-checked', 'true');
  await page.getByRole('button', { name: '方案提报', exact: true }).click();
  await page.getByTestId('list-view-mode-table').press('ArrowRight');
  await expect(page.getByTestId('view-selector-trigger')).toContainText(/表格视图|Table View/);
  await expect(page.getByTestId('kanban-card').filter({ hasText: names.proposal })).toBeVisible();
  await expect(page.getByTestId('kanban-card').filter({ hasText: names.discovery })).toHaveCount(0);
  await shot(page, testInfo, 'release-b-personal-view-shared-fact.png');
  completedScenarios.add('personal-view-persistence');
});

test('a personal view owns its visible fields, pinned amount and row density', async ({ page }, testInfo) => {
  await uiLogin(page);
  await gotoOpportunityList(page, ids.personalView);
  const tableMode = page.getByTestId('list-view-mode-table');
  if ((await tableMode.getAttribute('aria-checked')) !== 'true') await tableMode.click();

  await page.getByTestId('column-settings-btn').click();
  const panel = page.getByTestId('column-settings-panel');
  await expect(panel).toBeVisible();
  await expect(panel).toContainText(/配置.*字段/);
  await expect(page.getByTestId('column-settings-density-short')).toBeVisible();

  const search = page.getByTestId('column-settings-search');
  await search.fill('竞争对手');
  const competitorRow = page.getByTestId('column-settings-row-crm_opp_competitor');
  await expect(competitorRow).toBeVisible();
  await expect(competitorRow).toContainText('竞争对手');
  await expect(competitorRow).not.toContainText('crm_opp_competitor');
  await page.getByTestId('column-settings-visible-crm_opp_competitor').check();
  await page.getByTestId('column-settings-width-crm_opp_competitor').fill('180');
  await page.getByTestId('column-settings-density-short').click();
  await shot(page, testInfo, 'release-c-column-settings-desktop.png');

  await search.clear();
  await page.getByTestId('column-settings-pin-left-crm_opp_expected_amount').click();
  await page.getByTestId('column-settings-pin-right-crm_opp_competitor').click();
  await page.getByTestId('column-settings-save').click();
  await expect(panel).toHaveCount(0);
  await expect(page.getByTestId('personal-view-draft-banner')).toBeVisible();
  await page.getByTestId('personal-view-save-current').click();
  await page.waitForTimeout(2500);
  await expect(page.getByTestId('personal-view-draft-banner')).toHaveCount(0);

  const competitorHeader = page.getByTestId('table-header-crm_opp_competitor');
  const amountHeader = page.getByTestId('table-header-crm_opp_expected_amount');
  await expect(competitorHeader).toBeVisible();
  await expect(competitorHeader).toContainText('竞争对手');
  await expect(page.locator('tr').filter({ hasText: names.proposal })).toContainText('CordysCRM');
  await expect.poll(() => page.getByTestId('table-row-0').evaluate((element) =>
    (element as HTMLElement).style.height,
  )).toBe('32px');
  await expect.poll(() => amountHeader.evaluate((element) => ({
    position: getComputedStyle(element).position,
    left: getComputedStyle(element).left,
  }))).toEqual({ position: 'sticky', left: '40px' });
  await assertNoRawCodes(page);
  await shot(page, testInfo, 'release-c-column-view-applied-desktop.png');

  const personalViews = assertOk(await api(
    '/api/views/personal?modelCode=crm_opportunity_common&pageKey=crm_opportunity_common_list',
  ), 'personal views after column configuration').data ?? [];
  const savedView = personalViews.find((view: any) => view.pid === ids.personalView);
  expect(savedView?.viewConfig?.rowHeight).toBe('short');
  expect(savedView?.viewConfig?.columns).toEqual(expect.arrayContaining([
    expect.objectContaining({
      fieldCode: 'crm_opp_competitor',
      visible: true,
      width: 180,
      frozen: true,
      frozenPosition: 'right',
    }),
    expect.objectContaining({
      fieldCode: 'crm_opp_expected_amount',
      frozen: true,
      frozenPosition: 'left',
    }),
  ]));

  await page.reload({ waitUntil: 'domcontentloaded' });
  await expect(page.getByTestId('table-header-crm_opp_competitor')).toBeVisible();
  await expect.poll(() => page.getByTestId('table-row-0').evaluate((element) =>
    (element as HTMLElement).style.height,
  )).toBe('32px');
  await expect.poll(() => page.getByTestId('table-header-crm_opp_expected_amount')
    .evaluate((element) => getComputedStyle(element).left)).toBe('40px');
  await expect.poll(() => page.getByTestId('table-header-crm_opp_competitor')
    .evaluate((element) => getComputedStyle(element).right)).not.toBe('auto');

  await page.setViewportSize({ width: 960, height: 900 });
  await closeCompactNavigation(page);
  await page.getByTestId('column-settings-btn').click();
  await page.getByTestId('column-settings-search').fill('竞争对手');
  await expect(page.getByTestId('column-settings-visible-crm_opp_competitor')).toBeChecked();
  await assertNoPageOverflow(page);
  await shot(page, testInfo, 'release-c-column-settings-compact.png');
  await page.getByTestId('column-settings-close').click();
  await page.setViewportSize({ width: 1280, height: 720 });

  cover('uiActions', 'crm_opportunity_common_list:platform:configure_view_columns');
  completedScenarios.add('personal-view-column-configuration');
});

test('advanced filters persist and drive the exact exported opportunity fact', async ({ page }, testInfo) => {
  await uiLogin(page);
  await gotoOpportunityList(page, ids.personalView);
  const tableMode = page.getByTestId('list-view-mode-table');
  if ((await tableMode.getAttribute('aria-checked')) !== 'true') await tableMode.click();
  await page.getByRole('button', { name: '全部', exact: true }).click();
  const keywordResponsePromise = page.waitForResponse((response) =>
    response.request().method() === 'GET'
      && response.url().includes('/api/dynamic/crm_opportunity_common/list')
      && new URL(response.url()).searchParams.get('keyword') === RUN,
  { timeout: 20_000 });
  const keywordInput = page.getByPlaceholder(/查询|Search/);
  await keywordInput.fill(RUN);
  await keywordInput.press('Enter');
  expect((await keywordResponsePromise).ok()).toBeTruthy();

  await page.getByTestId('add-filter-btn').click();
  await page.getByTestId('filter-field-crm_opp_expected_amount').click();
  const amountPopover = page.getByTestId('filter-value-popover');
  await amountPopover.getByTestId('filter-operator-select').selectOption('gte');
  await amountPopover.getByTestId('filter-value-input').fill('300000');
  await amountPopover.getByTestId('filter-apply').click();

  await page.getByTestId('add-filter-btn').click();
  await page.getByTestId('filter-field-crm_opp_forecast_category').click();
  const categoryPopover = page.getByTestId('filter-value-popover');
  await categoryPopover.getByTestId('filter-operator-select').selectOption('in');
  await expect(categoryPopover.getByText('管道', { exact: true })).toBeVisible({ timeout: 10_000 });
  await categoryPopover.getByText('管道', { exact: true }).click();
  await categoryPopover.getByText('最佳预期', { exact: true }).click();

  const filteredResponsePromise = page.waitForResponse((response) =>
    response.request().method() === 'GET'
      && response.url().includes('/api/dynamic/crm_opportunity_common/list')
      && response.url().includes('crm_opp_forecast_category'),
  { timeout: 20_000 });
  await categoryPopover.getByTestId('filter-apply').click();
  const filteredResponse = await filteredResponsePromise;
  expect(filteredResponse.ok()).toBeTruthy();
  const requestFilters = JSON.parse(new URL(filteredResponse.url()).searchParams.get('filters') || '[]');
  expect(requestFilters).toEqual(expect.arrayContaining([
    { fieldName: 'crm_opp_expected_amount', operator: 'GTE', value: 300000 },
    {
      fieldName: 'crm_opp_forecast_category',
      operator: 'IN',
      values: ['pipeline', 'best_case'],
    },
  ]));

  await expect(page.locator('tr').filter({ hasText: names.discovery })).toBeVisible();
  await expect(page.locator('tr').filter({ hasText: names.proposal })).toBeVisible();
  await expect(page.locator('tr').filter({ hasText: names.bulkDiscovery })).toHaveCount(0);
  await expect(page.locator('tr').filter({ hasText: names.negotiation })).toHaveCount(0);
  await expect(page.getByTestId('filter-chip-bar')).toContainText('管道、最佳预期');
  await expect(page.getByTestId('filter-chip-bar')).not.toContainText('pipeline');
  await expect(page.getByTestId('filter-chip-bar')).not.toContainText('best_case');
  await shot(page, testInfo, 'release-b-opportunity-advanced-filter-desktop.png');
  await compactShot(page, testInfo, 'release-b-opportunity-advanced-filter-compact.png');

  const filterToggle = page.getByTestId('filters-toggle');
  if (await filterToggle.isVisible()) await filterToggle.click();
  await expect(page.getByTestId('filter-save')).toBeVisible();
  await page.getByTestId('filter-save').click();
  await expect(page.getByTestId('personal-view-draft-banner')).toBeVisible();
  await page.getByTestId('personal-view-save-current').click();
  await expect(page.getByTestId('personal-view-draft-banner')).toHaveCount(0);

  await page.reload({ waitUntil: 'domcontentloaded' });
  await expect(page.getByTestId('filter-chip-bar')).toContainText('预期金额');
  await expect(page.getByTestId('filter-chip-bar')).toContainText('300000');
  await expect(page.getByTestId('filter-chip-bar')).toContainText('预测类别');
  await expect(page.getByTestId('filter-chip-bar')).toContainText('管道、最佳预期');
  await expect(page.locator('tr').filter({ hasText: names.discovery })).toBeVisible();
  await expect(page.locator('tr').filter({ hasText: names.proposal })).toBeVisible();

  const exportResponsePromise = page.waitForResponse((response) =>
    response.request().method() === 'POST'
      && response.url().includes('/api/dynamic/crm_opportunity_common/export'),
  { timeout: 20_000 });
  const downloadPromise = page.waitForEvent('download', { timeout: 20_000 });
  await page.getByTestId('toolbar-more-menu').click();
  await page.getByTestId('more-menu-export-csv').click();
  const [exportResponse, download] = await Promise.all([exportResponsePromise, downloadPromise]);
  expect(exportResponse.ok()).toBeTruthy();
  const exportPayload = exportResponse.request().postDataJSON();
  expect(exportPayload.keyword).toBe(RUN);
  expect(exportPayload.conditions).toEqual(expect.arrayContaining([
    { field: 'crm_opp_expected_amount', operator: 'GTE', value: 300000 },
    {
      field: 'crm_opp_forecast_category',
      operator: 'IN',
      value: ['pipeline', 'best_case'],
    },
  ]));
  expect((await exportResponse.json()).data.recordCount).toBe(2);

  const csvPath = path.join(EVIDENCE_DIR, 'release-b-opportunity-advanced-filter.csv');
  await download.saveAs(csvPath);
  const csv = readFileSync(csvPath, 'utf8');
  expect(csv.trim().split(/\r?\n/)).toHaveLength(3);
  expect(csv).toContain(names.discovery);
  expect(csv).toContain(names.proposal);
  expect(csv).not.toContain(names.bulkDiscovery);
  expect(csv).not.toContain(names.negotiation);

  cover(
    'uiActions',
    'crm_opportunity_common_list:platform:add_advanced_filter',
    'crm_opportunity_common_list:platform:save_advanced_filters',
    'crm_opportunity_common_list:platform:export_filtered_csv',
  );
  completedScenarios.add('advanced-filter-saved-view-export');
});

test('current view analysis aggregates every matching opportunity and drills back to the exact list', async ({ page }, testInfo) => {
  await uiLogin(page);
  await gotoOpportunityList(page, ids.personalView);
  const tableMode = page.getByTestId('list-view-mode-table');
  if ((await tableMode.getAttribute('aria-checked')) !== 'true') await tableMode.click();
  await page.getByRole('button', { name: '全部', exact: true }).click();

  const keywordResponse = page.waitForResponse((response) =>
    response.request().method() === 'GET'
      && response.url().includes('/api/dynamic/crm_opportunity_common/list')
      && new URL(response.url()).searchParams.get('keyword') === RUN,
  { timeout: 20_000 });
  const keywordInput = page.getByTestId('list-search-input');
  await keywordInput.fill(RUN);
  await keywordInput.press('Enter');
  expect((await keywordResponse).ok()).toBeTruthy();

  const aggregateResponse = page.waitForResponse((response) =>
    response.request().method() === 'POST'
      && response.url().includes('/api/meta/chart-data')
      && response.request().postData()?.includes('analysis_value') === true,
  { timeout: 20_000 });
  await page.getByTestId('view-analysis-open').click();
  const drawer = page.getByTestId('view-analysis-drawer');
  await expect(drawer).toBeVisible();
  const firstAggregate = await aggregateResponse;
  expect(firstAggregate.ok()).toBeTruthy();
  const countRequest = firstAggregate.request().postDataJSON();
  expect(countRequest.keyword).toBe(RUN);
  expect(countRequest.metrics).toEqual([
    { field: 'pid', aggregation: 'count', alias: 'analysis_value' },
  ]);
  expect(countRequest.filters).toEqual(expect.arrayContaining([
    { field: 'crm_opp_expected_amount', operator: 'gte', value: 300000 },
    { field: 'crm_opp_forecast_category', operator: 'in', value: ['pipeline', 'best_case'] },
  ]));
  await expect(drawer).toContainText('当前视图');
  await expect(drawer).toContainText(`查询: ${RUN}`);
  await expect(drawer.getByTestId('view-analysis-chart-bar')).toHaveAttribute('aria-pressed', 'true');

  const categoryResponsePromise = page.waitForResponse((response) =>
    response.request().method() === 'POST'
      && response.url().includes('/api/meta/chart-data')
      && response.request().postData()?.includes('crm_opp_forecast_category') === true,
  { timeout: 20_000 });
  await drawer.getByTestId('view-analysis-group-field').selectOption('crm_opp_forecast_category');
  expect((await categoryResponsePromise).ok()).toBeTruthy();

  const sumResponsePromise = page.waitForResponse((response) =>
    response.request().method() === 'POST'
      && response.url().includes('/api/meta/chart-data')
      && response.request().postData()?.includes('"aggregation":"sum"') === true,
  { timeout: 20_000 });
  await drawer.getByTestId('view-analysis-aggregation').selectOption('sum');
  await drawer.getByTestId('view-analysis-metric-field').selectOption('crm_opp_expected_amount');
  const sumResponse = await sumResponsePromise;
  expect(sumResponse.ok()).toBeTruthy();
  const sumBody = await sumResponse.json();
  const rows = sumBody?.data?.rows ?? [];
  expect(rows).toEqual(expect.arrayContaining([
    expect.objectContaining({ crm_opp_forecast_category: 'pipeline', analysis_value: 480000 }),
    expect.objectContaining({ crm_opp_forecast_category: 'best_case', analysis_value: 320000 }),
  ]));
  await expect(drawer.getByText('480,000', { exact: true })).toBeVisible();
  await expect(drawer.getByText('320,000', { exact: true })).toBeVisible();
  await shot(page, testInfo, 'release-c-current-view-analysis-desktop.png');

  for (const chartType of ['line', 'pie', 'donut', 'funnel'] as const) {
    await drawer.getByTestId(`view-analysis-chart-${chartType}`).click();
    await expect(drawer.getByTestId(`view-analysis-chart-${chartType}`)).toHaveAttribute('aria-pressed', 'true');
  }
  await drawer.getByTestId('view-analysis-chart-bar').click();
  await page.setViewportSize({ width: 960, height: 900 });
  await assertNoPageOverflow(page);
  await shot(page, testInfo, 'release-c-current-view-analysis-compact.png');
  await page.setViewportSize({ width: 1280, height: 720 });

  const chartCanvas = drawer.locator('canvas').first();
  await expect(chartCanvas).toBeVisible();
  // Animation is disabled for this self-service chart. Wait for the responsive
  // desktop resize to settle, then hit the geometric centre of the first bar.
  await page.waitForTimeout(350);
  const chartBox = await chartCanvas.boundingBox();
  expect(chartBox).not.toBeNull();
  await chartCanvas.click({
    position: { x: chartBox!.width * 0.33, y: chartBox!.height * 0.72 },
  });
  await expect(drawer).toHaveCount(0);
  await expect(page.getByTestId('filter-chip-bar')).toContainText(/管道|最佳预期/);
  const drilledRows = page.locator('tbody tr');
  await expect(drilledRows).toHaveCount(1, { timeout: 20_000 });
  await expect(page.getByTestId('table-cell-0-crm_opp_forecast_category')).toContainText('管道');
  await expect(page.getByTestId('table-cell-0-crm_opp_expected_amount')).toContainText('480');
  await shot(page, testInfo, 'release-c-current-view-analysis-drilldown.png');

  cover(
    'uiActions',
    'crm_opportunity_common_list:platform:analyze_current_view',
    'crm_opportunity_common_list:platform:drill_chart_to_list',
  );
  completedScenarios.add('current-view-self-service-analysis');
});

test('opportunity keeps the next task and quote in one navigable context', async ({ page }, testInfo) => {
  const relations = await listRecords('crm_activity_relation_common', [
    { fieldName: 'crm_ar_activity_id', operator: 'EQ', value: ids.task },
    { fieldName: 'crm_ar_object_id', operator: 'EQ', value: ids.proposal },
  ]);
  expect(relations).toHaveLength(1);

  await uiLogin(page);
  await page.goto(`${BASE}/p/crm_opportunity_common/view/${ids.proposal}`, {
    waitUntil: 'domcontentloaded',
  });
  await expect(page.getByRole('heading', { name: names.proposal })).toBeVisible({ timeout: 20_000 });
  await page.getByRole('tab', { name: /报价与计划|Quotes & Plan/ }).click();
  await expect(page.getByText(names.task, { exact: true })).toBeVisible();
  await expect(page.getByText(/328,000/).first()).toBeVisible();
  await expect(page.getByRole('button', { name: /安排下一任务|Plan Next Task/ })).toBeVisible();
  await expect(page.getByRole('button', { name: /新建报价摘要|Create Quote Summary/ })).toBeVisible();
  await assertNoRawCodes(page);
  await shot(page, testInfo, 'release-b-opportunity-plan-quote-desktop.png');
  await compactShot(page, testInfo, 'release-b-opportunity-plan-quote-compact.png');

  await page.getByRole('button', { name: /安排下一任务|Plan Next Task/ }).click();
  await expect(page).toHaveURL((url) =>
    url.pathname === '/p/crm_activity_common/new'
      && url.searchParams.get('commandCode') === 'crm:create_opp_task'
      && url.searchParams.get('sourceRecordPid') === ids.proposal,
  );
  await page.goBack({ waitUntil: 'domcontentloaded' });
  await page.getByRole('tab', { name: /报价与计划|Quotes & Plan/ }).click();
  await page.getByRole('button', { name: /新建报价摘要|Create Quote Summary/ }).click();
  await expect(page).toHaveURL((url) =>
    url.pathname === '/p/crm_quote_summary_common/new'
      && url.searchParams.get('dv.crm_qs_opportunity_id') === ids.proposal
      && url.searchParams.get('dv.crm_qs_account_id') === ids.account
      && url.searchParams.get('commandCode') === 'crm:create_quote_summary'
      && url.searchParams.get('sourceRecordPid') === ids.proposal,
  );
  cover('pages', 'crm_opportunity_common_detail');
  cover(
    'blocks',
    'crm_opportunity_common_detail:crm_opp_stage_rail',
    'crm_opportunity_common_detail:crm_opportunity_tabs',
    'crm_opportunity_common_detail:crm_opp_plan_quote_actions',
    'crm_opportunity_common_detail:block_opportunity_plan',
    'crm_opportunity_common_detail:block_opportunity_quotes',
  );
  cover(
    'fields',
    'crm_opportunity_common_detail:block_opportunity_plan:crm_act_subject',
    'crm_opportunity_common_detail:block_opportunity_quotes:crm_qs_quote_amount',
  );
  cover(
    'uiActions',
    'crm_opportunity_common_detail:crm_opportunity_tabs:plan_and_quotes',
    'crm_opportunity_common_detail:crm_opp_plan_quote_actions:create_plan_task',
    'crm_opportunity_common_detail:crm_opp_plan_quote_actions:create_quote_summary',
  );
  completedScenarios.add('opportunity-plan-quote-context');
});

test('opportunity follow-up tasks enforce read-only permissions and complete their lifecycle in context', async ({ page }, testInfo) => {
  const deniedStart = await api('/api/meta/commands/execute/crm:start_task', {
    method: 'POST',
    body: JSON.stringify({
      payload: {},
      targetRecordPid: ids.task,
      operationType: 'update',
    }),
  }, viewerJwt);
  expect(deniedStart.response.status, JSON.stringify(deniedStart.body)).toBe(403);
  expect(String(deniedStart.body?.code), JSON.stringify(deniedStart.body)).not.toBe('0');
  expect((await getRecord('crm_activity_common', ids.task)).crm_act_status).toBe('open');

  await uiLogin(page, VIEWER_EMAIL);
  await page.goto(`${BASE}/p/crm_opportunity_common/view/${ids.proposal}`, {
    waitUntil: 'domcontentloaded',
  });
  await expect(page.getByRole('heading', { name: names.proposal })).toBeVisible({ timeout: 20_000 });
  await page.getByRole('tab', { name: /报价与计划|Quotes & Plan/ }).click();

  const viewerTaskRow = page.locator('tr').filter({ hasText: names.task }).first();
  await expect(viewerTaskRow).toBeVisible();
  await expect(viewerTaskRow.getByRole('button', { name: /查看|View/ })).toBeVisible();
  await expect(viewerTaskRow.getByRole('button', { name: /开始|Start/ })).toHaveCount(0);
  await expect(viewerTaskRow.getByRole('button', { name: /完成|Complete/ })).toHaveCount(0);
  await expect(viewerTaskRow.getByRole('button', { name: /取消|Cancel/ })).toHaveCount(0);
  await expect(page.getByRole('button', { name: /安排下一任务|Plan Next Task/ })).toHaveCount(0);
  await assertNoRawCodes(page);
  await shot(page, testInfo, 'release-e-opportunity-follow-up-viewer-permission.png');

  await uiLogin(page);
  await page.goto(`${BASE}/p/crm_opportunity_common/view/${ids.proposal}`, {
    waitUntil: 'domcontentloaded',
  });
  await expect(page.getByRole('heading', { name: names.proposal })).toBeVisible({ timeout: 20_000 });
  await page.getByRole('tab', { name: /报价与计划|Quotes & Plan/ }).click();

  let taskRow = page.locator('tr').filter({ hasText: names.task }).first();
  await taskRow.getByRole('button', { name: /查看|View/ }).click();
  await expect(page).toHaveURL((url) =>
    url.pathname === `/p/crm_activity_common/view/${ids.task}`,
  );
  await page.goBack({ waitUntil: 'domcontentloaded' });
  await page.getByRole('tab', { name: /报价与计划|Quotes & Plan/ }).click();

  taskRow = page.locator('tr').filter({ hasText: names.task }).first();
  const startResponse = page.waitForResponse((response) =>
    response.request().method() === 'POST'
      && response.url().includes('/api/meta/commands/execute/crm:start_task'),
  { timeout: 20_000 });
  await taskRow.getByRole('button', { name: /开始|Start/ }).click();
  expect((await startResponse).ok()).toBeTruthy();
  await expect(taskRow).toContainText(/进行中|In Progress/);

  const completeResponse = page.waitForResponse((response) =>
    response.request().method() === 'POST'
      && response.url().includes('/api/meta/commands/execute/crm:complete_task'),
  { timeout: 20_000 });
  await taskRow.getByRole('button', { name: /完成|Complete/ }).click();
  expect((await completeResponse).ok()).toBeTruthy();
  await expect(taskRow).toContainText(/已完成|Done/);
  await expect(taskRow.getByRole('button', { name: /开始|Start/ })).toHaveCount(0);
  await expect(taskRow.getByRole('button', { name: /完成|Complete/ })).toHaveCount(0);
  await expect(taskRow.getByRole('button', { name: /取消|Cancel/ })).toHaveCount(0);

  const cancelRow = page.locator('tr').filter({ hasText: names.cancelTask }).first();
  const cancelResponse = page.waitForResponse((response) =>
    response.request().method() === 'POST'
      && response.url().includes('/api/meta/commands/execute/crm:cancel_task'),
  { timeout: 20_000 });
  await cancelRow.getByRole('button', { name: /取消|Cancel/ }).click();
  const dialog = page.getByTestId('confirm-dialog');
  await expect(dialog).toContainText(/取消后不可再开始或完成|cannot be started or completed/);
  await shot(page, testInfo, 'release-e-opportunity-follow-up-cancel-confirmation.png');
  await page.getByTestId('confirm-ok').click();
  expect((await cancelResponse).ok()).toBeTruthy();
  await expect(cancelRow).toContainText(/已取消|Cancelled/);

  expect((await getRecord('crm_activity_common', ids.task)).crm_act_status).toBe('done');
  expect((await getRecord('crm_activity_common', ids.cancelTask)).crm_act_status).toBe('cancelled');
  await assertNoRawCodes(page);
  await shot(page, testInfo, 'release-e-opportunity-follow-up-lifecycle-complete.png');

  cover(
    'commands',
    'crm:start_task',
    'crm:complete_task',
    'crm:cancel_task',
  );
  cover(
    'uiActions',
    'crm_opportunity_common_detail:block_opportunity_plan:view_task',
    'crm_opportunity_common_detail:block_opportunity_plan:start_task',
    'crm_opportunity_common_detail:block_opportunity_plan:complete_task',
    'crm_opportunity_common_detail:block_opportunity_plan:cancel_task',
  );
  cover('permissions', 'crm.activity.manage');
  completedScenarios.add('opportunity-follow-up-task-lifecycle');
});

test('opportunity activity tab merges business follow-up with system changes', async ({ page }, testInfo) => {
  await uiLogin(page);
  await page.goto(`${BASE}/p/crm_opportunity_common/view/${ids.proposal}`, {
    waitUntil: 'domcontentloaded',
  });
  await expect(page.getByRole('heading', { name: names.proposal })).toBeVisible({ timeout: 20_000 });
  await page.getByRole('tab', { name: /活动|Activities/ }).click();

  const timeline = page.getByTestId('activity-timeline');
  await expect(timeline).toBeVisible({ timeout: 20_000 });
  await expect(timeline.getByText(/客户互动时间线|Customer activity timeline/)).toBeVisible();
  await page.getByTestId('activity-timeline-filter-task').click();
  await expect(timeline.getByText(names.task, { exact: true })).toBeVisible();
  await page.getByTestId('activity-timeline-filter-system').click();
  await expect(timeline.locator('[data-activity-type]').first()).toBeVisible();
  await page.getByTestId('activity-timeline-filter-all').click();
  await expect(timeline.getByText(names.task, { exact: true })).toBeVisible();
  await assertNoRawCodes(page);
  await shot(page, testInfo, 'release-b-opportunity-merged-activity-timeline.png');
  cover('pages', 'crm_opportunity_common_detail');
  cover(
    'blocks',
    'crm_opportunity_common_detail:crm_opp_stage_rail',
    'crm_opportunity_common_detail:crm_opportunity_tabs',
    'crm_opportunity_common_detail:block_activities',
  );
  cover('fields', 'crm_opportunity_common_detail:block_activities:crm_act_subject');
  cover('queries', 'crm_account_timeline');
  cover('uiActions', 'crm_opportunity_common_detail:crm_opportunity_tabs:activities');
  completedScenarios.add('merged-customer-activity-timeline');
});

test('account dashboard keeps the account fact when drilling into contacts', async ({ page }, testInfo) => {
  const accountStats = assertOk(
    await api(
      `/api/datasource/list?datasourceId=nq:crm_account_stats&accountId=${encodeURIComponent(ids.account)}&format=records&maxItems=1`,
    ),
    'account dashboard stats',
  );
  const statsRecord = accountStats?.data?.records?.[0] ?? accountStats?.data?.[0] ?? {};
  expect(Number(statsRecord.total_contacts)).toBeGreaterThanOrEqual(1);

  await uiLogin(page);
  await page.goto(
    `${BASE}/dashboards/view/crm_account_360?recordPid=${encodeURIComponent(ids.account)}`,
    { waitUntil: 'domcontentloaded' },
  );
  const contactWidget = page.getByTestId('dashboard-block-stats_contacts');
  await expect(contactWidget).toContainText(/联系人|Contacts/, { timeout: 20_000 });
  await expect(contactWidget).toContainText(String(statsRecord.total_contacts));
  const recentOpportunities = page.getByTestId('dashboard-block-recent_opportunities');
  await expect(recentOpportunities.getByText(names.proposal, { exact: true })).toBeVisible({
    timeout: 20_000,
  });
  await expect(recentOpportunities).not.toContainText('crm_opp_name');
  const recentActivities = page.getByTestId('dashboard-block-recent_activities');
  await expect(recentActivities.getByText(names.task, { exact: true })).toBeVisible({
    timeout: 20_000,
  });
  await expect(recentActivities).not.toContainText('crm_act_subject');
  cover('queries', 'crm_account_stats', 'crm_account_timeline');
  cover(
    'dashboardTargets',
    'crm_account_360:stats_contacts:stats_contacts',
    'crm_account_360:recent_opportunities:recent_opportunities',
    'crm_account_360:recent_activities:recent_activities',
  );
  await shot(page, testInfo, 'release-b-account-360-record-scoped-kpis.png');

  await contactWidget.locator('[data-card-style="metric"][role="button"]').click();
  await expect(page).toHaveURL(/\/p\/crm_contact_common\?/, { timeout: 15_000 });
  expect(decodeListFilters(page.url())).toEqual([
    { fieldCode: 'crm_ct_account_id', operator: 'eq', value: ids.account },
  ]);
  await expect(page.getByTestId('dynamic-list')).toBeVisible({ timeout: 20_000 });
  await expect(page.locator('tr').filter({ hasText: names.contact })).toBeVisible();
  await shot(page, testInfo, 'release-b-account-360-contact-drilldown.png');
  cover('pages', 'crm_contact_common_list');
  cover('blocks', 'crm_contact_common_list:crm_contact_table');
  cover('fields', 'crm_contact_common_list:crm_contact_table:crm_ct_name');
  completedScenarios.add('account-dashboard-drilldown-fact');
});

test('bulk opportunity actions protect lifecycle fields and execute state commands', async ({ page }, testInfo) => {
  await executeTransition('crm:qualify_opportunity', ids.discovery);
  await uiLogin(page);
  await page.goto(`${BASE}/p/crm_opportunity_common?keyword=${encodeURIComponent(RUN)}`, {
    waitUntil: 'domcontentloaded',
  });
  await expect(page.getByTestId('dynamic-list')).toBeVisible({ timeout: 20_000 });
  const tableMode = page.getByTestId('list-view-mode-table');
  if ((await tableMode.getAttribute('aria-checked')) !== 'true') {
    await tableMode.click();
  }
  // Saved views are a server-side user concern, so a fresh browser context can
  // still inherit the advanced filters persisted by the preceding journey.
  // Reset presentation state explicitly before proving the bulk-action journey.
  const clearAllViewState = page
    .getByTestId('filter-chip-bar')
    .getByRole('button', { name: /清除全部|Clear All/ });
  if (await clearAllViewState.isVisible()) await clearAllViewState.click();
  await page.getByRole('button', { name: '全部', exact: true }).click();

  const discoveryRow = page.locator('tr').filter({ hasText: names.discovery });
  const bulkDiscoveryRow = page.locator('tr').filter({ hasText: names.bulkDiscovery });
  await expect(discoveryRow).toBeVisible();
  await expect(bulkDiscoveryRow).toBeVisible();
  await discoveryRow.locator('input[type="checkbox"]').click();
  await bulkDiscoveryRow.locator('input[type="checkbox"]').click();
  await expect(page.getByTestId('bulk-edit-btn')).toBeVisible();

  await page.getByTestId('bulk-edit-btn').click();
  const bulkDialog = page.getByTestId('bulk-edit-dialog');
  await expect(bulkDialog).toBeVisible();
  const editableValues = await page
    .getByTestId('bulk-edit-field')
    .locator('option')
    .evaluateAll((options) => options.map((option) => (option as HTMLOptionElement).value));
  expect(editableValues).toEqual(
    expect.arrayContaining([
      'crm_opp_expected_amount',
      'crm_opp_expected_close_date',
      'crm_opp_probability',
    ]),
  );
  expect(editableValues).not.toEqual(
    expect.arrayContaining(['crm_opp_stage', 'crm_opp_forecast_category', 'crm_opp_owner']),
  );
  await shot(page, testInfo, 'release-b-opportunity-safe-bulk-edit-fields.png');
  await bulkDialog.getByRole('button', { name: /取消|Cancel/ }).click();

  await page.getByTestId('bulk-action-bulk_qualify').click();
  const resultDialog = page.getByTestId('bulk-action-result-dialog');
  await expect(resultDialog).toBeVisible({ timeout: 20_000 });
  await expect(resultDialog).toContainText('成功 1 条');
  await expect(resultDialog).toContainText('失败 1 条');
  await expect(resultDialog).toContainText(names.discovery);
  await expect(resultDialog).toContainText('当前记录状态不满足操作条件');
  await expect
    .poll(
      async () => {
        const [first, second] = await Promise.all([
          getRecord('crm_opportunity_common', ids.discovery),
          getRecord('crm_opportunity_common', ids.bulkDiscovery),
        ]);
        return [first.crm_opp_stage, second.crm_opp_stage];
      },
      { timeout: 20_000 },
    )
    .toEqual(['qualification', 'qualification']);
  await shot(page, testInfo, 'release-b-opportunity-bulk-qualified.png');
  await compactShot(page, testInfo, 'release-b-opportunity-bulk-mixed-result-compact.png');
  cover('uiActions', 'crm_opportunity_common_list:crm_opp_table:bulk_qualify');
  completedScenarios.add('safe-bulk-opportunity-lifecycle');
});

test('forecast first screen prioritizes operating facts over execution counters', async ({ page }, testInfo) => {
  await uiLogin(page);
  await page.goto(`${BASE}/p/c/crm_forecast_cockpit`, { waitUntil: 'domcontentloaded' });
  await expect(page.getByText(/预测驾驶舱|Forecast Cockpit/).first()).toBeVisible({ timeout: 20_000 });

  const primaryKeys = ['commit', 'best_case', 'weighted', 'pipeline'];
  const primaryBoxes = [];
  for (const key of primaryKeys) {
    const metric = page.getByTestId(`metric-strip-item-${key}`);
    await expect(metric).toBeVisible();
    primaryBoxes.push(await metric.boundingBox());
  }
  expect(primaryBoxes.every(Boolean)).toBeTruthy();
  expect(primaryBoxes.map((box) => Math.round(box!.x))).toEqual(
    [...primaryBoxes].sort((a, b) => a!.x - b!.x).map((box) => Math.round(box!.x)),
  );
  const executionQueueTab = page.getByRole('tab', { name: /执行队列|Execution Queue/ });
  const executionQueueBox = await executionQueueTab.boundingBox();
  const viewportHeight = await page.evaluate(() => window.innerHeight);
  expect(executionQueueBox).not.toBeNull();
  for (const box of primaryBoxes) {
    expect(box!.y + box!.height, 'primary forecast metrics must be fully visible in the first screen')
      .toBeLessThanOrEqual(viewportHeight);
    expect(box!.y + box!.height, 'primary forecast metrics must precede the execution queue')
      .toBeLessThan(executionQueueBox!.y);
  }
  await expect(page.getByTestId('metric-strip-item-commit')).toContainText(/承诺|Commit/);
  await expect(page.getByTestId('metric-strip-item-best_case')).toContainText(/最佳预期|Best Case/);
  await expect(page.getByTestId('metric-strip-item-weighted')).toContainText(/加权预测|Weighted Forecast/);
  await expect(page.getByTestId('metric-strip-item-pipeline')).toContainText(/总管道|Total Pipeline/);
  await expect(page.getByTestId('metric-strip-item-open_deals')).toContainText(/在途商机|Open Deals/);
  await expect(page.getByTestId('metric-strip-item-drafts')).toContainText(/待提交|Drafts/);
  await expect(page.getByTestId('metric-strip-item-submitted')).toContainText(/待复核|In Review/);
  await expect(page.getByText(/团队下钻|Team Drill-down/).first()).toBeVisible();
  const forecastRow = page.locator('tr').filter({ hasText: names.forecastPeriod }).first();
  await expect(forecastRow).toBeVisible();
  await expect(forecastRow).toContainText(/180,000/);
  await expect(forecastRow).toContainText(/500,000/);
  await expect(forecastRow).toContainText(/980,000/);
  await assertNoRawCodes(page);
  await assertNoPageOverflow(page);
  await shot(page, testInfo, 'release-b-forecast-cockpit-desktop.png');
  await page.setViewportSize({ width: 960, height: 900 });
  await closeCompactNavigation(page);
  await assertNoPageOverflow(page);
  const compactSidebarBox = await page.getByTestId('sidebar').boundingBox();
  const compactContentLeft = compactSidebarBox && compactSidebarBox.x + compactSidebarBox.width > 0
    ? compactSidebarBox.x + compactSidebarBox.width
    : 0;
  for (const key of primaryKeys) {
    const box = await page.getByTestId(`metric-strip-item-${key}`).boundingBox();
    expect(box, `${key} metric must remain rendered at compact width`).not.toBeNull();
    expect(box!.x, `${key} metric must not overlap the compact sidebar`)
      .toBeGreaterThanOrEqual(compactContentLeft);
    expect(box!.x + box!.width, `${key} metric must not be clipped on the right`)
      .toBeLessThanOrEqual(960);
  }
  await shot(page, testInfo, 'release-b-forecast-cockpit-compact.png');
  await page.setViewportSize({ width: 1280, height: 720 });
  cover('pages', 'crm_forecast_cockpit');
  cover(
    'blocks',
    'crm_forecast_cockpit:crm_forecast_metrics',
    'crm_forecast_cockpit:crm_forecast_execution_metrics',
    'crm_forecast_cockpit:crm_forecast_tabs',
    'crm_forecast_cockpit:crm_forecast_submission_queue',
    'crm_forecast_cockpit:crm_forecast_owner_queue',
  );
  cover(
    'fields',
    'crm_forecast_cockpit:crm_forecast_submission_queue:crm_fcst_period',
    'crm_forecast_cockpit:crm_forecast_submission_queue:crm_fcst_commit_amount',
    'crm_forecast_cockpit:crm_forecast_submission_queue:crm_fcst_best_case_amount',
    'crm_forecast_cockpit:crm_forecast_submission_queue:crm_fcst_pipeline_amount',
  );
  completedScenarios.add('forecast-first-screen-hierarchy');
});
