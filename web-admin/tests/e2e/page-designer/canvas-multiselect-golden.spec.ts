/**
 * Unified Designer — canvas multi-select + batch-delete golden coverage.
 *
 * Feature: shift / cmd / ctrl + click on the canvas builds an additive
 * multi-selection (independent of the inspector / drop-context primary
 * selection); a batch bar (≥2 selected) exposes a batch delete and a clear
 * action. Box-select (geometric marquee) is intentionally out of scope for
 * this slice (deferred follow-up).
 *
 * Real-stack golden, mirroring tests/e2e/page-designer/inspector-authoring-golden.spec.ts:
 *   seed a detail page via POST /api/pages with STABLE block ids (3 deletable
 *   same-level detail-sections under an undeletable detail root) ->
 *   open /unified-designer?pageId=<pid> ->
 *   exercise selection / batch delete on the real canvas ->
 *   designer-save (PUT round-trip) + GET /api/pages/<pid> readback to prove the
 *   delete is persisted, not just a green UI -> undo restores.
 *
 * data-testids verified against the live source:
 *   - canvas block:       canvas-block-<id>           (CanvasHost.tsx)
 *   - primary selection:  data-selected               (CanvasHost.tsx)
 *   - additive selection: data-multi-selected          (CanvasHost.tsx)
 *   - batch bar:          multi-select-bar / -count / -delete / -clear (UDW)
 *
 * @since 4.0.0
 */

import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import { uniqueId } from '../helpers';

// ab_announcement is a published platform meta-model present in every OSS stack.
// The detail-section blocks under test do not bind model fields, so the model
// only has to satisfy the detail-page contract (a real, published modelCode for
// the root detail block).
const MODEL_CODE = 'ab_announcement';

const ROOT_BLOCK = 'detail_root';
const SECTION_A = 'pd_ms_section_a';
const SECTION_B = 'pd_ms_section_b';
const SECTION_C = 'pd_ms_section_c';

interface DslBlock {
  id?: string;
  blockType?: string;
  title?: unknown;
  props?: Record<string, unknown>;
  layout?: Record<string, unknown>;
  dataSource?: Record<string, unknown>;
  blocks?: DslBlock[];
}

interface PageSchemaDto {
  pid: string;
  pageKey: string;
  kind?: string;
  blocks?: DslBlock[];
}

function findBlockById(blocks: DslBlock[] | undefined, id: string): DslBlock | null {
  for (const block of blocks ?? []) {
    if (block.id === id) return block;
    const nested = findBlockById(block.blocks, id);
    if (nested) return nested;
  }
  return null;
}

async function readPage(page: Page, pid: string): Promise<PageSchemaDto> {
  const resp = await page.request.get(`/api/pages/${pid}`);
  expect(resp.ok(), `GET /api/pages/${pid} failed: ${resp.status()}`).toBeTruthy();
  const body = await resp.json();
  expect(body.code, 'read page API code').toBe('0');
  return body.data as PageSchemaDto;
}

async function openDesigner(page: Page, pid: string): Promise<void> {
  await page.goto(`/unified-designer?pageId=${pid}`, { waitUntil: 'domcontentloaded' });
  await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 30_000 });
  await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存', { timeout: 15_000 });
}

/**
 * Save and wait for the real PUT to land (mirrors saveDesigner in the inspector
 * authoring golden). The save button is disabled while clean/saving/invalid.
 */
async function saveDesigner(page: Page, pid: string): Promise<void> {
  const saveButton = page.getByTestId('designer-save');
  await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');
  await expect(saveButton).toBeEnabled({ timeout: 10_000 });
  await expect(async () => {
    const saveResp = page.waitForResponse(
      (r) => r.url().includes(`/api/pages/${pid}`) && r.request().method() === 'PUT',
      { timeout: 5_000 },
    );
    await saveButton.click();
    const resp = await saveResp;
    expect(resp.status()).toBe(200);
    const body = await resp.json();
    expect(body.code).toBe('0');
  }).toPass({ timeout: 30_000 });
  await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');
}

/** Click a canvas block with an optional additive modifier (shift/meta/ctrl). */
async function clickBlock(
  page: Page,
  blockId: string,
  modifiers?: Array<'Shift' | 'Meta' | 'Control'>,
): Promise<void> {
  // Click the header band ~14px down, but well clear of the left-edge drag
  // handle (~x<32, stopsPropagation) and the right-edge delete button. x=100
  // lands on the block title text, which routes the click to the section frame
  // (selection), not a child block or an interactive control.
  await page.getByTestId(`canvas-block-${blockId}`).click({
    modifiers,
    position: { x: 100, y: 14 },
  });
}

async function shot(page: Page, name: string): Promise<void> {
  await page.screenshot({ path: `test-results/canvas-multiselect-${name}.png`, fullPage: true });
}

/**
 * Seed a fresh detail page (3 sibling deletable detail-sections under an
 * undeletable detail root) and return its pid. Each test seeds its own page so
 * the persisted batch-delete in one test never bleeds into another (the specs
 * are independent, no shared mutable backend state).
 */
async function seedPage(page: Page, label: string): Promise<string> {
  const uid = uniqueId('pdms');
  const resp = await page.request.post('/api/pages', {
    data: {
      name: `Multi-select golden ${label} ${uid}`,
      pageKey: `pd_ms_${label}_${uid}`.replace(/-/g, '_'),
      title: `Multi-select golden ${label} ${uid}`,
      kind: 'detail',
      modelCode: MODEL_CODE,
      // The unified designer loads/saves a V3 document; its client validator
      // requires schemaVersion 3 (validatePageSchemaV3). A schemaVersion 4 seed
      // loads but fails client save validation, so the PUT never fires.
      schemaVersion: 3,
      blocks: [
        {
          id: ROOT_BLOCK,
          blockType: 'detail',
          title: 'Multi-select root',
          dataSource: { model: MODEL_CODE },
          layout: { span: 12 },
          // Three sibling detail-sections under the detail root. The root is
          // path.length 1 (undeletable); each section is path.length 2
          // (deletable). detail-section is an allowed direct child of detail
          // (BlockRegistry.allowedChildren) so the client validator accepts it.
          blocks: [
            { id: SECTION_A, blockType: 'detail-section', title: 'Section A', layout: { columns: 12 }, blocks: [] },
            { id: SECTION_B, blockType: 'detail-section', title: 'Section B', layout: { columns: 12 }, blocks: [] },
            { id: SECTION_C, blockType: 'detail-section', title: 'Section C', layout: { columns: 12 }, blocks: [] },
          ],
        },
      ],
      extension: { e2e: true, scenario: 'canvas-multiselect-golden' },
    },
  });
  expect(resp.ok(), `seed page failed: ${resp.status()} ${await resp.text()}`).toBeTruthy();
  const body = await resp.json();
  expect(body.code, 'seed page API code').toBe('0');
  const pid = String(body.data?.pid ?? '');
  expect(pid, 'seeded pid').toBeTruthy();
  return pid;
}

test.describe('Unified Designer canvas multi-select + batch delete golden', () => {
  test.describe.configure({ timeout: 90_000 });

  test('happy: shift/cmd-click builds a 3-block multi-selection then batch delete persists + undo restores', async ({
    page,
  }) => {
    const pid = await seedPage(page, 'happy');
    await openDesigner(page, pid);

    // Sanity: all three sections render on the canvas.
    await expect(page.getByTestId(`canvas-block-${SECTION_A}`)).toBeVisible();
    await expect(page.getByTestId(`canvas-block-${SECTION_B}`)).toBeVisible();
    await expect(page.getByTestId(`canvas-block-${SECTION_C}`)).toBeVisible();
    await shot(page, '01-seeded');

    // Single-click A: primary selection, no batch bar yet.
    await clickBlock(page, SECTION_A);
    await expect(page.getByTestId(`canvas-block-${SECTION_A}`)).toHaveAttribute('data-selected', 'true');
    await expect(page.getByTestId('multi-select-bar')).toHaveCount(0);

    // shift+click B, cmd+click C -> additive selection of A, B, C.
    await clickBlock(page, SECTION_B, ['Shift']);
    await clickBlock(page, SECTION_C, ['Meta']);

    await expect(page.getByTestId('multi-select-bar')).toBeVisible();
    await expect(page.getByTestId('multi-select-count')).toHaveText('已选 3 项');
    await expect(page.getByTestId(`canvas-block-${SECTION_A}`)).toHaveAttribute('data-multi-selected', 'true');
    await expect(page.getByTestId(`canvas-block-${SECTION_B}`)).toHaveAttribute('data-multi-selected', 'true');
    await expect(page.getByTestId(`canvas-block-${SECTION_C}`)).toHaveAttribute('data-multi-selected', 'true');
    await shot(page, '02-three-selected');

    // Batch delete -> all three vanish from the canvas, bar disappears.
    await page.getByTestId('multi-select-delete').click();
    await expect(page.getByTestId(`canvas-block-${SECTION_A}`)).toHaveCount(0);
    await expect(page.getByTestId(`canvas-block-${SECTION_B}`)).toHaveCount(0);
    await expect(page.getByTestId(`canvas-block-${SECTION_C}`)).toHaveCount(0);
    await expect(page.getByTestId('multi-select-bar')).toHaveCount(0);
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('未保存');
    await shot(page, '03-after-delete');

    // Save and prove the delete persisted via a GET readback.
    await saveDesigner(page, pid);
    const persisted = await readPage(page, pid);
    expect(findBlockById(persisted.blocks, SECTION_A), 'A removed from persisted schema').toBeNull();
    expect(findBlockById(persisted.blocks, SECTION_B), 'B removed from persisted schema').toBeNull();
    expect(findBlockById(persisted.blocks, SECTION_C), 'C removed from persisted schema').toBeNull();
    expect(findBlockById(persisted.blocks, ROOT_BLOCK), 'root container preserved').not.toBeNull();

    // Undo restores all three in a single step (one history entry).
    await page.getByTestId('designer-undo').click();
    await expect(page.getByTestId(`canvas-block-${SECTION_A}`)).toBeVisible();
    await expect(page.getByTestId(`canvas-block-${SECTION_B}`)).toBeVisible();
    await expect(page.getByTestId(`canvas-block-${SECTION_C}`)).toBeVisible();
    await shot(page, '04-after-undo');
  });

  test('edge: plain click collapses multi-selection; re-modifier-click deselects a block', async ({
    page,
  }) => {
    const pid = await seedPage(page, 'edge1');
    await openDesigner(page, pid);

    // Build a 3-block selection.
    await clickBlock(page, SECTION_A);
    await clickBlock(page, SECTION_B, ['Shift']);
    await clickBlock(page, SECTION_C, ['Shift']);
    await expect(page.getByTestId('multi-select-count')).toHaveText('已选 3 项');

    // shift+click an already-selected block (C) toggles it back out -> 2 left.
    await clickBlock(page, SECTION_C, ['Shift']);
    await expect(page.getByTestId('multi-select-count')).toHaveText('已选 2 项');
    await expect(page.getByTestId(`canvas-block-${SECTION_C}`)).toHaveAttribute('data-multi-selected', 'false');
    await shot(page, '05-deselect-one');

    // A plain (no-modifier) click collapses to single-select; bar disappears.
    await clickBlock(page, SECTION_C);
    await expect(page.getByTestId('multi-select-bar')).toHaveCount(0);
    await expect(page.getByTestId(`canvas-block-${SECTION_A}`)).toHaveAttribute('data-multi-selected', 'false');
    await expect(page.getByTestId(`canvas-block-${SECTION_B}`)).toHaveAttribute('data-multi-selected', 'false');
    await expect(page.getByTestId(`canvas-block-${SECTION_C}`)).toHaveAttribute('data-selected', 'true');
    await shot(page, '06-collapsed-single');
  });

  test('edge: batch delete with the undeletable root in the selection removes only deletable blocks', async ({
    page,
  }) => {
    const pid = await seedPage(page, 'edge2');
    await openDesigner(page, pid);

    await expect(page.getByTestId(`canvas-block-${SECTION_A}`)).toBeVisible();
    await expect(page.getByTestId(`canvas-block-${SECTION_B}`)).toBeVisible();

    // Select the undeletable detail root + a deletable section.
    await clickBlock(page, ROOT_BLOCK);
    await clickBlock(page, SECTION_A, ['Shift']);
    await expect(page.getByTestId('multi-select-count')).toHaveText('已选 2 项');
    await shot(page, '07-root-plus-section');

    await page.getByTestId('multi-select-delete').click();

    // Only the deletable section is removed; the root container survives.
    await expect(page.getByTestId(`canvas-block-${SECTION_A}`)).toHaveCount(0);
    await expect(page.getByTestId(`canvas-block-${ROOT_BLOCK}`)).toBeVisible();
    await expect(page.getByTestId('multi-select-bar')).toHaveCount(0);
    await shot(page, '08-root-survived');

    // Persist and confirm only A was deleted.
    await saveDesigner(page, pid);
    const persisted = await readPage(page, pid);
    expect(findBlockById(persisted.blocks, SECTION_A), 'A deleted').toBeNull();
    expect(findBlockById(persisted.blocks, ROOT_BLOCK), 'root kept').not.toBeNull();
    expect(findBlockById(persisted.blocks, SECTION_B), 'B kept (not selected)').not.toBeNull();
  });

  test('sad: clear button drops the selection without deleting any block', async ({ page }) => {
    const pid = await seedPage(page, 'sad');
    await openDesigner(page, pid);

    await expect(page.getByTestId(`canvas-block-${SECTION_B}`)).toBeVisible();
    await expect(page.getByTestId(`canvas-block-${SECTION_C}`)).toBeVisible();

    await clickBlock(page, SECTION_B);
    await clickBlock(page, SECTION_C, ['Shift']);
    await expect(page.getByTestId('multi-select-bar')).toBeVisible();
    await expect(page.getByTestId('multi-select-count')).toHaveText('已选 2 项');

    await page.getByTestId('multi-select-clear').click();

    await expect(page.getByTestId('multi-select-bar')).toHaveCount(0);
    // Both blocks remain on the canvas and nothing dirtied the document.
    await expect(page.getByTestId(`canvas-block-${SECTION_B}`)).toBeVisible();
    await expect(page.getByTestId(`canvas-block-${SECTION_C}`)).toBeVisible();
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');
    await shot(page, '09-cleared');
  });
});
