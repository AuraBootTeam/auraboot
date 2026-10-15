import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { test, expect, type Page, type TestInfo } from '@playwright/test';
import { navigateToMenuByClick } from '../helpers';
import { openAsRole } from '../rbac/rbac-helpers';
import { parseReportTemplate } from '../../../app/plugins/core-designer/components/report-designer/services/reportTemplateIO';

const templatePath = fileURLToPath(
  new URL('./fixtures/customer-analysis.report-template.json', import.meta.url),
);
test.use({ locale: 'zh-CN' });
test.describe.configure({ timeout: 120_000 });
async function menu(page: Page, label: string) {
  await navigateToMenuByClick(page, ['元数据管理', label]);
  await expect(page.locator('main')).not.toContainText(
    /Page Unavailable|Access forbidden|Internal Server Error/,
  );
}
async function screenshot(page: Page, info: TestInfo, name: string) {
  await info.attach(name, {
    body: await page.screenshot({ fullPage: true }),
    contentType: 'image/png',
  });
}

test('SC02: create a model, bind fields, publish and generate CRUD from the real menus', async ({
  page,
  browser,
}, info) => {
  const code = `launch_followup_${Date.now().toString(36)}`;
  await page.goto('/home');
  await menu(page, '模型管理');
  await page.getByTestId('toolbar-btn-create').click();
  await page.getByTestId('model-type-physical').click();
  await page.getByTestId('model-code-input').fill(code);
  await page.getByTestId('model-display-name-input').fill('客户跟进');
  const create = page.waitForResponse(
    (r) => r.url().endsWith('/api/meta/models') && r.request().method() === 'POST',
  );
  await page.getByTestId('model-create-submit').click();
  const created = await create;
  expect(created.ok()).toBe(true);
  const body = await created.json();
  expect(String(body.code)).toBe('0');
  const pid = body.data.pid;
  await expect(page).toHaveURL(new RegExp(`/meta/models/${pid}$`));
  for (const [suffix, type] of [
    ['customer_name', 'string'],
    ['opportunity_amount', 'integer'],
  ]) {
    await page
      .getByRole('main')
      .getByRole('button', { name: /^字段(?:\s*\(\d+\))?$/ })
      .click();
    await page.getByTestId('model-fields-add-button').click();
    await page.getByTestId('field-selection-tab-create').click();
    const dialog = page.getByTestId('field-selection-dialog');
    await dialog
      .getByTestId('field-create-display-name')
      .fill(suffix === 'customer_name' ? '客户名称' : '商机金额');
    await dialog.getByPlaceholder('例如: user_name, email, age').fill(`${code}_${suffix}`);
    await dialog.locator('select').first().selectOption(type);
    const bind = page.waitForResponse(
      (r) =>
        r.url().endsWith(`/api/meta/models/${pid}/fields/bind`) && r.request().method() === 'POST',
    );
    await page.getByTestId('field-selection-create-bind').click();
    const bound = await bind;
    expect(bound.ok()).toBe(true);
    expect(String((await bound.json()).code)).toBe('0');
    await expect(dialog).toBeHidden();
  }
  await page.getByTestId('model-fields-add-button').click();
  await page.getByTestId('field-selection-tab-create').click();
  const invalidDialog = page.getByTestId('field-selection-dialog');
  await expect(page.getByTestId('field-selection-create-bind')).toBeDisabled();
  await invalidDialog.getByTestId('field-create-display-name').fill('重复客户名称');
  await invalidDialog.getByPlaceholder('例如: user_name, email, age').fill('invalid-code');
  await expect(page.getByTestId('field-selection-create-bind')).toBeDisabled();
  await invalidDialog.getByPlaceholder('例如: user_name, email, age').fill(`${code}_customer_name`);
  const duplicate = page.waitForResponse((r) =>
    r.url().includes(`/api/meta/fields/key/${code}_customer_name/unique`),
  );
  await page.getByTestId('field-selection-create-bind').click();
  expect((await (await duplicate).json()).data).toBe(false);
  await expect(page.getByTestId('field-create-duplicate')).toBeVisible();
  await screenshot(page, info, 'SC02-duplicate-field-denied');
  await invalidDialog.getByRole('button', { name: '取消', exact: true }).click();
  await expect(invalidDialog).toBeHidden();
  const fields = await page.request.get(`/api/meta/models/${pid}/fields`);
  expect(fields.ok()).toBe(true);
  const metadata = await fields.json();
  expect(JSON.stringify(metadata.data)).toContain(`${code}_customer_name`);
  expect(JSON.stringify(metadata.data)).toContain('客户名称');
  expect(JSON.stringify(metadata.data)).toContain('商机金额');
  expect(metadata.data.filter((field: { code: string }) => field.code.startsWith(`${code}_`)))
    .toHaveLength(2);
  expect(metadata.data.filter((field: { code: string }) => !field.code.startsWith(`${code}_`))
    .map((field: { code: string }) => field.code).sort()).toEqual(['created_at', 'updated_at']);
  expect(JSON.stringify(metadata.data)).toContain(`${code}_opportunity_amount`);
  await page.getByTestId('model-more-actions').click();
  await page.getByTestId('model-publish-action').click();
  await expect(page.getByTestId('model-publish-dialog')).toBeVisible();
  await screenshot(page, info, 'SC02-publish-confirmation');
  const acknowledgement = page.getByTestId('model-publish-impact-ack');
  if (await acknowledgement.count()) await acknowledgement.check();
  const publish = page.waitForResponse(
    (r) => r.url().includes(`/api/meta/models/${pid}/publish`) && r.request().method() === 'POST',
  );
  await page.getByTestId('model-publish-confirm').click();
  expect(String((await (await publish).json()).code)).toBe('0');
  await expect(page.getByTestId('model-publish-dialog')).toBeHidden();
  await page.getByTestId('model-primary-page-action').click();
  await expect(page.getByTestId('crud-generate-list')).toBeChecked();
  await expect(page.getByTestId('crud-generate-form')).toBeChecked();
  await expect(page.getByTestId('crud-generate-detail')).toBeChecked();
  const generate = page.waitForResponse(
    (r) => r.url().includes('/api/templates/crud/generate') && r.request().method() === 'POST',
  );
  await page.getByTestId('crud-generate-submit').click();
  const generated = await (await generate).json();
  expect(String(generated.code)).toBe('0');
  expect(generated.data.generatedResources.pages).toHaveLength(3);
  await page.reload();
  await expect(page.locator('body')).not.toContainText(
    /Page Unavailable|Page not found|Access forbidden/,
  );
  await screenshot(page, info, 'SC02-generated-page-reopened');
  await page.goBack();
  await expect(page.getByTestId('model-primary-page-preview')).toBeVisible();
  await page.getByTestId('model-primary-page-preview').click();
  await expect(page.getByRole('columnheader', { name: '客户名称', exact: true })).toBeVisible();
  await expect(page.getByRole('columnheader', { name: '商机金额', exact: true })).toBeVisible();
  await page.reload();
  await expect(page.getByRole('columnheader', { name: '客户名称', exact: true })).toBeVisible();
  await screenshot(page, info, 'SC02-runtime-business-labels');
  const listUrl = page.url();
  const customerField = `${code}_customer_name`;
  const amountField = `${code}_opportunity_amount`;
  const customerName = `华辰客户跟进 ${Date.now()}`;
  await page.getByTestId('toolbar-btn-create').click();
  await page.getByTestId(`form-field-${customerField}`).locator('input').fill(customerName);
  await page.getByTestId(`form-field-${amountField}`).locator('input').fill('42000');
  await screenshot(page, info, 'SC02-customer-followup-form');
  const createRecord = page.waitForResponse(
    (r) => new URL(r.url()).pathname === `/api/dynamic/${code}` && r.request().method() === 'POST',
  );
  await page.getByTestId('form-btn-submit').click();
  const createdRecordResponse = await createRecord;
  expect(createdRecordResponse.ok()).toBe(true);
  expect(createdRecordResponse.request().postDataJSON()).toMatchObject({
    [customerField]: customerName,
    [amountField]: 42000,
  });
  const recordBody = await createdRecordResponse.json();
  expect(String(recordBody.code)).toBe('0');
  const recordPid = String(recordBody.data.pid);
  expect(recordPid).toMatch(/^[0-9A-Z]{26}$/);
  const row = page.locator('tr').filter({ hasText: customerName });
  await expect(row).toBeVisible();
  await row.getByTestId('row-action-edit').click();
  await expect(page.getByTestId(`form-field-${customerField}`).locator('input')).toHaveValue(
    customerName,
  );
  await expect(page.getByTestId(`form-field-${amountField}`).locator('input')).toHaveValue('42000');
  await page.getByTestId(`form-field-${amountField}`).locator('input').fill('68000');
  const updateRecord = page.waitForResponse(
    (r) =>
      new URL(r.url()).pathname === `/api/dynamic/${code}/${recordPid}` &&
      r.request().method() === 'PUT',
  );
  await page.getByTestId('form-btn-submit').click();
  const updatedRecordResponse = await updateRecord;
  expect(updatedRecordResponse.ok()).toBe(true);
  expect(updatedRecordResponse.request().postDataJSON()).toMatchObject({ [amountField]: 68000 });
  expect(String((await updatedRecordResponse.json()).code)).toBe('0');
  await page.reload();
  await expect(row).toBeVisible();
  const persistedRecord = await page.request.get(`/api/dynamic/${code}/${recordPid}`);
  expect(persistedRecord.ok()).toBe(true);
  expect((await persistedRecord.json()).data).toMatchObject({
    [customerField]: customerName,
    [amountField]: 68000,
  });
  await row.getByTestId('row-action-edit').click();
  await expect(page.getByTestId(`form-field-${amountField}`).locator('input')).toHaveValue('68000');
  await screenshot(page, info, 'SC02-customer-followup-saved-reopened');
  await page.getByTestId('form-btn-cancel').click();

  // Provision a task-owned read-only role. API use here is setup and a negative
  // authorization probe; the successful create/update path above is browser-only.
  const roleCode = `launch_reader_${Date.now().toString(36)}`;
  const roleResponse = await page.request.post('/api/roles', {
    data: {
      code: roleCode,
      name: '客户跟进只读验证',
      type: 'custom',
      status: 'active',
      scopeType: 'tenant',
      defaultDataScopeType: 'all',
    },
  });
  expect(roleResponse.ok()).toBe(true);
  const rolePid = (await roleResponse.json()).data.pid;
  const treeResponse = await page.request.get('/api/permissions/tree');
  expect(treeResponse.ok()).toBe(true);
  const permissionTree = await treeResponse.json();
  const permissionIds = new Map<string, number>();
  interface PermissionNode { code: string; id: number; children?: PermissionNode[] }
  const collectPermission = (node: PermissionNode) => {
    permissionIds.set(node.code, node.id);
    (node.children ?? []).forEach(collectPermission);
  };
  permissionTree.data.forEach(collectPermission);
  const readId = permissionIds.get(`model.${code}.read`);
  expect(readId, 'published model must expose its read permission').toBeDefined();
  const grants = await page.request.put(`/api/permissions/matrix/${rolePid}/batch`, {
    data: [{ permissionId: readId, granted: true }],
  });
  expect(grants.ok()).toBe(true);
  const email = `${roleCode}@e2e.local`;
  const password = 'Test2026x';
  const userResponse = await page.request.post('/api/admin/users', {
    data: {
      email,
      displayName: '客户跟进只读验证',
      initialPassword: password,
      roleCodes: [roleCode],
      sendInviteEmail: false,
    },
  });
  expect(userResponse.ok()).toBe(true);
  const reader = await openAsRole(browser, email, password, 'zh-CN');
  try {
    // Reuse the exact runtime URL reached through the author's real preview.
    await reader.page.goto(listUrl);
    const readerRow = reader.page.locator('tr').filter({ hasText: customerName });
    await expect(readerRow).toBeVisible();
    await expect(reader.page.getByTestId('toolbar-btn-create')).not.toBeVisible();
    await expect(readerRow.getByTestId('row-action-edit')).not.toBeVisible();
    const recordUrl = new URL(`/api/dynamic/${code}/${recordPid}`, listUrl);
    const readRecord = await reader.page.request.get(recordUrl.toString());
    expect(readRecord.ok()).toBe(true);
    expect((await readRecord.json()).data[amountField]).toBe(68000);
    const deniedWrite = await reader.page.request.put(recordUrl.toString(), {
      // This independently authenticated context does not inherit runner headers.
      // Reach backend permission enforcement rather than mistaking BFF CSRF for RBAC.
      headers: { Origin: recordUrl.origin, Referer: listUrl },
      data: { [amountField]: 999999 },
    });
    expect(deniedWrite.status()).toBe(403);
    const deniedBody = await deniedWrite.json();
    expect(String(deniedBody.code)).toBe('403');
    expect(JSON.stringify(deniedBody)).not.toContain('csrf_origin_rejected');
    expect(JSON.stringify(deniedBody)).toContain(`permissionCode: model.${code}.update`);
    const unchanged = await page.request.get(`/api/dynamic/${code}/${recordPid}`);
    expect((await unchanged.json()).data[amountField]).toBe(68000);
    await screenshot(reader.page, info, 'SC02-reader-cannot-write');
    await info.attach('SC02-readonly-enforcement', {
      body: JSON.stringify({
        roleCode,
        modelCode: code,
        recordPid,
        readStatus: readRecord.status(),
        deniedWriteStatus: deniedWrite.status(),
        deniedWriteBody: deniedBody,
        persistedAmount: 68000,
      }),
      contentType: 'application/json',
    });
  } finally {
    await reader.context.close();
  }
  await info.attach('SC02-model-identity', {
    body: JSON.stringify(
      { modelCode: code, modelPid: pid, fields: metadata.data, generated: generated.data },
      null,
      2,
    ),
    contentType: 'application/json',
  });
});

test('SC05-PAGE: import, refine, export, save and reopen a page definition', async ({
  page,
}, info) => {
  await page.goto('/home');
  const key = `launch_customer_${Date.now().toString(36)}`;
  const seeded = await page.request.post('/api/pages', {
    data: {
      name: key,
      pageKey: key,
      title: key,
      kind: 'detail',
      modelCode: 'tpm_customer',
      schemaVersion: 4,
      blocks: [
        {
          id: 'customer_section',
          blockType: 'detail-section',
          title: { 'zh-CN': '客户联系信息', en: 'Customer contact information' },
          columns: 12,
          fields: [],
        },
      ],
    },
  });
  expect(seeded.ok()).toBe(true);
  const seedBody = await seeded.json();
  expect(String(seedBody.code)).toBe('0');
  const pid = seedBody.data.pid;
  await menu(page, '页面配置');
  await page.getByTestId('list-search-input').fill(key);
  await page.getByTestId('list-search-input').press('Enter');
  const row = page.locator('tr').filter({ hasText: key });
  await row.getByText('统一设计器', { exact: true }).click();
  await expect(page.getByTestId('unified-designer-workbench')).toBeVisible();
  const imported = {
    schemaVersion: 4,
    id: key,
    pageKey: key,
    title: key,
    kind: 'detail',
    modelCode: 'tpm_customer',
    blocks: [
      {
        id: 'customer_section_imported',
        blockType: 'detail-section',
        title: { 'zh-CN': '客户跟进信息', en: 'Customer follow-up information' },
        columns: 12,
        fields: [],
      },
    ],
  };
  await page.getByTestId('designer-import-input').setInputFiles({
    name: 'customer.page.json',
    mimeType: 'application/json',
    buffer: Buffer.from(JSON.stringify(imported)),
  });
  await page.getByTestId('outline-item-customer_section_imported').click();
  await page.getByTestId('inspector-field-title').fill('客户经营与交付信息');
  const downloadEvent = page.waitForEvent('download');
  await page.getByTestId('designer-export').click();
  const download = await downloadEvent;
  expect(download.suggestedFilename()).toMatch(/\.page\.json$/);
  const exported = JSON.parse(await readFile((await download.path())!, 'utf8'));
  expect(exported.blocks.find((block: { id: string }) => block.id === 'customer_section_imported').title)
    .toEqual({ 'zh-CN': '客户经营与交付信息', en: 'Customer follow-up information' });
  const save = page.waitForResponse(
    (r) => r.url().includes(`/api/pages/${pid}`) && r.request().method() === 'PUT',
  );
  await page.getByTestId('designer-save').click();
  expect(String((await (await save).json()).code)).toBe('0');
  await page.reload();
  await expect(page.getByTestId('outline-item-customer_section_imported')).toBeVisible();
  const persisted = await page.request.get(`/api/pages/${pid}`);
  const persistedBlocks = (await persisted.json()).data.blocks;
  expect(persistedBlocks.find((block: { id: string }) => block.id === 'customer_section_imported').title)
    .toEqual({ 'zh-CN': '客户经营与交付信息', en: 'Customer follow-up information' });
  await screenshot(page, info, 'SC05-page-template-reopened');
});

test('SC05-REPORT: design template import, refinement, export and persisted reopen', async ({
  page,
}, info) => {
  await page.goto('/home');
  await menu(page, '报表管理');
  await page.getByRole('button', { name: '新建报表', exact: true }).click();
  await expect(page.getByTestId('report-template-actions')).toBeVisible();
  await page.getByTestId('report-template-import-input').setInputFiles(templatePath);
  await expect(page.getByTestId('report-template-imported')).toBeVisible();
  const title = `客户分析与交付_${Date.now().toString(36)}`;
  await page.getByRole('textbox', { name: '报表标题', exact: true }).fill(title);
  const downloadEvent = page.waitForEvent('download');
  await page.getByTestId('report-template-export').click();
  const download = await downloadEvent;
  expect(download.suggestedFilename()).toMatch(/\.report-template\.json$/);
  const raw = await readFile((await download.path())!, 'utf8');
  const dsl = parseReportTemplate(raw);
  expect(dsl.title).toBe(title);
  expect(dsl.dataSources.customers.modelCode).toBe('tpm_customer');
  expect(dsl.body).toHaveLength(2);
  await page.getByTestId('report-template-import-input').setInputFiles({
    name: 'invalid.json',
    mimeType: 'application/json',
    buffer: Buffer.from('{"format":"not-a-template"}'),
  });
  await expect(page.getByTestId('report-template-error')).toBeVisible();
  await expect(page.getByRole('textbox', { name: '报表标题', exact: true })).toHaveValue(title);
  const save = page.waitForResponse(
    (r) => r.url().endsWith('/api/report-definitions') && r.request().method() === 'POST',
  );
  await page.getByTestId('report-designer-toolbar-btn-save').click();
  const saved = await (await save).json();
  expect(String(saved.code)).toBe('0');
  const pid = saved.data.pid;
  const read = await page.request.get(`/api/report-definitions/${pid}`);
  expect(read.ok()).toBe(true);
  expect((await read.json()).data.dsl.title).toBe(title);
  await menu(page, '报表管理');
  const row = page.locator('tr').filter({ hasText: title });
  await row.getByText('打开', { exact: true }).click();
  await expect(page.getByRole('textbox', { name: '报表标题', exact: true })).toHaveValue(title);
  await screenshot(page, info, 'SC05-report-template-reopened');
  await info.attach('SC05-report-template-artifact', {
    body: raw,
    contentType: 'application/json',
  });
});
