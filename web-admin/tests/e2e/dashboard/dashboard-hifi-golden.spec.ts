/** B118 high-fidelity dashboard: real aggregate-bound widgets, persistence, presentation with data. */
import { test, expect } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { DashboardDesignerPage } from '../../pages/DashboardDesignerPage';
import { ensureSidebarExpanded, navigateToMenuByClick, ensureFilterFormOpen } from '../helpers';

test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json', locale: 'zh-CN' });

test.describe.configure({ mode: 'serial' });
test.setTimeout(90000);

let dp: DashboardDesignerPage;
const dashRun = `hifid_${randomUUID().replaceAll('-', '').slice(0, 12)}`;
const dashTitle = `hifi_dash_${dashRun}`;
let dashboardPid = '';
const semanticA = `dash_sem_a_${dashRun}`;
const semanticB = `dash_sem_b_${dashRun}`;
const semanticGrains = ['day', 'week', 'month', 'quarter', 'year'] as const;
const semanticDates = ['2026-01-15', '2026-02-15'];

function semanticFixture(code: string, alternate: boolean) {
  return `version: "0.1"
semantic_model: {code: ${code}, model_ref: e2et_order, primary_entity: order}
entities: [{name: order, type: primary, field_ref: pid}]
dimensions:
  - {code: ${alternate ? 'other_status' : 'order_status'}, type: categorical, field_ref: e2et_order_status}
  - {code: ${alternate ? 'other_day' : 'order_day'}, type: time, field_ref: e2et_order_date, time_grains: [day, week, month, quarter, year], primary_time: true}
  - {code: e2et_order_title, type: categorical, field_ref: e2et_order_title}
measures: [{code: n, agg: COUNT, field_ref: pid}]
metrics:
  - {code: ${alternate ? 'other_count' : 'order_count'}, type: simple, type_params: {measure: n}}
${alternate ? '' : "  - {code: draft_count, type: simple, type_params: {measure: n}, filter: \"e2et_order_status = 'draft'\"}"}
access_policies:
  - {access_grant: fixture_scope, user_attribute: fixture_scope, sql_filter: "e2et_order_title LIKE 'HiFi看板-${dashRun}-%'"}
`;
}

test.beforeAll(async ({ request }) => {
  // Setup only: authoring/publishing is covered by SC-00, not this API setup.
  for (const [code, alternate] of [[semanticA, false], [semanticB, true]] as const) {
    const response = await request.post('/api/semantic/publish', {
      data: { yaml: semanticFixture(code, alternate), pluginCode: 'test-fixtures' },
    });
    expect(response.ok(), await response.text()).toBeTruthy();
    const body = await response.json();
    expect(String(body.code), JSON.stringify(body)).toBe('0');
    expect(body.data.ok).toBe(true);
    expect(body.data.pid).toBeTruthy();
  }
});

test.describe('Dashboard designer high-fidelity', () => {
  test.beforeEach(async ({ page }) => {
    dp = new DashboardDesignerPage(page);
    await page.goto('/');
    await ensureSidebarExpanded(page);
    await navigateToMenuByClick(page, ['元数据管理', '仪表盘管理']);
    await expect(page).toHaveURL(/\/p\/dashboard_management$/);
    await expect(page.getByTestId('toolbar-btn-create')).toBeVisible();
  });

  test('DHIFI-01 bind real aggregates, save, verify rendered numbers and chart bars', async ({ page, request }) => {
    await createFromManagement(page);
    await nameViaSettings(page, dashTitle);
    // Own twelve records provide an exact expected count independent of other specs.
    for (let i = 0; i < 12; i++) {
      const created = await request.post('/api/dynamic/e2et_order/create', {
        data: {
          e2et_order_title: `HiFi看板-${dashRun}-${String(i + 1).padStart(3, '0')}`,
          e2et_order_type: ['normal', 'urgent', 'bulk'][i % 3],
          e2et_order_urgent: i % 4 === 0,
          e2et_order_status: ['draft', 'submitted', 'approved', 'completed'][i % 4],
          e2et_order_date: semanticDates[i < 6 ? 0 : 1],
        },
      });
      expect(created.status(), await created.text()).toBe(200);
      expect(String((await created.json()).code)).toBe('0');
    }
    await addWidgetBySingleClick(page, '数字卡片');
    await bindAggregate(page, 0, false, (rows, metric) => {
      expect(rows).toHaveLength(1); expect(Number(rows[0][metric])).toBe(12);
    });
    dashboardPid = await saveDashboard(page, 1);
    await addWidgetBySingleClick(page, '柱状图');
    await bindAggregate(page, 1, true, (rows, metric) => {
      expect(rows).toHaveLength(4);
      expect(rows.map(r => r.e2et_order_status).sort()).toEqual(['approved', 'completed', 'draft', 'submitted']);
      for (const row of rows) expect(Number(row[metric])).toBe(3);
    });
    expect(await saveDashboard(page, 2)).toBe(dashboardPid);
    await expect(dp.widgets).toHaveCount(2);
    await expect(dp.widgets.nth(0).getByText('12', { exact: true })).toBeVisible({ timeout: 15000 });
    // The chart must have finished painting before the capture: data-chart-ready
    // flips true on the EChartsHost `finished` event (see EChartsHost.tsx).
    await expect(
      dp.widgets.nth(1).locator('[data-chart-ready="true"]').first(),
    ).toBeAttached({ timeout: 15000 });
    // Visual guard: the draft status badge must stay a single-line pill — toolbar
    // flex pressure used to squeeze it into a vertical two-character stack.
    const draftBadge = dp.page.getByTestId('designer-toolbar').getByText('草稿', { exact: true });
    await expect(draftBadge).toBeVisible({ timeout: 10000 });
    const badgeBox = await draftBadge.boundingBox();
    expect(badgeBox?.height ?? 99).toBeLessThan(30);
    // Viewport capture: the canvas scrolls internally, so fullPage only adds a
    // blank band below the app chrome.
    await page.screenshot({ path: `${process.env.AURA_EVIDENCE_DIR}/dhifi-01-business.png` });
    const persisted = await request.get(`/api/dashboards/${dashboardPid}`);
    expect(persisted.ok()).toBeTruthy();
    const body = await persisted.json();
    expect(String(body.code)).toBe('0');
    expect(body.data.title).toBe(dashTitle);
    expect(body.data.widgets).toHaveLength(2);
    for (let i = 0; i < 2; i++) {
      assertBinding(body.data.widgets[i].config.dataSource, i);
    }
    await exerciseSemanticDataSource(page, request);
  });

  test('DHIFI-02 presentation mode shows the data-bound dashboard', async ({ page }) => {
    // Search through the real list and open only this worker's saved object.
    expect(dashboardPid).not.toBe('');
    await ensureFilterFormOpen(page);
    await page.getByPlaceholder(/^(搜索仪表盘标题|Search dashboard title)$/).fill(dashTitle);
    const listResponse = page.waitForResponse(r => new URL(r.url()).pathname === '/api/dashboards'
      && r.request().method() === 'GET');
    await page.getByTestId('filter-search').click();
    expect((await listResponse).ok()).toBeTruthy();
    const row = page.getByRole('row').filter({ has: page.getByText(dashTitle, { exact: true }) });
    await expect(row).toHaveCount(1);
    await expect(row).toBeVisible();
    await row.getByText(dashTitle, { exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`/dashboard-designer/${dashboardPid}$`));
    await expect(dp.canvas).toBeVisible({ timeout: 30000 });
    await page.getByTestId('toolbar-btn-presentation').click();
    await expect(page.getByTestId('big-screen-exit')).toBeVisible({ timeout: 20000 });
    await expect(dp.canvas).toBeVisible();
    await expect(dp.canvas.getByText('12', { exact: true })).toBeVisible({ timeout: 15000 });
    // Canvas visibility only proves mounting. The capture must show painted bars:
    // wait for the chart's own ready signal (EChartsHost `finished`), which only
    // flips true after the option data has rendered.
    await expect(
      page.locator('[data-testid^="dashboard-block-"] [data-chart-ready="true"]').first(),
    ).toBeAttached({ timeout: 15000 });
    await page.screenshot({ path: `${process.env.AURA_EVIDENCE_DIR}/dhifi-02-business.png`, fullPage: true });
    await page.keyboard.press('Escape');
    await expect(page.getByTestId('big-screen-exit')).toHaveCount(0);
  });

  test('DHIFI-03 settings dialog exposes the three scopes', async ({ page }) => {
    await createFromManagement(page);
    await dp.settingsButton.click();
    const dialog = dp.page.getByRole('dialog', { name: 'Dashboard Settings' });
    await expect(dialog).toBeVisible();
    const options = await dialog.locator('select').first().locator('option').allTextContents();
    expect(options).toHaveLength(3);
    expect(await dialog.locator('select').first().locator('option').evaluateAll(nodes => nodes.map(n => (n as HTMLOptionElement).value))).toEqual(['personal', 'team', 'global']);
    await dialog.locator('select').first().selectOption('global');
    await dp.page.screenshot({ path: `${process.env.AURA_EVIDENCE_DIR}/dhifi-03-business.png` });
    await dialog.getByRole('button', { name: /^(取消|Cancel)$/ }).click();
    await expect(dialog).toHaveCount(0);
    await dp.settingsButton.click();
    await expect(dialog.locator('select').first()).toHaveValue('personal');
    await dialog.getByRole('button', { name: /^(取消|Cancel)$/ }).click();
  });
});

// Reuse DHIFI-01 and its twelve owned rows; no new profile or API-driven core action.
async function exerciseSemanticDataSource(page: import('@playwright/test').Page,
  request: import('@playwright/test').APIRequestContext) {
  await dp.widgets.nth(1).click();
  const modelSelect = page.getByTestId('semantic-model-select');
  const metricPicker = page.getByTestId('semantic-metric-picker');
  const dimensionPicker = page.getByTestId('semantic-dimension-picker');
  const metric = (code: string) => metricPicker.locator('label').filter({ hasText: `(${code})` }).getByRole('checkbox');
  const dimension = (code: string) => dimensionPicker.locator('label').filter({ hasText: `(${code})` }).getByRole('checkbox');
  let code = semanticA;
  let metrics: string[] = [];
  let dimensions: string[] = [];
  const source = () => ({ type: 'aggregate', semanticModelCode: code,
    metrics: metrics.map(field => ({ field, aggregation: 'none' })), dimensions,
    filters: [{ field: 'e2et_order_title', operator: 'like', value: `HiFi看板-${dashRun}-` }] });
  const expectedRows = () => {
    const buckets = { day: semanticDates, week: ['2026-01-12', '2026-02-09'],
      month: ['2026-01-01', '2026-02-01'], quarter: ['2026-01-01', '2026-01-01'],
      year: ['2026-01-01', '2026-01-01'] };
    const rows = new Map<string, Record<string, unknown>>();
    for (let i = 0; i < 12; i++) {
      const status = ['draft', 'submitted', 'approved', 'completed'][i % 4];
      const dims = Object.fromEntries(dimensions.map(dim => {
        if (dim.endsWith('_status')) return [dim, status];
        const grain = dim.split('__')[1] as keyof typeof buckets;
        return [dim, buckets[grain][i < 6 ? 0 : 1]];
      }));
      const key = JSON.stringify(dims);
      const row = rows.get(key) || { ...dims, ...Object.fromEntries(metrics.map(m => [m, 0])) };
      for (const m of metrics) row[m] = Number(row[m]) + (m === 'draft_count' ? Number(status === 'draft') : 1);
      rows.set(key, row);
    }
    return [...rows.values()].map(r => JSON.stringify(r)).sort();
  };
  const queryResponse = () => page.waitForResponse(r => new URL(r.url()).pathname === '/api/meta/chart-data'
    && r.request().method() === 'POST' && r.request().postDataJSON()?.semanticModelCode === code
    && JSON.stringify(r.request().postDataJSON()?.metrics) === JSON.stringify(source().metrics)
    && JSON.stringify(r.request().postDataJSON()?.dimensions) === JSON.stringify(dimensions));
  const inspectQuery = async (response: import('@playwright/test').Response) => {
    const payload = response.request().postDataJSON();
    expect(payload).toMatchObject(source());
    expect(payload).not.toHaveProperty('modelCode');
    const body = await response.json();
    expect(String(body.code), JSON.stringify(body)).toBe('0');
    expect(body.data.meta.dimensions).toEqual(dimensions);
    expect(body.data.meta.metrics).toEqual(metrics);
    const actual = body.data.rows.map((r: Record<string, unknown>) => {
      const row = Object.fromEntries(dimensions.map(dim => {
        if (!dim.includes('__')) return [dim, r[dim]];
        expect(String(r[dim])).toMatch(/^\d{4}-\d{2}-\d{2}(?:T| )00:00:00(?:\.0+)?(?:Z|[+-]00(?::?00)?)?$/);
        return [dim, String(r[dim]).slice(0, 10)];
      }));
      for (const m of metrics) row[m] = Number(r[m]);
      return JSON.stringify(row);
    }).sort();
    expect(actual).toEqual(expectedRows());
  };
  const changeAndQuery = async (action: () => Promise<unknown>) => {
    const response = queryResponse();
    await action();
    await inspectQuery(await response);
  };
  const saveReload = async (screenshot: string) => {
    const saved = page.waitForResponse(r => new URL(r.url()).pathname === `/api/dashboards/${dashboardPid}`
      && r.request().method() === 'PUT');
    await dp.saveButton.click();
    const response = await saved;
    expect(response.request().postDataJSON().widgets[1].config.dataSource).toMatchObject(source());
    expect(response.request().postDataJSON().widgets[1].config.dataSource).not.toHaveProperty('modelCode');
    expect(String((await response.json()).code)).toBe('0');
    await dp.waitUntilSaved();
    const read = await request.get(`/api/dashboards/${dashboardPid}`);
    expect(read.ok()).toBeTruthy();
    const body = await read.json();
    expect(String(body.code)).toBe('0');
    expect(body.data.widgets[1].config.dataSource).toMatchObject(source());
    expect(body.data.widgets[1].config.dataSource).not.toHaveProperty('modelCode');
    const restored = queryResponse();
    await page.reload();
    await inspectQuery(await restored);
    await dp.widgets.nth(1).click();
    await expect(modelSelect).toHaveValue(code);
    for (const m of metrics) await expect(metric(m)).toBeChecked();
    for (const dim of dimensions) {
      const [base, grain] = dim.split('__');
      await expect(dimension(base)).toBeChecked();
      if (grain) await expect(dimensionPicker.getByRole('combobox', { name: `${base} 粒度` })).toHaveValue(grain);
    }
    await page.screenshot({ path: `${process.env.AURA_EVIDENCE_DIR}/${screenshot}.png` });
  };
  await page.getByTestId('datasource-mode-semantic').click();
  await expect(modelSelect).toHaveValue('');
  await expect(modelSelect.locator('option').first()).toHaveText('请选择语义模型');
  await expect(page.getByTestId('widget-prop-dataSource-modelCode')).toHaveCount(0);
  await expect(metricPicker).toHaveCount(0);
  await expect(dimensionPicker).toHaveCount(0);
  await page.screenshot({ path: `${process.env.AURA_EVIDENCE_DIR}/bi-ui-ds-mode-semantic-switch.png` });
  // Delay real metadata requests, then continue them unchanged. No fulfilled fixture response.
  let releaseMetadata!: () => void;
  const metadataGate = new Promise<void>(resolve => { releaseMetadata = resolve; });
  const holdMetadata = async (route: import('@playwright/test').Route) => {
    await metadataGate;
    await route.continue();
  };
  await page.route('**/api/semantic/meta', holdMetadata);
  try {
    await modelSelect.selectOption(code);
    await expect(metricPicker).toContainText('加载指标中…');
    await expect(dimensionPicker).toContainText('加载维度中…');
    await page.screenshot({ path: `${process.env.AURA_EVIDENCE_DIR}/bi-ui-ds-semantic-loading.png` });
  } finally {
    releaseMetadata();
    await page.unrouteAll({ behavior: 'wait' });
  }
  await expect(metric('order_count')).not.toBeChecked();
  await expect(metric('draft_count')).not.toBeChecked();
  await expect(dimension('order_status')).not.toBeChecked();
  metrics = ['order_count'];
  await changeAndQuery(() => metric('order_count').check());
  metrics = ['order_count', 'draft_count'];
  await changeAndQuery(() => metric('draft_count').check());
  await saveReload('bi-ui-ds-semantic-metric-select');
  metrics = ['draft_count'];
  await changeAndQuery(() => metric('order_count').uncheck());
  await expect(metric('draft_count')).toBeChecked();
  await saveReload('bi-ui-ds-semantic-metric-remove');
  metrics = ['draft_count', 'order_count'];
  await changeAndQuery(() => metric('order_count').check());
  dimensions = ['order_status'];
  await changeAndQuery(() => dimension('order_status').check());
  await saveReload('bi-ui-ds-semantic-dimension-categorical');
  dimensions = ['order_status', 'order_day__day'];
  await changeAndQuery(() => dimension('order_day').check());
  const grainSelect = dimensionPicker.getByRole('combobox', { name: 'order_day 粒度' });
  await expect(grainSelect.locator('option')).toHaveText([...semanticGrains]);
  await expect(grainSelect).toHaveValue('day');
  await saveReload('bi-ui-ds-semantic-dimension-time-default');
  for (const grain of semanticGrains.slice(1)) {
    dimensions = ['order_status', `order_day__${grain}`];
    await changeAndQuery(() => grainSelect.selectOption(grain));
    await saveReload(`bi-ui-ds-semantic-grain-${grain}`);
  }
  dimensions = ['order_status'];
  await changeAndQuery(() => dimension('order_day').uncheck());
  await expect(grainSelect).toHaveCount(0);
  await expect(dimension('order_status')).toBeChecked();
  await saveReload('bi-ui-ds-semantic-dimension-remove');
  dimensions = ['order_status', 'order_day__day'];
  await changeAndQuery(() => dimension('order_day').check());
  await expect(grainSelect).toHaveValue('day');
  await page.screenshot({ path: `${process.env.AURA_EVIDENCE_DIR}/bi-ui-ds-semantic-grain-unselected.png` });
  code = semanticB;
  metrics = []; dimensions = [];
  await modelSelect.selectOption(code);
  await expect(metric('other_count')).not.toBeChecked();
  await expect(metric('order_count')).toHaveCount(0);
  await expect(metric('draft_count')).toHaveCount(0);
  await expect(dimension('other_status')).not.toBeChecked();
  await expect(dimension('order_status')).toHaveCount(0);
  await expect(dimensionPicker.getByRole('combobox')).toHaveCount(0);
  metrics = ['other_count'];
  await changeAndQuery(() => metric('other_count').check());
  await saveReload('bi-ui-ds-semantic-model-switch');
  await page.getByTestId('datasource-mode-raw').click();
  await expect(modelSelect).toHaveCount(0);
  await expect(metricPicker).toHaveCount(0);
  await expect(dimensionPicker).toHaveCount(0);
  await page.getByTestId('widget-prop-dataSource-modelCode').fill('e2et_order');
  const rawDimensions = dp.propertyPanel.locator('label', { hasText: /^分组维度$/ }).locator('..');
  const rawResponse = page.waitForResponse(r => new URL(r.url()).pathname === '/api/meta/chart-data'
    && r.request().method() === 'POST' && !r.request().postDataJSON()?.semanticModelCode
    && JSON.stringify(r.request().postDataJSON()?.dimensions) === '["e2et_order_status"]');
  await rawDimensions.locator('label').filter({ hasText: '(e2et_order_status)' }).getByRole('checkbox').check();
  const response = await rawResponse;
  assertBinding(response.request().postDataJSON(), 1);
  expect(response.request().postDataJSON()).not.toHaveProperty('semanticModelCode');
  expect(response.request().postDataJSON().metrics).toEqual([{ field: 'id', aggregation: 'count' }]);
  const rawBody = await response.json();
  expect(String(rawBody.code)).toBe('0');
  expect(rawBody.data.rows).toHaveLength(4);
  expect(rawBody.data.rows.map((r: Record<string, unknown>) => r.e2et_order_status).sort())
    .toEqual(['approved', 'completed', 'draft', 'submitted']);
  for (const row of rawBody.data.rows) expect(Number(row[rawBody.data.meta.metrics[0]])).toBe(3);
  expect(await saveDashboard(page, 2)).toBe(dashboardPid);
  const persisted = await request.get(`/api/dashboards/${dashboardPid}`);
  expect(persisted.ok()).toBeTruthy();
  const persistedBody = await persisted.json();
  expect(String(persistedBody.code)).toBe('0');
  expect(persistedBody.data.widgets[1].config.dataSource).not.toHaveProperty('semanticModelCode');
  assertBinding(persistedBody.data.widgets[1].config.dataSource, 1);
  await page.screenshot({ path: `${process.env.AURA_EVIDENCE_DIR}/bi-ui-ds-mode-raw-switch.png` });
}

// A golden action must not retry, force clicks, dispatch events or remove errors.
async function addWidgetBySingleClick(page: import('@playwright/test').Page, name: string) {
  const before = await dp.widgets.count();
  const item = dp.paletteItem(name);
  await expect(page.locator('vite-error-overlay')).toHaveCount(0);
  await expect(item).toBeVisible();
  await item.click();
  await expect(dp.widgets).toHaveCount(before + 1);
  await expect(dp.propertyPanel.locator('h2').filter({ hasText: name })).toBeVisible();
  await expect(page.locator('vite-error-overlay')).toHaveCount(0);
}

async function createFromManagement(page: import('@playwright/test').Page) {
  await page.getByTestId('toolbar-btn-create').click();
  await expect(page).toHaveURL(/\/dashboard-designer$/);
  await expect(dp.toolbar).toBeVisible();
  await expect(dp.palette).toBeVisible();
  await expect(dp.propertyPanel).toBeVisible();
  await expect(dp.canvas).toBeVisible({ timeout: 30000 });
}

async function nameViaSettings(page: import('@playwright/test').Page, title: string) {
  await dp.settingsButton.click();
  const dialog = dp.page.getByRole('dialog', { name: 'Dashboard Settings' });
  await expect(dialog).toBeVisible();
  await dialog.locator('input').first().fill(title);
  await dialog.getByRole('button', { name: /^(保存|Save)$/ }).click();
  await expect(dialog).toHaveCount(0);
}

function assertBinding(source: Record<string, any>, index: number) {
  expect(source.type).toBe('aggregate');
  expect(source.modelCode).toBe('e2et_order');
  expect(source.dimensions || []).toEqual(index === 1 ? ['e2et_order_status'] : []);
  expect(source.filters).toEqual([{ field: 'e2et_order_title', operator: 'like', value: `HiFi看板-${dashRun}-` }]);
}

async function bindAggregate(page: import('@playwright/test').Page, index: number, grouped: boolean,
  assertRows: (rows: Record<string, unknown>[], metric: string) => void) {
  await dp.widgets.nth(index).click();
  await page.getByTestId('widget-prop-dataSource-modelCode').fill('e2et_order');
  if (grouped) {
    const dimensions = dp.propertyPanel.locator('label', { hasText: /^分组维度$/ }).locator('..');
    await dimensions.locator('label').filter({ hasText: '(e2et_order_status)' }).getByRole('checkbox').check();
  }
  const filters = dp.propertyPanel.locator('label', { hasText: /^筛选条件$/ }).locator('..').locator('..');
  await filters.getByRole('button', { name: 'Add', exact: true }).click();
  await filters.locator('select').nth(0).selectOption('e2et_order_title');
  await filters.locator('select').nth(1).selectOption('like');
  const responsePromise = page.waitForResponse(r => r.url().endsWith('/api/meta/chart-data')
    && r.request().method() === 'POST'
    && r.request().postDataJSON()?.filters?.some((f: { value: string }) => f.value === `HiFi看板-${dashRun}-`));
  await filters.getByPlaceholder('Value', { exact: true }).fill(`HiFi看板-${dashRun}-`);
  const response = await responsePromise;
  assertBinding(response.request().postDataJSON(), index);
  const body = await response.json();
  expect(String(body.code), JSON.stringify(body)).toBe('0');
  expect(body.data.meta.metrics).toHaveLength(1);
  assertRows(body.data.rows, body.data.meta.metrics[0]);
}

async function saveDashboard(page: import('@playwright/test').Page, widgets: number): Promise<string> {
  const isCreateRoute = new URL(page.url()).pathname === '/dashboard-designer';
  const loaded = isCreateRoute ? page.waitForResponse(r => /^\/api\/dashboards\/[^/]+$/.test(new URL(r.url()).pathname)
    && r.request().method() === 'GET') : null;
  const responsePromise = page.waitForResponse(r => /\/api\/dashboards(?:\/[^/]+)?$/.test(new URL(r.url()).pathname)
    && ['POST', 'PUT'].includes(r.request().method()));
  await dp.saveButton.click();
  const response = await responsePromise;
  const payload = response.request().postDataJSON();
  expect(payload.title).toBe(dashTitle);
  expect(payload.widgets).toHaveLength(widgets);
  for (let i = 0; i < widgets; i++) {
    assertBinding(payload.widgets[i].config.dataSource, i);
  }
  const body = await response.json();
  expect(String(body.code), JSON.stringify(body)).toBe('0');
  expect(body.data.pid).toBeTruthy();
  await dp.waitUntilSaved();
  await expect(page).toHaveURL(new RegExp(`/dashboard-designer/${body.data.pid}$`));
  if (loaded) {
    const restored = await loaded;
    expect(restored.ok()).toBeTruthy();
    const restoredBody = await restored.json();
    expect(String(restoredBody.code)).toBe('0');
    expect(restoredBody.data.pid).toBe(body.data.pid);
    expect(restoredBody.data.widgets).toHaveLength(widgets);
  }
  await expect(dp.widgets).toHaveCount(widgets);
  return body.data.pid;
}
