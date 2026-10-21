/** Mature, real-data designs and the shared container-responsive authoring shell. */
import { test, expect, type Page, type Response, type Locator } from '@playwright/test';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { execFileSync } from 'node:child_process';
import { navigateToMenuByClick } from '../helpers';
import { loginDeliveryMember } from '../../helpers/delivery-session';

test.use({ storageState: { cookies: [], origins: [] } });
const root = process.env.AURA_ENTERPRISE_PROJECT_ROOT;
const evidence = process.env.AURA_EVIDENCE_DIR;
if (!root || !evidence || !process.env.AURA_ADMIN_TENANT_ID)
  throw Error('Bind runtime and evidence');
async function data(response: Response) {
  const body = await response.json();
  expect(response.status(), JSON.stringify(body)).toBe(200);
  expect(String(body.code)).toBe('0');
  return body.data;
}
async function start(page: Page) {
  page.setDefaultTimeout(20000);
  await loginDeliveryMember(page);
  await mkdir(evidence!, { recursive: true });
}
async function shot(page: Page, id: string) {
  await page.screenshot({ path: join(evidence!, `${id}.png`), fullPage: false });
}
async function narrow(page: Page, prefix: string, selected: Locator, edit: () => Promise<void>) {
  for (const width of [768, 620]) {
    await page.setViewportSize({ width, height: 1000 });
    const workspace = page.getByTestId(`${prefix}-designer-workspace`);
    await expect(workspace).toHaveAttribute('data-layout', 'compact');
    await expect(page.getByTestId(`${prefix}-inspector-shell`)).toHaveAttribute(
      'data-open',
      'false',
    );
    const available = (await workspace.boundingBox())!.width;
    expect((await page.getByTestId(`${prefix}-canvas-shell`).boundingBox())!.width).toBeGreaterThan(
      available * 0.9,
    );
    await page.getByTestId(`${prefix}-toggle-palette`).click();
    await expect(page.getByTestId(`${prefix}-palette-shell`)).toHaveAttribute('data-open', 'true');
    await page.getByTestId(`${prefix}-close-palette`).click();
    await expect(page.getByTestId(`${prefix}-palette-shell`)).toHaveAttribute('data-open', 'false');
  }
  await shot(page, `NR-${prefix}-620-canvas`);
  await selected.click();
  await expect(page.getByTestId(`${prefix}-inspector-shell`)).toHaveAttribute('data-open', 'true');
  await edit();
  await shot(page, `NR-${prefix}-620-properties`);
  await page.keyboard.press('Escape');
  await expect(page.getByTestId(`${prefix}-inspector-shell`)).toHaveAttribute('data-open', 'false');
  await page.setViewportSize({ width: 1440, height: 1000 });
  await expect(page.getByTestId(`${prefix}-designer-workspace`)).toHaveAttribute(
    'data-layout',
    'wide',
  );
}

test('NR Dashboard: balanced DSL → narrow editing → save/publish → real results', async ({
  page,
}) => {
  test.setTimeout(180000);
  await start(page);
  await navigateToMenuByClick(page, ['元数据管理', '仪表盘管理']);
  await page.getByTestId('toolbar-btn-create').click();
  await page
    .getByTestId('dashboard-design-import-file')
    .setInputFiles(
      join(
        root!,
        'plugins/project-delivery-operations/examples/delivery-executive-review.dashboard-template.json',
      ),
    );
  await page.getByRole('button', { name: '导入为新草稿', exact: true }).click();
  const widget = page.locator('[data-widget-id="acceptance-distribution"]').first();
  await expect(widget.locator('[data-chart-ready="true"]')).toBeVisible();
  const title = '验收状态构成 · 本周';
  await narrow(page, 'dashboard', widget, async () => {
    await page.getByTestId('widget-prop-title-zh').fill(title);
  });
  await expect(page.getByTestId('widget-prop-title-zh')).toHaveValue(title);
  await shot(page, 'CF-dashboard-design');
  const saved = page.waitForResponse(
    (r) => new URL(r.url()).pathname === '/api/dashboards' && r.request().method() === 'POST',
  );
  await page.getByTestId('designer-toolbar-btn-save').click();
  const board = await data(await saved);
  const published = page.waitForResponse(
    (r) =>
      new URL(r.url()).pathname === `/api/dashboards/${board.pid}/publish` &&
      r.request().method() === 'POST',
  );
  await page.getByTestId('toolbar-btn-publish').click();
  await data(await published);
  await page.reload();
  await expect(widget.locator('[data-chart-ready="true"]')).toBeVisible();
  const response = await page.request.get(`/api/dashboards/${board.pid}`);
  const payload = await response.json();
  expect(payload.code).toBe('0');
  const kpis = payload.data.widgets.filter((w: any) =>
    ['pending_count', 'overdue_count', 'returned_count', 'late_completed_count'].includes(w.id),
  );
  expect(kpis.map((w: any) => [w.x, w.y, w.w, w.h])).toEqual([
    [0, 0, 3, 2],
    [3, 0, 3, 2],
    [6, 0, 3, 2],
    [9, 0, 3, 2],
  ]);
  const chartConfig = payload.data.widgets.find(
    (w: any) => w.id === 'acceptance-distribution',
  ).config;
  expect(chartConfig.title['zh-CN'] || chartConfig.title.zh).toBe(title);
  await page.getByTestId('toolbar-btn-presentation').click();
  await expect(page.getByTestId('big-screen-mode')).toBeVisible();
  // ECharts paints its title into Canvas; persisted config above proves the title.
  await expect(page.getByTestId('big-screen-mode').locator('[data-widget-id="acceptance-distribution"] [data-chart-ready="true"]')).toBeVisible();
  await expect(page.getByTestId('big-screen-mode')).toContainText('华辰制造');
  await expect(page.getByTestId('big-screen-mode')).toContainText('当前逾期');
  await shot(page, 'CF-dashboard-runtime');
  await page.keyboard.press('Escape');
  await writeFile(
    join(evidence!, 'CF-dashboard.json'),
    JSON.stringify({ pid: board.pid, kpis, chartConfig }, null, 2),
  );
});

test('NR Report: formal DSL → narrow Schema edit → save/reopen → three exports', async ({
  page,
}) => {
  test.setTimeout(180000);
  await start(page);
  await navigateToMenuByClick(page, ['元数据管理', '报表管理']);
  await page.getByRole('button', { name: '新建报表', exact: true }).click();
  const chooser = page.waitForEvent('filechooser');
  await page.getByTestId('report-template-import').click();
  await (
    await chooser
  ).setFiles(
    join(
      root!,
      'plugins/project-delivery-operations/examples/delivery-executive-review.report-template.json',
    ),
  );
  await expect(page.getByTestId('report-template-imported')).toBeVisible();
  const totalLabel = '验收金额合计（元）';
  await narrow(page, 'report', page.getByTestId('drag-handle-status-amount'), async () => {
    await page.getByTestId('schema-config-field-totalLabel').getByRole('textbox').fill(totalLabel);
  });
  await expect(page.getByTestId('schema-config-field-totalLabel').getByRole('textbox')).toHaveValue(
    totalLabel,
  );
  await shot(page, 'CF-report-design');
  const save = page.waitForResponse(
    (r) =>
      new URL(r.url()).pathname === '/api/report-definitions' && r.request().method() === 'POST',
  );
  await page.getByTestId('report-designer-toolbar-btn-save').click();
  const report = await data(await save);
  await expect(page).toHaveURL(new RegExp(`/report-designer/${report.pid}$`));
  await page.reload();
  const toolbar = page.getByTestId('report-designer-toolbar');
  await toolbar.getByRole('button', { name: '预览', exact: true }).click();
  const paper = page.getByTestId('report-runtime-paper');
  await expect(paper).toContainText(totalLabel);
  await expect(paper).toContainText('华辰制造');
  await shot(page, 'CF-report-runtime');
  const files: string[] = [];
  for (const [format, label, ext] of [
    ['markdown', 'Markdown', 'md'],
    ['html', 'HTML', 'html'],
    ['pdf', 'PDF', 'pdf'],
  ]) {
    const download = page.waitForEvent('download');
    const response = page.waitForResponse(
      (r) =>
        new URL(r.url()).pathname === `/api/reports/export/${format}` &&
        r.request().method() === 'POST',
    );
    await toolbar.getByRole('button', { name: `导出 ${label}`, exact: true }).click();
    expect((await response).status()).toBe(200);
    const path = join(evidence!, `customer-delivery-review.${ext}`);
    await (await download).saveAs(path);
    const content =
      format === 'pdf'
        ? execFileSync('pdftotext', ['-layout', path, '-'], { encoding: 'utf8' })
        : await readFile(path, 'utf8');
    expect(content).toContain('华辰制造');
    expect(content).toContain('SLA');
    if (format !== 'markdown') {
      expect(content.replace(/\s+/g, '')).toContain(totalLabel);
      expect(content).toContain('¥');
    }
    files.push(path);
  }
  await writeFile(
    join(evidence!, 'CF-report.json'),
    JSON.stringify({ pid: report.pid, totalLabel, files }, null, 2),
  );
});
