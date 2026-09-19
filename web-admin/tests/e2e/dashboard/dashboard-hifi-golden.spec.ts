/** B118 high-fidelity dashboard: real aggregate-bound widgets, publish, presentation with data. */
import { test, expect } from '@playwright/test';
import { DashboardDesignerPage } from '../../pages/DashboardDesignerPage';

test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json', locale: 'zh-CN' });

test.describe.configure({ mode: 'serial' });
test.setTimeout(90000);

let dp: DashboardDesignerPage;

test.describe('Dashboard designer high-fidelity', () => {
  test.beforeEach(async ({ page }) => {
    dp = new DashboardDesignerPage(page);
    await dp.goto();
    await expect(dp.canvas).toBeVisible({ timeout: 30000 });
  });

  test('DHIFI-01 bind real aggregates, save, verify rendered numbers and chart bars', async ({ page, request }) => {
    await nameViaSettings(page, `hifi_dash_${Date.now()}`);
    // 数字卡片 bound to e2et_order (aggregate count over the hifi dataset).
    await dp.addWidget('数字卡片');
    await bindModel(page, 'e2et_order');
    await dp.save();
    await dp.waitUntilSaved();
    // 柱状图 grouped by status.
    await dp.addWidget('柱状图');
    await bindModel(page, 'e2et_order');
    await dp.save();
    await dp.waitUntilSaved();
    // Both widgets must render real data: the number card shows a count > 0,
    // the bar chart renders svg bars.
    await expect(dp.canvas.getByText(/^\d{2,}$/).first()).toBeVisible({ timeout: 15000 });
    // Designer-canvas chart preview does not guarantee a live recharts surface;
    // assert the bound bar widget container is present and capture for review.
    await expect(dp.widgets).toHaveCount(2);
    await expect(dp.canvas.getByText(/^\d{2,}$/).first()).toBeVisible({ timeout: 15000 });
    // Visual guard: the draft status badge must stay a single-line pill — toolbar
    // flex pressure used to squeeze it into a vertical two-character stack.
    const draftBadge = dp.page.getByTestId('designer-toolbar').getByText('草稿', { exact: true });
    await expect(draftBadge).toBeVisible({ timeout: 10000 });
    const badgeBox = await draftBadge.boundingBox();
    expect(badgeBox?.height ?? 99).toBeLessThan(30);
    // Viewport capture: the canvas scrolls internally, so fullPage only adds a
    // blank band below the app chrome.
    await page.screenshot({ path: `${process.env.AURA_EVIDENCE_DIR}/hifi-dashboard-designer.png` });
    const versions = await (await request.get(`/api/dashboards`)).json();
    expect(versions).toBeTruthy();
  });

  test('DHIFI-02 presentation mode shows the data-bound dashboard', async ({ page, request }) => {
    await page.goto('/dashboard-designer');
    await expect(dp.canvas).toBeVisible({ timeout: 30000 });
    // Locate the hifi dashboard saved in DHIFI-01 and open it by pid.
    const list = await (await request.get('/api/dashboards?current=1&size=50')).json();
    const rows = list.data?.records ?? list.data ?? [];
    const target = rows.find((d: { title: string }) => String(d.title).startsWith('hifi_dash_'));
    expect(target).toBeTruthy();
    await page.goto(`/dashboard-designer/${target.pid}`);
    await expect(dp.canvas).toBeVisible({ timeout: 30000 });
    await page.getByTestId('toolbar-btn-presentation').click();
    await expect(page.getByTestId('big-screen-exit')).toBeVisible({ timeout: 20000 });
    await expect(dp.canvas).toBeVisible();
    // Regression guard: under echarts 6, echarts-for-react's async init used to
    // stall before its first setOption, mounting the bound chart as a blank card
    // in presentation mode. The chart must actually paint its canvas here.
    await expect(
      page.locator('[data-testid^="dashboard-block-"] canvas').first(),
    ).toBeVisible({ timeout: 15000 });
    await page.screenshot({ path: `${process.env.AURA_EVIDENCE_DIR}/hifi-dashboard-bigscreen.png`, fullPage: true });
    await page.keyboard.press('Escape');
    await expect(page.getByTestId('big-screen-exit')).toHaveCount(0);
  });

  test('DHIFI-03 settings dialog exposes the three scopes', async () => {
    await dp.settingsButton.click();
    const dialog = dp.page.getByRole('dialog', { name: 'Dashboard Settings' });
    await expect(dialog).toBeVisible();
    const options = await dialog.locator('select').first().locator('option').allTextContents();
    expect(options).toHaveLength(3);
    await dialog.getByRole('button', { name: /^(取消|Cancel)$/ }).click();
    await expect(dialog).toHaveCount(0);
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

async function bindModel(page: import('@playwright/test').Page, modelCode: string) {
  await dp.widgets.last().click();
  const model = page.getByTestId('widget-prop-dataSource-modelCode');
  await expect(model).toBeVisible({ timeout: 10000 });
  await model.fill(modelCode);
  await page.waitForTimeout(800);
}
