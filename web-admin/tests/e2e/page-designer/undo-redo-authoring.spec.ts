import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import { uniqueId } from '../helpers';

const FORM_BLOCK_ID = 'pd_undo_redo_section';
const BLOCK_TITLE_SECTION_ID = 'pd_undo_redo_block_title_section';
const SHOWCASE_MODEL = 'showcase_all_fields';
const RATING_FIELD = 'sc_rating';

async function createUndoRedoAuthoringPage(page: Page) {
  const id = uniqueId('pd_undo_redo_authoring');
  const pageKey = id.replace(/-/g, '_');
  const title = `Undo redo authoring ${id}`;
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
        title: 'Undo redo rating field',
        fields: [RATING_FIELD],
      },
    ],
    dataSources: {},
    schemaVersion: 4,
    metaInfo: { runtimeE2E: true, undoRedoAuthoring: true },
    semver: '0.1.0',
  };

  const createResp = await page.request.post('/api/pages', { data: payload });
  expect(createResp.ok(), `Create undo/redo authoring page failed: ${createResp.status()}`).toBeTruthy();
  const createBody = await createResp.json();
  expect(createBody.code, 'create page API code').toBe('0');
  const pid = String(createBody.data?.pid || '');
  expect(pid, 'created undo/redo authoring pid').toBeTruthy();
  return { pid, pageKey, title };
}

async function createBlockTitleUndoRedoPage(page: Page) {
  const id = uniqueId('pd_block_undo_redo_authoring');
  const pageKey = id.replace(/-/g, '_');
  const title = `Block undo redo authoring ${id}`;
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
        id: BLOCK_TITLE_SECTION_ID,
        blockType: 'form-section',
        title: 'Original block title',
        fields: [],
      },
    ],
    dataSources: {},
    schemaVersion: 4,
    metaInfo: { runtimeE2E: true, undoRedoBlockAuthoring: true },
    semver: '0.1.0',
  };

  const createResp = await page.request.post('/api/pages', { data: payload });
  expect(
    createResp.ok(),
    `Create block undo/redo authoring page failed: ${createResp.status()}`,
  ).toBeTruthy();
  const createBody = await createResp.json();
  expect(createBody.code, 'create page API code').toBe('0');
  const pid = String(createBody.data?.pid || '');
  expect(pid, 'created block undo/redo authoring pid').toBeTruthy();
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

async function selectDesignerField(page: Page, fieldCode: string) {
  await page.getByTestId(`designer-field-${fieldCode}`).click();
  await expect(page.getByRole('heading', { name: '字段属性' })).toBeVisible({ timeout: 5_000 });
  await expect(page.getByTestId('field-property-field-input')).toHaveValue(fieldCode);
}

async function selectCanvasBlock(page: Page, blockId: string) {
  const block = page.locator(`[data-testid="sortable-block"][data-block-id="${blockId}"]`);
  await expect(block).toBeVisible({ timeout: 5_000 });
  await block.click();
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

async function fillWidgetInput(page: Page, key: string, value: string) {
  const wrapper = page.getByTestId(`widget-prop-${key}`);
  await expect(wrapper).toBeVisible({ timeout: 5_000 });
  await wrapper.locator('input, textarea').first().fill(value);
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
  expect(block, 'saved undo/redo form-section block').toBeTruthy();
  const field = (block.fields ?? []).find((item: any) =>
    typeof item === 'string' ? item === fieldCode : item?.field === fieldCode,
  );
  expect(field, `saved field ${fieldCode}`).toBeTruthy();
  return typeof field === 'string' ? { field } : field;
}

function savedBlockById(savedPage: Record<string, any>, blockId: string): Record<string, any> {
  const block = (savedPage.blocks ?? []).find((item: any) => item.id === blockId);
  expect(block, `saved block ${blockId}`).toBeTruthy();
  return block;
}

test.describe('Page Designer undo/redo authoring persistence', () => {
  test('saves the undone schema and then saves the redone schema for a field property edit', async ({
    page,
  }) => {
    expect(await fetchModelFieldType(page, SHOWCASE_MODEL, RATING_FIELD)).toBe('integer');

    const { pid } = await createUndoRedoAuthoringPage(page);
    await openDesignerByPid(page, pid);
    await selectDesignerField(page, RATING_FIELD);
    await selectComponentForField(page, 'rating');
    await fillWidgetInput(page, 'maxRating', '7');
    await expect(page.getByTestId('widget-prop-maxRating').locator('input')).toHaveValue('7');

    await page.getByTestId('toolbar-undo').click();
    await expect(page.getByTestId('widget-prop-maxRating').locator('input')).toHaveValue('5');
    await saveDesignerAndWait(page, pid);
    const undonePage = await fetchPageByPid(page, pid);
    const undoneField = savedFieldByCode(undonePage, RATING_FIELD);
    expect(undoneField.component).toBe('rating');
    expect(undoneField.props ?? {}).not.toMatchObject({ maxRating: 7 });

    await page.getByTestId('toolbar-redo').click();
    await expect(page.getByTestId('widget-prop-maxRating').locator('input')).toHaveValue('7');
    await saveDesignerAndWait(page, pid);
    const redonePage = await fetchPageByPid(page, pid);
    expect(savedFieldByCode(redonePage, RATING_FIELD)).toMatchObject({
      field: RATING_FIELD,
      component: 'rating',
      props: { maxRating: 7 },
    });
  });

  test('saves the undone schema and then saves the redone schema for a block title edit', async ({
    page,
  }) => {
    const { pid } = await createBlockTitleUndoRedoPage(page);
    await openDesignerByPid(page, pid);
    await selectCanvasBlock(page, BLOCK_TITLE_SECTION_ID);

    const titleInput = page.getByTestId('block-title-input-zh');
    await expect(titleInput).toHaveValue('Original block title');
    await titleInput.fill('Edited block title');
    await expect(titleInput).toHaveValue('Edited block title');

    await page.getByTestId('toolbar-undo').click();
    await expect(titleInput).toHaveValue('Original block title');
    await saveDesignerAndWait(page, pid);
    const undonePage = await fetchPageByPid(page, pid);
    expect(savedBlockById(undonePage, BLOCK_TITLE_SECTION_ID).title).toBe('Original block title');

    await page.getByTestId('toolbar-redo').click();
    await expect(titleInput).toHaveValue('Edited block title');
    await saveDesignerAndWait(page, pid);
    const redonePage = await fetchPageByPid(page, pid);
    expect(savedBlockById(redonePage, BLOCK_TITLE_SECTION_ID).title).toBe('Edited block title');
  });
});
