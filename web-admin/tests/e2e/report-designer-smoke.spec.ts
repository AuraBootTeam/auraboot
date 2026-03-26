/**
 * Report Designer — Smoke E2E Tests
 *
 * Verifies:
 * 1. Report designer loads at /report-designer
 * 2. Adding a data-table block
 * 3. Adding header and footer
 * 4. Block selection and property panel interaction
 * 5. Page settings, preview mode, unsaved indicator
 */

import { test, expect } from '@playwright/test';
import { uniqueId } from './helpers';

const reportTitle = `E2E Report ${uniqueId('rpt')}`;

test.describe('Report Designer', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/report-designer', { waitUntil: 'domcontentloaded' });
    // Wait for the designer to render
    await expect(page.getByTestId('block-palette')).toBeVisible();
    await expect(page.getByTestId('report-canvas')).toBeVisible();
  });

  test('should load designer with 3-panel layout', async ({ page }) => {
    await expect(page.getByTestId('block-palette')).toBeVisible();
    await expect(page.getByTestId('report-canvas')).toBeVisible();
    await expect(page.getByTestId('block-property-panel')).toBeVisible();

    // Toolbar elements
    await expect(page.locator('input[placeholder="Report Title"]')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Save' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Preview' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Export PDF' })).toBeVisible();
  });

  test('should set report title', async ({ page }) => {
    const titleInput = page.locator('input[placeholder="Report Title"]');
    await titleInput.fill(reportTitle);
    await expect(titleInput).toHaveValue(reportTitle);
  });

  test('should add a data-table block', async ({ page }) => {
    // Click "Data Table" button in palette using role selector for full button
    await page.getByRole('button', { name: /Data Table/ }).click();

    // Wait for the block to appear on canvas
    await expect(page.getByTestId('report-canvas').getByText('Configure columns in the property panel')).toBeVisible({ timeout: 10000 });
  });

  test('should add page header', async ({ page }) => {
    // Click "Page Header" button
    await page.getByRole('button', { name: /Page Header/ }).click();

    // Canvas should show "Header" label
    await expect(page.getByTestId('report-canvas').getByText('Header', { exact: true })).toBeVisible({ timeout: 10000 });
  });

  test('should add page footer', async ({ page }) => {
    // Click "Page Footer" button
    await page.getByRole('button', { name: /Page Footer/ }).click();

    // Canvas should show "Footer" label
    await expect(page.getByTestId('report-canvas').getByText('Footer', { exact: true })).toBeVisible({ timeout: 10000 });
  });

  test('should select block and show property panel', async ({ page }) => {
    // Add a data-table block first
    await page.getByRole('button', { name: /Data Table/ }).click();
    await expect(page.getByTestId('report-canvas').getByText('Configure columns in the property panel')).toBeVisible({ timeout: 10000 });

    // Click on the block in canvas to select it
    await page.getByTestId('report-canvas').getByText('Configure columns in the property panel').click();

    // Property panel should show "Data Table" heading and editor
    await expect(page.getByTestId('block-property-panel').getByText('Data Table', { exact: true })).toBeVisible();
    // Should show title input for the block
    await expect(page.getByTestId('block-property-panel').locator('input[placeholder="Table title"]')).toBeVisible();
  });

  test('should open page settings dialog', async ({ page }) => {
    // Click Settings button in toolbar
    await page.getByRole('button', { name: /Settings/ }).click();

    // Settings dialog should appear
    await expect(page.getByText('Page Settings')).toBeVisible();
    // Check page size select has A4 selected
    await expect(page.locator('select').first()).toHaveValue('A4');

    // Close dialog
    await page.getByRole('button', { name: 'Cancel' }).click();
    await expect(page.getByText('Page Settings')).not.toBeVisible();
  });

  test('should toggle preview mode', async ({ page }) => {
    // Enter preview mode
    await page.getByRole('button', { name: 'Preview' }).click();

    // In preview mode, the button should say "Edit"
    await expect(page.getByRole('button', { name: 'Edit' })).toBeVisible();

    // Canvas/palette should not be visible in preview mode
    await expect(page.getByTestId('block-palette')).not.toBeVisible();

    // Toggle back to design mode
    await page.getByRole('button', { name: 'Edit' }).click();
    await expect(page.getByTestId('block-palette')).toBeVisible();
  });

  test('should show unsaved indicator', async ({ page }) => {
    // New reports start as dirty (unsaved)
    await expect(page.getByText('Unsaved')).toBeVisible();
  });

  // ==================== Phase 2b: New Block Types ====================

  test('should add a grouped-table block', async ({ page }) => {
    await page.getByRole('button', { name: /Grouped Table/ }).click();

    // Canvas should show the grouped table placeholder
    await expect(page.getByTestId('report-canvas').getByText('Select a group-by field')).toBeVisible({ timeout: 10000 });
  });

  test('should add a stat-card block', async ({ page }) => {
    await page.getByRole('button', { name: /Stat Card/ }).click();

    // Canvas should show the stat card with sample value
    await expect(page.getByTestId('report-canvas').getByText('12,345')).toBeVisible({ timeout: 10000 });
  });

  test('should add a rich-text block', async ({ page }) => {
    await page.getByRole('button', { name: /Rich Text/ }).click();

    // Canvas should show the rich text placeholder
    await expect(page.getByTestId('report-canvas').getByText('Click to add text content')).toBeVisible({ timeout: 10000 });
  });

  test('should select grouped-table and show property panel', async ({ page }) => {
    await page.getByRole('button', { name: /Grouped Table/ }).click();
    await expect(page.getByTestId('report-canvas').getByText('Select a group-by field')).toBeVisible({ timeout: 10000 });

    // Click the block to select it
    await page.getByTestId('report-canvas').getByText('Select a group-by field').click();

    // Property panel should show "Grouped Table"
    await expect(page.getByTestId('block-property-panel').getByText('Grouped Table', { exact: true })).toBeVisible();
    // Should show group by field input
    await expect(page.getByTestId('block-property-panel').locator('input[placeholder="Field name to group by"]')).toBeVisible();
  });

  test('should select stat-card and show color picker', async ({ page }) => {
    await page.getByRole('button', { name: /Stat Card/ }).click();
    await expect(page.getByTestId('report-canvas').getByText('Metric')).toBeVisible({ timeout: 10000 });

    await page.getByTestId('report-canvas').getByText('Metric').click();

    await expect(page.getByTestId('block-property-panel').getByText('Stat Card', { exact: true })).toBeVisible();
    await expect(page.getByTestId('block-property-panel').getByText('Color')).toBeVisible();
  });

  test('should select rich-text and show content editor', async ({ page }) => {
    await page.getByRole('button', { name: /Rich Text/ }).click();
    await expect(page.getByTestId('report-canvas').getByText('Click to add text content')).toBeVisible({ timeout: 10000 });

    await page.getByTestId('report-canvas').getByText('Click to add text content').click();

    await expect(page.getByTestId('block-property-panel').getByText('Rich Text', { exact: true })).toBeVisible();
    await expect(page.getByTestId('block-property-panel').locator('textarea')).toBeVisible();
  });

  test('should have all 8 block types in palette', async ({ page }) => {
    await expect(page.getByRole('button', { name: /Data Table/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /Grouped Table/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /Stat Card/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /Rich Text/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /Cross Tab/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /^Chart/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /Page Header/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /Page Footer/ })).toBeVisible();
  });

  // ==================== Phase 2c: Cross Tab + Chart + Parameters ====================

  test('should add a cross-tab block', async ({ page }) => {
    await page.getByRole('button', { name: /Cross Tab/ }).click();
    await expect(page.getByTestId('report-canvas').getByText('Configure row, column, and value fields')).toBeVisible({ timeout: 10000 });
  });

  test('should add a chart block', async ({ page }) => {
    await page.getByRole('button', { name: /^Chart/ }).click();
    await expect(page.getByTestId('report-canvas').getByText('Configure category and value fields')).toBeVisible({ timeout: 10000 });
  });

  test('should select cross-tab and show property panel', async ({ page }) => {
    await page.getByRole('button', { name: /Cross Tab/ }).click();
    await expect(page.getByTestId('report-canvas').getByText('Configure row, column, and value fields')).toBeVisible({ timeout: 10000 });

    await page.getByTestId('report-canvas').getByText('Configure row, column, and value fields').click();
    await expect(page.getByTestId('block-property-panel').getByText('Cross Tab', { exact: true })).toBeVisible();
    await expect(page.getByTestId('block-property-panel').locator('input[placeholder="Field for row grouping"]')).toBeVisible();
  });

  test('should select chart and show chart type selector', async ({ page }) => {
    await page.getByRole('button', { name: /^Chart/ }).click();
    await expect(page.getByTestId('report-canvas').getByText('Configure category and value fields')).toBeVisible({ timeout: 10000 });

    await page.getByTestId('report-canvas').getByText('Configure category and value fields').click();
    await expect(page.getByTestId('block-property-panel').getByText('Chart', { exact: true })).toBeVisible();
    // Chart type buttons
    await expect(page.getByTestId('block-property-panel').getByRole('button', { name: 'Bar', exact: true })).toBeVisible();
    await expect(page.getByTestId('block-property-panel').getByRole('button', { name: 'Pie', exact: true })).toBeVisible();
  });

  test('should show parameter editor in report properties', async ({ page }) => {
    // Click empty canvas area to deselect any block
    await page.getByTestId('report-canvas').click({ position: { x: 10, y: 10 } });

    // Property panel should show "Report Properties" with Parameter section
    await expect(page.getByTestId('block-property-panel').getByText('Parameters', { exact: true })).toBeVisible();
  });
});
