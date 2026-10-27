/** Real UI edits of reusable delivery designs; no injected designer state. */
import { test, expect, type Page, type Locator, type APIResponse, type Response } from '@playwright/test';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { execFileSync } from 'node:child_process';
import { navigateToMenuByClick } from '../helpers';
import { loginDeliveryMember } from '../../helpers/delivery-session';

// These journeys explicitly exercise sign-in; do not inherit profile administrator cookies.
test.use({ storageState: { cookies: [], origins: [] } });

const root = process.env.AURA_ENTERPRISE_PROJECT_ROOT;
const evidence = process.env.AURA_EVIDENCE_DIR;
if (!root || !evidence || !process.env.AURA_ADMIN_TENANT_ID)
  throw Error('Bind source, evidence and tenant.');
async function data(r: APIResponse | Response) {
  const b = await r.json();
  expect(r.status(), JSON.stringify(b)).toBe(200);
  expect(String(b.code)).toBe('0');
  return b.data;
}
async function login(page: Page) {
  page.setDefaultTimeout(20000);
  await loginDeliveryMember(page);
  const me = await data(await page.request.get('/api/auth/me'));
  expect(String(me.user.tenantId)).toBe(process.env.AURA_ADMIN_TENANT_ID);
  expect(me.user.email).toBe(process.env.ADMIN_EMAIL);
  await mkdir(evidence!, { recursive: true });
}
async function shot(page: Page, name: string) {
  const toolbar = page.getByTestId(
    name.startsWith('DS04') || name.startsWith('SC10-report')
      ? 'report-designer-toolbar'
      : 'designer-toolbar',
  );
  if (await toolbar.isVisible()) await toolbar.scrollIntoViewIfNeeded();
  await page.screenshot({ path: join(evidence!, `${name}.png`), fullPage: false });
}

async function showDeliveryResult(widget: Locator, page: Page, acceptanceName: string) {
  // Follow configured pagination; retained exception cases can precede this delivery.
  for (let visited = 0; visited < 100; visited++) {
    await expect(widget.getByRole('cell').first()).toBeVisible();
    const row = widget.getByRole('row').filter({ has: page.getByRole('cell', { name: acceptanceName, exact: true }) });
    if (await row.count()) {
      await expect(row).toHaveCount(1);
      await expect(row).toContainText('周宁');
      return;
    }
    const next = widget.getByRole('button', { name: '下一页', exact: true });
    await expect(next).toBeEnabled();
    const before = await widget.innerText();
    await next.click();
    await expect.poll(() => widget.innerText()).not.toBe(before);
  }
  throw new Error(`Completed delivery was not found in the configured pages: ${acceptanceName}`);
}

test('DS05 executive templates retain a finished layout after Schema edits and real exports', async ({ page }) => {
  test.setTimeout(240000);
  await login(page);
  const example = (name: string) => join(root!, 'plugins/project-delivery-operations/examples', name);
  const boardTemplate = JSON.parse(await readFile(example('delivery-executive-review.dashboard-template.json'), 'utf8'));
  const facts = await data(await page.request.post('/api/meta/named-queries/tdo_delivery_facts/execute', { data: { parameters: {}, size: 500 } }));
  expect(facts.records.length).toBeGreaterThan(0);
  const completed = facts.records.find((row: any) => row.reviewer_name === '周宁' && row.acceptance_label === '已通过');
  expect(completed, 'Complete the named delivery before reviewing its executive templates').toBeTruthy();
  await navigateToMenuByClick(page, ['元数据管理', '仪表盘管理']);
  await page.getByTestId('toolbar-btn-create').click();
  await page.getByTestId('dashboard-design-import-file').setInputFiles(example('delivery-executive-review.dashboard-template.json'));
  await page.getByRole('button', { name: '导入为新草稿', exact: true }).click();
  const canvas = page.getByTestId('designer-canvas');
  const chart = canvas.locator('[data-widget-id="acceptance-distribution"]');
  await expect(chart.locator('[data-chart-ready="true"]')).toBeVisible();
  await chart.click();
  const panel = page.getByTestId('widget-property-panel');
  await expect(panel.getByTestId('widget-prop-title-zh')).toHaveValue('验收状态构成');
  await shot(page, 'DS05-dashboard-before');
  await panel.getByTestId('widget-prop-title-zh').fill('交付验收状态 · 本次周会');
  const legend = panel.getByTestId('schema-config-field-style.showLegend').getByRole('switch');
  await legend.uncheck();
  await legend.check();
  await shot(page, 'DS05-dashboard-schema-edit');
  const boardSaved = page.waitForResponse(r => new URL(r.url()).pathname === '/api/dashboards' && r.request().method() === 'POST');
  await page.getByTestId('designer-toolbar-btn-save').click();
  const board = await data(await boardSaved);
  await expect(page).toHaveURL(new RegExp(`/dashboard-designer/${board.pid}$`));
  const published = page.waitForResponse(r => new URL(r.url()).pathname === `/api/dashboards/${board.pid}/publish` && r.request().method() === 'POST');
  await page.getByTestId('toolbar-btn-publish').click();
  await data(await published);
  await page.reload();
  const storedBoard = await data(await page.request.get(`/api/dashboards/${board.pid}`));
  expect(storedBoard.widgets.map(({ id, x, y, w, h }: any) => ({ id, x, y, w, h })))
    .toEqual(boardTemplate.dashboard.widgets.map(({ id, x, y, w, h }: any) => ({ id, x, y, w, h })));
  const storedChart = storedBoard.widgets.find((widget: any) => widget.id === 'acceptance-distribution');
  expect(storedChart.config.title['zh-CN'] || storedChart.config.title.zh).toBe('交付验收状态 · 本次周会');
  expect(storedChart.config.style.showLegend).toBe(true);
  for (const widget of storedBoard.widgets.filter((widget: any) => widget.type === 'smart-number-card')) expect(widget.config.precision).toBe(0);
  await chart.click();
  await expect(panel.getByTestId('widget-prop-title-zh')).toHaveValue('交付验收状态 · 本次周会');
  await expect(chart.locator('[data-chart-ready="true"]')).toBeVisible();
  await showDeliveryResult(canvas.locator('[data-widget-id="delivery-actions"]'), page, completed.acceptance_name);
  await shot(page, 'DS05-dashboard-reopened');
  await page.getByTestId('toolbar-btn-presentation').click();
  const presentation = page.getByTestId('big-screen-mode');
  await expect(presentation.locator('[data-widget-id="acceptance-distribution"] [data-chart-ready="true"]')).toBeVisible();
  await expect(presentation.getByRole('img', { name: '交付验收状态 · 本次周会', exact: true })).toBeVisible();
  await showDeliveryResult(presentation.locator('[data-widget-id="delivery-actions"]'), page, completed.acceptance_name);
  await shot(page, 'DS05-dashboard-finished');
  await page.keyboard.press('Escape');

  const reportTemplate = JSON.parse(await readFile(example('delivery-executive-review.report-template.json'), 'utf8'));
  await navigateToMenuByClick(page, ['元数据管理', '报表管理']);
  await page.getByRole('button', { name: '新建报表', exact: true }).click();
  const chooser = page.waitForEvent('filechooser');
  await page.getByTestId('report-template-import').click();
  await (await chooser).setFiles(example('delivery-executive-review.report-template.json'));
  await expect(page.getByTestId('report-template-imported')).toBeVisible();
  await page.getByTestId('drag-handle-summary').locator('..').click();
  await expect(page.getByTestId('schema-config-field-title').getByRole('textbox')).toHaveValue('本次复盘摘要');
  await shot(page, 'DS05-report-before');
  await page.getByTestId('schema-config-field-title').getByRole('textbox').fill('本周交付结果与待办');
  await page.getByRole('textbox', { name: '报表标题', exact: true }).fill('客户交付经营复盘 · 本次周会');
  await shot(page, 'DS05-report-schema-edit');
  const reportSaved = page.waitForResponse(r => new URL(r.url()).pathname === '/api/report-definitions' && r.request().method() === 'POST');
  await page.getByTestId('report-designer-toolbar-btn-save').click();
  const report = await data(await reportSaved);
  await expect(page).toHaveURL(new RegExp(`/report-designer/${report.pid}$`));
  await page.reload();
  const storedReport = await data(await page.request.get(`/api/report-definitions/${report.pid}`));
  expect(storedReport.dsl.body.find((block: any) => block.id === 'summary').title).toBe('本周交付结果与待办');
  expect(storedReport.dsl.body.map((block: any) => block.id)).toEqual(reportTemplate.dsl.body.map((block: any) => block.id));
  expect(storedReport.dsl.dataSources).toEqual(reportTemplate.dsl.dataSources);
  expect(storedReport.dsl.page).toEqual(reportTemplate.dsl.page);
  await page.getByTestId('drag-handle-summary').locator('..').click();
  await expect(page.getByTestId('schema-config-field-title').getByRole('textbox')).toHaveValue('本周交付结果与待办');
  await shot(page, 'DS05-report-reopened');
  const toolbar = page.getByTestId('report-designer-toolbar');
  await toolbar.getByRole('button', { name: '预览', exact: true }).click();
  await expect(page.getByTestId('report-runtime-paper')).toContainText('本周交付结果与待办');
  await expect(page.getByTestId('report-runtime-paper')).toContainText(completed.acceptance_name);
  await expect(page.getByTestId('report-runtime-paper')).toContainText('周宁');
  await shot(page, 'DS05-report-finished');
  const files = [];
  for (const [format, label, extension] of [['markdown', 'Markdown', 'md'], ['html', 'HTML', 'html'], ['pdf', 'PDF', 'pdf']] as const) {
    const response = page.waitForResponse(r => new URL(r.url()).pathname === `/api/reports/export/${format}` && r.request().method() === 'POST');
    const downloading = page.waitForEvent('download');
    await toolbar.getByRole('button', { name: `导出 ${label}`, exact: true }).click();
    const exported = await response;
    expect(exported.status()).toBe(200);
    expect(exported.request().postDataJSON().reportPid).toBe(report.pid);
    const download = await downloading;
    expect(download.suggestedFilename()).toMatch(new RegExp(`\\.${extension}$`));
    const file = join(evidence!, `DS05-executive-review.${extension}`);
    await download.saveAs(file);
    const bytes = await readFile(file);
    expect(bytes.length).toBeGreaterThan(100);
    if (format === 'pdf') expect(bytes.subarray(0, 5).toString()).toBe('%PDF-');
    const text = format === 'pdf' ? execFileSync('pdftotext', ['-layout', file, '-'], { encoding: 'utf8' }) : bytes.toString();
    const normalized = (value: string) => value.replace(/\s+/g, '');
    expect(normalized(text)).toContain(normalized(completed.acceptance_name));
    expect(text).toContain('周宁');
    expect(text).toContain('本周交付结果与待办');
    expect(text).toContain('SLA');
    files.push({ format, path: file, bytes: bytes.length });
    if (format === 'pdf') execFileSync('pdftoppm', ['-png', '-r', '90', file, join(evidence!, 'DS05-report-page')]);
  }
  await writeFile(join(evidence!, 'DS05-finished-designs.json'), JSON.stringify({ boardPid: board.pid, reportPid: report.pid, acceptancePid: completed.acceptance_pid, acceptanceName: completed.acceptance_name, source: 'executive templates; original layout preserved; Schema controls edited through browser', files }, null, 2));
});

test('SC10 dashboard template → real donut/title edit → save/publish → reload → exported configuration', async ({
  page,
}) => {
  test.setTimeout(180000);
  await login(page);
  // Explicit fixture setup imports governed query definitions; UI authors the board copy below.
  const imported = await page.request.post('/api/plugins/import/import-directory-sync', {
    data: {
      path: join(root!, 'plugins/project-delivery-operations'),
      conflictStrategy: 'OVERWRITE',
      deferReferenceValidation: true,
    },
  });
  expect(imported.ok()).toBe(true);
  expect((await imported.json()).success).toBe(true);
  const source = await data(
    await page.request.get('/api/dynamic/tpm_acceptance/list?pageSize=500'),
  );
  expect(Number(source.total)).toBe(source.records.length);
  expect(source.records.length).toBeGreaterThan(0);
  const counts = new Map<string, number>();
  const labels: Record<string, string> = {
    draft: '待提交',
    submitted: '审批中',
    accepted: '已通过',
    returned: '已退回',
  };
  for (const row of source.records) {
    const label = labels[row.tpm_ac_status];
    expect(label).toBeTruthy();
    counts.set(label, (counts.get(label) || 0) + 1);
  }
  await navigateToMenuByClick(page, ['元数据管理', '仪表盘管理']);
  await page.getByTestId('toolbar-btn-create').click();
  await page
    .getByTestId('dashboard-design-import-file')
    .setInputFiles(
      join(
        root!,
        'plugins/project-delivery-operations/examples/delivery-operations.dashboard-template.json',
      ),
    );
  await page.getByRole('button', { name: '导入为新草稿', exact: true }).click();
  const canvas = page.getByTestId('designer-canvas');
  const chart = canvas.locator('[data-widget-id="acceptance-distribution"]').first();
  await expect(chart.locator('[data-chart-ready="true"]')).toBeVisible();
  await chart.click();
  const panel = page.getByTestId('widget-property-panel');
  await expect(
    panel.getByTestId('schema-config-field-visualization.donut').getByRole('switch'),
  ).not.toBeChecked();
  const filters = panel.getByTestId('designer-filter-builder');
  await expect(filters).toContainText('未设置筛选条件');
  const distributionQuery = panel.locator('option[value="tdo_acceptance_distribution"]');
  await expect(distributionQuery).toBeAttached();
  await expect(distributionQuery).toHaveText('交付验收状态分布 (tdo_acceptance_distribution)');
  await page.mouse.move(280, 85);
  await shot(page, 'SC10-dashboard-before');
  let unfinishedQueries = 0;
  page.on('request', (request) => {
    if (
      request.method() === 'POST' &&
      new URL(request.url()).pathname === '/api/meta/chart-data' &&
      request.postDataJSON()?.filters?.some((filter: any) => !filter.field?.trim())
    )
      unfinishedQueries++;
  });
  await filters.getByRole('button', { name: '添加条件', exact: true }).click();
  await expect(filters.getByRole('textbox', { name: '字段', exact: true })).toBeVisible();
  await expect(filters.getByRole('option', { name: '等于', exact: true })).toBeAttached();
  await expect(chart.getByTestId('chart-filter-draft')).toContainText('请补全筛选条件后预览');
  await expect(chart.getByRole('alert')).toHaveCount(0);
  await filters.scrollIntoViewIfNeeded();
  await shot(page, 'SC10-dashboard-filter-controls');
  await filters.getByRole('button', { name: '移除条件', exact: true }).click();
  await expect(filters).toContainText('未设置筛选条件');
  await expect(chart.locator('[data-chart-ready="true"]')).toBeVisible();
  expect(unfinishedQueries, 'Unfinished designer filters must not query the backend').toBe(0);
  const tile = canvas
    .locator('.react-grid-item')
    .filter({ has: page.locator('[data-widget-id="pending_count"]') });
  const readBox = async () => {
    const box = await tile.boundingBox();
    expect(box).toBeTruthy();
    return box!;
  };
  const settleGeometry = async () => {
    await expect
      .poll(() =>
        tile.evaluate((element) => {
          const declared = new DOMMatrixReadOnly((element as HTMLElement).style.transform);
          const displayed = new DOMMatrixReadOnly(getComputedStyle(element).transform);
          return Math.max(
            Math.abs(declared.m41 - displayed.m41),
            Math.abs(declared.m42 - displayed.m42),
            Math.abs(
              parseFloat((element as HTMLElement).style.width) -
                element.getBoundingClientRect().width,
            ),
          );
        }),
      )
      .toBeLessThan(0.1);
  };
  await settleGeometry();
  const initialBox = await readBox();
  await page.mouse.move(initialBox.x + 20, initialBox.y + 20);
  await page.mouse.down();
  await page.mouse.move(initialBox.x + 100, initialBox.y + 20, { steps: 16 });
  await page.mouse.up();
  await expect.poll(async () => (await readBox()).x).not.toBeCloseTo(initialBox.x, 0);
  await settleGeometry();
  const movedBox = await readBox();
  await page.getByTestId('designer-toolbar-btn-undo').click();
  await expect.poll(async () => (await readBox()).x).toBeCloseTo(initialBox.x, 0);
  await page.getByTestId('designer-toolbar-btn-redo').click();
  await expect.poll(async () => (await readBox()).x).toBeCloseTo(movedBox.x, 0);
  const handle = tile.locator('.react-resizable-handle-se');
  await expect(handle).toBeVisible();
  const handleBox = await handle.boundingBox();
  expect(handleBox).toBeTruthy();
  await page.mouse.move(handleBox!.x + handleBox!.width / 2, handleBox!.y + handleBox!.height / 2);
  await page.mouse.down();
  await page.mouse.move(handleBox!.x + 100, handleBox!.y + 90, { steps: 16 });
  await page.mouse.up();
  await expect.poll(async () => (await readBox()).width).toBeGreaterThan(movedBox.width + 20);
  await settleGeometry();
  const resizedBox = await readBox();
  await shot(page, 'DS03-dashboard-drag-resize');
  await chart.click();
  const title = '验收状态 · 负责人';
  await panel.getByTestId('widget-prop-title-zh').fill(title);
  await panel.getByTestId('schema-config-field-visualization.donut').getByRole('switch').check();
  await panel
    .getByTestId('schema-config-field-visualization.showLabels')
    .getByRole('switch')
    .uncheck();
  await panel
    .getByTestId('schema-config-field-style.borderRadius')
    .getByRole('spinbutton')
    .fill('16');
  await panel.getByTestId('schema-config-field-style.showLegend').getByRole('switch').uncheck();
  const color = panel.getByTestId('schema-config-field-style.backgroundColor');
  await color.getByRole('button').click();
  await color.getByRole('textbox').fill('#eef2ff');
  await panel.getByRole('heading', { name: '样式配置', exact: true }).click();
  const chartSurface = chart.locator(':scope > div').first();
  await expect(chartSurface).toHaveCSS('border-radius', '16px');
  await expect(chartSurface).toHaveCSS('background-color', 'rgb(238, 242, 255)');
  // Select the table header so authoring does not trigger the existing row drilldown.
  const actionWidget=canvas.locator('[data-widget-id="delivery-actions"]').first();
  await actionWidget.getByText('交付行动清单 · 点击行定位验收单',{exact:true}).click();
  const drill=panel.getByTestId('schema-config-field-drillDown');
  await expect(drill.getByRole('checkbox',{name:'启用点击钻取'})).toBeChecked();
  await drill.getByPlaceholder('选择页面...').click();
  await drill.getByRole('button').filter({hasText:'/p/tpm_project'}).first().click();
  await drill.getByPlaceholder('选择页面...').click();
  await drill.getByRole('button').filter({hasText:'/p/tpm_acceptance'}).first().click();
  await drill.getByTestId('dashboard-drilldown-mapping-add').click();
  await drill.getByTestId('dashboard-drilldown-mapping-key').fill('acceptance_pid');
  await drill.getByTestId('dashboard-drilldown-mapping-value').fill('pid');
  const mappingRow=drill.getByTestId('dashboard-drilldown-mapping-row');
  const mappingBox=await mappingRow.boundingBox(),removeBox=await drill.getByTestId('dashboard-drilldown-mapping-remove').boundingBox();
  expect(mappingBox).toBeTruthy();expect(removeBox).toBeTruthy();expect(removeBox!.x+removeBox!.width).toBeLessThanOrEqual(mappingBox!.x+mappingBox!.width+1);
  await shot(page,'DS03-structured-drilldown');
  await canvas.locator('[data-widget-id="pending_count"]').first().click();
  const precision = panel.getByTestId('schema-config-field-precision').getByRole('spinbutton');
  await expect(precision).toHaveValue('0');
  await precision.fill('2');
  await panel.getByTestId('schema-config-field-format').getByRole('combobox').click();
  await page.getByRole('option', { name: '百分比', exact: true }).click();
  await panel.getByTestId('schema-config-field-format').getByRole('combobox').click();
  await page.getByRole('option', { name: '数字', exact: true }).click();
  await shot(page, 'DS03-number-precision-schema');
  const saved = page.waitForResponse(
    (r) => new URL(r.url()).pathname === '/api/dashboards' && r.request().method() === 'POST',
  );
  await page.getByTestId('designer-toolbar-btn-save').click();
  const board = await data(await saved);
  await expect(page).toHaveURL(new RegExp(`/dashboard-designer/${board.pid}$`));
  const publish = page.waitForResponse(
    (r) =>
      new URL(r.url()).pathname === `/api/dashboards/${board.pid}/publish` &&
      r.request().method() === 'POST',
  );
  await page.getByTestId('toolbar-btn-publish').click();
  await data(await publish);
  const queryResponses: Promise<any>[] = [];
  page.on('response', (r) => {
    if (
      new URL(r.url()).pathname === '/api/meta/chart-data' &&
      r.request().postDataJSON()?.queryCode === 'tdo_acceptance_distribution'
    )
      queryResponses.push(data(r));
  });
  await page.reload();
  await chart.click();
  await expect(panel.getByTestId('widget-prop-title-zh')).toHaveValue(title);
  await expect(chart.locator('[data-chart-ready="true"]')).toBeVisible();
  await expect.poll(() => queryResponses.length).toBeGreaterThan(0);
  const result = (await Promise.all(queryResponses))[0];
  expect(result.rows.length).toBe(counts.size);
  for (const row of result.rows)
    expect(Number(row.acceptance_count)).toBe(counts.get(row.status_label));
  const persisted = await data(await page.request.get(`/api/dashboards/${board.pid}`));
  const geometry = persisted.widgets.find((w: any) => w.id === 'pending_count');
  expect(geometry.config.precision).toBe(2);
  expect(geometry.config.format).toBe('number');
  expect(geometry.w).toBeGreaterThan(3);
  expect(geometry.h).toBeGreaterThan(2);
  expect(geometry.x).toBeGreaterThan(0);
  await expect.poll(async () => (await readBox()).width).toBeCloseTo(resizedBox.width, 0);
  const config = persisted.widgets.find((w: any) => w.id === 'acceptance-distribution').config;
  const tableConfig=persisted.widgets.find((w:any)=>w.id==='delivery-actions').config;
  expect(tableConfig.drillDown).toMatchObject({enabled:true,action:'navigate',targetPage:'/p/tpm_acceptance',paramMapping:{acceptance_pid:'pid'}});
  expect(tableConfig.drillDown.filters).toEqual([{sourceField:'acceptance_pid',targetField:'pid',operator:'eq'}]);
  expect(config.visualization.donut).toBe(true);
  expect(config.visualization.showLabels).toBe(false);
  expect(config.title['zh-CN'] || config.title.zh).toBe(title);
  expect(config.style).toMatchObject({
    borderRadius: 16,
    backgroundColor: '#eef2ff',
    showLegend: false,
  });
  await expect(chartSurface).toHaveCSS('border-radius', '16px');
  await expect(chartSurface).toHaveCSS('background-color', 'rgb(238, 242, 255)');
  await panel.getByTestId('widget-prop-title-zh').scrollIntoViewIfNeeded();
  await chart.scrollIntoViewIfNeeded();
  await page.mouse.move(280, 85);
  await shot(page, 'SC10-dashboard-after-reload');
  const download = page.waitForEvent('download');
  await page.getByTestId('dashboard-design-export').click();
  const output = join(evidence!, 'delivery-operations-edited.dashboard.json');
  await (await download).saveAs(output);
  const exported = JSON.parse(await readFile(output, 'utf8'));
  expect(
    exported.dashboard.widgets.find((w: any) => w.id === 'acceptance-distribution').config,
  ).toEqual(config);
  await expect(page.getByText('已导出当前设计，包含未保存的修改。', { exact: true })).toBeHidden();
  await page.getByTestId('toolbar-btn-presentation').click();
  await expect(page.getByTestId('big-screen-mode')).toBeVisible();
  await expect(
    page
      .getByTestId('big-screen-mode')
      .locator('[data-widget-id="acceptance-distribution"] [data-chart-ready="true"]'),
  ).toBeVisible();
  await shot(page, 'SC10-dashboard-published');
  await page.keyboard.press('Escape');
  const clickedRow=actionWidget.getByRole('row').nth(1);await expect(clickedRow).toBeVisible();
  const clickedName=(await clickedRow.locator('td').first().innerText()).trim();
  const clickedRecord=source.records.find((row:any)=>row.tpm_ac_name===clickedName);expect(clickedRecord).toBeTruthy();
  await clickedRow.click();await expect(page).toHaveURL(url=>url.pathname==='/p/tpm_acceptance'&&url.searchParams.has('filters'));
  const target=page.getByRole('row').filter({hasText:clickedName});await expect(target).toHaveCount(1);
  await target.getByRole('button',{name:/查看|详情/}).click();await expect(page).toHaveURL(new RegExp(`/p/tpm_acceptance/view/${clickedRecord.pid}$`));
  await expect(page.getByRole('main')).toContainText(clickedName);await shot(page,'DS03-configured-drilldown-result');
  await writeFile(
    join(evidence!, 'SC10-dashboard-receipt.json'),
    JSON.stringify({ pid: board.pid, counts: Object.fromEntries(counts), config, output }, null, 2),
  );
});

test('SC10 report template → reviewed text edit → save/reload → three real exports retain live facts', async ({
  page,
}) => {
  test.setTimeout(180000);
  await login(page);
  const template = JSON.parse(
    await readFile(
      join(
        root!,
        'plugins/project-delivery-operations/examples/delivery-retrospective.report-template.json',
      ),
      'utf8',
    ),
  );
  const facts=await data(await page.request.post('/api/meta/named-queries/tdo_delivery_facts/execute',{data:{parameters:{},size:500}}));
  expect(facts.records.length).toBeGreaterThan(0);
  const groupAmounts=new Map<string,number>();
  for(const row of facts.records){expect(Number.isFinite(Number(row.amount))).toBe(true);groupAmounts.set(row.project_name,(groupAmounts.get(row.project_name)||0)+Number(row.amount));}
  const totalAmount=[...groupAmounts.values()].reduce((sum,value)=>sum+value,0);
  const money=(value:number)=>'¥'+value.toLocaleString('zh-CN',{minimumFractionDigits:2,maximumFractionDigits:2});
  const original = template.dsl.body.find((b: any) => b.id === 'scope').content;
  const note =
    '本周复盘说明：先催办当前逾期审批，再补正退回材料；资料更新后须重新核验。以下表格按当前账号权限实时查询，验收金额不等于收入。';
  await navigateToMenuByClick(page, ['元数据管理', '报表管理']);
  await page.getByRole('button', { name: '新建报表', exact: true }).click();
  const chooser = page.waitForEvent('filechooser');
  await page.getByTestId('report-template-import').click();
  await (
    await chooser
  ).setFiles(
    join(
      root!,
      'plugins/project-delivery-operations/examples/delivery-retrospective.report-template.json',
    ),
  );
  await expect(page.getByTestId('report-template-imported')).toBeVisible();
  const paper = page.getByTestId('report-paper');
  await expect.poll(async () => Number(await paper.getAttribute('data-scale'))).toBeLessThan(1);
  const fitBox = await paper.boundingBox();
  expect(fitBox).toBeTruthy();
  await shot(page, 'DS04-paper-fit');
  await page.getByTestId('report-canvas-actual').click();
  await expect(paper).toHaveAttribute('data-scale', '1');
  await expect.poll(async () => (await paper.boundingBox())!.width).toBeCloseTo(891, 0);
  await shot(page, 'DS04-paper-actual');
  await page.getByTestId('report-canvas-fit').click();
  await expect.poll(async () => (await paper.boundingBox())!.width).toBeCloseTo(fitBox!.width, 0);
  const blocks = page.getByTestId('report-canvas').locator('[data-testid^="drag-handle-"]');
  const originalOrder = await blocks.evaluateAll((elements) =>
    elements.map((el) => el.getAttribute('data-testid')),
  );
  expect(originalOrder.length).toBeGreaterThan(2);
  const sourceHandle = page.getByTestId(originalOrder[1]!);
  const targetBlock = page.getByTestId(originalOrder[0]!).locator('..');
  await sourceHandle.dragTo(targetBlock, { targetPosition: { x: 30, y: 4 } });
  const movedOrder = [originalOrder[1], originalOrder[0], ...originalOrder.slice(2)];
  await expect
    .poll(() =>
      blocks.evaluateAll((elements) => elements.map((el) => el.getAttribute('data-testid'))),
    )
    .toEqual(movedOrder);
  await page.getByTestId('report-designer-toolbar-btn-undo').click();
  await expect
    .poll(() =>
      blocks.evaluateAll((elements) => elements.map((el) => el.getAttribute('data-testid'))),
    )
    .toEqual(originalOrder);
  await page.getByTestId('report-designer-toolbar-btn-redo').click();
  await expect
    .poll(() =>
      blocks.evaluateAll((elements) => elements.map((el) => el.getAttribute('data-testid'))),
    )
    .toEqual(movedOrder);
  await page.getByTestId('drag-handle-scope').locator('..').click();
  const fontSize = page.getByTestId('schema-config-field-style.fontSize').getByRole('spinbutton');
  await fontSize.fill('18');
  await shot(page, 'DS04-report-drag-schema-properties');
  const editor = page.getByTestId('block-property-panel').getByPlaceholder('输入文本内容…');
  await expect(editor).toHaveValue(original);
  await editor.fill(note);
  await shot(page, 'SC10-report-edited-draft');
  await page.getByTestId('drag-handle-summary').locator('..').click();
  await page.getByTestId('schema-config-field-title').getByRole('textbox').fill('当前交付经营摘要');
  await shot(page, 'DS04-table-schema-properties');
  await page.getByTestId('drag-handle-acceptance').locator('..').click();
  const tableColumns=page.getByTestId('schema-config-field-columns');
  const originalColumnCount=await tableColumns.getByRole('group').count();
  const originalFields=template.dsl.body.find((block:any)=>block.id==='acceptance').columns.map((column:any)=>column.field);
  await tableColumns.getByRole('group').nth(1).getByRole('button',{name:'上移',exact:true}).click();
  await page.getByTestId('report-designer-toolbar-btn-undo').click();
  await expect(tableColumns.getByRole('group').first().getByRole('textbox',{name:'字段名称*',exact:true})).toHaveValue(originalFields[0]);
  await page.getByTestId('report-designer-toolbar-btn-redo').click();
  await expect(tableColumns.getByRole('group').first().getByRole('textbox',{name:'字段名称*',exact:true})).toHaveValue(originalFields[1]);
  await tableColumns.getByRole('group').first().getByRole('spinbutton',{name:'列宽',exact:true}).fill('240');
  await tableColumns.getByRole('button',{name:'+ 添加',exact:true}).click();
  await expect(tableColumns.getByRole('group')).toHaveCount(originalColumnCount+1);
  await tableColumns.getByRole('group').last().getByRole('button',{name:'remove',exact:true}).click();
  await expect(tableColumns.getByRole('group')).toHaveCount(originalColumnCount);
  await shot(page,'DS04-reordered-columns');
  const addedTitles: Record<string, string> = {
    'grouped-table': '按客户项目分组',
    'stat-card': '当前明细金额合计',
    'cross-tab': '项目与验收状态交叉分析',
    chart: '项目验收金额分布',
    barcode: '验收记录条码',
    watermark: '内部交付复盘',
  };
  const addedTypes = Object.keys(addedTitles);
  for (const type of addedTypes) {
    await page.getByTestId(`block-palette-item-${type}`).click();
    await expect(
      page
        .getByTestId('report-canvas')
        .locator('[data-testid^="drag-handle-"]')
        .last()
        .locator('..'),
    ).toBeInViewport({ ratio: 0.9 });
    const panel = page.getByTestId('block-property-panel');
    const textField = (key: string) =>
      panel.getByTestId(`schema-config-field-${key}`).getByRole('textbox');
    if (type === 'watermark') await textField('text').fill('内部交付复盘');
    else await textField('title').fill(addedTitles[type]);
    if (['grouped-table', 'stat-card', 'cross-tab', 'chart'].includes(type)) {
      await panel
        .getByTestId('schema-config-field-dataSource')
        .locator('select')
        .selectOption('delivery');
    }
    if (type === 'grouped-table') {
      await textField('groupByField').fill('project_name');
      const columns = panel.getByTestId('schema-config-field-columns');
      await columns.getByRole('button', { name: '+ 添加', exact: true }).click();
      await columns.getByRole('group').last().getByRole('textbox',{name:'字段名称*',exact:true}).fill('acceptance_name');
      await columns.getByRole('group').last().getByRole('textbox',{name:'显示名称',exact:true}).fill('验收事项');
      await columns.getByRole('button', { name: '+ 添加', exact: true }).click();
      const amountColumn=columns.getByRole('group').last();
      await amountColumn.getByRole('textbox', { name: '字段名称*', exact: true }).fill('amount');
      await amountColumn.getByRole('textbox', { name: '显示名称', exact: true }).fill('交付金额');
      await amountColumn.getByRole('combobox',{name:'格式',exact:true}).click();await page.getByRole('option',{name:'金额',exact:true}).click();
      const width = amountColumn.getByRole('spinbutton', { name: '列宽', exact: true });
      await expect(width).toHaveValue('');
      await width.fill('0');
      await expect(columns.getByRole('alert')).toBeVisible();
      await shot(page, 'DS04-numeric-range');
      await width.fill('120');
      await expect(columns.getByRole('alert')).toHaveCount(0);
      for(const key of ['groupSubtotal','grandTotal']) {
        await panel.getByTestId(`schema-config-field-${key}.enabled`).getByRole('switch').check();
        await panel.getByTestId(`schema-config-field-${key}.label`).getByRole('textbox').fill(key==='groupSubtotal'?'本组交付金额小计':'全部项目交付金额总计');
        const summary=panel.getByTestId(`schema-config-field-${key}.columns`);
        await summary.getByRole('button',{name:'+ 添加',exact:true}).click();
        await summary.getByRole('textbox',{name:'字段名称*',exact:true}).fill('amount');
        const aggregate=summary.getByRole('combobox',{name:'聚合方式',exact:true});
        await aggregate.click();await page.getByRole('option',{name:'平均值',exact:true}).click();
        await aggregate.click();await page.getByRole('option',{name:'求和',exact:true}).click();
        await expect(aggregate).toContainText('求和');
        await summary.getByRole('combobox',{name:'格式',exact:true}).click();await page.getByRole('option',{name:'金额',exact:true}).click();
      }

    }
    if (['stat-card', 'cross-tab', 'chart'].includes(type))
      await textField('valueField').fill('amount');
    if (type === 'stat-card') await textField('label').fill('交付总金额');
    if(['stat-card','cross-tab'].includes(type)){await panel.getByTestId('schema-config-field-format').getByRole('combobox').click();await page.getByRole('option',{name:'金额',exact:true}).click();}
    if (type === 'cross-tab') {
      await textField('rowField').fill('project_name');
      await textField('columnField').fill('acceptance_label');
      await textField('rowLabel').fill('客户项目');await textField('columnLabel').fill('验收状态');await textField('totalLabel').fill('合计');
    }
    if (type === 'chart') {
      await textField('categoryField').fill('project_name');
      const chartWidth=panel.getByTestId('schema-config-field-width');await chartWidth.getByRole('spinbutton').fill('860');
      await expect(chartWidth.getByRole('alert')).toContainText('800');
      await chartWidth.getByRole('spinbutton').fill('760');await expect(chartWidth.getByRole('alert')).toHaveCount(0);
      await panel.getByTestId('schema-config-field-height').getByRole('spinbutton').fill('240');
      await page.getByTestId('report-canvas').locator('[data-testid^="drag-handle-"]').last().locator('..').scrollIntoViewIfNeeded();
    }
    if (type === 'barcode') await textField('staticValue').fill('ACC20261021');
    await expect(
      page
        .getByTestId('report-canvas')
        .locator('[data-testid^="drag-handle-"]')
        .last()
        .locator('..'),
    ).toBeInViewport({ ratio: 0.9 });
    await shot(page, `DS04-${type}-schema-properties`);
  }
  const title = '客户项目交付复盘 · 每周行动';
  await page.getByRole('textbox', { name: '报表标题', exact: true }).fill(title);
  const save = page.waitForResponse(
    (r) =>
      new URL(r.url()).pathname === '/api/report-definitions' && r.request().method() === 'POST',
  );
  await page.getByTestId('report-designer-toolbar-btn-save').click();
  const saved = await data(await save);
  await expect(page).toHaveURL(new RegExp(`/report-designer/${saved.pid}$`));
  await page.reload();
  const persisted = await data(await page.request.get(`/api/report-definitions/${saved.pid}`));
  expect(persisted.dsl.body.find((b: any) => b.id === 'scope').content).toBe(note);
  expect(persisted.dsl.dataSources).toEqual(template.dsl.dataSources);
  expect(persisted.dsl.page).toEqual(template.dsl.page);
  expect(persisted.dsl.body.find((b: any) => b.id === 'summary').title).toBe('当前交付经营摘要');
  expect(
    persisted.dsl.body.slice(0, movedOrder.length).map((b: any) => `drag-handle-${b.id}`),
  ).toEqual(movedOrder);
  expect(persisted.dsl.body.slice(movedOrder.length).map((b: any) => b.blockType)).toEqual(
    addedTypes,
  );
  for (const block of persisted.dsl.body.slice(movedOrder.length)) {
    if (block.blockType === 'watermark') expect(block.text).toBe('内部交付复盘');
    else expect(block.title).toBe(addedTitles[block.blockType]);
    if (['grouped-table', 'stat-card', 'cross-tab', 'chart'].includes(block.blockType))
      expect(block.dataSource).toBe('delivery');
  }
  expect(persisted.dsl.body.find((b: any) => b.id === 'scope').style.fontSize).toBe(18);
  const storedColumns=persisted.dsl.body.find((block:any)=>block.id==='acceptance').columns;
  expect(storedColumns.map((column:any)=>column.field)).toEqual([originalFields[1],originalFields[0],...originalFields.slice(2)]);
  expect(storedColumns[0].width).toBe(240);
  expect(persisted.dsl.body.find((block:any)=>block.blockType==='chart')).toMatchObject({width:760,height:240});
  const grouped=persisted.dsl.body.find((block:any)=>block.blockType==='grouped-table');
  expect(grouped.groupSubtotal).toMatchObject({enabled:true,label:'本组交付金额小计',columns:[{field:'amount',aggregation:'sum',format:'currency'}]});
  expect(grouped.grandTotal).toMatchObject({enabled:true,label:'全部项目交付金额总计',columns:[{field:'amount',aggregation:'sum',format:'currency'}]});

  expect(
    persisted.dsl.body.find((b: any) => b.blockType === 'grouped-table').columns.find((column:any)=>column.field==='amount').width,
  ).toBe(120);
  const toolbar = page.getByTestId('report-designer-toolbar');
  await toolbar.getByRole('button', { name: '预览', exact: true }).click();
  await expect(page.getByRole('main')).toContainText(note);
  await expect(page.getByRole('main')).toContainText('四、核验快照与跟进行动');
  const runtimePaper = page.getByTestId('report-runtime-paper');
  const watermark = page.getByTestId('report-watermark-overlay');
  await expect(watermark).toHaveCSS('position', 'absolute');
  await expect(runtimePaper).toHaveCSS('isolation', 'isolate');
  expect(
    await watermark.evaluate(
      (element) =>
        element instanceof HTMLElement && element.offsetParent === document.querySelector('[data-testid="report-runtime-paper"]'),
    ),
  ).toBe(true);
  const paperBox = await runtimePaper.boundingBox();
  const watermarkBox = await watermark.boundingBox();
  expect(paperBox).not.toBeNull();
  expect(watermarkBox).not.toBeNull();
  expect(watermarkBox!.x).toBeGreaterThanOrEqual(paperBox!.x);
  expect(watermarkBox!.y).toBeGreaterThanOrEqual(paperBox!.y);
  expect(watermarkBox!.x + watermarkBox!.width).toBeLessThanOrEqual(
    paperBox!.x + paperBox!.width + 1,
  );
  expect(watermarkBox!.y + watermarkBox!.height).toBeLessThanOrEqual(
    paperBox!.y + paperBox!.height + 1,
  );
  await expect(page.getByRole('main')).toContainText('本组交付金额小计');await expect(page.getByRole('main')).toContainText('全部项目交付金额总计');
  await shot(page, 'SC10-report-persisted-preview');
  const files = [];
  for (const [format, label, ext] of [
    ['markdown', 'Markdown', 'md'],
    ['html', 'HTML', 'html'],
    ['pdf', 'PDF', 'pdf'],
  ]) {
    const response = page.waitForResponse(
      (r) =>
        new URL(r.url()).pathname === `/api/reports/export/${format}` &&
        r.request().method() === 'POST',
    );
    const download = page.waitForEvent('download');
    await toolbar.getByRole('button', { name: `导出 ${label}`, exact: true }).click();
    const r = await response;
    expect(r.status()).toBe(200);
    expect(r.request().postDataJSON().reportPid).toBe(saved.pid);
    const path = join(evidence!, `delivery-retrospective-edited.${ext}`);
    await (await download).saveAs(path);
    let text = await readFile(path, 'utf8');
    if (format === 'pdf')
      text = execFileSync('pdftotext', ['-layout', path, '-'], { encoding: 'utf8' });
    if (format === 'html')
      text = await page.evaluate(
        (html) => new DOMParser().parseFromString(html, 'text/html').body.textContent || '',
        text,
      );
    const compact = (value: string) => value.replace(/\s+/g, '');
    expect(compact(text)).toContain(compact(note));
    for (const heading of [
      '一、验收与责任人',
      '二、审批执行结果',
      '三、SLA 实际结果',
      '四、核验快照与跟进行动',
    ])
      expect(compact(text)).toContain(compact(heading));
    expect(text).toContain('华辰制造');
    const groupSection=compact(text).split(compact(addedTitles['grouped-table']))[1]?.split(compact(addedTitles['stat-card']))[0];
    expect(groupSection,format+' grouped summary section').toBeTruthy();
    expect(groupSection).toContain('本组交付金额小计');expect(groupSection).toContain('全部项目交付金额总计');
    for(const [project,total] of groupAmounts){expect(groupSection).toContain(compact(project));expect(groupSection).toContain(money(total));}
    expect(groupSection).toContain(money(totalAmount));
    const statSection=compact(text).split(compact(addedTitles['stat-card']))[1]?.split(compact(addedTitles['cross-tab']))[0];
    expect(statSection,format+' statistic currency').toContain(money(totalAmount));
    const crossSection=compact(text).split(compact(addedTitles['cross-tab']))[1]?.split(compact(addedTitles.chart))[0];
    expect(crossSection,format+' crosstab labels and money').toContain('客户项目');expect(crossSection).toContain('验收状态');expect(crossSection).toContain('合计');expect(crossSection).toContain(money(totalAmount));
    files.push(path);
  }
  await writeFile(
    join(evidence!, 'SC10-report-receipt.json'),
    JSON.stringify({ pid: saved.pid, note, files }, null, 2),
  );
});
