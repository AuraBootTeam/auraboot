import { test, expect, type Page } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';

const ast = (amount: number) => ({ type: 'compare', operator: 'GT', left: { type: 'path', scope: 'record', path: 'data.amount', dataType: 'integer' }, right: { type: 'literal', value: amount, dataType: 'integer' } });
function manifest(code: string, version: string, amount: number, invalid = false) {
  return { pluginId: code, namespace: code, version, displayName: '客户项目交付应用',
    models: [{ code, displayName: '客户项目', modelType: 'entity' }],
    fields: [{ code: `${code}_name`, displayName: '项目名称', dataType: 'string' }, { code: `${code}_amount`, displayName: '交付金额', dataType: 'decimal' }],
    modelFieldBindings: [{ modelCode: code, fieldCode: `${code}_name`, aliasCode: 'name' }, { modelCode: code, fieldCode: `${code}_amount`, aliasCode: 'amount' }],
    pages: [{ pageKey: `${code}_overview`, name: `项目交付概览 ${version}`, title: { 'zh-CN': `项目交付概览 ${version}` }, kind: 'form', schemaVersion: 4, layout: { type: 'stack' }, blocks: [{ id: 'summary', blockType: 'rich-text', content: `客户项目交付 · ${version}` }] }],
    decisionDefinitions: [{ decisionCode: `${code}_approval`, decisionName: '验收审批分流', enabled: true, scopeType: 'GLOBAL', kind: 'SIMPLE_CONDITION', runtimeAdapter: invalid ? 'INVALID_ADAPTER' : 'AST_EVALUATOR', contentJson: ast(amount), publish: true }],
  };
}
async function api(page: Page, method: string, url: string, body?: unknown) {
  const response = await page.request.fetch(url, { method, ...(body === undefined ? {} : { data: body }) });
  const result = await response.json();
  expect(response.ok(), `${method} ${url}: ${result.message}`).toBeTruthy();
  expect(String(result.code), result.message).toBe('0');
  return result.data;
}
async function configurationPage(page: Page, code: string) {
  const result = await api(page, 'GET', '/api/pages?pageSize=100');
  const matches = result.records.filter((record: { pageKey: string }) => record.pageKey === `${code}_overview`);
  expect(matches).toHaveLength(1);
  return api(page, 'GET', `/api/pages/${matches[0].pid}`);
}
async function selectSpace(page: Page, tenantId: string) {
  await page.reload();
  await page.getByTestId('user-menu').getByRole('button').first().click();
  await Promise.all([
    page.waitForNavigation({ waitUntil: 'domcontentloaded' }),
    page.getByTestId(`tenant-switch-${tenantId}`).click(),
  ]);
  expect(String((await api(page, 'GET', '/api/auth/me')).user.tenantId)).toBe(tenantId);
  await expect(page.getByTestId('user-menu').getByRole('button').first()).toBeVisible();
}
async function openImports(page: Page) {
  await page.getByRole('link', { name: '插件管理', exact: true }).click();
  await expect(page.getByTestId('plugins-page')).toHaveAttribute('data-hydrated', 'true');
  await page.getByRole('tab', { name: /已安装/ }).click();
  await expect(page.getByRole('tab', { name: /已安装/ })).toHaveAttribute('aria-selected', 'true');
  await expect(page.getByRole('button', { name: /上传插件|上传安装/ })).toBeVisible();
}
async function upload(page: Page, value: ReturnType<typeof manifest>, failure = false, previewShot?: string) {
  await page.getByRole('button', { name: /上传插件|上传安装/ }).click();
  await page.locator('#plugin-file').setInputFiles({ name: `delivery-${value.version}.json`, mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(value)) });
  await expect(page.getByText('安全更新', { exact: true })).toBeVisible();
  if (previewShot) await page.screenshot({ path: previewShot, fullPage: true });
  const execution = page.waitForResponse(r => /\/api\/plugins\/import\/[^/]+\/execute$/.test(r.url()) && r.request().method() === 'POST');
  await page.getByRole('button', { name: /确认安装|确认升级/ }).click();
  const response = await execution;
  expect(response.request().postDataJSON().conflictStrategy).toBe('overwrite_safe');
  await expect(page.getByRole('heading', { name: failure ? '导入未完成' : '导入已完成', exact: true })).toBeVisible();
  return response.json();
}

test('customer A preserves customization, B upgrades, and failed import leaves business usable', async ({ page, browser }, testInfo) => {
  const account = process.env.PW_UPGRADE_ACCOUNT_FILE ? JSON.parse(fs.readFileSync(process.env.PW_UPGRADE_ACCOUNT_FILE, 'utf8')) : DEFAULT_TEST_ACCOUNT;
  const shots = process.env.PW_UPGRADE_SHOTS || testInfo.outputPath('review');
  fs.mkdirSync(shots, { recursive: true });
  const code = `upgrade_${Date.now()}`;
  await page.goto('/login');
  await expect(page.getByTestId('login-page-root')).toHaveAttribute('data-hydrated', 'true');
  await page.getByRole('textbox', { name: '用户名或邮箱' }).fill(account.email);
  await page.getByLabel('密码', { exact: true }).fill(account.password);
  await page.getByRole('button', { name: '立即登录', exact: true }).click();
  await expect(page).not.toHaveURL(/\/login(?:\?|$)/);
  // Fixture preparation uses normal tenant APIs; all plugin imports below use the UI.
  const a = await api(page, 'POST', '/api/tenant-selection/process', { action: 'create', clientRequestId: `${code}-a`, tenantName: `华辰制造 · ${code}`, displayName: '客户 A · 华辰制造' });
  const b = await api(page, 'POST', '/api/tenant-selection/process', { action: 'create', clientRequestId: `${code}-b`, tenantName: `启明设备 · ${code}`, displayName: '客户 B · 启明设备' });
  await selectSpace(page, String(a.tenantId));
  await openImports(page);
  await upload(page, manifest(code, '1.0.0', 100000));
  await page.screenshot({ path: path.join(shots, '01-installed.png'), fullPage: true });
  // Arrange an actual customer customization through the existing configuration APIs.
  const original = await configurationPage(page, code);
  await api(page, 'PUT', `/api/pages/${original.pid}`, { name: '华辰制造专属交付工作台', title: { 'zh-CN': '华辰制造专属交付工作台' }, blocks: [{ id: 'summary', blockType: 'rich-text', content: '<h2>华辰制造 · 客户交付要求</h2><p>本客户采用专属验收审批配置，交付金额超过 200,000 元时进入加强审核。</p><p>共享应用安全更新时，保留客户维护的页面与审批规则。</p>' }], rowVersion: original.rowVersion });
  const draft = await api(page, 'POST', `/api/decision/definitions/${code}_approval/versions`, { kind: 'SIMPLE_CONDITION', runtimeAdapter: 'AST_EVALUATOR', contentJson: ast(200000) });
  const validation = await api(page, 'POST', `/api/decision/versions/${draft.pid}/validate`);
  expect(validation.valid).toBeTruthy();
  await api(page, 'POST', `/api/decision/versions/${draft.pid}/publish`, { impactAcknowledged: true });
  // Publishing this fixture is explicit; plugin upload itself leaves pages as drafts.
  await api(page, 'POST', `/api/pages/${original.pid}/publish`);
  await page.goto(`/p/c/${code}_overview`);
  await expect(page.getByText('华辰制造 · 客户交付要求', { exact: true })).toBeVisible();
  await page.screenshot({ path: path.join(shots, '02-a-custom.png'), fullPage: true });
  await openImports(page);
  await upload(page, manifest(code, '1.1.0', 150000), false, path.join(shots, '03-safe-preview.png'));
  await expect(page.getByText(/业务决策：已有客户修改/)).toBeVisible();
  expect((await configurationPage(page, code)).name).toBe('华辰制造专属交付工作台');
  await page.screenshot({ path: path.join(shots, '04-upgrade-result.png'), fullPage: true });
  await page.getByRole('tab', { name: /导入历史/ }).click();
  await expect(page).toHaveURL(/tab=history/);
  await page.reload();
  const preserved = page.locator('tr').filter({ hasText: code }).filter({ hasText: '1.1.0' });
  await preserved.getByText('查看保留原因与技术诊断').click();
  await expect(preserved).toContainText('customer changes');
  await page.screenshot({ path: path.join(shots, '04-history-preserved.png'), fullPage: true });

  await selectSpace(page, String(b.tenantId));
  await openImports(page);
  await upload(page, manifest(code, '1.0.0', 100000));
  await upload(page, manifest(code, '1.1.0', 150000));
  expect((await configurationPage(page, code)).name).toBe('项目交付概览 1.1.0');
  await api(page, 'POST', '/api/templates/crud/generate', { modelCode: code, config: { menuName: '客户项目' } });
  const record = await api(page, 'POST', `/api/dynamic/${code}/create`, { [`${code}_name`]: '启明设备 · 产线交付', [`${code}_amount`]: 170000 });
  let evaluated = await api(page, 'POST', '/api/decision/evaluate', { decisionCode: `${code}_approval`, binding: 'LATEST', context: { record: { data: { amount: 170000 } } } });
  expect(evaluated.matched).toBe(true);
  await upload(page, manifest(code, '1.2.0', 180000, true), true);
  await page.screenshot({ path: path.join(shots, '06-failed-upgrade.png'), fullPage: true });
  expect((await configurationPage(page, code)).name).toBe('项目交付概览 1.1.0');
  evaluated = await api(page, 'POST', '/api/decision/evaluate', { decisionCode: `${code}_approval`, binding: 'LATEST', context: { record: { data: { amount: 170000 } } } });
  expect(evaluated.matched).toBe(true);
  // A normal business edit remains available while the failed package is unresolved.
  await page.reload();
  await page.locator(`nav a[href="/p/${code}"]`).click();
  await expect(page.getByText('启明设备 · 产线交付', { exact: true }).first()).toBeVisible();
  await page.goto(`/p/${code}/edit/${record.pid}`);
  const nameInput = page.getByTestId(`form-field-${code}_name`).locator('input');
  await expect(nameInput).toBeVisible();
  await nameInput.fill('启明设备 · 产线交付（继续处理）');
  const saved = page.waitForResponse(response => response.request().method() === 'PUT' && response.url().includes(`/api/dynamic/`) && response.url().includes(record.pid));
  await page.getByTestId('form-btn-submit').click();
  const savedResponse = await saved;
  expect(savedResponse.ok()).toBeTruthy();
  expect(String((await savedResponse.json()).code)).toBe('0');
  await page.goto(`/p/${code}/edit/${record.pid}`);
  await expect(nameInput).toHaveValue('启明设备 · 产线交付（继续处理）');
  await page.screenshot({ path: path.join(shots, '07-business-continuity.png'), fullPage: true });
  await openImports(page);
  await upload(page, manifest(code, '1.2.0', 180000));
  evaluated = await api(page, 'POST', '/api/decision/evaluate', { decisionCode: `${code}_approval`, binding: 'LATEST', context: { record: { data: { amount: 170000 } } } });
  expect(evaluated.matched).toBe(false);
  await page.screenshot({ path: path.join(shots, '09-corrected-upgrade.png'), fullPage: true });
  const viewerEmail = `${code}@viewer.auraboot.local`;
  const viewerPassword = `Viewer-${randomUUID()}-Aa2!`;
  await api(page, 'POST', '/api/admin/users', { email: viewerEmail, displayName: '交付观察员', initialPassword: viewerPassword, roleAssignmentMode: 'NONE', sendInviteEmail: false });
  const viewerContext = await browser.newContext({ baseURL: testInfo.project.use.baseURL, locale: 'zh-CN', viewport: { width: 1440, height: 1000 }, extraHTTPHeaders: { Referer: `${testInfo.project.use.baseURL}/` } });
  const viewer = await viewerContext.newPage();
  await viewer.goto('/login');
  await expect(viewer.getByTestId('login-page-root')).toHaveAttribute('data-hydrated', 'true');
  await viewer.getByRole('textbox', { name: '用户名或邮箱' }).fill(viewerEmail);
  await viewer.getByLabel('密码', { exact: true }).fill(viewerPassword);
  await viewer.getByRole('button', { name: '立即登录', exact: true }).click();
  await expect(viewer).not.toHaveURL(/\/login(?:\?|$)/);
  expect(String((await api(viewer, 'GET', '/api/auth/me')).user.tenantId)).toBe(String(b.tenantId));
  await viewer.goto('/plugins?tab=installed');
  await expect(viewer.getByRole('button', { name: /上传插件|上传安装/ })).toHaveCount(0);
  const denied = await viewer.request.post('/api/plugins/import/execute-direct?conflictStrategy=OVERWRITE_SAFE', { data: manifest(`${code}_denied`, '1.0.0', 100000) });
  expect(denied.status()).toBe(403);
  await viewer.screenshot({ path: path.join(shots, '08-permission-denied.png'), fullPage: true });
  await viewerContext.close();
  fs.writeFileSync(path.join(shots, 'case-identity.json'), JSON.stringify({ code, tenantA: String(a.tenantId), tenantB: String(b.tenantId), entry: '/plugins', customizationDriver: 'real configuration API fixture', importDriver: 'browser upload/preview/execute', finalVersion: '1.2.0' }, null, 2));
});
