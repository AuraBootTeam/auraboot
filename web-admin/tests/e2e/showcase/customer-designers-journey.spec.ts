import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { test, expect, type Page, type TestInfo } from '@playwright/test';
import { navigateToMenuByClick } from '../helpers';
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
  const fields = await page.request.get(`/api/meta/models/${pid}/fields`);
  expect(fields.ok()).toBe(true);
  const metadata = await fields.json();
  expect(JSON.stringify(metadata.data)).toContain(`${code}_customer_name`);
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
          title: '客户联系信息',
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
        title: '客户跟进信息',
        columns: 12,
        fields: [],
      },
    ],
  };
  await page
    .getByTestId('designer-import-input')
    .setInputFiles({
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
  expect(JSON.stringify(exported.blocks)).toContain('客户经营与交付信息');
  const save = page.waitForResponse(
    (r) => r.url().includes(`/api/pages/${pid}`) && r.request().method() === 'PUT',
  );
  await page.getByTestId('designer-save').click();
  expect(String((await (await save).json()).code)).toBe('0');
  await page.reload();
  await expect(page.getByTestId('outline-item-customer_section_imported')).toBeVisible();
  const persisted = await page.request.get(`/api/pages/${pid}`);
  expect(JSON.stringify((await persisted.json()).data.blocks)).toContain('客户经营与交付信息');
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
  await page
    .getByTestId('report-template-import-input')
    .setInputFiles({
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
