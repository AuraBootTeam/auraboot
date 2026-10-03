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

const TABLE_PLACEHOLDER = /^(请在属性面板中配置列|Configure columns in the property panel)$/;
const RICHTEXT_PLACEHOLDER = /点击添加文本内容|Click to add text content/;

function canvas(page: Page) {
  return page.getByTestId('report-canvas');
}

// Initialization is asserted before actions; each block is added exactly once.
async function addPaletteBlock(page: Page, buttonName: RegExp, placeholder: string | RegExp) {
  const button = page.getByTestId('block-palette').getByRole('button', { name: buttonName });
  await expect(button).toBeEnabled();
  await button.click();
  await expect(canvas(page).getByText(placeholder)).toBeVisible();
}

async function addDataTable(page: Page) {
  await addPaletteBlock(page, /数据表格|Data Table/, TABLE_PLACEHOLDER);
}

async function addRichText(page: Page) {
  await addPaletteBlock(page, /富文本|Rich Text/, RICHTEXT_PLACEHOLDER);
}

test.describe('Report Designer — history & topology golden', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/report-designer', { waitUntil: 'load' });
    await expect(page.getByTestId('block-palette')).toBeVisible();
    await expect(canvas(page)).toBeVisible();
    await expect(
      page.getByTestId('report-designer-toolbar').getByText(/^(未保存|Unsaved)$/),
    ).toBeVisible();
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
    await expect(panel.getByText(/^(数据表格|Data Table)$/)).toBeVisible();

    await panel.getByTitle(/^(删除|Delete)$/).click();
    await expect(canvas(page).getByText(TABLE_PLACEHOLDER)).toHaveCount(0);
  });

  test('undo restores a deleted block', async ({ page }) => {
    await addDataTable(page);
    await canvas(page).getByText(TABLE_PLACEHOLDER).click();
    await page
      .getByTestId('block-property-panel')
      .getByTitle(/^(删除|Delete)$/)
      .click();
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
    await expect(panel.getByText(/^(富文本|Rich Text)$/)).toBeVisible();
    await panel.getByTitle(/^(上移|Move up)$/).click();

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
    await expect(panel.getByTitle(/^(上移|Move up)$/)).toBeDisabled();
  });
});
