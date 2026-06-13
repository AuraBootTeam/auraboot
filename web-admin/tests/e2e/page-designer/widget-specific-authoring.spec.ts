import type { Locator, Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import { uniqueId } from '../helpers';

const FORM_BLOCK_ID = 'pd_widget_specific_section';
const SHOWCASE_MODEL = 'showcase_all_fields';

const FIELD_MATRIX = [
  { code: 'sc_richtext_content', dataType: 'text', component: 'richtext' },
  { code: 'sc_rating', dataType: 'integer', component: 'rating' },
  { code: 'sc_progress', dataType: 'integer', component: 'progress' },
  { code: 'sc_color', dataType: 'string', component: 'colorpicker' },
  { code: 'sc_budget', dataType: 'decimal', component: 'moneyinput' },
  { code: 'sc_cascade_category', dataType: 'string', component: 'cascadeselect' },
  { code: 'sc_tree_node', dataType: 'string', component: 'treeselect' },
  { code: 'sc_time_slot', dataType: 'string', component: 'timepicker' },
  { code: 'sc_attachment', dataType: 'json', component: 'fileattachment' },
] as const;

async function createWidgetSpecificAuthoringPage(page: Page) {
  const id = uniqueId('pd_widget_specific_authoring');
  const pageKey = id.replace(/-/g, '_');
  const title = `Widget-specific authoring ${id}`;
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
        title: 'Designer widget-specific fields',
        fields: [],
      },
    ],
    dataSources: {},
    schemaVersion: 4,
    metaInfo: { runtimeE2E: true, widgetSpecificAuthoring: true },
    semver: '0.1.0',
  };

  const createResp = await page.request.post('/api/pages', { data: payload });
  expect(createResp.ok(), `Create widget-specific authoring page failed: ${createResp.status()}`).toBeTruthy();
  const createBody = await createResp.json();
  expect(createBody.code, 'create page API code').toBe('0');
  const pid = String(createBody.data?.pid || '');
  expect(pid, 'created widget-specific authoring pid').toBeTruthy();

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

function widgetProp(page: Page, key: string): Locator {
  return page.getByTestId(`widget-prop-${key}`);
}

async function fillWidgetText(page: Page, key: string, value: string) {
  const wrapper = widgetProp(page, key);
  await expect(wrapper).toBeVisible({ timeout: 5_000 });
  await wrapper.locator('input, textarea').first().fill(value);
}

async function fillWidgetJson(page: Page, key: string, value: unknown) {
  await fillWidgetText(page, key, JSON.stringify(value, null, 2));
}

async function setWidgetSwitch(page: Page, key: string, expected: boolean) {
  const wrapper = widgetProp(page, key);
  await expect(wrapper).toBeVisible({ timeout: 5_000 });
  const control = wrapper.getByRole('switch');
  await expect(control).toBeVisible({ timeout: 5_000 });
  const expectedState = expected ? 'checked' : 'unchecked';
  if ((await control.getAttribute('data-state')) !== expectedState) {
    await control.click();
  }
  await expect(control).toHaveAttribute('data-state', expectedState);
}

async function selectWidgetOption(page: Page, key: string, label: string) {
  const wrapper = widgetProp(page, key);
  await expect(wrapper).toBeVisible({ timeout: 5_000 });
  await wrapper.getByRole('combobox').click();
  await page.getByRole('option', { name: label, exact: true }).click();
  await expect(wrapper.getByRole('combobox')).toContainText(label);
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
  expect(block, 'saved widget-specific form-section block').toBeTruthy();
  const field = (block.fields ?? []).find((item: any) => item?.field === fieldCode);
  expect(field, `saved field ${fieldCode}`).toBeTruthy();
  return field;
}

test.describe('Page Designer widget-specific authoring', () => {
  test.setTimeout(120_000);

  test('edits complex widget-specific schemas through FieldPropertyEditor and persists reloadable props', async ({
    page,
  }) => {
    const fieldTypes = await fetchModelFieldsByCode(page, SHOWCASE_MODEL);
    for (const expected of FIELD_MATRIX) {
      expect(fieldTypes.get(expected.code), `metadata dataType for ${expected.code}`).toBe(
        expected.dataType,
      );
    }

    const { pid } = await createWidgetSpecificAuthoringPage(page);
    await openDesignerByPid(page, pid);
    await selectFormBlock(page);

    for (const field of FIELD_MATRIX) {
      await addFieldFromPropertyPanel(page, field.code);
    }

    await selectDesignerField(page, 'sc_richtext_content');
    await selectComponentForField(page, 'richtext');
    await fillWidgetText(page, 'placeholder', 'Compose rich operational notes');

    await selectDesignerField(page, 'sc_rating');
    await selectComponentForField(page, 'rating');
    await fillWidgetText(page, 'maxRating', '7');
    await fillWidgetText(page, 'size', '28');

    await selectDesignerField(page, 'sc_progress');
    await selectComponentForField(page, 'progress');
    await setWidgetSwitch(page, 'showLabel', false);

    await selectDesignerField(page, 'sc_color');
    await selectComponentForField(page, 'colorpicker');
    await fillWidgetText(page, 'defaultValue', '#22c55e');

    await selectDesignerField(page, 'sc_budget');
    await selectComponentForField(page, 'moneyinput');
    await fillWidgetText(page, 'placeholder', 'Budget amount');
    await fillWidgetText(page, 'precision', '3');
    await fillWidgetText(page, 'min', '10');
    await fillWidgetText(page, 'max', '9999');
    await selectWidgetOption(page, 'size', 'Large');
    await selectWidgetOption(page, 'variant', 'Outlined');
    await fillWidgetText(page, 'currencyCode', 'USD');
    await fillWidgetText(page, 'currencySymbol', '$');
    await fillWidgetText(page, 'baseCurrencySymbol', 'EUR');
    await fillWidgetText(page, 'exchangeRate', '7.25');
    await setWidgetSwitch(page, 'showBaseEquivalent', false);

    await selectDesignerField(page, 'sc_cascade_category');
    await selectComponentForField(page, 'cascadeselect');
    await fillWidgetText(page, 'placeholder', 'Pick cascade category');
    await fillWidgetText(page, 'dictCode', 'sc_cascade_category_dict');
    await fillWidgetJson(page, 'options', [
      {
        label: 'Hardware',
        value: 'hardware',
        children: [{ label: 'Sensor', value: 'sensor' }],
      },
    ]);
    await fillWidgetText(page, 'levels', '2');
    await fillWidgetJson(page, 'levelLabels', ['Category', 'Leaf']);
    await setWidgetSwitch(page, 'allowPartial', true);

    await selectDesignerField(page, 'sc_tree_node');
    await selectComponentForField(page, 'treeselect');
    await fillWidgetText(page, 'placeholder', 'Pick tree node');
    await fillWidgetText(page, 'dictCode', 'sc_tree_dept_dict');
    await fillWidgetJson(page, 'treeData', [
      {
        label: 'Operations',
        value: 'ops',
        children: [{ label: 'Dispatch', value: 'dispatch' }],
      },
    ]);
    await setWidgetSwitch(page, 'multiple', true);
    await setWidgetSwitch(page, 'checkable', true);
    await setWidgetSwitch(page, 'leafOnly', false);
    await setWidgetSwitch(page, 'cascade', true);
    await setWidgetSwitch(page, 'searchable', true);
    await setWidgetSwitch(page, 'clearable', true);
    await fillWidgetText(page, 'maxTagCount', '4');
    await selectWidgetOption(page, 'size', 'Small');
    await selectWidgetOption(page, 'variant', 'Filled');
    await setWidgetSwitch(page, 'inline', true);

    await selectDesignerField(page, 'sc_time_slot');
    await selectComponentForField(page, 'timepicker');
    await fillWidgetText(page, 'placeholder', 'Select inspection time');
    await selectWidgetOption(page, 'format', 'HH:mm:ss');
    await setWidgetSwitch(page, 'showSecond', true);
    await setWidgetSwitch(page, 'use12Hours', true);
    await fillWidgetText(page, 'hourStep', '2');
    await fillWidgetText(page, 'minuteStep', '10');
    await fillWidgetText(page, 'secondStep', '5');
    await setWidgetSwitch(page, 'clearable', false);
    await selectWidgetOption(page, 'size', 'Large');
    await selectWidgetOption(page, 'variant', 'Outlined');

    await selectDesignerField(page, 'sc_attachment');
    await selectComponentForField(page, 'fileattachment');
    await setWidgetSwitch(page, 'multiple', false);
    await fillWidgetText(page, 'accept', '.pdf,.docx');
    await fillWidgetText(page, 'maxSize', '8');

    await page.getByTestId('toolbar-preview').click();
    await expect(page.getByTestId('preview-modal')).toBeVisible({ timeout: 5_000 });
    const previewBlockCountText = (await page.getByTestId('preview-block-count').textContent()) ?? '';
    expect(Number.parseInt(previewBlockCountText, 10), 'preview includes authored widget section').toBe(
      1,
    );
    await page.getByTestId('preview-close').click();

    await saveDesignerAndWait(page, pid);
    const savedPage = await fetchPageByPid(page, pid);

    expect(savedFieldByCode(savedPage, 'sc_richtext_content')).toMatchObject({
      component: 'richtext',
      props: { placeholder: 'Compose rich operational notes' },
    });
    expect(savedFieldByCode(savedPage, 'sc_rating')).toMatchObject({
      component: 'rating',
      props: { maxRating: 7, size: 28 },
    });
    expect(savedFieldByCode(savedPage, 'sc_progress')).toMatchObject({
      component: 'progress',
      props: { showLabel: false },
    });
    expect(savedFieldByCode(savedPage, 'sc_color')).toMatchObject({
      component: 'colorpicker',
      props: { defaultValue: '#22c55e' },
    });
    expect(savedFieldByCode(savedPage, 'sc_budget')).toMatchObject({
      component: 'moneyinput',
      props: {
        placeholder: 'Budget amount',
        precision: 3,
        min: 10,
        max: 9999,
        size: 'large',
        variant: 'outlined',
        currencyCode: 'USD',
        currencySymbol: '$',
        baseCurrencySymbol: 'EUR',
        exchangeRate: 7.25,
        showBaseEquivalent: false,
      },
    });
    expect(savedFieldByCode(savedPage, 'sc_cascade_category')).toMatchObject({
      component: 'cascadeselect',
      props: {
        placeholder: 'Pick cascade category',
        dictCode: 'sc_cascade_category_dict',
        options: [
          {
            label: 'Hardware',
            value: 'hardware',
            children: [{ label: 'Sensor', value: 'sensor' }],
          },
        ],
        levels: 2,
        levelLabels: ['Category', 'Leaf'],
        allowPartial: true,
      },
    });
    expect(savedFieldByCode(savedPage, 'sc_tree_node')).toMatchObject({
      component: 'treeselect',
      props: {
        placeholder: 'Pick tree node',
        dictCode: 'sc_tree_dept_dict',
        treeData: [
          {
            label: 'Operations',
            value: 'ops',
            children: [{ label: 'Dispatch', value: 'dispatch' }],
          },
        ],
        multiple: true,
        checkable: true,
        leafOnly: false,
        cascade: true,
        searchable: true,
        clearable: true,
        maxTagCount: 4,
        size: 'small',
        variant: 'filled',
        inline: true,
      },
    });
    expect(savedFieldByCode(savedPage, 'sc_time_slot')).toMatchObject({
      component: 'timepicker',
      props: {
        placeholder: 'Select inspection time',
        format: 'HH:mm:ss',
        showSecond: true,
        use12Hours: true,
        hourStep: 2,
        minuteStep: 10,
        secondStep: 5,
        clearable: false,
        size: 'large',
        variant: 'outlined',
      },
    });
    expect(savedFieldByCode(savedPage, 'sc_attachment')).toMatchObject({
      component: 'fileattachment',
      props: {
        multiple: false,
        accept: '.pdf,.docx',
        maxSize: 8,
      },
    });

    await openDesignerByPid(page, pid);
    await selectDesignerField(page, 'sc_budget');
    await expect(page.getByTestId('field-property-component-select')).toHaveValue('moneyinput');
    await expect(widgetProp(page, 'currencyCode').locator('input')).toHaveValue('USD');
    await expect(widgetProp(page, 'precision').locator('input')).toHaveValue('3');
    await expect(widgetProp(page, 'showBaseEquivalent').getByRole('switch')).toHaveAttribute(
      'data-state',
      'unchecked',
    );

    await selectDesignerField(page, 'sc_tree_node');
    await expect(page.getByTestId('field-property-component-select')).toHaveValue('treeselect');
    await expect(widgetProp(page, 'treeData').locator('textarea')).toContainText('Operations');
    await expect(widgetProp(page, 'inline').getByRole('switch')).toHaveAttribute(
      'data-state',
      'checked',
    );

    await selectDesignerField(page, 'sc_time_slot');
    await expect(page.getByTestId('field-property-component-select')).toHaveValue('timepicker');
    await expect(widgetProp(page, 'format').getByRole('combobox')).toContainText('HH:mm:ss');
    await expect(widgetProp(page, 'minuteStep').locator('input')).toHaveValue('10');
  });
});
