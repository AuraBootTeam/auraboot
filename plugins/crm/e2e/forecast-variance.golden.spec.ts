import { expect, test, type Page, type TestInfo } from '@playwright/test';
import { mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';

const BASE = process.env.PLAYWRIGHT_BASE_URL || 'http://127.0.0.1:5198';
const BE = process.env.BACKEND_URL || 'http://127.0.0.1:6498';
const RUN = process.env.FORECAST_VARIANCE_RUN_ID || `forecast-variance-${Date.now()}`;
const EVIDENCE_DIR =
  process.env.FORECAST_VARIANCE_EVIDENCE_DIR ||
  path.resolve(process.cwd(), '.workspace', 'evidence', 'crm-forecast-variance');
const ADMIN_EMAIL = 'admin@auraboot.com';
const PASSWORD = 'Test2026x';
const SALES_EMAIL = `${RUN}@e2e.local`;
const SERVICE_EMAIL = `${RUN}-service@e2e.local`;
// crm_fcst_period is a compact business period key (maxLength=20); keep the
// evidence run id separate so diagnostic names never leak into domain fields.
const PERIOD_KEY = Date.now().toString(36).slice(-8).toUpperCase();
const FIRST_PERIOD = `FV-${PERIOD_KEY}-A`;
const SECOND_PERIOD = `FV-${PERIOD_KEY}-B`;
const FORECAST_VARIANCE_COVERAGE = {
  queries: ['crm_forecast_variance_summary', 'crm_forecast_variance_drivers'],
  blocks: [
    'crm_forecast_cockpit:crm_forecast_variance_summary_intro',
    'crm_forecast_cockpit:crm_forecast_variance_summary',
    'crm_forecast_cockpit:crm_forecast_variance_drivers_intro',
    'crm_forecast_cockpit:crm_forecast_variance_drivers',
  ],
  fields: [
    'crm_forecast_cockpit:crm_forecast_variance_summary:measure',
    'crm_forecast_cockpit:crm_forecast_variance_summary:submitted_amount',
    'crm_forecast_cockpit:crm_forecast_variance_summary:current_amount',
    'crm_forecast_cockpit:crm_forecast_variance_summary:variance_amount',
    'crm_forecast_cockpit:crm_forecast_variance_drivers:crm_opp_name',
    'crm_forecast_cockpit:crm_forecast_variance_drivers:account_name',
    'crm_forecast_cockpit:crm_forecast_variance_drivers:crm_opp_stage',
    'crm_forecast_cockpit:crm_forecast_variance_drivers:forecast_category',
    'crm_forecast_cockpit:crm_forecast_variance_drivers:crm_opp_expected_amount',
    'crm_forecast_cockpit:crm_forecast_variance_drivers:crm_opp_probability',
    'crm_forecast_cockpit:crm_forecast_variance_drivers:crm_opp_expected_close_date',
    'crm_forecast_cockpit:crm_forecast_variance_drivers:variance_driver',
  ],
  uiActions: [
    'crm_forecast_cockpit:crm_forecast_tabs:variance',
    'crm_forecast_cockpit:crm_forecast_variance_drivers:open_variance_opportunity',
  ],
} as const;

const ids = {
  salesUser: '',
  serviceUser: '',
  account: '',
  overdueOpportunity: '',
  bestCaseOpportunity: '',
  pipelineOpportunity: '',
  firstForecast: '',
  secondForecast: '',
};
let adminJwt = '';
let salesJwt = '';
let serviceJwt = '';
const screenshots: string[] = [];
const completedActions = new Set<string>();

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
  expect(
    result.response.ok,
    `${label}: HTTP ${result.response.status} ${JSON.stringify(result.body)}`,
  ).toBeTruthy();
  expect(String(result.body?.code), `${label}: ${JSON.stringify(result.body)}`).toBe('0');
  return result.body;
}

async function loginApi(email: string): Promise<string> {
  const body = assertOk(
    await api(
      '/api/auth/login',
      {
        method: 'POST',
        body: JSON.stringify({ email, password: PASSWORD }),
      },
      '',
    ),
    `login ${email}`,
  );
  const jwt = findValue(body?.data, ['jwt']);
  expect(jwt, `login ${email} must return a JWT`).toBeTruthy();
  return String(jwt);
}

async function provisionUser(
  email: string,
  roleCode: string,
  displayName: string,
): Promise<string> {
  const body = assertOk(
    await api('/api/admin/users', {
      method: 'POST',
      body: JSON.stringify({
        email,
        displayName,
        initialPassword: PASSWORD,
        roleCodes: [roleCode],
        sendInviteEmail: false,
      }),
    }),
    `provision ${roleCode}`,
  );
  const pid = findValue(body?.data, ['pid', 'userPid']);
  expect(pid, `${roleCode} user must return a public pid`).toBeTruthy();
  return String(pid);
}

async function executeCreate(
  code: string,
  payload: Record<string, unknown>,
  jwt: string,
): Promise<string> {
  const body = assertOk(
    await api(
      `/api/meta/commands/execute/${code}`,
      {
        method: 'POST',
        body: JSON.stringify({ payload, operationType: 'create' }),
      },
      jwt,
    ),
    code,
  );
  const pid = findValue(body?.data?.data ?? body?.data, [
    'recordId',
    'recordPid',
    'publicRecordId',
    'pid',
  ]);
  expect(pid, `${code} must return a public pid`).toBeTruthy();
  return String(pid);
}

async function uiLogin(page: Page, email: string): Promise<void> {
  await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded' });
  const identifier = page.locator('input#identifier, input#email').first();
  await expect(identifier).toBeVisible({ timeout: 15_000 });
  await identifier.click();
  await identifier.pressSequentially(email, { delay: 15 });
  const password = page.locator('input#password').first();
  await password.click();
  await password.pressSequentially(PASSWORD, { delay: 15 });
  await expect(identifier).toHaveValue(email);
  await expect(password).toHaveValue(PASSWORD);
  await page
    .getByRole('button', { name: /立即登录|登录|Sign in|Login/i })
    .first()
    .click();
  await page.waitForURL((url) => !url.pathname.includes('/login'), {
    timeout: 30_000,
  });
  if (page.url().includes('tenant-selection')) {
    await page
      .getByRole('button', { name: /进入|选择|Enter|AuraBoot/ })
      .first()
      .click();
    await page.waitForURL((url) => !url.pathname.includes('tenant-selection'), {
      timeout: 20_000,
    });
  }
  await expect(page.locator('input#email, input#identifier')).toHaveCount(0);
}

async function openForecastCockpitFromMenu(page: Page): Promise<void> {
  const nav = page.locator('nav, aside, [role="navigation"]').first();
  await expect(nav).toBeVisible({ timeout: 15_000 });
  const link = nav.locator('a[href="/p/c/crm_forecast_cockpit"]').first();
  if (!(await link.isVisible().catch(() => false))) {
    await nav
      .getByRole('button', { name: /客户关系管理|CRM/i })
      .first()
      .click();
  }
  await expect(link).toBeVisible({ timeout: 10_000 });
  const statsResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'GET' &&
      response.url().includes('/api/datasource/list') &&
      new URL(response.url()).searchParams.get('datasourceId') === 'nq:crm_forecast_cockpit_stats',
    { timeout: 20_000 },
  );
  await link.click();
  expect((await statsResponse).ok()).toBeTruthy();
  await expect(page).toHaveURL(/\/p\/c\/crm_forecast_cockpit(?:[?#].*)?$/);
  await expect(page.getByText(/预测驾驶舱|Forecast Cockpit/).first()).toBeVisible();
  completedActions.add('menu-open-forecast-cockpit');
}

async function searchForecast(page: Page, period: string): Promise<void> {
  const input = page.getByTestId('field-crm_fcst_period').locator('input');
  await expect(input).toBeVisible();
  await input.fill(period);
  const response = page.waitForResponse(
    (candidate) =>
      candidate.request().method() === 'GET' &&
      candidate.url().includes('/api/dynamic/crm_forecast_submission/list'),
    { timeout: 20_000 },
  );
  await page.getByTestId('filter-btn-search').click();
  expect((await response).ok()).toBeTruthy();
}

function varianceResponse(page: Page, queryCode: string, forecastPid: string) {
  return page.waitForResponse(
    (response) => {
      if (
        response.request().method() !== 'GET' ||
        !response.url().includes('/api/datasource/list')
      ) {
        return false;
      }
      const params = new URL(response.url()).searchParams;
      return (
        params.get('datasourceId') === `nq:${queryCode}` &&
        params.get('submissionPid') === forecastPid
      );
    },
    { timeout: 20_000 },
  );
}

async function selectForecast(page: Page, period: string, forecastPid: string) {
  const summaryPromise = varianceResponse(page, 'crm_forecast_variance_summary', forecastPid);
  const driversPromise = varianceResponse(page, 'crm_forecast_variance_drivers', forecastPid);
  const row = page.locator('tr').filter({ hasText: period }).first();
  await expect(row).toBeVisible({ timeout: 20_000 });
  await row.locator('td').filter({ hasText: period }).first().click();
  const [summaryResponse, driversResponse] = await Promise.all([summaryPromise, driversPromise]);
  expect(summaryResponse.ok(), `summary HTTP ${summaryResponse.status()}`).toBeTruthy();
  expect(driversResponse.ok(), `drivers HTTP ${driversResponse.status()}`).toBeTruthy();
  return {
    summary: await summaryResponse.json(),
    drivers: await driversResponse.json(),
  };
}

function records(body: any): Record<string, any>[] {
  return body?.data?.records ?? body?.data?.rows ?? body?.data?.content ?? [];
}

async function screenshot(page: Page, testInfo: TestInfo, name: string): Promise<void> {
  const output = testInfo.outputPath(name);
  mkdirSync(path.dirname(output), { recursive: true });
  await page.screenshot({ path: output, fullPage: true });
  await testInfo.attach(name, { path: output, contentType: 'image/png' });
  screenshots.push(output);
}

async function collapseSidebarForCompact(page: Page): Promise<void> {
  const mobileToggle = page.getByTestId('header-sidebar-toggle');
  if (
    (await mobileToggle.isVisible().catch(() => false)) &&
    (await mobileToggle.getAttribute('aria-expanded')) === 'true'
  ) {
    await mobileToggle.click();
  }
  await expect(page.getByTestId('sidebar-mobile-backdrop')).toHaveCount(0);

  const sidebar = page.getByTestId('sidebar');
  const collapse = page.getByTitle('Collapse sidebar');
  if (!String(await sidebar.getAttribute('class')).includes('w-[68px]')) {
    await collapse.evaluate((element: HTMLElement) => element.click());
  }
  await expect
    .poll(async () => (await sidebar.boundingBox())?.width ?? 0, {
      message: 'compact screenshot requires the sidebar to finish collapsing',
    })
    .toBeLessThanOrEqual(80);
}

async function assertNoTechnicalLeak(page: Page): Promise<void> {
  const body = await page.locator('main, [role="main"]').first().innerText();
  expect(body).not.toMatch(/\bcrm_(?:opp|fcst)_[a-z_]+\b/i);
  expect(body).not.toMatch(
    /\b(?:best_case|closed_won|closed_lost|close_date_overdue|mitigate_risk|follow_up)\b/,
  );
  expect(body).not.toMatch(/\b[0-9A-HJKMNP-TV-Z]{26}\b/);
}

test.beforeAll(async () => {
  adminJwt = await loginApi(ADMIN_EMAIL);
  ids.salesUser = await provisionUser(SALES_EMAIL, 'crm_sales', `${RUN} 华东销售`);
  ids.serviceUser = await provisionUser(SERVICE_EMAIL, 'crm_service', `${RUN} 客服`);
  salesJwt = await loginApi(SALES_EMAIL);
  serviceJwt = await loginApi(SERVICE_EMAIL);

  ids.account = await executeCreate(
    'crm:create_account',
    {
      crm_acc_name: `${RUN} 华东智造`,
      crm_acc_industry: 'manufacturing',
      crm_acc_status: 'active',
    },
    salesJwt,
  );
  const yesterday = new Date(Date.now() - 24 * 60 * 60 * 1000).toISOString();
  const nextWeek = new Date(Date.now() + 7 * 24 * 60 * 60 * 1000).toISOString();
  const nextMonth = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000).toISOString();
  ids.overdueOpportunity = await executeCreate(
    'crm:create_opportunity',
    {
      crm_opp_name: `${RUN} 逾期承诺商机`,
      crm_opp_account_id: ids.account,
      crm_opp_currency_code: 'CNY',
      crm_opp_expected_amount: 120000,
      crm_opp_expected_close_date: yesterday,
      crm_opp_probability: 80,
      crm_opp_forecast_category: 'commit',
      crm_opp_owner: ids.salesUser,
    },
    salesJwt,
  );
  ids.bestCaseOpportunity = await executeCreate(
    'crm:create_opportunity',
    {
      crm_opp_name: `${RUN} 最佳预期商机`,
      crm_opp_account_id: ids.account,
      crm_opp_currency_code: 'CNY',
      crm_opp_expected_amount: 180000,
      crm_opp_expected_close_date: nextWeek,
      crm_opp_probability: 60,
      crm_opp_forecast_category: 'best_case',
      crm_opp_owner: ids.salesUser,
    },
    salesJwt,
  );
  ids.pipelineOpportunity = await executeCreate(
    'crm:create_opportunity',
    {
      crm_opp_name: `${RUN} 管道商机`,
      crm_opp_account_id: ids.account,
      crm_opp_currency_code: 'CNY',
      crm_opp_expected_amount: 300000,
      crm_opp_expected_close_date: nextMonth,
      crm_opp_probability: 30,
      crm_opp_forecast_category: 'pipeline',
      crm_opp_owner: ids.salesUser,
    },
    salesJwt,
  );
  ids.firstForecast = await executeCreate(
    'crm:create_forecast',
    {
      crm_fcst_period: FIRST_PERIOD,
      crm_fcst_commit_amount: 100000,
      crm_fcst_best_case_amount: 250000,
      crm_fcst_pipeline_amount: 550000,
      crm_fcst_notes: '解释第一版预测与实时管道的差额。',
    },
    salesJwt,
  );
  ids.secondForecast = await executeCreate(
    'crm:create_forecast',
    {
      crm_fcst_period: SECOND_PERIOD,
      crm_fcst_commit_amount: 110000,
      crm_fcst_best_case_amount: 290000,
      crm_fcst_pipeline_amount: 590000,
      crm_fcst_notes: '切换提交后应重新计算差额。',
    },
    salesJwt,
  );
});

test('manager explains forecast variance from the CRM menu and drills into the exact opportunity', async ({
  page,
}, testInfo) => {
  await uiLogin(page, ADMIN_EMAIL);
  await openForecastCockpitFromMenu(page);

  await searchForecast(page, FIRST_PERIOD);
  const first = await selectForecast(page, FIRST_PERIOD, ids.firstForecast);
  const summaryRows = records(first.summary);
  expect(summaryRows).toHaveLength(3);
  const summaryByMeasure = new Map(summaryRows.map((row) => [row.measure, row]));
  expect(Number(summaryByMeasure.get('commit')?.submitted_amount)).toBe(100000);
  expect(Number(summaryByMeasure.get('commit')?.current_amount)).toBe(120000);
  expect(Number(summaryByMeasure.get('commit')?.variance_amount)).toBe(20000);
  expect(Number(summaryByMeasure.get('best_case')?.current_amount)).toBe(300000);
  expect(Number(summaryByMeasure.get('best_case')?.variance_amount)).toBe(50000);
  expect(Number(summaryByMeasure.get('pipeline')?.current_amount)).toBe(600000);
  expect(Number(summaryByMeasure.get('pipeline')?.variance_amount)).toBe(50000);
  const driverRows = records(first.drivers);
  expect(driverRows).toHaveLength(3);
  expect(driverRows.map((row) => row.pid)).toEqual(
    expect.arrayContaining([
      ids.overdueOpportunity,
      ids.bestCaseOpportunity,
      ids.pipelineOpportunity,
    ]),
  );
  expect(driverRows.find((row) => row.pid === ids.overdueOpportunity)?.variance_driver).toBe(
    'close_date_overdue',
  );
  completedActions.add('nq-summary-exact');
  completedActions.add('nq-drivers-exact');

  await page.getByRole('tab', { name: /偏差解释|Variance Explanation/ }).click();
  await expect(page.getByText(/提交预测与实时事实|Submitted Forecast vs Live Facts/)).toBeVisible();
  await expect(page.getByText(/偏差商机驱动|Opportunity Variance Drivers/)).toBeVisible();
  for (const label of [
    /预测口径|Measure/,
    /已提交|Submitted/,
    /实时事实|Live Facts/,
    /偏差|Variance/,
    /商机|Opportunity/,
    /客户|Account/,
    /阶段|Stage/,
    /预测类别|Forecast Category/,
    /预计金额|Amount/,
    /概率|Probability/,
    /预计关闭|Close Date/,
    /经理动作|Manager Action/,
  ]) {
    await expect(page.getByRole('columnheader', { name: label }).first()).toBeVisible();
  }
  await expect(
    page
      .locator('tr')
      .filter({ hasText: /承诺|Commit/ })
      .first(),
  ).toContainText(/100,000/);
  await expect(
    page
      .locator('tr')
      .filter({ hasText: /最佳预期|Best Case/ })
      .first(),
  ).toContainText(/250,000/);
  await expect(
    page
      .locator('tr')
      .filter({ hasText: /总管道|Pipeline/ })
      .first(),
  ).toContainText(/550,000/);
  const overdueDriverRow = page
    .locator('tr')
    .filter({ hasText: `${RUN} 逾期承诺商机` })
    .first();
  await expect(overdueDriverRow).toContainText(`${RUN} 华东智造`);
  await expect(overdueDriverRow).toContainText(/发现|Discovery/);
  await expect(overdueDriverRow).toContainText(/承诺|Commit/);
  await expect(overdueDriverRow).toContainText(/120,000/);
  await expect(overdueDriverRow).toContainText(/80%/);
  await expect(overdueDriverRow).toContainText(/关闭日期逾期|Close Date Overdue/);
  await assertNoTechnicalLeak(page);
  completedActions.add('open-variance-tab');

  await page.setViewportSize({ width: 1440, height: 1000 });
  await screenshot(page, testInfo, `${RUN}-forecast-variance-desktop.png`);
  await page.setViewportSize({ width: 768, height: 1000 });
  await collapseSidebarForCompact(page);
  await expect
    .poll(async () =>
      page.evaluate(
        () => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1,
      ),
    )
    .toBe(true);
  await screenshot(page, testInfo, `${RUN}-forecast-variance-compact.png`);
  completedActions.add('desktop-compact-visual');

  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.getByRole('tab', { name: /执行队列|Execution Queue/ }).click();
  await searchForecast(page, SECOND_PERIOD);
  const second = await selectForecast(page, SECOND_PERIOD, ids.secondForecast);
  const secondSummary = new Map(records(second.summary).map((row) => [row.measure, row]));
  expect(Number(secondSummary.get('commit')?.submitted_amount)).toBe(110000);
  expect(Number(secondSummary.get('commit')?.variance_amount)).toBe(10000);
  expect(Number(secondSummary.get('pipeline')?.variance_amount)).toBe(10000);
  completedActions.add('selection-refetch');

  await searchForecast(page, FIRST_PERIOD);
  await selectForecast(page, FIRST_PERIOD, ids.firstForecast);
  await page.getByRole('tab', { name: /偏差解释|Variance Explanation/ }).click();
  const overdueRow = page
    .locator('tr')
    .filter({ hasText: `${RUN} 逾期承诺商机` })
    .first();
  await expect(overdueRow).toBeVisible();
  await overdueRow.getByTestId('row-action-open_variance_opportunity').click();
  await expect(page).toHaveURL(
    new RegExp(`/p/crm_opportunity_common/view/${ids.overdueOpportunity}`),
  );
  await expect(page.getByText(`${RUN} 逾期承诺商机`, { exact: true }).first()).toBeVisible();
  completedActions.add('drill-exact-opportunity');

  const denied = await api(
    `/api/datasource/list?datasourceId=${encodeURIComponent('nq:crm_forecast_variance_drivers')}` +
      `&format=records&submissionPid=${encodeURIComponent(ids.firstForecast)}`,
    {},
    serviceJwt,
  );
  expect(denied.response.status, JSON.stringify(denied.body)).toBe(403);
  expect(String(denied.body?.code), JSON.stringify(denied.body)).not.toBe('0');
  completedActions.add('permission-negative');

  const expectedActions = [
    'menu-open-forecast-cockpit',
    'nq-summary-exact',
    'nq-drivers-exact',
    'open-variance-tab',
    'desktop-compact-visual',
    'selection-refetch',
    'drill-exact-opportunity',
    'permission-negative',
  ];
  expect([...completedActions].sort()).toEqual(expectedActions.sort());
  const receipt = {
    schemaVersion: 1,
    runId: RUN,
    source: 'plugins/crm/e2e/forecast-variance.golden.spec.ts',
    baseUrl: BASE,
    backendUrl: BE,
    recordIds: ids,
    expectedActions,
    completedActions: [...completedActions].sort(),
    assertions: {
      summaryRows: 3,
      driverRows: 3,
      firstVariance: { commit: 20000, bestCase: 50000, pipeline: 50000 },
      secondVariance: { commit: 10000, pipeline: 10000 },
      menuDriven: true,
      uiLogin: true,
      retries: 0,
      workerCount: 1,
    },
    coverage: Object.fromEntries(
      Object.entries(FORECAST_VARIANCE_COVERAGE).map(([axis, expected]) => [
        axis,
        { expected: [...expected], completed: [...expected] },
      ]),
    ),
    screenshots,
    verdict: 'pass',
  };
  mkdirSync(EVIDENCE_DIR, { recursive: true });
  writeFileSync(
    path.join(EVIDENCE_DIR, `crm-forecast-variance-${RUN}.json`),
    `${JSON.stringify(receipt, null, 2)}\n`,
  );
});
