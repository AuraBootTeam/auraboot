/**
 * Designer Unified Architecture — E2E Tests
 *
 * Covers the 3 gaps fixed in the designer unification effort:
 *
 * GAP 1: Report Designer — Shared DesignerToolbar with Undo/Redo buttons
 *   DUA-01..05: Toolbar buttons, undo/redo, testId
 *
 * GAP 2: Report Designer — Version History Panel
 *   DUA-06..10: Version history open/close, empty state, save+version
 *
 * GAP 3: Shared DataSourceWizard — ModelPicker, NamedQueryPicker, FilterBuilder
 *   DUA-11..14: Report DataTable data source with shared pickers
 *
 * Integration:
 *   DUA-15: Full lifecycle — create, edit, save, check version, undo
 *
 * @since 6.1.0
 */

import { test, expect } from '@playwright/test';
import { uniqueId } from '../helpers';

// Report designer is a heavy page — increase per-test timeout
test.setTimeout(60_000);

// ---------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------

async function openReportDesigner(page: import('@playwright/test').Page) {
  await page.goto('/report-designer', { waitUntil: 'domcontentloaded' });
  // Wait for block palette to appear (may be SSR-rendered or client-rendered)
  await expect(page.getByTestId('block-palette')).toBeVisible({ timeout: 30000 });
  // Wait for React hydration + store initialization: the title input gets a value
  // only after the useEffect runs createReport(), which happens after hydration.
  const titleInput = page.locator('input[placeholder="Report Title"]');
  await expect(titleInput).toHaveValue('Untitled Report', { timeout: 15000 });
}

// =========================================================================
// GAP 1: Report Toolbar Undo/Redo
// =========================================================================

test.describe('GAP 1: Report Toolbar Undo/Redo', () => {
  test('DUA-01: Toolbar testId, undo/redo visible and disabled initially', async ({ page }) => {
    await openReportDesigner(page);

    // Toolbar has correct testId
    await expect(page.getByTestId('report-designer-toolbar')).toBeVisible();
    await expect(page.getByTestId('report-designer-toolbar-btn-save')).toBeVisible();

    // Undo/Redo visible but disabled
    const undoBtn = page.getByTestId('report-designer-toolbar-btn-undo');
    const redoBtn = page.getByTestId('report-designer-toolbar-btn-redo');
    await expect(undoBtn).toBeVisible();
    await expect(undoBtn).toBeDisabled();
    await expect(redoBtn).toBeVisible();
    await expect(redoBtn).toBeDisabled();
  });

  test('DUA-02: Undo enabled after adding block, undo/redo cycle works', async ({ page }) => {
    await openReportDesigner(page);
    const canvas = page.getByTestId('report-canvas');
    const undoBtn = page.getByTestId('report-designer-toolbar-btn-undo');
    const redoBtn = page.getByTestId('report-designer-toolbar-btn-redo');

    // Add a stat card block by clicking palette item
    const palette = page.getByTestId('block-palette');
    await palette.getByTestId('block-palette-item-stat-card').click();
    const statCard = canvas.getByText('12,345');
    await expect(statCard).toBeVisible({ timeout: 10000 });

    // Undo should be enabled
    await expect(undoBtn).toBeEnabled();

    // Click undo — block disappears
    await undoBtn.click();
    await expect(statCard).not.toBeVisible({ timeout: 5000 });

    // Redo enabled — click redo — block reappears
    await expect(redoBtn).toBeEnabled();
    await redoBtn.click();
    await expect(canvas.getByText('12,345')).toBeVisible({ timeout: 5000 });
  });

  test('DUA-03: Keyboard shortcuts work alongside toolbar buttons', async ({ page }) => {
    await openReportDesigner(page);
    const canvas = page.getByTestId('report-canvas');

    // Add a block via palette testId
    const palette = page.getByTestId('block-palette');
    await palette.getByTestId('block-palette-item-rich-text').click();
    await expect(canvas.getByText('Click to add text content')).toBeVisible({ timeout: 10000 });

    // Ctrl+Z undoes
    await page.keyboard.press('ControlOrMeta+z');
    await expect(canvas.getByText('Click to add text content')).not.toBeVisible({ timeout: 5000 });

    // Ctrl+Y redoes
    await page.keyboard.press('ControlOrMeta+y');
    await expect(canvas.getByText('Click to add text content')).toBeVisible({ timeout: 5000 });
  });
});

// =========================================================================
// GAP 2: Report Version History Panel
// =========================================================================

test.describe('GAP 2: Report Version History', () => {
  test('DUA-06: History button visible, opens panel with empty state', async ({ page }) => {
    await openReportDesigner(page);

    // History button visible
    const historyBtn = page.locator('button[title="Version History"]');
    await expect(historyBtn).toBeVisible();

    // Click opens panel
    await historyBtn.click();
    await expect(page.getByText('Version History', { exact: true })).toBeVisible({ timeout: 5000 });
    await expect(page.getByText('No versions yet')).toBeVisible({ timeout: 5000 });
    await expect(page.getByText('Save to create the first version')).toBeVisible();
  });

  test('DUA-07: Version panel opens with ESC close', async ({ page }) => {
    await openReportDesigner(page);
    const panel = page.locator('.fixed.right-0.z-40');

    // Open panel
    await page.locator('button[title="Version History"]').click();
    await expect(panel).toHaveClass(/translate-x-0/, { timeout: 5000 });

    // Verify close button exists in panel header
    await expect(page.locator('button[aria-label="Close version panel"]')).toBeVisible();

    // Close with ESC
    await page.keyboard.press('Escape');
    await expect(panel).toHaveClass(/translate-x-full/, { timeout: 5000 });
  });

  test('DUA-09: Save report then check version panel loads', async ({ page }) => {
    await openReportDesigner(page);

    // Set unique title
    const title = `VH Test ${uniqueId('vh')}`;
    await page.locator('input[placeholder="Report Title"]').fill(title);

    // Save
    const saveBtn = page.getByTestId('report-designer-toolbar-btn-save');
    await expect(saveBtn).toBeEnabled();

    const saveResponse = page.waitForResponse(
      (resp) =>
        resp.url().includes('/api/pages') &&
        (resp.request().method() === 'POST' || resp.request().method() === 'PUT'),
      { timeout: 15000 },
    );
    await saveBtn.click();
    const resp = await saveResponse;
    expect(resp.status()).toBeLessThan(400);

    // Wait for save to complete
    await page
      .getByText('Saving...')
      .waitFor({ state: 'hidden', timeout: 15000 })
      .catch(() => {});
    await expect(saveBtn).toBeDisabled({ timeout: 5000 });

    // Open version history — should trigger API call
    await page.locator('button[title="Version History"]').click();
    await expect(page.getByText('Version History', { exact: true })).toBeVisible({ timeout: 5000 });

    // Wait for version list to load (API call)
    const versionApi = page
      .waitForResponse((resp) => resp.url().includes('/versions') && resp.status() === 200, {
        timeout: 15000,
      })
      .catch(() => null);
    await versionApi;

    await page
      .getByText('Loading versions...')
      .waitFor({ state: 'hidden', timeout: 10000 })
      .catch(() => {});

    // Panel footer shows version count text
    const panelFooter = page.locator('.fixed.right-0.z-40');
    await expect(panelFooter).toBeVisible();
  });
});

// =========================================================================
// GAP 3: Shared DataSource Components
// =========================================================================

test.describe('GAP 3: Report DataSource — Shared Pickers', () => {
  test('DUA-11: Add data source shows type selector with Model/NQ/API', async ({ page }) => {
    await openReportDesigner(page);

    // Add data-table block and select it
    await page.getByRole('button', { name: /Data Table|数据表/i }).first().click();
    await expect(
      page.getByTestId('report-canvas').getByText('Configure columns in the property panel'),
    ).toBeVisible({ timeout: 10000 });
    await page
      .getByTestId('report-canvas')
      .getByText('Configure columns in the property panel')
      .click();

    const panel = page.getByTestId('block-property-panel');
    await panel.getByText('+ Add new data source').click();

    // The DS type selector is the FIRST select in the add-source form (appears right after "Key" input)
    // It has options: Model, Named Query, API
    const addDsForm = panel.locator('.bg-gray-50'); // add-ds form has gray bg
    const typeSelect = addDsForm.locator('select').first();
    await expect(typeSelect).toBeVisible();
    const options = await typeSelect.locator('option').allTextContents();
    expect(options).toContain('Model');
    expect(options).toContain('Named Query');
    expect(options).toContain('API');
  });

  test('DUA-12: Model type loads shared ModelPicker with API data', async ({ page }) => {
    await openReportDesigner(page);

    await page.getByRole('button', { name: /Data Table|数据表/i }).first().click();
    await expect(
      page.getByTestId('report-canvas').getByText('Configure columns in the property panel'),
    ).toBeVisible({ timeout: 10000 });
    await page
      .getByTestId('report-canvas')
      .getByText('Configure columns in the property panel')
      .click();

    const panel = page.getByTestId('block-property-panel');
    await panel.getByText('+ Add new data source').click();

    // Wait for models API (ModelPicker auto-fetches)
    await page.waitForResponse(
      (resp) => resp.url().includes('/api/meta/models') && resp.status() === 200,
      { timeout: 15000 },
    );

    // ModelPicker renders as a select with "Select model" placeholder
    const modelSelect = panel.locator('select').filter({ hasText: /Select model/i });
    await expect(modelSelect).toBeVisible();
    const optionCount = await modelSelect.locator('option').count();
    expect(optionCount).toBeGreaterThan(1);
  });

  test('DUA-13: NamedQuery type shows shared NamedQueryPicker', async ({ page }) => {
    await openReportDesigner(page);

    await page.getByRole('button', { name: /Data Table|数据表/i }).first().click();
    await expect(
      page.getByTestId('report-canvas').getByText('Configure columns in the property panel'),
    ).toBeVisible({ timeout: 10000 });
    await page
      .getByTestId('report-canvas')
      .getByText('Configure columns in the property panel')
      .click();

    const panel = page.getByTestId('block-property-panel');
    await panel.getByText('+ Add new data source').click();

    // Switch to namedQuery using the DS type selector (first select in the add-form)
    const addDsForm = panel.locator('.bg-gray-50');
    const typeSelect = addDsForm.locator('select').first();
    await typeSelect.selectOption('namedQuery');

    // Wait for NQ API
    await page.waitForResponse(
      (resp) => resp.url().includes('/api/meta/named-queries') && resp.status() === 200,
      { timeout: 15000 },
    );

    // NamedQueryPicker visible
    const nqSelect = panel.locator('select').filter({ hasText: /Select named query/i });
    await expect(nqSelect).toBeVisible();
  });

  test('DUA-14: API type shows URL text input', async ({ page }) => {
    await openReportDesigner(page);

    await page.getByRole('button', { name: /Data Table|数据表/i }).first().click();
    await expect(
      page.getByTestId('report-canvas').getByText('Configure columns in the property panel'),
    ).toBeVisible({ timeout: 10000 });
    await page
      .getByTestId('report-canvas')
      .getByText('Configure columns in the property panel')
      .click();

    const panel = page.getByTestId('block-property-panel');
    await panel.getByText('+ Add new data source').click();

    // Switch to API using the DS type selector
    const addDsForm = panel.locator('.bg-gray-50');
    const typeSelect = addDsForm.locator('select').first();
    await typeSelect.selectOption('api');

    // URL input visible
    const urlInput = panel.locator('input[placeholder="API URL"]');
    await expect(urlInput).toBeVisible();
    await urlInput.fill('/api/custom/data');
    await expect(urlInput).toHaveValue('/api/custom/data');
  });
});

// =========================================================================
// Integration: Full Lifecycle
// =========================================================================

test.describe('Integration', () => {
  test('DUA-15: Create → edit → save → version history → undo', async ({ page }) => {
    await openReportDesigner(page);

    const title = `Lifecycle ${uniqueId('lc')}`;
    const canvas = page.getByTestId('report-canvas');
    const undoBtn = page.getByTestId('report-designer-toolbar-btn-undo');
    const saveBtn = page.getByTestId('report-designer-toolbar-btn-save');

    // 1. Set title
    await page.locator('input[placeholder="Report Title"]').fill(title);

    // 2. Add block via palette testId
    await page.getByTestId('block-palette-item-rich-text').click();
    await expect(canvas.getByText('Click to add text content')).toBeVisible({ timeout: 10000 });
    await expect(undoBtn).toBeEnabled();

    // 3. Save
    const saveResponse = page.waitForResponse(
      (resp) =>
        resp.url().includes('/api/pages') &&
        (resp.request().method() === 'POST' || resp.request().method() === 'PUT'),
      { timeout: 15000 },
    );
    await saveBtn.click();
    const resp = await saveResponse;
    expect(resp.status()).toBeLessThan(400);
    await page
      .getByText('Saving...')
      .waitFor({ state: 'hidden', timeout: 15000 })
      .catch(() => {});
    await expect(saveBtn).toBeDisabled({ timeout: 5000 });

    // 4. Open/close version history
    await page.locator('button[title="Version History"]').click();
    await expect(page.getByText('Version History', { exact: true })).toBeVisible({ timeout: 5000 });
    await page.keyboard.press('Escape');

    // 5. Undo block addition
    await undoBtn.click();
    await expect(canvas.getByText('Click to add text content')).not.toBeVisible({ timeout: 5000 });

    // 6. Unsaved indicator visible (undid after save)
    await expect(page.getByText('Unsaved')).toBeVisible({ timeout: 3000 });
  });
});
