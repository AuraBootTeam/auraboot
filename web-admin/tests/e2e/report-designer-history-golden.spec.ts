/**
 * Report Designer — History / Topology Golden
 *
 * Pins the report designer's CLIENT-SIDE interaction contract that the planned
 * B1 Phase 2 canvas-kernel swap (ReportCanvas -> unified CanvasHost) must
 * preserve:
 *   - undo / redo (keyboard) of block add + delete
 *   - delete a body block via the property panel
 *   - reorder body blocks via the property-panel Move up / Move down controls
 *
 * The existing report-designer-smoke spec covers load + add + select +
 * property panels; this golden adds the selection/history/topology behaviours
 * the swap rewires, so a future "behaviour-preserving" claim is testable.
 *
 * These interactions are all client-side (the designer manages document state
 * in-browser; the backend is only needed for auth + the page load), so no
 * persistence round-trip is asserted here.
 */
import { test, expect, type Page } from '@playwright/test';

const TABLE_PLACEHOLDER = 'Configure columns in the property panel';
const RICHTEXT_PLACEHOLDER = 'Click to add text content';

function canvas(page: Page) {
  return page.getByTestId('report-canvas');
}

async function addDataTable(page: Page) {
  await page.getByRole('button', { name: /Data Table/ }).click();
  await expect(canvas(page).getByText(TABLE_PLACEHOLDER)).toBeVisible({ timeout: 10000 });
}

async function addRichText(page: Page) {
  await page.getByRole('button', { name: /Rich Text/ }).click();
  await expect(canvas(page).getByText(RICHTEXT_PLACEHOLDER)).toBeVisible({ timeout: 10000 });
}

test.describe('Report Designer — history & topology golden', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/report-designer', { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('block-palette')).toBeVisible();
    await expect(canvas(page)).toBeVisible();
  });

  test('undo removes an added block; redo restores it', async ({ page }) => {
    await addDataTable(page);

    // Undo (Ctrl/Cmd+Z) — the block leaves the canvas.
    await page.keyboard.press('ControlOrMeta+z');
    await expect(canvas(page).getByText(TABLE_PLACEHOLDER)).toHaveCount(0);

    // Redo (Ctrl/Cmd+Y) — the block comes back.
    await page.keyboard.press('ControlOrMeta+y');
    await expect(canvas(page).getByText(TABLE_PLACEHOLDER)).toBeVisible();
  });

  test('delete a selected body block via the property panel', async ({ page }) => {
    await addDataTable(page);

    // Select the block so the property panel shows the block action bar.
    await canvas(page).getByText(TABLE_PLACEHOLDER).click();
    const panel = page.getByTestId('block-property-panel');
    await expect(panel.getByText('Data Table', { exact: true })).toBeVisible();

    await panel.getByTitle('Delete').click();
    await expect(canvas(page).getByText(TABLE_PLACEHOLDER)).toHaveCount(0);
  });

  test('undo restores a deleted block', async ({ page }) => {
    await addDataTable(page);
    await canvas(page).getByText(TABLE_PLACEHOLDER).click();
    await page.getByTestId('block-property-panel').getByTitle('Delete').click();
    await expect(canvas(page).getByText(TABLE_PLACEHOLDER)).toHaveCount(0);

    // Delete is an undoable step.
    await page.keyboard.press('ControlOrMeta+z');
    await expect(canvas(page).getByText(TABLE_PLACEHOLDER)).toBeVisible();
  });

  test('reorder body blocks with Move up', async ({ page }) => {
    // Initial document order: [data-table, rich-text].
    await addDataTable(page);
    await addRichText(page);

    const tableBefore = await canvas(page).getByText(TABLE_PLACEHOLDER).boundingBox();
    const richBefore = await canvas(page).getByText(RICHTEXT_PLACEHOLDER).boundingBox();
    expect(tableBefore && richBefore).toBeTruthy();
    // Sanity: data-table is above rich-text initially.
    expect(tableBefore!.y).toBeLessThan(richBefore!.y);

    // Select rich-text and move it up — order becomes [rich-text, data-table].
    await canvas(page).getByText(RICHTEXT_PLACEHOLDER).click();
    const panel = page.getByTestId('block-property-panel');
    await expect(panel.getByText('Rich Text', { exact: true })).toBeVisible();
    await panel.getByTitle('Move up').click();

    await expect
      .poll(async () => {
        const t = await canvas(page).getByText(TABLE_PLACEHOLDER).boundingBox();
        const r = await canvas(page).getByText(RICHTEXT_PLACEHOLDER).boundingBox();
        if (!t || !r) return 'missing';
        return r.y < t.y ? 'rich-above-table' : 'table-above-rich';
      })
      .toBe('rich-above-table');
  });

  test('Move up is disabled for the first block', async ({ page }) => {
    await addDataTable(page);
    await canvas(page).getByText(TABLE_PLACEHOLDER).click();
    const panel = page.getByTestId('block-property-panel');
    // The single (first) block cannot move up.
    await expect(panel.getByTitle('Move up')).toBeDisabled();
  });
});
