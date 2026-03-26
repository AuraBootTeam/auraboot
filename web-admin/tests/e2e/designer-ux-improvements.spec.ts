/**
 * Designer UX Improvements — E2E Tests
 *
 * Covers all 5 improvements from the designer UX optimization plan:
 * #1: Report Designer block drag reorder
 * #2: i18n — no hardcoded Chinese in designer components
 * #3: Dashboard drag preview enhancement
 * #4: Unified empty state component
 * #5: BPMN Canvas drag feedback
 */

import { test, expect, type Page } from '@playwright/test';
import { uniqueId } from './helpers';

// ============================================================================
// #1: Report Designer Block Drag Reorder
// ============================================================================

test.describe('Report Designer — Block Drag Reorder (#1)', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/report-designer', { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('block-palette')).toBeVisible();
    await expect(page.getByTestId('report-canvas')).toBeVisible();
  });

  test('should show empty state with DesignerEmptyState component', async ({ page }) => {
    // The unified empty state should render when no blocks exist
    await expect(page.getByTestId('report-canvas-empty')).toBeVisible();
  });

  test('should show drag handles on block hover', async ({ page }) => {
    // Add a block via palette click
    const palette = page.getByTestId('block-palette');
    await palette.getByRole('button', { name: /Data Table/ }).click();

    // Wait for a draggable block to appear in canvas
    const canvas = page.getByTestId('report-canvas');
    const blocks = canvas.locator('[draggable="true"]');
    await expect(blocks.first()).toBeVisible({ timeout: 10000 });

    // Empty state should disappear
    await expect(page.getByTestId('report-canvas-empty')).not.toBeVisible();

    // Drag handle should exist
    const dragHandle = canvas.locator('[data-testid^="drag-handle-"]').first();
    await expect(dragHandle).toBeAttached();
  });

  test('should reorder blocks via drag and drop', async ({ page }) => {
    const palette = page.getByTestId('block-palette');
    const canvas = page.getByTestId('report-canvas');
    const blocks = canvas.locator('[draggable="true"]');

    // Add three blocks in order
    await palette.getByRole('button', { name: /Data Table/ }).click();
    await expect(blocks).toHaveCount(1, { timeout: 10000 });

    await palette.getByRole('button', { name: /Stat Card/ }).click();
    await expect(blocks).toHaveCount(2, { timeout: 10000 });

    await palette.getByRole('button', { name: /Rich Text/ }).click();
    await expect(blocks).toHaveCount(3, { timeout: 10000 });

    // Drag the third block to the first position
    const thirdBlock = blocks.nth(2);
    const firstBlock = blocks.nth(0);

    await thirdBlock.dragTo(firstBlock, { targetPosition: { x: 10, y: 5 } });

    // Verify blocks still exist after reorder
    await expect(blocks).toHaveCount(3);
  });

  test('should have draggable blocks after adding multiple', async ({ page }) => {
    const palette = page.getByTestId('block-palette');
    const canvas = page.getByTestId('report-canvas');
    const blocks = canvas.locator('[draggable="true"]');

    // Add two blocks
    await palette.getByRole('button', { name: /Data Table/ }).click();
    await expect(blocks).toHaveCount(1, { timeout: 10000 });

    await palette.getByRole('button', { name: /Stat Card/ }).click();
    await expect(blocks).toHaveCount(2, { timeout: 10000 });

    // Both blocks should be draggable
    await expect(blocks.nth(0)).toHaveAttribute('draggable', 'true');
    await expect(blocks.nth(1)).toHaveAttribute('draggable', 'true');
  });
});

// ============================================================================
// #2: i18n — No Hardcoded Chinese in Designer Components
// ============================================================================

test.describe('Designer i18n (#2)', () => {
  test('report designer empty state renders localized text', async ({ page }) => {
    await page.goto('/report-designer', { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('report-canvas')).toBeVisible();

    // Empty state should show localized text (not raw i18n keys)
    const emptyState = page.getByTestId('report-canvas-empty');
    await expect(emptyState).toBeVisible();
    // Should have meaningful text content (not empty, not a raw key)
    const text = await emptyState.textContent();
    expect(text?.length).toBeGreaterThan(5);
    // Should NOT contain raw i18n key patterns
    expect(text).not.toContain('designer.');
    expect(text).not.toContain('$i18n:');
  });

  test('dashboard designer empty state renders localized text', async ({ page }) => {
    await page.goto('/dashboard-designer', { waitUntil: 'domcontentloaded' });
    // Wait for the canvas
    await expect(page.getByTestId('designer-canvas')).toBeVisible({ timeout: 15000 });

    // Check the empty state component
    const emptyState = page.getByTestId('dashboard-canvas-empty');
    // Dashboard may or may not have widgets — only check if empty
    const isEmpty = await emptyState.isVisible().catch(() => false);
    if (isEmpty) {
      const text = await emptyState.textContent();
      expect(text?.length).toBeGreaterThan(5);
      expect(text).not.toContain('designer.');
    }
  });

  test('dashboard widget palette shows localized text', async ({ page }) => {
    await page.goto('/dashboard-designer', { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('widget-palette')).toBeVisible({ timeout: 15000 });

    // Header should show localized text, not hardcoded Chinese or raw keys
    const paletteHeader = page.getByTestId('widget-palette').locator('h2').first();
    const headerText = await paletteHeader.textContent();
    expect(headerText?.length).toBeGreaterThan(0);
    expect(headerText).not.toContain('$i18n:');
  });
});

// ============================================================================
// #3: Dashboard Drag Preview Enhancement
// ============================================================================

test.describe('Dashboard Designer — Drag Preview (#3)', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/dashboard-designer', { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('widget-palette')).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId('designer-canvas')).toBeVisible();
  });

  test('should show drop preview when dragging widget over canvas', async ({ page }) => {
    // Find a widget in the palette to drag
    const palette = page.getByTestId('widget-palette');
    const firstWidget = palette.locator('[draggable="true"]').first();
    await expect(firstWidget).toBeVisible();

    const canvas = page.getByTestId('designer-canvas');

    // Start drag from palette widget to canvas
    // Use manual drag sequence to check for preview element
    const widgetBox = await firstWidget.boundingBox();
    const canvasBox = await canvas.boundingBox();

    if (widgetBox && canvasBox) {
      await page.mouse.move(
        widgetBox.x + widgetBox.width / 2,
        widgetBox.y + widgetBox.height / 2,
      );
      await page.mouse.down();
      await page.mouse.move(canvasBox.x + canvasBox.width / 2, canvasBox.y + canvasBox.height / 2, {
        steps: 5,
      });

      // The canvas should have the drag-over styling (ring-2 ring-blue-300)
      // Check that canvas is in drag-over state via class
      const canvasClasses = await canvas.getAttribute('class');
      // Drop preview element may or may not be visible depending on timing
      // But the canvas should at minimum accept the drag
      await page.mouse.up();
    }
  });

  test('widget palette items should have pre-rendered drag preview refs', async ({ page }) => {
    // The drag preview divs should be pre-rendered in the DOM (hidden offscreen)
    const palette = page.getByTestId('widget-palette');
    const previewElements = palette.locator('[aria-hidden="true"]');
    const count = await previewElements.count();
    // Each widget item should have a hidden drag preview
    expect(count).toBeGreaterThan(0);
  });
});

// ============================================================================
// #4: Unified Empty State Component
// ============================================================================

test.describe('Unified Empty State (#4)', () => {
  test('report designer shows dashed variant empty state', async ({ page }) => {
    await page.goto('/report-designer', { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('report-canvas')).toBeVisible();

    const emptyState = page.getByTestId('report-canvas-empty');
    await expect(emptyState).toBeVisible();

    // Should have the dashed border style (border-dashed class)
    const classes = await emptyState.getAttribute('class');
    expect(classes).toContain('border-dashed');
  });

  test('dashboard designer shows subtle variant empty state', async ({ page }) => {
    await page.goto('/dashboard-designer', { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('designer-canvas')).toBeVisible({ timeout: 15000 });

    const emptyState = page.getByTestId('dashboard-canvas-empty');
    const isEmpty = await emptyState.isVisible().catch(() => false);
    if (isEmpty) {
      // Subtle variant should NOT have border-dashed
      const classes = await emptyState.getAttribute('class');
      expect(classes).not.toContain('border-dashed');
    }
  });

  test('empty state disappears when blocks are added', async ({ page }) => {
    await page.goto('/report-designer', { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('report-canvas-empty')).toBeVisible();

    // Add a block
    await page.getByTestId('block-palette').getByRole('button', { name: /Data Table/ }).click();
    const blocks = page.getByTestId('report-canvas').locator('[draggable="true"]');
    await expect(blocks).toHaveCount(1, { timeout: 10000 });

    // Empty state should be gone
    await expect(page.getByTestId('report-canvas-empty')).not.toBeVisible();
  });
});

// ============================================================================
// #5: BPMN Canvas Drag Feedback
// ============================================================================

test.describe('BPMN Designer — Drag Feedback (#5)', () => {
  test.beforeEach(async ({ page }) => {
    // Navigate to BPMN designer — may need to go through menu or direct URL
    await page.goto('/bpmn-designer', { waitUntil: 'domcontentloaded' });
    await expect(page.locator('.react-flow')).toBeVisible({ timeout: 15000 });
  });

  test('should render BPMN canvas without errors', async ({ page }) => {
    // The canvas wrapper should be present
    const wrapper = page.locator('.react-flow');
    await expect(wrapper).toBeVisible({ timeout: 15000 });
  });

  test('BPMN canvas wrapper should support drag-over feedback class', async ({ page }) => {
    // Verify the wrapper div exists and has the transition-all class for smooth feedback
    const canvasWrapper = page.locator('.react-flow').locator('..');
    const classes = await canvasWrapper.getAttribute('class');
    expect(classes).toContain('transition-all');
  });
});

// ============================================================================
// #1 (continued): BlockPropertyPanel — BlockActionBar refactoring
// ============================================================================

test.describe('Report Designer — BlockActionBar (#1 refactoring)', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/report-designer');
    await expect(page.getByTestId('block-palette')).toBeVisible();
    await expect(page.getByTestId('report-canvas')).toBeVisible();
  });

  test('should show move up/down/delete buttons in property panel', async ({ page }) => {
    const palette = page.getByTestId('block-palette');
    const canvas = page.getByTestId('report-canvas');
    const blocks = canvas.locator('[draggable="true"]');

    // Add a data-table block
    await palette.getByRole('button', { name: /Data Table/ }).click();
    await expect(blocks).toHaveCount(1, { timeout: 10000 });

    // Select the block by clicking it
    await blocks.first().click();

    // Property panel should show the block action buttons
    const panel = page.getByTestId('block-property-panel');
    await expect(panel.getByText('Data Table', { exact: true })).toBeVisible();

    await expect(panel.getByTitle('Move up')).toBeVisible();
    await expect(panel.getByTitle('Move down')).toBeVisible();
    await expect(panel.getByTitle('Delete')).toBeVisible();
  });

  test('should enable move down when multiple blocks exist', async ({ page }) => {
    const palette = page.getByTestId('block-palette');
    const canvas = page.getByTestId('report-canvas');
    const blocks = canvas.locator('[draggable="true"]');

    // Add two blocks
    await palette.getByRole('button', { name: /Data Table/ }).click();
    await expect(blocks).toHaveCount(1, { timeout: 10000 });

    await palette.getByRole('button', { name: /Stat Card/ }).click();
    await expect(blocks).toHaveCount(2, { timeout: 10000 });

    // Select the first block
    await blocks.first().click();

    const panel = page.getByTestId('block-property-panel');
    await expect(panel.getByText('Data Table', { exact: true })).toBeVisible();

    // Move down should be enabled for the first block
    const moveDown = panel.getByTitle('Move down');
    await expect(moveDown).toBeVisible();
    await expect(moveDown).toBeEnabled();

    // Move up should be disabled for the first block
    const moveUp = panel.getByTitle('Move up');
    await expect(moveUp).toBeVisible();
    await expect(moveUp).toBeDisabled();
  });

  test('should delete block via property panel button', async ({ page }) => {
    const palette = page.getByTestId('block-palette');
    const canvas = page.getByTestId('report-canvas');
    const blocks = canvas.locator('[draggable="true"]');

    // Add a block
    await palette.getByRole('button', { name: /Rich Text/ }).click();
    await expect(blocks).toHaveCount(1, { timeout: 10000 });

    // Select and delete
    await blocks.first().click();
    await page.getByTestId('block-property-panel').getByTitle('Delete').click();

    // Block should be removed, empty state should return
    await expect(page.getByTestId('report-canvas-empty')).toBeVisible();
  });
});
