/** B118 high-fidelity dashboard: real aggregate-bound widgets, persistence, presentation with data. */
import { test, expect } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { DashboardDesignerPage } from '../../pages/DashboardDesignerPage';

test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json', locale: 'zh-CN' });

test.describe.configure({ mode: 'serial' });
test.setTimeout(90000);

let dp: DashboardDesignerPage;
const dashRun = `hifid_${randomUUID().replaceAll('-', '').slice(0, 12)}`;
const dashTitle = `hifi_dash_${dashRun}`;
let dashboardPid = '';

test.describe('Dashboard designer high-fidelity', () => {
  test.beforeEach(async ({ page }) => {
    dp = new DashboardDesignerPage(page);
    // Menu-entry journey remains a separate unverified acceptance requirement.
    await page.goto('/dashboard-designer');
    await expect(dp.toolbar).toBeVisible();
    await expect(dp.palette).toBeVisible();
    await expect(dp.propertyPanel).toBeVisible();
    await expect(dp.canvas).toBeVisible({ timeout: 30000 });
  });

  test('DHIFI-01 bind real aggregates, save, verify rendered numbers and chart bars', async ({ page, request }) => {
    await nameViaSettings(page, dashTitle);
    // Own twelve records provide an exact expected count independent of other specs.
    for (let i = 0; i < 12; i++) {
      const created = await request.post('/api/dynamic/e2et_order/create', {
        data: {
          e2et_order_title: `HiFi看板-${dashRun}-${String(i + 1).padStart(3, '0')}`,
          e2et_order_type: ['normal', 'urgent', 'bulk'][i % 3],
          e2et_order_urgent: i % 4 === 0,
          e2et_order_status: ['draft', 'confirmed', 'shipped', 'completed'][i % 4],
        },
      });
      expect(created.status(), await created.text()).toBe(200);
      expect(String((await created.json()).code)).toBe('0');
    }
    await dp.addWidget('数字卡片');
    await bindAggregate(page, 0, false, (rows, metric) => {
      expect(rows).toHaveLength(1); expect(Number(rows[0][metric])).toBe(12);
    });
    dashboardPid = await saveDashboard(page, 1);
    await dp.addWidget('柱状图');
    await bindAggregate(page, 1, true, (rows, metric) => {
      expect(rows).toHaveLength(4);
      expect(rows.map(r => r.e2et_order_status).sort()).toEqual(['completed', 'confirmed', 'draft', 'shipped']);
      for (const row of rows) expect(Number(row[metric])).toBe(3);
    });
    expect(await saveDashboard(page, 2)).toBe(dashboardPid);
    await expect(dp.widgets).toHaveCount(2);
    await expect(dp.widgets.nth(0).getByText('12', { exact: true })).toBeVisible({ timeout: 15000 });
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
  });

  test('DHIFI-02 presentation mode shows the data-bound dashboard', async ({ page, request }) => {
    await page.goto('/dashboard-designer');
    await expect(dp.canvas).toBeVisible({ timeout: 30000 });
    // Open the exact object created in this worker; never select old prefix matches.
    expect(dashboardPid).not.toBe('');
    const target = await (await request.get(`/api/dashboards/${dashboardPid}`)).json();
    expect(String(target.code)).toBe('0');
    expect(target.data.title).toBe(dashTitle);
    await page.goto(`/dashboard-designer/${dashboardPid}`);
    await expect(dp.canvas).toBeVisible({ timeout: 30000 });
    await page.getByTestId('toolbar-btn-presentation').click();
    await expect(page.getByTestId('big-screen-exit')).toBeVisible({ timeout: 20000 });
    await expect(dp.canvas).toBeVisible();
    await expect(dp.canvas.getByText('12', { exact: true })).toBeVisible({ timeout: 15000 });
    // Canvas visibility only proves mounting. Screenshot review must establish
    // that chart bars paint correctly; this assertion does not close that gap.
    await expect(
      page.locator('[data-testid^="dashboard-block-"] canvas').first(),
    ).toBeVisible({ timeout: 15000 });
    await page.screenshot({ path: `${process.env.AURA_EVIDENCE_DIR}/dhifi-02-business.png`, fullPage: true });
    await page.keyboard.press('Escape');
    await expect(page.getByTestId('big-screen-exit')).toHaveCount(0);
  });

  test('DHIFI-03 settings dialog exposes the three scopes', async () => {
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
  return body.data.pid;
}
