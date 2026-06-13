/**
 * Regression spec for the unified-designer overhaul (PR feat/unified-designer-overhaul).
 *
 * Guards the three headline fixes on a real form-kind page:
 *  - canvas band shows the localized page-kind label (表单), not the old
 *    hardcoded "Composite canvas"
 *  - the Blocks palette collapses to the page kind: a form page exposes form
 *    blocks only (no List/Detail/Dashboard), and never the bare placeholder
 *    leaf blocks (field/column/filter-field)
 *  - dragging a model field from the Fields library binds it as a real field
 *    block via @dnd-kit (the drag layer that unit tests mock out)
 *
 * The form page is discovered from /api/pages so the spec is portable across
 * seeds. Default UI locale is zh-CN.
 *
 * Dimensions: D1 (auth/session), D6 (designer canvas), D9 (regression guard)
 */

import { test, expect } from '../../fixtures';
import type { Locator, Page } from '@playwright/test';
import { uniqueId } from '../helpers';

type TestBlock = { id: string; blocks?: TestBlock[] };
type CreatedDesignerPage = { pageKey: string; pid: string };

async function createFormPage(page: Page): Promise<string> {
  const id = uniqueId('udw_kind');
  const pageKey = `udw_kind_${id}`;
  const resp = await page.request.post('/api/pages', {
    data: {
      name: `UDW kind ${id}`,
      pageKey,
      title: `UDW kind ${id}`,
      kind: 'form',
      modelCode: 'page_schema',
      schemaVersion: 3,
      blocks: [
        {
          id: 'form_root',
          blockType: 'form',
          title: 'Form root',
          dataSource: { model: 'page_schema' },
          layout: { span: 12 },
          blocks: [
            {
              id: 'section_main',
              blockType: 'form-section',
              title: 'Main section',
              layout: { span: 12 },
              blocks: [
                {
                  id: 'field_seed_name',
                  blockType: 'field',
                  field: 'name',
                  layout: { span: 6 },
                  props: { label: 'Seed name', component: 'input' },
                },
                {
                  id: 'field_seed_page_key',
                  blockType: 'field',
                  field: 'page_key',
                  layout: { span: 6 },
                  props: { label: 'Seed page key', component: 'input' },
                },
              ],
            },
          ],
        },
      ],
      extension: { e2e: true, scenario: 'unified-designer-kind-and-binding' },
    },
  });
  expect(resp.ok(), await resp.text()).toBe(true);
  const body = await resp.json();
  expect(body.code).toBe('0');
  return pageKey;
}

async function createCrossContainerFormPage(
  page: Page,
  options: { emptyTarget?: boolean } = {},
): Promise<CreatedDesignerPage> {
  const id = uniqueId('udw_cross_container');
  const pageKey = `udw_cross_container_${id}`;
  const targetBlocks = options.emptyTarget
    ? []
    : [
        {
          id: 'field_target_email',
          blockType: 'field',
          field: 'description',
          layout: { span: 6 },
          props: { label: 'Target email', component: 'textarea' },
        },
        {
          id: 'field_target_status',
          blockType: 'field',
          field: 'status',
          layout: { span: 6 },
          props: { label: 'Target status', component: 'select' },
        },
      ];
  const resp = await page.request.post('/api/pages', {
    data: {
      name: `UDW cross container ${id}`,
      pageKey,
      title: `UDW cross container ${id}`,
      kind: 'form',
      modelCode: 'page_schema',
      schemaVersion: 3,
      blocks: [
        {
          id: 'form_root',
          blockType: 'form',
          title: 'Form root',
          dataSource: { model: 'page_schema' },
          layout: { span: 12 },
          blocks: [
            {
              id: 'section_source',
              blockType: 'form-section',
              title: 'Source section',
              layout: { span: 12 },
              blocks: [
                {
                  id: 'field_source_name',
                  blockType: 'field',
                  field: 'name',
                  layout: { span: 6 },
                  props: { label: 'Source name', component: 'input' },
                },
                {
                  id: 'field_move_candidate',
                  blockType: 'field',
                  field: 'page_key',
                  layout: { span: 6 },
                  props: { label: 'Move candidate', component: 'input' },
                },
              ],
            },
            {
              id: 'section_target',
              blockType: 'form-section',
              title: 'Target section',
              layout: { span: 12 },
              blocks: targetBlocks,
            },
          ],
        },
      ],
      extension: { e2e: true, scenario: 'unified-designer-cross-container-move' },
    },
  });
  expect(resp.ok(), await resp.text()).toBe(true);
  const body = await resp.json();
  expect(body.code).toBe('0');
  expect(body.data?.pid).toBeTruthy();
  return { pageKey, pid: body.data.pid };
}

async function createCrossContainerSubTableFormPage(
  page: Page,
  options: { emptyTarget?: boolean } = {},
): Promise<CreatedDesignerPage> {
  const id = uniqueId('udw_sub_table_move');
  const pageKey = `udw_sub_table_move_${id}`;
  const targetBlocks = options.emptyTarget
    ? []
    : [
        {
          id: 'sub_table_target',
          blockType: 'sub-table',
          title: 'Target items',
          layout: { span: 12 },
          blocks: [
            {
              id: 'target_col_status',
              blockType: 'column',
              field: 'status',
              props: { label: 'Target status' },
            },
          ],
        },
      ];
  const resp = await page.request.post('/api/pages', {
    data: {
      name: `UDW sub table move ${id}`,
      pageKey,
      title: `UDW sub table move ${id}`,
      kind: 'form',
      modelCode: 'page_schema',
      schemaVersion: 3,
      blocks: [
        {
          id: 'form_root',
          blockType: 'form',
          title: 'Form root',
          dataSource: { model: 'page_schema' },
          layout: { span: 12 },
          blocks: [
            {
              id: 'section_source',
              blockType: 'form-section',
              title: 'Source section',
              layout: { span: 12 },
              blocks: [
                {
                  id: 'field_source_name',
                  blockType: 'field',
                  field: 'name',
                  layout: { span: 6 },
                  props: { label: 'Source name', component: 'input' },
                },
                {
                  id: 'sub_table_move_candidate',
                  blockType: 'sub-table',
                  title: 'Move candidate items',
                  layout: { span: 12 },
                  blocks: [
                    {
                      id: 'candidate_col_title',
                      blockType: 'column',
                      field: 'name',
                      props: { label: 'Candidate title' },
                    },
                    {
                      id: 'candidate_action_add',
                      blockType: 'action',
                      actionType: 'create',
                      props: { label: 'Add item' },
                    },
                  ],
                },
              ],
            },
            {
              id: 'section_target',
              blockType: 'form-section',
              title: 'Target section',
              layout: { span: 12 },
              blocks: targetBlocks,
            },
          ],
        },
      ],
      extension: { e2e: true, scenario: 'unified-designer-sub-table-cross-container-move' },
    },
  });
  expect(resp.ok(), await resp.text()).toBe(true);
  const body = await resp.json();
  expect(body.code).toBe('0');
  expect(body.data?.pid).toBeTruthy();
  return { pageKey, pid: body.data.pid };
}

async function createCrossKindGuardFormPage(page: Page): Promise<CreatedDesignerPage> {
  const id = uniqueId('udw_cross_kind_guard');
  const pageKey = `udw_cross_kind_guard_${id}`;
  const resp = await page.request.post('/api/pages', {
    data: {
      name: `UDW cross kind guard ${id}`,
      pageKey,
      title: `UDW cross kind guard ${id}`,
      kind: 'form',
      modelCode: 'page_schema',
      schemaVersion: 3,
      blocks: [
        {
          id: 'form_root',
          blockType: 'form',
          title: 'Form root',
          dataSource: { model: 'page_schema' },
          layout: { span: 12 },
          blocks: [
            {
              id: 'tabs_root',
              blockType: 'tabs',
              title: 'Tabs root',
              layout: { span: 12 },
              blocks: [
                {
                  id: 'tab_main',
                  blockType: 'tab',
                  title: 'Main tab',
                  blocks: [
                    {
                      id: 'section_main',
                      blockType: 'form-section',
                      title: 'Main section',
                      layout: { span: 12 },
                      blocks: [
                        {
                          id: 'field_inside_section',
                          blockType: 'field',
                          field: 'name',
                          layout: { span: 6 },
                          props: { label: 'Section field', component: 'input' },
                        },
                      ],
                    },
                    {
                      id: 'detail_section_from_detail',
                      blockType: 'detail-section',
                      title: 'Detail section from stale schema',
                      layout: { span: 12 },
                      blocks: [
                        {
                          id: 'field_inside_detail_section',
                          blockType: 'field',
                          field: 'description',
                          layout: { span: 12 },
                          props: { label: 'Invalid detail field', component: 'textarea' },
                        },
                      ],
                    },
                  ],
                },
              ],
            },
          ],
        },
      ],
      extension: { e2e: true, scenario: 'unified-designer-cross-kind-guard' },
    },
  });
  expect(resp.ok(), await resp.text()).toBe(true);
  const body = await resp.json();
  expect(body.code).toBe('0');
  expect(body.data?.pid).toBeTruthy();
  return { pageKey, pid: body.data.pid };
}

/**
 * Open the unified designer for a page key. On a cold dev Vite the very first
 * navigation can race optimizeDeps and render a transient "Application Error";
 * reload once (Vite is warm by then) before asserting on the workbench.
 */
async function openDesigner(page: Page, pageKey: string) {
  const workbench = page.getByTestId('unified-designer-workbench');
  const attempts = 4;
  for (let i = 0; i < attempts; i++) {
    if (i === 0) {
      await page.goto(`/unified-designer?pageKey=${pageKey}`, { waitUntil: 'domcontentloaded' });
    } else {
      // Cold dev Vite re-runs optimizeDeps on the heavy designer route and can
      // render a transient "Application Error" until it settles; reload until ready.
      await page.reload({ waitUntil: 'domcontentloaded' });
    }
    try {
      await workbench.waitFor({ state: 'visible', timeout: i === attempts - 1 ? 45000 : 15000 });
      return;
    } catch {
      if (i === attempts - 1) throw new Error('unified-designer-workbench never became visible');
    }
  }
}

test.describe('Unified designer — kind collapse, i18n, model binding', () => {
  // The designer route pulls a heavy dep graph (@dnd-kit, lucide, react-router
  // framework). On a cold dev Vite the first load triggers optimizeDeps and can
  // need a couple of reloads to settle, which exceeds the default per-test budget.
  test.describe.configure({ timeout: 120_000 });

  test('a form page collapses the palette and renders zh-CN copy', async ({ page }) => {
    const formKey = await createFormPage(page);
    await openDesigner(page, formKey);

    // Canvas band shows the localized form kind label, not the old Composite text.
    const band = page.getByTestId('canvas-root-drop-zone');
    await expect(band).toContainText('表单');
    await expect(band).not.toContainText('组合页面');
    await expect(band).not.toContainText('Composite');

    // zh-CN designer chrome.
    await expect(page.getByTestId('resource-tab-blocks')).toHaveText('区块');

    // Palette collapses to the form kind; other page kinds + placeholder leaves absent.
    await page.getByTestId('resource-tab-blocks').click();
    await expect(page.getByTestId('palette-add-form-section')).toBeVisible();
    await expect(page.getByTestId('palette-add-list')).toHaveCount(0);
    await expect(page.getByTestId('palette-add-detail')).toHaveCount(0);
    await expect(page.getByTestId('palette-add-dashboard')).toHaveCount(0);
    await expect(page.getByTestId('palette-add-field')).toHaveCount(0);
    await expect(page.getByTestId('palette-add-column')).toHaveCount(0);
    await expect(page.getByTestId('palette-add-filter-field')).toHaveCount(0);
  });

  test('dragging a model field into a section binds a field block via @dnd-kit', async ({ page }) => {
    const formKey = await createFormPage(page);
    await openDesigner(page, formKey);

    // Wait for the outline tree to populate before querying it.
    await expect(page.locator('[data-testid^="outline-item-"]').first()).toBeVisible({
      timeout: 15000,
    });

    // Select the first form-section from the outline.
    const sectionItem = page
      .locator('button[data-testid^="outline-item-"]')
      .filter({ hasText: 'form-section' })
      .first();
    await expect(sectionItem).toBeVisible();
    const sectionTestId = await sectionItem.getAttribute('data-testid');
    const sectionId = sectionTestId!.replace('outline-item-', '');
    await sectionItem.click();

    // Open the Fields library; model fields load async, so wait before deciding.
    await page.getByTestId('resource-tab-fields').click();
    await page
      .locator('[data-testid^="model-field-"]')
      .first()
      .waitFor({ state: 'visible', timeout: 10000 })
      .catch(() => {});
    const fieldItem = page.locator('[data-testid^="model-field-"][data-used="false"]').first();
    await expect(fieldItem).toBeVisible();

    const beforeFields = await page.locator('[data-testid^="canvas-block-field_"]').count();

    // Real @dnd-kit pointer drag: field item -> section canvas block.
    const target = page.getByTestId(`canvas-block-${sectionId}`);
    const src = await fieldItem.boundingBox();
    const dst = await target.boundingBox();
    expect(src && dst).toBeTruthy();
    await page.mouse.move(src!.x + src!.width / 2, src!.y + src!.height / 2);
    await page.mouse.down();
    await page.mouse.move(src!.x + src!.width / 2 + 12, src!.y + src!.height / 2 + 12, { steps: 6 });
    await page.mouse.move(dst!.x + dst!.width / 2, dst!.y + dst!.height / 2, { steps: 14 });
    await page.mouse.move(dst!.x + dst!.width / 2 + 3, dst!.y + dst!.height / 2 + 3, { steps: 4 });
    await page.mouse.up();

    await expect
      .poll(async () => page.locator('[data-testid^="canvas-block-field_"]').count())
      .toBeGreaterThan(beforeFields);
  });

  test('moves an existing field block between form-section containers and persists schema order', async ({ page }) => {
    const { pageKey: formKey, pid } = await createCrossContainerFormPage(page);
    await openDesigner(page, formKey);
    await expect(page.locator('[data-testid^="outline-item-"]').first()).toBeVisible();

    await page.getByTestId('designer-mode-layout').click();
    await dragCanvasBlockBefore(page, 'field_move_candidate', 'field_target_email');

    const sourceSection = page.getByTestId('canvas-block-section_source');
    const targetSection = page.getByTestId('canvas-block-section_target');
    await expect(sourceSection.getByTestId('canvas-block-field_move_candidate')).toHaveCount(0);
    await expect(targetSection.getByTestId('canvas-block-field_move_candidate')).toBeVisible();
    await expect(targetSection.getByTestId('canvas-block-field_target_email')).toBeVisible();
    expect(await isBeforeInDom(targetSection, 'field_move_candidate', 'field_target_email')).toBe(true);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesignerPage(page, pid);

    const readback = await page.request.get(`/api/pages/key/${formKey}`);
    expect(readback.ok(), await readback.text()).toBe(true);
    const readbackBody = await readback.json();
    expect(readbackBody.code).toBe('0');
    const savedBlocks = readbackBody.data.blocks as Array<{ id: string; blocks?: Array<{ id: string }> }>;
    const savedSource = findBlock(savedBlocks, 'section_source');
    const savedTarget = findBlock(savedBlocks, 'section_target');

    expect(savedSource?.blocks?.map((block) => block.id)).toEqual(['field_source_name']);
    expect(savedTarget?.blocks?.map((block) => block.id)).toEqual([
      'field_move_candidate',
      'field_target_email',
      'field_target_status',
    ]);
  });

  test('undoes and redoes a cross-container move-before before saving schema order', async ({ page }) => {
    const { pageKey: formKey, pid } = await createCrossContainerFormPage(page);
    await openDesigner(page, formKey);
    await expect(page.locator('[data-testid^="outline-item-"]').first()).toBeVisible();

    await page.getByTestId('designer-mode-layout').click();
    await dragCanvasBlockBefore(page, 'field_move_candidate', 'field_target_email');

    const sourceSection = page.getByTestId('canvas-block-section_source');
    const targetSection = page.getByTestId('canvas-block-section_target');
    await expect(sourceSection.getByTestId('canvas-block-field_move_candidate')).toHaveCount(0);
    await expect(targetSection.getByTestId('canvas-block-field_move_candidate')).toBeVisible();
    expect(await isBeforeInDom(targetSection, 'field_move_candidate', 'field_target_email')).toBe(true);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');
    await waitForDesignerDragToSettle(page);

    await clickDesignerToolbarButton(page, 'designer-undo');

    await expect(sourceSection.getByTestId('canvas-block-field_move_candidate')).toBeVisible();
    await expect(targetSection.getByTestId('canvas-block-field_move_candidate')).toHaveCount(0);
    expect(await isBeforeInDom(sourceSection, 'field_source_name', 'field_move_candidate')).toBe(true);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');
    await expect(page.getByTestId('designer-redo')).toBeEnabled();

    await clickDesignerToolbarButton(page, 'designer-redo');

    await expect(sourceSection.getByTestId('canvas-block-field_move_candidate')).toHaveCount(0);
    await expect(targetSection.getByTestId('canvas-block-field_move_candidate')).toBeVisible();
    expect(await isBeforeInDom(targetSection, 'field_move_candidate', 'field_target_email')).toBe(true);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesignerPage(page, pid);

    const readback = await page.request.get(`/api/pages/key/${formKey}`);
    expect(readback.ok(), await readback.text()).toBe(true);
    const readbackBody = await readback.json();
    expect(readbackBody.code).toBe('0');
    const savedBlocks = readbackBody.data.blocks as TestBlock[];
    const savedSource = findBlock(savedBlocks, 'section_source');
    const savedTarget = findBlock(savedBlocks, 'section_target');

    expect(savedSource?.blocks?.map((block) => block.id)).toEqual(['field_source_name']);
    expect(savedTarget?.blocks?.map((block) => block.id)).toEqual([
      'field_move_candidate',
      'field_target_email',
      'field_target_status',
    ]);
  });

  test('moves an existing field block inside an empty form-section container and persists schema order', async ({ page }) => {
    const { pageKey: formKey, pid } = await createCrossContainerFormPage(page, { emptyTarget: true });
    await openDesigner(page, formKey);
    await expect(page.locator('[data-testid^="outline-item-"]').first()).toBeVisible();

    await page.getByTestId('designer-mode-layout').click();
    await dragCanvasBlockInto(page, 'field_move_candidate', 'section_target');

    const sourceSection = page.getByTestId('canvas-block-section_source');
    const targetSection = page.getByTestId('canvas-block-section_target');
    await expect(sourceSection.getByTestId('canvas-block-field_move_candidate')).toHaveCount(0);
    await expect(targetSection.getByTestId('canvas-block-field_move_candidate')).toBeVisible();
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesignerPage(page, pid);

    const readback = await page.request.get(`/api/pages/key/${formKey}`);
    expect(readback.ok(), await readback.text()).toBe(true);
    const readbackBody = await readback.json();
    expect(readbackBody.code).toBe('0');
    const savedBlocks = readbackBody.data.blocks as TestBlock[];
    const savedSource = findBlock(savedBlocks, 'section_source');
    const savedTarget = findBlock(savedBlocks, 'section_target');

    expect(savedSource?.blocks?.map((block) => block.id)).toEqual(['field_source_name']);
    expect(savedTarget?.blocks?.map((block) => block.id)).toEqual(['field_move_candidate']);
  });

  test('undoes and redoes a cross-container move-inside into an empty section before saving schema order', async ({ page }) => {
    const { pageKey: formKey, pid } = await createCrossContainerFormPage(page, { emptyTarget: true });
    await openDesigner(page, formKey);
    await expect(page.locator('[data-testid^="outline-item-"]').first()).toBeVisible();

    await page.getByTestId('designer-mode-layout').click();
    await dragCanvasBlockInto(page, 'field_move_candidate', 'section_target');

    const sourceSection = page.getByTestId('canvas-block-section_source');
    const targetSection = page.getByTestId('canvas-block-section_target');
    await expect(sourceSection.getByTestId('canvas-block-field_move_candidate')).toHaveCount(0);
    await expect(targetSection.getByTestId('canvas-block-field_move_candidate')).toBeVisible();
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');
    await waitForDesignerDragToSettle(page);

    await clickDesignerToolbarButton(page, 'designer-undo');

    await expect(sourceSection.getByTestId('canvas-block-field_move_candidate')).toBeVisible();
    await expect(targetSection.getByTestId('canvas-block-field_move_candidate')).toHaveCount(0);
    expect(await isBeforeInDom(sourceSection, 'field_source_name', 'field_move_candidate')).toBe(true);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');
    await expect(page.getByTestId('designer-redo')).toBeEnabled();

    await clickDesignerToolbarButton(page, 'designer-redo');

    await expect(sourceSection.getByTestId('canvas-block-field_move_candidate')).toHaveCount(0);
    await expect(targetSection.getByTestId('canvas-block-field_move_candidate')).toBeVisible();
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesignerPage(page, pid);

    const readback = await page.request.get(`/api/pages/key/${formKey}`);
    expect(readback.ok(), await readback.text()).toBe(true);
    const readbackBody = await readback.json();
    expect(readbackBody.code).toBe('0');
    const savedBlocks = readbackBody.data.blocks as TestBlock[];
    const savedSource = findBlock(savedBlocks, 'section_source');
    const savedTarget = findBlock(savedBlocks, 'section_target');

    expect(savedSource?.blocks?.map((block) => block.id)).toEqual(['field_source_name']);
    expect(savedTarget?.blocks?.map((block) => block.id)).toEqual(['field_move_candidate']);
  });

  test('moves an existing sub-table subtree before another sub-table in a different section', async ({ page }) => {
    const { pageKey: formKey, pid } = await createCrossContainerSubTableFormPage(page);
    await openDesigner(page, formKey);
    await expect(page.locator('[data-testid^="outline-item-"]').first()).toBeVisible();

    await page.getByTestId('designer-mode-layout').click();
    await dragCanvasBlockBeforeHeader(page, 'sub_table_move_candidate', 'sub_table_target');

    const sourceSection = page.getByTestId('canvas-block-section_source');
    const targetSection = page.getByTestId('canvas-block-section_target');
    await expect(sourceSection.getByTestId('canvas-block-sub_table_move_candidate')).toHaveCount(0);
    await expect(targetSection.getByTestId('canvas-block-sub_table_move_candidate')).toBeVisible();
    await expect(targetSection.getByTestId('canvas-block-sub_table_target')).toBeVisible();
    expect(await isBeforeInDom(targetSection, 'sub_table_move_candidate', 'sub_table_target')).toBe(true);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesignerPage(page, pid);

    const readback = await page.request.get(`/api/pages/key/${formKey}`);
    expect(readback.ok(), await readback.text()).toBe(true);
    const readbackBody = await readback.json();
    expect(readbackBody.code).toBe('0');
    const savedBlocks = readbackBody.data.blocks as TestBlock[];
    const savedSource = findBlock(savedBlocks, 'section_source');
    const savedTarget = findBlock(savedBlocks, 'section_target');
    const movedSubTable = findBlock(savedBlocks, 'sub_table_move_candidate');

    expect(savedSource?.blocks?.map((block) => block.id)).toEqual(['field_source_name']);
    expect(savedTarget?.blocks?.map((block) => block.id)).toEqual([
      'sub_table_move_candidate',
      'sub_table_target',
    ]);
    expect(movedSubTable?.blocks?.map((block) => block.id)).toEqual([
      'candidate_col_title',
      'candidate_action_add',
    ]);
  });

  test('moves an existing sub-table subtree inside an empty section and preserves children', async ({ page }) => {
    const { pageKey: formKey, pid } = await createCrossContainerSubTableFormPage(page, { emptyTarget: true });
    await openDesigner(page, formKey);
    await expect(page.locator('[data-testid^="outline-item-"]').first()).toBeVisible();

    await page.getByTestId('designer-mode-layout').click();
    await dragCanvasBlockInto(page, 'sub_table_move_candidate', 'section_target');

    const sourceSection = page.getByTestId('canvas-block-section_source');
    const targetSection = page.getByTestId('canvas-block-section_target');
    await expect(sourceSection.getByTestId('canvas-block-sub_table_move_candidate')).toHaveCount(0);
    await expect(targetSection.getByTestId('canvas-block-sub_table_move_candidate')).toBeVisible();
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');

    await saveDesignerPage(page, pid);

    const readback = await page.request.get(`/api/pages/key/${formKey}`);
    expect(readback.ok(), await readback.text()).toBe(true);
    const readbackBody = await readback.json();
    expect(readbackBody.code).toBe('0');
    const savedBlocks = readbackBody.data.blocks as TestBlock[];
    const savedSource = findBlock(savedBlocks, 'section_source');
    const savedTarget = findBlock(savedBlocks, 'section_target');
    const movedSubTable = findBlock(savedBlocks, 'sub_table_move_candidate');

    expect(savedSource?.blocks?.map((block) => block.id)).toEqual(['field_source_name']);
    expect(savedTarget?.blocks?.map((block) => block.id)).toEqual(['sub_table_move_candidate']);
    expect(movedSubTable?.blocks?.map((block) => block.id)).toEqual([
      'candidate_col_title',
      'candidate_action_add',
    ]);
  });

  test('rejects moving a cross-kind block within a form designer and keeps persisted schema unchanged', async ({ page }) => {
    const { pageKey: formKey } = await createCrossKindGuardFormPage(page);
    await openDesigner(page, formKey);
    await expect(page.locator('[data-testid^="outline-item-"]').first()).toBeVisible();

    await page.getByTestId('designer-mode-layout').click();
    const tabsRoot = page.getByTestId('canvas-block-tabs_root');
    await expect(tabsRoot.getByTestId('canvas-block-section_main')).toBeVisible();
    await expect(tabsRoot.getByTestId('canvas-block-detail_section_from_detail')).toBeVisible();
    expect(await isBeforeInDom(tabsRoot, 'section_main', 'detail_section_from_detail')).toBe(true);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    await dragCanvasBlockBefore(page, 'detail_section_from_detail', 'section_main');

    await expect(tabsRoot.getByTestId('canvas-block-section_main')).toBeVisible();
    await expect(tabsRoot.getByTestId('canvas-block-detail_section_from_detail')).toBeVisible();
    expect(await isBeforeInDom(tabsRoot, 'section_main', 'detail_section_from_detail')).toBe(true);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    const readback = await page.request.get(`/api/pages/key/${formKey}`);
    expect(readback.ok(), await readback.text()).toBe(true);
    const readbackBody = await readback.json();
    expect(readbackBody.code).toBe('0');
    const savedBlocks = readbackBody.data.blocks as TestBlock[];
    const tabMain = findBlock(savedBlocks, 'tab_main');
    expect(tabMain?.blocks?.map((block) => block.id)).toEqual([
      'section_main',
      'detail_section_from_detail',
    ]);
  });

  // Guards Playwright `.dragTo()` compatibility with @dnd-kit. The wider designer
  // E2E suite (unified-designer-workbench UDW-*) drives drags via `.dragTo()`,
  // whose single jump-move pointerWithin can miss — the workbench's
  // pointerWithin→closestCenter fallback is what keeps those green.
  test('binds a model field via Playwright .dragTo() (UDW drag-driver guard)', async ({ page }) => {
    const formKey = await createFormPage(page);
    await openDesigner(page, formKey);
    await expect(page.locator('[data-testid^="outline-item-"]').first()).toBeVisible({ timeout: 15000 });

    const sectionItem = page
      .locator('button[data-testid^="outline-item-"]')
      .filter({ hasText: 'form-section' })
      .first();
    await expect(sectionItem).toBeVisible();
    const sectionId = (await sectionItem.getAttribute('data-testid'))!.replace('outline-item-', '');
    await sectionItem.click();

    await page.getByTestId('resource-tab-fields').click();
    const fieldItem = page.locator('[data-testid^="model-field-"][data-used="false"]').first();
    await fieldItem.waitFor({ state: 'visible', timeout: 10000 }).catch(() => {});
    await expect(fieldItem).toBeVisible();

    const before = await page.locator('[data-testid^="canvas-block-field_"]').count();
    await fieldItem.dragTo(page.getByTestId(`canvas-block-${sectionId}`));
    await expect
      .poll(() => page.locator('[data-testid^="canvas-block-field_"]').count())
      .toBeGreaterThan(before);
  });

  // Block deletion: a designer must let users remove blocks (golden-standard
  // delete). The top-level kind container is protected; descendants are deletable.
  test('deletes a canvas block via the delete control and persists the removal', async ({ page }) => {
    const formKey = await createFormPage(page);
    await openDesigner(page, formKey);
    await expect(page.locator('[data-testid^="outline-item-"]').first()).toBeVisible({ timeout: 15000 });

    // The root form container has no delete control (it defines the page kind).
    const rootItem = page.locator('button[data-testid^="outline-item-"]').first();
    const rootId = (await rootItem.getAttribute('data-testid'))!.replace('outline-item-', '');
    await expect(page.getByTestId(`block-delete-${rootId}`)).toHaveCount(0);

    // Pick a deletable descendant block that exposes a delete control.
    const deletable = page.locator('[data-testid^="block-delete-"]').first();
    await expect(deletable).toBeVisible({ timeout: 10000 });
    const deleteTestId = (await deletable.getAttribute('data-testid'))!;
    const blockId = deleteTestId.replace('block-delete-', '');
    await expect(page.getByTestId(`canvas-block-${blockId}`)).toBeVisible();

    await deletable.click();

    await expect(page.getByTestId(`canvas-block-${blockId}`)).toHaveCount(0);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');
  });
});

async function dragCanvasBlockBefore(page: Page, sourceBlockId: string, targetBlockId: string) {
  const sourceHandle = page.getByTestId(`block-drag-handle-${sourceBlockId}`);
  const targetBlock = page.getByTestId(`canvas-block-${targetBlockId}`);
  await expect(sourceHandle).toBeVisible();
  await expect(targetBlock).toBeVisible();

  const src = await sourceHandle.boundingBox();
  const dst = await targetBlock.boundingBox();
  expect(src && dst).toBeTruthy();
  await page.mouse.move(src!.x + src!.width / 2, src!.y + src!.height / 2);
  await page.mouse.down();
  await page.mouse.move(src!.x + src!.width / 2 + 12, src!.y + src!.height / 2 + 12, { steps: 6 });
  await page.mouse.move(dst!.x + dst!.width / 2, dst!.y + dst!.height / 2, { steps: 18 });
  await page.mouse.move(dst!.x + dst!.width / 2 + 3, dst!.y + dst!.height / 2 + 3, { steps: 4 });
  await page.mouse.up();
}

async function dragCanvasBlockBeforeHeader(page: Page, sourceBlockId: string, targetBlockId: string) {
  const sourceHandle = page.getByTestId(`block-drag-handle-${sourceBlockId}`);
  const targetBlock = page.getByTestId(`canvas-block-${targetBlockId}`);
  await expect(sourceHandle).toBeVisible();
  await expect(targetBlock).toBeVisible();

  const src = await sourceHandle.boundingBox();
  const dst = await targetBlock.boundingBox();
  expect(src && dst).toBeTruthy();
  const targetX = dst!.x + dst!.width / 2;
  const targetY = dst!.y + Math.min(20, Math.max(12, dst!.height * 0.18));
  await page.mouse.move(src!.x + src!.width / 2, src!.y + src!.height / 2);
  await page.mouse.down();
  await page.mouse.move(src!.x + src!.width / 2 + 12, src!.y + src!.height / 2 + 12, { steps: 6 });
  await page.mouse.move(targetX, targetY, { steps: 18 });
  await page.mouse.move(targetX + 3, targetY + 3, { steps: 4 });
  await page.mouse.up();
}

async function dragCanvasBlockInto(page: Page, sourceBlockId: string, parentBlockId: string) {
  const sourceHandle = page.getByTestId(`block-drag-handle-${sourceBlockId}`);
  const targetBlock = page.getByTestId(`canvas-block-${parentBlockId}`);
  await expect(sourceHandle).toBeVisible();
  await expect(targetBlock).toBeVisible();

  const src = await sourceHandle.boundingBox();
  const dst = await targetBlock.boundingBox();
  expect(src && dst).toBeTruthy();
  await page.mouse.move(src!.x + src!.width / 2, src!.y + src!.height / 2);
  await page.mouse.down();
  await page.mouse.move(src!.x + src!.width / 2 + 12, src!.y + src!.height / 2 + 12, { steps: 6 });
  await page.mouse.move(dst!.x + dst!.width / 2, dst!.y + dst!.height / 2, { steps: 18 });
  await page.mouse.move(dst!.x + dst!.width / 2 + 4, dst!.y + dst!.height / 2 + 4, { steps: 4 });
  await page.mouse.up();
}

async function waitForDesignerDragToSettle(page: Page) {
  await expect(page.getByTestId('drag-overlay-ghost')).toHaveCount(0);
  await expect
    .poll(() => page.locator('[data-drop-intent]:not([data-drop-intent="none"])').count())
    .toBe(0);
}

async function clickDesignerToolbarButton(page: Page, testId: string) {
  const button = page.getByTestId(testId);
  await expect(button).toBeEnabled();
  await expect
    .poll(() => receivesPointerAtCenter(button))
    .toBe(true);
  await button.hover();
  await button.click();
}

async function receivesPointerAtCenter(locator: Locator): Promise<boolean> {
  return locator.evaluate((element) => {
    const rect = element.getBoundingClientRect();
    const target = document.elementFromPoint(
      rect.left + rect.width / 2,
      rect.top + rect.height / 2,
    );
    return target === element || element.contains(target);
  });
}

async function saveDesignerPage(page: Page, pid: string): Promise<void> {
  let lastError: unknown;
  for (let attempt = 0; attempt < 3; attempt += 1) {
    try {
      const saveButton = page.getByTestId('designer-save');
      await expect(saveButton).toBeEnabled();
      const saveResponsePromise = page.waitForResponse(
        (response) =>
          response.url().includes(`/api/pages/${pid}`) && response.request().method() === 'PUT',
        { timeout: 5000 },
      );
      await saveButton.click();
      const saveResponse = await saveResponsePromise;
      expect(saveResponse.ok(), await saveResponse.text()).toBe(true);
      const saveBody = await saveResponse.json();
      expect(saveBody.code).toBe('0');
      await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');
      return;
    } catch (error) {
      lastError = error;
    }
  }
  throw lastError instanceof Error ? lastError : new Error('Designer save did not complete.');
}

async function isBeforeInDom(
  container: Locator,
  beforeBlockId: string,
  afterBlockId: string,
) {
  return container
    .evaluate((containerNode, args) => {
      const beforeNode = containerNode.querySelector(`[data-testid="${args.beforeTestId}"]`);
      const afterNode = containerNode.querySelector(`[data-testid="${args.afterTestId}"]`);
      return Boolean(
        beforeNode &&
        afterNode &&
          (beforeNode.compareDocumentPosition(afterNode) & Node.DOCUMENT_POSITION_FOLLOWING),
      );
    }, {
      beforeTestId: `canvas-block-${beforeBlockId}`,
      afterTestId: `canvas-block-${afterBlockId}`,
    });
}

function findBlock(blocks: TestBlock[], blockId: string): TestBlock | null {
  for (const block of blocks) {
    if (block.id === blockId) return block;
    const child = block.blocks ? findBlock(block.blocks, blockId) : null;
    if (child) return child;
  }
  return null;
}
