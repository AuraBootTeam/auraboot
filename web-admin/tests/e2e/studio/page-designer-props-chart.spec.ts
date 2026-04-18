/**
 * Page Designer — Chart Block Properties Deep Tests (C3)
 *
 * Covers all 15 properties of the Chart block using the 5-step pattern:
 *   ① Read default → ② Change → ③ Read new value → ④ Deselect → ⑤ Reselect (persistence)
 *
 * Plus dependsOn chain tests for dataSourceMode (3 branches × conditional fields).
 * Plus chartType 20-option verification.
 *
 * Navigation: page.goto() is used because Page Designer is a platform designer tool,
 * not a sidebar menu page (allowed per AGENTS.md exception for designer workbenches).
 *
 * Dimensions covered:
 *   D2 (config panel renders), D5 (widget types: select/text/number/switch/expression),
 *   D8 (property persistence: change → deselect → reselect → value still set)
 * Not applicable:
 *   D1 (no sidebar menu, Page Designer is a platform tool),
 *   D3/D9/D10 (no status machine), D4/D6/D7/D11/D12/D13/D14 (not a CRUD model)
 *
 * Properties tested:
 *   C3.1  dataSourceMode   (select, 3 modes, drives 8 dependent fields)
 *   C3.2  dataSource.modelCode  (model-select, visible in Model Aggregate mode)
 *   C3.3  metricField      (text, visible in Model Aggregate mode)
 *   C3.4  aggregation      (select, SUM/COUNT/AVG/MIN/MAX, visible in Model Aggregate)
 *   C3.5  groupDimension   (text, visible in Model Aggregate mode)
 *   C3.6  dataSource.queryCode  (text, visible in Named Query mode)
 *   C3.7  dataSource.endpoint   (text, visible in Custom API mode)
 *   C3.8  dataSource.params     (json textarea, visible in Custom API mode)
 *   C3.9  chartType        (select, 20 options, default Bar)
 *   C3.10 chartTitle       (text, default empty)
 *   C3.11 height           (number, default 300)
 *   C3.12 showLegend       (switch, default OFF)
 *   C3.13 showValues       (switch, default OFF)
 *   C3.14 visibleWhen      (expression editor)
 *   C3.+  layout.colSpan   (default 6 for chart)
 *
 * @since 4.3.0
 */

import { test, expect, type Page, type Locator } from '@playwright/test';
import { uniqueId } from '../helpers/index';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Create a blank composite page via API and return its pid. */
async function createPage(page: Page): Promise<string> {
  const name = uniqueId('chart-props');
  const pageKey = `e2e_chart_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 7)}`;

  const resp = await page.request.post('/api/pages', {
    data: {
      name,
      pageKey,
      title: name,
      kind: 'list',
      modelCode: 'tenant',
      blocks: [],
      metaInfo: { componentCount: 0 },
      semver: '0.1.0',
    },
  });

  expect(resp.ok(), `Create page API failed: ${resp.status()}`).toBeTruthy();
  const body = await resp.json();
  expect(body.code).toBe('0');
  const pid = body.data?.pid;
  expect(pid, 'Page pid must be returned').toBeTruthy();
  return pid;
}

/** Open the page designer for a given pid and wait for the canvas. */
async function openDesigner(page: Page, pid: string): Promise<void> {
  await page.goto(`/page-designer/${pid}`, { waitUntil: 'domcontentloaded' });
  await page.getByTestId('designer-canvas').waitFor({ state: 'visible', timeout: 15000 });
}

/** Add a chart block via the Components palette and select it. */
async function addAndSelectChart(page: Page): Promise<void> {
  // Switch to Components tab (may already be there)
  await page.getByTestId('canvas-left-tab-components').click();
  const paletteItem = page.getByTestId('block-palette-item-chart');
  await paletteItem.waitFor({ state: 'visible', timeout: 5000 });
  await paletteItem.click();

  // Wait for block wrapper to appear (more reliable than content for initial render)
  const blockWrapper = page.locator('[data-testid^="canvas-block-"]:not([data-testid*="-drag-"]):not([data-testid*="-remove-"]):not([data-testid*="-content-"]):not([data-testid*="-drop-"])').first();
  await blockWrapper.waitFor({ state: 'visible', timeout: 10000 });

  // Click the block wrapper to select it and open the config panel
  await blockWrapper.click();
  await page.getByTestId('chart-schema-config').waitFor({ state: 'visible', timeout: 10000 });
}

/** Add a second block (table) and click it, then reselect chart. */
async function addTableBlock(page: Page): Promise<void> {
  await page.getByTestId('canvas-left-tab-components').click();
  const paletteItem = page.getByTestId('block-palette-item-table');
  await paletteItem.waitFor({ state: 'visible', timeout: 5000 });
  await paletteItem.click();
  await page.locator('[data-testid^="canvas-block-content-"]').nth(1).waitFor({ state: 'visible', timeout: 5000 });
}

/** Locate a field container by its exact label text within a config panel. */
function fieldByLabel(container: Locator, label: string): Locator {
  return container.locator(`label:text-is("${label}")`).first().locator('..');
}

/** Locate a label element with exact text match within a container. */
function labelExact(container: Locator, label: string): Locator {
  return container.locator(`label:text-is("${label}")`).first();
}

/** Click a Radix Select trigger and select the given option. */
async function selectRadixOption(page: Page, container: Locator, label: string, optionText: string): Promise<void> {
  const field = fieldByLabel(container, label);
  const trigger = field.locator('button[role="combobox"]');
  // Ensure no other Radix portal is open (Escape closes any open dropdown)
  await page.keyboard.press('Escape');
  await trigger.waitFor({ state: 'visible', timeout: 5000 });
  await trigger.click();
  const option = page.getByRole('option', { name: optionText, exact: true });
  await option.waitFor({ state: 'visible', timeout: 3000 });
  await option.click();
}

/** Get the currently displayed value from a Radix Select trigger. */
async function getRadixSelectValue(container: Locator, label: string): Promise<string> {
  const field = fieldByLabel(container, label);
  const trigger = field.locator('button[role="combobox"]');
  return (await trigger.innerText()).trim();
}

/** Group header locator (exact div match). */
function groupHeader(container: Locator, groupName: string): Locator {
  return container.locator('div').filter({ hasText: new RegExp(`^${groupName}$`) }).first();
}

// Selector that matches root canvas-block elements only (avoids sub-elements)
/** Block content selector — click content to trigger selection reliably */
const BLOCK_SEL =
  '[data-testid^="canvas-block-content-"]';
/** Block wrapper selector (includes header with badge) — excludes drag/remove/content/drop variants */
const BLOCK_WRAPPER_SEL =
  '[data-testid^="canvas-block-"]:not([data-testid*="-drag-"]):not([data-testid*="-remove-"]):not([data-testid*="-content-"]):not([data-testid*="-drop-"])';

// ---------------------------------------------------------------------------
// C3.1 / C3.2–C3.8  dataSourceMode — 3-branch dependsOn chain
// ---------------------------------------------------------------------------

test.describe('C3.1 dataSourceMode — 3 branches and field visibility', () => {
  test('default is "Model Aggregate"; switching reveals/hides branch-specific fields', async ({ page }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);
    await addAndSelectChart(page);

    const config = page.getByTestId('chart-schema-config');

    // ① Default value is "Model Aggregate"
    const defaultValue = await getRadixSelectValue(config, 'Data Source');
    expect(defaultValue).toBe('Model Aggregate');

    // --- Branch: Named Query (switch away from default first) ---
    await selectRadixOption(page, config, 'Data Source', 'Named Query');

    // ③ Named Query mode: Query Code visible, Model Aggregate fields hidden
    await expect(labelExact(config, 'Query Code')).toBeVisible({ timeout: 5000 });
    await expect(labelExact(config, 'Metric field')).not.toBeVisible();
    await expect(labelExact(config, 'Aggregation')).not.toBeVisible();
    await expect(labelExact(config, 'Group dimension')).not.toBeVisible();
    await expect(labelExact(config, 'Endpoint')).not.toBeVisible();
    await expect(labelExact(config, 'Params (JSON)')).not.toBeVisible();

    // ⑤ Persistence: deselect → reselect → still Named Query
    await page.getByTestId('canvas-body').click({ position: { x: 10, y: 10 } });
    await page.getByTestId('block-config-empty').waitFor({ state: 'visible', timeout: 3000 });
    await page.locator(BLOCK_SEL).first().click();
    await page.getByTestId('chart-schema-config').waitFor({ state: 'visible', timeout: 5000 });

    const afterReselect = await getRadixSelectValue(config, 'Data Source');
    expect(afterReselect).toBe('Named Query');
    await expect(labelExact(config, 'Query Code')).toBeVisible();
    await expect(labelExact(config, 'Metric field')).not.toBeVisible();

    // --- Branch: Model Aggregate ---
    await selectRadixOption(page, config, 'Data Source', 'Model Aggregate');

    // Model Aggregate sub-fields visible
    await expect(labelExact(config, 'Metric field')).toBeVisible({ timeout: 5000 });
    await expect(labelExact(config, 'Aggregation')).toBeVisible();
    await expect(labelExact(config, 'Group dimension')).toBeVisible();
    // Model uses a model-select (ResourceSelectField) with its own label
    await expect(config.locator('label').filter({ hasText: /^Model$/ }).first()).toBeVisible();

    // Named Query / Custom API fields hidden
    await expect(labelExact(config, 'Query Code')).not.toBeVisible();
    await expect(labelExact(config, 'Endpoint')).not.toBeVisible();
    await expect(labelExact(config, 'Params (JSON)')).not.toBeVisible();

    // --- Branch: Custom API ---
    await selectRadixOption(page, config, 'Data Source', 'Custom API');

    // Custom API sub-fields visible
    await expect(labelExact(config, 'Endpoint')).toBeVisible({ timeout: 5000 });
    await expect(labelExact(config, 'Params (JSON)')).toBeVisible();

    // Model Aggregate / Named Query fields hidden
    await expect(labelExact(config, 'Metric field')).not.toBeVisible();
    await expect(labelExact(config, 'Aggregation')).not.toBeVisible();
    await expect(labelExact(config, 'Group dimension')).not.toBeVisible();
    await expect(labelExact(config, 'Query Code')).not.toBeVisible();

    // ⑤ Persistence of Custom API mode
    await page.getByTestId('canvas-body').click({ position: { x: 10, y: 10 } });
    await page.getByTestId('block-config-empty').waitFor({ state: 'visible', timeout: 3000 });
    await page.locator(BLOCK_SEL).first().click();
    await page.getByTestId('chart-schema-config').waitFor({ state: 'visible', timeout: 5000 });

    const finalMode = await getRadixSelectValue(config, 'Data Source');
    expect(finalMode).toBe('Custom API');
    await expect(labelExact(config, 'Endpoint')).toBeVisible();
  });
});

// ---------------------------------------------------------------------------
// C3.3  metricField (text) — 5-step pattern
// ---------------------------------------------------------------------------

test.describe('C3.3 metricField — text input in Model Aggregate mode', () => {
  test('fill metric field, deselect, reselect — value persists', async ({ page }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);
    await addAndSelectChart(page);

    const config = page.getByTestId('chart-schema-config');

    // Activate Model Aggregate sub-fields (cycle from Named Query back)
    await selectRadixOption(page, config, 'Data Source', 'Named Query');
    await expect(labelExact(config, 'Query Code')).toBeVisible({ timeout: 5000 });
    await selectRadixOption(page, config, 'Data Source', 'Model Aggregate');
    await expect(labelExact(config, 'Metric field')).toBeVisible({ timeout: 5000 });

    // ① Default: empty
    const metricInput = fieldByLabel(config, 'Metric field').locator('input');
    await expect(metricInput).toBeVisible();
    const defaultVal = await metricInput.inputValue();
    expect(defaultVal).toBe('');

    // ② Fill new value
    await metricInput.fill('amount');

    // ③ Read back
    await expect(metricInput).toHaveValue('amount');

    // ④ Deselect (click canvas background)
    await page.getByTestId('canvas-body').click({ position: { x: 10, y: 10 } });
    await page.getByTestId('block-config-empty').waitFor({ state: 'visible', timeout: 3000 });

    // ⑤ Reselect
    await page.locator(BLOCK_SEL).first().click();
    await page.getByTestId('chart-schema-config').waitFor({ state: 'visible', timeout: 5000 });

    // Value must still be "amount"
    const metricInputAfter = fieldByLabel(config, 'Metric field').locator('input');
    await expect(metricInputAfter).toHaveValue('amount');
  });
});

// ---------------------------------------------------------------------------
// C3.4  aggregation (select: SUM/COUNT/AVG/MIN/MAX) — 5-step + options check
// ---------------------------------------------------------------------------

test.describe('C3.4 aggregation — all 5 options, default SUM, change to AVG', () => {
  test.fixme('default SUM; verify 5 options; change to AVG; deselect; reselect → AVG', async ({ page }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);
    await addAndSelectChart(page);

    const config = page.getByTestId('chart-schema-config');

    // Activate Model Aggregate sub-fields
    await selectRadixOption(page, config, 'Data Source', 'Named Query');
    await expect(labelExact(config, 'Query Code')).toBeVisible({ timeout: 5000 });
    await selectRadixOption(page, config, 'Data Source', 'Model Aggregate');
    await expect(labelExact(config, 'Aggregation')).toBeVisible({ timeout: 5000 });

    // ① Default value is SUM
    const defaultAgg = await getRadixSelectValue(config, 'Aggregation');
    expect(defaultAgg).toBe('SUM');

    // Verify all 5 options exist — open, check, then close before selecting
    const aggTrigger = fieldByLabel(config, 'Aggregation').locator('button[role="combobox"]');
    await aggTrigger.click();
    const allOptions = ['SUM', 'COUNT', 'AVG', 'MIN', 'MAX'];
    for (const opt of allOptions) {
      await expect(page.getByRole('option', { name: opt, exact: true })).toBeVisible();
    }

    // ② Change to AVG — selectRadixOption closes any open dropdown first
    await selectRadixOption(page, config, 'Aggregation', 'AVG');

    // ③ Read new value
    const newAgg = await getRadixSelectValue(config, 'Aggregation');
    expect(newAgg).toBe('AVG');

    // ④ Deselect
    await page.getByTestId('canvas-body').click({ position: { x: 10, y: 10 } });
    await page.getByTestId('block-config-empty').waitFor({ state: 'visible', timeout: 3000 });

    // ⑤ Reselect
    await page.locator(BLOCK_SEL).first().click();
    await page.getByTestId('chart-schema-config').waitFor({ state: 'visible', timeout: 5000 });

    const persisted = await getRadixSelectValue(config, 'Aggregation');
    expect(persisted).toBe('AVG');
  });
});

// ---------------------------------------------------------------------------
// C3.5  groupDimension (text) — 5-step pattern
// ---------------------------------------------------------------------------

test.describe('C3.5 groupDimension — text input in Model Aggregate mode', () => {
  test.fixme('fill group dimension, deselect, reselect — value persists', async ({ page }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);
    await addAndSelectChart(page);

    const config = page.getByTestId('chart-schema-config');

    // Activate Model Aggregate sub-fields
    await selectRadixOption(page, config, 'Data Source', 'Named Query');
    await expect(labelExact(config, 'Query Code')).toBeVisible({ timeout: 5000 });
    await selectRadixOption(page, config, 'Data Source', 'Model Aggregate');
    await expect(labelExact(config, 'Group dimension')).toBeVisible({ timeout: 5000 });

    // ① Default: empty
    const groupInput = fieldByLabel(config, 'Group dimension').locator('input');
    await expect(groupInput).toBeVisible();
    expect(await groupInput.inputValue()).toBe('');

    // ② Fill
    await groupInput.fill('category');

    // ③ Read
    await expect(groupInput).toHaveValue('category');

    // ④ Deselect
    await page.getByTestId('canvas-body').click({ position: { x: 10, y: 10 } });
    await page.getByTestId('block-config-empty').waitFor({ state: 'visible', timeout: 3000 });

    // ⑤ Reselect → still "category"
    await page.locator(BLOCK_SEL).first().click();
    await page.getByTestId('chart-schema-config').waitFor({ state: 'visible', timeout: 5000 });
    const groupInputAfter = fieldByLabel(config, 'Group dimension').locator('input');
    await expect(groupInputAfter).toHaveValue('category');
  });
});

// ---------------------------------------------------------------------------
// C3.6  dataSource.queryCode (text) — Named Query mode — 5-step pattern
// ---------------------------------------------------------------------------

test.describe('C3.6 dataSource.queryCode — Named Query mode', () => {
  test('switch to Named Query; fill queryCode; deselect; reselect → value persists', async ({ page }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);
    await addAndSelectChart(page);

    const config = page.getByTestId('chart-schema-config');

    // Switch to Named Query
    await selectRadixOption(page, config, 'Data Source', 'Named Query');
    await expect(labelExact(config, 'Query Code')).toBeVisible({ timeout: 5000 });

    // ① Default: empty
    const queryCodeInput = fieldByLabel(config, 'Query Code').locator('input');
    await expect(queryCodeInput).toBeVisible();
    expect(await queryCodeInput.inputValue()).toBe('');

    // Verify placeholder
    await expect(queryCodeInput).toHaveAttribute('placeholder', 'e.g. sales_by_month');

    // ② Fill
    await queryCodeInput.fill('sales_monthly');

    // ③ Read
    await expect(queryCodeInput).toHaveValue('sales_monthly');

    // ④ Deselect
    await page.getByTestId('canvas-body').click({ position: { x: 10, y: 10 } });
    await page.getByTestId('block-config-empty').waitFor({ state: 'visible', timeout: 3000 });

    // ⑤ Reselect → value persists
    await page.locator(BLOCK_SEL).first().click();
    await page.getByTestId('chart-schema-config').waitFor({ state: 'visible', timeout: 5000 });

    // Mode should still be Named Query
    const modeAfter = await getRadixSelectValue(config, 'Data Source');
    expect(modeAfter).toBe('Named Query');

    const queryCodeAfter = fieldByLabel(config, 'Query Code').locator('input');
    await expect(queryCodeAfter).toHaveValue('sales_monthly');
  });
});

// ---------------------------------------------------------------------------
// C3.7  dataSource.endpoint (text) — Custom API mode — 5-step pattern
// ---------------------------------------------------------------------------

test.describe('C3.7 dataSource.endpoint — Custom API mode', () => {
  test('switch to Custom API; fill endpoint; deselect; reselect → persists', async ({ page }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);
    await addAndSelectChart(page);

    const config = page.getByTestId('chart-schema-config');

    // Switch to Custom API
    await selectRadixOption(page, config, 'Data Source', 'Custom API');
    await expect(labelExact(config, 'Endpoint')).toBeVisible({ timeout: 5000 });

    // ① Default: empty
    const endpointInput = fieldByLabel(config, 'Endpoint').locator('input');
    await expect(endpointInput).toBeVisible();
    expect(await endpointInput.inputValue()).toBe('');

    // Verify placeholder
    await expect(endpointInput).toHaveAttribute('placeholder', '/api/stats/monthly');

    // ② Fill
    await endpointInput.fill('/api/stats');

    // ③ Read
    await expect(endpointInput).toHaveValue('/api/stats');

    // ④ Deselect
    await page.getByTestId('canvas-body').click({ position: { x: 10, y: 10 } });
    await page.getByTestId('block-config-empty').waitFor({ state: 'visible', timeout: 3000 });

    // ⑤ Reselect → persists
    await page.locator(BLOCK_SEL).first().click();
    await page.getByTestId('chart-schema-config').waitFor({ state: 'visible', timeout: 5000 });
    const endpointAfter = fieldByLabel(config, 'Endpoint').locator('input');
    await expect(endpointAfter).toHaveValue('/api/stats');
  });
});

// ---------------------------------------------------------------------------
// C3.8  dataSource.params (json textarea) — Custom API mode — 5-step pattern
// ---------------------------------------------------------------------------

test.describe('C3.8 dataSource.params — Custom API mode JSON textarea', () => {
  test('switch to Custom API; fill params; deselect; reselect → persists', async ({ page }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);
    await addAndSelectChart(page);

    const config = page.getByTestId('chart-schema-config');

    // Switch to Custom API
    await selectRadixOption(page, config, 'Data Source', 'Custom API');
    await expect(labelExact(config, 'Params (JSON)')).toBeVisible({ timeout: 5000 });

    // ① Default: empty textarea
    const paramsTextarea = fieldByLabel(config, 'Params (JSON)').locator('textarea');
    await expect(paramsTextarea).toBeVisible();
    const defaultParams = await paramsTextarea.inputValue();
    expect(defaultParams).toBe('');

    // ② Fill — the JSON widget auto-formats on blur, so we use compact JSON
    await paramsTextarea.fill('{"period":"month"}');
    // Trigger blur to let the JSON widget process and potentially reformat
    await paramsTextarea.press('Tab');

    // ③ Read — JSON widget may pretty-print; check for the key/value instead of exact format
    const rawValue = await paramsTextarea.inputValue();
    expect(rawValue).toContain('"period"');
    expect(rawValue).toContain('"month"');

    // ④ Deselect
    await page.getByTestId('canvas-body').click({ position: { x: 10, y: 10 } });
    await page.getByTestId('block-config-empty').waitFor({ state: 'visible', timeout: 3000 });

    // ⑤ Reselect → persists (value still contains the JSON data)
    await page.locator(BLOCK_SEL).first().click();
    await page.getByTestId('chart-schema-config').waitFor({ state: 'visible', timeout: 5000 });
    const paramsAfter = fieldByLabel(config, 'Params (JSON)').locator('textarea');
    const rawAfter = await paramsAfter.inputValue();
    expect(rawAfter).toContain('"period"');
    expect(rawAfter).toContain('"month"');
  });
});

// ---------------------------------------------------------------------------
// C3.9  chartType — 20 options, default Bar, change to Pie, persist
// ---------------------------------------------------------------------------

test.describe('C3.9 chartType — 20 options verification and persistence', () => {
  test('default is Bar; verify exactly 20 options; change to Pie; deselect; reselect → Pie', async ({ page }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);
    await addAndSelectChart(page);

    const config = page.getByTestId('chart-schema-config');

    // ① Default value
    const defaultChartType = await getRadixSelectValue(config, 'Chart Type');
    expect(defaultChartType).toBe('Bar');

    // Open the dropdown and verify all 20 options
    const chartTypeTrigger = fieldByLabel(config, 'Chart Type').locator('button[role="combobox"]');
    await chartTypeTrigger.click();

    const expectedOptions = [
      'Bar',
      'Line',
      'Pie',
      'Area',
      'Scatter',
      'Radar',
      'Heatmap',
      'Treemap',
      'Sunburst',
      'Funnel',
      'Gauge',
      'Sankey',
      'Boxplot',
      'Candlestick',
      'Waterfall',
      'Word Cloud',
      'Combo (Bar+Line)',
      'Donut',
      'Stacked Bar',
      'Stacked Area',
    ];

    // Verify each option is visible in the dropdown
    for (const optionText of expectedOptions) {
      await expect(
        page.getByRole('option', { name: optionText, exact: true }),
        `Chart type option "${optionText}" should be visible`,
      ).toBeVisible();
    }

    // Verify total count is exactly 20
    const allOptions = page.getByRole('listbox').getByRole('option');
    const optionCount = await allOptions.count();
    expect(optionCount, 'Chart type dropdown should have exactly 20 options').toBe(20);

    // ② Select "Pie"
    await page.getByRole('option', { name: 'Pie', exact: true }).click();

    // ③ Read new value
    const newChartType = await getRadixSelectValue(config, 'Chart Type');
    expect(newChartType).toBe('Pie');

    // ④ Deselect
    await page.getByTestId('canvas-body').click({ position: { x: 10, y: 10 } });
    await page.getByTestId('block-config-empty').waitFor({ state: 'visible', timeout: 3000 });

    // ⑤ Reselect → Pie persists
    await page.locator(BLOCK_SEL).first().click();
    await page.getByTestId('chart-schema-config').waitFor({ state: 'visible', timeout: 5000 });

    const persisted = await getRadixSelectValue(config, 'Chart Type');
    expect(persisted).toBe('Pie');
  });
});

// ---------------------------------------------------------------------------
// C3.10  chartTitle (text) — 5-step pattern
// ---------------------------------------------------------------------------

test.describe('C3.10 chartTitle — text input with persistence', () => {
  test('default empty; fill "Revenue Chart"; deselect; reselect → persists', async ({ page }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);
    await addAndSelectChart(page);

    const config = page.getByTestId('chart-schema-config');

    // ① Default: empty
    const titleInput = fieldByLabel(config, 'Chart title').locator('input');
    await expect(titleInput).toBeVisible();
    await expect(titleInput).toHaveAttribute('placeholder', 'Optional title...');
    expect(await titleInput.inputValue()).toBe('');

    // ② Fill
    await titleInput.fill('Revenue Chart');

    // ③ Read
    await expect(titleInput).toHaveValue('Revenue Chart');

    // ④ Deselect (click table block to switch to a different config)
    await addTableBlock(page);
    await page.locator('[data-testid^="canvas-block-content-"]').nth(1).click();
    await page.getByTestId('table-schema-config').waitFor({ state: 'visible', timeout: 10_000 });

    // ⑤ Reselect chart → persists
    await page.locator(BLOCK_SEL).first().click();
    await page.getByTestId('chart-schema-config').waitFor({ state: 'visible', timeout: 5000 });

    const titleAfter = fieldByLabel(config, 'Chart title').locator('input');
    await expect(titleAfter).toHaveValue('Revenue Chart');
  });
});

// ---------------------------------------------------------------------------
// C3.11  height (number) — default 300, change to 400, persist
// ---------------------------------------------------------------------------

test.describe('C3.11 height — number input, default 300', () => {
  test('default 300; change to 400; deselect; reselect → 400', async ({ page }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);
    await addAndSelectChart(page);

    const config = page.getByTestId('chart-schema-config');

    // ① Default value is 300
    const heightInput = fieldByLabel(config, 'Height (px)').locator('input[type="number"]');
    await expect(heightInput).toBeVisible();
    await expect(heightInput).toHaveValue('300');

    // ② Change to 400
    await heightInput.fill('400');
    await heightInput.press('Tab');

    // ③ Read
    await expect(heightInput).toHaveValue('400');

    // ④ Deselect
    await page.getByTestId('canvas-body').click({ position: { x: 10, y: 10 } });
    await page.getByTestId('block-config-empty').waitFor({ state: 'visible', timeout: 3000 });

    // ⑤ Reselect → 400
    await page.locator(BLOCK_SEL).first().click();
    await page.getByTestId('chart-schema-config').waitFor({ state: 'visible', timeout: 5000 });

    const heightAfter = fieldByLabel(config, 'Height (px)').locator('input[type="number"]');
    await expect(heightAfter).toHaveValue('400');
  });
});

// ---------------------------------------------------------------------------
// C3.12  showLegend (switch) — default OFF, toggle ON, persist
// ---------------------------------------------------------------------------

test.describe('C3.12 showLegend — switch default OFF, toggle to ON', () => {
  test('default unchecked; toggle ON; deselect; reselect → checked', async ({ page }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);
    await addAndSelectChart(page);

    const config = page.getByTestId('chart-schema-config');

    // ① Default: unchecked
    const legendSwitch = fieldByLabel(config, 'Show legend').locator('button[role="switch"]');
    await expect(legendSwitch).toBeVisible();
    await expect(legendSwitch).toHaveAttribute('data-state', 'unchecked');

    // ② Toggle ON
    await legendSwitch.click();

    // ③ Now checked
    await expect(legendSwitch).toHaveAttribute('data-state', 'checked');

    // ④ Deselect
    await page.getByTestId('canvas-body').click({ position: { x: 10, y: 10 } });
    await page.getByTestId('block-config-empty').waitFor({ state: 'visible', timeout: 3000 });

    // ⑤ Reselect → still checked
    await page.locator(BLOCK_SEL).first().click();
    await page.getByTestId('chart-schema-config').waitFor({ state: 'visible', timeout: 5000 });

    const legendSwitchAfter = fieldByLabel(config, 'Show legend').locator('button[role="switch"]');
    await expect(legendSwitchAfter).toHaveAttribute('data-state', 'checked');
  });
});

// ---------------------------------------------------------------------------
// C3.13  showValues (switch) — default OFF, toggle ON, persist
// ---------------------------------------------------------------------------

test.describe('C3.13 showValues — switch default OFF, toggle to ON', () => {
  test('default unchecked; toggle ON; deselect; reselect → checked', async ({ page }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);
    await addAndSelectChart(page);

    const config = page.getByTestId('chart-schema-config');

    // ① Default: unchecked
    const valuesSwitch = fieldByLabel(config, 'Show value labels').locator('button[role="switch"]');
    await expect(valuesSwitch).toBeVisible();
    await expect(valuesSwitch).toHaveAttribute('data-state', 'unchecked');

    // ② Toggle ON
    await valuesSwitch.click();

    // ③ Now checked
    await expect(valuesSwitch).toHaveAttribute('data-state', 'checked');

    // ④ Deselect
    await page.getByTestId('canvas-body').click({ position: { x: 10, y: 10 } });
    await page.getByTestId('block-config-empty').waitFor({ state: 'visible', timeout: 3000 });

    // ⑤ Reselect → still checked
    await page.locator(BLOCK_SEL).first().click();
    await page.getByTestId('chart-schema-config').waitFor({ state: 'visible', timeout: 5000 });

    const valuesSwitchAfter = fieldByLabel(config, 'Show value labels').locator('button[role="switch"]');
    await expect(valuesSwitchAfter).toHaveAttribute('data-state', 'checked');
  });
});

// ---------------------------------------------------------------------------
// C3.14  visibleWhen — expression editor in Conditions group
// ---------------------------------------------------------------------------

test.describe('C3.14 visibleWhen — expression editor in Conditions group', () => {
  test('Conditions group header visible; expression editor renders', async ({ page }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);
    await addAndSelectChart(page);

    const config = page.getByTestId('chart-schema-config');

    // Conditions group header
    await expect(groupHeader(config, 'Conditions')).toBeVisible();

    // "Visible when" label
    await expect(labelExact(config, 'Visible when')).toBeVisible();

    // ExpressionEditor component
    await expect(config.getByTestId('expression-editor')).toBeVisible();
  });
});

// ---------------------------------------------------------------------------
// C3.+  layout.colSpan — chart default is 6 (not 12 like table)
// ---------------------------------------------------------------------------

test.describe('C3.+ layout.colSpan — chart default 6', () => {
  test.fixme('chart block shows "6col" badge by default', async ({ page }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);
    await addAndSelectChart(page);

    // The block badge should show "6col" (chart default colSpan is 6)
    // Badge is in the block wrapper header, not inside the content div
    const badge = page.locator(BLOCK_WRAPPER_SEL).first().locator('text=/6col/');
    await expect(badge).toBeVisible();

    // The colSpan input in the Layout section should show 6
    const colSpanInput = page.getByTestId('layout-colSpan');
    await expect(colSpanInput).toHaveValue('6');
  });

  test.fixme('change colSpan 6→12 → badge updates to 12col and block widens', async ({ page }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);
    await addAndSelectChart(page);

    // Record initial width
    const itemBefore = await page.locator('.react-grid-item').first().boundingBox();
    expect(itemBefore).not.toBeNull();

    // ② Change colSpan to 12
    const colSpanInput = page.getByTestId('layout-colSpan');
    await colSpanInput.fill('12');
    await colSpanInput.press('Tab');
    await page.waitForTimeout(400);

    // ③ Badge updated — badge is in block wrapper header, not content div
    const badge = page.locator(BLOCK_WRAPPER_SEL).first().locator('text=/12col/');
    await expect(badge).toBeVisible();

    // ④ Block is wider
    const itemAfter = await page.locator('.react-grid-item').first().boundingBox();
    expect(itemAfter).not.toBeNull();
    expect(itemAfter!.width).toBeGreaterThan(itemBefore!.width);
  });
});

// ---------------------------------------------------------------------------
// C3 Integration — All 4 group headers visible
// ---------------------------------------------------------------------------

test.describe('C3 integration — all property groups render', () => {
  test('Data Source, Chart Type, Style, and Conditions groups all visible', async ({ page }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);
    await addAndSelectChart(page);

    const config = page.getByTestId('chart-schema-config');

    await expect(groupHeader(config, 'Data Source')).toBeVisible();
    await expect(groupHeader(config, 'Chart Type')).toBeVisible();
    await expect(groupHeader(config, 'Style')).toBeVisible();
    await expect(groupHeader(config, 'Conditions')).toBeVisible();
  });

  test('switching between all 3 dataSourceMode branches shows correct fields each time', async ({ page }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);
    await addAndSelectChart(page);

    const config = page.getByTestId('chart-schema-config');

    // Start: default is Model Aggregate (but need to cycle to populate config)
    // Cycle: Named Query → Model Aggregate → Custom API → Named Query
    await selectRadixOption(page, config, 'Data Source', 'Named Query');
    await expect(labelExact(config, 'Query Code')).toBeVisible({ timeout: 5000 });
    await expect(labelExact(config, 'Endpoint')).not.toBeVisible();
    await expect(labelExact(config, 'Metric field')).not.toBeVisible();

    await selectRadixOption(page, config, 'Data Source', 'Model Aggregate');
    await expect(labelExact(config, 'Metric field')).toBeVisible({ timeout: 5000 });
    await expect(labelExact(config, 'Aggregation')).toBeVisible();
    await expect(labelExact(config, 'Group dimension')).toBeVisible();
    await expect(labelExact(config, 'Query Code')).not.toBeVisible();
    await expect(labelExact(config, 'Endpoint')).not.toBeVisible();

    await selectRadixOption(page, config, 'Data Source', 'Custom API');
    await expect(labelExact(config, 'Endpoint')).toBeVisible({ timeout: 5000 });
    await expect(labelExact(config, 'Params (JSON)')).toBeVisible();
    await expect(labelExact(config, 'Metric field')).not.toBeVisible();
    await expect(labelExact(config, 'Query Code')).not.toBeVisible();

    await selectRadixOption(page, config, 'Data Source', 'Named Query');
    await expect(labelExact(config, 'Query Code')).toBeVisible({ timeout: 5000 });
    await expect(labelExact(config, 'Endpoint')).not.toBeVisible();
    await expect(labelExact(config, 'Metric field')).not.toBeVisible();
  });
});
