import { expect, test } from '@playwright/test';
import { createCookieSessionStorage } from 'react-router';
import path from 'node:path';
import { mkdir, readFile } from 'node:fs/promises';
import { BACKEND_URL, BASE_URL as WEB_BASE_URL } from '../../helpers/environments';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';

const EVIDENCE_DIR = path.join(process.env.AURA_EVIDENCE_ROOT || '/tmp', 'ui-golden-20260914');
const sessionStorage = createCookieSessionStorage({
  cookie: {
    name: '__session',
    httpOnly: true,
    path: '/',
    sameSite: 'lax',
    secrets: [process.env.SESSION_SECRET || 'dev-only-secret-do-not-use-in-production'],
    secure: false,
  },
});

test.describe('Dynamic resource publication journey', () => {
  test.setTimeout(180_000);
  test.describe.configure({ retries: 0 });
  test.use({ storageState: { cookies: [], origins: [] }, locale: 'zh-CN', viewport: { width: 1440, height: 1000 } });

  test('publishes a real model contract through UI, retains conflicts, downloads and withdraws', async ({ page }) => {
    await authenticate(page);
    const suffix = String(Date.now());
    const modelCode = `dop_ui_${suffix}`;
    const modelName = `接入验证零件 ${suffix}`;
    const resourceCode = `ui.parts.${suffix}`;
    // Model authoring is fixture setup. Every publication mutation below is driven by the real UI.
    const createdResponse = await page.request.post(`${WEB_BASE_URL}/api/meta/models`, { data: {
      code: modelCode, displayName: modelName, tableName: modelCode, modelType: 'entity', sourceType: 'physical',
      capabilities: { list: true, detail: true, create: true, update: true, delete: true, filter: true, sort: true, paginate: true },
      fields: [
        { code: 'dop_ui_name', columnName: 'dop_ui_name', displayName: '零件名称', dataType: 'string', required: true, editable: true, filterable: true, sortable: true },
        { code: 'dop_ui_details', columnName: 'dop_ui_details', displayName: '联系信息', dataType: 'json', required: false, editable: true },
      ],
    } });
    expect(createdResponse.status()).toBe(200);
    const created = await createdResponse.json(); expect(String(created.code)).toBe('0');
    const modelPublish = await page.request.post(`${WEB_BASE_URL}/api/meta/models/${created.data.pid}/publish`, { data: { impactAcknowledged: true } });
    expect(modelPublish.status()).toBe(200); expect(String((await modelPublish.json()).code)).toBe('0');
    const screenshotRoot = path.join(process.env.AURA_EVIDENCE_ROOT || '/tmp', 'ui-publication-r1', 'screenshots');
    await mkdir(screenshotRoot, { recursive: true });
    const snapshot = async (id: string) => { await page.screenshot({ path: path.join(screenshotRoot, `${id}.png`), fullPage: true }); };
    await page.goto('/'); await openAccountMenu(page); await page.getByTestId('open-platform-link').click();
    await expect(page.getByTestId('open-platform-page')).toBeVisible();
    await expect(page.getByTestId('open-platform-publications-link')).toBeVisible();
    await snapshot('publication-entry');
    await page.getByTestId('open-platform-publications-link').click();
    await expect(page.getByTestId('publication-editor')).toBeVisible();
    await expect(page.getByRole('heading', { name: '尚未公开业务模型' })).toBeVisible();
    await snapshot('publication-empty');
    await page.getByRole('button', { name: '新建公共资源', exact: true }).click();
    await page.getByLabel('公共资源地址', { exact: true }).fill(resourceCode);
    await page.getByLabel('查找业务模型', { exact: true }).fill(modelName);
    await page.getByRole('button', { name: '查找', exact: true }).click();
    await expect(page.getByLabel('业务模型', { exact: true }).getByRole('option', { name: modelName, exact: true })).toHaveCount(1);
    const metadataResponse = page.waitForResponse(response => response.url().includes(`/publication-sources/${modelCode}`) && response.status() === 200);
    await page.getByLabel('业务模型', { exact: true }).selectOption({ label: modelName });
    const metadata = await (await metadataResponse).json();
    expect(String(metadata.code)).toBe('0');
    expect(metadata.data.operations).toEqual(expect.arrayContaining(['list', 'get', 'create', 'update', 'delete']));
    expect(metadata.data.fields.some((field: { code: string }) => ['id', 'tenant_id', 'row_version'].includes(field.code))).toBe(false);
    await expect(page.getByLabel('列表查询', { exact: true })).toBeEnabled();
    await snapshot('publication-source');
    for (const operation of ['列表查询', '详情读取', '创建记录', '修改记录', '删除记录']) await page.getByLabel(operation, { exact: true }).check();
    const scopes = page.getByLabel('所需权限', { exact: true });
    for (const [index, operation] of ['list', 'get', 'create', 'update', 'delete'].entries()) await scopes.nth(index).fill(`dop.ui:${operation}`);
    await page.getByLabel('机器数据范围', { exact: true }).selectOption('tenant_wide');
    const nameField = page.getByTestId('publication-field-dop_ui_name');
    await nameField.getByLabel('零件名称', { exact: true }).check();
    await nameField.getByLabel('对外字段名称', { exact: true }).fill('name');
    await nameField.getByLabel('EQ', { exact: true }).check();
    await nameField.getByLabel('允许排序', { exact: true }).check();
    const detailsField = page.getByTestId('publication-field-dop_ui_details');
    await detailsField.getByLabel('联系信息', { exact: true }).check();
    await detailsField.getByLabel('对外字段名称', { exact: true }).fill('details');
    await detailsField.getByLabel('对外类型', { exact: true }).selectOption('object');
    await detailsField.getByLabel('原对象属性', { exact: true }).fill('contact_name');
    await detailsField.getByLabel('对外属性名称', { exact: true }).fill('contactName');
    await page.getByLabel('创建通知', { exact: true }).check();
    await snapshot('publication-fields');
    await page.getByTestId('publication-editor').locator('section').filter({ has: detailsField }).screenshot({ path: path.join(screenshotRoot, 'publication-fields-region.png') });
    const saveButton = page.getByRole('button', { name: '保存并校验草稿', exact: true });
    await saveButton.hover();
    const saveContrast = await saveButton.evaluate(element => {
      const luminance = (color: string) => (color.match(/[\d.]+/g) ?? []).slice(0, 3).map(Number)
        .map(channel => channel / 255).map(channel => channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4)
        .reduce((sum, channel, index) => sum + channel * [0.2126, 0.7152, 0.0722][index], 0);
      const style = getComputedStyle(element); const foreground = luminance(style.color); const background = luminance(style.backgroundColor);
      return (Math.max(foreground, background) + 0.05) / (Math.min(foreground, background) + 0.05);
    });
    expect(saveContrast).toBeGreaterThanOrEqual(4.5);
    const savedResponse = page.waitForResponse(response => response.url().endsWith(`/publications/${resourceCode}/draft`) && response.request().method() === 'PUT');
    await page.getByRole('button', { name: '保存并校验草稿', exact: true }).click();
    const savedHttp = await savedResponse; expect(savedHttp.status()).toBe(200);
    const saved = await savedHttp.json(); expect(String(saved.code)).toBe('0');
    expect(saved.data.draft.fields.name).toMatchObject({ source: 'dop_ui_name', writable: true, required: true, sortable: true, operators: ['EQ'] });
    expect(saved.data.draft.fields.details).toMatchObject({ source: 'dop_ui_details', type: 'object', properties: { contactName: { source: 'contact_name', type: 'string' } } });
    expect(saved.data.draftRevision).toBe(1); expect(saved.data.activeVersion).toBeNull();
    await expect(page.getByTestId('publication-editor').getByRole('status')).toContainText('草稿已保存并通过来源校验');
    await expect(page.getByTestId('publication-editor').getByRole('status')).toBeInViewport();
    await snapshot('publication-draft');

    const otherEditor = await page.context().newPage();
    await otherEditor.goto('/p/c/open_platform_publications');
    await otherEditor.getByRole('button', { name: '编辑合同', exact: true }).click();
    await expect(otherEditor.getByLabel('查询超时（毫秒）', { exact: true })).toBeEnabled();
    await otherEditor.getByLabel('查询超时（毫秒）', { exact: true }).fill('6000');
    await otherEditor.getByRole('button', { name: '保存并校验草稿', exact: true }).click();
    await expect(otherEditor.getByTestId('publication-editor').getByRole('status')).toContainText('草稿已保存并通过来源校验');
    await otherEditor.close();
    await page.getByLabel('查询超时（毫秒）', { exact: true }).fill('7000');
    const conflictResponse = page.waitForResponse(response => response.url().endsWith(`/publications/${resourceCode}/draft`) && response.status() === 409);
    await page.getByRole('button', { name: '保存并校验草稿', exact: true }).click(); await conflictResponse;
    await expect(page.getByTestId('publication-editor').getByRole('alert')).toContainText('你的修改已保留');
    await expect(page.getByLabel('查询超时（毫秒）', { exact: true })).toHaveValue('7000');
    await expect(page.getByTestId('publication-editor').getByRole('alert')).toBeInViewport();
    await snapshot('publication-conflict');
    await page.getByRole('button', { name: '关闭编辑', exact: true }).click();
    await page.getByRole('group', { name: '操作确认', exact: true }).getByRole('button', { name: '确认', exact: true }).click();
    await expect(page.getByRole('button', { name: '编辑合同', exact: true })).toBeEnabled();
    await page.getByRole('button', { name: '编辑合同', exact: true }).click();
    await expect(page.getByLabel('查询超时（毫秒）', { exact: true })).toHaveValue('6000');
    await page.getByRole('button', { name: '发布合同', exact: true }).click();
    await page.getByRole('group', { name: '操作确认', exact: true }).getByRole('button', { name: '确认', exact: true }).click();
    await expect(page.getByTestId('publication-editor').getByRole('status')).toContainText('已发布');
    await expect(page.getByTestId('publication-editor').getByRole('status')).toBeInViewport();
    await snapshot('publication-publish');
    for (const [button, extension] of [['下载 OpenAPI', '.openapi.json'], ['下载类型声明', '.d.ts']] as const) {
      const downloaded = page.waitForEvent('download'); await page.getByRole('button', { name: button, exact: true }).click();
      const artifact = await downloaded; expect(artifact.suggestedFilename()).toBe(resourceCode + extension);
      const location = await artifact.path(); expect(location).toBeTruthy();
      const content = await readFile(location!, 'utf8'); expect(content.length).toBeGreaterThan(0);
      expect(content).not.toContain(modelCode); expect(content).not.toContain('dop_ui_name'); expect(content).not.toContain('contact_name'); expect(content).toContain('contactName');
      if (extension === '.openapi.json') {
        const document = JSON.parse(content); expect(document.openapi).toBe('3.1.0'); expect(document['x-schema-version']).toBe(1);
        expect(document.paths[`/api/open/v1/resources/${resourceCode}`].post).toBeDefined();
      } else expect(content).toContain('export type Resource');
      await artifact.saveAs(path.join(screenshotRoot, resourceCode + extension));
      await snapshot(extension === '.openapi.json' ? 'publication-openapi' : 'publication-types');
    }
    await page.getByRole('button', { name: '停用资源', exact: true }).click();
    await expect(page.getByRole('group', { name: '操作确认', exact: true })).toBeInViewport();
    await snapshot('publication-withdrawdialog');
    await page.getByRole('group', { name: '操作确认', exact: true }).screenshot({ path: path.join(screenshotRoot, 'publication-withdrawdialog-region.png') });
    await page.getByRole('group', { name: '操作确认', exact: true }).getByRole('button', { name: '确认', exact: true }).click();
    await expect(page.getByTestId('publication-editor').getByRole('status')).toContainText('已停用');
    await expect(page.getByRole('button', { name: '下载 OpenAPI', exact: true })).toBeDisabled();
    await expect(page.getByRole('button', { name: '下载类型声明', exact: true })).toBeDisabled();
    const finalState = await page.request.get(`${WEB_BASE_URL}/api/open-platform/publications`);
    expect(finalState.status()).toBe(200);
    const final = await finalState.json(); expect(String(final.code)).toBe('0');
    expect(final.data.find((view: { code: string }) => view.code === resourceCode)).toMatchObject({ status: 'withdrawn', activeVersion: 1, draftRevision: 2 });
    await expect(page.getByTestId('publication-editor').getByRole('status')).toBeInViewport();
    await snapshot('publication-withdraw');
  });
});

test.describe('Dynamic query publication journey', () => {
  test.setTimeout(180_000);
  test.use({ storageState: { cookies: [], origins: [] }, locale: 'zh-CN', viewport: { width: 1440, height: 1000 } });
  test('pins a real NamedQuery, saves read-only projections, downloads and withdraws through UI', async ({ page }) => {
    await authenticate(page);
    const suffix = String(Date.now()); const modelCode = `dop_q_${suffix}`; const modelName = `查询验证零件 ${suffix}`;
    const queryCode = `dop_named_${suffix}`; const queryName = `区域零件查询 ${suffix}`;
    const sourceCode = `query.parts.${suffix}`; const publicCode = `parts.by-zone.${suffix}`;
    const modelResponse = await page.request.post(`${WEB_BASE_URL}/api/meta/models`, { data: {
      code: modelCode, displayName: modelName, tableName: modelCode, modelType: 'entity', sourceType: 'physical',
      capabilities: { list: true, detail: true, create: true, update: true, delete: true, filter: true, sort: true, paginate: true },
      fields: [
        { code: 'dop_q_name', columnName: 'dop_q_name', displayName: '零件名称', dataType: 'string', required: true, editable: true },
        { code: 'dop_q_zone', columnName: 'dop_q_zone', displayName: '所属区域', dataType: 'string', required: false, editable: true, filterable: true },
        { code: 'dop_q_details', columnName: 'dop_q_details', displayName: '联系信息', dataType: 'json', required: false, editable: true },
      ],
    } });
    expect(modelResponse.status()).toBe(200); const model = await modelResponse.json(); expect(String(model.code)).toBe('0');
    expect(model.data.tableName).toMatch(/^[a-z][a-z0-9_]+$/);
    const modelPublish = await page.request.post(`${WEB_BASE_URL}/api/meta/models/${model.data.pid}/publish`, { data: { impactAcknowledged: true } });
    expect(modelPublish.status()).toBe(200); expect(String((await modelPublish.json()).code)).toBe('0');
    const field = (source: string, type: string, properties = {}) => ({ source, type, readable: true, writable: false, required: false,
      nullable: true, operators: [], sortable: false, referenceResource: null, properties, items: null });
    // Prerequisite source contracts are fixtures. Query-publication mutations below are exclusively UI-driven.
    const resource = { code: sourceCode, modelCode, schemaVersion: 1, operations: ['list', 'get'], scopes: { list: 'dop.q:read', get: 'dop.q:read' }, dataPolicy: 'tenant_wide',
      fields: { pid: { ...field('pid', 'pid'), nullable: false }, name: field('dop_q_name', 'string'), zone: { ...field('dop_q_zone', 'string'), operators: ['EQ'] },
        details: field('dop_q_details', 'object', { contactName: field('contact_name', 'string') }) }, maxPageSize: 50, maxConditions: 8, timeoutMs: 5000, events: [] };
    const savedSource = await page.request.put(`${WEB_BASE_URL}/api/open-platform/publications/${sourceCode}/draft`, { data: { expectedRevision: 0, definition: resource } });
    expect(savedSource.status()).toBe(200); expect(String((await savedSource.json()).code)).toBe('0');
    const publishedSource = await page.request.post(`${WEB_BASE_URL}/api/open-platform/publications/${sourceCode}/publish`, { data: { expectedRevision: 1 } });
    expect(publishedSource.status()).toBe(200); expect(String((await publishedSource.json()).code)).toBe('0');
    const queryResponse = await page.request.post(`${WEB_BASE_URL}/api/meta/named-queries`, { data: {
      code: queryCode, title: queryName, resourceCode: modelCode, actionCode: 'read', fromSql: `${model.data.tableName} a WHERE a.dop_q_zone = #{params.zone}`,
      defaultOrder: [{ field: 'pid', direction: 'asc' }],
      fields: [
        { fieldCode: 'pid', columnExpr: 'a.pid', dataType: 'string', displayName: '记录标识', sortable: true, searchable: false },
        { fieldCode: 'partName', columnExpr: 'a.dop_q_name', dataType: 'string', displayName: '零件名称', sortable: false, searchable: false },
        { fieldCode: 'partDetails', columnExpr: 'a.dop_q_details', dataType: 'json', displayName: '联系信息', sortable: false, searchable: false },
      ],
    } });
    expect(queryResponse.status()).toBe(200); const query = await queryResponse.json(); expect(String(query.code)).toBe('0');
    for (const status of ['testing', 'published']) {
      const response = await page.request.put(`${WEB_BASE_URL}/api/meta/named-queries/${query.data.pid}/status`, { data: { status } });
      expect(response.status()).toBe(200); expect(String((await response.json()).code)).toBe('0');
    }
    const screenshotRoot = path.join(process.env.AURA_EVIDENCE_ROOT || '/tmp', 'ui-publication-r1', 'screenshots');
    await mkdir(screenshotRoot, { recursive: true });
    const snapshot = async (id: string) => { await page.screenshot({ path: path.join(screenshotRoot, `${id}.png`), fullPage: true }); };
    await page.goto('/p/c/open_platform_publications'); await expect(page.getByTestId('publication-editor')).toBeVisible();
    await page.getByTestId('publication-editor').getByRole('link', { name: '公共查询发布', exact: true }).click();
    const editor = page.getByTestId('query-publication-editor'); await expect(editor).toBeVisible();
    await expect(editor).toContainText('尚未公开命名查询'); await expect(editor).not.toContainText(/Page Unavailable|Page not found/);
    await snapshot('query-entry');
    await page.getByRole('button', { name: '新建公共查询', exact: true }).click();
    await page.getByLabel('公共查询地址', { exact: true }).fill(publicCode);
    await page.getByLabel('查找命名查询', { exact: true }).fill(queryName); await page.getByRole('button', { name: '查找', exact: true }).click();
    await expect(page.getByLabel('命名查询来源', { exact: true }).getByRole('option', { name: queryName, exact: true })).toHaveCount(1);
    const sourceResponse = page.waitForResponse(response => response.url().endsWith(`/query-sources/${queryCode}`) && response.status() === 200);
    await page.getByLabel('命名查询来源', { exact: true }).selectOption({ label: queryName });
    const source = await (await sourceResponse).json(); expect(String(source.code)).toBe('0'); expect(source.data.parameters).toEqual(['zone']);
    expect(source.data.fingerprint).toMatch(/^[a-f0-9]{64}$/); expect(source.data.sources[0].resources).toContainEqual({ code: sourceCode, schemaVersion: 1 });
    expect(JSON.stringify(source)).not.toContain('fromSql'); expect(JSON.stringify(source)).not.toContain('columnExpr');
    await snapshot('query-source');
    await page.getByTestId(`query-source-${modelCode}`).getByLabel(modelName, { exact: true }).selectOption(sourceCode);
    await page.getByLabel('查询执行权限', { exact: true }).fill('dop.q:execute'); await snapshot('query-bindings');
    const parameter = page.getByTestId('query-parameter-zone');
    await expect(parameter.getByLabel('参数类型', { exact: true })).toHaveValue('string'); await expect(parameter.getByLabel('必填参数', { exact: true })).toBeChecked();
    await parameter.scrollIntoViewIfNeeded(); await snapshot('query-parameters');
    for (const [sourceField, label, alias, type] of [['pid', '记录标识', 'pid', 'pid'], ['partName', '零件名称', 'name', 'string'], ['partDetails', '联系信息', 'details', 'object']]) {
      const output = page.getByTestId(`query-output-${sourceField}`); await output.getByLabel(label, { exact: true }).check();
      await output.getByLabel('对外输出名称', { exact: true }).fill(alias); await output.getByLabel('输出类型', { exact: true }).selectOption(type);
      if (type === 'object') { await output.getByLabel('原对象属性', { exact: true }).fill('contact_name'); await output.getByLabel('对外属性名称', { exact: true }).fill('contactName'); }
    }
    await expect(editor.getByLabel('可写', { exact: true })).toHaveCount(0);
    const savedResponse = page.waitForResponse(response => response.url().endsWith(`/query-publications/${publicCode}/draft`) && response.request().method() === 'PUT');
    await page.getByRole('button', { name: '保存并校验查询草稿', exact: true }).click();
    const savedHttp = await savedResponse; expect(savedHttp.status()).toBe(200); const saved = await savedHttp.json(); expect(String(saved.code)).toBe('0');
    expect(saved.data.draft).toMatchObject({ sourceFingerprint: source.data.fingerprint, sourceResources: { [modelCode]: sourceCode }, parameters: { zone: { type: 'string', required: true, nullable: false } } });
    expect(saved.data.draft.fields.details).toMatchObject({ readable: true, writable: false, properties: { contactName: { source: 'contact_name', readable: true, writable: false } } });
    await expect(editor.getByRole('status')).toContainText('查询草稿已保存并通过来源校验'); await snapshot('query-draft');
    await page.getByRole('button', { name: '发布查询', exact: true }).click();
    await page.getByRole('group', { name: '查询操作确认', exact: true }).getByRole('button', { name: '确认', exact: true }).click();
    await expect(editor.getByRole('status')).toContainText('已发布查询'); await snapshot('query-publish');
    for (const [button, extension] of [['下载查询 OpenAPI', '.openapi.json'], ['下载查询类型声明', '.d.ts']] as const) {
      const downloaded = page.waitForEvent('download'); await page.getByRole('button', { name: button, exact: true }).click();
      const artifact = await downloaded; expect(artifact.suggestedFilename()).toBe(publicCode + extension); const location = await artifact.path(); expect(location).toBeTruthy();
      const content = await readFile(location!, 'utf8'); expect(content).toContain('contactName'); expect(content).not.toContain(modelCode); expect(content).not.toContain(queryCode); expect(content).not.toContain('contact_name'); expect(content).not.toContain('dop_q_name');
      if (extension === '.openapi.json') { const document = JSON.parse(content); expect(document.openapi).toBe('3.1.0'); expect(document['x-schema-version']).toBe(1); expect(document.paths[`/api/open/v1/queries/${publicCode}:execute`].post).toBeDefined(); }
      else { expect(content).toContain('export type QueryParameters'); expect(content).toContain('export type QueryItem'); }
      await artifact.saveAs(path.join(screenshotRoot, publicCode + extension)); await snapshot(extension === '.openapi.json' ? 'query-openapi' : 'query-types');
    }
    await page.getByRole('button', { name: '停用查询', exact: true }).click(); const confirmation = page.getByRole('group', { name: '查询操作确认', exact: true });
    await expect(confirmation).toBeInViewport(); await snapshot('query-withdrawdialog'); await confirmation.getByRole('button', { name: '确认', exact: true }).click();
    await expect(editor.getByRole('status')).toContainText('查询已停用'); await expect(page.getByRole('button', { name: '下载查询 OpenAPI', exact: true })).toBeDisabled();
    const persisted = await page.request.get(`${WEB_BASE_URL}/api/open-platform/query-publications`); expect(persisted.status()).toBe(200);
    const publications = await persisted.json(); expect(String(publications.code)).toBe('0');
    expect(publications.data.find((entry: { code: string }) => entry.code === publicCode)).toMatchObject({ status: 'withdrawn', activeVersion: 1, draftRevision: 1 });
    await snapshot('query-withdraw');
  });
});

test.describe('Dynamic installation authority journey', () => {
  test.setTimeout(180_000);
  test.describe.configure({ retries: 0 });
  test.use({ storageState: { cookies: [], origins: [] }, locale: 'zh-CN', viewport: { width: 1440, height: 1000 } });

  test('persists resource and query grants through installation UI and enforces them on real machine calls', async ({ page }) => {
    await authenticate(page);
    const suffix = String(Date.now()); const modelCode = `dop_grant_${suffix}`;
    const resourceCode = `granted.parts.${suffix}`; const queryCode = `granted.query.${suffix}`; const namedCode = `dop_grant_query_${suffix}`;
    const operations = ['list', 'get', 'create', 'update', 'delete']; const scopes = operations.map(operation => `dop.grant:${operation}`);
    const human = async (method: string, url: string, data?: unknown) => { const response = await page.request.fetch(`${WEB_BASE_URL}${url}`, { method, data });
      expect(response.status(), `${method} ${url}`).toBe(200); const body = await response.json(); expect(String(body.code)).toBe('0'); return body.data; };
    const model = await human('POST', '/api/meta/models', { code: modelCode, tableName: modelCode, displayName: `授权验证零件 ${suffix}`, modelType: 'entity', sourceType: 'physical',
      capabilities: { list: true, detail: true, create: true, update: true, delete: true, filter: true, sort: true, paginate: true }, fields: [
        { code: 'dop_g_name', columnName: 'dop_g_name', displayName: '零件名称', dataType: 'string', required: true, editable: true },
        { code: 'dop_g_zone', columnName: 'dop_g_zone', displayName: '所属区域', dataType: 'string', required: true, editable: true, filterable: true },
        { code: 'dop_g_secret', columnName: 'dop_g_secret', displayName: '内部备注', dataType: 'string', editable: true },
      ] });
    await human('POST', `/api/meta/models/${model.pid}/publish`, { impactAcknowledged: true });
    const recordA = await human('POST', `/api/dynamic/${modelCode}/create`, { dop_g_name: 'Allowed A', dop_g_zone: 'A', dop_g_secret: 'Hidden A' });
    const recordB = await human('POST', `/api/dynamic/${modelCode}/create`, { dop_g_name: 'Forbidden B', dop_g_zone: 'B', dop_g_secret: 'Hidden B' });
    expect(recordA.pid).toEqual(expect.any(String)); expect(recordB.pid).toEqual(expect.any(String));
    const field = (source: string, writable = false, required = false) => ({ source, type: 'string', readable: true, writable, required, nullable: !required,
      operators: [], sortable: false, referenceResource: null, properties: {}, items: null });
    // Model/publication/app/token setup are fixtures; all tested installation policy writes and revocations below use the UI.
    const resource = { code: resourceCode, modelCode, schemaVersion: 1, operations, scopes: Object.fromEntries(operations.map((operation, index) => [operation, scopes[index]])), dataPolicy: 'installation_filter',
      fields: { pid: { ...field('pid'), type: 'pid', nullable: false }, name: field('dop_g_name', true, true), zone: { ...field('dop_g_zone', true, true), operators: ['EQ'] }, secret: field('dop_g_secret') },
      maxPageSize: 50, maxConditions: 8, timeoutMs: 5000, events: [] };
    await human('PUT', `/api/open-platform/publications/${resourceCode}/draft`, { expectedRevision: 0, definition: resource });
    await human('POST', `/api/open-platform/publications/${resourceCode}/publish`, { expectedRevision: 1 });
    const named = await human('POST', '/api/meta/named-queries', { code: namedCode, title: `授权查询 ${suffix}`, resourceCode: modelCode, actionCode: 'read',
      fromSql: `${model.tableName} a WHERE a.dop_g_zone = #{params.zone}`, defaultOrder: [{ field: 'pid', direction: 'asc' }], fields: [
        { fieldCode: 'pid', columnExpr: 'a.pid', dataType: 'string', displayName: '记录标识', sortable: true, searchable: false },
        { fieldCode: 'partName', columnExpr: 'a.dop_g_name', dataType: 'string', displayName: '零件名称', sortable: false, searchable: false },
      ] });
    for (const status of ['testing', 'published']) await human('PUT', `/api/meta/named-queries/${named.pid}/status`, { status });
    const source = await human('GET', `/api/open-platform/query-sources/${namedCode}`);
    const query = { code: queryCode, queryCode: namedCode, schemaVersion: 1, sourceFingerprint: source.fingerprint, sourceResources: { [modelCode]: resourceCode }, scopes: { execute: 'dop.grant:query' },
      parameters: { zone: { type: 'string', required: true, nullable: false, items: null } }, fields: { pid: { ...field('pid'), type: 'pid', nullable: false }, name: field('partName') },
      maxPageSize: 20, maxRows: 100, timeoutMs: 5000, maxConcurrent: 2 };
    await human('PUT', `/api/open-platform/query-publications/${queryCode}/draft`, { expectedRevision: 0, definition: query });
    await human('POST', `/api/open-platform/query-publications/${queryCode}/publish`, { expectedRevision: 1 });
    const application = await human('POST', '/api/open-platform/applications', { name: `授权接入应用 ${suffix}`, description: 'Real installation policy journey' });
    const installation = await human('POST', `/api/open-platform/applications/${application.pid}/installations`, { environment: 'staging', scopes: [...scopes, 'dop.grant:query'], rateLimitPerMinute: 600 });
    const credential = await human('POST', `/api/open-platform/installations/${installation.pid}/credentials`);
    const issued = await page.request.post(`${BACKEND_URL}/oauth2/token`, { form: { grant_type: 'client_credentials', client_id: credential.clientId, client_secret: credential.clientSecret } });
    expect(issued.status()).toBe(200); const token = await issued.json(); expect(token.access_token).toEqual(expect.any(String));
    const machineHeaders = { Authorization: `Bearer ${token.access_token}` }; const resourcePath = `${BACKEND_URL}/api/open/v1/resources/${resourceCode}`;
    const queryPath = `${BACKEND_URL}/api/open/v1/queries/${queryCode}:execute`;
    expect((await page.request.get(resourcePath, { headers: machineHeaders })).status()).toBe(403);
    expect((await page.request.post(queryPath, { headers: machineHeaders, data: { parameters: { zone: 'A' }, limit: 10 } })).status()).toBe(403);
    const screenshotRoot = path.join(process.env.AURA_EVIDENCE_ROOT || '/tmp', 'ui-publication-r1', 'screenshots'); await mkdir(screenshotRoot, { recursive: true });
    const snapshot = async (id: string) => { await page.screenshot({ path: path.join(screenshotRoot, `${id}.png`), fullPage: true }); };
    await page.goto('/settings/api-docs');
    const installationCard = page.getByTestId(`open-platform-installation-${installation.pid}`);
    await installationCard.getByRole('button', { name: '数据授权', exact: true }).click();
    const editor = page.getByTestId('installation-grant-editor'); await expect(editor).toBeVisible(); await snapshot('grant-entry');
    await editor.getByLabel('授权合同', { exact: true }).selectOption(`resource:${resourceCode}`);
    await expect(editor).toContainText('此安装尚未获此合同的数据授权');
    for (const name of ['列表查询', '详情读取', '创建记录', '修改记录', '删除记录']) await editor.getByLabel(name, { exact: true }).check();
    for (const alias of ['name', 'zone']) { await editor.getByLabel(`读取 ${alias}`, { exact: true }).check(); await editor.getByRole('checkbox', { name: `写入 ${alias} (创建必填)`, exact: true }).check(); }
    await expect(editor.getByLabel('读取 secret', { exact: true })).not.toBeChecked();
    await editor.getByRole('button', { name: '添加行约束', exact: true }).click();
    const constraint = editor.getByTestId('grant-constraint-0'); await constraint.getByLabel('约束公共字段', { exact: true }).selectOption('zone'); await constraint.getByLabel('允许值（每行一个）', { exact: true }).fill('A');
    await expect(editor.getByLabel('授权合同', { exact: true })).toBeDisabled(); await snapshot('grant-resource');
    const granted = page.waitForResponse(response => response.url().endsWith(`/installations/${installation.pid}/resources/${resourceCode}`) && response.request().method() === 'PUT');
    await editor.getByRole('button', { name: '保存安装授权', exact: true }).click(); expect((await granted).status()).toBe(200);
    await expect(editor.getByRole('status').filter({ hasText: '授权已保存' })).toBeVisible(); await snapshot('grant-saved');
    await page.reload(); await installationCard.getByRole('button', { name: '数据授权', exact: true }).click(); await editor.getByLabel('授权合同', { exact: true }).selectOption(`resource:${resourceCode}`);
    await expect(editor.getByLabel('读取 name', { exact: true })).toBeChecked(); await expect(editor.getByTestId('grant-constraint-0').getByLabel('允许值（每行一个）', { exact: true })).toHaveValue('A');
    const listed = await page.request.get(resourcePath, { headers: machineHeaders }); expect(listed.status()).toBe(200); expect((await listed.json()).items).toEqual([{ pid: recordA.pid, name: 'Allowed A', zone: 'A' }]);
    expect((await page.request.get(`${resourcePath}/${recordB.pid}`, { headers: machineHeaders })).status()).toBe(404);
    const forbidden = await page.request.post(resourcePath, { headers: { ...machineHeaders, 'Idempotency-Key': `grant-forbidden-${suffix}` }, data: { name: 'Denied', zone: 'B' } }); expect(forbidden.status()).toBe(403);
    await editor.getByLabel('授权合同', { exact: true }).selectOption(`query:${queryCode}`); await expect(editor).toContainText('已有列表授权');
    await editor.getByLabel('pid', { exact: true }).check(); await editor.getByLabel('name', { exact: true }).check(); await snapshot('grant-query');
    const queryGranted = page.waitForResponse(response => response.url().endsWith(`/installations/${installation.pid}/queries/${queryCode}`) && response.request().method() === 'PUT');
    await editor.getByRole('button', { name: '保存安装授权', exact: true }).click(); expect((await queryGranted).status()).toBe(200); await expect(editor.getByRole('status').filter({ hasText: '授权已保存' })).toBeVisible();
    const result = await page.request.post(queryPath, { headers: machineHeaders, data: { parameters: { zone: 'A' }, limit: 10 } }); expect(result.status()).toBe(200); expect((await result.json()).items).toEqual([{ pid: recordA.pid, name: 'Allowed A' }]);
    const otherRows = await page.request.post(queryPath, { headers: machineHeaders, data: { parameters: { zone: 'B' }, limit: 10 } }); expect(otherRows.status()).toBe(200); expect((await otherRows.json()).items).toEqual([]);
    const intent = { name: 'Created A', zone: 'A' }; const createHeaders = { ...machineHeaders, 'Idempotency-Key': `grant-create-${suffix}` };
    const created = await page.request.post(resourcePath, { headers: createHeaders, data: intent }); expect(created.status()).toBe(201); const row = await created.json(); expect(row).toMatchObject(intent); expect(row.pid).toEqual(expect.any(String)); expect(row).not.toHaveProperty('secret');
    const replay = await page.request.post(resourcePath, { headers: createHeaders, data: intent }); expect(replay.status()).toBe(201); expect(replay.headers()['idempotent-replay']).toBe('true'); expect(await replay.json()).toEqual(row);
    const updated = await page.request.patch(`${resourcePath}/${row.pid}`, { headers: { ...machineHeaders, 'Idempotency-Key': `grant-update-${suffix}`, 'If-Match': created.headers().etag }, data: { name: 'Updated A' } }); expect(updated.status()).toBe(200); expect(await updated.json()).toMatchObject({ pid: row.pid, name: 'Updated A' });
    const removed = await page.request.delete(`${resourcePath}/${row.pid}`, { headers: { ...machineHeaders, 'Idempotency-Key': `grant-delete-${suffix}`, 'If-Match': updated.headers().etag } }); expect(removed.status()).toBe(204);
    expect((await page.request.get(`${resourcePath}/${row.pid}`, { headers: machineHeaders })).status()).toBe(404);
    await editor.getByRole('button', { name: '撤销安装授权', exact: true }).click(); await snapshot('grant-revoke-dialog');
    await editor.getByRole('group', { name: '安装授权操作确认', exact: true }).getByRole('button', { name: '确认', exact: true }).click(); await expect(editor.getByRole('status').filter({ hasText: '授权已撤销' })).toBeVisible();
    expect((await page.request.post(queryPath, { headers: machineHeaders, data: { parameters: { zone: 'A' }, limit: 10 } })).status()).toBe(403); await snapshot('grant-query-revoked');
    await editor.getByLabel('授权合同', { exact: true }).selectOption(`resource:${resourceCode}`); await editor.getByRole('button', { name: '撤销安装授权', exact: true }).click();
    await editor.getByRole('group', { name: '安装授权操作确认', exact: true }).getByRole('button', { name: '确认', exact: true }).click(); await expect(editor.getByRole('status').filter({ hasText: '授权已撤销' })).toBeVisible();
    expect((await page.request.get(resourcePath, { headers: machineHeaders })).status()).toBe(403); await snapshot('grant-resource-revoked');
    const persisted = await human('GET', `/api/open-platform/installations/${installation.pid}/resources`); expect(persisted.find((view: { code: string }) => view.code === resourceCode).policy).toBeNull();
  });
});

async function authenticate(page: import('@playwright/test').Page) {
  const loginResponse = await page.request.post(`${BACKEND_URL}/api/auth/login`, {
    data: { email: DEFAULT_TEST_ACCOUNT.email, password: DEFAULT_TEST_ACCOUNT.password },
  });
  expect(loginResponse.status()).toBe(200);
  const login = await loginResponse.json();
  expect(String(login.code)).toBe('0');
  expect(login.data.jwt).toEqual(expect.any(String));
  const authorization = { Authorization: `Bearer ${login.data.jwt}` };
  const spacesResponse = await page.request.get(`${BACKEND_URL}/api/tenant-selection/my-spaces`, {
    headers: authorization,
  });
  expect(spacesResponse.status()).toBe(200);
  const spaces = await spacesResponse.json();
  expect(String(spaces.code)).toBe('0');
  const business = spaces.data.find(
    (space: { spaceType: string; tenantId: string }) => space.spaceType === 'business',
  );
  expect(business).toBeDefined();
  const selectionResponse = await page.request.post(`${BACKEND_URL}/api/tenant-selection/process`, {
    headers: authorization,
    data: { action: 'select', tenantId: business.tenantId },
  });
  expect(selectionResponse.status()).toBe(200);
  const selected = await selectionResponse.json();
  expect(String(selected.code)).toBe('0');
  expect(selected.data.jwt).toEqual(expect.any(String));
  const session = await sessionStorage.getSession();
  session.set('jwtToken', selected.data.jwt);
  const setCookie = await sessionStorage.commitSession(session, { maxAge: 604800 });
  const value = setCookie.match(/__session=([^;]+)/)?.[1];
  expect(value).toBeTruthy();
  await page.context().addCookies([
    { name: '__session', value: value!, url: WEB_BASE_URL, httpOnly: true, sameSite: 'Lax' },
    { name: 'locale', value: 'zh-CN', url: WEB_BASE_URL, sameSite: 'Lax' },
  ]);
  await page.addInitScript(() => localStorage.setItem('locale', 'zh-CN'));

}

async function capture(page: import('@playwright/test').Page, id: string, fullPage = true) {
  await page.screenshot({
    path: path.join(EVIDENCE_DIR, `${id}.png`),
    fullPage,
    timeout: 30_000,
  });
}

async function captureLocator(locator: import('@playwright/test').Locator, id: string) {
  await locator.scrollIntoViewIfNeeded();
  await locator.screenshot({ path: path.join(EVIDENCE_DIR, `${id}.png`), timeout: 30_000 });
}

async function dismissToasts(page: import('@playwright/test').Page) {
  const closeButtons = page.getByRole('button', { name: 'Close notification' });
  while ((await closeButtons.count()) > 0) {
    const previousCount = await closeButtons.count();
    await closeButtons.first().click();
    await expect(closeButtons).toHaveCount(previousCount - 1);
  }
  await expect(page.getByRole('alert')).toHaveCount(0);
}

async function openAccountMenu(page: import('@playwright/test').Page) {
  await expect(page.locator('header[data-hydrated]')).toHaveAttribute('data-hydrated', 'true', {
    timeout: 15_000,
  });
  const userMenu = page.getByTestId('user-menu');
  await userMenu.getByRole('button', { name: /User avatar/ }).click();
  await expect(userMenu.getByTestId('user-dropdown')).toBeVisible();
}

test.describe('Open Platform golden journey', () => {
  test.setTimeout(180_000);
  test.use({
    storageState: { cookies: [], origins: [] },
    locale: 'zh-CN',
    viewport: { width: 1440, height: 1000 },
  });

  test('covers application, installation, credential and revocation states', async ({
    page,
    context,
  }) => {
    await authenticate(page);
    const existingResponse = await page.request.get(`${WEB_BASE_URL}/api/open-platform/applications`);
    expect(existingResponse.status()).toBe(200);
    const existing = await existingResponse.json();
    expect(String(existing.code)).toBe('0');
    const existingPids = (existing.data as Array<{ pid: string }>).map((entry) => entry.pid);
    // Empty-state rendering is a controlled UI projection, not a claim that the DB is empty.
    await page.route('**/api/open-platform/applications', async (route) => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: '0', data: [] }) });
    });
    await page.goto('/');
    await expect(page).not.toHaveURL(/\/login|\/setup/);
    await openAccountMenu(page);
    const menuEntry = page.getByTestId('open-platform-link');
    await expect(menuEntry).toBeVisible();
    await expect(page.getByTestId('user-dropdown')).toContainText('退出登录');
    await expect(page.getByTestId('user-dropdown')).not.toContainText('user.logout');
    await capture(page, 'OP-OPS-01');
    await menuEntry.click();
    await expect(page.getByTestId('open-platform-page')).toBeVisible();
    await expect(page.getByTestId('open-platform-page')).not.toContainText(
      /Page Unavailable|加载失败|Page not found/,
    );
    await expect(page.getByTestId('open-platform-empty')).toBeVisible();
    await expect(page.getByRole('heading', { name: '暂无外部应用' })).toBeVisible();
    await capture(page, 'OP-UI-03');
    await page.unroute('**/api/open-platform/applications');

    await page.route('**/api/open-platform/applications', async (route) => {
      await route.fulfill({
        status: 503,
        contentType: 'application/json',
        body: JSON.stringify({ code: 'service_unavailable', message: '受控加载失败' }),
      });
    });
    await page.goto('/');
    await openAccountMenu(page);
    await page.getByTestId('open-platform-link').click();
    await expect(page.getByTestId('open-platform-page')).toContainText('受控加载失败');
    await expect(page.getByRole('button', { name: '重试' })).toBeVisible();
    await expect(page.getByTestId('sidebar')).toBeVisible();
    await capture(page, 'OP-UI-04');
    await page.unroute('**/api/open-platform/applications');
    await page.getByRole('button', { name: '重试' }).click();
    if (existingPids.length === 0) {
      await expect(page.getByTestId('open-platform-empty')).toBeVisible();
    } else {
      await expect(page.getByTestId('open-platform-application-list').locator('article')).toHaveCount(existingPids.length);
    }

    await page.getByTestId('open-platform-create-app').click();
    const createDialog = page.getByRole('dialog', { name: '创建外部应用' });
    await expect(createDialog).toBeVisible();
    await expect(createDialog.getByRole('button', { name: '新建' })).toBeDisabled();
    await capture(page, 'OP-UI-05');
    const appName = `金蝶 ERP 连接器 ${Date.now()}-${Math.random().toString(16).slice(2, 6)}`;
    await page.getByTestId('open-platform-app-name').fill(appName);
    await createDialog.getByRole('textbox', { name: '描述' }).fill('生产订单与库存事件统一接入');
    await createDialog.getByRole('button', { name: '新建' }).click();
    const appCard = page.getByTestId('open-platform-application-list').locator('article').filter({ hasText: appName });
    await expect(appCard).toHaveCount(1);
    await expect(appCard.getByText(appName, { exact: true })).toBeVisible();
    await expect(appCard.getByText('生产订单与库存事件统一接入')).toBeVisible();
    await expect(page.getByTestId('open-platform-page')).not.toContainText(
      /01[A-Z0-9]{20,}|[0-9a-f]{8}-[0-9a-f-]{27,}/i,
    );
    await capture(page, 'OP-UI-06');
    await dismissToasts(page);

    await appCard.getByRole('button', { name: '添加安装' }).click();
    const installDialog = page.getByRole('dialog', { name: '安装应用' });
    await installDialog.getByLabel('环境').selectOption('production');
    await installDialog.getByTestId('open-platform-rate-limit').fill('1200');
    const scopeChecks = installDialog.getByRole('checkbox');
    await expect(scopeChecks.first()).toBeChecked();
    await expect(scopeChecks).toHaveCount(7);
    await installDialog.getByLabel('assets.read').check();
    await installDialog.getByLabel('inventory.stock-ins.read').check();
    await installDialog.getByLabel('inventory.stock-ins.manage').check();
    await installDialog.getByLabel('openapi.events.read').check();
    await installDialog.getByLabel('automation.events.write').check();
    await installDialog.getByLabel('openapi.profile.read').check();
    await capture(page, 'OP-UI-07');
    await installDialog.getByRole('button', { name: '安装' }).click();
    await expect(appCard.getByText('生产环境')).toBeVisible();
    await expect(appCard.getByText('限流: 1200/min')).toBeVisible();

    await appCard.getByRole('button', { name: '新建凭据' }).click();
    const secretDialog = page.getByRole('dialog', { name: '立即保存此凭据' });
    await expect(secretDialog).toContainText('Client Secret 仅显示一次');
    await expect(secretDialog.getByText('client_id')).toBeVisible();
    await expect(secretDialog.getByText('client_secret')).toBeVisible();
    const clientId = (await secretDialog.locator('code').nth(0).textContent())?.trim();
    const clientSecret = (await secretDialog.locator('code').nth(1).textContent())?.trim();
    expect(clientId).toMatch(/^ab_client_/);
    expect(clientSecret).toEqual(expect.any(String));
    await dismissToasts(page);
    await secretDialog
      .locator('code')
      .nth(1)
      .evaluate((element) => {
        element.textContent = '[redacted for evidence]';
      });
    await capture(page, 'OP-UI-08');
    await secretDialog.getByRole('button', { name: '我已保存' }).click();

    const tokenResponse = await page.request.post(`${BACKEND_URL}/oauth2/token`, {
      form: {
        grant_type: 'client_credentials',
        client_id: clientId!,
        client_secret: clientSecret!,
        scope:
          'assets.manage assets.read inventory.stock-ins.read inventory.stock-ins.manage openapi.events.read automation.events.write openapi.profile.read',
      },
    });
    expect(tokenResponse.status()).toBe(200);
    const token = await tokenResponse.json();
    expect(token.token_type).toBe('Bearer');
    expect(String(token.scope).split(' ').sort()).toEqual([
      'assets.manage',
      'assets.read',
      'automation.events.write',
      'inventory.stock-ins.manage',
      'inventory.stock-ins.read',
      'openapi.events.read',
      'openapi.profile.read',
    ]);
    expect(token.access_token).toEqual(expect.any(String));
    expect(token.expires_in).toBeGreaterThan(0);

    const whoAmIResponse = await page.request.get(`${BACKEND_URL}/api/open/v1/whoami`, {
      headers: {
        Authorization: `Bearer ${token.access_token}`,
        'X-Request-Id': 'open-platform-golden-whoami',
        'X-Tenant-Id': 'spoofed-tenant-must-be-ignored',
      },
    });
    expect(whoAmIResponse.status()).toBe(200);
    const principal = await whoAmIResponse.json();
    expect(principal.environment).toBe('production');
    expect(principal.scopes).toEqual(
      expect.arrayContaining(['automation.events.write', 'openapi.profile.read']),
    );
    expect(principal.applicationPid).toEqual(expect.any(String));
    expect(principal.installationPid).toEqual(expect.any(String));

    // Exercise the developer entry while this credential is active. Document
    // loading alone cannot prove the OAuth and API transport paths work.
    await context.setExtraHTTPHeaders({});
    const authorizedReferencePromise = context.waitForEvent('page');
    await page.getByRole('button', { name: /API 参考/ }).click();
    const authorizedReference = await authorizedReferencePromise;
    try {
      await expect(authorizedReference).toHaveURL(
        `${WEB_BASE_URL}/swagger-ui/index.html?urls.primaryName=open-platform`,
      );
      await expect(authorizedReference.locator('.opblock').first()).toBeVisible({ timeout: 30_000 });
      const publicDocument = await authorizedReference.request.get(`${WEB_BASE_URL}/v3/api-docs/open-platform`);
      expect(publicDocument.status()).toBe(200);
      expect((await publicDocument.json()).servers).toEqual([{ url: '/' }]);
      await authorizedReference.getByRole('button', { name: 'Authorize', exact: true }).first().click();
      const authorizationDialog = authorizedReference.locator('.dialog-ux');
      await expect(authorizationDialog).toBeVisible();
      const oauthAuthorization = authorizationDialog.locator('.auth-container').filter({
        hasText: 'oauth2ClientCredentials',
      });
      await oauthAuthorization.getByLabel('client_id:', { exact: true }).fill(clientId!);
      await oauthAuthorization.getByLabel('client_secret:', { exact: true }).fill(clientSecret!);
      const browserTokenPromise = authorizedReference.waitForResponse(
        (response) => response.request().method() === 'POST'
          && response.url() === `${WEB_BASE_URL}/oauth2/token`,
      );
      await oauthAuthorization.getByRole('button', {
        name: 'Apply given OAuth2 credentials', exact: true,
      }).click();
      const browserTokenResponse = await browserTokenPromise;
      expect(browserTokenResponse.status()).toBe(200);
      const browserToken = await browserTokenResponse.json();
      expect(browserToken.token_type).toBe('Bearer');
      await expect(oauthAuthorization.getByRole('button', {
        name: 'Remove authorization', exact: true,
      })).toBeVisible();
      await oauthAuthorization.getByRole('button', { name: 'Close', exact: true }).click();
      await expect(authorizationDialog).toHaveCount(0);
      const whoamiOperation = authorizedReference.locator('.opblock').filter({
        has: authorizedReference.locator('[data-path="/api/open/v1/whoami"]'),
      });
      await whoamiOperation.locator('.opblock-summary-control').click();
      // The product enables Try it out by default in its Swagger configuration.
      await expect(whoamiOperation.getByRole('button', { name: 'Cancel', exact: true })).toBeVisible();
      const browserWhoamiPromise = authorizedReference.waitForResponse(
        (response) => response.request().method() === 'GET'
          && response.url() === `${WEB_BASE_URL}/api/open/v1/whoami`,
      );
      await whoamiOperation.getByRole('button', { name: 'Execute', exact: true }).click();
      const browserWhoamiResponse = await browserWhoamiPromise;
      // Swagger renders the live Authorization header in its curl example.
      // Redact only evidence text after the actual browser request has completed.
      await authorizedReference.locator('body').evaluate((body, accessToken: string) => {
        const walker = document.createTreeWalker(body, NodeFilter.SHOW_TEXT);
        for (let node = walker.nextNode(); node; node = walker.nextNode()) {
          if (node.nodeValue?.includes(accessToken)) {
            node.nodeValue = node.nodeValue.replaceAll(accessToken, '[redacted for evidence]');
          }
        }
      }, browserToken.access_token);
      expect(browserWhoamiResponse.status()).toBe(200);
      expect(await browserWhoamiResponse.json()).toEqual(principal);
      await expect(whoamiOperation.locator('.responses-wrapper')).toContainText('production');
      await capture(authorizedReference, 'OP-UI-13-authorized', false);
    } finally {
      await authorizedReference.close();
      await context.setExtraHTTPHeaders({ Referer: `${WEB_BASE_URL}/` });
    }

    const externalEvent = {
      id: 'order-10001-created',
      type: 'order.created',
      schemaVersion: 1,
      occurredAt: '2026-09-14T08:00:00Z',
      subject: { type: 'order', pid: 'order-10001' },
      sequence: 1,
      data: { orderNo: 'SO-10001', source: 'kingdee' },
    };
    const eventResponse = await page.request.post(
      `${BACKEND_URL}/api/open/v1/event-sources/kingdee/events`,
      {
        headers: {
          Authorization: `Bearer ${token.access_token}`,
          'Idempotency-Key': 'open-platform-golden-order-10001',
        },
        data: externalEvent,
      },
    );
    expect(eventResponse.status()).toBe(202);
    expect(await eventResponse.json()).toMatchObject({
      eventId: externalEvent.id,
      duplicate: false,
    });

    for (const template of ['asset-management', 'simple-inventory']) {
      const installTemplate = await page.request.post(`/api/templates/${template}/install`, {
        data: {},
        // Model provisioning is fixture setup, separate from action and production SLO budgets.
        timeout: 30_000,
      });
      expect(installTemplate.status(), `install ${template}`).toBe(200);
      expect(await installTemplate.json()).toMatchObject({ success: true, status: 'SUCCESS' });
    }

    const assetCreateResponse = await page.request.post('/api/dynamic/tasset_asset/create', {
      data: {
        tasset_as_code: 'AST-OPEN-API-001',
        tasset_as_name: 'Open API 验证笔记本',
        tasset_as_serial: 'OPEN-API-GOLDEN-001',
        tasset_as_status: 'available',
        tasset_as_location: 'Shanghai Lab',
      },
    });
    expect(assetCreateResponse.status()).toBe(200);
    const assetCreateBody = await assetCreateResponse.json();
    expect(assetCreateBody.code).toBe('0');
    const assetPid = assetCreateBody.data.pid as string;

    const assetReadResponse = await page.request.get(
      `${BACKEND_URL}/api/open/v1/resources/assets/${assetPid}`,
      { headers: { Authorization: `Bearer ${token.access_token}` } },
    );
    expect(assetReadResponse.status()).toBe(200);
    const publishedAsset = await assetReadResponse.json();
    expect(publishedAsset).toMatchObject({
      pid: assetPid,
      name: 'Open API 验证笔记本',
      serialNumber: 'OPEN-API-GOLDEN-001',
      status: 'available',
    });
    expect(Object.keys(publishedAsset)).not.toContain('tasset_as_name');

    const raceAssetCreate = await page.request.post('/api/dynamic/tasset_asset/create', {
      data: {
        tasset_as_code: 'AST-OPEN-API-RACE',
        tasset_as_name: 'Open API 并发验证资产',
        tasset_as_status: 'available',
      },
    });
    const raceAssetPid = (await raceAssetCreate.json()).data.pid as string;
    const raceAssetRead = await page.request.get(
      `${BACKEND_URL}/api/open/v1/resources/assets/${raceAssetPid}`,
      { headers: { Authorization: `Bearer ${token.access_token}` } },
    );
    const raceEtag = raceAssetRead.headers().etag!;
    const raceResponses = await Promise.all(
      ['alice', 'bob'].map((assignee, index) =>
        page.request.post(`${BACKEND_URL}/api/open/v1/commands/assets.assign:execute`, {
          headers: {
            Authorization: `Bearer ${token.access_token}`,
            'Idempotency-Key': `open-platform-race-${index}-0001`,
            'If-Match': raceEtag,
          },
          data: { targetPid: raceAssetPid, input: { assignee } },
        }),
      ),
    );
    expect(raceResponses.map((response) => response.status()).sort()).toEqual([200, 412]);

    const idempotencyKey = 'open-platform-asset-assign-0001';
    const assetEtag = assetReadResponse.headers().etag;
    expect(assetEtag).toMatch(/^"ab1\./);
    const assignAsset = () =>
      page.request.post(`${BACKEND_URL}/api/open/v1/commands/assets.assign:execute`, {
        headers: {
          Authorization: `Bearer ${token.access_token}`,
          'Idempotency-Key': idempotencyKey,
          'If-Match': assetEtag!,
        },
        data: { targetPid: assetPid, input: { assignee: 'alice' } },
      });
    const assignmentResponse = await assignAsset();
    expect(assignmentResponse.status()).toBe(200);
    expect(await assignmentResponse.json()).toMatchObject({
      command: 'assets.assign',
      idempotentReplay: false,
      resource: { pid: assetPid, assignedTo: 'alice', status: 'in_use' },
    });
    const replayResponse = await assignAsset();
    expect(replayResponse.status()).toBe(200);
    expect(await replayResponse.json()).toMatchObject({
      command: 'assets.assign',
      idempotentReplay: true,
      resource: { pid: assetPid, assignedTo: 'alice', status: 'in_use' },
    });
    const staleAssignment = await page.request.post(
      `${BACKEND_URL}/api/open/v1/commands/assets.assign:execute`,
      {
        headers: {
          Authorization: `Bearer ${token.access_token}`,
          'Idempotency-Key': 'open-platform-asset-assign-stale-0002',
          'If-Match': assetEtag!,
        },
        data: { targetPid: assetPid, input: { assignee: 'bob' } },
      },
    );
    expect(staleAssignment.status()).toBe(412);
    expect(await staleAssignment.json()).toMatchObject({ code: 'precondition_failed' });

    const inventoryProduct = await page.request.post('/api/dynamic/tinv_product/create', {
      data: { tinv_pd_code: 'OP-PROD-001', tinv_pd_name: 'Open API 入库商品' },
    });
    const inventoryWarehouse = await page.request.post('/api/dynamic/tinv_warehouse/create', {
      data: { tinv_wh_code: 'OP-WH-001', tinv_wh_name: 'Open API 上海仓' },
    });
    expect(inventoryProduct.status()).toBe(200);
    expect(inventoryWarehouse.status()).toBe(200);
    const productPid = (await inventoryProduct.json()).data.pid as string;
    const warehousePid = (await inventoryWarehouse.json()).data.pid as string;
    const stockInCreate = await page.request.post('/api/dynamic/tinv_stock_in/create', {
      data: {
        tinv_si_code: 'OP-SI-001',
        tinv_si_product_id: productPid,
        tinv_si_warehouse_id: warehousePid,
        tinv_si_quantity: 3,
        tinv_si_unit_cost: 12.5,
        tinv_si_supplier: 'Open API fixture',
      },
    });
    expect(stockInCreate.status()).toBe(200);
    const stockInPid = (await stockInCreate.json()).data.pid as string;
    const stockInRead = await page.request.get(
      `${BACKEND_URL}/api/open/v1/resources/inventory.stock-ins/${stockInPid}`,
      { headers: { Authorization: `Bearer ${token.access_token}` } },
    );
    expect(stockInRead.status()).toBe(200);
    expect(await stockInRead.json()).toMatchObject({
      pid: stockInPid,
      productPid,
      warehousePid,
      quantity: 3,
    });
    const stockInConfirm = await page.request.post(
      `${BACKEND_URL}/api/open/v1/commands/inventory.stock-ins.confirm:execute`,
      {
        headers: {
          Authorization: `Bearer ${token.access_token}`,
          'Idempotency-Key': 'open-platform-stock-in-confirm-0001',
          'If-Match': stockInRead.headers().etag,
        },
        data: { targetPid: stockInPid, input: {} },
      },
    );
    expect(stockInConfirm.status()).toBe(200);
    expect(await stockInConfirm.json()).toMatchObject({
      command: 'inventory.stock-ins.confirm',
      resource: { pid: stockInPid, status: 'confirmed' },
    });

    const assetPage = await page.request.get(
      `${BACKEND_URL}/api/open/v1/resources/assets?limit=1`,
      {
        headers: { Authorization: `Bearer ${token.access_token}` },
      },
    );
    const assetPageBody = await assetPage.json();
    expect(assetPageBody.hasMore).toBe(true);
    expect(assetPageBody.nextCursor).toMatch(/^ab1\./);
    expect(assetPageBody.nextCursor).not.toContain(assetPid);
    const badCursor = await page.request.get(
      `${BACKEND_URL}/api/open/v1/resources/assets?limit=1&cursor=${encodeURIComponent(`${assetPageBody.nextCursor}x`)}`,
      { headers: { Authorization: `Bearer ${token.access_token}` } },
    );
    expect(badCursor.status()).toBe(400);

    const publicCatalog = await page.request.get(`${BACKEND_URL}/api/open/v1/event-catalog`, {
      headers: { Authorization: `Bearer ${token.access_token}` },
    });
    expect(publicCatalog.status()).toBe(200);
    expect((await publicCatalog.json()).map((item: { type: string }) => item.type).sort()).toEqual([
      'assets.assignment.changed',
      'inventory.stock-in.confirmed',
    ]);

    const internalResourceProbe = await page.request.get(
      `${BACKEND_URL}/api/open/v1/resources/tasset_asset/${assetPid}`,
      { headers: { Authorization: `Bearer ${token.access_token}` } },
    );
    expect(internalResourceProbe.status()).toBe(404);

    await appCard.getByRole('button', { name: '运维' }).click();
    const operations = page.getByTestId('open-platform-operations-panel');
    await expect(operations).toBeVisible();
    await expect(operations).toContainText('调用审计');
    await expect(operations).toContainText('open-platform-golden-whoami');
    await capture(page, 'OP-OPS-02');
    await capture(page, 'OP-OPS-03');

    await page.route('**/api/open-platform/event-catalog', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: '0',
          data: [
            {
              type: 'assets.assignment.changed',
              currentVersion: 1,
              supportedVersions: [1],
              classification: 'partner',
              subjectResourceCode: 'assets',
            },
            {
              type: 'inventory.stock-in.confirmed',
              currentVersion: 1,
              supportedVersions: [1],
              classification: 'partner',
              subjectResourceCode: 'inventory.stock-ins',
            },
          ],
        }),
      });
    });
    await page.route('**/api/open-platform/installations/*/webhook-health', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: '0',
          data: [
            {
              pid: 'hook-healthy',
              name: 'ERP asset sync',
              eventType: 'assets.assignment.changed',
              eventVersion: 1,
              catalogCurrentVersion: 1,
              compatible: true,
              rotationStatus: 'healthy',
              rotationDueAt: '2026-12-01T08:00:00Z',
              enabled: true,
            },
            {
              pid: 'hook-due',
              name: 'Warehouse receipt sync',
              eventType: 'inventory.stock-in.confirmed',
              eventVersion: 1,
              catalogCurrentVersion: 1,
              compatible: true,
              rotationStatus: 'due',
              rotationDueAt: '2026-09-20T08:00:00Z',
              enabled: true,
            },
            {
              pid: 'hook-overdue',
              name: 'Legacy asset bridge',
              eventType: 'assets.assignment.changed',
              eventVersion: 1,
              catalogCurrentVersion: 1,
              compatible: true,
              rotationStatus: 'overdue',
              rotationDueAt: '2026-08-01T08:00:00Z',
              enabled: true,
            },
            {
              pid: 'hook-missing',
              name: 'Unsigned receipt bridge',
              eventType: 'inventory.stock-in.confirmed',
              eventVersion: 1,
              catalogCurrentVersion: 1,
              compatible: true,
              rotationStatus: 'missing',
              enabled: false,
            },
            {
              pid: 'hook-incompatible',
              name: 'Old schema consumer',
              eventType: 'assets.assignment.changed',
              eventVersion: 0,
              catalogCurrentVersion: 1,
              compatible: false,
              rotationStatus: 'healthy',
              enabled: false,
            },
          ],
        }),
      });
    });
    await operations.getByTestId('open-platform-operations-refresh').click();
    const eventCatalog = operations.getByTestId('open-platform-event-catalog');
    const webhookHealth = operations.getByTestId('open-platform-webhook-health');
    await expect(eventCatalog).toContainText('inventory.stock-in.confirmed');
    await expect(eventCatalog).toContainText('事件目录');
    await expect(eventCatalog).toContainText('支持 v1');
    await expect(webhookHealth).toContainText('Webhook 兼容性与签名');
    await expect(webhookHealth.locator('[data-rotation-status="healthy"]')).toHaveCount(2);
    await expect(webhookHealth.locator('[data-rotation-status="due"]')).toHaveCount(1);
    await expect(webhookHealth.locator('[data-rotation-status="overdue"]')).toContainText(
      '请立即轮换签名密钥。',
    );
    await expect(webhookHealth.locator('[data-rotation-status="missing"]')).toContainText(
      '启用投递前请添加签名密钥。',
    );
    await expect(webhookHealth.locator('[data-compatible="false"]')).toContainText(
      '请选择受支持的事件版本。',
    );
    await expect(operations).not.toContainText(
      /super-secret|https:\/\/.*hook|Bearer [A-Za-z0-9._-]+/,
    );
    await expect(webhookHealth.locator('[data-rotation-status="healthy"]').first()).toContainText('目录版本 v1');
    await expect(webhookHealth.locator('[data-rotation-status="healthy"]').first()).toContainText('12/01');
    await expect(webhookHealth.locator('[data-rotation-status="due"]')).toContainText('09/20');
    await expect(webhookHealth.locator('[data-rotation-status="overdue"]')).toContainText('08/01');
    await expect(webhookHealth.locator('[data-compatible="false"]')).toContainText('目录版本 v1');
    await captureLocator(eventCatalog, 'OP-PROTO-01');
    await captureLocator(
      webhookHealth.locator('[data-rotation-status="healthy"]').first(),
      'OP-PROTO-02',
    );
    await captureLocator(webhookHealth.locator('[data-rotation-status="due"]'), 'OP-PROTO-03');
    await captureLocator(webhookHealth.locator('[data-rotation-status="overdue"]'), 'OP-PROTO-04');
    await captureLocator(webhookHealth.locator('[data-rotation-status="missing"]'), 'OP-PROTO-05');
    await captureLocator(webhookHealth.locator('[data-compatible="false"]'), 'OP-PROTO-06');
    await page.unroute('**/api/open-platform/event-catalog');
    await page.unroute('**/api/open-platform/installations/*/webhook-health');

    let releaseLoading!: () => void;
    const loadingGate = new Promise<void>((resolve) => {
      releaseLoading = resolve;
    });
    await page.route('**/api/open-platform/installations/*/overview*', async (route) => {
      await loadingGate;
      await route.continue();
    });
    await operations.getByTestId('open-platform-operations-refresh').click();
    await expect(
      operations.getByTestId('open-platform-operations-refresh').locator('svg'),
    ).toHaveClass(/animate-spin/);
    await captureLocator(operations, 'OP-PROTO-09');
    releaseLoading();
    await expect(
      operations.getByTestId('open-platform-operations-refresh').locator('svg'),
    ).not.toHaveClass(/animate-spin/);
    await page.unroute('**/api/open-platform/installations/*/overview*');

    await operations.getByLabel('request_id').fill('open-platform-golden-whoami');
    const callAudit = operations.getByTestId('open-platform-call-audit');
    await expect(callAudit).toContainText('GET /api/open/v1/whoami');
    await expect(callAudit).not.toContainText('assets.assign');
    await capture(page, 'OP-OPS-04');
    await operations.getByLabel('request_id').fill('request-id-with-no-match');
    await expect(operations).toContainText(/没有.*筛选条件的调用/);
    await capture(page, 'OP-OPS-04-empty');
    await operations.getByLabel('request_id').fill('');

    await page.route('**/api/open-platform/installations/*/audits*', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: '0',
          data: [
            {
              requestId: 'req-forbidden',
              method: 'GET',
              path: '/api/open/v1/resources/assets/a1',
              status: 403,
              durationMs: 12,
              occurredAt: '2026-09-14T08:00:00Z',
            },
            {
              requestId: 'req-throttled',
              method: 'GET',
              path: '/api/open/v1/whoami',
              status: 429,
              durationMs: 3,
              occurredAt: '2026-09-14T08:01:00Z',
            },
            {
              requestId: 'req-failed',
              method: 'POST',
              path: '/api/open/v1/event-sources/erp/events',
              status: 500,
              durationMs: 81,
              occurredAt: '2026-09-14T08:02:00Z',
            },
          ],
        }),
      });
    });
    await operations.getByTestId('open-platform-operations-refresh').click();
    await expect(operations).toContainText('req-throttled');
    await capture(page, 'OP-OPS-05');
    await page.unroute('**/api/open-platform/installations/*/audits*');

    let replayed = false;
    await page.route(
      '**/api/open-platform/installations/*/webhook-deliveries/*/replay',
      async (route) => {
        replayed = true;
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ code: '0', data: null }),
        });
      },
    );
    await page.route('**/api/open-platform/installations/*/webhook-deliveries*', async (route) => {
      const deliveries = [
        {
          pid: 'delivery-success',
          subscriptionName: '金蝶订单事件',
          eventId: 'evt-success',
          status: 'success',
          retryCount: 0,
          maxRetries: 5,
          responseStatus: 204,
          createdAt: '2026-09-14T07:58:00Z',
          replayable: false,
        },
        {
          pid: 'delivery-pending',
          subscriptionName: '金蝶库存事件',
          eventId: 'evt-pending',
          status: 'pending',
          retryCount: 1,
          maxRetries: 5,
          createdAt: '2026-09-14T07:59:00Z',
          replayable: false,
        },
        {
          pid: 'delivery-golden',
          subscriptionName: '金蝶库存事件',
          eventId: 'evt-golden',
          status: replayed ? 'pending' : 'dead_letter',
          retryCount: replayed ? 0 : 5,
          maxRetries: 5,
          responseStatus: replayed ? undefined : 503,
          failureReason: replayed ? undefined : 'HTTP 503',
          replayCount: replayed ? 1 : 0,
          createdAt: '2026-09-14T08:00:00Z',
          replayable: !replayed,
        },
      ];
      const status = new URL(route.request().url()).searchParams.get('status');
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: '0',
          data: status ? deliveries.filter((item) => item.status === status) : deliveries,
        }),
      });
    });
    await operations.getByTestId('open-platform-operations-refresh').click();
    await expect(operations).toContainText('金蝶库存事件');
    await capture(page, 'OP-OPS-06');
    await operations.getByLabel('投递状态').selectOption('dead_letter');
    await expect(operations.getByLabel('投递状态').locator('option:checked')).toHaveText('死信');
    await expect(operations).toContainText('HTTP 503');
    await expect(operations).not.toContainText('evt-success');
    await expect(operations).not.toContainText('evt-pending');
    await capture(page, 'OP-OPS-07');
    await operations.getByRole('button', { name: '重放' }).click();
    const replayConfirmation = operations.getByRole('alert');
    await expect(replayConfirmation).toContainText('重放此投递？');
    await replayConfirmation.scrollIntoViewIfNeeded();
    await capture(page, 'OP-OPS-08', false);
    await operations.getByRole('button', { name: '确认重放' }).click();
    await expect(operations).toContainText(/没有.*筛选条件的投递/);
    await operations.getByLabel('投递状态').selectOption('');
    await expect(operations).toContainText('evt-golden');
    await capture(page, 'OP-OPS-09');
    await dismissToasts(page);
    await expect(operations.getByRole('button', { name: '重放' })).toHaveCount(0);
    await capture(page, 'OP-OPS-10');
    await page.unroute('**/api/open-platform/installations/*/webhook-deliveries*');
    await page.unroute('**/api/open-platform/installations/*/webhook-deliveries/*/replay');

    await page.route('**/api/open-platform/installations/*/overview*', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: '0',
          data: {
            windowHours: 24,
            totalCalls: 0,
            errorCalls: 0,
            throttledCalls: 0,
            errorRate: 0,
            p95DurationMs: 0,
            deadLetterCount: 0,
          },
        }),
      });
    });
    await page.route('**/api/open-platform/installations/*/audits*', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: '0', data: [] }),
      });
    });
    await page.route('**/api/open-platform/installations/*/webhook-deliveries*', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: '0', data: [] }),
      });
    });
    await page.route('**/api/open-platform/event-catalog', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: '0', data: [] }),
      });
    });
    await page.route('**/api/open-platform/installations/*/webhook-health', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: '0', data: [] }),
      });
    });
    await operations.getByTestId('open-platform-operations-refresh').click();
    await expect(operations).toContainText(/没有.*筛选条件的调用/);
    await capture(page, 'OP-OPS-13');
    await captureLocator(
      operations.getByTestId('open-platform-contract-health-grid'),
      'OP-PROTO-07',
    );
    await page.unroute('**/api/open-platform/installations/*/overview*');
    await page.unroute('**/api/open-platform/installations/*/audits*');
    await page.unroute('**/api/open-platform/installations/*/webhook-deliveries*');
    await page.unroute('**/api/open-platform/event-catalog');
    await page.unroute('**/api/open-platform/installations/*/webhook-health');

    await page.route('**/api/open-platform/installations/*/overview*', async (route) => {
      await route.fulfill({
        status: 503,
        contentType: 'application/json',
        body: JSON.stringify({ code: 'service_unavailable', message: '受控运维加载失败' }),
      });
    });
    await operations.getByTestId('open-platform-operations-refresh').click();
    await expect(operations).toContainText('受控运维加载失败');
    await capture(page, 'OP-OPS-14');
    await captureLocator(operations, 'OP-PROTO-08');
    await page.unroute('**/api/open-platform/installations/*/overview*');
    await operations.getByRole('button', { name: /重试|Retry/ }).click();
    await expect(operations).toContainText('调用审计');

    await appCard.getByRole('button', { name: '查看凭据' }).click();
    const credentialList = appCard.locator('[data-testid^="open-platform-credentials-"]');
    await expect(credentialList).toContainText('ab_client_');
    await credentialList.getByRole('button', { name: '轮换' }).click();
    const rotateDialog = page.getByRole('dialog', { name: '轮换凭据' });
    await expect(rotateDialog).toContainText('宽限期');
    await expect(rotateDialog).toContainText('已签发的 Token');
    await rotateDialog.getByRole('spinbutton').fill('5.5');
    await expect(rotateDialog.getByRole('button', { name: '轮换' })).toBeDisabled();
    await expect(rotateDialog.getByRole('spinbutton')).toHaveAttribute('aria-invalid', 'true');
    await rotateDialog.getByRole('spinbutton').fill('5');
    await expect(rotateDialog.getByRole('button', { name: '轮换' })).toBeEnabled();
    await rotateDialog.getByRole('spinbutton').fill('1');
    await expect(rotateDialog.getByRole('button', { name: '轮换' })).toBeDisabled();
    await expect(rotateDialog.getByRole('alert')).toHaveText('请输入 5 至 10080 的整数分钟。');
    await expect(rotateDialog.getByRole('spinbutton')).toHaveAccessibleDescription('请输入 5 至 10080 的整数分钟。');
    await capture(page, 'OP-OPS-12');
    await rotateDialog.getByRole('button', { name: '取消' }).click();
    await credentialList.getByRole('button', { name: '轮换' }).click();
    await page.getByRole('dialog', { name: '轮换凭据' }).getByRole('spinbutton').fill('1440');
    await rotateDialog.getByRole('button', { name: '轮换' }).click();
    const rotatedSecretDialog = page.getByRole('dialog', { name: '立即保存此凭据' });
    await expect(rotatedSecretDialog).toBeVisible();
    const rotatedClientId = (
      await rotatedSecretDialog.locator('code').nth(0).textContent()
    )?.trim();
    const rotatedClientSecret = (
      await rotatedSecretDialog.locator('code').nth(1).textContent()
    )?.trim();
    await rotatedSecretDialog
      .locator('code')
      .nth(1)
      .evaluate((element) => {
        element.textContent = '[redacted for evidence]';
      });
    await capture(page, 'OP-OPS-11');
    await rotatedSecretDialog.getByRole('button', { name: '我已保存' }).click();

    const rotatedTokenResponse = await page.request.post(`${BACKEND_URL}/oauth2/token`, {
      form: {
        grant_type: 'client_credentials',
        client_id: rotatedClientId!,
        client_secret: rotatedClientSecret!,
        scope: 'openapi.profile.read',
      },
    });
    expect(rotatedTokenResponse.status()).toBe(200);
    const oldCredentialDuringGrace = await page.request.post(`${BACKEND_URL}/oauth2/token`, {
      form: {
        grant_type: 'client_credentials',
        client_id: clientId!,
        client_secret: clientSecret!,
        scope: 'openapi.profile.read',
      },
    });
    expect(oldCredentialDuringGrace.status()).toBe(200);

    await credentialList.getByRole('button', { name: '吊销' }).first().click();
    const revokeDialog = page.getByRole('dialog', { name: '确认吊销凭据？' });
    await expect(revokeDialog).toContainText('Access Token 将立即失效');
    await capture(page, 'OP-UI-09');
    await revokeDialog.getByRole('button', { name: '取消' }).click();

    await appCard.getByRole('button', { name: '编辑 Scope' }).click();
    const scopeDialog = page.getByRole('dialog', { name: '编辑 Scope' });
    await expect(scopeDialog).toContainText('立即吊销此安装的现有 Access Token');
    await capture(page, 'OP-UI-10');
    await scopeDialog.getByRole('button', { name: '保存' }).click();
    await expect(scopeDialog).toBeHidden();
    await dismissToasts(page);
    const revokedTokenResponse = await page.request.get(`${BACKEND_URL}/api/open/v1/whoami`, {
      headers: { Authorization: `Bearer ${token.access_token}` },
    });
    expect(revokedTokenResponse.status()).toBe(401);

    await appCard.getByRole('button', { name: '停用安装' }).click();
    const disableInstallationDialog = page.getByRole('dialog', { name: '确认停用访问？' });
    await expect(disableInstallationDialog).toContainText('立即吊销有效 Token');
    await capture(page, 'OP-UI-11');
    await disableInstallationDialog.getByRole('button', { name: '停用' }).click();
    await expect(appCard.getByText('已停用', { exact: true })).toBeVisible();
    await dismissToasts(page);

    await appCard.getByRole('button', { name: '停用', exact: true }).click();
    const disableApplicationDialog = page.getByRole('dialog', { name: '确认停用访问？' });
    await expect(disableApplicationDialog).toContainText('无法在本页面恢复');
    await expect
      .poll(async () => (await disableApplicationDialog.boundingBox())?.width ?? 0)
      .toBeGreaterThan(400);
    await capture(page, 'OP-UI-12');
    await disableApplicationDialog.getByRole('button', { name: '停用' }).click();
    await expect(appCard.getByRole('button', { name: '添加安装' })).toBeDisabled();
    await dismissToasts(page);
    await capture(page, 'OP-UI-02');

    // APIRequestContext needs the project's synthetic same-origin Referer for writes.
    // Chromium rejects that forced header on the real noreferrer popup; browser
    // navigation must use its native referrer policy, as an ordinary user does.
    await context.setExtraHTTPHeaders({});
    const popupPromise = context.waitForEvent('page');
    await page.getByRole('button', { name: /API 参考/ }).click();
    const apiReference = await popupPromise;
    await apiReference.waitForLoadState('domcontentloaded');
    await expect(apiReference).toHaveURL(
      `${WEB_BASE_URL}/swagger-ui/index.html?urls.primaryName=open-platform`,
    );
    expect(new URL(apiReference.url()).origin).toBe(new URL(WEB_BASE_URL).origin);
    await expect(apiReference.locator('.opblock').first()).toBeVisible({ timeout: 30_000 });
    await expect(apiReference.locator('body')).not.toContainText(
      /Whitelabel Error Page|404 Not Found|Loading page configuration/,
    );
    await capture(apiReference, 'OP-UI-13', false);
    await apiReference.close();
    await context.setExtraHTTPHeaders({ Referer: `${WEB_BASE_URL}/` });

    await page.route('**/api/open-platform/event-catalog', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: '0',
          data: [
            {
              type: 'assets.assignment.changed',
              currentVersion: 1,
              supportedVersions: [1],
              classification: 'partner',
              subjectResourceCode: 'assets',
            },
          ],
        }),
      });
    });
    await page.route('**/api/open-platform/installations/*/webhook-health', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: '0',
          data: [
            {
              pid: 'mobile-overdue',
              name: 'Mobile overdue bridge',
              eventType: 'assets.assignment.changed',
              eventVersion: 0,
              catalogCurrentVersion: 1,
              compatible: false,
              rotationStatus: 'overdue',
              rotationDueAt: '2026-08-01T08:00:00Z',
              enabled: false,
            },
          ],
        }),
      });
    });
    await operations.getByTestId('open-platform-operations-refresh').click();

    await page.setViewportSize({ width: 390, height: 844 });
    await expect(page.getByTestId('sidebar')).toHaveClass(/-translate-x-full/);
    await expect
      .poll(() =>
        page.getByTestId('sidebar').evaluate((element) => element.getBoundingClientRect().right),
      )
      .toBeLessThanOrEqual(1);
    await expect(page.getByTestId('open-platform-page')).toBeVisible();
    const overflow = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
    );
    expect(overflow).toBeLessThanOrEqual(1);
    for (const label of ['已停用', '所有者']) {
      const badge = appCard.getByText(label, { exact: true }).first();
      await expect(badge).toHaveCSS('white-space', 'nowrap');
    }
    const headerActions = page.getByTestId('open-platform-page').locator('header').first();
    for (const action of ['公共资源发布', 'API 参考', '新建应用']) {
      const control = headerActions.getByRole(action === '公共资源发布' ? 'link' : 'button', { name: action });
      await expect(control).toHaveCSS('white-space', 'nowrap');
      await expect(control).toBeInViewport();
    }
    await capture(page, 'OP-OPS-16-top', false);
    await expect(operations).toContainText('open-platform-golden-whoami');
    const mobileAudit = operations.getByTestId('open-platform-call-audit');
    await mobileAudit.scrollIntoViewIfNeeded();
    await expect(mobileAudit.locator('code').first()).toBeInViewport();
    // Settle the resized scroll container before capturing its painted content.
    await page.evaluate(() => new Promise<void>((resolve) => {
      requestAnimationFrame(() => requestAnimationFrame(() => resolve()));
    }));
    await capture(page, 'OP-OPS-16', false);
    await eventCatalog.scrollIntoViewIfNeeded();
    await captureLocator(eventCatalog, 'OP-PROTO-10');
    await expect
      .poll(() => webhookHealth.evaluate((element) => element.scrollWidth - element.clientWidth))
      .toBeLessThanOrEqual(1);
    await webhookHealth.scrollIntoViewIfNeeded();
    await captureLocator(webhookHealth, 'OP-PROTO-11');
    const preservedResponse = await page.request.get(`${WEB_BASE_URL}/api/open-platform/applications`);
    expect(preservedResponse.status()).toBe(200);
    const preserved = await preservedResponse.json();
    expect(String(preserved.code)).toBe('0');
    const preservedPids = (preserved.data as Array<{ pid: string }>).map((entry) => entry.pid);
    expect(preservedPids).toEqual(expect.arrayContaining(existingPids));
  });
});
