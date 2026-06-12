import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import { uniqueId } from '../helpers';

const FORM_BLOCK_ID = 'pd_form_input_section';

const FIELD_MATRIX = [
  { code: 'e2et_order_title', dataType: 'string', component: 'input' },
  { code: 'e2et_order_desc', dataType: 'text', component: 'textarea' },
  { code: 'e2et_order_qty', dataType: 'integer', component: 'number' },
  { code: 'e2et_order_date', dataType: 'date', component: 'date' },
  { code: 'e2et_order_type', dataType: 'enum', component: 'select' },
  { code: 'e2et_order_customer', dataType: 'reference', component: 'userselect' },
] as const;

async function createFormInputAuthoringPage(page: Page) {
  const id = uniqueId('pd_form_input_authoring');
  const pageKey = id.replace(/-/g, '_');
  const title = `Form input authoring ${id}`;
  const payload = {
    name: title,
    pageKey,
    title,
    kind: 'form',
    modelCode: 'e2et_order',
    profile: 'admin',
    layout: { type: 'stack', gap: 12 },
    blocks: [
      {
        id: FORM_BLOCK_ID,
        blockType: 'form-section',
        title: 'Designer input matrix',
        fields: [],
      },
    ],
    dataSources: {},
    schemaVersion: 4,
    metaInfo: { runtimeE2E: true, formInputAuthoring: true },
    semver: '0.1.0',
  };

  const createResp = await page.request.post('/api/pages', { data: payload });
  expect(createResp.ok(), `Create form input authoring page failed: ${createResp.status()}`).toBeTruthy();
  const createBody = await createResp.json();
  expect(createBody.code, 'create page API code').toBe('0');
  const pid = String(createBody.data?.pid || '');
  expect(pid, 'created form input authoring pid').toBeTruthy();

  return { pid, pageKey, title };
}

async function fetchModelFieldsByCode(page: Page, modelCode: string) {
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
  return new Map(fields.map((field: any) => [String(field.code), String(field.dataType)]));
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

async function ensureFieldControl(page: Page, testId: string, sectionName?: RegExp) {
  const control = page.getByTestId(testId);
  if (!(await control.isVisible().catch(() => false)) && sectionName) {
    await page.getByRole('button', { name: sectionName }).click();
  }
  await expect(control).toBeVisible({ timeout: 5_000 });
  return control;
}

async function selectComponentForField(page: Page, component: string) {
  const componentSelect = page.getByTestId('field-property-component-select');
  await expect(componentSelect).toBeVisible({ timeout: 5_000 });
  await expect
    .poll(
      async () => componentSelect.locator(`option[value="${component}"]`).count(),
      { timeout: 10_000 },
    )
    .toBe(1);
  await componentSelect.selectOption(component);
  await expect(page.getByTestId('widget-specific-panel')).toHaveAttribute(
    'data-component',
    component,
    { timeout: 5_000 },
  );
}

async function fillFieldInput(page: Page, key: string, value: string) {
  await ensureFieldControl(page, `field-property-${key}-input`);
  await page.getByTestId(`field-property-${key}-input`).fill(value);
}

async function selectFieldOption(page: Page, key: string, value: string) {
  await ensureFieldControl(page, `field-property-${key}-select`, /布局设置|Layout/);
  await page.getByTestId(`field-property-${key}-select`).selectOption(value);
}

async function toggleFieldSwitch(page: Page, key: string) {
  await ensureFieldControl(page, `field-property-${key}-switch`);
  await page.getByTestId(`field-property-${key}-switch`).click();
  await expect(page.getByTestId(`field-property-${key}-switch`)).toHaveAttribute(
    'aria-checked',
    'true',
  );
}

async function fillWidgetInput(page: Page, key: string, value: string) {
  const wrapper = page.getByTestId(`widget-prop-${key}`);
  await expect(wrapper).toBeVisible({ timeout: 5_000 });
  await wrapper.locator('input, textarea').first().fill(value);
}

async function toggleWidgetSwitch(page: Page, key: string) {
  const wrapper = page.getByTestId(`widget-prop-${key}`);
  await expect(wrapper).toBeVisible({ timeout: 5_000 });
  await wrapper.getByRole('switch').click();
  await expect(wrapper.getByRole('switch')).toHaveAttribute('data-state', 'checked');
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
  expect(block, 'saved form-section block').toBeTruthy();
  const field = (block.fields ?? []).find((item: any) => item?.field === fieldCode);
  expect(field, `saved field ${fieldCode}`).toBeTruthy();
  return field;
}

test.describe('Page Designer form/input authoring', () => {
  test.setTimeout(90_000);

  test('adds form fields, edits type-specific components and properties, and persists schema', async ({
    page,
  }) => {
    const fieldTypes = await fetchModelFieldsByCode(page, 'e2et_order');
    for (const expected of FIELD_MATRIX) {
      expect(fieldTypes.get(expected.code), `metadata dataType for ${expected.code}`).toBe(
        expected.dataType,
      );
    }

    const { pid } = await createFormInputAuthoringPage(page);
    await openDesignerByPid(page, pid);
    await selectFormBlock(page);

    for (const field of FIELD_MATRIX) {
      await addFieldFromPropertyPanel(page, field.code);
    }

    await selectDesignerField(page, 'e2et_order_title');
    await selectComponentForField(page, 'input');
    await fillFieldInput(page, 'placeholder', 'Designer title placeholder');
    await fillFieldInput(page, 'maxLength', '80');
    await fillFieldInput(page, 'minLength', '2');
    await fillFieldInput(page, 'pattern', '^TO-.+');
    await toggleFieldSwitch(page, 'required');
    await selectFieldOption(page, 'span', '2');
    await fillWidgetInput(page, 'placeholder', 'Widget title placeholder');
    await fillWidgetInput(page, 'maxLength', '80');

    await selectDesignerField(page, 'e2et_order_desc');
    await selectComponentForField(page, 'textarea');
    await fillFieldInput(page, 'placeholder', 'Designer description placeholder');
    await fillWidgetInput(page, 'rows', '5');
    await fillWidgetInput(page, 'maxLength', '200');

    await selectDesignerField(page, 'e2et_order_qty');
    await selectComponentForField(page, 'number');
    await fillFieldInput(page, 'minValue', '1');
    await fillFieldInput(page, 'maxValue', '99');
    await toggleFieldSwitch(page, 'readonly');
    await fillWidgetInput(page, 'min', '1');
    await fillWidgetInput(page, 'max', '99');
    await fillWidgetInput(page, 'step', '1');

    await selectDesignerField(page, 'e2et_order_date');
    await selectComponentForField(page, 'date');
    await fillWidgetInput(page, 'dateFormat', 'YYYY-MM-DD');
    await fillWidgetInput(page, 'minDate', '2026-01-01');

    await selectDesignerField(page, 'e2et_order_type');
    await selectComponentForField(page, 'select');
    await fillWidgetInput(page, 'placeholder', 'Select order type');
    await fillWidgetInput(page, 'dictCode', 'e2et_order_type');
    await toggleWidgetSwitch(page, 'allowClear');

    await selectDesignerField(page, 'e2et_order_customer');
    await selectComponentForField(page, 'userselect');
    await fillWidgetInput(page, 'placeholder', 'Select order customer');
    await toggleWidgetSwitch(page, 'multiple');

    await page.getByTestId('toolbar-preview').click();
    await expect(page.getByTestId('preview-modal')).toBeVisible({ timeout: 5_000 });
    const previewBlockCountText = (await page.getByTestId('preview-block-count').textContent()) ?? '';
    expect(Number.parseInt(previewBlockCountText, 10), 'preview includes authored form section').toBe(
      1,
    );
    await page.getByTestId('preview-close').click();
    await expect(page.getByTestId('preview-modal')).toBeHidden({ timeout: 5_000 });

    await saveDesignerAndWait(page, pid);
    const savedPage = await fetchPageByPid(page, pid);

    expect(savedFieldByCode(savedPage, 'e2et_order_title')).toMatchObject({
      component: 'input',
      required: true,
      placeholder: 'Designer title placeholder',
      maxLength: 80,
      minLength: 2,
      pattern: '^TO-.+',
      span: 2,
      props: {
        placeholder: 'Widget title placeholder',
        maxLength: 80,
      },
    });
    expect(savedFieldByCode(savedPage, 'e2et_order_desc')).toMatchObject({
      component: 'textarea',
      placeholder: 'Designer description placeholder',
      props: {
        rows: 5,
        maxLength: 200,
      },
    });
    expect(savedFieldByCode(savedPage, 'e2et_order_qty')).toMatchObject({
      component: 'number',
      readonly: true,
      minValue: 1,
      maxValue: 99,
      props: {
        min: 1,
        max: 99,
        step: 1,
      },
    });
    expect(savedFieldByCode(savedPage, 'e2et_order_date')).toMatchObject({
      component: 'date',
      props: {
        dateFormat: 'YYYY-MM-DD',
        minDate: '2026-01-01',
      },
    });
    expect(savedFieldByCode(savedPage, 'e2et_order_type')).toMatchObject({
      component: 'select',
      props: {
        placeholder: 'Select order type',
        dictCode: 'e2et_order_type',
        allowClear: true,
      },
    });
    expect(savedFieldByCode(savedPage, 'e2et_order_customer')).toMatchObject({
      component: 'userselect',
      props: {
        placeholder: 'Select order customer',
        multiple: true,
      },
    });
  });
});
