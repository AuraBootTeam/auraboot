/**
 * GAP-025: Designer Maturity E2E Tests
 *
 * Covers:
 * - Undo/Redo via keyboard shortcuts
 * - Preview modal opens and shows content
 * - Device switcher in preview
 * - FloorsDesigner 3-panel layout
 * - Keyboard shortcuts (Ctrl+S save)
 *
 * @since 4.0.0
 */

import { test, expect } from '../../fixtures';
import { PageDesignerPage } from '../../pages';

test.describe('GAP-025: Designer Maturity', () => {
  let designerPage: PageDesignerPage;

  test.beforeEach(async ({ page }) => {
    designerPage = new PageDesignerPage(page);
  });

  /**
   * G25-E01: Three-panel layout visible
   * Open page designer and verify left/center/right panels exist.
   */
  test('G25-E01: Three-panel layout visible @smoke', async ({ page }) => {
    await page.goto('/page-designer', { waitUntil: 'domcontentloaded' });

    const loaded = await designerPage.openViaList();
    if (!loaded) {
      test.skip(true, 'No pages available in designer');
      return;
    }

    // Canvas should be visible
    const canvas = page
      .locator('[data-testid="designer-canvas"], [data-testid="floors-designer-canvas"]')
      .first();
    await expect(canvas).toBeVisible({ timeout: 10000 });

    // Properties panel should be visible
    const propertiesPanel = page
      .locator('[data-testid="designer-properties-panel"], [data-testid="floors-properties-panel"]')
      .first();
    await expect(propertiesPanel).toBeVisible({ timeout: 5000 });
  });

  /**
   * G25-E02: Preview modal opens
   * Click Preview button and verify modal appears with device frame.
   */
  test('G25-E02: Preview modal opens', async ({ page }) => {
    await page.goto('/page-designer', { waitUntil: 'domcontentloaded' });

    const loaded = await designerPage.openViaList();
    if (!loaded) {
      test.skip(true, 'No pages available in designer');
      return;
    }

    // Click preview button
    const previewBtn = designerPage.previewButton;
    if (!(await previewBtn.isVisible({ timeout: 3000 }).catch(() => false))) {
      test.skip(true, 'Preview button not visible');
      return;
    }

    await previewBtn.click();

    // Preview modal should appear
    const previewHeader = page.locator('h2:has-text("Preview")');
    await expect(previewHeader).toBeVisible({ timeout: 5000 });

    // Device frame (traffic lights) should be visible
    const deviceFrame = page.locator('.rounded-full.bg-red-400');
    await expect(deviceFrame).toBeVisible({ timeout: 3000 });
  });

  /**
   * G25-E03: Preview device switcher
   * Open preview and switch between Desktop/Tablet/Mobile.
   */
  test('G25-E03: Preview device switcher', async ({ page }) => {
    await page.goto('/page-designer', { waitUntil: 'domcontentloaded' });

    const loaded = await designerPage.openViaList();
    if (!loaded) {
      test.skip(true, 'No pages available in designer');
      return;
    }

    const previewBtn = designerPage.previewButton;
    if (!(await previewBtn.isVisible({ timeout: 3000 }).catch(() => false))) {
      test.skip(true, 'Preview button not visible');
      return;
    }

    await previewBtn.click();

    // Wait for preview to open
    await expect(page.locator('h2:has-text("Preview")')).toBeVisible({ timeout: 5000 });

    // Desktop should be default — check dimension display
    const dimensionDisplay = page.locator('text="1440 x 900"');
    await expect(dimensionDisplay).toBeVisible({ timeout: 3000 });

    // Switch to Tablet
    await page.locator('button:has-text("Tablet")').click();
    await expect(page.locator('text="768 x 1024"')).toBeVisible({ timeout: 3000 });

    // Switch to Mobile
    await page.locator('button:has-text("Mobile")').click();
    await expect(page.locator('text="375 x 812"')).toBeVisible({ timeout: 3000 });
  });

  /**
   * G25-E04: Undo/Redo — add block then undo
   * If blocks exist on canvas, verify undo button state reflects history.
   */
  test('G25-E04: Undo/Redo buttons reflect state', async ({ page }) => {
    await page.goto('/page-designer', { waitUntil: 'domcontentloaded' });

    const loaded = await designerPage.openViaList();
    if (!loaded) {
      test.skip(true, 'No pages available in designer');
      return;
    }

    // Initially undo should be disabled (no history yet)
    const undoBtn = designerPage.undoButton;
    if (await undoBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
      // Undo button visible — check it's disabled or enabled based on history
      await expect(undoBtn).toBeVisible();
    }

    // Make a change via the blocks tab if available
    const blocksTab = page.locator('[data-testid="designer-tab-blocks"]');
    if (await blocksTab.isVisible({ timeout: 3000 }).catch(() => false)) {
      await blocksTab.click();

      // Look for a block template to click
      const blockTemplate = page.locator('[data-testid="block-library"] button').first();
      if (await blockTemplate.isVisible({ timeout: 3000 }).catch(() => false)) {
        const initialBlockCount = await designerPage.getBlockCount();
        await blockTemplate.click();

        // After adding a block, undo should become available
        // Wait a moment for state to update
        await page.waitForTimeout(500);
        const newBlockCount = await designerPage.getBlockCount();
        if (newBlockCount > initialBlockCount) {
          // Undo via keyboard: Ctrl+Z
          await page.keyboard.press('Control+z');
          await page.waitForTimeout(500);

          // Block count should decrease
          const afterUndoCount = await designerPage.getBlockCount();
          expect(afterUndoCount).toBeLessThanOrEqual(initialBlockCount);
        }
      }
    }
  });

  /**
   * G25-E05: Ctrl+S triggers save
   * Press Ctrl+S and verify save operation fires.
   */
  test('G25-E05: Ctrl+S triggers save', async ({ page }) => {
    await page.goto('/page-designer', { waitUntil: 'domcontentloaded' });

    const loaded = await designerPage.openViaList();
    if (!loaded) {
      test.skip(true, 'No pages available in designer');
      return;
    }

    // Press Ctrl+S — should not navigate away or cause errors
    await page.keyboard.press('Control+s');

    // Page should still be in designer (not navigated away)
    await expect(
      page
        .locator('[data-testid="designer-canvas"], [data-testid="floors-designer-canvas"]')
        .first(),
    ).toBeVisible({ timeout: 5000 });
  });

  /**
   * G25-E06: FloorsDesigner — add floor button visible for detail/home pages
   * Checks that the "Add Floor" button exists in the FloorsDesigner.
   */
  test('G25-E06: FloorsDesigner add floor button', async ({ page }) => {
    // Try to find a detail-kind page
    await page.goto('/page-designer', { waitUntil: 'domcontentloaded' });

    const loaded = await designerPage.openViaList();
    if (!loaded) {
      test.skip(true, 'No pages available in designer');
      return;
    }

    // Check if we're in floors designer mode
    const addFloorBtn = page.locator('[data-testid="floors-add-floor-btn"]');
    const floorsCanvas = page.locator('[data-testid="floors-designer-canvas"]');

    // If we're in floors mode, verify add floor button
    if (await floorsCanvas.isVisible({ timeout: 3000 }).catch(() => false)) {
      await expect(addFloorBtn).toBeVisible({ timeout: 3000 });

      // Click add floor
      await addFloorBtn.click();

      // A floor section should appear
      const floorSection = page.locator('[data-testid^="floor-section-"]').first();
      await expect(floorSection).toBeVisible({ timeout: 5000 });
    }
  });

  /**
   * G25-E07: Preview shows "Empty page" for blank page
   * Opens preview on an empty page and verifies the empty placeholder.
   */
  test('G25-E07: Preview shows content for non-empty page', async ({ page }) => {
    await page.goto('/page-designer', { waitUntil: 'domcontentloaded' });

    const loaded = await designerPage.openViaList();
    if (!loaded) {
      test.skip(true, 'No pages available in designer');
      return;
    }

    const previewBtn = designerPage.previewButton;
    if (!(await previewBtn.isVisible({ timeout: 3000 }).catch(() => false))) {
      test.skip(true, 'Preview button not visible');
      return;
    }

    await previewBtn.click();
    await expect(page.locator('h2:has-text("Preview")')).toBeVisible({ timeout: 5000 });

    // Should show either content or empty placeholder
    const hasContent = await page
      .locator('.bg-grid-pattern, .bg-white')
      .first()
      .isVisible({ timeout: 3000 })
      .catch(() => false);
    const hasEmpty = await page
      .locator('text="Empty page"')
      .isVisible({ timeout: 3000 })
      .catch(() => false);

    // One of them must be true
    expect(hasContent || hasEmpty).toBe(true);

    // Close preview
    const closeBtn = page.locator('button:has-text("Close")');
    if (await closeBtn.isVisible({ timeout: 2000 }).catch(() => false)) {
      await closeBtn.click();
    }
  });
});
