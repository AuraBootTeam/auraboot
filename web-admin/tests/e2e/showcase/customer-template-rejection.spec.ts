import fs from 'node:fs';
import path from 'node:path';
import { test, expect, type Page } from '@playwright/test';
import { ensureSidebarExpanded, navigateToMenuByClick } from '../helpers';

test.use({storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json', locale: 'zh-CN'});
test.setTimeout(120000);
const template = JSON.parse(fs.readFileSync(path.resolve(process.cwd(), '../plugins/project-management/examples/dashboard-customer-delivery.json'), 'utf8'));

async function downloadDesign(page: Page, destination: string) {
  const [download] = await Promise.all([page.waitForEvent('download'), page.getByTestId('dashboard-design-export').click()]);
  expect(download.suggestedFilename()).toMatch(/\.json$/);
  await download.saveAs(destination);
  return JSON.parse(fs.readFileSync(destination, 'utf8'));
}

test('SC05-INVALID dashboard rejects damaged, unknown-version and oversized imports without replacing the canvas', async ({page}, info) => {
  await page.goto('/');
  await ensureSidebarExpanded(page);
  await navigateToMenuByClick(page, ['元数据管理', '仪表盘管理']);
  await page.getByTestId('toolbar-btn-create').click();
  await expect(page.getByTestId('designer-canvas')).toBeVisible();
  await page.getByTestId('dashboard-design-import-file').setInputFiles({name: 'valid.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(template))});
  await page.getByRole('button', {name: '导入为新草稿', exact: true}).click();
  await expect(page.getByTestId('designer-canvas').locator('[data-widget-id]')).toHaveCount(6);
  const before = await downloadDesign(page, info.outputPath('dashboard-before.json'));
  const writes: string[] = [];
  page.on('request', request => {
    if (new URL(request.url()).pathname.startsWith('/api/dashboards') && ['POST', 'PUT', 'DELETE'].includes(request.method())) writes.push(request.url());
  });
  for (const [label, text, error] of [
    ['damaged', '{broken', '设计文件无效。请选择 AuraBoot 导出的仪表盘 JSON，并检查组件和布局。'],
    ['version', JSON.stringify({...template, version: 999}), '不支持此设计文件版本，请使用版本 1。'],
    ['oversized', ' '.repeat(2 * 1024 * 1024 + 1), '设计文件不能超过 2 MB。'],
  ]) {
    await page.getByTestId('dashboard-design-import-file').setInputFiles({name: `${label}.json`, mimeType: 'application/json', buffer: Buffer.from(text)});
    await expect(page.getByText(error, {exact: true})).toBeVisible();
    await expect(page.getByRole('button', {name: '导入为新草稿', exact: true})).toHaveCount(0);
    expect(await downloadDesign(page, info.outputPath(`dashboard-after-${label}.json`))).toEqual(before);
    await page.screenshot({path: info.outputPath(`SC05-dashboard-rejected-${label}.png`), fullPage: true});
  }
  expect(writes).toEqual([]);
  fs.writeFileSync(info.outputPath('dashboard-rejection-receipt.json'), JSON.stringify({driver: 'browser', rejected: ['damaged', 'version', 'oversized'], canvasWidgets: 6, exportedDesignUnchanged: true, mutations: writes}, null, 2));
});

test('SC05-INVALID automation rejects invalid design files without adding or changing rules', async ({page}, info) => {
  await page.goto('/');
  await ensureSidebarExpanded(page);
  await navigateToMenuByClick(page, ['系统管理', '自动化']);
  await expect(page.getByTestId('input-import-automation')).toBeAttached();
  const initial = await page.request.get('/api/automations?page=1&size=500');
  expect(initial.ok()).toBe(true);
  const before = await initial.json();
  expect(String(before.code)).toBe('0');
  const writes: string[] = [];
  page.on('request', request => {
    if (new URL(request.url()).pathname.startsWith('/api/automations') && ['POST', 'PUT', 'DELETE'].includes(request.method())) writes.push(request.url());
  });
  const valid = {name: 'Rejected import', enabled: true, flowConfig: {nodes: [], edges: []}};
  for (const [label, text, error] of [
    ['damaged', '{broken', '自动化设计文件无效，请选择 AuraBoot 导出的 JSON 并检查节点和连线。'],
    ['version', JSON.stringify({...valid, version: 999}), '不支持此自动化设计文件版本，请使用版本 1。'],
    ['oversized', ' '.repeat(2 * 1024 * 1024 + 1), '自动化设计文件不能超过 2 MB。'],
    ['invalid-graph', JSON.stringify({...valid, flowConfig: {nodes: 'invalid', edges: []}}), '自动化设计文件无效，请选择 AuraBoot 导出的 JSON 并检查节点和连线。'],
  ]) {
    await page.getByTestId('input-import-automation').setInputFiles({name: `${label}.json`, mimeType: 'application/json', buffer: Buffer.from(text)});
    await expect(page.getByText(error, {exact: true}).last()).toBeVisible();
    await expect(page.getByRole('link', {name: valid.name, exact: true})).toHaveCount(0);
    await page.screenshot({path: info.outputPath(`SC05-automation-rejected-${label}.png`), fullPage: true});
  }
  const final = await page.request.get('/api/automations?page=1&size=500');
  expect(final.ok()).toBe(true);
  expect((await final.json()).data).toEqual(before.data);
  expect(writes).toEqual([]);
  fs.writeFileSync(info.outputPath('automation-rejection-receipt.json'), JSON.stringify({driver: 'browser', rejected: ['damaged', 'version', 'oversized', 'invalid-graph'], rulesUnchanged: true, mutations: writes}, null, 2));
});
