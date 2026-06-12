import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import { uniqueId } from '../helpers';

const FORM_BLOCK_ID = 'pd_undo_redo_section';
const BLOCK_TITLE_SECTION_ID = 'pd_undo_redo_block_title_section';
const BLOCK_DELETE_TEXT_ID = 'pd_undo_redo_delete_text';
const BLOCK_REORDER_FIRST_ID = 'pd_undo_redo_reorder_first';
const BLOCK_REORDER_SECOND_ID = 'pd_undo_redo_reorder_second';
const NESTED_TABS_BLOCK_ID = 'pd_undo_redo_nested_tabs';
const NESTED_TEXT_CHILD_ID = 'pd_undo_redo_nested_text';
const SHOWCASE_MODEL = 'showcase_all_fields';
const RATING_FIELD = 'sc_rating';
const NESTED_TEXT_ORIGINAL = 'Original nested undo copy';
const NESTED_TEXT_EDITED = 'Edited nested undo copy';

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

async function createBlockAddUndoRedoPage(page: Page) {
  const id = uniqueId('pd_block_add_undo_redo_authoring');
  const pageKey = id.replace(/-/g, '_');
  const title = `Block add undo redo authoring ${id}`;
  const payload = {
    name: title,
    pageKey,
    title,
    kind: 'form',
    modelCode: SHOWCASE_MODEL,
    profile: 'admin',
    layout: { type: 'stack', gap: 12 },
    blocks: [],
    dataSources: {},
    schemaVersion: 4,
    metaInfo: { runtimeE2E: true, undoRedoBlockAdd: true },
    semver: '0.1.0',
  };

  const createResp = await page.request.post('/api/pages', { data: payload });
  expect(
    createResp.ok(),
    `Create block add undo/redo authoring page failed: ${createResp.status()}`,
  ).toBeTruthy();
  const createBody = await createResp.json();
  expect(createBody.code, 'create page API code').toBe('0');
  const pid = String(createBody.data?.pid || '');
  expect(pid, 'created block add undo/redo authoring pid').toBeTruthy();
  return { pid, pageKey, title };
}

async function createBlockDeleteUndoRedoPage(page: Page) {
  const id = uniqueId('pd_block_delete_undo_redo_authoring');
  const pageKey = id.replace(/-/g, '_');
  const title = `Block delete undo redo authoring ${id}`;
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
        id: BLOCK_DELETE_TEXT_ID,
        blockType: 'text',
        props: { content: 'Delete undo redo seed text' },
      },
    ],
    dataSources: {},
    schemaVersion: 4,
    metaInfo: { runtimeE2E: true, undoRedoBlockDelete: true },
    semver: '0.1.0',
  };

  const createResp = await page.request.post('/api/pages', { data: payload });
  expect(
    createResp.ok(),
    `Create block delete undo/redo authoring page failed: ${createResp.status()}`,
  ).toBeTruthy();
  const createBody = await createResp.json();
  expect(createBody.code, 'create page API code').toBe('0');
  const pid = String(createBody.data?.pid || '');
  expect(pid, 'created block delete undo/redo authoring pid').toBeTruthy();
  return { pid, pageKey, title };
}

async function createBlockReorderUndoRedoPage(page: Page) {
  const id = uniqueId('pd_block_reorder_undo_redo_authoring');
  const pageKey = id.replace(/-/g, '_');
  const title = `Block reorder undo redo authoring ${id}`;
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
        id: BLOCK_REORDER_FIRST_ID,
        blockType: 'text',
        props: { content: 'First reorder seed text' },
      },
      {
        id: BLOCK_REORDER_SECOND_ID,
        blockType: 'text',
        props: { content: 'Second reorder seed text' },
      },
    ],
    dataSources: {},
    schemaVersion: 4,
    metaInfo: { runtimeE2E: true, undoRedoBlockReorder: true },
    semver: '0.1.0',
  };

  const createResp = await page.request.post('/api/pages', { data: payload });
  expect(
    createResp.ok(),
    `Create block reorder undo/redo authoring page failed: ${createResp.status()}`,
  ).toBeTruthy();
  const createBody = await createResp.json();
  expect(createBody.code, 'create page API code').toBe('0');
  const pid = String(createBody.data?.pid || '');
  expect(pid, 'created block reorder undo/redo authoring pid').toBeTruthy();
  return { pid, pageKey, title };
}

async function createNestedTabsUndoRedoPage(page: Page) {
  const id = uniqueId('pd_nested_tabs_undo_redo_authoring');
  const pageKey = id.replace(/-/g, '_');
  const title = `Nested tabs undo redo authoring ${id}`;
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
        id: NESTED_TABS_BLOCK_ID,
        blockType: 'tabs',
        title: 'Nested undo tabs',
        tabs: [
          {
            key: 'overview',
            label: { 'en-US': 'Overview', 'zh-CN': '概览' },
            filter: null,
            blocks: [
              {
                id: NESTED_TEXT_CHILD_ID,
                blockType: 'text',
                title: { 'en-US': 'Nested text', 'zh-CN': '嵌套文本' },
                props: { content: NESTED_TEXT_ORIGINAL },
              },
            ],
          },
        ],
      },
    ],
    dataSources: {},
    schemaVersion: 4,
    metaInfo: { runtimeE2E: true, undoRedoNestedTabs: true },
    semver: '0.1.0',
  };

  const createResp = await page.request.post('/api/pages', { data: payload });
  expect(
    createResp.ok(),
    `Create nested tabs undo/redo authoring page failed: ${createResp.status()}`,
  ).toBeTruthy();
  const createBody = await createResp.json();
  expect(createBody.code, 'create page API code').toBe('0');
  const pid = String(createBody.data?.pid || '');
  expect(pid, 'created nested tabs undo/redo authoring pid').toBeTruthy();
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

async function canvasBlockIds(page: Page): Promise<string[]> {
  return page.locator('[data-testid="sortable-block"][data-block-id]').evaluateAll((elements) =>
    elements
      .map((element) => (element as HTMLElement).getAttribute('data-block-id') || '')
      .filter(Boolean),
  );
}

async function addBlockViaPalette(page: Page, blockType: string): Promise<string> {
  await page.getByTestId('designer-tab-blocks').click();
  await expect(page.getByTestId('library-tab-blocks')).toBeVisible({ timeout: 5_000 });

  const beforeIds = await canvasBlockIds(page);
  const paletteItem = page.getByTestId(`block-palette-item-${blockType}`);
  await expect(paletteItem).toBeVisible({ timeout: 5_000 });
  await paletteItem.evaluate((element: HTMLElement) => element.click());

  await expect
    .poll(async () => (await canvasBlockIds(page)).length, { timeout: 5_000 })
    .toBe(beforeIds.length + 1);

  const afterIds = await canvasBlockIds(page);
  const newId = afterIds.find((blockId) => !beforeIds.includes(blockId));
  expect(newId, `new ${blockType} block id`).toBeTruthy();
  return newId!;
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

async function deleteCanvasBlock(page: Page, blockId: string) {
  const block = page.locator(`[data-testid="sortable-block"][data-block-id="${blockId}"]`);
  await expect(block).toBeVisible({ timeout: 5_000 });
  await block.click();
  await expect(block.locator('[data-testid="block-delete"]')).toBeVisible({ timeout: 5_000 });
  await block.locator('[data-testid="block-delete"]').click();
  await expect(block).toBeHidden({ timeout: 5_000 });
}

async function dragBlockBelow(page: Page, sourceBlockId: string, targetBlockId: string) {
  const source = page.locator(`[data-testid="sortable-block"][data-block-id="${sourceBlockId}"]`);
  const target = page.locator(`[data-testid="sortable-block"][data-block-id="${targetBlockId}"]`);
  await expect(source).toBeVisible({ timeout: 5_000 });
  await expect(target).toBeVisible({ timeout: 5_000 });

  const sourceBox = await source.boundingBox();
  const targetBox = await target.boundingBox();
  expect(sourceBox, `source block ${sourceBlockId} box`).toBeTruthy();
  expect(targetBox, `target block ${targetBlockId} box`).toBeTruthy();

  await page.mouse.move(sourceBox!.x + sourceBox!.width / 2, sourceBox!.y + 18);
  await page.mouse.down();
  await page.mouse.move(sourceBox!.x + sourceBox!.width / 2 + 12, sourceBox!.y + 42, {
    steps: 4,
  });
  await page.mouse.move(targetBox!.x + targetBox!.width / 2, targetBox!.y + targetBox!.height - 6, {
    steps: 12,
  });
  await page.mouse.up();
  await page.mouse.move(20, 20);
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

async function clickEnabledToolbarButton(page: Page, testId: string) {
  const button = page.getByTestId(testId);
  await expect(button).toBeVisible({ timeout: 5_000 });
  await expect(button).toBeEnabled({ timeout: 5_000 });
  await button.scrollIntoViewIfNeeded();
  await button.click({ trial: true });
  await button.click();
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

function savedNestedChildBlocks(savedPage: Record<string, any>): Record<string, any>[] {
  const tabsBlock = savedBlockById(savedPage, NESTED_TABS_BLOCK_ID);
  const children = tabsBlock.tabs?.[0]?.blocks;
  expect(Array.isArray(children), `saved nested children for ${NESTED_TABS_BLOCK_ID}`).toBeTruthy();
  return children;
}

function savedNestedTextChild(savedPage: Record<string, any>): Record<string, any> {
  const child = savedNestedChildBlocks(savedPage).find((item) => item.id === NESTED_TEXT_CHILD_ID);
  if (!child) {
    throw new Error(`Missing saved nested child ${NESTED_TEXT_CHILD_ID}`);
  }
  return child;
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

    await clickEnabledToolbarButton(page, 'toolbar-undo');
    await expect(page.getByTestId('widget-prop-maxRating').locator('input')).toHaveValue('5');
    await saveDesignerAndWait(page, pid);
    const undonePage = await fetchPageByPid(page, pid);
    const undoneField = savedFieldByCode(undonePage, RATING_FIELD);
    expect(undoneField.component).toBe('rating');
    expect(undoneField.props ?? {}).not.toMatchObject({ maxRating: 7 });

    await clickEnabledToolbarButton(page, 'toolbar-redo');
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

    await clickEnabledToolbarButton(page, 'toolbar-undo');
    await expect(titleInput).toHaveValue('Original block title');
    await saveDesignerAndWait(page, pid);
    const undonePage = await fetchPageByPid(page, pid);
    expect(savedBlockById(undonePage, BLOCK_TITLE_SECTION_ID).title).toBe('Original block title');

    await clickEnabledToolbarButton(page, 'toolbar-redo');
    await expect(titleInput).toHaveValue('Edited block title');
    await saveDesignerAndWait(page, pid);
    const redonePage = await fetchPageByPid(page, pid);
    expect(savedBlockById(redonePage, BLOCK_TITLE_SECTION_ID).title).toBe('Edited block title');
  });

  test('saves the undone schema and then saves the redone schema for a block add action', async ({
    page,
  }) => {
    const { pid } = await createBlockAddUndoRedoPage(page);
    await openDesignerByPid(page, pid);

    const baselineBlockIds = await canvasBlockIds(page);
    const textBlockId = await addBlockViaPalette(page, 'text');
    await expect
      .poll(async () => canvasBlockIds(page), { timeout: 5_000 })
      .toEqual([...baselineBlockIds, textBlockId]);

    await clickEnabledToolbarButton(page, 'toolbar-undo');
    await expect
      .poll(async () => canvasBlockIds(page), { timeout: 5_000 })
      .toEqual(baselineBlockIds);
    await saveDesignerAndWait(page, pid);
    const undonePage = await fetchPageByPid(page, pid);
    expect((undonePage.blocks ?? []).map((block: any) => block.id)).not.toContain(textBlockId);

    await clickEnabledToolbarButton(page, 'toolbar-redo');
    await expect
      .poll(async () => canvasBlockIds(page), { timeout: 5_000 })
      .toEqual([...baselineBlockIds, textBlockId]);
    await saveDesignerAndWait(page, pid);
    const redonePage = await fetchPageByPid(page, pid);
    expect(savedBlockById(redonePage, textBlockId)).toMatchObject({
      id: textBlockId,
      blockType: 'text',
    });
  });

  test('saves the undone schema and then saves the redone schema for a block delete action', async ({
    page,
  }) => {
    const { pid } = await createBlockDeleteUndoRedoPage(page);
    await openDesignerByPid(page, pid);

    const initialBlockIds = await canvasBlockIds(page);
    expect(initialBlockIds, 'initial canvas contains delete target').toContain(BLOCK_DELETE_TEXT_ID);

    await deleteCanvasBlock(page, BLOCK_DELETE_TEXT_ID);
    const afterDeleteBlockIds = initialBlockIds.filter((blockId) => blockId !== BLOCK_DELETE_TEXT_ID);
    await expect
      .poll(async () => canvasBlockIds(page), { timeout: 5_000 })
      .toEqual(afterDeleteBlockIds);

    await page.getByTestId('toolbar-undo').click();
    await expect
      .poll(async () => canvasBlockIds(page), { timeout: 5_000 })
      .toEqual(initialBlockIds);
    await saveDesignerAndWait(page, pid);
    const undonePage = await fetchPageByPid(page, pid);
    expect(savedBlockById(undonePage, BLOCK_DELETE_TEXT_ID)).toMatchObject({
      id: BLOCK_DELETE_TEXT_ID,
      blockType: 'text',
      props: { content: 'Delete undo redo seed text' },
    });

    await page.getByTestId('toolbar-redo').click();
    await expect
      .poll(async () => canvasBlockIds(page), { timeout: 5_000 })
      .toEqual(afterDeleteBlockIds);
    await saveDesignerAndWait(page, pid);
    const redonePage = await fetchPageByPid(page, pid);
    expect((redonePage.blocks ?? []).map((block: any) => block.id)).not.toContain(
      BLOCK_DELETE_TEXT_ID,
    );
  });

  test('saves the undone schema and then saves the redone schema for a block reorder action', async ({
    page,
  }) => {
    const { pid } = await createBlockReorderUndoRedoPage(page);
    await openDesignerByPid(page, pid);

    const initialOrder = [BLOCK_REORDER_FIRST_ID, BLOCK_REORDER_SECOND_ID];
    const reordered = [BLOCK_REORDER_SECOND_ID, BLOCK_REORDER_FIRST_ID];
    await expect
      .poll(async () => canvasBlockIds(page), { timeout: 5_000 })
      .toEqual(initialOrder);

    await dragBlockBelow(page, BLOCK_REORDER_FIRST_ID, BLOCK_REORDER_SECOND_ID);
    await expect
      .poll(async () => canvasBlockIds(page), { timeout: 5_000 })
      .toEqual(reordered);

    await clickEnabledToolbarButton(page, 'toolbar-undo');
    await expect
      .poll(async () => canvasBlockIds(page), { timeout: 5_000 })
      .toEqual(initialOrder);
    await saveDesignerAndWait(page, pid);
    const undonePage = await fetchPageByPid(page, pid);
    expect((undonePage.blocks ?? []).map((block: any) => block.id)).toEqual(initialOrder);

    await clickEnabledToolbarButton(page, 'toolbar-redo');
    await expect
      .poll(async () => canvasBlockIds(page), { timeout: 5_000 })
      .toEqual(reordered);
    await saveDesignerAndWait(page, pid);
    const redonePage = await fetchPageByPid(page, pid);
    expect((redonePage.blocks ?? []).map((block: any) => block.id)).toEqual(reordered);
  });

  test('saves the undone schema and then saves the redone schema for a tabs child property edit', async ({
    page,
  }) => {
    const { pid } = await createNestedTabsUndoRedoPage(page);
    await openDesignerByPid(page, pid);
    await selectCanvasBlock(page, NESTED_TABS_BLOCK_ID);

    const nestedTextInput = page.getByTestId('tab-child-text-content-0');
    await expect(nestedTextInput).toHaveValue(NESTED_TEXT_ORIGINAL);
    await nestedTextInput.fill(NESTED_TEXT_EDITED);
    await expect(nestedTextInput).toHaveValue(NESTED_TEXT_EDITED);

    await clickEnabledToolbarButton(page, 'toolbar-undo');
    await expect(nestedTextInput).toHaveValue(NESTED_TEXT_ORIGINAL);
    await saveDesignerAndWait(page, pid);
    const undonePage = await fetchPageByPid(page, pid);
    expect(savedNestedTextChild(undonePage)).toMatchObject({
      id: NESTED_TEXT_CHILD_ID,
      blockType: 'text',
      props: { content: NESTED_TEXT_ORIGINAL },
    });

    await clickEnabledToolbarButton(page, 'toolbar-redo');
    await expect(nestedTextInput).toHaveValue(NESTED_TEXT_EDITED);
    await saveDesignerAndWait(page, pid);
    const redonePage = await fetchPageByPid(page, pid);
    expect(savedNestedTextChild(redonePage)).toMatchObject({
      id: NESTED_TEXT_CHILD_ID,
      blockType: 'text',
      props: { content: NESTED_TEXT_EDITED },
    });
  });

  test('saves the undone schema and then saves the redone schema for a tabs child add action', async ({
    page,
  }) => {
    const { pid } = await createNestedTabsUndoRedoPage(page);
    await openDesignerByPid(page, pid);
    await selectCanvasBlock(page, NESTED_TABS_BLOCK_ID);

    await expect(page.getByTestId('tab-child-block-0')).toBeVisible();
    await expect(page.getByTestId('tab-child-block-1')).toHaveCount(0);
    await page.getByTestId('tab-child-add-text-block').click();
    await expect(page.getByTestId('tab-child-block-1')).toBeVisible();
    await expect(page.getByTestId('tab-child-text-content-1')).toHaveValue('');

    await clickEnabledToolbarButton(page, 'toolbar-undo');
    await expect(page.getByTestId('tab-child-block-0')).toBeVisible();
    await expect(page.getByTestId('tab-child-block-1')).toHaveCount(0);
    await saveDesignerAndWait(page, pid);
    const undonePage = await fetchPageByPid(page, pid);
    const undoneChildren = savedNestedChildBlocks(undonePage);
    expect(undoneChildren).toHaveLength(1);
    expect(undoneChildren[0]).toMatchObject({
      id: NESTED_TEXT_CHILD_ID,
      blockType: 'text',
      props: { content: NESTED_TEXT_ORIGINAL },
    });

    await clickEnabledToolbarButton(page, 'toolbar-redo');
    await expect(page.getByTestId('tab-child-block-1')).toBeVisible();
    await saveDesignerAndWait(page, pid);
    const redonePage = await fetchPageByPid(page, pid);
    const redoneChildren = savedNestedChildBlocks(redonePage);
    expect(redoneChildren).toHaveLength(2);
    expect(redoneChildren[0]).toMatchObject({ id: NESTED_TEXT_CHILD_ID, blockType: 'text' });
    expect(redoneChildren[1]).toMatchObject({
      blockType: 'text',
      title: { 'en-US': 'Text', 'zh-CN': '文本内容' },
      props: { content: '' },
    });
  });

  test('saves the undone schema and then saves the redone schema for a tabs child remove action', async ({
    page,
  }) => {
    const { pid } = await createNestedTabsUndoRedoPage(page);
    await openDesignerByPid(page, pid);
    await selectCanvasBlock(page, NESTED_TABS_BLOCK_ID);

    await expect(page.getByTestId('tab-child-block-0')).toBeVisible();
    await page.getByTestId('tab-child-remove-0').click();
    await expect(page.getByTestId('tab-child-block-0')).toHaveCount(0);

    await clickEnabledToolbarButton(page, 'toolbar-undo');
    await expect(page.getByTestId('tab-child-block-0')).toBeVisible();
    await expect(page.getByTestId('tab-child-text-content-0')).toHaveValue(NESTED_TEXT_ORIGINAL);
    await saveDesignerAndWait(page, pid);
    const undonePage = await fetchPageByPid(page, pid);
    const undoneChildren = savedNestedChildBlocks(undonePage);
    expect(undoneChildren).toHaveLength(1);
    expect(undoneChildren[0]).toMatchObject({
      id: NESTED_TEXT_CHILD_ID,
      blockType: 'text',
      props: { content: NESTED_TEXT_ORIGINAL },
    });

    await clickEnabledToolbarButton(page, 'toolbar-redo');
    await expect(page.getByTestId('tab-child-block-0')).toHaveCount(0);
    await saveDesignerAndWait(page, pid);
    const redonePage = await fetchPageByPid(page, pid);
    expect(savedNestedChildBlocks(redonePage)).toHaveLength(0);
  });
});
