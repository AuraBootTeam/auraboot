import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import { uniqueId } from '../helpers';

const FORM_BLOCK_ID = 'pd_upload_authoring_section';
const FILE_FIELD = 'sc_attachment_file';
const SHOWCASE_MODEL = 'showcase_all_fields';

async function createUploadAuthoringPage(page: Page) {
  const id = uniqueId('pd_upload_authoring');
  const pageKey = id.replace(/-/g, '_');
  const title = `Upload authoring ${id}`;
  const payload = {
    name: title,
    pageKey,
    title,
    kind: 'form',
    modelCode: SHOWCASE_MODEL,
    profile: 'admin',
    layout: { type: 'stack', gap: 12 },
    blocks: [
      {
        id: FORM_BLOCK_ID,
        blockType: 'form-section',
        title: 'Designer upload field',
        fields: [],
      },
    ],
    dataSources: {},
    schemaVersion: 4,
    metaInfo: { runtimeE2E: true, uploadAuthoring: true },
    semver: '0.1.0',
  };

  const createResp = await page.request.post('/api/pages', { data: payload });
  expect(createResp.ok(), `Create upload authoring page failed: ${createResp.status()}`).toBeTruthy();
  const createBody = await createResp.json();
  expect(createBody.code, 'create page API code').toBe('0');
  const pid = String(createBody.data?.pid || '');
  expect(pid, 'created upload authoring pid').toBeTruthy();
  return { pid, pageKey, title };
}

async function fetchModelFieldType(page: Page, modelCode: string, fieldCode: string) {
  const modelResp = await page.request.get(`/api/meta/models/code/${modelCode}`);
  expect(modelResp.ok(), `Fetch model ${modelCode} failed: ${modelResp.status()}`).toBeTruthy();
  const modelBody = await modelResp.json();
  expect(modelBody.code, 'model lookup API code').toBe('0');
  const modelPid = String(modelBody.data?.pid || '');
  expect(modelPid, `model pid for ${modelCode}`).toBeTruthy();

  const fieldsResp = await page.request.get(`/api/meta/models/${modelPid}/fields`);
  expect(fieldsResp.ok(), `Fetch model fields failed: ${fieldsResp.status()}`).toBeTruthy();
  const fieldsBody = await fieldsResp.json();
  expect(fieldsBody.code, 'model fields API code').toBe('0');
  const fields = Array.isArray(fieldsBody.data) ? fieldsBody.data : [];
  const field = fields.find((item: any) => String(item.code) === fieldCode);
  expect(field, `metadata field ${fieldCode}`).toBeTruthy();
  return String(field.dataType);
}

async function openDesignerByPid(page: Page, pid: string) {
  await page.goto(`/page-designer/${pid}`, { waitUntil: 'domcontentloaded' });
  await expect(page.getByTestId('designer-canvas')).toBeVisible({ timeout: 15_000 });
  await expect(page.getByTestId('toolbar-save')).toBeVisible({ timeout: 10_000 });
}

async function selectFormBlock(page: Page) {
  const block = page.locator(
    `[data-testid="sortable-block"][data-block-id="${FORM_BLOCK_ID}"]`,
  );
  await expect(block).toBeVisible({ timeout: 10_000 });
  await block.click();
  await expect(page.getByTestId('fields-add-input')).toBeVisible({ timeout: 5_000 });
}

async function addFieldFromPropertyPanel(page: Page, fieldCode: string) {
  await page.getByTestId('fields-add-input').fill(fieldCode);
  await page.getByTestId('fields-add-button').click();
  await expect(page.getByTestId(`field-item-${fieldCode}`)).toBeVisible({ timeout: 5_000 });
  await expect(page.getByTestId(`designer-field-${fieldCode}`)).toBeVisible({ timeout: 5_000 });
}

async function selectDesignerField(page: Page, fieldCode: string) {
  await page.getByTestId(`designer-field-${fieldCode}`).click();
  await expect(page.getByRole('heading', { name: '字段属性' })).toBeVisible({ timeout: 5_000 });
  await expect(page.getByTestId('field-property-field-input')).toHaveValue(fieldCode);
}

async function selectUploadComponent(page: Page) {
  const componentSelect = page.getByTestId('field-property-component-select');
  await expect(componentSelect).toBeVisible({ timeout: 5_000 });
  await expect
    .poll(
      async () => componentSelect.locator('option[value="upload"]').count(),
      { timeout: 10_000 },
    )
    .toBe(1);
  await componentSelect.selectOption('upload');
  await expect(page.getByTestId('widget-specific-panel')).toHaveAttribute(
    'data-component',
    'upload',
    { timeout: 5_000 },
  );
}

async function fillWidgetInput(page: Page, key: string, value: string) {
  const wrapper = page.getByTestId(`widget-prop-${key}`);
  await expect(wrapper).toBeVisible({ timeout: 5_000 });
  await wrapper.locator('input, textarea').first().fill(value);
}

async function selectWidgetOption(page: Page, key: string, value: string, label: string) {
  const wrapper = page.getByTestId(`widget-prop-${key}`);
  await expect(wrapper).toBeVisible({ timeout: 5_000 });
  await wrapper.getByRole('combobox').click();
  await page.getByRole('option', { name: label, exact: true }).click();
  await expect(wrapper.getByRole('combobox')).toContainText(label);
}

async function toggleWidgetSwitch(page: Page, key: string) {
  const wrapper = page.getByTestId(`widget-prop-${key}`);
  await expect(wrapper).toBeVisible({ timeout: 5_000 });
  await wrapper.getByRole('switch').click();
  await expect(wrapper.getByRole('switch')).toHaveAttribute('data-state', 'checked');
}

async function selectFieldOption(page: Page, key: string, value: string) {
  const select = page.getByTestId(`field-property-${key}-select`);
  if (!(await select.isVisible().catch(() => false))) {
    await page.getByRole('button', { name: /布局设置|Layout/ }).click();
  }
  await expect(select).toBeVisible({ timeout: 5_000 });
  await select.selectOption(value);
}

async function toggleFieldSwitch(page: Page, key: string) {
  const toggle = page.getByTestId(`field-property-${key}-switch`);
  await expect(toggle).toBeVisible({ timeout: 5_000 });
  await toggle.click();
  await expect(toggle).toHaveAttribute('aria-checked', 'true');
}

async function saveDesignerAndWait(page: Page, pid: string) {
  const saveButton = page.getByTestId('toolbar-save');
  await expect(saveButton).toBeVisible({ timeout: 5_000 });
  await expect
    .poll(async () => saveButton.isEnabled().catch(() => false), { timeout: 8_000 })
    .toBe(true);

  const saveResp = page.waitForResponse(
    (response) =>
      response.url().includes(`/api/pages/${pid}`) &&
      response.request().method() === 'PUT' &&
      response.status() < 400,
    { timeout: 10_000 },
  );
  await saveButton.click();
  await saveResp;
}

async function fetchPageByPid(page: Page, pid: string): Promise<Record<string, any>> {
  const resp = await page.request.get(`/api/pages/${pid}`);
  expect(resp.ok(), `Fetch page ${pid} failed: ${resp.status()}`).toBeTruthy();
  const body = await resp.json();
  expect(body.code, 'fetch page API code').toBe('0');
  return body.data ?? {};
}

function savedFieldByCode(savedPage: Record<string, any>, fieldCode: string): Record<string, any> {
  const block = (savedPage.blocks ?? []).find((item: any) => item.id === FORM_BLOCK_ID);
  expect(block, 'saved upload form-section block').toBeTruthy();
  const field = (block.fields ?? []).find((item: any) => item?.field === fieldCode);
  expect(field, `saved field ${fieldCode}`).toBeTruthy();
  return field;
}

test.describe('Page Designer upload authoring', () => {
  test('edits upload field widget props through FieldPropertyEditor and persists reloadable schema', async ({
    page,
  }) => {
    expect(await fetchModelFieldType(page, SHOWCASE_MODEL, FILE_FIELD)).toBe('file');

    const { pid } = await createUploadAuthoringPage(page);
    await openDesignerByPid(page, pid);
    await selectFormBlock(page);
    await addFieldFromPropertyPanel(page, FILE_FIELD);

    await selectDesignerField(page, FILE_FIELD);
    await selectUploadComponent(page);
    await toggleFieldSwitch(page, 'required');
    await selectFieldOption(page, 'span', '3');
    await fillWidgetInput(page, 'accept', '.zip,application/zip');
    await toggleWidgetSwitch(page, 'multiple');
    await fillWidgetInput(page, 'maxSize', '2');
    await fillWidgetInput(page, 'maxCount', '3');
    await selectWidgetOption(page, 'listType', 'picture', 'Picture');
    await fillWidgetInput(page, 'buttonText', 'Upload attachments');
    await fillWidgetInput(page, 'hint', 'ZIP or image evidence');

    await page.getByTestId('toolbar-preview').click();
    await expect(page.getByTestId('preview-modal')).toBeVisible({ timeout: 5_000 });
    const previewBlockCountText = (await page.getByTestId('preview-block-count').textContent()) ?? '';
    expect(Number.parseInt(previewBlockCountText, 10), 'preview includes authored upload section').toBe(
      1,
    );
    await page.getByTestId('preview-close').click();

    await saveDesignerAndWait(page, pid);
    const savedPage = await fetchPageByPid(page, pid);
    expect(savedFieldByCode(savedPage, FILE_FIELD)).toMatchObject({
      field: FILE_FIELD,
      component: 'upload',
      required: true,
      span: 3,
      props: {
        accept: '.zip,application/zip',
        multiple: true,
        maxSize: 2,
        maxCount: 3,
        listType: 'picture',
        buttonText: 'Upload attachments',
        hint: 'ZIP or image evidence',
      },
    });

    await openDesignerByPid(page, pid);
    await selectDesignerField(page, FILE_FIELD);
    await expect(page.getByTestId('field-property-component-select')).toHaveValue('upload');
    await expect(page.getByTestId('field-property-required-switch')).toHaveAttribute(
      'aria-checked',
      'true',
    );
    if (!(await page.getByTestId('field-property-span-select').isVisible().catch(() => false))) {
      await page.getByRole('button', { name: /布局设置|Layout/ }).click();
    }
    await expect(page.getByTestId('field-property-span-select')).toHaveValue('3');
    await expect(page.getByTestId('widget-prop-accept').locator('input')).toHaveValue(
      '.zip,application/zip',
    );
    await expect(page.getByTestId('widget-prop-multiple').getByRole('switch')).toHaveAttribute(
      'data-state',
      'checked',
    );
    await expect(page.getByTestId('widget-prop-maxSize').locator('input')).toHaveValue('2');
    await expect(page.getByTestId('widget-prop-maxCount').locator('input')).toHaveValue('3');
    await expect(page.getByTestId('widget-prop-listType').getByRole('combobox')).toContainText(
      'Picture',
    );
    await expect(page.getByTestId('widget-prop-buttonText').locator('input')).toHaveValue(
      'Upload attachments',
    );
    await expect(page.getByTestId('widget-prop-hint').locator('input')).toHaveValue(
      'ZIP or image evidence',
    );
  });
});
