/** R4: controlled non-empty data, real menu/author/query actions, exact values and restore. */
import { test, expect, type Page } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { ensureSidebarExpanded, navigateToMenuByClick } from '../helpers';

test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json', locale: 'zh-CN' });
test.describe.configure({ mode: 'serial', timeout: 120000 });
const EV = process.env.AURA_EVIDENCE_DIR!;
const run = randomUUID().replaceAll('-', '').slice(0, 12);
const model = `console_golden_orders_${run}`;
const statuses = ['draft', 'confirmed', 'shipped', 'completed'];
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
      zh-CN: 创建日期
    field_ref: created_at
    type: time
    time_grains: [day, month]
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

async function openConsole(page: Page) {
  await page.goto('/');
  await ensureSidebarExpanded(page);
  const catalog = page.waitForResponse(r => r.url().endsWith('/api/semantic/meta'));
  await navigateToMenuByClick(page, ['语义模型']);
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
    } });
    expect(response.ok(), await response.text()).toBeTruthy();
    const body = await response.json();
    expect(String(body.code), JSON.stringify(body)).toBe('0');
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
});

test('SC-01 console exploration: TopN, governed run, time grain, save-restore', async ({ page }) => {
  await openConsole(page);
  await page.getByTestId(`semantic-model-item-${model}`).click();
  await expect(page.getByTestId('semantic-metric-order_count_metric').locator('input')).toBeChecked();
  await page.getByTestId('semantic-dim-order_status').locator('input').check();
  await page.getByTestId('semantic-limit').selectOption('10');

  const runQuery = async (month: boolean) => {
    const responsePromise = page.waitForResponse(r => r.url().endsWith('/api/semantic/query') && r.request().method() === 'POST');
    const usagePromise = page.waitForResponse(r => r.url().includes('/api/semantic/usage/summary'));
    await page.getByTestId('semantic-run-query').click();
    const response = await responsePromise;
    expect(response.request().postDataJSON()).toEqual({
      metrics: [`${model}.order_count_metric`],
      dimensions: month ? [`${model}.order_status`, `${model}.created_day__month`] : [`${model}.order_status`],
      limit: 10,
    });
    const body = await response.json();
    expect(String(body.code), JSON.stringify(body)).toBe('0');
    expect(body.data.rows).toHaveLength(4);
    expect(body.data.rows.map((r: Record<string, unknown>) => r[`${model}.order_status`]).sort()).toEqual([...statuses].sort());
    for (const row of body.data.rows) {
      expect(Number(row[`${model}.order_count_metric`])).toBe(3);
      if (month) expect(String(row[`${model}.created_day__month`])).toMatch(/-01(?:T| )/);
    }
    const usageBody = await (await usagePromise).json();
    expect(String(usageBody.code)).toBe('0');
    expect(usageBody.data.totalQueries).toBeGreaterThan(0);
    await expect(page.getByTestId('semantic-usage-strip')).toContainText(String(usageBody.data.totalQueries));
    await expect(page.getByTestId('semantic-query-error')).toHaveCount(0);
    const rows = page.getByTestId('semantic-query-result').locator('tbody tr');
    await expect(rows).toHaveCount(4);
    for (const status of statuses) await expect(rows.filter({ hasText: status })).toContainText('3');
  };
  await runQuery(false);
  await page.screenshot({ path: `${EV}/sc-01-business.png`, fullPage: true });
  await page.getByTestId('semantic-dim-created_day').locator('input').check();
  await page.getByTestId('semantic-grain-created_day').selectOption('month');
  await runQuery(true);
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
  await runQuery(true);
  await page.screenshot({ path: `${EV}/semantic-console-restore.png`, fullPage: true });
});
