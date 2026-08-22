import {
  expect,
  request as playwrightRequest,
  test,
  type APIRequestContext,
  type Page,
} from '@playwright/test';
import fs from 'node:fs';

const PASSWORD = 'Test2026x';
const BACKEND_ACTOR_EMAIL = requiredEnv('SCALE_BACKEND_ACTOR_EMAIL');
const BFF_ACTOR_EMAIL = requiredEnv('SCALE_BFF_ACTOR_EMAIL');
const BROWSER_ACTOR_EMAIL = requiredEnv('SCALE_BROWSER_ACTOR_EMAIL');
const BACKEND_URL = requiredEnv('SCALE_BACKEND_URL').replace(/\/$/, '');
const BFF_URL = requiredEnv('SCALE_BFF_URL').replace(/\/$/, '');
const MARKER = requiredEnv('SCALE_MARKER');
const REPORT_PATH = requiredEnv('SCALE_REPORT_PATH');
const DATASET_SIZE = Number(process.env.SCALE_DATASET_SIZE ?? '100000');
const API_SAMPLES = Number(process.env.SCALE_API_SAMPLES ?? '20');
const BROWSER_SAMPLES = Number(process.env.SCALE_BROWSER_SAMPLES ?? '10');
const RATE_WINDOW_PAUSE_MS = Number(process.env.SCALE_RATE_WINDOW_PAUSE_MS ?? '61000');
const SEARCH_NEEDLE = `${MARKER}-NEEDLE`;
const API_BUDGETS = {
  statsP95Ms: 450,
  statsP99Ms: 500,
  queueP95Ms: 1_000,
  queueP99Ms: 1_200,
  searchP95Ms: 300,
  searchP99Ms: 400,
};
const BROWSER_BUDGETS = {
  loadP95Ms: 2_500,
  searchP95Ms: 1_200,
  p99Ms: 3_000,
};

type Measurement = {
  minMs: number;
  p50Ms: number;
  p95Ms: number;
  p99Ms: number;
  maxMs: number;
  meanMs: number;
  samples: number;
};

test('100k customer-pool API, BFF and browser stay inside fixed budgets', async ({
  page,
}, testInfo) => {
  const backend = await authenticatedContext(BACKEND_URL, BACKEND_ACTOR_EMAIL);
  const bff = await authenticatedContext(BFF_URL, BFF_ACTOR_EMAIL);
  const failures: string[] = [];
  try {
    const surfaces: Record<string, Record<string, Measurement>> = {};
    const apiSurfaces = Object.entries({ backend, bff });
    for (const [index, [surface, context]] of apiSurfaces.entries()) {
      if (index > 0) await pauseForQueryWindow();
      surfaces[surface] = {
        stats: await measureApi(context, statsUrl(), API_SAMPLES, 'stats'),
        queue: await measureApi(context, queueUrl(''), API_SAMPLES, 'queue'),
        search: await measureApi(context, queueUrl(SEARCH_NEEDLE), API_SAMPLES, 'search'),
      };
      assertBudget(
        `${surface}.stats`,
        surfaces[surface].stats,
        API_BUDGETS.statsP95Ms,
        API_BUDGETS.statsP99Ms,
        failures,
      );
      assertBudget(
        `${surface}.queue`,
        surfaces[surface].queue,
        API_BUDGETS.queueP95Ms,
        API_BUDGETS.queueP99Ms,
        failures,
      );
      assertBudget(
        `${surface}.search`,
        surfaces[surface].search,
        API_BUDGETS.searchP95Ms,
        API_BUDGETS.searchP99Ms,
        failures,
      );
    }

    await pauseForQueryWindow();
    await uiLogin(page, BROWSER_ACTOR_EMAIL);
    const loadTimes: number[] = [];
    await loadWorkbench(page);
    for (let sample = 0; sample < BROWSER_SAMPLES; sample += 1) {
      const startedAt = performance.now();
      await loadWorkbench(page, true);
      loadTimes.push(performance.now() - startedAt);
    }

    await pauseForQueryWindow();
    const searchTimes: number[] = [];
    const searchInput = page.getByRole('textbox', {
      name: /搜索池内客户|Search pooled customers/,
    });
    for (let sample = 0; sample < BROWSER_SAMPLES; sample += 1) {
      // Change the controlled value without submitting it so every timed sample
      // consumes exactly one named-query request from the production rate limit.
      await searchInput.fill(`reset-${sample}`);
      await searchInput.fill(SEARCH_NEEDLE);
      const startedAt = performance.now();
      await triggerSearch(page, SEARCH_NEEDLE);
      await expect(page.getByRole('row', { name: new RegExp(SEARCH_NEEDLE) })).toBeVisible();
      searchTimes.push(performance.now() - startedAt);
    }
    const browser = {
      load: summarize(loadTimes),
      search: summarize(searchTimes),
    };
    assertBudget(
      'browser.load',
      browser.load,
      BROWSER_BUDGETS.loadP95Ms,
      BROWSER_BUDGETS.p99Ms,
      failures,
    );
    assertBudget(
      'browser.search',
      browser.search,
      BROWSER_BUDGETS.searchP95Ms,
      BROWSER_BUDGETS.p99Ms,
      failures,
    );

    const screenshotPath = testInfo.outputPath('customer-pool-100k-search.png');
    await page.screenshot({ path: screenshotPath });
    await testInfo.attach('customer-pool-100k-search', { path: screenshotPath });
    const report = {
      verdict: failures.length === 0 ? 'pass' : 'fail',
      claim:
        'fixed 100k backend API, BFF proxy and Chromium workbench evidence; database results are reported separately',
      datasetSize: DATASET_SIZE,
      apiSamples: API_SAMPLES,
      browserSamples: BROWSER_SAMPLES,
      marker: MARKER,
      budgets: { api: API_BUDGETS, browser: BROWSER_BUDGETS },
      surfaces,
      browser,
      failures,
    };
    fs.writeFileSync(REPORT_PATH, `${JSON.stringify(report, null, 2)}\n`);
    await testInfo.attach('customer-pool-runtime-scale.json', {
      body: Buffer.from(JSON.stringify(report, null, 2)),
      contentType: 'application/json',
    });
    expect(failures, failures.join('\n')).toEqual([]);
  } finally {
    await backend.dispose();
    await bff.dispose();
  }
});

async function authenticatedContext(baseURL: string, email: string): Promise<APIRequestContext> {
  const bootstrap = await playwrightRequest.newContext({ baseURL });
  const login = await bootstrap.post('/api/auth/login', {
    data: { email, password: PASSWORD },
  });
  const body = await login.json().catch(() => ({}));
  const token = String(body?.data?.jwt ?? '');
  expect(login.ok() && String(body?.code) === '0' && token, `${baseURL} login`).toBeTruthy();
  await bootstrap.dispose();
  return playwrightRequest.newContext({
    baseURL,
    extraHTTPHeaders: { Authorization: `Bearer ${token}` },
  });
}

async function measureApi(
  context: APIRequestContext,
  url: string,
  samples: number,
  kind: 'stats' | 'queue' | 'search',
): Promise<Measurement> {
  for (let warm = 0; warm < 3; warm += 1) await executeApi(context, url, kind);
  const times: number[] = [];
  for (let sample = 0; sample < samples; sample += 1) {
    const startedAt = performance.now();
    await executeApi(context, url, kind);
    times.push(performance.now() - startedAt);
  }
  return summarize(times);
}

async function executeApi(
  context: APIRequestContext,
  url: string,
  kind: 'stats' | 'queue' | 'search',
): Promise<void> {
  const response = await context.get(url);
  const body = await response.json().catch(() => ({}));
  expect(
    response.ok() && String(body?.code) === '0',
    `${kind} HTTP/application result: HTTP ${response.status()} ${JSON.stringify(body).slice(0, 500)}`,
  ).toBe(true);
  const records = Array.isArray(body?.data?.records) ? body.data.records : [];
  if (kind === 'stats') {
    const total = ['available_count', 'owned_count', 'processing_count']
      .map((field) => Number(records[0]?.[field] ?? 0))
      .reduce((sum, value) => sum + value, 0);
    expect(total).toBe(DATASET_SIZE);
  } else if (kind === 'queue') {
    expect(records.length).toBe(50);
  } else {
    expect(records).toHaveLength(1);
    expect(String(records[0]?.crm_cpi_account_name)).toBe(SEARCH_NEEDLE);
  }
}

async function uiLogin(page: Page, email: string): Promise<void> {
  await page.goto('/login', { waitUntil: 'domcontentloaded' });
  await page.waitForLoadState('networkidle', { timeout: 10_000 }).catch(() => {});
  const identifier = page
    .locator('input[placeholder*="用户名"], input[name="identifier"], input[type="email"]')
    .first();
  await identifier.fill(email);
  await page.getByRole('textbox', { name: '密码' }).fill(PASSWORD);
  await page.getByRole('button', { name: '立即登录', exact: true }).click();
  await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 20_000 });
}

async function loadWorkbench(page: Page, reload = false): Promise<void> {
  const stats = page.waitForResponse(isStatsResponse, { timeout: 30_000 });
  const queue = page.waitForResponse(isQueueResponse, { timeout: 30_000 });
  if (reload) await page.reload({ waitUntil: 'domcontentloaded' });
  else await page.goto('/p/c/crm_customer_pool_item_list', { waitUntil: 'domcontentloaded' });
  const [statsResponse, queueResponse] = await Promise.all([stats, queue]);
  expect(statsResponse.ok() && queueResponse.ok()).toBe(true);
  await expect(page.getByTestId('metric-strip-item-available')).toContainText('65000');
  await expect(page.locator('tbody tr').first()).toBeVisible();
}

async function triggerSearch(page: Page, keyword: string): Promise<void> {
  const responsePromise = page.waitForResponse(
    (response) => {
      if (!isQueueResponse(response)) return false;
      const url = new URL(response.url());
      return (url.searchParams.get('customerKeyword') ?? '') === keyword;
    },
    { timeout: 30_000 },
  );
  await page.getByTestId('filter-btn-search').click();
  const response = await responsePromise;
  expect(response.ok()).toBe(true);
}

function isStatsResponse(response: { url(): string }): boolean {
  const url = new URL(response.url());
  return url.searchParams.get('datasourceId') === 'nq:crm_customer_pool_ops_stats';
}

function isQueueResponse(response: { url(): string }): boolean {
  const url = new URL(response.url());
  return url.searchParams.get('datasourceId') === 'nq:crm_customer_pool_ops_queue';
}

function statsUrl(): string {
  return '/api/datasource/list?datasourceId=nq%3Acrm_customer_pool_ops_stats&format=records&maxItems=1';
}

function queueUrl(keyword: string): string {
  return `/api/datasource/list?datasourceId=nq%3Acrm_customer_pool_ops_queue&format=records&maxItems=50&customerKeyword=${encodeURIComponent(keyword)}&viewFilter=`;
}

function summarize(values: number[]): Measurement {
  const sorted = [...values].sort((left, right) => left - right);
  return {
    minMs: round(sorted[0]),
    p50Ms: percentile(sorted, 0.5),
    p95Ms: percentile(sorted, 0.95),
    p99Ms: percentile(sorted, 0.99),
    maxMs: round(sorted.at(-1) ?? 0),
    meanMs: round(sorted.reduce((sum, value) => sum + value, 0) / sorted.length),
    samples: sorted.length,
  };
}

function assertBudget(
  label: string,
  measurement: Measurement,
  p95Budget: number,
  p99Budget: number,
  failures: string[],
): void {
  if (measurement.p95Ms > p95Budget)
    failures.push(`${label} p95 ${measurement.p95Ms}ms > ${p95Budget}ms`);
  if (measurement.p99Ms > p99Budget)
    failures.push(`${label} p99 ${measurement.p99Ms}ms > ${p99Budget}ms`);
}

function percentile(sorted: number[], quantile: number): number {
  return round(sorted[Math.max(0, Math.ceil(sorted.length * quantile) - 1)] ?? 0);
}

function round(value: number): number {
  return Math.round(value * 1_000) / 1_000;
}

function requiredEnv(name: string): string {
  const value = process.env[name]?.trim();
  if (!value) throw new Error(`${name} is required`);
  return value;
}

async function pauseForQueryWindow(): Promise<void> {
  await new Promise((resolvePromise) => setTimeout(resolvePromise, RATE_WINDOW_PAUSE_MS));
}
