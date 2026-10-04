/** R4: controlled non-empty data, real menu/author/query actions, exact values and restore. */
import { test, expect, type Page } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { ensureSidebarExpanded, navigateToMenuByClick } from '../helpers';

test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json', locale: 'zh-CN' });
test.describe.configure({ mode: 'serial', timeout: 120000 });
const EV = process.env.AURA_EVIDENCE_DIR!;
const run = randomUUID().replaceAll('-', '').slice(0, 12);
const model = `console_golden_orders_${run}`;
const topnModel = `console_golden_topn_${run}`;
const statuses = ['draft', 'confirmed', 'shipped', 'completed'];
const dates = ['2026-01-15', '2026-02-15'];
const limits = [10, 50, 100, 200, 500];
const grains = ['day', 'week', 'month', 'quarter', 'year'] as const;
type Grain = (typeof grains)[number];
const bucketDates: Record<Grain, string[]> = {
  day: dates,
  week: ['2026-01-12', '2026-02-09'],
  month: ['2026-01-01', '2026-02-01'],
  quarter: ['2026-01-01', '2026-01-01'],
  year: ['2026-01-01', '2026-01-01'],
};
const yaml = `version: "0.1"
semantic_model:
  code: ${model}
  label:
    zh-CN: 控制台金样订单
  model_ref: e2et_order
  primary_entity: order
entities:
  - name: order
    type: primary
    field_ref: pid
dimensions:
  - code: order_status
    label:
      zh-CN: 订单状态
    field_ref: e2et_order_status
    type: categorical
  - code: created_day
    label:
      zh-CN: 下单日期
    field_ref: e2et_order_date
    type: time
    time_grains: [day, week, month, quarter, year]
    primary_time: true
measures:
  - code: order_count
    agg: COUNT
    field_ref: pid
metrics:
  - code: order_count_metric
    label:
      zh-CN: 订单数
    type: simple
    type_params:
      measure: order_count
access_policies:
  - access_grant: fixture_scope
    user_attribute: fixture_scope
    sql_filter: "e2et_order_title LIKE 'SC-${run}-%'"
`;
const topnYaml = yaml
  .replace(`code: ${model}`, `code: ${topnModel}`)
  .replace('code: order_status', 'code: order_title')
  .replace('zh-CN: 订单状态', 'zh-CN: 订单标题')
  .replace('field_ref: e2et_order_status', 'field_ref: e2et_order_title')
  .replace(`SC-${run}-%`, `SCTOP-${run}-%`);

async function openConsole(page: Page) {
  await page.goto('/');
  await ensureSidebarExpanded(page);
  const catalog = page.waitForResponse(r => new URL(r.url()).pathname === '/api/semantic/meta'
    && r.request().method() === 'GET');
  await navigateToMenuByClick(page, ['元数据管理', '语义模型']);
  const catalogBody = await (await catalog).json();
  expect(String(catalogBody.code)).toBe('0');
  await expect(page).toHaveURL(/\/semantic\/models$/);
  await expect(page.getByTestId('semantic-models-page')).toBeVisible();
  await expect(page.getByTestId('semantic-models-loading')).toHaveCount(0);
  await expect(page.getByTestId('semantic-models-error')).toHaveCount(0);
}

test.beforeAll(async ({ request }) => {
  // Setup only. Authoring/publishing/querying are browser actions below.
  for (let i = 0; i < 12; i++) {
    const response = await request.post('/api/dynamic/e2et_order/create', { data: {
      e2et_order_title: `SC-${run}-${i}`, e2et_order_type: 'normal',
      e2et_order_urgent: false, e2et_order_status: statuses[i % 4],
      e2et_order_date: dates[i < 6 ? 0 : 1],
    } });
    expect(response.ok(), await response.text()).toBeTruthy();
    const body = await response.json();
    expect(String(body.code), JSON.stringify(body)).toBe('0');
  }
  // Every offered limit is smaller than this namespace. Each title is its own
  // group with exact count one; no pre-existing row can satisfy the truncation.
  // Bound concurrency; actual fixture setup duration still needs CI verification.
  for (let offset = 0; offset < 501; offset += 8) {
    await Promise.all(Array.from({ length: Math.min(8, 501 - offset) }, async (_, n) => {
      const response = await request.post('/api/dynamic/e2et_order/create', { data: {
        e2et_order_title: `SCTOP-${run}-${String(offset + n).padStart(3, '0')}`,
        e2et_order_type: 'normal', e2et_order_urgent: false,
        e2et_order_status: 'draft', e2et_order_date: dates[0],
      } });
      expect(response.ok(), await response.text()).toBeTruthy();
      const body = await response.json();
      expect(String(body.code), JSON.stringify(body)).toBe('0');
    }));
  }
});

test('SC-00 seed controlled orders and publish through the console', async ({ page }) => {
  await openConsole(page);
  await page.getByTestId('semantic-tab-author').click();
  await page.getByTestId('semantic-plugin-code').fill('test-fixtures');
  await page.getByTestId('semantic-yaml-editor').fill(yaml);
  const validated = page.waitForResponse(r => r.url().endsWith('/api/semantic/validate') && r.request().method() === 'POST');
  await page.getByTestId('semantic-validate').click();
  expect(String((await (await validated).json()).code)).toBe('0');
  await expect(page.getByTestId('semantic-author-ok')).toBeVisible();
  const published = page.waitForResponse(r => r.url().endsWith('/api/semantic/publish') && r.request().method() === 'POST');
  await page.getByTestId('semantic-publish').click();
  expect(String((await (await published).json()).code)).toBe('0');
  await expect(page.getByTestId(`semantic-model-item-${model}`)).toBeVisible();
  await expect(page.getByTestId('semantic-author-error')).toHaveCount(0);
  await page.screenshot({ path: `${EV}/sc-00-business.png`, fullPage: true });
  await page.getByTestId('semantic-yaml-editor').fill(topnYaml);
  const topnPublished = page.waitForResponse(r => r.url().endsWith('/api/semantic/publish') && r.request().method() === 'POST');
  await page.getByTestId('semantic-publish').click();
  expect(String((await (await topnPublished).json()).code)).toBe('0');
  await expect(page.getByTestId(`semantic-model-item-${topnModel}`)).toBeVisible();
  await expect(page.getByTestId('semantic-author-error')).toHaveCount(0);
});

test('SC-01 console exploration: TopN, governed run, time grain, save-restore', async ({ page }) => {
  await openConsole(page);
  await page.getByTestId(`semantic-model-item-${model}`).click();
  await expect(page.getByTestId('semantic-metric-order_count_metric').locator('input')).toBeChecked();
  await page.getByTestId('semantic-dim-order_status').locator('input').check();
  await page.getByTestId('semantic-limit').selectOption('10');

  const runQuery = async (grain?: Grain) => {
    const responsePromise = page.waitForResponse(r => r.url().endsWith('/api/semantic/query') && r.request().method() === 'POST');
    const usagePromise = page.waitForResponse(r => r.url().includes('/api/semantic/usage/summary'));
    await page.getByTestId('semantic-run-query').click();
    const response = await responsePromise;
    expect(response.request().postDataJSON()).toEqual({
      metrics: [`${model}.order_count_metric`],
      dimensions: grain ? [`${model}.order_status`, `${model}.created_day__${grain}`] : [`${model}.order_status`],
      limit: 10,
    });
    const body = await response.json();
    expect(String(body.code), JSON.stringify(body)).toBe('0');
    const expected = new Map<string, number>();
    for (let i = 0; i < 12; i++) {
      const key = grain ? `${statuses[i % 4]}|${bucketDates[grain][i < 6 ? 0 : 1]}` : statuses[i % 4];
      expected.set(key, (expected.get(key) || 0) + 1);
    }
    const actual = body.data.rows.map((row: Record<string, unknown>) => {
      if (grain) expect(String(row[`${model}.created_day__${grain}`]))
        .toMatch(/^\d{4}-\d{2}-\d{2}(?:T| )00:00:00(?:\.0+)?(?:Z|[+-]00(?::?00)?)?$/);
      const bucket = grain ? String(row[`${model}.created_day__${grain}`]).slice(0, 10) : '';
      const key = grain ? `${row[`${model}.order_status`]}|${bucket}` : String(row[`${model}.order_status`]);
      return [key, Number(row[`${model}.order_count_metric`])];
    });
    expect(actual.sort()).toEqual([...expected.entries()].sort());
    const usageBody = await (await usagePromise).json();
    expect(String(usageBody.code)).toBe('0');
    expect(usageBody.data.totalQueries).toBeGreaterThan(0);
    await expect(page.getByTestId('semantic-usage-strip')).toContainText(String(usageBody.data.totalQueries));
    await expect(page.getByTestId('semantic-query-error')).toHaveCount(0);
    const rows = page.getByTestId('semantic-query-result').locator('tbody tr');
    await expect(rows).toHaveCount(expected.size);
    for (const [key, count] of expected) {
      const [status, bucket] = key.split('|');
      let row = rows.filter({ has: page.getByRole('cell', { name: status, exact: true }) });
      if (bucket) row = row.filter({ hasText: bucket });
      await expect(row).toHaveCount(1);
      await expect(row.getByRole('cell', { name: String(count), exact: true })).toHaveCount(1);
    }
  };
  await runQuery();
  await page.screenshot({ path: `${EV}/sc-01-business.png`, fullPage: true });
  await page.getByTestId('semantic-dim-created_day').locator('input').check();
  for (const grain of grains) {
    await page.getByTestId('semantic-grain-created_day').selectOption(grain);
    await runQuery(grain);
    const restored = page.waitForResponse(r => r.url().endsWith('/api/semantic/meta'));
    await page.reload();
    expect(String((await (await restored).json()).code)).toBe('0');
    await page.getByTestId(`semantic-model-item-${model}`).click();
    await expect(page.getByTestId('semantic-grain-created_day')).toHaveValue(grain);
    await expect(page.getByTestId('semantic-limit')).toHaveValue('10');
    await expect(page.getByTestId('semantic-dim-order_status').locator('input')).toBeChecked();
    await expect(page.getByTestId('semantic-dim-created_day').locator('input')).toBeChecked();
    await expect(page.getByTestId('semantic-metric-order_count_metric').locator('input')).toBeChecked();
    await runQuery(grain);
    await page.screenshot({ path: `${EV}/bi-ui-13-grain-${grain}.png`, fullPage: true });
  }
  await page.getByTestId('semantic-grain-created_day').selectOption('month');
  await runQuery('month');
  const restoredCatalog = page.waitForResponse(r => r.url().endsWith('/api/semantic/meta'));
  await page.reload();
  expect(String((await (await restoredCatalog).json()).code)).toBe('0');
  await page.getByTestId(`semantic-model-item-${model}`).click();
  await expect(page.getByTestId('semantic-limit')).toHaveValue('10');
  await expect(page.getByTestId('semantic-dim-created_day').locator('input')).toBeChecked();
  await expect(page.getByTestId('semantic-dim-order_status').locator('input')).toBeChecked();
  await expect(page.getByTestId('semantic-metric-order_count_metric').locator('input')).toBeChecked();
  await expect(page.getByTestId('semantic-grain-created_day')).toHaveValue('month');
  // Rerun the restored state: checkbox visibility alone is not persistence proof.
  await runQuery('month');
  await page.screenshot({ path: `${EV}/semantic-console-restore.png`, fullPage: true });

  const runTopN = async (limit: number) => {
    const responsePromise = page.waitForResponse(r => r.url().endsWith('/api/semantic/query') && r.request().method() === 'POST');
    await page.getByTestId('semantic-run-query').click();
    const response = await responsePromise;
    expect(response.request().postDataJSON()).toEqual({
      metrics: [`${topnModel}.order_count_metric`], dimensions: [`${topnModel}.order_title`], limit,
    });
    const body = await response.json();
    expect(String(body.code), JSON.stringify(body)).toBe('0');
    expect(body.data.rowcount).toBe(limit);
    expect(body.data.rows).toHaveLength(limit);
    const ownedTitles = new Set(Array.from({ length: 501 }, (_, i) => `SCTOP-${run}-${String(i).padStart(3, '0')}`));
    const titles = body.data.rows.map((row: Record<string, unknown>) => String(row[`${topnModel}.order_title`]));
    expect(new Set(titles).size).toBe(limit);
    for (const row of body.data.rows) {
      expect(ownedTitles.has(String(row[`${topnModel}.order_title`]))).toBe(true);
      expect(Number(row[`${topnModel}.order_count_metric`])).toBe(1);
    }
    await expect(page.getByTestId('semantic-query-error')).toHaveCount(0);
    await expect(page.getByTestId('semantic-query-result').locator('tbody tr')).toHaveCount(limit);
  };
  await page.getByTestId(`semantic-model-item-${topnModel}`).click();
  const totalResponse = page.waitForResponse(r => r.url().endsWith('/api/semantic/query') && r.request().method() === 'POST');
  await page.getByTestId('semantic-run-query').click();
  const total = await totalResponse;
  expect(total.request().postDataJSON()).toEqual({
    metrics: [`${topnModel}.order_count_metric`], dimensions: [], limit: 100,
  });
  const totalBody = await total.json();
  expect(String(totalBody.code), JSON.stringify(totalBody)).toBe('0');
  expect(totalBody.data.rows).toEqual([{ [`${topnModel}.order_count_metric`]: 501 }]);
  await expect(page.getByTestId('semantic-query-result').getByRole('cell', { name: '501', exact: true })).toBeVisible();
  await page.getByTestId('semantic-dim-order_title').locator('input').check();
  for (const limit of limits) {
    await page.getByTestId('semantic-limit').selectOption(String(limit));
    await runTopN(limit);
    const restored = page.waitForResponse(r => r.url().endsWith('/api/semantic/meta'));
    await page.reload();
    expect(String((await (await restored).json()).code)).toBe('0');
    await page.getByTestId(`semantic-model-item-${topnModel}`).click();
    await expect(page.getByTestId('semantic-limit')).toHaveValue(String(limit));
    await expect(page.getByTestId('semantic-dim-order_title').locator('input')).toBeChecked();
    await expect(page.getByTestId('semantic-metric-order_count_metric').locator('input')).toBeChecked();
    await runTopN(limit);
    await page.screenshot({ path: `${EV}/bi-ui-14-limit-${limit}.png` });
  }
  // Switching back must restore the first model's independent preferences.
  await page.getByTestId(`semantic-model-item-${model}`).click();
  await expect(page.getByTestId('semantic-limit')).toHaveValue('10');
  await expect(page.getByTestId('semantic-grain-created_day')).toHaveValue('month');
  await runQuery('month');
});
