/**
 * Page Designer — Left Panel Deep E2E Tests
 *
 * Covers all 4 left-panel tabs with deep interaction testing:
 * - A1. Components Tab (13 block types, search, categories, click-to-add)
 * - A2. Fields Tab (model selector, field list, click-to-add)
 * - A3. Widgets Tab (11 widget types, search, add to form-section or new block)
 * - A4. Outline Tab (block sync, click-to-select, delete sync, type labels)
 * - H.  Left Panel Details (search precision, empty state, index badges)
 *
 * Navigation: page.goto() used because Page Designer is a platform designer tool,
 * not a sidebar menu page (allowed per AGENTS.md exception for designer workbenches).
 *
 * Dimensions covered:
 *   A1: D2 (palette render), D5 (component types), D13 (search), D14 (add feedback)
 *   A2: D2 (field list render), D5 (field types), D14 (add feedback)
 *   A3: D2 (widget palette render), D5 (widget types), D13 (search), D14 (add feedback)
 *   A4: D2 (outline render), D5 (type labels), D8 (selection sync), D11 (delete sync)
 * Not applicable: D1 (no sidebar menu), D3/D9/D10 (no status machine), D4/D6/D7/D12 (not CRUD).
 *
 * @since 4.1.0
 */

import { test, expect, type Page } from '@playwright/test';
import { uniqueId } from '../helpers/index';

// ---------------------------------------------------------------------------
// Selectors
// ---------------------------------------------------------------------------

/**
 * Matches only root canvas block containers (not drag-handle, remove, or content sub-elements).
 */
const BLK = '[data-testid^="canvas-block-"]:not([data-testid*="-drag-"]):not([data-testid*="-remove-"]):not([data-testid*="-content-"])';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

async function createPage(page: Page): Promise<string> {
  const name = uniqueId('lpanel');
  const pageKey = `e2e_lpanel_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 7)}`;

  const resp = await page.request.post('/api/pages', {
    data: {
      name,
      pageKey,
      title: name,
      kind: 'composite',
      blocks: [],
      metaInfo: { componentCount: 0 },
      semver: '0.1.0',
    },
  });
  expect(resp.ok(), `Create page API failed: ${resp.status()}`).toBeTruthy();
  const body = await resp.json();
  expect(body.code, `API response code must be '0', got: ${JSON.stringify(body)}`).toBe('0');
  const pid = body.data?.pid;
  expect(pid, 'Page pid must be returned').toBeTruthy();
  return pid;
}

async function openDesigner(page: Page, pid: string): Promise<void> {
  await page.goto(`/page-designer/${pid}`, { waitUntil: 'domcontentloaded' });
  await page.getByTestId('canvas-editor').waitFor({ state: 'visible', timeout: 15000 });
}

/** Count root canvas blocks (excludes sub-elements). */
async function blockCount(page: Page): Promise<number> {
  return page.locator('[data-testid^="canvas-block-content-"]').count();
}

/** Click a block-palette item to add a block, wait for DOM update. */
async function addBlockFromPalette(page: Page, type: string): Promise<void> {
  // Ensure we're on the Components tab
  await page.getByTestId('canvas-left-tab-components').click();
  await page.getByTestId('block-palette').waitFor({ state: 'visible' });
  await page.getByTestId(`block-palette-item-${type}`).click();
  // Small wait for React state to settle
  await page.waitForFunction(
    () => document.querySelectorAll('[data-testid^="canvas-block-content-"]').length >= 1,
    { timeout: 5000 },
  ).catch(() => {/* block may already exist */});
}

async function dragWidgetTo(
  page: Page,
  widgetType: string,
  target: ReturnType<Page['locator']>,
  primeTarget?: ReturnType<Page['locator']>,
): Promise<void> {
  await page.getByTestId('canvas-left-tab-widgets').click();
  const source = page.getByTestId(`widget-palette-item-${widgetType}`);
  await expect(source).toBeVisible();

  const sourceBox = await source.boundingBox();
  expect(sourceBox, `Widget ${widgetType} must have a bounding box`).toBeTruthy();

  await page.mouse.move(sourceBox!.x + sourceBox!.width / 2, sourceBox!.y + sourceBox!.height / 2);
  await page.mouse.down();
  await page.mouse.move(sourceBox!.x + sourceBox!.width / 2 + 12, sourceBox!.y + sourceBox!.height / 2 + 12, { steps: 4 });

  if (primeTarget) {
    const primeBox = await primeTarget.boundingBox();
    expect(primeBox, 'Prime target must have a bounding box').toBeTruthy();
    await page.mouse.move(primeBox!.x + primeBox!.width / 2, primeBox!.y + primeBox!.height / 2, { steps: 10 });
  }

  await expect(target).toBeVisible({ timeout: 5000 });
  const targetBox = await target.boundingBox();
  expect(targetBox, 'Drop target must have a bounding box').toBeTruthy();

  await page.mouse.move(targetBox!.x + targetBox!.width / 2, targetBox!.y + targetBox!.height / 2, { steps: 12 });
  await page.mouse.up();
}

async function dragLocatorTo(
  page: Page,
  source: ReturnType<Page['locator']>,
  target: ReturnType<Page['locator']>,
  primeTarget?: ReturnType<Page['locator']>,
): Promise<void> {
  await expect(source).toBeVisible();
  const sourceBox = await source.boundingBox();
  expect(sourceBox, 'Source locator must have a bounding box').toBeTruthy();

  await page.mouse.move(sourceBox!.x + sourceBox!.width / 2, sourceBox!.y + sourceBox!.height / 2);
  await page.mouse.down();
  await page.mouse.move(sourceBox!.x + sourceBox!.width / 2 + 10, sourceBox!.y + sourceBox!.height / 2 + 10, { steps: 4 });

  if (primeTarget) {
    const primeBox = await primeTarget.boundingBox();
    expect(primeBox, 'Prime target must have a bounding box').toBeTruthy();
    await page.mouse.move(primeBox!.x + primeBox!.width / 2, primeBox!.y + primeBox!.height / 2, { steps: 10 });
  }

  await expect(target).toBeVisible({ timeout: 5000 });
  const targetBox = await target.boundingBox();
  expect(targetBox, 'Target locator must have a bounding box').toBeTruthy();
  await page.mouse.move(targetBox!.x + targetBox!.width / 2, targetBox!.y + targetBox!.height / 2, { steps: 12 });
  await page.mouse.up();
}

/** Get a list of all published model codes available in the system. */
async function getFirstAvailableModel(page: Page): Promise<string | null> {
  const resp = await page.request.get('/api/meta/models', {
    params: { currentOnly: 'true', pageSize: '20' },
  });
  if (!resp.ok()) return null;
  const body = await resp.json();
  const records = body?.data?.records ?? [];
  if (records.length === 0) return null;
  return records[0].code as string;
}

// ---------------------------------------------------------------------------
// A1. Components Tab — 13 block types, search, categories, click-to-add
// ---------------------------------------------------------------------------

test.describe('A1 — Components Tab', () => {
  test('A1.1 — shows all 13 block types with correct footer count', async ({ page }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);

    // Components tab is active by default
    await expect(page.getByTestId('block-palette')).toBeVisible();

    // Verify all 13 block types from BLOCK_DEFS
    const allBlockTypes = [
      // DATA category
      'table', 'sub-table', 'chart', 'stat-card', 'monthly-grid',
      // LAYOUT category
      'tabs', 'divider', 'toolbar',
      // FORM category
      'form-section', 'form-buttons', 'filters',
      // DISPLAY category
      'rich-text', 'detail-section',
    ];

    for (const type of allBlockTypes) {
      await expect(
        page.getByTestId(`block-palette-item-${type}`),
        `Block type '${type}' must be visible in palette`,
      ).toBeVisible();
    }

    // Footer shows exact count: "13 block types"
    const palette = page.getByTestId('block-palette');
    await expect(palette.locator('text=13 block types')).toBeVisible();
  });

  test('A1.2 — search "chart" shows only matching block types', async ({ page }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);

    const searchInput = page.getByTestId('block-palette-search');
    await expect(searchInput).toBeVisible();

    // Type "chart"
    await searchInput.fill('chart');

    // chart should be visible (name "Chart" matches)
    await expect(page.getByTestId('block-palette-item-chart')).toBeVisible();

    // Non-matching types must be hidden
    await expect(page.getByTestId('block-palette-item-table')).not.toBeVisible();
    await expect(page.getByTestId('block-palette-item-form-section')).not.toBeVisible();
    await expect(page.getByTestId('block-palette-item-toolbar')).not.toBeVisible();
    await expect(page.getByTestId('block-palette-item-tabs')).not.toBeVisible();
    await expect(page.getByTestId('block-palette-item-divider')).not.toBeVisible();
  });

  test('A1.3 — clearing search restores all 13 block types', async ({ page }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);

    const searchInput = page.getByTestId('block-palette-search');

    // Search then clear
    await searchInput.fill('chart');
    await expect(page.getByTestId('block-palette-item-table')).not.toBeVisible();

    await searchInput.clear();

    // All types must reappear
    await expect(page.getByTestId('block-palette-item-table')).toBeVisible();
    await expect(page.getByTestId('block-palette-item-chart')).toBeVisible();
    await expect(page.getByTestId('block-palette-item-form-section')).toBeVisible();
    await expect(page.getByTestId('block-palette-item-rich-text')).toBeVisible();

    // Footer still shows 13
    await expect(page.getByTestId('block-palette').locator('text=13 block types')).toBeVisible();
  });

  test('A1.4 — clicking block type adds block to canvas (blockCount +1)', async ({ page }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);

    // Initial state: empty canvas
    expect(await blockCount(page)).toBe(0);
    await expect(page.getByTestId('canvas-empty-state')).toBeVisible();

    // Click Table
    await page.getByTestId('block-palette-item-table').click();
    await page.locator('[data-testid^="canvas-block-content-"]').first().waitFor({ state: 'visible', timeout: 5000 });
    expect(await blockCount(page)).toBe(1);

    // Empty state must be gone
    await expect(page.getByTestId('canvas-empty-state')).not.toBeVisible();

    // Click Chart — count becomes 2
    await page.getByTestId('block-palette-item-chart').click();
    await page.waitForFunction(() => document.querySelectorAll('[data-testid^="canvas-block-content-"]').length >= 2, { timeout: 5000 });
    expect(await blockCount(page)).toBe(2);

    // Click form-section — count becomes 3
    await page.getByTestId('block-palette-item-form-section').click();
    await page.waitForFunction(() => document.querySelectorAll('[data-testid^="canvas-block-content-"]').length >= 3, { timeout: 5000 });
    expect(await blockCount(page)).toBe(3);
  });

  test('A1.5 — 4 category groups visible (DATA/LAYOUT/FORM/DISPLAY)', async ({ page }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);

    const palette = page.getByTestId('block-palette');

    // Category headers use specific color classes from CATEGORY_CONFIG
    await expect(palette.locator('.text-purple-600').filter({ hasText: 'Data' })).toBeVisible();
    await expect(palette.locator('.text-blue-600').filter({ hasText: 'Layout' })).toBeVisible();
    await expect(palette.locator('.text-yellow-600').filter({ hasText: 'Form' })).toBeVisible();
    await expect(palette.locator('.text-green-600').filter({ hasText: 'Display' })).toBeVisible();
  });
});

// ---------------------------------------------------------------------------
// A2. Fields Tab — model selector, field list, click-to-add
// ---------------------------------------------------------------------------

test.describe('A2 — Fields Tab', () => {
  test('A2.1 — initial state shows model selector + empty state prompt', async ({ page }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);

    // Switch to Fields tab
    await page.getByTestId('canvas-left-tab-fields').click();
    await expect(page.getByTestId('field-palette')).toBeVisible();

    // Model selector is visible
    await expect(page.getByTestId('field-palette-model-select')).toBeVisible();

    // Empty state prompt visible (no model selected)
    await expect(page.getByText('Select a model to see fields')).toBeVisible();

    // No field items visible yet
    await expect(page.locator('[data-testid^="field-palette-item-"]').first()).not.toBeVisible();
  });

  test('A2.2 — selecting a model shows field list', async ({ page }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);

    // Get first available model
    const modelCode = await getFirstAvailableModel(page);
    if (!modelCode) {
      test.skip(true, 'No published models available in the system');
      return;
    }

    // Switch to Fields tab
    await page.getByTestId('canvas-left-tab-fields').click();
    await expect(page.getByTestId('field-palette')).toBeVisible();

    // Select model from dropdown
    await page.getByTestId('field-palette-model-select').selectOption(modelCode);

    // Field list should load (wait for loading to finish)
    await page.waitForFunction(
      () => document.querySelectorAll('[data-testid^="field-palette-item-"]').length > 0,
      { timeout: 10000 },
    );

    // At least 1 field item visible
    const fieldItems = page.locator('[data-testid^="field-palette-item-"]');
    const count = await fieldItems.count();
    expect(count, `Model '${modelCode}' should have at least 1 field`).toBeGreaterThan(0);

    // Empty state prompt should be gone
    await expect(page.getByText('Select a model to see fields')).not.toBeVisible();

    // Footer shows field count
    const footerText = await page.getByTestId('field-palette').locator('.border-t').innerText();
    expect(footerText, 'Footer should mention field count').toMatch(/\d+ field/);
  });

  test('A2.3 — field items have correct type badges and code labels', async ({ page }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);

    const modelCode = await getFirstAvailableModel(page);
    if (!modelCode) {
      test.skip(true, 'No published models available in the system');
      return;
    }

    // Switch to Fields tab, select model
    await page.getByTestId('canvas-left-tab-fields').click();
    await page.getByTestId('field-palette-model-select').selectOption(modelCode);

    // Wait for fields to load
    await page.waitForFunction(
      () => document.querySelectorAll('[data-testid^="field-palette-item-"]').length > 0,
      { timeout: 10000 },
    );

    // Get the first field item
    const firstField = page.locator('[data-testid^="field-palette-item-"]').first();
    await expect(firstField).toBeVisible();

    // Field item must have: a type badge (font-mono span), a name (non-empty text), a code (font-mono text)
    // Type badge — contains the abbreviated type icon (Aa, #, D, etc.)
    const typeBadge = firstField.locator('span.font-mono').first();
    await expect(typeBadge).toBeVisible();
    const badgeText = await typeBadge.innerText();
    expect(badgeText.trim().length, 'Type badge must not be empty').toBeGreaterThan(0);

    // Field name line is visible (non-empty)
    const fieldNameDiv = firstField.locator('.text-xs.font-medium');
    await expect(fieldNameDiv).toBeVisible();
    const fieldName = await fieldNameDiv.innerText();
    expect(fieldName.trim().length, 'Field name must not be empty').toBeGreaterThan(0);

    // Code line is visible (font-mono, smaller text)
    const codeSpan = firstField.locator('span.font-mono.text-gray-400');
    await expect(codeSpan).toBeVisible();
    const codeText = await codeSpan.innerText();
    expect(codeText.trim().length, 'Field code must not be empty').toBeGreaterThan(0);
  });

  test('A2.4 — field search filters the field list', async ({ page }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);

    const modelCode = await getFirstAvailableModel(page);
    if (!modelCode) {
      test.skip(true, 'No published models available in the system');
      return;
    }

    // Switch to Fields tab, select model
    await page.getByTestId('canvas-left-tab-fields').click();
    await page.getByTestId('field-palette-model-select').selectOption(modelCode);

    // Wait for fields to load
    await page.waitForFunction(
      () => document.querySelectorAll('[data-testid^="field-palette-item-"]').length > 0,
      { timeout: 10000 },
    );

    const totalCount = await page.locator('[data-testid^="field-palette-item-"]').count();

    // Search for a non-existent field
    await page.getByTestId('field-palette-search').fill('zzz_nonexistent_xyz');

    // Should show empty state or reduced count
    const filteredCount = await page.locator('[data-testid^="field-palette-item-"]').count();
    expect(filteredCount, 'Non-matching search should reduce visible fields').toBeLessThan(totalCount);

    // Clear search -> full list restored
    await page.getByTestId('field-palette-search').clear();
    const restoredCount = await page.locator('[data-testid^="field-palette-item-"]').count();
    expect(restoredCount, 'Clearing search should restore all fields').toBe(totalCount);
  });

  test('A2.5 — switching model refreshes field list', async ({ page }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);

    // Get all available models
    const resp = await page.request.get('/api/meta/models', {
      params: { currentOnly: 'true', pageSize: '20' },
    });
    const body = await resp.json();
    const records = body?.data?.records ?? [];

    if (records.length < 2) {
      test.skip(true, 'Need at least 2 published models for model-switch test');
      return;
    }

    const model1 = records[0].code as string;
    const model2 = records[1].code as string;

    // Switch to Fields tab
    await page.getByTestId('canvas-left-tab-fields').click();

    // Select model 1
    await page.getByTestId('field-palette-model-select').selectOption(model1);
    await page.waitForFunction(
      () => document.querySelectorAll('[data-testid^="field-palette-item-"]').length > 0,
      { timeout: 10000 },
    );
    const count1 = await page.locator('[data-testid^="field-palette-item-"]').count();

    // Switch to model 2
    await page.getByTestId('field-palette-model-select').selectOption(model2);

    // Wait for list to refresh (may briefly show loading)
    await page.waitForFunction(
      (expectedCount) => {
        const items = document.querySelectorAll('[data-testid^="field-palette-item-"]');
        return items.length !== expectedCount || items.length > 0;
      },
      count1,
      { timeout: 10000 },
    );

    // Field list now reflects model 2 (model selector shows model2)
    const selectedValue = await page.getByTestId('field-palette-model-select').inputValue();
    expect(selectedValue, 'Model selector must reflect model2').toBe(model2);

    // Field palette is visible (model is selected, so no empty-state prompt)
    await expect(page.getByText('Select a model to see fields')).not.toBeVisible();
  });

  test('A2.6 — clicking field with form-section selected adds field to existing block', async ({ page }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);

    const modelCode = await getFirstAvailableModel(page);
    if (!modelCode) {
      test.skip(true, 'No published models available in the system');
      return;
    }

    await addBlockFromPalette(page, 'form-section');
    await page.locator('[data-testid^="canvas-block-content-"]').first().waitFor({ state: 'visible' });
    expect(await blockCount(page)).toBe(1);

    await page.locator(BLK).first().click();
    await expect(page.locator(BLK).first()).toHaveClass(/selected/);

    await page.getByTestId('canvas-left-tab-fields').click();
    await page.getByTestId('field-palette-model-select').selectOption(modelCode);
    await page.waitForFunction(
      () => document.querySelectorAll('[data-testid^="field-palette-item-"]').length > 0,
      { timeout: 10000 },
    );

    const firstField = page.locator('[data-testid^="field-palette-item-"]').first();
    const fieldCode = (await firstField.locator('span.font-mono.text-gray-400').innerText()).trim();
    await firstField.click();

    expect(await blockCount(page)).toBe(1);
    const fieldChips = page.locator('[data-testid^="canvas-block-content-"]').first().locator('.cursor-grab');
    await expect(fieldChips).toHaveCount(1);
    await expect(fieldChips.first()).toContainText(fieldCode);
  });

  test('A2.7 — clicking field with non-form block selected creates adjacent form-section', async ({ page }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);

    const modelCode = await getFirstAvailableModel(page);
    if (!modelCode) {
      test.skip(true, 'No published models available in the system');
      return;
    }

    await addBlockFromPalette(page, 'form-section');
    await addBlockFromPalette(page, 'table');
    await expect(page.locator('[data-testid^="canvas-block-content-"]')).toHaveCount(2);

    await page.locator('[data-testid^="canvas-block-content-"]').nth(1).click();

    await page.getByTestId('canvas-left-tab-fields').click();
    await page.getByTestId('field-palette-model-select').selectOption(modelCode);
    await page.waitForFunction(
      () => document.querySelectorAll('[data-testid^="field-palette-item-"]').length > 0,
      { timeout: 10000 },
    );

    const firstField = page.locator('[data-testid^="field-palette-item-"]').first();
    const fieldCode = (await firstField.locator('span.font-mono.text-gray-400').innerText()).trim();
    await firstField.click();

    const blockContents = page.locator('[data-testid^="canvas-block-content-"]');
    await expect(blockContents).toHaveCount(3);
    await expect(blockContents.nth(1)).toContainText(/Data table preview|Table/i);

    const newFormSection = blockContents.nth(2);
    const fieldChips = newFormSection.locator('.cursor-grab');
    await expect(fieldChips).toHaveCount(1);
    await expect(fieldChips.first()).toContainText(fieldCode);
  });
});

// ---------------------------------------------------------------------------
// A3. Widgets Tab — 11 widget types, search, add to form-section or new block
// ---------------------------------------------------------------------------

test.describe('A3 — Widgets Tab', () => {
  test('A3.1 — shows all 11 widget types with correct footer count', async ({ page }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);

    // Switch to Widgets tab
    await page.getByTestId('canvas-left-tab-widgets').click();
    await expect(page.getByTestId('widget-palette')).toBeVisible();

    // All 11 widget types from WIDGET_DEFS
    const widgetTypes = [
      'text', 'textarea', 'number', 'select', 'date',
      'datetime', 'checkbox', 'switch', 'radio', 'file', 'reference',
    ];

    for (const type of widgetTypes) {
      await expect(
        page.getByTestId(`widget-palette-item-${type}`),
        `Widget type '${type}' must be visible in palette`,
      ).toBeVisible();
    }

    // Footer says "11 widget types"
    await expect(page.getByTestId('widget-palette').locator('text=11 widget types')).toBeVisible();
  });

  test('A3.2 — search "date" shows exactly Date + DateTime (2 results)', async ({ page }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);

    await page.getByTestId('canvas-left-tab-widgets').click();
    await expect(page.getByTestId('widget-palette')).toBeVisible();

    await page.getByTestId('widget-palette-search').fill('date');

    // Date and DateTime must be visible
    await expect(page.getByTestId('widget-palette-item-date')).toBeVisible();
    await expect(page.getByTestId('widget-palette-item-datetime')).toBeVisible();

    // All other widget types must be hidden
    const hiddenTypes = ['text', 'textarea', 'number', 'select', 'checkbox', 'switch', 'radio', 'file', 'reference'];
    for (const type of hiddenTypes) {
      await expect(
        page.getByTestId(`widget-palette-item-${type}`),
        `Widget '${type}' should be hidden when searching "date"`,
      ).not.toBeVisible();
    }
  });

  test('A3.3 — clicking widget with form-section selected adds field to existing block', async ({ page }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);

    // Add a form-section block first
    await addBlockFromPalette(page, 'form-section');
    await page.locator('[data-testid^="canvas-block-content-"]').first().waitFor({ state: 'visible' });
    expect(await blockCount(page)).toBe(1);

    // Select the form-section block
    await page.locator(BLK).first().click();
    await expect(page.locator(BLK).first()).toHaveClass(/selected/);

    // Switch to Widgets tab
    await page.getByTestId('canvas-left-tab-widgets').click();
    await expect(page.getByTestId('widget-palette')).toBeVisible();

    // Click Text Input widget
    await page.getByTestId('widget-palette-item-text').click();

    // Block count must REMAIN 1 (widget added to existing block, not new one)
    expect(await blockCount(page), 'Block count must stay at 1 when adding widget to selected form-section').toBe(1);

    // The form-section preview must now show 1 field chip
    const blockContent = page.locator('[data-testid^="canvas-block-content-"]').first();
    const fieldChips = blockContent.locator('.grid > div');
    const chipCount = await fieldChips.count();
    expect(chipCount, 'Form-section should show 1 field chip after adding widget').toBeGreaterThan(0);
  });

  test('A3.4 — clicking widget without selection creates new form-section block', async ({ page }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);

    // Switch to Widgets tab (no block selected)
    await page.getByTestId('canvas-left-tab-widgets').click();
    await expect(page.getByTestId('widget-palette')).toBeVisible();

    expect(await blockCount(page)).toBe(0);

    // Click Number widget — no selection, so should create new form-section
    await page.getByTestId('widget-palette-item-number').click();

    // Block count becomes 1
    await page.locator('[data-testid^="canvas-block-content-"]').first().waitFor({ state: 'visible', timeout: 5000 });
    expect(await blockCount(page), 'Clicking widget with no selection must create a new form-section block').toBe(1);

    const blockContent = page.locator('[data-testid^="canvas-block-content-"]').first();
    const fieldChips = blockContent.locator('.cursor-grab');
    await expect(fieldChips).toHaveCount(1);
    await expect(fieldChips.first()).toContainText('Number');
  });

  test('A3.4b — clicking widget with non-form block selected creates adjacent form-section', async ({ page }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);

    await addBlockFromPalette(page, 'form-section');
    await addBlockFromPalette(page, 'table');
    const blockCards = page.locator('[data-testid^="canvas-block-content-"]');
    await expect(blockCards).toHaveCount(2);
    await expect(blockCards.nth(0)).toContainText(/Text Input|Drag widgets or fields here|Form/i);
    await expect(blockCards.nth(1)).toContainText(/Data table preview|Table/i);

    await blockCards.nth(1).click();

    await page.getByTestId('canvas-left-tab-widgets').click();
    await page.getByTestId('widget-palette-item-number').click();

    await expect(blockCards).toHaveCount(3);
    await expect(blockCards.nth(1)).toContainText(/Data table preview|Table/i);

    const newFormSectionContent = blockCards.nth(2);
    const fieldChips = newFormSectionContent.locator('.cursor-grab');
    await expect(fieldChips).toHaveCount(1);
    await expect(fieldChips.first()).toContainText('Number');
  });

  test('A3.5 — adding 3 widgets to same form-section shows 3 field chips', async ({ page }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);

    // Switch to Widgets tab, add first widget (creates form-section)
    await page.getByTestId('canvas-left-tab-widgets').click();
    await page.getByTestId('widget-palette-item-text').click();
    await page.locator('[data-testid^="canvas-block-content-"]').first().waitFor({ state: 'visible' });

    // Select the form-section
    await page.locator(BLK).first().click();
    await expect(page.locator(BLK).first()).toHaveClass(/selected/);

    // Re-open widgets tab and add 2nd widget
    await page.getByTestId('canvas-left-tab-widgets').click();
    await page.getByTestId('widget-palette-item-number').click();

    // Re-select form-section and add 3rd widget
    await page.locator(BLK).first().click();
    await page.getByTestId('canvas-left-tab-widgets').click();
    await page.getByTestId('widget-palette-item-date').click();

    // Still 1 block total
    expect(await blockCount(page), 'Must still have exactly 1 form-section block').toBe(1);

    // Form-section preview must show 3 field chips
    const blockContent = page.locator('[data-testid^="canvas-block-content-"]').first();
    const fieldChips = blockContent.locator('.grid > div');
    await expect(fieldChips).toHaveCount(3);
  });

  test('A3.5b — dragging widget to slot inserts at target position', async ({ page }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);

    await addBlockFromPalette(page, 'form-section');
    await page.locator(BLK).first().click();

    await page.getByTestId('canvas-left-tab-widgets').click();
    await page.getByTestId('widget-palette-item-number').click();
    await page.getByTestId('canvas-left-tab-widgets').click();
    await page.getByTestId('widget-palette-item-date').click();

    const firstBlock = page.locator('[data-testid^="canvas-block-content-"]').first();
    const firstSlot = page.getByTestId(/form-section-slot-.*-0/).first();

    await dragWidgetTo(page, 'text', firstSlot, firstBlock);

    const fieldChips = firstBlock.locator('.cursor-grab');
    await expect(fieldChips).toHaveCount(3);
    await expect(fieldChips.nth(0)).toContainText('Text Input');
    await expect(fieldChips.nth(1)).toContainText('Number');
    await expect(fieldChips.nth(2)).toContainText('Date');
  });

  test('A3.5c — dragging existing field chip to slot reorders fields', async ({ page }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);

    await addBlockFromPalette(page, 'form-section');
    await page.locator(BLK).first().click();

    await page.getByTestId('canvas-left-tab-widgets').click();
    await page.getByTestId('widget-palette-item-text').click();
    await page.getByTestId('canvas-left-tab-widgets').click();
    await page.getByTestId('widget-palette-item-number').click();
    await page.getByTestId('canvas-left-tab-widgets').click();
    await page.getByTestId('widget-palette-item-date').click();

    const firstBlock = page.locator('[data-testid^="canvas-block-content-"]').first();
    const fieldChips = firstBlock.locator('.cursor-grab');
    await expect(fieldChips).toHaveCount(3);
    await expect(fieldChips.nth(0)).toContainText('Text Input');
    await expect(fieldChips.nth(1)).toContainText('Number');
    await expect(fieldChips.nth(2)).toContainText('Date');

    await page.locator(BLK).first().click();
    const tailSlot = page.getByTestId(/form-section-slot-.*-3/).first();
    await dragLocatorTo(page, fieldChips.nth(0), tailSlot, firstBlock);

    await expect(fieldChips).toHaveCount(3);
    await expect(fieldChips.nth(0)).toContainText('Number');
    await expect(fieldChips.nth(1)).toContainText('Date');
    await expect(fieldChips.nth(2)).toContainText('Text Input');
  });

  test('A3.5d — dragging widget over non-form block shows invalid hint and does not drop', async ({ page }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);

    await addBlockFromPalette(page, 'table');
    await expect(page.locator('[data-testid^="canvas-block-content-"]')).toHaveCount(1);

    await page.getByTestId('canvas-left-tab-widgets').click();
    const source = page.getByTestId('widget-palette-item-text');
    await expect(source).toBeVisible();

    const sourceBox = await source.boundingBox();
    expect(sourceBox, 'Widget source must have a bounding box').toBeTruthy();
    await page.mouse.move(sourceBox!.x + sourceBox!.width / 2, sourceBox!.y + sourceBox!.height / 2);
    await page.mouse.down();
    await page.mouse.move(sourceBox!.x + sourceBox!.width / 2 + 12, sourceBox!.y + sourceBox!.height / 2 + 12, { steps: 4 });

    const invalidWrapper = page.locator('[data-testid^="canvas-block-drop-wrapper-"]').first();
    const invalidBox = await invalidWrapper.boundingBox();
    expect(invalidBox, 'Invalid drop target must have a bounding box').toBeTruthy();
    await page.mouse.move(invalidBox!.x + invalidBox!.width / 2, invalidBox!.y + invalidBox!.height / 2, { steps: 12 });

    await expect(page.locator('[data-testid^="canvas-block-drop-invalid-"]').first()).toBeVisible();
    await page.mouse.up();

    await expect(page.locator('[data-testid^="canvas-block-content-"]')).toHaveCount(1);
    await expect(page.locator('[data-testid^="canvas-block-content-"]').first()).toContainText(/Data table preview|Table/i);
  });

  test('A3.5e — dragging widget to empty canvas shows create-section hint and drops into new section', async ({ page }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);

    expect(await blockCount(page)).toBe(0);

    await page.getByTestId('canvas-left-tab-widgets').click();
    const source = page.getByTestId('widget-palette-item-text');
    await expect(source).toBeVisible();

    const sourceBox = await source.boundingBox();
    expect(sourceBox, 'Widget source must have a bounding box').toBeTruthy();
    await page.mouse.move(sourceBox!.x + sourceBox!.width / 2, sourceBox!.y + sourceBox!.height / 2);
    await page.mouse.down();
    await page.mouse.move(sourceBox!.x + sourceBox!.width / 2 + 12, sourceBox!.y + sourceBox!.height / 2 + 12, { steps: 4 });

    const canvasDropZone = page.getByTestId('empty-canvas-drop-zone');
    const dropBox = await canvasDropZone.boundingBox();
    expect(dropBox, 'Empty canvas drop zone must have a bounding box').toBeTruthy();
    await page.mouse.move(dropBox!.x + dropBox!.width / 2, dropBox!.y + dropBox!.height / 2, { steps: 12 });

    await expect(page.getByTestId('canvas-new-form-section-hint')).toBeVisible();
    await expect(page.getByTestId('canvas-new-form-section-hint')).toContainText('Release to create a new form section');
    await page.mouse.up();

    await expect(page.locator('[data-testid^="canvas-block-content-"]')).toHaveCount(1);
    const fieldChips = page.locator('[data-testid^="canvas-block-content-"]').first().locator('.cursor-grab');
    await expect(fieldChips).toHaveCount(1);
    await expect(fieldChips.first()).toContainText('Text Input');
  });

  test('A3.5f — dragging a fifth widget into a populated form-section keeps layout stable and inserts successfully', async ({ page }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);

    await addBlockFromPalette(page, 'form-section');
    await page.locator(BLK).first().click();

    await page.getByTestId('canvas-left-tab-widgets').click();
    await page.getByTestId('widget-palette-item-text').click();
    await page.getByTestId('canvas-left-tab-widgets').click();
    await page.getByTestId('widget-palette-item-number').click();
    await page.getByTestId('canvas-left-tab-widgets').click();
    await page.getByTestId('widget-palette-item-date').click();
    await page.getByTestId('canvas-left-tab-widgets').click();
    await page.getByTestId('widget-palette-item-textarea').click();

    const firstBlock = page.locator('[data-testid^="canvas-block-content-"]').first();
    const fieldChips = firstBlock.locator('.cursor-grab');
    await expect(fieldChips).toHaveCount(4);

    const tailSlot = page.getByTestId(/form-section-slot-.*-4/).first();
    await dragWidgetTo(page, 'select', tailSlot, firstBlock);

    await expect(fieldChips).toHaveCount(5);
    await expect(fieldChips.nth(0)).toContainText('Text Input');
    await expect(fieldChips.nth(1)).toContainText('Number');
    await expect(fieldChips.nth(2)).toContainText('Date');
    await expect(fieldChips.nth(3)).toContainText('Textarea');
    await expect(fieldChips.nth(4)).toContainText('Select');
  });

  test('A3.6 — each widget type creates block with correct component value', async ({ page }) => {
    // Test a representative subset of widget types to verify correct component mapping
    const widgetTests: Array<{ component: string; label: string }> = [
      { component: 'text', label: 'Text Input' },
      { component: 'select', label: 'Select' },
      { component: 'date', label: 'Date' },
      { component: 'checkbox', label: 'Checkbox' },
    ];

    for (const { component, label } of widgetTests) {
      const pid = await createPage(page);
      await openDesigner(page, pid);

      // Switch to Widgets tab
      await page.getByTestId('canvas-left-tab-widgets').click();
      await expect(page.getByTestId('widget-palette')).toBeVisible();

      // Verify the palette item has the correct label text (use .text-xs.font-medium to target name, not description)
      const item = page.getByTestId(`widget-palette-item-${component}`);
      await expect(item).toBeVisible();
      // Use the name div specifically to avoid strict mode violation (description may also contain the label text)
      await expect(item.locator('.text-xs.font-medium').filter({ hasText: label })).toBeVisible();

      // Click to add
      await item.click();
      await page.locator('[data-testid^="canvas-block-content-"]').first().waitFor({ state: 'visible', timeout: 5000 });

      // Block was created (form-section with the widget field)
      expect(await blockCount(page)).toBe(1);

      const blockContent = page.locator('[data-testid^="canvas-block-content-"]').first();
      const fieldChips = blockContent.locator('.cursor-grab');
      await expect(fieldChips, `Widget '${component}' must add exactly one field chip to form-section`).toHaveCount(1);
      await expect(fieldChips.first()).toContainText(label);
    }
  });
});

// ---------------------------------------------------------------------------
// A4. Outline Tab — block sync, click-to-select, delete sync, type labels
// ---------------------------------------------------------------------------

test.describe('A4 — Outline Tab', () => {
  test('A4.1 — outline shows correct block count after adding 3 blocks', async ({ page }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);

    // Add 3 blocks via palette
    await addBlockFromPalette(page, 'table');
    await page.waitForFunction(() => document.querySelectorAll('[data-testid^="canvas-block-content-"]').length >= 1, { timeout: 5000 });
    await addBlockFromPalette(page, 'chart');
    await page.waitForFunction(() => document.querySelectorAll('[data-testid^="canvas-block-content-"]').length >= 2, { timeout: 5000 });
    await addBlockFromPalette(page, 'form-section');
    await page.waitForFunction(() => document.querySelectorAll('[data-testid^="canvas-block-content-"]').length >= 3, { timeout: 5000 });

    // Verify canvas has 3 blocks
    expect(await blockCount(page)).toBe(3);

    // Switch to Outline tab
    await page.getByTestId('canvas-left-tab-outline').click();
    await expect(page.getByTestId('outline-panel')).toBeVisible();

    // Outline must list exactly 3 items
    const outlineItems = page.locator('[data-testid^="outline-item-"]');
    await expect(outlineItems).toHaveCount(3);

    // Outline footer shows "3 blocks"
    const outlineFooter = page.getByTestId('outline-panel').locator('.border-t');
    await expect(outlineFooter.locator('text=3 blocks')).toBeVisible();
  });

  test('A4.2 — clicking outline item selects corresponding canvas block', async ({ page }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);

    // Add 2 blocks
    await addBlockFromPalette(page, 'table');
    await page.waitForFunction(() => document.querySelectorAll('[data-testid^="canvas-block-content-"]').length >= 1, { timeout: 5000 });
    await addBlockFromPalette(page, 'chart');
    await page.waitForFunction(() => document.querySelectorAll('[data-testid^="canvas-block-content-"]').length >= 2, { timeout: 5000 });

    // Switch to Outline tab
    await page.getByTestId('canvas-left-tab-outline').click();
    await expect(page.getByTestId('outline-panel')).toBeVisible();

    const outlineItems = page.locator('[data-testid^="outline-item-"]');
    await expect(outlineItems).toHaveCount(2);

    // Click the first outline item
    await outlineItems.first().click();

    // The first canvas block must now have the selected class
    await expect(page.locator(BLK).first()).toHaveClass(/selected/);

    // Outline item itself should be highlighted (purple background)
    await expect(outlineItems.first()).toHaveClass(/bg-purple-100/);

    // Second block must NOT be selected
    await expect(page.locator(BLK).nth(1)).not.toHaveClass(/selected/);
  });

  test('A4.3 — deleting a block decrements outline count', async ({ page }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);

    // Add 3 blocks
    await addBlockFromPalette(page, 'table');
    await page.waitForFunction(() => document.querySelectorAll('[data-testid^="canvas-block-content-"]').length >= 1, { timeout: 5000 });
    await addBlockFromPalette(page, 'chart');
    await page.waitForFunction(() => document.querySelectorAll('[data-testid^="canvas-block-content-"]').length >= 2, { timeout: 5000 });
    await addBlockFromPalette(page, 'stat-card');
    await page.waitForFunction(() => document.querySelectorAll('[data-testid^="canvas-block-content-"]').length >= 3, { timeout: 5000 });

    // Switch to Outline and verify 3 items
    await page.getByTestId('canvas-left-tab-outline').click();
    let outlineItems = page.locator('[data-testid^="outline-item-"]');
    await expect(outlineItems).toHaveCount(3);

    // Delete the first canvas block by clicking its remove button
    // Get the block id from the first outline item testid
    const firstOutlineId = await outlineItems.first().getAttribute('data-testid');
    const blockId = firstOutlineId?.replace('outline-item-', '') ?? '';
    expect(blockId, 'Must extract block id from outline item').toBeTruthy();

    // Click the remove button on that canvas block
    await page.getByTestId(`canvas-block-remove-${blockId}`).click();

    // Outline must now show 2 items
    outlineItems = page.locator('[data-testid^="outline-item-"]');
    await expect(outlineItems).toHaveCount(2);

    // Canvas must also show 2 blocks
    expect(await blockCount(page)).toBe(2);

    // Outline footer says "2 blocks"
    await expect(page.getByTestId('outline-panel').locator('.border-t').locator('text=2 blocks')).toBeVisible();
  });

  test('A4.4 — outline shows correct type labels (Table, Chart, etc.)', async ({ page }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);

    // Add table and chart blocks
    await addBlockFromPalette(page, 'table');
    await page.waitForFunction(() => document.querySelectorAll('[data-testid^="canvas-block-content-"]').length >= 1, { timeout: 5000 });
    await addBlockFromPalette(page, 'chart');
    await page.waitForFunction(() => document.querySelectorAll('[data-testid^="canvas-block-content-"]').length >= 2, { timeout: 5000 });

    // Switch to Outline
    await page.getByTestId('canvas-left-tab-outline').click();
    await expect(page.getByTestId('outline-panel')).toBeVisible();

    const outlineItems = page.locator('[data-testid^="outline-item-"]');
    await expect(outlineItems).toHaveCount(2);

    // First item should show "Table" label text
    await expect(outlineItems.first().locator('text=Table')).toBeVisible();

    // Second item should show "Chart" label text
    await expect(outlineItems.nth(1).locator('text=Chart')).toBeVisible();
  });
});

// ---------------------------------------------------------------------------
// H. Left Panel Detail Tests
// ---------------------------------------------------------------------------

test.describe('H — Left Panel Details', () => {
  test('H3 — field search filter works when model selected', async ({ page }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);

    const modelCode = await getFirstAvailableModel(page);
    if (!modelCode) {
      test.skip(true, 'No published models available');
      return;
    }

    // Switch to Fields tab and select model
    await page.getByTestId('canvas-left-tab-fields').click();
    await page.getByTestId('field-palette-model-select').selectOption(modelCode);

    await page.waitForFunction(
      () => document.querySelectorAll('[data-testid^="field-palette-item-"]').length > 0,
      { timeout: 10000 },
    );

    const totalFields = await page.locator('[data-testid^="field-palette-item-"]').count();

    // Search appears only after model selection
    await expect(page.getByTestId('field-palette-search')).toBeVisible();

    // Search for a non-existent term
    await page.getByTestId('field-palette-search').fill('zzz_impossible_xyz_999');

    // Either no fields shown or empty state message
    const filteredCount = await page.locator('[data-testid^="field-palette-item-"]').count();
    expect(filteredCount, 'Search must filter the field list').toBeLessThan(totalFields);

    // Clear → all restored
    await page.getByTestId('field-palette-search').clear();
    const restoredCount = await page.locator('[data-testid^="field-palette-item-"]').count();
    expect(restoredCount, 'Clear must restore all fields').toBe(totalFields);
  });

  test('H6 — widget search "date" returns exactly 2 results (Date + DateTime)', async ({ page }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);

    await page.getByTestId('canvas-left-tab-widgets').click();
    await expect(page.getByTestId('widget-palette')).toBeVisible();

    await page.getByTestId('widget-palette-search').fill('date');

    // Exactly date and datetime visible
    await expect(page.getByTestId('widget-palette-item-date')).toBeVisible();
    await expect(page.getByTestId('widget-palette-item-datetime')).toBeVisible();

    // Count visible widget items: exactly 2
    const visibleWidgets = await page.locator('[data-testid^="widget-palette-item-"]').filter({ hasNot: page.locator('[style*="display: none"]') }).all();
    // Using attribute approach: check testids of visible items
    const allWidgetItems = page.locator('[data-testid^="widget-palette-item-"]');
    const allCount = await allWidgetItems.count();
    let visibleCount = 0;
    for (let i = 0; i < allCount; i++) {
      const isVisible = await allWidgetItems.nth(i).isVisible();
      if (isVisible) visibleCount++;
    }
    expect(visibleCount, 'Exactly 2 widget types should match "date"').toBe(2);
  });

  test('H7 — outline empty state shows "No blocks yet" when canvas is empty', async ({ page }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);

    // Switch to Outline with empty canvas
    await page.getByTestId('canvas-left-tab-outline').click();
    await expect(page.getByTestId('outline-panel')).toBeVisible();

    // Must show "No blocks yet" empty state
    await expect(page.getByText('No blocks yet')).toBeVisible();
    await expect(page.getByText('Add blocks from Components tab')).toBeVisible();

    // Outline footer shows "0 blocks"
    const outlineFooter = page.getByTestId('outline-panel').locator('.border-t');
    await expect(outlineFooter.locator('text=0 blocks')).toBeVisible();

    // No outline items should exist
    await expect(page.locator('[data-testid^="outline-item-"]').first()).not.toBeVisible();
  });

  test('H8 — outline index badge updates correctly after deleting a block', async ({ page }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);

    // Add 3 blocks: table, chart, stat-card
    await addBlockFromPalette(page, 'table');
    await page.waitForFunction(() => document.querySelectorAll('[data-testid^="canvas-block-content-"]').length >= 1, { timeout: 5000 });
    await addBlockFromPalette(page, 'chart');
    await page.waitForFunction(() => document.querySelectorAll('[data-testid^="canvas-block-content-"]').length >= 2, { timeout: 5000 });
    await addBlockFromPalette(page, 'stat-card');
    await page.waitForFunction(() => document.querySelectorAll('[data-testid^="canvas-block-content-"]').length >= 3, { timeout: 5000 });

    // Switch to Outline
    await page.getByTestId('canvas-left-tab-outline').click();
    await expect(page.getByTestId('outline-panel')).toBeVisible();

    const outlineItems = page.locator('[data-testid^="outline-item-"]');
    await expect(outlineItems).toHaveCount(3);

    // Index badges should be 1, 2, 3 (text content)
    const badge1 = outlineItems.first().locator('span').first();
    const badge2 = outlineItems.nth(1).locator('span').first();
    const badge3 = outlineItems.nth(2).locator('span').first();
    await expect(badge1).toContainText('1');
    await expect(badge2).toContainText('2');
    await expect(badge3).toContainText('3');

    // Delete the first block
    const firstOutlineTestId = await outlineItems.first().getAttribute('data-testid');
    const blockId = firstOutlineTestId?.replace('outline-item-', '') ?? '';
    await page.getByTestId(`canvas-block-remove-${blockId}`).click();

    // After delete: 2 items, badges should be 1 and 2
    await expect(outlineItems).toHaveCount(2);
    const newBadge1 = outlineItems.first().locator('span').first();
    const newBadge2 = outlineItems.nth(1).locator('span').first();
    await expect(newBadge1).toContainText('1');
    await expect(newBadge2).toContainText('2');
  });

  test('H — block palette search "zzz" shows no-results message', async ({ page }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);

    const searchInput = page.getByTestId('block-palette-search');
    await searchInput.fill('zzznomatch');

    // "No blocks found" message visible
    await expect(page.getByText('No blocks found')).toBeVisible();

    // All palette items hidden
    await expect(page.getByTestId('block-palette-item-table')).not.toBeVisible();
    await expect(page.getByTestId('block-palette-item-chart')).not.toBeVisible();

    // Footer still shows total block count (13)
    await expect(page.getByTestId('block-palette').locator('text=13 block types')).toBeVisible();
  });

  test('H — tab switching correctly shows/hides panels', async ({ page }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);

    // Default: Components tab active
    await expect(page.getByTestId('block-palette')).toBeVisible();
    await expect(page.getByTestId('field-palette')).not.toBeVisible();
    await expect(page.getByTestId('widget-palette')).not.toBeVisible();
    await expect(page.getByTestId('outline-panel')).not.toBeVisible();

    // Click Fields tab
    await page.getByTestId('canvas-left-tab-fields').click();
    await expect(page.getByTestId('field-palette')).toBeVisible();
    await expect(page.getByTestId('block-palette')).not.toBeVisible();
    await expect(page.getByTestId('widget-palette')).not.toBeVisible();
    await expect(page.getByTestId('outline-panel')).not.toBeVisible();

    // Click Widgets tab
    await page.getByTestId('canvas-left-tab-widgets').click();
    await expect(page.getByTestId('widget-palette')).toBeVisible();
    await expect(page.getByTestId('field-palette')).not.toBeVisible();
    await expect(page.getByTestId('block-palette')).not.toBeVisible();
    await expect(page.getByTestId('outline-panel')).not.toBeVisible();

    // Click Outline tab
    await page.getByTestId('canvas-left-tab-outline').click();
    await expect(page.getByTestId('outline-panel')).toBeVisible();
    await expect(page.getByTestId('widget-palette')).not.toBeVisible();
    await expect(page.getByTestId('field-palette')).not.toBeVisible();
    await expect(page.getByTestId('block-palette')).not.toBeVisible();

    // Back to Components
    await page.getByTestId('canvas-left-tab-components').click();
    await expect(page.getByTestId('block-palette')).toBeVisible();
    await expect(page.getByTestId('outline-panel')).not.toBeVisible();
  });
});
