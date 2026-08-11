import { expect, test, type Page, type TestInfo } from '@playwright/test';
import { mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';

const BASE = process.env.PLAYWRIGHT_BASE_URL || 'http://127.0.0.1:5191';
const BE = process.env.BACKEND_URL || 'http://127.0.0.1:6491';
const RUN = process.env.OPPORTUNITY_EFFICIENCY_RUN_ID || `opp-eff-${Date.now()}`;
const EVIDENCE_DIR =
  process.env.OPPORTUNITY_EFFICIENCY_EVIDENCE_DIR ||
  path.join('/tmp', `crm-opportunity-efficiency-${RUN}`);
const ADMIN_EMAIL = 'admin@auraboot.com';
const PASSWORD = 'Test2026x';
const TODAY = new Date().toISOString().slice(0, 10);
const NEXT_MONTH = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000).toISOString();

const names = {
  discovery: `${RUN} 华东智造云发现阶段`,
  proposal: `${RUN} 华东智造云方案提报`,
  negotiation: `${RUN} 华东智造云商务谈判`,
  task: `${RUN} 完成技术方案复核`,
  forecastPeriod: `FY26Q3-${RUN.slice(-10)}`,
};

const ids = {
  account: '',
  discovery: '',
  proposal: '',
  negotiation: '',
  task: '',
  quote: '',
  forecast: '',
  personalView: '',
  pipelineBoard: '',
};

const expectedScenarios = [
  'shared-list-kanban-fact',
  'personal-view-persistence',
  'opportunity-plan-quote-context',
  'forecast-first-screen-hierarchy',
] as const;
const completedScenarios = new Set<(typeof expectedScenarios)[number]>();
const screenshots: string[] = [];
let adminJwt = '';

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

async function executeTransition(code: string, targetRecordPid: string): Promise<void> {
  assertOk(await api(`/api/meta/commands/execute/${code}`, {
    method: 'POST',
    body: JSON.stringify({ payload: {}, targetRecordPid, operationType: 'update' }),
  }), code);
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
    crm_opp_forecast_category: 'pipeline',
  });
  ids.proposal = await executeCreate('crm:create_opportunity', {
    crm_opp_name: names.proposal,
    crm_opp_account_id: ids.account,
    crm_opp_currency_code: 'CNY',
    crm_opp_expected_amount: 320000,
    crm_opp_expected_close_date: NEXT_MONTH,
    crm_opp_probability: 60,
    crm_opp_forecast_category: 'best_case',
  });
  ids.negotiation = await executeCreate('crm:create_opportunity', {
    crm_opp_name: names.negotiation,
    crm_opp_account_id: ids.account,
    crm_opp_currency_code: 'CNY',
    crm_opp_expected_amount: 180000,
    crm_opp_expected_close_date: NEXT_MONTH,
    crm_opp_probability: 85,
    crm_opp_forecast_category: 'commit',
  });
  await executeTransition('crm:qualify_opportunity', ids.proposal);
  await executeTransition('crm:advance_opp_to_proposal', ids.proposal);
  await executeTransition('crm:qualify_opportunity', ids.negotiation);
  await executeTransition('crm:advance_opp_to_proposal', ids.negotiation);
  await executeTransition('crm:advance_opp_to_negotiation', ids.negotiation);

  ids.task = await executeCreate('crm:create_opp_task', {
    sourceRecordPid: ids.proposal,
    crm_act_subject: names.task,
    crm_act_content: '与售前、交付共同复核 QDP 范围和报价假设。',
    crm_act_due_date: TODAY,
    crm_act_priority: 'high',
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
}

async function uiLogin(page: Page): Promise<void> {
  const response = await page.request.post(`${BASE}/login`, {
    form: { email: ADMIN_EMAIL, password: PASSWORD, remember: 'on', redirectTo: '/' },
    maxRedirects: 0,
  });
  expect([302, 303], `UI login: HTTP ${response.status()}`).toContain(response.status());
  await page.goto(`${BASE}/`, { waitUntil: 'domcontentloaded' });
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
  ids.pipelineBoard = String(
    accessibleViews.find((view: any) =>
      view.viewKey === 'crm_opportunity_pipeline_board'
        || view.name === '$i18n:crm.saved_view.pipeline_board'
        || view.name === 'Pipeline Board')?.pid || '',
  );
  expect(ids.pipelineBoard).toBeTruthy();
  await seedJourney();
});

test.afterAll(() => {
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
    technicalVerdict: expectedScenarios.every((scenario) => completedScenarios.has(scenario))
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
  completedScenarios.add('opportunity-plan-quote-context');
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
  completedScenarios.add('forecast-first-screen-hierarchy');
});
