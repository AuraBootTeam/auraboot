import { expect, test } from '@playwright/test';
import { createCookieSessionStorage } from 'react-router';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { BACKEND_URL, BASE_URL as WEB_BASE_URL } from '../../helpers/environments';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';
import { verifyModelNotifications } from '../../helpers/open-platform-notifications';

// Secrets are captured only by explicit masked screenshots, never worker recordings.
test.use({ trace: 'off', video: 'off', screenshot: 'off' });

const EVIDENCE_DIR = path.join(process.env.AURA_EVIDENCE_ROOT || '/tmp', 'ui-golden-20260914');
const PUBLICATION_VIEWPORT = process.env.AURA_OPEN_PLATFORM_PUBLICATION_VIEWPORT || 'desktop';
if (!['desktop', 'narrow'].includes(PUBLICATION_VIEWPORT)) throw new Error('Unsupported publication viewport');
const publicationViewport = PUBLICATION_VIEWPORT === 'narrow' ? { width: 1024, height: 900 } : { width: 1440, height: 1000 };
const publicationLocale = process.env.AURA_OPEN_PLATFORM_PUBLICATION_LOCALE || 'zh-CN';
if (!['zh-CN', 'en-US'].includes(publicationLocale)) throw new Error('Unsupported publication locale');
const publicationEnglish: Record<string, string> = {
  "来源已不可用": "Source unavailable",
  "合同版本": "Schema version",
  "刷新目录": "Reload catalog",
  "正在加载发布配置…": "Loading publications…",
  "已发布的资源字段不能修改。请使用新的公共资源地址发布新合同，原合同将保持有效。": "Published resource fields cannot change. Publish a new contract at a new public resource address; the original contract stays active.",
  "公共资源发布": "Public Resource Publications",
  "公共查询发布": "Public Query Publications",
  "列表查询": "List",
  "详情读取": "Get",
  "创建记录": "Create",
  "修改记录": "Update",
  "删除记录": "Delete",
  "已发布": "Published",
  "已停用": "Withdrawn",
  "新建公共资源": "New public resource",
  "尚未公开业务模型": "No business models are public yet",
  "编辑合同": "Edit contract",
  "公共资源地址": "Public resource address",
  "查找业务模型": "Find business model",
  "查找": "Search",
  "业务模型": "Business model",
  "为每个来源选择已发布的列表资源；安装还需获得这些来源的列表和字段授权。虚拟模型自身的行约束会同时生效。": "Select a published list resource for each source. Installations also need its list and field grants. The virtual model row constraints apply as well.",
  "请选择该来源的已发布列表资源。": "Choose a published list resource for this source.",
  "所需权限": "Required scope",
  "机器数据范围": "Machine data policy",
  "对外字段名称": "Public field name",
  "对外类型": "Public type",
  "允许排序": "Sortable",
  "查询超时（毫秒）": "Query timeout (ms)",
  "创建通知": "Created",
  "保存并校验草稿": "Save and validate draft",
  "发布合同": "Publish contract",
  "下载 OpenAPI": "Download OpenAPI",
  "下载类型声明": "Download types",
  "停用资源": "Withdraw resource",
  "关闭编辑": "Close editor",
  "操作确认": "Confirm operation",
  "确认": "Confirm",
  "取消": "Cancel",
  "原对象属性": "Source property",
  "对外属性名称": "Public property name",
  "草稿已保存并通过来源校验": "Draft saved and source validation passed",
  "你的修改已保留": "Your edits are retained"
};
const publicationText = (cn: string) => {
  if (publicationLocale === 'zh-CN') return cn;
  if (!(cn in publicationEnglish)) throw new Error(`Missing English publication expectation: ${cn}`);
  return publicationEnglish[cn];
};
const queryLocale = process.env.AURA_OPEN_PLATFORM_QUERY_LOCALE || 'zh-CN';
if (!['zh-CN', 'en-US'].includes(queryLocale)) throw new Error('Unsupported query publication locale');
const QUERY_VIEWPORT = process.env.AURA_OPEN_PLATFORM_QUERY_VIEWPORT || 'desktop';
if (!['desktop', 'narrow'].includes(QUERY_VIEWPORT)) throw new Error('Unsupported query publication viewport');
const queryViewport = QUERY_VIEWPORT === 'narrow' ? { width: 1024, height: 900 } : { width: 1440, height: 1000 };
const queryEnglish: Record<string, string> = {
  "正在加载查询发布…": "Loading query publications…",
  "刷新目录": "Reload catalog",
  "查询已停用": "Query withdrawn",
  "合同版本": "Schema version",
  "已发布的查询输出不能修改。请使用新的公共查询地址发布新合同，原合同将保持有效。": "Published query outputs cannot change. Publish a new contract at a new public query address; the original contract stays active.",
  "关闭编辑": "Close editor",
  "编辑查询合同": "Edit query contract",
  "公共查询发布": "Public query publications",
  "查找": "Search",
  "可写": "Writable",
  "确认": "Confirm",
  "查询草稿已保存并通过来源校验": "Query draft saved and source validation passed",
  "已发布查询": "Query published",
  "新建公共查询": "New public query",
  "尚未公开命名查询": "No NamedQueries are public yet",
  "公共查询地址": "Public query address",
  "查找命名查询": "Find NamedQuery",
  "命名查询来源": "NamedQuery source",
  "查询执行权限": "Query execution scope",
  "参数类型": "Parameter type",
  "必填参数": "Required parameter",
  "对外输出名称": "Public output name",
  "输出类型": "Output type",
  "保存并校验查询草稿": "Save and validate query draft",
  "发布查询": "Publish query",
  "下载查询 OpenAPI": "Download query OpenAPI",
  "下载查询类型声明": "Download query types",
  "停用查询": "Withdraw query",
  "查询操作确认": "Confirm query operation",
  "取消": "Cancel",
  "原对象属性": "Source property",
  "对外属性名称": "Public property name",
  "执行预算": "Execution budgets",
  "单页上限": "Maximum page size",
  "结果总量上限": "Maximum result rows",
  "查询超时（毫秒）": "Query timeout (ms)",
  "同时执行上限": "Maximum concurrent executions"
};
const queryText = (cn: string) => {
  if (queryLocale === 'zh-CN') return cn;
  if (!(cn in queryEnglish)) throw new Error(`Missing English query expectation: ${cn}`);
  return queryEnglish[cn];
};
const UI_MUTATION = process.env.AURA_OPEN_PLATFORM_UI_MUTATION;
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

// A supplied acceptance plan is materialized before any browser capture, including all unresolved rows.
test.beforeAll(async () => {
  if (UI_MUTATION && !['scope-label-clipping', 'publication-overflow', 'intro-word-break', 'publication-title-locale', 'query-compatibility-feedback', 'query-confirmation-stale', 'major-grant-alias', 'resource-compatibility-feedback', 'resource-confirmation-stale', 'resource-catalog-feedback', 'resource-validation-feedback', 'resource-catalog-empty', 'resource-publish-conflict', 'sdk-ui-projection', 'protocol-visible-leak', 'editor-tab-order'].includes(UI_MUTATION)) throw new Error('Unsupported Open Platform UI mutation');
  if (['intro-word-break', 'publication-title-locale'].includes(UI_MUTATION || '') && publicationLocale !== 'en-US') throw new Error('English locale mutation requires en-US');
  if (!process.env.AURA_UI_ACCEPTANCE_MATRIX) return;
  if (!process.env.AURA_EVIDENCE_ROOT) throw new Error('UI acceptance requires a registered evidence round');
  const plan = JSON.parse(await readFile(process.env.AURA_UI_ACCEPTANCE_MATRIX, 'utf8'));
  expect(Array.isArray(plan.rows) && plan.rows.length >= 25).toBe(true);
  expect(new Set(plan.rows.map((row: { ID: string }) => row.ID)).size).toBe(plan.rows.length);
  for (const row of plan.rows) for (const field of ['ID', 'feature', 'actionPoint', 'state', 'expectation', 'driver', 'screenshotId', 'status']) {
    expect(typeof row[field] === 'string' && row[field].length > 0).toBe(true);
  }
  const columns = ['ID', 'feature', 'actionPoint', 'state', 'expectation', 'driver', 'screenshotId', 'status'];
  const markdown = ['| ' + columns.join(' | ') + ' |', '| ' + columns.map(() => '---').join(' | ') + ' |',
    ...plan.rows.map((row: Record<string, string>) => '| ' + columns.map(column => row[column].replaceAll('|', '/')).join(' | ') + ' |')].join('\n');
  await writeFile(path.join(process.env.AURA_EVIDENCE_ROOT, 'acceptance-matrix.json'), JSON.stringify(plan, null, 2) + '\n');
  await writeFile(path.join(process.env.AURA_EVIDENCE_ROOT, 'acceptance-matrix.md'), markdown + '\n');
});

test.describe('Dynamic resource publication journey', () => {
  let cleanupCodes: string[] = [];
  test.afterEach(async ({ page }) => {
    if (!cleanupCodes.length) return;
    // Failure cleanup owns only this test's publications; it cannot satisfy any UI assertion.
    const response = await page.request.get(`${WEB_BASE_URL}/api/open-platform/publications`);
    expect(response.status()).toBe(200); const catalog = await response.json(); expect(String(catalog.code)).toBe('0');
    for (const code of [...cleanupCodes].reverse()) {
      const view = catalog.data.find((entry: { code: string }) => entry.code === code);
      if (!view || view.status !== 'published') continue;
      const withdrawn = await page.request.post(`${WEB_BASE_URL}/api/open-platform/publications/${code}/withdraw`,
        { data: { expectedRevision: view.draftRevision } });
      expect(withdrawn.status()).toBe(200); expect(String((await withdrawn.json()).code)).toBe('0');
    }
  });
  test.setTimeout(180_000);
  test.describe.configure({ retries: 0 });
  test.use({ storageState: { cookies: [], origins: [] }, locale: publicationLocale, viewport: publicationViewport });

  test('publishes a real model contract through UI, retains conflicts, downloads and withdraws', async ({ page }) => {
    await authenticate(page, publicationLocale);
    const suffix = String(Date.now());
    const modelCode = `dop_ui_${suffix}`;
    const modelName = `接入验证零件 ${suffix}`;
    const resourceCode = `ui.parts.${suffix}`;
    cleanupCodes = [resourceCode, `ui.virtual-parts.${suffix}`];
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
    const snapshot = async (id: string) => {
      if (id === 'publication-entry' && publicationLocale === 'en-US') {
        const intro = page.getByTestId('open-platform-intro');
        if (UI_MUTATION === 'intro-word-break') await intro.evaluate(element => { element.style.wordBreak = 'break-all'; });
        await expect(intro).toHaveCSS('word-break', 'normal');
      }
      if (UI_MUTATION === 'publication-overflow') await page.addStyleTag({ content: 'html { min-width: 1440px !important; }' });
      if (PUBLICATION_VIEWPORT === 'narrow') {
        expect(page.viewportSize()).toEqual(publicationViewport);
        expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(1);
      }
      await page.screenshot({ path: path.join(screenshotRoot, `${id}.png`), fullPage: true });
    };
    await page.goto('/'); await openAccountMenu(page); await page.getByTestId('open-platform-link').click();
    await expect(page.getByTestId('open-platform-page')).toBeVisible();
    await expect(page.getByTestId('open-platform-publications-link')).toBeVisible();
    await snapshot('publication-entry');
    // Catalog GETs are declared loading/empty/error presentation fixtures; publication writes stay real.
    let releaseCatalog!: () => void;
    const catalogGate = new Promise<void>(resolve => { releaseCatalog = resolve; });
    await page.route('**/api/open-platform/publications', async route => {
      expect(route.request().method()).toBe('GET'); await catalogGate;
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: '0', data: [] }) });
    });
    await page.getByTestId('open-platform-publications-link').click();
    await expect(page.getByTestId('publication-editor')).toBeVisible();
    try {
      await expect(page.getByTestId('publication-editor').getByRole('status')).toHaveText(publicationText("正在加载发布配置…"));
      await snapshot('publication-loading');
    } finally { releaseCatalog(); }
    if (UI_MUTATION === 'publication-title-locale') {
      await page.getByRole('heading', { name: publicationText("公共资源发布"), exact: true }).evaluate(element => { element.textContent = '公共资源发布'; });
    }
    await expect(page.getByRole('heading', { name: publicationText("公共资源发布"), exact: true })).toBeVisible();
    await expect(page.getByRole('heading', { name: publicationText("尚未公开业务模型") })).toBeVisible();
    await snapshot('publication-empty');
    await page.unroute('**/api/open-platform/publications');
    const catalogProblem = publicationLocale === 'zh-CN' ? '目录暂时不可用，请重试。' : 'Publication catalog unavailable. Retry.';
    await page.route('**/api/open-platform/publications', async route => {
      expect(route.request().method()).toBe('GET');
      await route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ code: '503', message: catalogProblem }) });
    });
    await page.getByRole('button', { name: publicationText("刷新目录"), exact: true }).click();
    const catalogAlert = page.getByTestId('publication-editor').getByRole('alert');
    await expect(catalogAlert).toBeVisible();
    if (UI_MUTATION === 'resource-catalog-feedback') await catalogAlert.evaluate(element => { element.textContent = 'Catalog loaded'; });
    await expect(catalogAlert).toHaveText(catalogProblem);
    if (UI_MUTATION === 'resource-catalog-empty') await catalogAlert.evaluate(element => { const heading = document.createElement('h3'); heading.textContent = '尚未公开业务模型'; element.parentElement?.append(heading); });
    await expect(page.getByRole('heading', { name: publicationText("尚未公开业务模型"), exact: true })).toHaveCount(0);
    await snapshot('publication-failure');
    await page.unroute('**/api/open-platform/publications');
    const realCatalogResponse = page.waitForResponse(response => new URL(response.url()).pathname === '/api/open-platform/publications' && response.request().method() === 'GET');
    await page.getByRole('button', { name: publicationText("刷新目录"), exact: true }).click();
    const realCatalogHttp = await realCatalogResponse; expect(realCatalogHttp.status()).toBe(200);
    const realCatalog = await realCatalogHttp.json(); expect(String(realCatalog.code)).toBe('0');
    await expect(catalogAlert).toHaveCount(0);
    expect(Array.isArray(realCatalog.data)).toBe(true);
    expect(realCatalog.data.some((entry: { code: string }) => entry.code === resourceCode)).toBe(false);
    await page.getByRole('button', { name: publicationText("新建公共资源"), exact: true }).click();
    await verifyEditorTabOrder(page, [
      [page.getByLabel(publicationText("公共资源地址"), { exact: true }), publicationText("公共资源地址")],
      [page.getByLabel(publicationText("合同版本"), { exact: true }), publicationText("合同版本")],
      [page.getByLabel(publicationText("查找业务模型"), { exact: true }), publicationText("查找业务模型")],
      [page.getByRole('button', { name: publicationText("查找"), exact: true }), publicationText("查找")],
      [page.getByLabel(publicationText("业务模型"), { exact: true }), publicationText("业务模型")],
    ], 'publication-source-keyboard');
    await page.getByLabel(publicationText("公共资源地址"), { exact: true }).fill(resourceCode);
    await page.getByLabel(publicationText("查找业务模型"), { exact: true }).fill(modelName);
    await page.getByRole('button', { name: publicationText("查找"), exact: true }).click();
    await expect(page.getByLabel(publicationText("业务模型"), { exact: true }).getByRole('option', { name: modelName, exact: true })).toHaveCount(1);
    const metadataResponse = page.waitForResponse(response => response.url().includes(`/publication-sources/${modelCode}`) && response.status() === 200);
    await page.getByLabel(publicationText("业务模型"), { exact: true }).selectOption({ label: modelName });
    const metadata = await (await metadataResponse).json();
    expect(String(metadata.code)).toBe('0');
    expect(metadata.data.operations).toEqual(expect.arrayContaining(['list', 'get', 'create', 'update', 'delete']));
    expect(metadata.data.fields.some((field: { code: string }) => ['id', 'tenant_id', 'row_version'].includes(field.code))).toBe(false);
    await expect(page.getByLabel(publicationText("列表查询"), { exact: true })).toBeEnabled();
    await snapshot('publication-source');
    for (const operation of [publicationText("列表查询"), publicationText("详情读取"), publicationText("创建记录"), publicationText("修改记录"), publicationText("删除记录")]) await page.getByLabel(operation, { exact: true }).check();
    const scopes = page.getByLabel(publicationText("所需权限"), { exact: true });
    for (const [index, operation] of ['list', 'get', 'create', 'update', 'delete'].entries()) await scopes.nth(index).fill(`dop.ui:${operation}`);
    await page.getByLabel(publicationText("机器数据范围"), { exact: true }).selectOption('tenant_wide');
    const nameField = page.getByTestId('publication-field-dop_ui_name');
    await nameField.getByLabel('零件名称', { exact: true }).check();
    await nameField.getByLabel(publicationText("对外字段名称"), { exact: true }).fill('name');
    await nameField.getByLabel('EQ', { exact: true }).check();
    await nameField.getByLabel(publicationText("允许排序"), { exact: true }).check();
    const detailsField = page.getByTestId('publication-field-dop_ui_details');
    await detailsField.getByLabel('联系信息', { exact: true }).check();
    await detailsField.getByLabel(publicationText("对外字段名称"), { exact: true }).fill('details');
    await detailsField.getByLabel(publicationText("对外类型"), { exact: true }).selectOption('object');
    await detailsField.getByLabel(publicationText("原对象属性"), { exact: true }).fill('contact_name');
    await detailsField.getByLabel(publicationText("对外属性名称"), { exact: true }).fill('contactName');
    await page.getByLabel(publicationText("创建通知"), { exact: true }).check();
    await verifyDraftCancel(page, page.getByRole('button', { name: publicationText("关闭编辑"), exact: true }),
      page.getByRole('group', { name: publicationText("操作确认"), exact: true }),
      nameField.getByLabel(publicationText("对外字段名称"), { exact: true }), 'name',
      publicationText("确认"), publicationText("取消"), 'publication-discard-confirm', `/api/open-platform/publications/${resourceCode}`);
    await snapshot('publication-fields');
    await nameField.scrollIntoViewIfNeeded();
    await nameField.screenshot({ path: path.join(screenshotRoot, 'publication-name-field-region.png') });
    await detailsField.scrollIntoViewIfNeeded();
    await detailsField.screenshot({ path: path.join(screenshotRoot, 'publication-fields-region.png') });
    const saveButton = page.getByRole('button', { name: publicationText("保存并校验草稿"), exact: true });
    await saveButton.hover();
    const saveContrast = await saveButton.evaluate(element => {
      const luminance = (color: string) => (color.match(/[\d.]+/g) ?? []).slice(0, 3).map(Number)
        .map(channel => channel / 255).map(channel => channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4)
        .reduce((sum, channel, index) => sum + channel * [0.2126, 0.7152, 0.0722][index], 0);
      const style = getComputedStyle(element); const foreground = luminance(style.color); const background = luminance(style.backgroundColor);
      return (Math.max(foreground, background) + 0.05) / (Math.min(foreground, background) + 0.05);
    });
    expect(saveContrast).toBeGreaterThanOrEqual(4.5);
    const requiredScope = page.getByLabel(publicationText("所需权限"), { exact: true }).first();
    await requiredScope.fill(''); await saveButton.click();
    expect(await requiredScope.evaluate(element => (element as HTMLInputElement).validity.valueMissing)).toBe(true);
    await expect(requiredScope).toBeFocused();
    expect(await requiredScope.evaluate(element => (element as HTMLInputElement).validationMessage)).not.toBe('');
    const invalidScopeRegion = requiredScope.locator('..');
    await expect(requiredScope).toHaveAttribute('aria-invalid', 'true');
    const requiredMessage = publicationLocale === 'zh-CN' ? '请填写所需权限。' : 'Enter Required scope.';
    if (UI_MUTATION === 'resource-validation-feedback') await invalidScopeRegion.getByRole('alert').evaluate(element => { element.textContent = 'Please fill out this field.'; });
    await expect(invalidScopeRegion.getByRole('alert')).toHaveText(requiredMessage);
    await expect(requiredScope).toHaveAttribute('aria-describedby', await invalidScopeRegion.getByRole('alert').getAttribute('id') || '');
    await expect(requiredScope).toHaveAccessibleDescription(requiredMessage);
    await test.info().attach('required-scope-accessibility', { contentType: 'application/json',
      body: JSON.stringify({ schemaVersion: 1, driver: 'browser-native-validation',
        cases: ['invalid-focus', 'linked-error', 'accessible-description'] }) });
    await invalidScopeRegion.evaluate(element => element.scrollIntoView({ block: 'center' }));
    await expect(invalidScopeRegion).toBeInViewport({ ratio: 1 }); await snapshot('publication-invalid');
    await invalidScopeRegion.screenshot({ path: path.join(screenshotRoot, 'publication-invalid-scope-region.png') });
    const beforeValidSave = await page.request.get(`${WEB_BASE_URL}/api/open-platform/publications`);
    expect(beforeValidSave.status()).toBe(200); const beforeValidCatalog = await beforeValidSave.json();
    expect(String(beforeValidCatalog.code)).toBe('0');
    expect(beforeValidCatalog.data.some((row: { code: string }) => row.code === resourceCode)).toBe(false);
    await requiredScope.fill('dop.ui:list');
    await expect(invalidScopeRegion.getByRole('alert')).toHaveCount(0);
    await expect(requiredScope).not.toHaveAttribute('aria-invalid', 'true');
    const savedResponse = page.waitForResponse(response => response.url().endsWith(`/publications/${resourceCode}/draft`) && response.request().method() === 'PUT');
    await activateWithEnter(page.getByRole('button', { name: publicationText("保存并校验草稿"), exact: true }));
    const savedHttp = await savedResponse; expect(savedHttp.status()).toBe(200);
    const saved = await savedHttp.json(); expect(String(saved.code)).toBe('0');
    expect(saved.data.draft.fields.name).toMatchObject({ source: 'dop_ui_name', writable: true, required: true, sortable: true, operators: ['EQ'] });
    expect(saved.data.draft.fields.details).toMatchObject({ source: 'dop_ui_details', type: 'object', properties: { contactName: { source: 'contact_name', type: 'string' } } });
    expect(saved.data.draftRevision).toBe(1); expect(saved.data.activeVersion).toBeNull();
    await expect(page.getByTestId('publication-editor').getByRole('status')).toContainText(publicationText("草稿已保存并通过来源校验"));
    await expect(page.getByTestId('publication-editor').getByRole('status')).toBeInViewport();
    await snapshot('publication-draft');

    const savedTimeout = String(saved.data.draft.timeoutMs);
    const resourceTimeout = page.getByLabel(publicationText("查询超时（毫秒）"), { exact: true });
    await resourceTimeout.fill(String(Number(savedTimeout) + 1));
    await verifyConfirmedDiscard(page, page.getByRole('button', { name: publicationText("关闭编辑"), exact: true }),
      page.getByRole('group', { name: publicationText("操作确认"), exact: true }), publicationText("确认"),
      page.getByTestId('publication-editor').getByRole('listitem').filter({ has: page.getByText(resourceCode, { exact: true }) })
        .getByRole('button', { name: publicationText("编辑合同"), exact: true }),
      resourceTimeout, savedTimeout, 'publication-confirmed-discard', `/api/open-platform/publications/${resourceCode}`);

    const otherEditor = await page.context().newPage();
    await otherEditor.goto('/p/c/open_platform_publications');
    await otherEditor.getByTestId('publication-editor').getByRole('listitem')
      .filter({ has: otherEditor.getByText(resourceCode, { exact: true }) })
      .getByRole('button', { name: publicationText("编辑合同"), exact: true }).click();
    await expect(otherEditor.getByLabel(publicationText("查询超时（毫秒）"), { exact: true })).toBeEnabled();
    await otherEditor.getByLabel(publicationText("查询超时（毫秒）"), { exact: true }).fill('6000');
    await activateWithEnter(otherEditor.getByRole('button', { name: publicationText("保存并校验草稿"), exact: true }));
    await expect(otherEditor.getByTestId('publication-editor').getByRole('status')).toContainText(publicationText("草稿已保存并通过来源校验"));
    await otherEditor.close();
    // The still-clean original window must not publish the revision replaced by another editor.
    await activateWithEnter(page.getByRole('button', { name: publicationText("发布合同"), exact: true }));
    const stalePublishConfirmation = page.getByRole('group', { name: publicationText("操作确认"), exact: true });
    await expect(stalePublishConfirmation).toBeVisible();
    const stalePublishResponse = page.waitForResponse(response => response.url().endsWith(`/publications/${resourceCode}/publish`) && response.request().method() === 'POST');
    await activateWithEnter(stalePublishConfirmation.getByRole('button', { name: publicationText("确认"), exact: true }));
    const stalePublish = await stalePublishResponse;
    expect(stalePublish.status()).toBe(409);
    expect(stalePublish.request().postDataJSON()).toEqual({ expectedRevision: 1 });
    expect(String((await stalePublish.json()).code)).toBe('409');
    await expect(stalePublishConfirmation).toHaveCount(0);
    const stalePublishAlert = page.getByTestId('publication-editor').getByRole('alert');
    if (UI_MUTATION === 'resource-publish-conflict') await stalePublishAlert.evaluate(element => { element.textContent = 'Published.'; });
    await expect(stalePublishAlert).toContainText(publicationText("你的修改已保留"));
    await expect(page.getByLabel(publicationText("查询超时（毫秒）"), { exact: true })).toHaveValue(String(saved.data.draft.timeoutMs));
    const afterStalePublish = await page.request.get(`${WEB_BASE_URL}/api/open-platform/publications`);
    expect(afterStalePublish.status()).toBe(200); const afterStaleCatalog = await afterStalePublish.json();
    expect(String(afterStaleCatalog.code)).toBe('0');
    expect(afterStaleCatalog.data.find((entry: { code: string }) => entry.code === resourceCode)).toMatchObject({ activeVersion: null, draftRevision: 2, draft: { timeoutMs: 6000 } });
    await stalePublishAlert.evaluate(element => element.scrollIntoView({ block: 'center' }));
    await expect(stalePublishAlert).toBeInViewport({ ratio: 1 }); await snapshot('publication-publish-conflict');
    await page.getByLabel(publicationText("查询超时（毫秒）"), { exact: true }).fill('7000');
    const conflictResponse = page.waitForResponse(response => response.url().endsWith(`/publications/${resourceCode}/draft`) && response.status() === 409);
    await activateWithEnter(page.getByRole('button', { name: publicationText("保存并校验草稿"), exact: true })); await conflictResponse;
    await expect(page.getByTestId('publication-editor').getByRole('alert')).toContainText(publicationText("你的修改已保留"));
    await expect(page.getByLabel(publicationText("查询超时（毫秒）"), { exact: true })).toHaveValue('7000');
    await expect(page.getByTestId('publication-editor').getByRole('alert')).toBeInViewport();
    const conflictActions = page.getByTestId('publication-editor').getByRole('button', { name: publicationText("关闭编辑"), exact: true }).locator('..');
    await conflictActions.scrollIntoViewIfNeeded();
    await expect(conflictActions).toBeInViewport({ ratio: 1 });
    await expect(page.getByTestId('publication-editor').getByRole('alert')).toBeInViewport({ ratio: 1 });
    await saveButton.evaluate(element => element.scrollIntoView({ block: 'center' }));
    await expect(saveButton).toBeInViewport({ ratio: 1 });
    await snapshot('publication-conflict');
    await page.getByRole('button', { name: publicationText("关闭编辑"), exact: true }).click();
    await activateWithEnter(page.getByRole('group', { name: publicationText("操作确认"), exact: true }).getByRole('button', { name: publicationText("确认"), exact: true }));
    const ownContractEdit = page.getByTestId('publication-editor').getByRole('listitem')
      .filter({ has: page.getByText(resourceCode, { exact: true }) })
      .getByRole('button', { name: publicationText("编辑合同"), exact: true });
    await expect(ownContractEdit).toBeEnabled();
    await ownContractEdit.click();
    await expect(page.getByLabel(publicationText("查询超时（毫秒）"), { exact: true })).toHaveValue('6000');
    await activateWithEnter(page.getByRole('button', { name: publicationText("发布合同"), exact: true }));
    await activateWithEnter(page.getByRole('group', { name: publicationText("操作确认"), exact: true }).getByRole('button', { name: publicationText("确认"), exact: true }));
    await expect(page.getByTestId('publication-editor').getByRole('status')).toContainText(publicationText("已发布"));
    await expect(page.getByTestId('publication-editor').getByRole('status')).toBeInViewport();
    await snapshot('publication-publish');
    for (const [button, extension] of [[publicationText("下载 OpenAPI"), '.openapi.json'], [publicationText("下载类型声明"), '.d.ts']] as const) {
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
    const originalOpenApiResponse = await page.request.get(`${WEB_BASE_URL}/api/open-platform/publications/${resourceCode}/openapi`);
    expect(originalOpenApiResponse.status()).toBe(200); const originalOpenApi = await originalOpenApiResponse.json();
    await nameField.getByLabel(publicationText("对外字段名称"), { exact: true }).fill('displayName');
    await page.getByLabel(publicationText("合同版本"), { exact: true }).fill('2');
    const breakingSave = page.waitForResponse(reply => reply.url().endsWith(`/publications/${resourceCode}/draft`) && reply.request().method() === 'PUT');
    await saveButton.click(); const breakingSaved = await breakingSave; expect(breakingSaved.status()).toBe(200);
    const breakingDraft = await breakingSaved.json(); expect(breakingDraft.data).toMatchObject({ activeVersion: 1, draftRevision: 3 });
    expect(breakingDraft.data.draft.fields.displayName.source).toBe('dop_ui_name'); expect(breakingDraft.data.draft.fields.name).toBeUndefined();
    await expect(page.getByTestId('publication-editor').getByRole('status')).toContainText(publicationText("草稿已保存并通过来源校验"));
    await snapshot('publication-major-draft');
    await activateWithEnter(page.getByRole('button', { name: publicationText("发布合同"), exact: true }));
    const incompatibleConfirm = page.getByRole('group', { name: publicationText("操作确认"), exact: true });
    await expect(incompatibleConfirm).toBeInViewport(); await snapshot('publication-major-confirm');
    const incompatibleMarkup = await incompatibleConfirm.evaluate(element => element.outerHTML);
    const incompatibleResponse = page.waitForResponse(reply => reply.url().endsWith(`/publications/${resourceCode}/publish`) && reply.request().method() === 'POST');
    await activateWithEnter(incompatibleConfirm.getByRole('button', { name: publicationText("确认"), exact: true }));
    const incompatible = await incompatibleResponse; expect(incompatible.status()).toBe(400);
    expect(await incompatible.json()).toMatchObject({ code: '400', message: 'Existing public field contract cannot change: name' });
    if (UI_MUTATION === 'resource-confirmation-stale') await page.getByTestId('publication-editor').evaluate((element, markup) => element.insertAdjacentHTML('beforeend', markup), incompatibleMarkup);
    await expect(page.getByRole('group', { name: publicationText("操作确认"), exact: true })).toHaveCount(0);
    if (UI_MUTATION === 'resource-compatibility-feedback') await page.getByTestId('publication-editor').getByRole('alert').evaluate(element => { element.textContent = 'Publication succeeded'; });
    await expect(page.getByTestId('publication-editor').getByRole('alert')).toHaveText(publicationText("已发布的资源字段不能修改。请使用新的公共资源地址发布新合同，原合同将保持有效。"));
    await expect(nameField.getByLabel(publicationText("对外字段名称"), { exact: true })).toHaveValue('displayName');
    await snapshot('publication-major-rejected');
    const afterRejected = await page.request.get(`${WEB_BASE_URL}/api/open-platform/publications/${resourceCode}/openapi`);
    expect(afterRejected.status()).toBe(200); expect(await afterRejected.json()).toEqual(originalOpenApi);
    await nameField.getByLabel(publicationText("对外字段名称"), { exact: true }).fill('name');
    await page.getByLabel(publicationText("合同版本"), { exact: true }).fill('1');
    const repairResponse = page.waitForResponse(reply => reply.url().endsWith(`/publications/${resourceCode}/draft`) && reply.request().method() === 'PUT');
    await saveButton.click(); const repaired = await repairResponse; expect(repaired.status()).toBe(200);
    expect((await repaired.json()).data).toMatchObject({ activeVersion: 1, draftRevision: 4 });
    await page.getByRole('button', { name: publicationText("关闭编辑"), exact: true }).click();
    const majorCode = resourceCode + '.v2'; cleanupCodes.push(majorCode);
    await page.getByRole('button', { name: publicationText("新建公共资源"), exact: true }).click();
    await page.getByLabel(publicationText("公共资源地址"), { exact: true }).fill(majorCode);
    await page.getByLabel(publicationText("查找业务模型"), { exact: true }).fill(modelName);
    await page.getByRole('button', { name: publicationText("查找"), exact: true }).click();
    await page.getByLabel(publicationText("业务模型"), { exact: true }).selectOption({ label: modelName });
    for (const operation of [publicationText("列表查询"), publicationText("详情读取"), publicationText("创建记录"), publicationText("修改记录"), publicationText("删除记录")]) await page.getByLabel(operation, { exact: true }).check();
    for (const [index, operation] of ['list', 'get', 'create', 'update', 'delete'].entries()) await page.getByLabel(publicationText("所需权限"), { exact: true }).nth(index).fill(`dop.ui:${operation}`);
    await page.getByLabel(publicationText("机器数据范围"), { exact: true }).selectOption('tenant_wide');
    await nameField.getByLabel('零件名称', { exact: true }).check();
    await nameField.getByLabel(publicationText("对外字段名称"), { exact: true }).fill('displayName');
    await nameField.getByLabel('EQ', { exact: true }).check(); await nameField.getByLabel(publicationText("允许排序"), { exact: true }).check();
    await detailsField.getByLabel('联系信息', { exact: true }).check();
    await detailsField.getByLabel(publicationText("对外字段名称"), { exact: true }).fill('details');
    await detailsField.getByLabel(publicationText("对外类型"), { exact: true }).selectOption('object');
    await detailsField.getByLabel(publicationText("原对象属性"), { exact: true }).fill('contact_name');
    await detailsField.getByLabel(publicationText("对外属性名称"), { exact: true }).fill('contactName');
    await page.getByLabel(publicationText("创建通知"), { exact: true }).check();
    const majorSave = page.waitForResponse(reply => reply.url().endsWith(`/publications/${majorCode}/draft`) && reply.request().method() === 'PUT');
    await saveButton.click(); const savedMajor = await majorSave; expect(savedMajor.status()).toBe(200);
    expect((await savedMajor.json()).data).toMatchObject({ activeVersion: null, draftRevision: 1, draft: { code: majorCode, schemaVersion: 1 } });
    const majorPublish = page.waitForResponse(reply => reply.url().endsWith(`/publications/${majorCode}/publish`) && reply.request().method() === 'POST');
    await activateWithEnter(page.getByRole('button', { name: publicationText("发布合同"), exact: true }));
    await activateWithEnter(page.getByRole('group', { name: publicationText("操作确认"), exact: true }).getByRole('button', { name: publicationText("确认"), exact: true }));
    const publishedMajor = await majorPublish; expect(publishedMajor.status()).toBe(200);
    expect((await publishedMajor.json()).data).toMatchObject({ activeVersion: 1, draftRevision: 1, status: 'published' });
    await expect(page.getByTestId('publication-editor').getByRole('status')).toContainText(publicationText("已发布"));
    await snapshot('publication-major-published');
    const majorDocumentResponse = await page.request.get(`${WEB_BASE_URL}/api/open-platform/publications/${majorCode}/openapi`);
    expect(majorDocumentResponse.status()).toBe(200); const majorDocument = await majorDocumentResponse.json();
    expect(majorDocument.paths[`/api/open/v1/resources/${majorCode}`].post).toBeDefined();
    expect(majorDocument.components.schemas.Resource.properties.displayName).toBeDefined(); expect(majorDocument.components.schemas.Resource.properties.name).toBeUndefined();
    const originalStillPublished = await page.request.get(`${WEB_BASE_URL}/api/open-platform/publications/${resourceCode}/openapi`);
    expect(originalStillPublished.status()).toBe(200); expect(await originalStillPublished.json()).toEqual(originalOpenApi);
    // Model/query authoring remains fixture setup; every virtual publication mutation uses the real UI.
    const virtualCode = `ui.virtual-parts.${suffix}`; const virtualModel = `dop_uv_${suffix}`;
    const virtualName = `虚拟接入零件 ${suffix}`; const virtualQuery = `dop_uv_query_${suffix}`;
    const queryResponse = await page.request.post(`${WEB_BASE_URL}/api/meta/named-queries`, { data: {
      code: virtualQuery, title: virtualName, resourceCode: modelCode, actionCode: 'read', fromSql: `${created.data.tableName} a`,
      defaultOrder: [{ field: 'pid', direction: 'asc' }], fields: [
        { fieldCode: 'pid', columnExpr: 'a.pid', dataType: 'string', displayName: '记录标识', sortable: true, searchable: false },
        { fieldCode: 'name', columnExpr: 'a.dop_ui_name', dataType: 'string', displayName: '零件名称', sortable: true, searchable: false },
      ],
    } });
    expect(queryResponse.status()).toBe(200); const virtualSource = await queryResponse.json(); expect(String(virtualSource.code)).toBe('0');
    for (const status of ['testing', 'published']) {
      const response = await page.request.put(`${WEB_BASE_URL}/api/meta/named-queries/${virtualSource.data.pid}/status`, { data: { status } });
      expect(response.status()).toBe(200); expect(String((await response.json()).code)).toBe('0');
    }
    const virtualResponse = await page.request.post(`${WEB_BASE_URL}/api/meta/models`, { data: {
      code: virtualModel, displayName: virtualName, modelType: 'view', sourceType: 'namedQuery', sourceRef: virtualQuery, primaryKey: 'pid',
      capabilities: { list: true, detail: true, filter: true, sort: true, paginate: true },
      fields: [{ code: 'name', displayName: '零件名称', dataType: 'string', filterable: true, sortable: true }],
    } });
    expect(virtualResponse.status()).toBe(200); const virtual = await virtualResponse.json(); expect(String(virtual.code)).toBe('0');
    const virtualPublished = await page.request.post(`${WEB_BASE_URL}/api/meta/models/${virtual.data.pid}/publish`, { data: { impactAcknowledged: true } });
    expect(virtualPublished.status()).toBe(200); expect(String((await virtualPublished.json()).code)).toBe('0');
    await page.getByRole('button', { name: publicationText("关闭编辑"), exact: true }).click();
    await page.getByRole('button', { name: publicationText("新建公共资源"), exact: true }).click();
    await page.getByLabel(publicationText("公共资源地址"), { exact: true }).fill(virtualCode);
    await page.getByLabel(publicationText("查找业务模型"), { exact: true }).fill(virtualName);
    await page.getByRole('button', { name: publicationText("查找"), exact: true }).click();
    await expect(page.getByLabel(publicationText("业务模型"), { exact: true }).getByRole('option', { name: virtualName, exact: true })).toHaveCount(1);
    await page.getByLabel(publicationText("业务模型"), { exact: true }).selectOption({ label: virtualName });
    const sourceSelector = page.getByTestId(`publication-source-${modelCode}`).getByRole('combobox');
    await expect(sourceSelector).toHaveValue('');
    await expect(sourceSelector.getByRole('option', { name: `${resourceCode} (v1)`, exact: true })).toHaveCount(1);
    await sourceSelector.selectOption(resourceCode);
    await expect(page.getByText(publicationText("为每个来源选择已发布的列表资源；安装还需获得这些来源的列表和字段授权。虚拟模型自身的行约束会同时生效。"), { exact: true })).toBeVisible();
    await expect(page.getByLabel(publicationText("创建记录"), { exact: true })).toBeDisabled();
    await expect(page.getByLabel(publicationText("修改记录"), { exact: true })).toBeDisabled();
    for (const operation of [publicationText("列表查询"), publicationText("详情读取")]) await page.getByLabel(operation, { exact: true }).check();
    await page.getByLabel(publicationText("所需权限"), { exact: true }).nth(0).fill('dop.ui:list');
    await page.getByLabel(publicationText("所需权限"), { exact: true }).nth(1).fill('dop.ui:get');
    await page.getByLabel(publicationText("机器数据范围"), { exact: true }).selectOption('tenant_wide');
    const virtualNameField = page.getByTestId('publication-field-name');
    await virtualNameField.getByLabel('零件名称', { exact: true }).check();
    await virtualNameField.getByLabel(publicationText("对外字段名称"), { exact: true }).fill('name');
    if (process.env.AURA_UI_NEGATIVE_MUTATION && !['fill-required-source', 'skip-source-keyboard'].includes(process.env.AURA_UI_NEGATIVE_MUTATION)) {
      // Expected-red only: falsify persisted projection assertions using the actual UI.
      expect(process.env.AURA_UI_NEGATIVE_MUTATION).toBe('drop-virtual-output');
      await virtualNameField.getByLabel('零件名称', { exact: true }).uncheck();
    }
    await snapshot('publication-virtual-source');
    await page.getByTestId('publication-editor').locator('section').filter({ has: sourceSelector })
      .screenshot({ path: path.join(screenshotRoot, 'publication-virtual-source-region.png') });
    // Native required validation must stop the actual submit, not merely display an empty selector.
    await sourceSelector.selectOption('');
    if (process.env.AURA_UI_NEGATIVE_MUTATION === 'fill-required-source') await sourceSelector.selectOption(resourceCode);
    const prematureDraftWrites: string[] = [];
    const observeDraftWrite = (request: import('@playwright/test').Request) => {
      if (request.method() === 'PUT' && request.url().endsWith(`/publications/${virtualCode}/draft`)) prematureDraftWrites.push(request.url());
    };
    page.on('request', observeDraftWrite);
    await activateWithEnter(page.getByRole('button', { name: publicationText("保存并校验草稿"), exact: true }));
    expect(await sourceSelector.evaluate((element: HTMLSelectElement) => element.validity.valueMissing)).toBe(true);
    await expect(sourceSelector).toBeFocused();
    const requiredSourceError = page.getByTestId(`publication-source-${modelCode}`).getByRole('alert');
    await expect(requiredSourceError).toHaveText(publicationText("请选择该来源的已发布列表资源。"));
    await expect(requiredSourceError).toBeVisible();
    await expect(requiredSourceError).toBeInViewport();
    await expect(sourceSelector).toHaveAttribute('aria-invalid', 'true');
    await expect(sourceSelector).toHaveAttribute('aria-describedby', `publication-source-error-${modelCode}`);
    await activateWithEnter(page.getByRole('button', { name: publicationText("保存并校验草稿"), exact: true }));
    await expect(sourceSelector).toBeFocused();
    expect(prematureDraftWrites).toEqual([]);
    page.off('request', observeDraftWrite);
    const blockedCatalog = await page.request.get(`${WEB_BASE_URL}/api/open-platform/publications`);
    expect(blockedCatalog.status()).toBe(200); const blockedBody = await blockedCatalog.json(); expect(String(blockedBody.code)).toBe('0');
    expect(blockedBody.data.some((entry: { code: string }) => entry.code === virtualCode)).toBe(false);
    await snapshot('publication-virtual-source-required');
    await page.getByTestId('publication-editor').locator('section').filter({ has: sourceSelector })
      .screenshot({ path: path.join(screenshotRoot, 'publication-virtual-source-required-region.png') });
    // Native typeahead recovers the source through keyboard events on the current browser.
    await expect(sourceSelector).toBeFocused();
    if (process.env.AURA_UI_NEGATIVE_MUTATION !== 'skip-source-keyboard') await page.keyboard.type(resourceCode);
    await expect(sourceSelector).toHaveValue(resourceCode);
    await expect(requiredSourceError).toHaveCount(0);
    await expect(sourceSelector).toHaveAttribute('aria-invalid', 'false');
    await sourceSelector.press('Tab');
    await expect(page.getByLabel(publicationText("列表查询"), { exact: true })).toBeFocused();
    await page.keyboard.press('Shift+Tab');
    await expect(sourceSelector).toBeFocused();
    await page.getByTestId('publication-editor').locator('section').filter({ has: sourceSelector })
      .screenshot({ path: path.join(screenshotRoot, 'publication-virtual-source-keyboard-region.png') });
    const virtualSaved = page.waitForResponse(response => response.url().endsWith(`/publications/${virtualCode}/draft`) && response.request().method() === 'PUT');
    await activateWithEnter(page.getByRole('button', { name: publicationText("保存并校验草稿"), exact: true }));
    const virtualSavedHttp = await virtualSaved; expect(virtualSavedHttp.status()).toBe(200);
    const virtualDraft = await virtualSavedHttp.json(); expect(String(virtualDraft.code)).toBe('0');
    expect(virtualDraft.data.draft.sourceResources).toEqual({ [modelCode]: resourceCode });
    expect(virtualDraft.data.draft.fields.name).toMatchObject({ source: 'name', readable: true, writable: false });
    await activateWithEnter(page.getByRole('button', { name: publicationText("发布合同"), exact: true }));
    await activateWithEnter(page.getByRole('group', { name: publicationText("操作确认"), exact: true }).getByRole('button', { name: publicationText("确认"), exact: true }));
    await expect(page.getByTestId('publication-editor').getByRole('status')).toContainText(publicationText("已发布"));
    await expect(sourceSelector).toBeDisabled();
    await page.getByRole('button', { name: publicationText("关闭编辑"), exact: true }).click();
    await page.getByTestId('publication-editor').getByRole('listitem').filter({ has: page.getByText(virtualCode, { exact: true }) })
      .getByRole('button', { name: publicationText("编辑合同"), exact: true }).click();
    await expect(sourceSelector).toHaveValue(resourceCode); await expect(sourceSelector).toBeDisabled();
    await snapshot('publication-virtual-published');
    await page.getByTestId('publication-editor').locator('section').filter({ has: sourceSelector })
      .screenshot({ path: path.join(screenshotRoot, 'publication-virtual-published-region.png') });
    const virtualState = await page.request.get(`${WEB_BASE_URL}/api/open-platform/publications`);
    expect(virtualState.status()).toBe(200); const virtualCatalog = await virtualState.json(); expect(String(virtualCatalog.code)).toBe('0');
    expect(virtualCatalog.data.find((view: { code: string }) => view.code === virtualCode)).toMatchObject({ status: 'published', activeVersion: 1,
      draft: { sourceResources: { [modelCode]: resourceCode } } });
    // Consumption uses the contract configured above; app/row/grant authoring is fixture setup.
    const human = async (method: string, url: string, data?: unknown) => {
      const response = await page.request.fetch(`${WEB_BASE_URL}${url}`, { method, data });
      expect(response.status(), `${method} ${url}`).toBe(200); const body = await response.json(); expect(String(body.code)).toBe('0'); return body.data;
    };
    const record = await human('POST', `/api/dynamic/${modelCode}/create`, {
      dop_ui_name: 'UI virtual projection', dop_ui_details: { contact_name: 'Hidden virtual source property' },
    });
    const application = await human('POST', '/api/open-platform/applications', { name: `虚拟消费验证 ${suffix}`, description: 'UI-authored virtual contract consumption' });
    const installation = await human('POST', `/api/open-platform/applications/${application.pid}/installations`, {
      environment: 'staging', scopes: ['dop.ui:list', 'dop.ui:get'], rateLimitPerMinute: 600,
    });
    for (const code of [resourceCode, virtualCode]) await human('PUT', `/api/open-platform/installations/${installation.pid}/resources/${code}`, {
      operations: ['list', 'get'], readableFields: ['pid', 'name'], writableFields: [], rowConstraints: [],
    });
    const credential = await human('POST', `/api/open-platform/installations/${installation.pid}/credentials`);
    const issued = await page.request.post(`${BACKEND_URL}/oauth2/token`, { form: {
      grant_type: 'client_credentials', client_id: credential.clientId, client_secret: credential.clientSecret,
    } });
    expect(issued.status()).toBe(200); const token = await issued.json(); expect(token.access_token).toEqual(expect.any(String));
    const headers = { Authorization: `Bearer ${token.access_token}` }; const resourcePath = `${BACKEND_URL}/api/open/v1/resources/${virtualCode}`;
    const listed = await page.request.get(resourcePath, { headers }); expect(listed.status()).toBe(200);
    expect((await listed.json()).items).toEqual([{ pid: record.pid, name: 'UI virtual projection' }]);
    const detail = await page.request.get(`${resourcePath}/${record.pid}`, { headers }); expect(detail.status()).toBe(200);
    expect(await detail.json()).toEqual({ pid: record.pid, name: 'UI virtual projection' });
    expect(detail.headers().etag).toMatch(/^"[a-f0-9]{64}"$/);
    await page.getByRole('button', { name: publicationText("关闭编辑"), exact: true }).click(); await ownContractEdit.click();
    await page.getByRole('button', { name: publicationText("停用资源"), exact: true }).click();
    await expect(page.getByRole('group', { name: publicationText("操作确认"), exact: true })).toBeInViewport();
    await snapshot('publication-withdrawdialog');
    await page.getByRole('group', { name: publicationText("操作确认"), exact: true }).screenshot({ path: path.join(screenshotRoot, 'publication-withdrawdialog-region.png') });
    await activateWithEnter(page.getByRole('group', { name: publicationText("操作确认"), exact: true }).getByRole('button', { name: publicationText("确认"), exact: true }));
    await expect(page.getByTestId('publication-editor').getByRole('status')).toContainText(publicationText("已停用"));
    await expect(page.getByRole('button', { name: publicationText("下载 OpenAPI"), exact: true })).toBeDisabled();
    await expect(page.getByRole('button', { name: publicationText("下载类型声明"), exact: true })).toBeDisabled();
    const finalState = await page.request.get(`${WEB_BASE_URL}/api/open-platform/publications`);
    expect(finalState.status()).toBe(200);
    const final = await finalState.json(); expect(String(final.code)).toBe('0');
    expect(final.data.find((view: { code: string }) => view.code === resourceCode)).toMatchObject({ status: 'withdrawn', activeVersion: 1, draftRevision: 4 });
    await expect(page.getByTestId('publication-editor').getByRole('status')).toBeInViewport();
    await snapshot('publication-withdraw');
    await page.getByRole('button', { name: publicationText("关闭编辑"), exact: true }).click();
    await page.getByTestId('publication-editor').getByRole('listitem').filter({ has: page.getByText(virtualCode, { exact: true }) })
      .getByRole('button', { name: publicationText("编辑合同"), exact: true }).click();
    await expect(sourceSelector).toHaveValue(resourceCode); await expect(sourceSelector).toBeDisabled();
    const unavailableSource = sourceSelector.getByRole('option', { name: `${resourceCode} (${publicationText("来源已不可用")})`, exact: true });
    await expect(unavailableSource).toHaveCount(1); await expect(unavailableSource).toBeDisabled();

    const deniedSourceRead = await page.request.get(resourcePath, { headers }); expect(deniedSourceRead.status()).toBe(403);
    const afterSourceWithdrawal = await page.request.get(`${WEB_BASE_URL}/api/open-platform/publications`);
    expect(afterSourceWithdrawal.status()).toBe(200); const preservedVirtual = await afterSourceWithdrawal.json(); expect(String(preservedVirtual.code)).toBe('0');
    expect(preservedVirtual.data.find((entry: { code: string }) => entry.code === virtualCode)).toMatchObject({ status: 'published', activeVersion: 1,
      draft: { sourceResources: { [modelCode]: resourceCode } } });
    await page.getByTestId('publication-editor').locator('section').filter({ has: sourceSelector })
      .screenshot({ path: path.join(screenshotRoot, 'publication-virtual-source-unavailable-region.png') });
    await page.getByTestId('publication-editor').getByRole('link', { name: publicationLocale === 'zh-CN' ? '公共查询发布' : 'Public query publications', exact: true }).click();
    await expect(page.getByRole('heading', { name: publicationText("公共查询发布"), exact: true })).toBeVisible();
    await snapshot('publication-query-title');
  });
});

test.describe('Dynamic query publication journey', () => {
  test.setTimeout(180_000);
  test.use({ storageState: { cookies: [], origins: [] }, locale: queryLocale, viewport: queryViewport });
  test('pins a real NamedQuery, saves read-only projections, downloads and withdraws through UI', async ({ page }) => {
    await authenticate(page, queryLocale);
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
    const snapshot = async (id: string) => {
      if (UI_MUTATION === 'publication-overflow') await page.addStyleTag({ content: 'html { min-width: 1440px !important; }' });
      if (QUERY_VIEWPORT === 'narrow') {
        expect(page.viewportSize()).toEqual(queryViewport);
        expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(1);
      }
      await page.screenshot({ path: path.join(screenshotRoot, `${id}.png`), fullPage: true });
    };
    await page.goto('/'); await openAccountMenu(page); await page.getByTestId('open-platform-link').click();
    await expect(page.getByTestId('open-platform-page')).toBeVisible();
    await page.getByTestId('open-platform-publications-link').click();
    await expect(page.getByTestId('publication-editor')).toBeVisible();
    // Catalog GETs are declared presentation fixtures; all query mutations remain real.
    let releaseCatalog!: () => void;
    const catalogGate = new Promise<void>(resolve => { releaseCatalog = resolve; });
    await page.route('**/api/open-platform/query-publications', async route => {
      expect(route.request().method()).toBe('GET'); await catalogGate;
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: '0', data: [] }) });
    });
    await page.getByTestId('publication-editor').getByRole('link', { name: queryText("公共查询发布"), exact: true }).click();
    await expect(page).toHaveURL(new URL('/p/c/open_platform_queries', WEB_BASE_URL).toString());
    const editor = page.getByTestId('query-publication-editor'); await expect(editor).toBeVisible();
    try {
      await expect(editor.getByRole('status')).toHaveText(queryText("正在加载查询发布…"));
      await expect(editor).not.toContainText(queryText("尚未公开命名查询"));
      await snapshot('query-loading');
    } finally { releaseCatalog(); }
    await expect(editor).toContainText(queryText("尚未公开命名查询")); await expect(editor).not.toContainText(/Page Unavailable|Page not found/);
    await snapshot('query-entry');
    await page.unroute('**/api/open-platform/query-publications');
    const catalogProblem = queryLocale === 'zh-CN' ? '查询目录暂时不可用，请重试。' : 'Query catalog unavailable. Retry.';
    await page.route('**/api/open-platform/query-publications', async route => {
      expect(route.request().method()).toBe('GET');
      await route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ code: '503', message: catalogProblem }) });
    });
    await editor.getByRole('button', { name: queryText("刷新目录"), exact: true }).click();
    await expect(editor.getByRole('alert')).toHaveText(catalogProblem);
    await expect(editor).not.toContainText(queryText("尚未公开命名查询"));
    await snapshot('query-failure');
    await page.unroute('**/api/open-platform/query-publications');
    const realQueryCatalogResponse = page.waitForResponse(response => new URL(response.url()).pathname === '/api/open-platform/query-publications' && response.request().method() === 'GET');
    await page.reload();
    const realQueryCatalogHttp = await realQueryCatalogResponse; expect(realQueryCatalogHttp.status()).toBe(200);
    await expect(editor.getByRole('alert')).toHaveCount(0);
    const realQueryCatalog = await realQueryCatalogHttp.json(); expect(String(realQueryCatalog.code)).toBe('0');
    expect(Array.isArray(realQueryCatalog.data)).toBe(true);
    expect(realQueryCatalog.data.some((entry: { code: string }) => entry.code === publicCode)).toBe(false);
    await page.getByRole('button', { name: queryText("新建公共查询"), exact: true }).click();
    await verifyEditorTabOrder(page, [
      [page.getByLabel(queryText("公共查询地址"), { exact: true }), queryText("公共查询地址")],
      [page.getByLabel(queryText("合同版本"), { exact: true }), queryText("合同版本")],
      [page.getByLabel(queryText("查找命名查询"), { exact: true }), queryText("查找命名查询")],
      [page.getByRole('button', { name: queryText("查找"), exact: true }), queryText("查找")],
      [page.getByLabel(queryText("命名查询来源"), { exact: true }), queryText("命名查询来源")],
    ], 'query-source-keyboard');
    await page.getByLabel(queryText("公共查询地址"), { exact: true }).fill(publicCode);
    await page.getByLabel(queryText("查找命名查询"), { exact: true }).fill(queryName); await page.getByRole('button', { name: queryText("查找"), exact: true }).click();
    await expect(page.getByLabel(queryText("命名查询来源"), { exact: true }).getByRole('option', { name: queryName, exact: true })).toHaveCount(1);
    const sourceResponse = page.waitForResponse(response => response.url().endsWith(`/query-sources/${queryCode}`) && response.status() === 200);
    await page.getByLabel(queryText("命名查询来源"), { exact: true }).selectOption({ label: queryName });
    const source = await (await sourceResponse).json(); expect(String(source.code)).toBe('0'); expect(source.data.parameters).toEqual(['zone']);
    expect(source.data.fingerprint).toMatch(/^[a-f0-9]{64}$/); expect(source.data.sources[0].resources).toContainEqual({ code: sourceCode, schemaVersion: 1 });
    expect(JSON.stringify(source)).not.toContain('fromSql'); expect(JSON.stringify(source)).not.toContain('columnExpr');
    await snapshot('query-source');
    await page.getByTestId(`query-source-${modelCode}`).getByLabel(modelName, { exact: true }).selectOption(sourceCode);
    await page.getByLabel(queryText("查询执行权限"), { exact: true }).fill('dop.q:execute'); await snapshot('query-bindings');
    const parameter = page.getByTestId('query-parameter-zone');
    await expect(parameter.getByLabel(queryText("参数类型"), { exact: true })).toHaveValue('string'); await expect(parameter.getByLabel(queryText("必填参数"), { exact: true })).toBeChecked();
    await parameter.scrollIntoViewIfNeeded(); await snapshot('query-parameters');
    for (const [sourceField, label, alias, type] of [['pid', '记录标识', 'pid', 'pid'], ['partName', '零件名称', 'name', 'string'], ['partDetails', '联系信息', 'details', 'object']]) {
      const output = page.getByTestId(`query-output-${sourceField}`); await output.getByLabel(label, { exact: true }).check();
      await output.getByLabel(queryText("对外输出名称"), { exact: true }).fill(alias); await output.getByLabel(queryText("输出类型"), { exact: true }).selectOption(type);
      if (type === 'object') { await output.getByLabel(queryText("原对象属性"), { exact: true }).fill('contact_name'); await output.getByLabel(queryText("对外属性名称"), { exact: true }).fill('contactName'); }
    }
    await expect(editor.getByLabel(queryText("可写"), { exact: true })).toHaveCount(0);
    for (const [field, image] of [['partDetails', 'query-output-region'], ['partName', 'query-output-name'], ['pid', 'query-output-pid']]) {
      const output = editor.getByTestId(`query-output-${field}`);
      await output.scrollIntoViewIfNeeded();
      await expect(output).toBeInViewport({ ratio: 1 });
      await output.screenshot({ path: path.join(screenshotRoot, `${image}.png`) });
    }
    const budgets = { maxPageSize: 7, maxRows: 35, timeoutMs: 1200, maxConcurrent: 3 };
    await verifyDraftCancel(page, editor.getByRole('button', { name: queryText("关闭编辑"), exact: true }),
      editor.getByRole('group', { name: queryText("查询操作确认"), exact: true }),
      editor.getByLabel(queryText("查询执行权限"), { exact: true }), 'dop.q:execute',
      queryText("确认"), queryText("取消"), 'query-discard-confirm', `/api/open-platform/query-publications/${publicCode}`);
    const budgetInputs = [
      ['maxPageSize', "单页上限"], ['maxRows', "结果总量上限"],
      ['timeoutMs', "查询超时（毫秒）"], ['maxConcurrent', "同时执行上限"],
    ] as const;
    for (const [key, label] of budgetInputs) await editor.getByLabel(queryText(label), { exact: true }).fill(String(budgets[key]));
    const pageLimit = editor.getByLabel(queryText("单页上限"), { exact: true });
    const rowLimit = editor.getByLabel(queryText("结果总量上限"), { exact: true });
    let prematureBudgetWrites = 0;
    const observeBudgetWrites = (request: import('@playwright/test').Request) => {
      if (request.method() === 'PUT' && new URL(request.url()).pathname.endsWith(`/query-publications/${publicCode}/draft`)) prematureBudgetWrites++;
    };
    page.on('request', observeBudgetWrites);
    await rowLimit.fill('0');
    await activateWithEnter(editor.getByRole('button', { name: queryText("保存并校验查询草稿"), exact: true }));
    await expect(pageLimit).toBeFocused();
    await expect(pageLimit).toHaveAttribute('aria-invalid', 'true');
    await expect(rowLimit).toHaveAttribute('aria-invalid', 'true');
    await rowLimit.fill(String(budgets.maxRows));
    await expect(rowLimit).not.toHaveAttribute('aria-invalid', 'true');
    await expect(pageLimit).not.toHaveAttribute('aria-invalid', 'true');
    await expect(pageLimit.locator('..').getByRole('alert')).toHaveCount(0);
    expect(prematureBudgetWrites).toBe(0);
    page.off('request', observeBudgetWrites);
    const budgetRegion = editor.getByRole('heading', { name: queryText("执行预算"), exact: true }).locator('..');
    await budgetRegion.scrollIntoViewIfNeeded();
    await expect(budgetRegion).toBeInViewport({ ratio: 1 });
    await budgetRegion.screenshot({ path: path.join(screenshotRoot, 'query-budget-configured-region.png') });
    await editor.getByRole('button', { name: queryText("保存并校验查询草稿"), exact: true }).locator('..').locator('..').screenshot({ path: path.join(screenshotRoot, 'query-footer-region.png') });
    const savedResponse = page.waitForResponse(response => response.url().endsWith(`/query-publications/${publicCode}/draft`) && response.request().method() === 'PUT');
    await activateWithEnter(page.getByRole('button', { name: queryText("保存并校验查询草稿"), exact: true }));
    const savedHttp = await savedResponse; expect(savedHttp.status()).toBe(200); const saved = await savedHttp.json(); expect(String(saved.code)).toBe('0');
    expect(saved.data.draft).toMatchObject(budgets);
    expect(saved.data.draft).toMatchObject({ sourceFingerprint: source.data.fingerprint, sourceResources: { [modelCode]: sourceCode }, parameters: { zone: { type: 'string', required: true, nullable: false } } });
    expect(saved.data.draft.fields.details).toMatchObject({ readable: true, writable: false, properties: { contactName: { source: 'contact_name', readable: true, writable: false } } });
    await expect(editor.getByRole('status')).toContainText(queryText("查询草稿已保存并通过来源校验")); await snapshot('query-draft');
    await page.reload();
    await editor.getByRole('listitem').filter({ has: page.getByText(publicCode, { exact: true }) })
      .getByRole('button', { name: queryText("编辑查询合同"), exact: true }).click();
    for (const [key, label] of budgetInputs) await expect(editor.getByLabel(queryText(label), { exact: true })).toHaveValue(String(budgets[key]));
    await expect(editor.getByRole('button', { name: queryText("发布查询"), exact: true })).toBeEnabled();
    await budgetRegion.scrollIntoViewIfNeeded();
    await expect(budgetRegion).toBeInViewport({ ratio: 1 });
    await budgetRegion.screenshot({ path: path.join(screenshotRoot, 'query-budget-reopened-region.png') });
    const queryTimeout = editor.getByLabel(queryText("查询超时（毫秒）"), { exact: true });
    await queryTimeout.fill(String(budgets.timeoutMs + 1));
    await verifyConfirmedDiscard(page, editor.getByRole('button', { name: queryText("关闭编辑"), exact: true }),
      editor.getByRole('group', { name: queryText("查询操作确认"), exact: true }), queryText("确认"),
      editor.getByRole('listitem').filter({ has: page.getByText(publicCode, { exact: true }) })
        .getByRole('button', { name: queryText("编辑查询合同"), exact: true }),
      queryTimeout, String(budgets.timeoutMs), 'query-confirmed-discard', `/api/open-platform/query-publications/${publicCode}`);
    await activateWithEnter(page.getByRole('button', { name: queryText("发布查询"), exact: true }));
    await verifyBusyPublish(page, editor.getByRole('group', { name: queryText("查询操作确认"), exact: true }),
      queryText("确认"), queryText("取消"), `/api/open-platform/query-publications/${publicCode}/publish`);
    await expect(editor.getByRole('status')).toContainText(queryText("已发布查询")); await snapshot('query-publish');
    const originalDocumentResponse = await page.request.get(`${WEB_BASE_URL}/api/open-platform/query-publications/${publicCode}/openapi`);
    expect(originalDocumentResponse.status()).toBe(200); const originalDocument = await originalDocumentResponse.json();
    const nameOutput = editor.getByTestId('query-output-partName');
    await nameOutput.getByLabel(queryText("对外输出名称"), { exact: true }).fill('displayName');
    await page.getByLabel(queryText("合同版本"), { exact: true }).fill('2');
    const breakingSave = page.waitForResponse(response => response.url().endsWith(`/query-publications/${publicCode}/draft`) && response.request().method() === 'PUT');
    await activateWithEnter(page.getByRole('button', { name: queryText("保存并校验查询草稿"), exact: true }));
    const breakingSaved = await breakingSave; expect(breakingSaved.status()).toBe(200);
    const breakingDraft = await breakingSaved.json(); expect(breakingDraft.data).toMatchObject({ activeVersion: 1, draftRevision: 2 });
    expect(breakingDraft.data.draft.fields.displayName.source).toBe('partName'); expect(breakingDraft.data.draft.fields.name).toBeUndefined();
    await expect(editor.getByRole('status')).toContainText(queryText("查询草稿已保存并通过来源校验")); await snapshot('query-newdraft');
    await activateWithEnter(page.getByRole('button', { name: queryText("发布查询"), exact: true }));
    const incompatibleConfirm = page.getByRole('group', { name: queryText("查询操作确认"), exact: true });
    await expect(incompatibleConfirm).toBeInViewport(); await snapshot('query-compat-confirm');
    const incompatibleMarkup = await incompatibleConfirm.evaluate(element => element.outerHTML);
    const rejectionResponse = page.waitForResponse(response => response.url().endsWith(`/query-publications/${publicCode}/publish`) && response.request().method() === 'POST');
    await activateWithEnter(incompatibleConfirm.getByRole('button', { name: queryText("确认"), exact: true }));
    const rejection = await rejectionResponse; expect(rejection.status()).toBe(400);
    expect(await rejection.json()).toMatchObject({ code: '400', message: 'Existing query output cannot change' });
    if (UI_MUTATION === 'query-confirmation-stale') {
      await editor.evaluate((element, markup) => element.insertAdjacentHTML('beforeend', markup), incompatibleMarkup);
    }
    await expect(page.getByRole('group', { name: queryText("查询操作确认"), exact: true })).toHaveCount(0);
    if (UI_MUTATION === 'query-compatibility-feedback') {
      await editor.getByRole('alert').evaluate(element => { element.textContent = 'Publication succeeded'; });
    }
    await expect(editor.getByRole('alert')).toHaveText(queryText("已发布的查询输出不能修改。请使用新的公共查询地址发布新合同，原合同将保持有效。"));
    await expect(editor.getByRole('alert')).toBeInViewport();
    await expect(nameOutput.getByLabel(queryText("对外输出名称"), { exact: true })).toHaveValue('displayName');
    await snapshot('query-compatibility');
    const afterRejected = await page.request.get(`${WEB_BASE_URL}/api/open-platform/query-publications/${publicCode}/openapi`);
    expect(afterRejected.status()).toBe(200); expect(await afterRejected.json()).toEqual(originalDocument);
    // Repair only the draft; the old published snapshot must stay unchanged.
    await page.getByLabel(queryText("合同版本"), { exact: true }).fill('1');
    await nameOutput.getByLabel(queryText("对外输出名称"), { exact: true }).fill('name');
    const repairResponse = page.waitForResponse(response => response.url().endsWith(`/query-publications/${publicCode}/draft`) && response.request().method() === 'PUT');
    await activateWithEnter(page.getByRole('button', { name: queryText("保存并校验查询草稿"), exact: true }));
    const repaired = await repairResponse; expect(repaired.status()).toBe(200);
    expect((await repaired.json()).data).toMatchObject({ activeVersion: 1, draftRevision: 3 });
    await page.getByRole('button', { name: queryText("关闭编辑"), exact: true }).click();
    const majorCode = publicCode + '.v2';
    await page.getByRole('button', { name: queryText("新建公共查询"), exact: true }).click();
    await page.getByLabel(queryText("公共查询地址"), { exact: true }).fill(majorCode);
    await page.getByLabel(queryText("查找命名查询"), { exact: true }).fill(queryName);
    await page.getByRole('button', { name: queryText("查找"), exact: true }).click();
    await page.getByLabel(queryText("命名查询来源"), { exact: true }).selectOption({ label: queryName });
    await page.getByTestId(`query-source-${modelCode}`).getByLabel(modelName, { exact: true }).selectOption(sourceCode);
    await page.getByLabel(queryText("查询执行权限"), { exact: true }).fill('dop.q:execute');
    for (const [sourceField, label, alias, type] of [['pid', '记录标识', 'pid', 'pid'], ['partName', '零件名称', 'displayName', 'string'], ['partDetails', '联系信息', 'details', 'object']]) {
      const output = editor.getByTestId(`query-output-${sourceField}`); await output.getByLabel(label, { exact: true }).check();
      await output.getByLabel(queryText("对外输出名称"), { exact: true }).fill(alias);
      await output.getByLabel(queryText("输出类型"), { exact: true }).selectOption(type);
      if (type === 'object') { await output.getByLabel(queryText("原对象属性"), { exact: true }).fill('contact_name'); await output.getByLabel(queryText("对外属性名称"), { exact: true }).fill('contactName'); }
    }
    const majorSaveResponse = page.waitForResponse(response => response.url().endsWith(`/query-publications/${majorCode}/draft`) && response.request().method() === 'PUT');
    await activateWithEnter(page.getByRole('button', { name: queryText("保存并校验查询草稿"), exact: true }));
    const majorSaved = await majorSaveResponse; expect(majorSaved.status()).toBe(200);
    expect((await majorSaved.json()).data).toMatchObject({ draftRevision: 1, activeVersion: null });
    const majorPublishResponse = page.waitForResponse(response => response.url().endsWith(`/query-publications/${majorCode}/publish`) && response.request().method() === 'POST');
    await activateWithEnter(page.getByRole('button', { name: queryText("发布查询"), exact: true }));
    await activateWithEnter(page.getByRole('group', { name: queryText("查询操作确认"), exact: true }).getByRole('button', { name: queryText("确认"), exact: true }));
    const majorPublished = await majorPublishResponse; expect(majorPublished.status()).toBe(200);
    expect((await majorPublished.json()).data).toMatchObject({ activeVersion: 1, draftRevision: 1, status: 'published' });
    await expect(editor.getByRole('status')).toContainText(queryText("已发布查询")); await snapshot('query-major-publish');
    const majorDocumentResponse = await page.request.get(`${WEB_BASE_URL}/api/open-platform/query-publications/${majorCode}/openapi`);
    expect(majorDocumentResponse.status()).toBe(200); const majorDocument = await majorDocumentResponse.json();
    expect(majorDocument.paths[`/api/open/v1/queries/${majorCode}:execute`].post).toBeDefined();
    expect(majorDocument.components.schemas.QueryItem.properties.displayName).toBeDefined();
    expect(majorDocument.components.schemas.QueryItem.properties.name).toBeUndefined();
    const oldStillPublished = await page.request.get(`${WEB_BASE_URL}/api/open-platform/query-publications/${publicCode}/openapi`);
    expect(oldStillPublished.status()).toBe(200); expect(await oldStillPublished.json()).toEqual(originalDocument);
    await page.getByRole('button', { name: queryText("关闭编辑"), exact: true }).click();
    await editor.getByRole('listitem').filter({ has: page.getByText(publicCode, { exact: true }) })
      .getByRole('button', { name: queryText("编辑查询合同"), exact: true }).click();
    await expect(nameOutput.getByLabel(queryText("对外输出名称"), { exact: true })).toHaveValue('name');
    for (const [button, extension] of [[queryText("下载查询 OpenAPI"), '.openapi.json'], [queryText("下载查询类型声明"), '.d.ts']] as const) {
      const downloaded = page.waitForEvent('download'); await page.getByRole('button', { name: button, exact: true }).click();
      const artifact = await downloaded; expect(artifact.suggestedFilename()).toBe(publicCode + extension); const location = await artifact.path(); expect(location).toBeTruthy();
      const content = await readFile(location!, 'utf8'); expect(content).toContain('contactName'); expect(content).not.toContain(modelCode); expect(content).not.toContain(queryCode); expect(content).not.toContain('contact_name'); expect(content).not.toContain('dop_q_name');
      if (extension === '.openapi.json') { const document = JSON.parse(content); expect(document.openapi).toBe('3.1.0'); expect(document['x-schema-version']).toBe(1); expect(document.paths[`/api/open/v1/queries/${publicCode}:execute`].post).toBeDefined(); }
      else { expect(content).toContain('export type QueryParameters'); expect(content).toContain('export type QueryItem'); }
      await artifact.saveAs(path.join(screenshotRoot, publicCode + extension)); await snapshot(extension === '.openapi.json' ? 'query-openapi' : 'query-types');
    }
    await page.getByRole('button', { name: queryText("停用查询"), exact: true }).click(); const confirmation = page.getByRole('group', { name: queryText("查询操作确认"), exact: true });
    await expect(confirmation).toBeInViewport(); await snapshot('query-withdrawdialog'); await activateWithEnter(confirmation.getByRole('button', { name: queryText("确认"), exact: true }));
    await expect(editor.getByRole('status')).toContainText(queryText("查询已停用")); await expect(page.getByRole('button', { name: queryText("下载查询 OpenAPI"), exact: true })).toBeDisabled();
    const persisted = await page.request.get(`${WEB_BASE_URL}/api/open-platform/query-publications`); expect(persisted.status()).toBe(200);
    const publications = await persisted.json(); expect(String(publications.code)).toBe('0');
    expect(publications.data.find((entry: { code: string }) => entry.code === publicCode)).toMatchObject({ status: 'withdrawn', activeVersion: 1, draftRevision: 3 });
    await snapshot('query-withdraw');
  });
});

const installationLocale = process.env.AURA_OPEN_PLATFORM_INSTALLATION_LOCALE || 'zh-CN';
if (!['zh-CN', 'en-US'].includes(installationLocale)) throw new Error('Unsupported installation locale');
const installationViewportName = process.env.AURA_OPEN_PLATFORM_INSTALLATION_VIEWPORT || 'desktop';
if (!['desktop', 'narrow'].includes(installationViewportName)) throw new Error('Unsupported installation viewport');
const installationViewport = installationViewportName === 'narrow' ? { width: 1024, height: 900 } : { width: 1440, height: 1000 };
const installationEnglish: Record<string, string> = {
  '数据授权': 'Data authority', '授权合同': 'Authority contract',
  '此安装尚未获此合同的数据授权': 'This installation has no authority for this contract',
  '列表查询': 'list', '详情读取': 'get', '创建记录': 'create', '修改记录': 'update', '删除记录': 'delete',
  '读取': 'Read', '写入': 'Write', '创建必填': 'Required on create',
  '添加行约束': 'Add row constraint', '约束公共字段': 'Constraint public field', '允许值（每行一个）': 'Allowed values (one per line)',
  '保存安装授权': 'Save installation authority', '授权已保存': 'Authority saved', '已有列表授权': 'List granted',
  '撤销安装授权': 'Revoke installation authority', '安装授权操作确认': 'Installation authority confirmation',
  '确认': 'Confirm', '取消': 'Cancel', '关闭授权编辑': 'Close authority editor', '授权已撤销': 'Authority revoked',
};
const installationText = (cn: string) => {
  if (installationLocale === 'zh-CN') return cn;
  if (!(cn in installationEnglish)) throw new Error(`Missing English installation expectation: ${cn}`);
  return installationEnglish[cn];
};

test.describe('Dynamic installation authority journey', () => {
  test.setTimeout(180_000);
  test.describe.configure({ retries: 0 });
  test.use({ storageState: { cookies: [], origins: [] }, locale: installationLocale, viewport: installationViewport });

  test('persists resource and query grants through installation UI and enforces them on real machine calls', async ({ page }, testInfo) => {
    await authenticate(page, installationLocale);
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
      maxPageSize: 50, maxConditions: 8, timeoutMs: 5000, events: ['created', 'updated', 'deleted'] };
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
    const majorResourceCode = resourceCode + '.v2'; const majorQueryCode = queryCode + '.v2';
    const { name: originalName, ...otherResourceFields } = resource.fields;
    const majorResource = { ...resource, code: majorResourceCode, fields: { ...otherResourceFields, displayName: originalName } };
    await human('PUT', `/api/open-platform/publications/${majorResourceCode}/draft`, { expectedRevision: 0, definition: majorResource });
    await human('POST', `/api/open-platform/publications/${majorResourceCode}/publish`, { expectedRevision: 1 });
    const { name: originalQueryName, ...otherQueryFields } = query.fields;
    const majorQuery = { ...query, code: majorQueryCode, sourceResources: { [modelCode]: majorResourceCode }, fields: { ...otherQueryFields, displayName: originalQueryName } };
    await human('PUT', `/api/open-platform/query-publications/${majorQueryCode}/draft`, { expectedRevision: 0, definition: majorQuery });
    await human('POST', `/api/open-platform/query-publications/${majorQueryCode}/publish`, { expectedRevision: 1 });
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
    const snapshot = async (id: string) => {
      if (installationViewportName === 'narrow') {
        expect(page.viewportSize()).toEqual(installationViewport);
        expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(1);
      }
      await page.screenshot({ path: path.join(screenshotRoot, `${id}.png`), fullPage: true }); };
    await page.goto('/'); await openAccountMenu(page); await page.getByTestId('open-platform-link').click();
    await expect(page.getByTestId('open-platform-page')).toBeVisible();
    const installationCard = page.getByTestId(`open-platform-installation-${installation.pid}`);
    await installationCard.getByRole('button', { name: installationText('数据授权'), exact: true }).click();
    const editor = page.getByTestId('installation-grant-editor'); await expect(editor).toBeVisible();
    await expect(editor.getByLabel(installationText('授权合同'), { exact: true })).toBeEnabled(); await snapshot('grant-entry');
    await editor.getByLabel(installationText('授权合同'), { exact: true }).selectOption(`resource:${resourceCode}`);
    await expect(editor).toContainText(installationText('此安装尚未获此合同的数据授权'));
    for (const name of [installationText('列表查询'), installationText('详情读取'), installationText('创建记录'), installationText('修改记录'), installationText('删除记录')]) await editor.getByLabel(name, { exact: true }).check();
    for (const alias of ['name', 'zone']) { await editor.getByLabel(`${installationText('读取')} ${alias}`, { exact: true }).check(); await editor.getByRole('checkbox', { name: `${installationText('写入')} ${alias} (${installationText('创建必填')})`, exact: true }).check(); }
    await expect(editor.getByLabel(`${installationText('读取')} secret`, { exact: true })).not.toBeChecked();
    await editor.getByRole('button', { name: installationText('添加行约束'), exact: true }).click();
    const constraint = editor.getByTestId('grant-constraint-0'); await constraint.getByLabel(installationText('约束公共字段'), { exact: true }).selectOption('zone'); await constraint.getByLabel(installationText('允许值（每行一个）'), { exact: true }).fill('A');
    await verifyDraftCancel(page, editor.getByRole('button', { name: installationText('关闭授权编辑'), exact: true }),
      editor.getByRole('group', { name: installationText('安装授权操作确认'), exact: true }),
      constraint.getByLabel(installationText('允许值（每行一个）'), { exact: true }), 'A',
      installationText('确认'), installationText('取消'), 'grant-discard-confirm', `/api/open-platform/installations/${installation.pid}`);
    await expect(editor.getByLabel(installationText('授权合同'), { exact: true })).toBeDisabled(); await snapshot('grant-resource');
    const granted = page.waitForResponse(response => response.url().endsWith(`/installations/${installation.pid}/resources/${resourceCode}`) && response.request().method() === 'PUT');
    await editor.getByRole('button', { name: installationText('保存安装授权'), exact: true }).click(); expect((await granted).status()).toBe(200);
    await expect(editor.getByRole('status').filter({ hasText: installationText('授权已保存') })).toBeVisible(); await snapshot('grant-saved');
    await page.reload(); await installationCard.getByRole('button', { name: installationText('数据授权'), exact: true }).click(); await editor.getByLabel(installationText('授权合同'), { exact: true }).selectOption(`resource:${resourceCode}`);
    await expect(editor.getByLabel(`${installationText('读取')} name`, { exact: true })).toBeChecked(); await expect(editor.getByTestId('grant-constraint-0').getByLabel(installationText('允许值（每行一个）'), { exact: true })).toHaveValue('A');
    const grantedRows = editor.getByTestId('grant-constraint-0').getByLabel(installationText('允许值（每行一个）'), { exact: true });
    await grantedRows.fill('B');
    await verifyConfirmedDiscard(page, editor.getByRole('button', { name: installationText('关闭授权编辑'), exact: true }),
      editor.getByRole('group', { name: installationText('安装授权操作确认'), exact: true }), installationText('确认'),
      installationCard.getByRole('button', { name: installationText('数据授权'), exact: true }),
      grantedRows, 'A', 'grant-confirmed-discard', `/api/open-platform/installations/${installation.pid}`,
      async () => { await editor.getByLabel(installationText('授权合同'), { exact: true }).selectOption(`resource:${resourceCode}`); });
    const listed = await page.request.get(resourcePath, { headers: machineHeaders }); expect(listed.status()).toBe(200); expect((await listed.json()).items).toEqual([{ pid: recordA.pid, name: 'Allowed A', zone: 'A' }]);
    expect((await page.request.get(`${resourcePath}/${recordB.pid}`, { headers: machineHeaders })).status()).toBe(404);
    const forbidden = await page.request.post(resourcePath, { headers: { ...machineHeaders, 'Idempotency-Key': `grant-forbidden-${suffix}` }, data: { name: 'Denied', zone: 'B' } }); expect(forbidden.status()).toBe(403);
    await editor.getByLabel(installationText('授权合同'), { exact: true }).selectOption(`query:${queryCode}`); await expect(editor).toContainText(installationText('已有列表授权'));
    await editor.getByLabel('pid', { exact: true }).check(); await editor.getByLabel('name', { exact: true }).check();
    const queryGrantActions = editor.getByRole('button', { name: installationText('保存安装授权'), exact: true }).locator('..').locator('..');
    await queryGrantActions.scrollIntoViewIfNeeded();
    await expect(queryGrantActions).toBeInViewport({ ratio: 1 });
    for (const alias of ['pid', 'name']) await expect(editor.getByLabel(alias, { exact: true }).locator('..')).toBeInViewport({ ratio: 1 });
    await snapshot('grant-query');
    const queryGranted = page.waitForResponse(response => response.url().endsWith(`/installations/${installation.pid}/queries/${queryCode}`) && response.request().method() === 'PUT');
    await editor.getByRole('button', { name: installationText('保存安装授权'), exact: true }).click(); expect((await queryGranted).status()).toBe(200); await expect(editor.getByRole('status').filter({ hasText: installationText('授权已保存') })).toBeVisible();
    const result = await page.request.post(queryPath, { headers: machineHeaders, data: { parameters: { zone: 'A' }, limit: 10 } }); expect(result.status()).toBe(200); expect((await result.json()).items).toEqual([{ pid: recordA.pid, name: 'Allowed A' }]);
    const otherRows = await page.request.post(queryPath, { headers: machineHeaders, data: { parameters: { zone: 'B' }, limit: 10 } }); expect(otherRows.status()).toBe(200); expect((await otherRows.json()).items).toEqual([]);
    // Reuse the release SDK consumer against these exact UI-authored installation policies.
    const coreRoot = fileURLToPath(new URL('../../../../', import.meta.url));
    const sdkOutput = path.join(process.env.AURA_EVIDENCE_ROOT || testInfo.outputDir, 'ui-sdk-consumer');
    const preparedSdk = spawnSync('sh', [path.join(coreRoot, 'scripts/ci/prepare-open-platform-sdk.sh'), coreRoot, sdkOutput],
      { encoding: 'utf8', timeout: 60_000 });
    await writeFile(path.join(screenshotRoot, 'sdk-prepare.log'), (preparedSdk.stdout || '') + (preparedSdk.stderr || '') || 'SDK prepare produced no output');
    expect(preparedSdk.status, 'The original fresh SDK preparation must succeed').toBe(0);
    if (UI_MUTATION === 'sdk-ui-projection') {
      await editor.getByLabel(installationText('授权合同'), { exact: true }).selectOption(`resource:${resourceCode}`);
      await editor.getByLabel(`${installationText('读取')} name`, { exact: true }).uncheck();
      const narrowedGrant = page.waitForResponse(response => response.url().endsWith(`/installations/${installation.pid}/resources/${resourceCode}`) && response.request().method() === 'PUT');
      await editor.getByRole('button', { name: installationText('保存安装授权'), exact: true }).click();
      expect((await narrowedGrant).status()).toBe(200);
      await expect(editor.getByRole('status').filter({ hasText: installationText('授权已保存') })).toBeVisible();
    }
    const sdkReportPath = path.join(sdkOutput, 'real-backend-results.json');
    const sdkArchiveReceipt = path.join(sdkOutput, 'archive-receipt.json');
    const consumedSdk = spawnSync('python3', ['-B', path.join(coreRoot, 'scripts/ci/verify-open-platform-sdk.py'), 'consume', coreRoot,
      sdkOutput, sdkArchiveReceipt, sdkReportPath, process.execPath], { encoding: 'utf8', timeout: 95_000,
      input: JSON.stringify({ baseUrl: BACKEND_URL, clientId: credential.clientId, clientSecret: credential.clientSecret,
        scopes: [...scopes, 'dop.grant:query'], resourceCode, queryCode, allowedPid: recordA.pid, hiddenPid: recordB.pid }) });
    await writeFile(path.join(screenshotRoot, 'sdk-consumer.exit'), String(consumedSdk.status) + '\n');
    expect(consumedSdk.status, 'The original SDK13 must consume the UI-authored grant without exposing credentials').toBe(0);
    const sdkReport = JSON.parse(await readFile(sdkReportPath, 'utf8'));
    const sdkProfile = JSON.parse(await readFile(path.join(coreRoot, 'scripts/fixtures/open-platform-consumer/real-backend.profile.json'), 'utf8'));
    expect(sdkReport.cases).toEqual(sdkProfile.cases.map((id: string) => ({ id, status: 'passed' })));
    expect(sdkReport).toMatchObject({ profile: sdkProfile.id, executed: 13, passed: 13, requests: 17, tokenExchanges: 1 });
    await snapshot('grant-sdk-consumed');
    const resourceCatalogPath = `/api/open-platform/installations/${installation.pid}/resources`;
    const queryCatalogPath = `/api/open-platform/installations/${installation.pid}/queries`;
    const oldAuthority = async () => ({
      resource: (await human('GET', resourceCatalogPath)).find((view: { code: string }) => view.code === resourceCode).policy,
      query: (await human('GET', queryCatalogPath)).find((view: { code: string }) => view.code === queryCode).policy,
    });
    const baselineAuthority = await oldAuthority();
    const baselineRecord = await page.request.get(`${resourcePath}/${recordA.pid}`, { headers: machineHeaders });
    expect(baselineRecord.status()).toBe(200); const baselineBody = await baselineRecord.json(); const baselineEtag = baselineRecord.headers().etag;
    expect(baselineBody).toEqual({ pid: recordA.pid, name: 'Allowed A', zone: 'A' }); expect(baselineEtag).toMatch(/^"[^"\r\n]+"$/);
    const assertOldUnchanged = async () => {
      expect(await oldAuthority()).toEqual(baselineAuthority);
      const response = await page.request.get(`${resourcePath}/${recordA.pid}`, { headers: machineHeaders });
      expect(response.status()).toBe(200); expect(await response.json()).toEqual(baselineBody); expect(response.headers().etag).toBe(baselineEtag);
      const responseQuery = await page.request.post(queryPath, { headers: machineHeaders, data: { parameters: { zone: 'A' }, limit: 10 } });
      expect(responseQuery.status()).toBe(200); expect((await responseQuery.json()).items).toEqual([{ pid: recordA.pid, name: 'Allowed A' }]);
    };
    const majorResourcePath = `${BACKEND_URL}/api/open/v1/resources/${majorResourceCode}`;
    const majorQueryPath = `${BACKEND_URL}/api/open/v1/queries/${majorQueryCode}:execute`;
    const executeMajor = () => page.request.post(majorQueryPath, { headers: machineHeaders, data: { parameters: { zone: 'A' }, limit: 10 } });
    expect((await page.request.get(`${majorResourcePath}/${recordA.pid}`, { headers: machineHeaders })).status()).toBe(403);
    expect((await executeMajor()).status()).toBe(403);
    await editor.getByLabel(installationText('授权合同'), { exact: true }).selectOption(`resource:${majorResourceCode}`);
    await expect(editor).toContainText(installationText('此安装尚未获此合同的数据授权')); await snapshot('grant-major-ungranted');
    const grantMajorResource = async () => {
      await editor.getByLabel(installationText('授权合同'), { exact: true }).selectOption(`resource:${majorResourceCode}`);
      for (const operation of [installationText('列表查询'), installationText('详情读取')]) await editor.getByLabel(operation, { exact: true }).check();
      for (const alias of ['displayName', 'zone']) await editor.getByLabel(`${installationText('读取')} ${alias}`, { exact: true }).check();
      if (UI_MUTATION === 'major-grant-alias') await editor.getByLabel(`${installationText('读取')} displayName`, { exact: true }).uncheck();
      await expect(editor.getByLabel(`${installationText('读取')} displayName`, { exact: true })).toBeChecked();
      await expect(editor.getByLabel(`${installationText('读取')} secret`, { exact: true })).not.toBeChecked();
      await editor.getByRole('button', { name: installationText('添加行约束'), exact: true }).click();
      await editor.getByTestId('grant-constraint-0').getByLabel(installationText('约束公共字段'), { exact: true }).selectOption('zone');
      await editor.getByTestId('grant-constraint-0').getByLabel(installationText('允许值（每行一个）'), { exact: true }).fill('A');
      const response = page.waitForResponse(reply => reply.url().endsWith(`/installations/${installation.pid}/resources/${majorResourceCode}`) && reply.request().method() === 'PUT');
      await editor.getByRole('button', { name: installationText('保存安装授权'), exact: true }).click(); const saved = await response;
      expect(saved.status()).toBe(200); const savedPolicy = saved.request().postDataJSON();
      expect({ ...savedPolicy, operations: [...savedPolicy.operations].sort(), readableFields: [...savedPolicy.readableFields].sort() }).toEqual({ operations: ['get', 'list'], readableFields: ['displayName', 'pid', 'zone'], writableFields: [], rowConstraints: [{ field: 'zone', values: ['A'] }] });
      await expect(editor.getByRole('status').filter({ hasText: installationText('授权已保存') })).toBeVisible();
    };
    const revokeMajor = async (kind: 'resource' | 'query', code: string, image?: string) => {
      await editor.getByLabel(installationText('授权合同'), { exact: true }).selectOption(`${kind}:${code}`);
      await editor.getByRole('button', { name: installationText('撤销安装授权'), exact: true }).click();
      const confirmation = editor.getByRole('group', { name: installationText('安装授权操作确认'), exact: true });
      await expect(confirmation).toBeVisible(); if (image) await snapshot(image);
      const response = page.waitForResponse(reply => reply.url().endsWith(`/installations/${installation.pid}/${kind === 'resource' ? 'resources' : 'queries'}/${code}`) && reply.request().method() === 'DELETE');
      await confirmation.getByRole('button', { name: installationText('确认'), exact: true }).click(); expect((await response).status()).toBe(200);
      await expect(editor.getByRole('status').filter({ hasText: installationText('授权已撤销') })).toBeVisible();
    };
    await grantMajorResource(); await snapshot('grant-major-resource-saved');
    const majorRecord = await page.request.get(`${majorResourcePath}/${recordA.pid}`, { headers: machineHeaders });
    expect(majorRecord.status()).toBe(200); expect(await majorRecord.json()).toEqual({ pid: recordA.pid, displayName: 'Allowed A', zone: 'A' });
    expect((await page.request.get(`${majorResourcePath}/${recordB.pid}`, { headers: machineHeaders })).status()).toBe(404);
    const deniedMajorWrite = await page.request.post(majorResourcePath, { headers: { ...machineHeaders, 'Idempotency-Key': `major-readonly-denial-${suffix}` }, data: { displayName: 'Denied major write', zone: 'A' } });
    expect(deniedMajorWrite.status()).toBe(403);
    expect((await executeMajor()).status()).toBe(403); await assertOldUnchanged();
    await editor.getByLabel(installationText('授权合同'), { exact: true }).selectOption(`query:${majorQueryCode}`);
    await expect(editor).toContainText(installationText('已有列表授权'));
    for (const alias of ['pid', 'displayName']) await editor.getByLabel(alias, { exact: true }).check();
    const majorQueryGranted = page.waitForResponse(reply => reply.url().endsWith(`/installations/${installation.pid}/queries/${majorQueryCode}`) && reply.request().method() === 'PUT');
    await editor.getByRole('button', { name: installationText('保存安装授权'), exact: true }).click(); const grantedQuery = await majorQueryGranted;
    expect(grantedQuery.status()).toBe(200); const savedQueryPolicy = grantedQuery.request().postDataJSON();
    expect({ ...savedQueryPolicy, readableFields: [...savedQueryPolicy.readableFields].sort() }).toEqual({ readableFields: ['displayName', 'pid'] });
    await expect(editor.getByRole('status').filter({ hasText: installationText('授权已保存') })).toBeVisible(); await snapshot('grant-major-query-saved');
    const majorItems = await executeMajor(); expect(majorItems.status()).toBe(200); expect((await majorItems.json()).items).toEqual([{ pid: recordA.pid, displayName: 'Allowed A' }]);
    const hiddenMajor = await page.request.post(majorQueryPath, { headers: machineHeaders, data: { parameters: { zone: 'B' }, limit: 10 } });
    expect(hiddenMajor.status()).toBe(200); expect((await hiddenMajor.json()).items).toEqual([]); await assertOldUnchanged();
    await revokeMajor('resource', majorResourceCode, 'grant-major-source-revoke-dialog');
    expect((await executeMajor()).status()).toBe(403); await assertOldUnchanged();
    await grantMajorResource();
    const restoredMajorItems = await executeMajor(); expect(restoredMajorItems.status()).toBe(200); expect((await restoredMajorItems.json()).items).toEqual([{ pid: recordA.pid, displayName: 'Allowed A' }]);
    await snapshot('grant-major-source-restored'); await assertOldUnchanged();
    await revokeMajor('query', majorQueryCode);
    expect((await executeMajor()).status()).toBe(403);
    expect((await human('GET', queryCatalogPath)).find((view: { code: string }) => view.code === majorQueryCode).policy).toBeNull();
    await snapshot('grant-major-query-revoked'); await assertOldUnchanged();
    await revokeMajor('resource', majorResourceCode);
    expect((await page.request.get(`${majorResourcePath}/${recordA.pid}`, { headers: machineHeaders })).status()).toBe(403);
    expect((await human('GET', resourceCatalogPath)).find((view: { code: string }) => view.code === majorResourceCode).policy).toBeNull();
    await snapshot('grant-major-resource-revoked'); await assertOldUnchanged();
    await editor.getByLabel(installationText('授权合同'), { exact: true }).selectOption(`query:${queryCode}`);
    const intent = { name: 'Created A', zone: 'A' }; const createHeaders = { ...machineHeaders, 'Idempotency-Key': `grant-create-${suffix}` };
    const created = await page.request.post(resourcePath, { headers: createHeaders, data: intent }); expect(created.status()).toBe(201); const row = await created.json(); expect(row).toMatchObject(intent); expect(row.pid).toEqual(expect.any(String)); expect(row).not.toHaveProperty('secret');
    const replay = await page.request.post(resourcePath, { headers: createHeaders, data: intent }); expect(replay.status()).toBe(201); expect(replay.headers()['idempotent-replay']).toBe('true'); expect(await replay.json()).toEqual(row);
    const updated = await page.request.patch(`${resourcePath}/${row.pid}`, { headers: { ...machineHeaders, 'Idempotency-Key': `grant-update-${suffix}`, 'If-Match': created.headers().etag }, data: { name: 'Updated A' } }); expect(updated.status()).toBe(200); expect(await updated.json()).toMatchObject({ pid: row.pid, name: 'Updated A' });
    const removed = await page.request.delete(`${resourcePath}/${row.pid}`, { headers: { ...machineHeaders, 'Idempotency-Key': `grant-delete-${suffix}`, 'If-Match': updated.headers().etag } }); expect(removed.status()).toBe(204);
    expect((await page.request.get(`${resourcePath}/${row.pid}`, { headers: machineHeaders })).status()).toBe(404);
    let deniedAccountCreated = false;
    await verifyModelNotifications({ page, card: installationCard, installationPid: installation.pid, resourceCode, resourcePath, machineHeaders,
      locale: installationLocale, evidenceRoot: screenshotRoot, testInfo, withDeniedSession: async action => {
        const adminCookies = await page.context().cookies(WEB_BASE_URL);
        const account = { email: `dop-notify-denied-${suffix}@op-closure.test`, password: 'Closure2026x!' };
        if (!deniedAccountCreated) { await human('POST', '/api/meta/commands/execute/admin:create_member', { payload: { name: `Notification denied ${suffix}`, ...account } }); deniedAccountCreated = true; }
        try { await authenticate(page, installationLocale, account); await action(); }
        finally { await page.context().addCookies(adminCookies); }
      }, revoke: async () => {
    await editor.getByRole('button', { name: installationText('撤销安装授权'), exact: true }).click(); await snapshot('grant-revoke-dialog');
    await editor.getByRole('group', { name: installationText('安装授权操作确认'), exact: true }).getByRole('button', { name: installationText('确认'), exact: true }).click(); await expect(editor.getByRole('status').filter({ hasText: installationText('授权已撤销') })).toBeVisible();
    expect((await page.request.post(queryPath, { headers: machineHeaders, data: { parameters: { zone: 'A' }, limit: 10 } })).status()).toBe(403); await snapshot('grant-query-revoked');
    await editor.getByLabel(installationText('授权合同'), { exact: true }).selectOption(`resource:${resourceCode}`); await editor.getByRole('button', { name: installationText('撤销安装授权'), exact: true }).click();
    await editor.getByRole('group', { name: installationText('安装授权操作确认'), exact: true }).getByRole('button', { name: installationText('确认'), exact: true }).click(); await expect(editor.getByRole('status').filter({ hasText: installationText('授权已撤销') })).toBeVisible();
    expect((await page.request.get(resourcePath, { headers: machineHeaders })).status()).toBe(403); await snapshot('grant-resource-revoked');
    const persisted = await human('GET', `/api/open-platform/installations/${installation.pid}/resources`); expect(persisted.find((view: { code: string }) => view.code === resourceCode).policy).toBeNull();
    } });
  });
});

async function authenticate(page: import('@playwright/test').Page, locale = 'zh-CN', account: { email: string; password: string } = DEFAULT_TEST_ACCOUNT) {
  const loginResponse = await page.request.post(`${BACKEND_URL}/api/auth/login`, {
    data: { email: account.email, password: account.password },
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
    { name: 'locale', value: locale, url: WEB_BASE_URL, sameSite: 'Lax' },
  ]);
  await page.addInitScript(value => localStorage.setItem('locale', value), locale);

}

async function verifyBusyPublish(page: import('@playwright/test').Page, group: import('@playwright/test').Locator,
  confirmName: string, cancelName: string, pathname: string) {
  let release!: () => void;
  const gate = new Promise<void>(resolve => { release = resolve; });
  let started!: (response: import('@playwright/test').APIResponse) => void;
  let failed!: (error: unknown) => void;
  const responseReady = new Promise<import('@playwright/test').APIResponse>((resolve, reject) => { started = resolve; failed = reject; });
  let requests = 0;
  const pattern = `**${pathname}`;
  let received: Promise<import('@playwright/test').Response> | undefined;
  let actualFetched = false;
  await page.route(pattern, async route => {
    try {
      expect(route.request().method()).toBe('POST');
      requests++;
      const response = await route.fetch();
      started(response);
      await gate;
      await route.fulfill({ response });
    } catch (error) { failed(error); throw error; }
  });
  try {
    await activateWithEnter(group.getByRole('button', { name: confirmName, exact: true }));
    const actual = await responseReady;
    actualFetched = true;
    expect(actual.status()).toBe(200);
    expect((await actual.json()).data).toMatchObject({ activeVersion: 1, status: 'published' });
    await expect(group.getByRole('button', { name: confirmName, exact: true })).toBeDisabled();
    await expect(group.getByRole('button', { name: cancelName, exact: true })).toBeDisabled();
    await expect(group).toBeFocused();
    await page.keyboard.press('Escape');
    await expect(group).toBeVisible();
    await expect(group).toBeFocused();
    expect(requests).toBe(1);
    const root = path.join(process.env.AURA_EVIDENCE_ROOT || '/tmp', 'ui-publication-r1', 'screenshots');
    await mkdir(root, { recursive: true });
    await group.screenshot({ path: path.join(root, 'query-publish-busy.png') });
    received = page.waitForResponse(response => new URL(response.url()).pathname === pathname && response.request().method() === 'POST');
    release();
    const clientResponse = await received;
    expect(clientResponse.status()).toBe(200);
    expect((await clientResponse.json()).data).toMatchObject({ activeVersion: 1, status: 'published' });
    await expect(group).toHaveCount(0);
    expect(requests).toBe(1);
    await test.info().attach('busy-confirmation-publication', { contentType: 'application/json', body: JSON.stringify({
      schemaVersion: 1, driver: 'actual-http-held-response', requests: 1,
      cases: ['busy-disabled-controls', 'focused-escape-keeps-operation', 'single-real-publish-completes'],
    }) });
  } finally {
    if (actualFetched && !received) received = page.waitForResponse(response => new URL(response.url()).pathname === pathname && response.request().method() === 'POST');
    release();
    if (received) await received;
    await page.unroute(pattern);
  }
}

async function verifyConfirmedDiscard(page: import('@playwright/test').Page,
  close: import('@playwright/test').Locator, group: import('@playwright/test').Locator, confirmName: string,
  reopen: import('@playwright/test').Locator, restored: import('@playwright/test').Locator, value: string,
  id: string, writePrefix: string, configure?: () => Promise<void>) {
  let writes = 0;
  const observe = (request: import('@playwright/test').Request) => {
    if (!['GET', 'HEAD'].includes(request.method()) && new URL(request.url()).pathname.startsWith(writePrefix)) writes++;
  };
  page.on('request', observe);
  await activateWithEnter(close);
  await expect(group).toBeVisible();
  const root = path.join(process.env.AURA_EVIDENCE_ROOT || '/tmp', 'ui-publication-r1', 'screenshots');
  await mkdir(root, { recursive: true });
  await group.screenshot({ path: path.join(root, `${id}.png`) });
  await activateWithEnter(group.getByRole('button', { name: confirmName, exact: true }));
  await expect(group).toHaveCount(0);
  await expect(reopen).toBeFocused();
  await activateWithEnter(reopen);
  await configure?.();
  await expect(restored).toHaveValue(value);
  expect(writes).toBe(0);
  page.off('request', observe);
  await test.info().attach('confirmed-discard-recovery', { contentType: 'application/json', body: JSON.stringify({
    schemaVersion: 1, driver: 'native-keyboard', writes: 0,
    cases: ['confirmed-discard-focus-return', 'no-write-server-state-restored'],
  }) });
}

async function verifyDraftCancel(page: import('@playwright/test').Page,
  trigger: import('@playwright/test').Locator, group: import('@playwright/test').Locator,
  retained: import('@playwright/test').Locator, value: string, confirmName: string, cancelName: string,
  id: string, writePrefix: string) {
  let writes = 0;
  const observe = (request: import('@playwright/test').Request) => {
    if (!['GET', 'HEAD'].includes(request.method()) && new URL(request.url()).pathname.startsWith(writePrefix)) writes++;
  };
  page.on('request', observe);
  await activateWithEnter(trigger);
  await expect(group).toBeVisible();
  await expect(group.getByRole('button', { name: confirmName, exact: true })).toBeFocused();
  const root = path.join(process.env.AURA_EVIDENCE_ROOT || '/tmp', 'ui-publication-r1', 'screenshots');
  await mkdir(root, { recursive: true });
  await group.screenshot({ path: path.join(root, `${id}.png`) });
  await activateWithEnter(group.getByRole('button', { name: cancelName, exact: true }));
  await expect(group).toHaveCount(0);
  await expect(trigger).toBeFocused();
  await expect(retained).toHaveValue(value);
  await activateWithEnter(trigger);
  await expect(group.getByRole('button', { name: confirmName, exact: true })).toBeFocused();
  await page.keyboard.press('Escape');
  await expect(group).toHaveCount(0);
  await expect(trigger).toBeFocused();
  await expect(retained).toHaveValue(value);
  expect(writes).toBe(0);
  page.off('request', observe);
  await test.info().attach('draft-cancel-recovery', { contentType: 'application/json', body: JSON.stringify({
    schemaVersion: 1, driver: 'native-keyboard', writes: 0,
    cases: ['enter-cancel-focus-return', 'escape-cancel-focus-return', 'draft-retained-no-write'],
  }) });
}

async function verifyEditorTabOrder(page: import('@playwright/test').Page,
  controls: [import('@playwright/test').Locator, string][], id: string) {
  // Anchor once, then let the browser choose every forward and reverse tab stop.
  for (const [control, name] of controls) {
    await expect(control).toBeVisible();
    await expect(control).toBeEnabled();
    await expect(control).toHaveAccessibleName(name);
  }
  if (UI_MUTATION === 'editor-tab-order') {
    await controls[1][0].evaluate(element => element.setAttribute('tabindex', '-1'));
  }
  await controls[0][0].focus();
  for (let index = 0; index < controls.length; index++) {
    if (index) await page.keyboard.press('Tab');
    await expect(controls[index][0]).toBeFocused();
  }
  for (let index = controls.length - 2; index >= 0; index--) {
    await page.keyboard.press('Shift+Tab');
    await expect(controls[index][0]).toBeFocused();
  }
  const root = path.join(process.env.AURA_EVIDENCE_ROOT || '/tmp', 'ui-publication-r1');
  await mkdir(root, { recursive: true });
  const proof = {
    schemaVersion: 1, driver: 'native-keyboard', controlCount: controls.length,
    cases: ['accessible-names', 'native-forward-tab', 'native-reverse-tab'],
    scope: 'Browser accessible names and anchored bidirectional native tab traversal; native screen reader not exercised',
    controls: controls.map(([, name]) => name), forwardTransitions: controls.length - 1,
    reverseTransitions: controls.length - 1,
  };
  await writeFile(path.join(root, `${id}.json`), JSON.stringify(proof, null, 2));
  await test.info().attach('source-keyboard-controls', { contentType: 'application/json', body: JSON.stringify(proof) });
}

async function activateWithEnter(control: import('@playwright/test').Locator) {
  // Unlike click(), press() does not wait for a disabled fieldset to become ready.
  await expect(control).toBeVisible();
  await expect(control).toBeEnabled();
  await control.focus();
  await expect(control).toBeFocused();
  await control.press('Enter');
}

async function capture(page: import('@playwright/test').Page, id: string, fullPage = true) {
  await page.screenshot({
    path: path.join(EVIDENCE_DIR, `${id}.png`),
    fullPage,
    timeout: 30_000,
  });
}

const protocolVisibleLeak = /\b(?:openPlatform|common)\.[A-Za-z][A-Za-z0-9_.-]*|\bBearer\s+\S+|https?:\/\/\S+|super-secret|\bhook-(?:healthy|due|overdue|missing|incompatible)\b|\bmobile-overdue\b|\{\s*"(?:data|pid|tenantId|secret|token|tokenPid|remoteAddress|installationId|credentialPid)"\s*:/;
const protocolCaptureChecks: string[] = [];

async function assertProtocolRegionSafe(locator: import('@playwright/test').Locator, id: string) {
  expect(await locator.innerText(), `OP-PROTO-12 visible leak guard: ${id}`).not.toMatch(protocolVisibleLeak);
}

async function captureLocator(locator: import('@playwright/test').Locator, id: string) {
  await locator.scrollIntoViewIfNeeded();
  if (/^OP-PROTO-\d+$/.test(id)) {
    await assertProtocolRegionSafe(locator, id);
    if (id === 'OP-PROTO-01') {
      const counterexamples = [
        ['i18n', 'openPlatform.rotation.overdue'],
        ['token', 'Bearer protocol-fixture-token'],
        ['url', 'https://protocol-fixture.invalid/hook'],
        ['payload', '{"data":{"privateFixture":"leak"}}'],
        ['internal-field', '{"tokenPid":"protocol-fixture-internal"}'],
        ['internal-id', 'hook-healthy'],
      ];
      for (const [kind, text] of counterexamples) {
        await locator.evaluate((element, value) => {
          const probe = document.createElement('span');
          probe.dataset.protocolLeakProbe = 'true';
          probe.textContent = value;
          element.append(probe);
        }, text);
        try {
          await expect(assertProtocolRegionSafe(locator, `${id}:${kind}`)).rejects.toThrow();
        } finally {
          await locator.locator('[data-protocol-leak-probe]').evaluateAll(elements => elements.forEach(element => element.remove()));
        }
        await assertProtocolRegionSafe(locator, `${id}:restored`);
      }
      await writeFile(path.join(EVIDENCE_DIR, 'protocol-leak-counterexamples.json'), JSON.stringify({
        surface: 'browser DOM acceptance guard', persisted: false,
        cases: counterexamples.map(([kind]) => ({ kind, rejected: true })),
      }, null, 2));
      if (UI_MUTATION === 'protocol-visible-leak') {
        await locator.evaluate(element => {
          const probe = document.createElement('span');
          probe.textContent = 'Bearer protocol-fixture-token';
          element.append(probe);
        });
      }
    }
    await assertProtocolRegionSafe(locator, id);
    protocolCaptureChecks.push(id);
    await writeFile(path.join(EVIDENCE_DIR, 'protocol-capture-checks.json'), JSON.stringify({
      authority: 'per-capture visible DOM assertion; visual review remains separate',
      ids: protocolCaptureChecks,
    }, null, 2));
  }
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
    const existingResponse = await page.request.get(
      `${WEB_BASE_URL}/api/open-platform/applications`,
    );
    expect(existingResponse.status()).toBe(200);
    const existing = await existingResponse.json();
    expect(String(existing.code)).toBe('0');
    const existingPids = (existing.data as Array<{ pid: string }>).map((entry) => entry.pid);
    // Empty-state rendering is a controlled UI projection, not a claim that the DB is empty.
    await page.route('**/api/open-platform/applications', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ code: '0', data: [] }),
      });
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
      await expect(
        page.getByTestId('open-platform-application-list').locator('article'),
      ).toHaveCount(existingPids.length);
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
    const appCard = page
      .getByTestId('open-platform-application-list')
      .locator('article')
      .filter({ hasText: appName });
    await expect(appCard).toHaveCount(1);
    await expect(appCard.getByText(appName, { exact: true })).toBeVisible();
    await expect(appCard.getByText('生产订单与库存事件统一接入')).toBeVisible();
    await expect(page.getByTestId('open-platform-page')).not.toContainText(
      /01[A-Z0-9]{20,}|[0-9a-f]{8}-[0-9a-f-]{27,}/i,
    );
    await capture(page, 'OP-UI-06');
    await dismissToasts(page);

    // Published dynamic contracts extend the real capability catalog in a shared tenant.
    const capabilitiesResponse = await page.request.get(
      `${WEB_BASE_URL}/api/open-platform/capabilities`,
    );
    expect(capabilitiesResponse.status()).toBe(200);
    const capabilitiesBody = await capabilitiesResponse.json();
    expect(String(capabilitiesBody.code)).toBe('0');
    const catalogScopes = [
      ...new Set(
        (capabilitiesBody.data as Array<{ requiredScope: string }>).map(
          (capability) => capability.requiredScope,
        ),
      ),
    ].sort();
    expect(catalogScopes).toEqual(
      expect.arrayContaining([
        'assets.manage',
        'assets.read',
        'inventory.stock-ins.read',
        'inventory.stock-ins.manage',
        'openapi.events.read',
        'automation.events.write',
        'openapi.profile.read',
      ]),
    );
    await appCard.getByRole('button', { name: '添加安装' }).click();
    const installDialog = page.getByRole('dialog', { name: '安装应用' });
    await installDialog.getByLabel('环境').selectOption('production');
    await installDialog.getByTestId('open-platform-rate-limit').fill('1200');
    const scopeChecks = installDialog.getByRole('checkbox');
    await expect(scopeChecks.first()).toBeChecked();
    await expect(scopeChecks).toHaveCount(catalogScopes.length);
    for (const scope of catalogScopes)
      await expect(installDialog.getByLabel(scope, { exact: true })).toHaveCount(1);
    await installDialog.getByLabel('assets.read').check();
    await installDialog.getByLabel('inventory.stock-ins.read').check();
    await installDialog.getByLabel('inventory.stock-ins.manage').check();
    await installDialog.getByLabel('openapi.events.read').check();
    await installDialog.getByLabel('automation.events.write').check();
    await installDialog.getByLabel('openapi.profile.read').check();
    await installDialog
      .getByRole('heading', { name: '安装应用', exact: true })
      .scrollIntoViewIfNeeded();
    await installDialog.getByLabel('环境').scrollIntoViewIfNeeded();
    await expect(installDialog.getByLabel('环境')).toBeInViewport({ ratio: 1 });
    await expect(installDialog.getByRole('button', { name: '安装', exact: true })).toBeInViewport({
      ratio: 1,
    });
    await expect(installDialog.getByRole('button', { name: '取消', exact: true })).toBeInViewport({
      ratio: 1,
    });
    await capture(page, 'OP-UI-07-top', false);
    const lastInstallationScope = installDialog.getByRole('checkbox').last().locator('..');
    await lastInstallationScope.scrollIntoViewIfNeeded();
    await expect(lastInstallationScope).toBeInViewport({ ratio: 1 });
    await expect(installDialog.getByRole('checkbox').last()).toBeInViewport({ ratio: 1 });
    await installDialog.getByRole('button', { name: '安装', exact: true }).scrollIntoViewIfNeeded();
    await expect(installDialog.getByRole('button', { name: '安装', exact: true })).toBeInViewport({
      ratio: 1,
    });
    await expect(installDialog.getByRole('button', { name: '取消', exact: true })).toBeInViewport({
      ratio: 1,
    });
    await capture(page, 'OP-UI-07-actions', false);
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
      await expect(authorizedReference.locator('.opblock').first()).toBeVisible({
        timeout: 30_000,
      });
      const publicDocument = await authorizedReference.request.get(
        `${WEB_BASE_URL}/v3/api-docs/open-platform`,
      );
      expect(publicDocument.status()).toBe(200);
      expect((await publicDocument.json()).servers).toEqual([{ url: '/' }]);
      await authorizedReference
        .getByRole('button', { name: 'Authorize', exact: true })
        .first()
        .click();
      const authorizationDialog = authorizedReference.locator('.dialog-ux');
      await expect(authorizationDialog).toBeVisible();
      const oauthAuthorization = authorizationDialog.locator('.auth-container').filter({
        hasText: 'oauth2ClientCredentials',
      });
      await oauthAuthorization.getByLabel('client_id:', { exact: true }).fill(clientId!);
      await oauthAuthorization.getByLabel('client_secret:', { exact: true }).fill(clientSecret!);
      const browserTokenPromise = authorizedReference.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          response.url() === `${WEB_BASE_URL}/oauth2/token`,
      );
      await oauthAuthorization
        .getByRole('button', {
          name: 'Apply given OAuth2 credentials',
          exact: true,
        })
        .click();
      const browserTokenResponse = await browserTokenPromise;
      expect(browserTokenResponse.status()).toBe(200);
      const browserToken = await browserTokenResponse.json();
      expect(browserToken.token_type).toBe('Bearer');
      await expect(
        oauthAuthorization.getByRole('button', {
          name: 'Remove authorization',
          exact: true,
        }),
      ).toBeVisible();
      await oauthAuthorization.getByRole('button', { name: 'Close', exact: true }).click();
      await expect(authorizationDialog).toHaveCount(0);
      const whoamiOperation = authorizedReference.locator('.opblock').filter({
        has: authorizedReference.locator('[data-path="/api/open/v1/whoami"]'),
      });
      await whoamiOperation.locator('.opblock-summary-control').click();
      // The product enables Try it out by default in its Swagger configuration.
      await expect(
        whoamiOperation.getByRole('button', { name: 'Cancel', exact: true }),
      ).toBeVisible();
      const browserWhoamiPromise = authorizedReference.waitForResponse(
        (response) =>
          response.request().method() === 'GET' &&
          response.url() === `${WEB_BASE_URL}/api/open/v1/whoami`,
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
    const publishedResourcesResponse = await page.request.get(
      `${WEB_BASE_URL}/api/open-platform/installations/${principal.installationPid}/resources`,
    );
    expect(publishedResourcesResponse.status()).toBe(200);
    const publishedResources = await publishedResourcesResponse.json();
    expect(String(publishedResources.code)).toBe('0');
    const modelEvents = publishedResources.data
      .filter((view: { status: string }) => view.status === 'published')
      .flatMap((view: { code: string; contract: { events: string[] } }) =>
        view.contract.events.map(operation => `open.resource.${view.code}.${operation}`));
    expect((await publicCatalog.json()).map((item: { type: string }) => item.type).sort()).toEqual([
      'assets.assignment.changed',
      'inventory.stock-in.confirmed',
      ...modelEvents,
    ].sort());

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
    await captureLocator(operations.getByTestId('open-platform-call-audit'), 'OP-OPS-03');

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
    const healthFixtureNow = Date.now();
    const fixtureDayMs = 24 * 60 * 60 * 1000;
    const rotationDueDates = {
      healthy: new Date(healthFixtureNow + 30 * fixtureDayMs).toISOString(),
      due: new Date(healthFixtureNow + 7 * fixtureDayMs).toISOString(),
      overdue: new Date(healthFixtureNow - 7 * fixtureDayMs).toISOString(),
    };
    expect(Date.parse(rotationDueDates.healthy) - healthFixtureNow).toBeGreaterThan(14 * fixtureDayMs);
    expect(Date.parse(rotationDueDates.due) - healthFixtureNow).toBeGreaterThan(0);
    expect(Date.parse(rotationDueDates.due) - healthFixtureNow).toBeLessThanOrEqual(14 * fixtureDayMs);
    expect(Date.parse(rotationDueDates.overdue)).toBeLessThan(healthFixtureNow);
    const displayedRotationDates = await page.evaluate((dates) => {
      const format = new Intl.DateTimeFormat('zh-CN', {
        month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
      });
      return Object.fromEntries(Object.entries(dates).map(([status, date]) =>
        [status, format.format(new Date(date))]));
    }, rotationDueDates);
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
              rotationDueAt: rotationDueDates.healthy,
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
              rotationDueAt: rotationDueDates.due,
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
              rotationDueAt: rotationDueDates.overdue,
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
    await expect(webhookHealth.locator('[data-rotation-status="healthy"]').first()).toContainText(
      '目录版本 v1',
    );
    await expect(webhookHealth.locator('[data-rotation-status="healthy"]').first()).toContainText(
      displayedRotationDates.healthy,
    );
    await expect(webhookHealth.locator('[data-rotation-status="due"]')).toContainText(displayedRotationDates.due);
    await expect(webhookHealth.locator('[data-rotation-status="overdue"]')).toContainText(displayedRotationDates.overdue);
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
    for (const [requestId, status] of [['req-forbidden', '403'], ['req-throttled', '429'], ['req-failed', '500']]) {
      const auditRow = callAudit.getByText(requestId, { exact: true }).locator('../..');
      await expect(auditRow).toHaveCount(1);
      await expect(auditRow.getByText(status, { exact: true })).toBeVisible();
    }
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
    await expect(operations.getByTestId('webhook-delivery-delivery-success')).toContainText('HTTP 204');
    await expect(operations.getByTestId('webhook-delivery-delivery-success')).toContainText('0/5');
    await expect(operations.getByTestId('webhook-delivery-delivery-pending')).toContainText('待投递');
    await expect(operations.getByTestId('webhook-delivery-delivery-golden')).toContainText('5/5');
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
    await expect(replayConfirmation).toContainText('可能重复触发下游副作用');
    await replayConfirmation.scrollIntoViewIfNeeded();
    await capture(page, 'OP-OPS-08', false);
    await replayConfirmation.getByRole('button', { name: '取消', exact: true }).click();
    await expect(replayConfirmation).toHaveCount(0);
    expect(replayed, 'Cancelling replay must not submit a replay request').toBe(false);
    await expect(operations.getByTestId('webhook-delivery-delivery-golden')).toContainText('5/5');
    await operations.getByRole('button', { name: '重放', exact: true }).click();
    await operations.getByRole('button', { name: '确认重放' }).click();
    await expect(operations).toContainText(/没有.*筛选条件的投递/);
    await operations.getByLabel('投递状态').selectOption('');
    const replayedDelivery = operations.getByTestId('webhook-delivery-delivery-golden');
    await expect(replayedDelivery).toContainText('evt-golden');
    await expect(replayedDelivery).toContainText('待投递');
    await expect(replayedDelivery).toContainText('0/5');
    await expect(replayedDelivery).not.toContainText('HTTP 503');
    await replayedDelivery.scrollIntoViewIfNeeded();
    await captureLocator(replayedDelivery, 'OP-OPS-09');
    await dismissToasts(page);
    await expect(operations.getByRole('button', { name: '重放' })).toHaveCount(0);
    await captureLocator(replayedDelivery, 'OP-OPS-10');
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
    await expect(operations).toContainText(/没有.*筛选条件的投递/);
    await expect(operations.getByText('P95', { exact: true }).locator('..')).toContainText('0 ms');
    await expect(operations.getByText('HTTP 429', { exact: true }).locator('..')).toContainText('0');
    for (const label of ['调用数', '错误调用', '死信']) {
      await expect(operations.getByText(label, { exact: true }).locator('..').locator('div').last()).toHaveText('0');
    }
    await expect(operations.getByText('错误率', { exact: true }).locator('..')).toContainText('0.00%');
    await expect(eventCatalog).not.toContainText('inventory.stock-in.confirmed');
    await expect(eventCatalog.locator('code')).toHaveCount(0);
    await expect(webhookHealth.locator('[data-rotation-status]')).toHaveCount(0);
    await expect(eventCatalog).toContainText('暂无已发布的合作事件');
    await expect(webhookHealth).toContainText('此安装暂无 Webhook 订阅');
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
    await expect(operations).not.toContainText('受控运维加载失败');

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
    await expect(rotateDialog.getByRole('spinbutton')).toHaveAccessibleDescription(
      '请输入 5 至 10080 的整数分钟。',
    );
    await capture(page, 'OP-OPS-12');
    const credentialsBeforeCancel = await page.request.get(
      `${WEB_BASE_URL}/api/open-platform/installations/${principal.installationPid}/credentials`,
    );
    expect(credentialsBeforeCancel.status()).toBe(200);
    const beforeCancelledRotation = (await credentialsBeforeCancel.json()).data;
    await rotateDialog.getByRole('button', { name: '取消' }).click();
    await expect(rotateDialog).toHaveCount(0);
    const credentialsAfterCancel = await page.request.get(
      `${WEB_BASE_URL}/api/open-platform/installations/${principal.installationPid}/credentials`,
    );
    expect(credentialsAfterCancel.status()).toBe(200);
    expect((await credentialsAfterCancel.json()).data).toEqual(beforeCancelledRotation);
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
    const rotatedCredentialsResponse = await page.request.get(
      `${WEB_BASE_URL}/api/open-platform/installations/${principal.installationPid}/credentials`,
    );
    expect(rotatedCredentialsResponse.status()).toBe(200);
    const rotatedCredentials = await rotatedCredentialsResponse.json();
    expect(String(rotatedCredentials.code)).toBe('0');
    const previousCredential = rotatedCredentials.data.find(
      (credential: { clientId: string }) => credential.clientId === clientId,
    );
    expect(previousCredential.expiresAt).toEqual(expect.any(String));
    expect(Date.parse(previousCredential.expiresAt)).toBeGreaterThan(Date.now());
    const previousCredentialRow = credentialList.getByTestId(
      `open-platform-credential-${previousCredential.pid}`,
    );
    await expect(previousCredentialRow).toContainText('到期时间');
    await expect(previousCredentialRow.locator('time')).toHaveAttribute(
      'datetime', previousCredential.expiresAt,
    );
    await expect(previousCredentialRow.locator('time')).toBeVisible();
    await captureLocator(previousCredentialRow, 'OP-OPS-11-expiry');


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

    await dismissToasts(page);
    await appCard.getByRole('button', { name: '编辑 Scope' }).click();
    const scopeDialog = page.getByRole('dialog', { name: '编辑 Scope' });
    await expect(scopeDialog).toContainText('立即吊销此安装的现有 Access Token');
    await scopeDialog
      .getByRole('heading', { name: '编辑 Scope', exact: true })
      .scrollIntoViewIfNeeded();
    await expect(
      scopeDialog.getByRole('heading', { name: '编辑 Scope', exact: true }),
    ).toBeInViewport();
    await expect(scopeDialog.getByRole('button', { name: '保存', exact: true })).toBeInViewport({
      ratio: 1,
    });
    await expect(scopeDialog.getByRole('button', { name: '取消', exact: true })).toBeInViewport({
      ratio: 1,
    });
    await capture(page, 'OP-UI-10-top', false);
    const lastEditableScope = scopeDialog.getByRole('checkbox').last().locator('..');
    await lastEditableScope.scrollIntoViewIfNeeded();
    if (UI_MUTATION === 'scope-label-clipping') {
      // A declared test-only layout fault must be rejected by the original full-label assertion.
      const geometry = await lastEditableScope.evaluate(element => {
        const before = element.getBoundingClientRect();
        (element as HTMLElement).style.transform = 'translateY(48px)';
        const after = element.getBoundingClientRect();
        return { beforeTop: before.top, beforeBottom: before.bottom, afterTop: after.top, afterBottom: after.bottom };
      });
      console.log('Open Platform UI mutation scope-label-clipping:', JSON.stringify(geometry));
    }
    await expect(lastEditableScope).toBeInViewport({ ratio: 1 });
    await expect(scopeDialog.getByRole('checkbox').last()).toBeInViewport({ ratio: 1 });
    await scopeDialog.getByRole('button', { name: '保存', exact: true }).scrollIntoViewIfNeeded();
    await expect(scopeDialog.getByRole('button', { name: '保存', exact: true })).toBeInViewport({
      ratio: 1,
    });
    await expect(scopeDialog.getByRole('button', { name: '取消', exact: true })).toBeInViewport({
      ratio: 1,
    });
    await capture(page, 'OP-UI-10-actions', false);
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

    const verifyInactiveRotationUI = async () => {
      await expect(credentialList.getByRole('status')).toHaveText('访问已停用，有效 Token 已吊销');
      const rotateControls = credentialList.getByRole('button', { name: '轮换', exact: true });
      await expect(rotateControls).toHaveCount(2);
      const attempts: string[] = [];
      const observe = (req: import('@playwright/test').Request) => {
        if (
          req.method() === 'POST' &&
          req.url().includes('/credentials/') &&
          req.url().endsWith('/rotate')
        )
          attempts.push(req.url());
      };
      page.on('request', observe);
      try {
        for (const control of await rotateControls.all()) {
          await expect(control).toBeDisabled();
          await expect(control).toHaveAttribute('title', '访问已停用，有效 Token 已吊销');
          await control.scrollIntoViewIfNeeded();
          const box = await control.boundingBox();
          expect(box).not.toBeNull();
          await page.mouse.click(box!.x + box!.width / 2, box!.y + box!.height / 2);
          await expect(page.getByRole('dialog', { name: '轮换凭据', exact: true })).toHaveCount(0);
        }
        await page.evaluate(
          () =>
            new Promise<void>((resolve) =>
              requestAnimationFrame(() => requestAnimationFrame(() => resolve())),
            ),
        );
        expect(attempts).toEqual([]);
      } finally {
        page.off('request', observe);
      }
    };
    await verifyInactiveRotationUI();

    // Probe the real management API, never the controlled operations rendering fixtures.
    const verifyDisabledCredentialWrites = async () => {
      const credentialPath = `${WEB_BASE_URL}/api/open-platform/installations/${principal.installationPid}/credentials`;
      const beforeResponse = await page.request.get(credentialPath);
      expect(beforeResponse.status()).toBe(200);
      const before = await beforeResponse.json();
      expect(String(before.code)).toBe('0');
      const target = before.data.find(
        (entry: { clientId: string }) => entry.clientId === rotatedClientId,
      );
      expect(target).toMatchObject({ status: 'active', expiresAt: null });
      expect(before.data.length).toBeGreaterThanOrEqual(2);
      const createDenied = await page.request.post(credentialPath);
      expect(createDenied.status()).toBe(404);
      expect(String((await createDenied.json()).code)).toBe('404');
      const rotateDenied = await page.request.post(`${credentialPath}/${target.pid}/rotate`, {
        data: { graceMinutes: 15 },
      });
      expect(rotateDenied.status()).toBe(404);
      expect(String((await rotateDenied.json()).code)).toBe('404');
      const afterResponse = await page.request.get(credentialPath);
      expect(afterResponse.status()).toBe(200);
      expect((await afterResponse.json()).data).toEqual(before.data);
      const deniedIssue = await page.request.post(`${BACKEND_URL}/oauth2/token`, {
        form: {
          grant_type: 'client_credentials',
          client_id: rotatedClientId!,
          client_secret: rotatedClientSecret!,
          scope: 'openapi.profile.read',
        },
      });
      expect(deniedIssue.status()).toBe(401);
    };
    await verifyDisabledCredentialWrites();

    await appCard.getByRole('button', { name: '停用', exact: true }).click();
    const disableApplicationDialog = page.getByRole('dialog', { name: '确认停用访问？' });
    await expect(disableApplicationDialog).toContainText('无法在本页面恢复');
    await expect
      .poll(async () => (await disableApplicationDialog.boundingBox())?.width ?? 0)
      .toBeGreaterThan(400);
    await capture(page, 'OP-UI-12');
    await disableApplicationDialog.getByRole('button', { name: '停用' }).click();
    await expect(appCard.getByRole('button', { name: '添加安装' })).toBeDisabled();
    await verifyInactiveRotationUI();
    await verifyDisabledCredentialWrites();
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
              rotationDueAt: rotationDueDates.overdue,
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
      const control = headerActions.getByRole(action === '公共资源发布' ? 'link' : 'button', {
        name: action,
      });
      await expect(control).toHaveCSS('white-space', 'nowrap');
      await expect(control).toBeInViewport();
    }
    await capture(page, 'OP-OPS-16-top', false);
    await expect(operations).toContainText('open-platform-golden-whoami');
    const mobileAudit = operations.getByTestId('open-platform-call-audit');
    await mobileAudit.scrollIntoViewIfNeeded();
    await expect(mobileAudit.locator('code').first()).toBeInViewport();
    // Settle the resized scroll container before capturing its painted content.
    await page.evaluate(
      () =>
        new Promise<void>((resolve) => {
          requestAnimationFrame(() => requestAnimationFrame(() => resolve()));
        }),
    );
    await capture(page, 'OP-OPS-16', false);
    await eventCatalog.scrollIntoViewIfNeeded();
    await captureLocator(eventCatalog, 'OP-PROTO-10');
    await expect
      .poll(() => webhookHealth.evaluate((element) => element.scrollWidth - element.clientWidth))
      .toBeLessThanOrEqual(1);
    await webhookHealth.scrollIntoViewIfNeeded();
    await captureLocator(webhookHealth, 'OP-PROTO-11');
    const preservedResponse = await page.request.get(
      `${WEB_BASE_URL}/api/open-platform/applications`,
    );
    expect(preservedResponse.status()).toBe(200);
    const preserved = await preservedResponse.json();
    expect(String(preserved.code)).toBe('0');
    const preservedPids = (preserved.data as Array<{ pid: string }>).map((entry) => entry.pid);
    expect(preservedPids).toEqual(expect.arrayContaining(existingPids));
  });
});

// Explicit masked captures replace automatic trace/video/screenshots for one-time secrets.
test.describe('Quick integration token journey', () => {
  test.setTimeout(120_000);
  test.use({ storageState: { cookies: [], origins: [] }, locale: 'en-US', viewport: publicationViewport });
  test('generates expiring and permanent tokens, rotates and revokes through the installation UI', async ({ page }) => {
    await authenticate(page, 'en-US');
    const name = `Office token acceptance ${Date.now()}`;
    const created = await page.request.post(`${WEB_BASE_URL}/api/open-platform/applications`, { data: { name } });
    expect(created.status()).toBe(200); const app = (await created.json()).data;
    const installed = await page.request.post(`${WEB_BASE_URL}/api/open-platform/applications/${app.pid}/installations`,
      { data: { environment: 'development', scopes: ['openapi.profile.read'], rateLimitPerMinute: 10000 } });
    expect(installed.status()).toBe(200); const installation = (await installed.json()).data;
    const evidence = path.join(EVIDENCE_DIR, `quick-token-${PUBLICATION_VIEWPORT}`); await mkdir(evidence, { recursive: true });
    const captureToken = async (id: string) => {
      await page.screenshot({ path: path.join(evidence, `${id}.png`), fullPage: true,
        mask: [page.getByTestId('integration-token-secret').locator('textarea')] });
    };
    const machineStatus = async (token: string, status: number) => {
      const result = await page.request.get(`${BACKEND_URL}/api/open/v1/whoami`, { headers: { Authorization: `Bearer ${token}` } });
      expect(result.status()).toBe(status);
      if (status === 200) { const identity = await result.json(); expect(identity.applicationPid).toBe(app.pid); expect(identity.installationPid).toBe(installation.pid); }
    };
    await page.goto('/'); await openAccountMenu(page); await page.getByTestId('open-platform-link').click();
    const appCard = page.getByTestId('open-platform-application-list').locator('article').filter({ hasText: name });
    await appCard.getByTestId(`open-platform-tokens-${installation.pid}`).click();
    let panel = page.getByTestId('integration-token-panel');
    await expect(panel.getByText('No integration tokens yet.', { exact: true })).toBeVisible();
    await captureToken('QAT-07-empty');
    await panel.getByRole('button', { name: 'Generate token', exact: true }).click();
    await panel.getByLabel('Token name', { exact: true }).fill('Office expiring');
    await expect(panel.getByLabel('Expiration mode', { exact: true })).toHaveValue('fixed');
    await expect(panel.getByLabel('Valid for days', { exact: true })).toHaveValue('30');
    await captureToken('QAT-07-fixed-form');
    await panel.getByRole('button', { name: 'Cancel', exact: true }).click();
    await expect(panel.getByTestId('integration-token-row')).toHaveCount(0);
    await panel.getByRole('button', { name: 'Generate token', exact: true }).click();
    await panel.getByLabel('Token name', { exact: true }).fill('Office expiring');
    const issuedFixed = page.waitForResponse(r => r.url().endsWith(`/installations/${installation.pid}/integration-tokens`) && r.request().method() === 'POST');
    await panel.getByRole('button', { name: 'Confirm', exact: true }).click();
    const fixedResponse = await issuedFixed; expect(fixedResponse.status()).toBe(200);
    expect(fixedResponse.headers()['cache-control']).toContain('no-store');
    await expect(panel.getByLabel('New token', { exact: true })).toBeVisible();
    const fixed = await panel.getByLabel('New token', { exact: true }).inputValue();
    expect(fixed.startsWith('ab_at_')).toBe(true); await machineStatus(fixed, 200);
    await captureToken('QAT-07-fixed-secret');
    await panel.getByRole('button', { name: 'Saved; close secret', exact: true }).click();
    await page.reload(); await appCard.getByTestId(`open-platform-tokens-${installation.pid}`).click();
    panel = page.getByTestId('integration-token-panel');
    await expect(panel.getByTestId('integration-token-row')).toHaveCount(1);
    await expect(panel.getByLabel('New token', { exact: true })).toHaveCount(0);
    await panel.getByRole('button', { name: 'Generate token', exact: true }).click();
    await panel.getByLabel('Token name', { exact: true }).fill('Office permanent');
    await panel.getByLabel('Expiration mode', { exact: true }).selectOption('never');
    await expect(panel.getByLabel('Valid for days', { exact: true })).toHaveCount(0);
    await captureToken('QAT-07-permanent-form');
    await panel.getByRole('button', { name: 'Confirm', exact: true }).click();
    await expect(panel.getByLabel('New token', { exact: true })).toBeVisible();
    const permanent = await panel.getByLabel('New token', { exact: true }).inputValue();
    expect(permanent.startsWith('ab_at_')).toBe(true); await machineStatus(permanent, 200);
    await panel.getByRole('button', { name: 'Saved; close secret', exact: true }).click();
    const permanentRow = panel.getByTestId('integration-token-row').filter({ hasText: 'Office permanent' });
    await permanentRow.getByRole('button', { name: 'Rotate token', exact: true }).click();
    await panel.getByLabel('Expiration mode', { exact: true }).selectOption('never');
    await panel.getByLabel('Old token grace minutes', { exact: true }).fill('0');
    await captureToken('QAT-07-rotation-form');
    await panel.getByRole('button', { name: 'Confirm', exact: true }).click();
    await expect(panel.getByLabel('New token', { exact: true })).toBeVisible();
    const replacement = await panel.getByLabel('New token', { exact: true }).inputValue();
    expect(replacement.startsWith('ab_at_')).toBe(true);
    await machineStatus(permanent, 401); await machineStatus(replacement, 200); await machineStatus(fixed, 200);
    await expect(panel.getByTestId('integration-token-secret')).toContainText('Old token accepted until');
    await captureToken('QAT-07-rotation-secret');
    await panel.getByRole('button', { name: 'Saved; close secret', exact: true }).click();
    const active = panel.getByTestId('integration-token-row').filter({ hasText: 'Office permanent' }).filter({ has: page.getByText('Active', { exact: true }) });
    await active.getByRole('button', { name: 'Revoke token', exact: true }).click();
    await captureToken('QAT-07-revoke-confirmation');
    await panel.getByRole('button', { name: 'Cancel', exact: true }).click(); await machineStatus(replacement, 200);
    await active.getByRole('button', { name: 'Revoke token', exact: true }).click();
    await panel.getByRole('button', { name: 'Confirm', exact: true }).click();
    await expect(panel.getByText('Token revoked. Subsequent requests are rejected.', { exact: true })).toBeVisible();
    await machineStatus(replacement, 401); await machineStatus(fixed, 200);
    await captureToken('QAT-07-revoked');
    await panel.getByRole('button', { name: 'Close token manager', exact: true }).click();
    await appCard.getByTestId(`open-platform-tokens-${installation.pid}`).click();
    await expect(page.getByTestId('integration-token-panel').getByLabel('New token', { exact: true })).toHaveCount(0);
    await writeFile(path.join(evidence, 'result.json'), JSON.stringify({ profile: 'quick-token-browser-v1',
      executed: ['empty', 'fixed-cancel', 'fixed-create-and-bearer', 'reload-redaction', 'permanent-create-and-bearer',
        'rotate-immediate-and-bearer', 'revoke-cancel', 'revoke-and-bearer', 'sibling-preserved', 'reopen-redaction'],
      viewport: PUBLICATION_VIEWPORT, passed: true }, null, 2));
  });
});
