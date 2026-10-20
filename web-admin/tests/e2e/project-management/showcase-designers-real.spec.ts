/** Real UI edits of reusable delivery designs; no injected designer state. */
import { test, expect, type Page, type APIResponse, type Response } from '@playwright/test';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { execFileSync } from 'node:child_process';
import { navigateToMenuByClick } from '../helpers';
import { loginDeliveryMember } from '../../helpers/delivery-session';

const root = process.env.AURA_ENTERPRISE_PROJECT_ROOT;
const evidence = process.env.AURA_EVIDENCE_DIR;
if (!root || !evidence || !process.env.AURA_ADMIN_TENANT_ID) throw Error('Bind source, evidence and tenant.');
async function data(r: APIResponse | Response) {
  const b = await r.json(); expect(r.status(), JSON.stringify(b)).toBe(200);
  expect(String(b.code)).toBe('0'); return b.data;
}
async function login(page: Page) {
  page.setDefaultTimeout(20000);
  await loginDeliveryMember(page);
  const me = await data(await page.request.get('/api/auth/me'));
  expect(String(me.user.tenantId)).toBe(process.env.AURA_ADMIN_TENANT_ID);
  expect(me.user.email).toBe(process.env.ADMIN_EMAIL);
  await mkdir(evidence!, {recursive: true});
}
async function shot(page: Page, name: string) {
  await page.screenshot({path: join(evidence!, `${name}.png`), fullPage: false});
}

test('SC10 dashboard template → real donut/title edit → save/publish → reload → exported configuration', async ({page}) => {
  test.setTimeout(180000); await login(page);
  // Explicit fixture setup imports governed query definitions; UI authors the board copy below.
  const imported = await page.request.post('/api/plugins/import/import-directory-sync', {data:{path:join(root!,'plugins/project-delivery-operations'),conflictStrategy:'OVERWRITE',deferReferenceValidation:true}});
  expect(imported.ok()).toBe(true); expect((await imported.json()).success).toBe(true);
  const source = await data(await page.request.get('/api/dynamic/tpm_acceptance/list?pageSize=500'));
  expect(Number(source.total)).toBe(source.records.length);
  expect(source.records.length).toBeGreaterThan(0);
  const counts = new Map<string, number>();
  const labels: Record<string,string> = {draft:'待提交',submitted:'审批中',accepted:'已通过',returned:'已退回'};
  for (const row of source.records) { const label = labels[row.tpm_ac_status]; expect(label).toBeTruthy(); counts.set(label,(counts.get(label)||0)+1); }
  await navigateToMenuByClick(page, ['元数据管理','仪表盘管理']);
  await page.getByTestId('toolbar-btn-create').click();
  await page.getByTestId('dashboard-design-import-file').setInputFiles(join(root!, 'plugins/project-delivery-operations/examples/delivery-operations.dashboard-template.json'));
  await page.getByRole('button', {name:'导入为新草稿',exact:true}).click();
  const canvas = page.getByTestId('designer-canvas');
  const chart = canvas.locator('[data-widget-id="acceptance-distribution"]').first();
  await expect(chart.locator('[data-chart-ready="true"]')).toBeVisible();
  await chart.click();
  const panel = page.getByTestId('widget-property-panel');
  await expect(panel.getByTestId('widget-prop-visualization-donut')).not.toBeChecked();
  const filters = panel.getByTestId('designer-filter-builder');
  await expect(filters).toContainText('未设置筛选条件');
  await expect(panel.getByRole('option', {name:/交付验收状态分布/})).toBeAttached();
  await page.mouse.move(280, 85);
  await shot(page, 'SC10-dashboard-before');
  let unfinishedQueries = 0;
  page.on('request', request => {
    if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/meta/chart-data'
      && request.postDataJSON()?.filters?.some((filter:any) => !filter.field?.trim())) unfinishedQueries++;
  });
  await filters.getByRole('button', {name:'添加条件',exact:true}).click();
  await expect(filters.getByRole('textbox', {name:'字段',exact:true})).toBeVisible();
  await expect(filters.getByRole('option', {name:'等于',exact:true})).toBeAttached();
  await expect(chart.getByTestId('chart-filter-draft')).toContainText('请补全筛选条件后预览');
  await expect(chart.getByRole('alert')).toHaveCount(0);
  await filters.scrollIntoViewIfNeeded();
  await shot(page, 'SC10-dashboard-filter-controls');
  await filters.getByRole('button', {name:'移除条件',exact:true}).click();
  await expect(filters).toContainText('未设置筛选条件');
  await expect(chart.locator('[data-chart-ready="true"]')).toBeVisible();
  expect(unfinishedQueries, 'Unfinished designer filters must not query the backend').toBe(0);
  const title = '验收状态 · 负责人';
  await panel.getByTestId('widget-prop-title-zh').fill(title);
  await panel.getByTestId('widget-prop-visualization-donut').check();
  await panel.getByTestId('widget-prop-visualization-showLabels').uncheck();
  const saved = page.waitForResponse(r => new URL(r.url()).pathname === '/api/dashboards' && r.request().method() === 'POST');
  await page.getByTestId('designer-toolbar-btn-save').click();
  const board = await data(await saved);
  await expect(page).toHaveURL(new RegExp(`/dashboard-designer/${board.pid}$`));
  const publish = page.waitForResponse(r => new URL(r.url()).pathname === `/api/dashboards/${board.pid}/publish` && r.request().method() === 'POST');
  await page.getByTestId('toolbar-btn-publish').click(); await data(await publish);
  const queryResponses: Promise<any>[] = [];
  page.on('response', r => { if(new URL(r.url()).pathname === '/api/meta/chart-data' && r.request().postDataJSON()?.queryCode === 'tdo_acceptance_distribution') queryResponses.push(data(r)); });
  await page.reload();
  await chart.click();
  await expect(panel.getByTestId('widget-prop-title-zh')).toHaveValue(title);
  await expect(chart.locator('[data-chart-ready="true"]')).toBeVisible();
  await expect.poll(() => queryResponses.length).toBeGreaterThan(0);
  const result = (await Promise.all(queryResponses))[0];
  expect(result.rows.length).toBe(counts.size);
  for(const row of result.rows) expect(Number(row.acceptance_count)).toBe(counts.get(row.status_label));
  const persisted = await data(await page.request.get(`/api/dashboards/${board.pid}`));
  const config = persisted.widgets.find((w:any) => w.id === 'acceptance-distribution').config;
  expect(config.visualization.donut).toBe(true);
  expect(config.visualization.showLabels).toBe(false);
  expect(config.title['zh-CN'] || config.title.zh).toBe(title);
  await panel.getByTestId('widget-prop-title-zh').scrollIntoViewIfNeeded();
  await chart.scrollIntoViewIfNeeded();
  await page.mouse.move(280, 85);
  await shot(page, 'SC10-dashboard-after-reload');
  const download = page.waitForEvent('download'); await page.getByTestId('dashboard-design-export').click();
  const output = join(evidence!, 'delivery-operations-edited.dashboard.json'); await (await download).saveAs(output);
  const exported = JSON.parse(await readFile(output,'utf8'));
  expect(exported.dashboard.widgets.find((w:any) => w.id === 'acceptance-distribution').config).toEqual(config);
  await expect(page.getByText('已导出当前设计，包含未保存的修改。', {exact:true})).toBeHidden();
  await page.getByTestId('toolbar-btn-presentation').click();
  await expect(page.getByTestId('big-screen-mode')).toBeVisible();
  await expect(page.getByTestId('big-screen-mode').locator('[data-widget-id="acceptance-distribution"] [data-chart-ready="true"]')).toBeVisible();
  await shot(page, 'SC10-dashboard-published');
  await page.keyboard.press('Escape');
  await writeFile(join(evidence!, 'SC10-dashboard-receipt.json'), JSON.stringify({pid:board.pid,counts:Object.fromEntries(counts),config,output},null,2));
});

test('SC10 report template → reviewed text edit → save/reload → three real exports retain live facts', async ({page}) => {
  test.setTimeout(180000); await login(page);
  const template = JSON.parse(await readFile(join(root!, 'plugins/project-delivery-operations/examples/delivery-retrospective.report-template.json'),'utf8'));
  const original = template.dsl.body.find((b:any) => b.id === 'scope').content;
  const note = '本周复盘说明：先催办当前逾期审批，再补正退回材料；资料更新后须重新核验。以下表格按当前账号权限实时查询，验收金额不等于收入。';
  await navigateToMenuByClick(page,['元数据管理','报表管理']);
  await page.getByRole('button',{name:'新建报表',exact:true}).click();
  const chooser = page.waitForEvent('filechooser'); await page.getByTestId('report-template-import').click();
  await (await chooser).setFiles(join(root!, 'plugins/project-delivery-operations/examples/delivery-retrospective.report-template.json'));
  await expect(page.getByTestId('report-template-imported')).toBeVisible();
  await page.getByTestId('drag-handle-scope').locator('..').click();
  const editor = page.getByTestId('block-property-panel').getByPlaceholder('输入文本内容…');
  await expect(editor).toHaveValue(original);
  await editor.fill(note); await shot(page,'SC10-report-edited-draft');
  const title = '客户项目交付复盘 · 每周行动'; await page.getByRole('textbox',{name:'报表标题',exact:true}).fill(title);
  const save = page.waitForResponse(r => new URL(r.url()).pathname === '/api/report-definitions' && r.request().method() === 'POST');
  await page.getByTestId('report-designer-toolbar-btn-save').click(); const saved = await data(await save);
  await expect(page).toHaveURL(new RegExp(`/report-designer/${saved.pid}$`)); await page.reload();
  const persisted = await data(await page.request.get(`/api/report-definitions/${saved.pid}`));
  expect(persisted.dsl.body.find((b:any) => b.id === 'scope').content).toBe(note);
  expect(persisted.dsl.dataSources).toEqual(template.dsl.dataSources);
  const toolbar = page.getByTestId('report-designer-toolbar');
  await toolbar.getByRole('button',{name:'预览',exact:true}).click();
  await expect(page.getByRole('main')).toContainText(note);
  await expect(page.getByRole('main')).toContainText('四、核验快照与跟进行动');
  await shot(page,'SC10-report-persisted-preview');
  const files = [];
  for(const [format,label,ext] of [['markdown','Markdown','md'],['html','HTML','html'],['pdf','PDF','pdf']]) {
    const response = page.waitForResponse(r => new URL(r.url()).pathname === `/api/reports/export/${format}` && r.request().method() === 'POST');
    const download = page.waitForEvent('download'); await toolbar.getByRole('button',{name:`导出 ${label}`,exact:true}).click();
    const r = await response; expect(r.status()).toBe(200); expect(r.request().postDataJSON().reportPid).toBe(saved.pid);
    const path = join(evidence!,`delivery-retrospective-edited.${ext}`); await (await download).saveAs(path);
    let text = await readFile(path,'utf8');
    if(format === 'pdf') text = execFileSync('pdftotext',['-layout',path,'-'],{encoding:'utf8'});
    if(format === 'html') text = await page.evaluate(html => new DOMParser().parseFromString(html,'text/html').body.textContent || '',text);
    const compact = (value:string) => value.replace(/\s+/g,'');
    expect(compact(text)).toContain(compact(note));
    for(const heading of ['一、验收与责任人','二、审批执行结果','三、SLA 实际结果','四、核验快照与跟进行动']) expect(compact(text)).toContain(compact(heading));
    expect(text).toContain('华辰制造'); files.push(path);
  }
  await writeFile(join(evidence!,'SC10-report-receipt.json'),JSON.stringify({pid:saved.pid,note,files},null,2));
});
