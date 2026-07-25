/**
 * Unified Designer drop-collision regression (guards the #1479 fix in
 * `dnd/dndShared.ts` `buildDesignerCollisionCandidates`).
 *
 * The bug: for a container TALLER than the viewport, `closestCenter` (a pure
 * proximity guess that never checks containment) nominates a small descendant
 * whose center happens to be near the pointer. Pre-fix, that proximity guess was
 * POOLED with the real `pointerWithin` containment hits and the whole set ranked
 * by smallest rect area, so the descendant won on area even though the pointer
 * was NOT inside it. A drop aimed at the tall container therefore resolved to a
 * descendant that REJECTS the block -> silent no-op. The fix ranks containment
 * above proximity (proximity is only consulted when there are zero containment
 * hits).
 *
 * The discriminating scenario built here (measured: form 3773px tall in a 720px
 * viewport; the form's left gutter is at form.x + 6, its children inset ~13px):
 *   form (taller than the viewport, ACCEPTS an `ai-fill-banner`)
 *     └─ form-section (taller than the viewport, does NOT accept `ai-fill-banner`)
 *          └─ 28 field rows (leaf, do NOT accept `ai-fill-banner`)
 *
 * The dragged block is `ai-fill-banner`: it is in the FORM's `allowedChildren`
 * but NOT the form-section's, and (unlike `divider`) it has a `createBlockTemplate`
 * entry, so the drop actually creates a block — a block type without a template
 * no-ops on BOTH rankings and cannot discriminate.
 *
 * The drop aims at the form's own left gutter ~200px down (inside the FORM, not
 * inside any field, and clear of @dnd-kit's ~25% top/bottom auto-scroll bands).
 * At that point:
 *   - pointerWithin  -> [form, root]   (the pointer's true aim: the form)
 *   - closestCenter  -> a field row    (nearest center; the section/form centers
 *                                        are ~700px away because they are tall)
 *
 * Outcome, provably different between the two rankings (verified: FIXED resolves
 * `over` = form_root with intent `inside`; PRE-FIX resolves `over` = a field with
 * no accepting intent):
 *   - FIXED  : `over` = the form -> the banner is added as a child of the FORM.
 *   - PRE-FIX: `over` = the field -> `canAddBlockBeforeTarget(field, banner)` is
 *              false (the field's parent, the form-section, cannot contain the
 *              banner) and `canAddBlockToParent(field, banner)` is false (a field
 *              is a leaf) -> the drag resolves to null -> NOTHING is added.
 *
 * The test asserts the OUTCOME (a banner block appears, nested directly under the
 * form and NOT inside the section), so reverting `buildDesignerCollisionCandidates`
 * to its pre-fix pooled+area-ranked form makes it go red on the same assertion.
 * Confirmed by mutation: FIXED 5/5 pass, PRE-FIX 5/5 fail on `toHaveLength(1)`.
 *
 * Why the existing palette-drop tests (UDW-040/041/042/047) do NOT discriminate:
 * at their drop points the proximity-elected candidate is a DIRECT CHILD of the
 * container, and a sibling-insert before a direct child is legitimately accepted,
 * so the block lands either way. Here the proximity-elected candidate is a
 * GRANDCHILD (field) whose direct parent (section) rejects the block, which is
 * exactly what makes fixed vs pre-fix observable.
 */

import { expect, test } from '../../fixtures';
import type { Locator, Page } from '@playwright/test';
import { uniqueId } from '../helpers';

const ADMIN_STORAGE_STATE =
  process.env.PW_ADMIN_STORAGE_STATE ||
  (process.env.PW_STORAGE_DIR
    ? `${process.env.PW_STORAGE_DIR}/admin.json`
    : './tests/storage/admin.json');

// Enough full-width field rows to push the form (and the section) well past the
// 720px viewport, so the container centers sit hundreds of px from the pointer
// and closestCenter is forced to elect a small near-the-pointer descendant.
const FIELD_COUNT = 28;

interface CreatedPage {
  pid: string;
}

async function collectCanvasBlockTestIds(page: Page): Promise<string[]> {
  return page
    .locator('[data-testid^="canvas-block-"]')
    .evaluateAll((els) => els.map((el) => el.getAttribute('data-testid') ?? '').filter(Boolean));
}

/** Switch the resource panel tab and confirm the switch took effect. */
async function switchResourceTab(page: Page, tab: 'outline' | 'blocks' | 'fields'): Promise<void> {
  const button = page.getByTestId(`resource-tab-${tab}`);
  await expect(async () => {
    await button.click();
    await expect(button).toHaveAttribute('data-active', 'true', { timeout: 1000 });
  }).toPass({ timeout: 10000 });
}

/**
 * Drag a palette block into the tall form's own left gutter, ~200px below its
 * top. Uses a real multi-step pointer gesture (so @dnd-kit runs its async
 * droppable measurement + per-move collision detection like a human drag) and
 * confirms the drag actually STARTED (overlay ghost) before completing it, so a
 * lost pointer-activation is retried while a genuinely-resolved drop is not.
 */
async function dragPaletteBlockIntoTallForm(
  page: Page,
  source: Locator,
  form: Locator,
): Promise<void> {
  await expect(async () => {
    // Pin the canvas scroll to the top so the form's top is visible and the drop
    // point stays fixed through the whole gesture. `scrollIntoViewIfNeeded` on a
    // form far taller than the pane re-aligns it mid-gesture (and @dnd-kit's
    // auto-scroll would then slide it out from under a stationary pointer); a
    // pinned scrollTop=0 avoids both.
    await page.getByTestId('unified-canvas-host').evaluate((el) => {
      (el as HTMLElement).scrollTop = 0;
    });
    await source.scrollIntoViewIfNeeded();
    await page.evaluate(
      () => new Promise<void>((resolve) => requestAnimationFrame(() => resolve())),
    );
    const src = await source.boundingBox();
    const dst = await form.boundingBox();
    if (!src || !dst) {
      throw new Error('dragPaletteBlockIntoTallForm: source or form has no bounding box');
    }

    const sx = src.x + src.width / 2;
    const sy = src.y + src.height / 2;
    // x = form.x + 6: the form's left gutter (children inset by ~13px), so the
    //   pointer is inside the FORM but inside no field.
    // y = form.y + 200: level with a field row (so closestCenter elects a field)
    //   and clear of the canvas pane's ~25% top/bottom auto-scroll bands.
    const tx = dst.x + 6;
    const ty = dst.y + 200;

    await page.mouse.move(sx, sy);
    await page.mouse.down();
    await page.mouse.move(sx + 10, sy + 10, { steps: 6 });
    // Confirm the drag activated; a lost activation makes the whole gesture a
    // no-op and is worth retrying (unlike a resolved drop, which we never retry).
    await expect(page.getByTestId('drag-overlay-ghost')).toBeVisible({ timeout: 2000 });
    await page.mouse.move(tx, ty, { steps: 20 });
    await page.mouse.move(tx + 2, ty + 2, { steps: 4 });
    await page.mouse.up();
    await page
      .locator('[data-testid="drag-overlay-ghost"]')
      .waitFor({ state: 'detached', timeout: 5000 })
      .catch(() => {});
    await page.evaluate(
      () =>
        new Promise<void>((resolve) =>
          requestAnimationFrame(() => requestAnimationFrame(() => resolve())),
        ),
    );
  }).toPass({ timeout: 20000 });
}

test.describe.serial('Designer drop-collision regression (#1479)', () => {
  test.describe.configure({ timeout: 60_000 });

  const uid = uniqueId('dnd_collision');
  const pageKey = `dnd_collision_form_${uid}`;
  const title = `DnD Collision Form ${uid}`;
  const modelCode = 'page_schema';
  let formPage: CreatedPage = { pid: '' };

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: ADMIN_STORAGE_STATE });
    const page = await ctx.newPage();

    const fieldBlocks = Array.from({ length: FIELD_COUNT }, (_, index) => ({
      id: `filler_field_${index}`,
      blockType: 'field',
      field: `filler_${index}`,
      layout: { span: 12 },
      props: { label: `Filler field ${index}`, component: 'input' },
    }));

    const resp = await page.request.post('/api/pages', {
      data: {
        name: title,
        pageKey,
        title,
        kind: 'form',
        modelCode,
        schemaVersion: 3,
        blocks: [
          {
            id: 'form_root',
            blockType: 'form',
            title: 'DnD Collision Form',
            dataSource: { model: modelCode },
            layout: { span: 12 },
            blocks: [
              {
                id: 'tall_section',
                blockType: 'form-section',
                title: 'Tall Section',
                layout: { columns: 12 },
                blocks: fieldBlocks,
              },
            ],
          },
        ],
        extension: { e2e: true, scenario: 'designer-dnd-collision-regression' },
      },
    });
    expect(resp.ok(), await resp.text()).toBe(true);
    const body = await resp.json();
    expect(body.code).toBe('0');
    formPage.pid = String(body.data?.pid ?? '');
    expect(formPage.pid).toBeTruthy();

    await page.close();
    await ctx.close();
  });

  test('DND-COL-01: a drop aimed at a viewport-taller form lands in the form, not on a rejecting descendant', async ({
    page,
  }) => {
    expect(formPage.pid).toBeTruthy();

    await page.goto(`/unified-designer?pageId=${formPage.pid}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('designer-dirty-state')).toHaveText('已保存');

    // Sanity: the seeded structure rendered (form -> tall section -> 28 fields).
    await expect(page.getByTestId('canvas-block-form_root')).toBeVisible();
    await expect(page.getByTestId('canvas-block-tall_section')).toBeVisible();
    await expect(page.locator('[data-testid^="canvas-block-filler_field_"]')).toHaveCount(FIELD_COUNT);

    // Select the form root so the `ai-fill-banner` palette item is draggable (it
    // is only enabled when the current selection can contain the block type). The outline
    // tab is the default; the palette lives in the blocks tab, so select first,
    // then switch tabs (the selection persists across the tab switch).
    await page.getByTestId('outline-item-form_root').click();
    await expect(page.getByTestId('inspector-selected-id')).toContainText('form_root');
    await switchResourceTab(page, 'blocks');

    const paletteItem = page.getByTestId('palette-add-ai-fill-banner');
    await expect(paletteItem).toBeVisible();
    await expect(paletteItem).toBeEnabled();

    // The form must actually be taller than the viewport for the bug to exist
    // (its center has to be far from the pointer). Guard the premise.
    const formBox = await page.getByTestId('canvas-block-form_root').boundingBox();
    expect(formBox, 'form must render with a bounding box').not.toBeNull();
    expect(
      formBox!.height,
      'the seeded form must be taller than the 720px viewport for this regression to apply',
    ).toBeGreaterThan(720);

    const idsBefore = await collectCanvasBlockTestIds(page);

    await dragPaletteBlockIntoTallForm(
      page,
      paletteItem,
      page.getByTestId('canvas-block-form_root'),
    );

    const idsAfter = await collectCanvasBlockTestIds(page);
    const added = idsAfter.filter((id) => !idsBefore.includes(id));

    // === THE DISCRIMINATING ASSERTION (flips fixed<->pre-fix) ===
    // FIXED: containment ranks the form first -> the banner is added to the form.
    // PRE-FIX: proximity elects a field, whose parent section rejects the banner
    //          and which cannot contain it either -> nothing is added at all.
    expect(
      added,
      'An ai-fill-banner dropped into a viewport-taller form must be added to the form. ' +
        'Pre-fix ranking elects a proximity descendant (a field) whose parent ' +
        'section rejects the banner, so the drop silently no-ops and nothing is added.',
    ).toHaveLength(1);

    // The added block is the dragged ai-fill-banner ...
    const addedId = added[0];
    expect(addedId).toMatch(/^canvas-block-ai_fill_banner_/);

    // ... and it landed under the FORM (the container the pointer was inside),
    // NOT nested inside the section (which the pre-fix proximity guess targeted).
    const addedTestId = addedId.replace(/^canvas-block-/, '');
    const insideForm = await page
      .getByTestId('canvas-block-form_root')
      .locator(`[data-testid="canvas-block-${addedTestId}"]`)
      .count();
    const insideSection = await page
      .getByTestId('canvas-block-tall_section')
      .locator(`[data-testid="canvas-block-${addedTestId}"]`)
      .count();
    expect(insideForm, 'the banner must be nested under the form').toBe(1);
    expect(insideSection, 'the banner must NOT be nested inside the rejecting section').toBe(0);
  });
});
