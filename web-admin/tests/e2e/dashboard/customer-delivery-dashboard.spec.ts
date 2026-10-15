import fs from 'node:fs';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { test, expect, type Page } from '@playwright/test';
import { ensureSidebarExpanded, navigateToMenuByClick } from '../helpers';

// No API-created dashboards or injected store state: new widget authoring,
// publication, template transfer and edits all go through the visible UI.
test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json', locale: 'zh-CN' });
test.setTimeout(180000);
const root = path.resolve(process.cwd(), '../plugins/project-management');
const template = JSON.parse(fs.readFileSync(path.join(root, 'examples/dashboard-customer-delivery.json'), 'utf8'));
const references: unknown[][] = JSON.parse(fs.readFileSync(path.join(root, 'scripts/reference-projects.json'), 'utf8'));
const run = randomUUID().slice(0, 8);

async function management(page: Page) {
  await ensureSidebarExpanded(page);
  await navigateToMenuByClick(page, ['元数据管理', '仪表盘管理']);
}
async function title(page: Page, value: string) {
  await page.getByTestId('toolbar-btn-settings').click();
  const dialog = page.getByRole('dialog', { name: 'Dashboard Settings' });
  await dialog.locator('input[type="text"]').first().fill(value);
  await dialog.getByRole('button', { name: /^(保存|Save)$/ }).click();
  await expect(dialog).toBeHidden();
}
async function detail(page: Page, pid: string) {
  const response = await page.request.get(`/api/dashboards/${pid}`);
  expect(response.ok()).toBe(true);
  const body = await response.json();
  expect(String(body.code)).toBe('0');
  return body.data;
}
async function saveNew(page: Page) {
  await expect(page.getByTestId('designer-toolbar-btn-save')).toBeEnabled();
  const response = page.waitForResponse(r => /^\/api\/dashboards(?:\/[0-9A-Z]{26})?$/.test(new URL(r.url()).pathname) && ['POST', 'PUT'].includes(r.request().method()));
  await page.getByTestId('designer-toolbar-btn-save').click();
  const saved = await response;
  expect(saved.ok()).toBe(true);
  const body = await saved.json();
  expect(String(body.code)).toBe('0');
  const pid = body.data.pid;
  expect(pid).toMatch(/^[0-9A-Z]{26}$/);
  await expect(page).toHaveURL(new RegExp(`/dashboard-designer/${pid}$`));
  return pid as string;
}
async function publish(page: Page, pid: string) {
  const response = page.waitForResponse(r => new URL(r.url()).pathname === `/api/dashboards/${pid}/publish` && r.request().method() === 'POST');
  await page.getByTestId('toolbar-btn-publish').click();
  const published = await response;
  expect(published.ok()).toBe(true);
  expect(String((await published.json()).code)).toBe('0');
  expect((await detail(page, pid)).status).toBe('published');
}

test('builds a live project metric in the UI and publishes a portable six-widget customer board', async ({ page }, info) => {
  const fixturePath = process.env.DELIVERY_FIXTURE_PATH;
  expect(fixturePath, 'Set DELIVERY_FIXTURE_PATH to the retained initializer fixture.json').toBeTruthy();
  const fixture = JSON.parse(fs.readFileSync(fixturePath!, 'utf8'));
  expect(fixture.projects.length).toBe(references.length);
  await page.goto('/');
  const expected = {
    count: fixture.projects.length,
    budget: references.reduce((sum, row) => sum + Number(String(row[11]).replace(/,/g, '')), 0),
    acceptance: references.filter(row => row[3] === '待验收').length,
    completed: references.filter(row => row[3] === '已交付').length,
  };
  for (const project of fixture.projects) {
    const response = await page.request.get(`/api/dynamic/tpm_project/${project.pid}`);
    expect(response.ok()).toBe(true);
    const body = await response.json();
    expect(String(body.code)).toBe('0');
    const reference = references.find(row => row[0] === project.name)!;
    expect(reference).toBeDefined();
    expect(body.data.tpm_pj_name).toBe(reference[0]);
    expect(Number(body.data.tpm_pj_budget)).toBe(Number(String(reference[11]).replace(/,/g, '')));
  }
  const liveList = await (await page.request.get('/api/dynamic/tpm_project/list?size=100')).json();
  expect(String(liveList.code)).toBe('0');
  expect(liveList.data.records.map((r: any) => r.pid).sort()).toEqual(fixture.projects.map((r: any) => r.pid).sort());
  const aggregateEvidence: any[] = [];
  page.on('response', async response => {
    if (new URL(response.url()).pathname !== '/api/meta/chart-data' || response.request().method() !== 'POST') return;
    const request = response.request().postDataJSON();
    if (request.modelCode === 'tpm_project') aggregateEvidence.push({ request, body: await response.json() });
  });

  // S4 authoring: add a number card, select its model/field/aggregation, save and publish.
  await management(page);
  await page.getByTestId('toolbar-btn-create').click();
  await expect(page.getByTestId('designer-canvas')).toBeVisible();
  await page.getByTestId('widget-palette-item-smart-number-card').click();
  const panel = page.getByTestId('widget-property-panel');
  await panel.getByTestId('widget-prop-title-zh').fill('客户项目');
  const model = panel.locator('select').filter({ has: page.locator('option[value="tpm_project"]') });
  await expect(model).toHaveCount(1);
  await model.selectOption('tpm_project');
  const field = panel.locator('select').filter({ has: page.locator('option[value="tpm_pj_code"]') });
  await expect(field).toHaveCount(1);
  await field.selectOption('tpm_pj_code');
  await panel.locator('select').filter({ has: page.locator('option[value="count"]') }).selectOption('count');
  await title(page, `客户项目指标-${run}`);
  const authoredPid = await saveNew(page);
  const authored = await detail(page, authoredPid);
  expect(authored.widgets).toHaveLength(1);
  expect(authored.widgets[0].config.dataSource).toMatchObject({ type: 'aggregate', modelCode: 'tpm_project', metrics: [{ field: 'tpm_pj_code', aggregation: 'count' }] });
  await expect(page.getByTestId('designer-canvas').getByText(String(expected.count), { exact: true })).toBeVisible();
  await publish(page, authoredPid);
  await page.screenshot({ path: info.outputPath('CD01-ui-authored-project-metric.png'), fullPage: true });

  // S5: import the reusable design as a separate draft, edit, save, publish and reopen.
  await page.getByTestId('dashboard-design-import-file').setInputFiles({ name: 'customer-delivery.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(template)) });
  await page.getByRole('button', { name: '导入为新草稿', exact: true }).click();
  const boardTitle = `客户项目交付总览-${run}`;
  await title(page, boardTitle);
  const boardPid = await saveNew(page);
  expect(boardPid).not.toBe(authoredPid);
  const saved = await detail(page, boardPid);
  expect(saved.widgets).toHaveLength(6);
  expect(saved.title).toBe(boardTitle);
  await publish(page, boardPid);
  await page.reload();
  const canvas = page.getByTestId('designer-canvas');
  for (const [id, value] of [['project-count', expected.count], ['awaiting-acceptance', expected.acceptance], ['delivered-projects', expected.completed]] as const) {
    await expect(canvas.locator(`[data-widget-id="${id}"]`).getByText(String(value), { exact: true })).toBeVisible();
  }
  await expect(canvas.locator('[data-widget-id="project-budget"]')).toContainText(expected.budget.toLocaleString('zh-CN'));
  const table = canvas.locator('[data-widget-id="delivery-projects"]');
  for (const project of fixture.projects) await expect(table.getByText(project.name, { exact: true })).toBeVisible();
  await expect(table.getByText('待验收', { exact: true }).first()).toBeVisible();
  await expect(table.getByText('已交付', { exact: true }).first()).toBeVisible();
  await expect(canvas.locator('[data-widget-id="delivery-stages"] [data-chart-ready="true"]')).toBeVisible();
  for (const [id, value] of [['project-count', expected.count], ['project-budget', expected.budget], ['awaiting-acceptance', expected.acceptance], ['delivered-projects', expected.completed]] as const) {
    const source = template.dashboard.widgets.find((w: any) => w.id === id).config.dataSource;
    await expect.poll(() => aggregateEvidence.some(e => JSON.stringify(e.request.metrics) === JSON.stringify(source.metrics) && JSON.stringify(e.request.filters || []) === JSON.stringify(source.filters || []) && String(e.body.code) === '0' && Number(e.body.data.rows?.[0]?.value) === value)).toBe(true);
  }
  await page.screenshot({ path: info.outputPath('CD02-customer-delivery-board.png'), fullPage: true });
  const [download] = await Promise.all([page.waitForEvent('download'), page.getByTestId('dashboard-design-export').click()]);
  const exportedPath = info.outputPath('customer-delivery-dashboard.json');
  await download.saveAs(exportedPath);
  const exported = JSON.parse(fs.readFileSync(exportedPath, 'utf8'));
  expect(exported.dashboard.widgets).toHaveLength(6);
  expect(exported.dashboard.title).toBe(boardTitle);
  expect(exported.dashboard).not.toHaveProperty('pid');
  expect((await detail(page, authoredPid)).widgets).toHaveLength(1);
  await info.attach('customer-delivery-dashboard-ledger', { body: Buffer.from(JSON.stringify({ fixturePath, authoredPid, boardPid, expected, aggregateEvidence }, null, 2)), contentType: 'application/json' });
});
