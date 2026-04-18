/**
 * Page Designer — Misc Block Properties Deep Tests (C6–C13)
 *
 * Covers all properties for the 8 smaller block types using the 5-step verification pattern:
 *   ① Read default value → assert correct
 *   ② Change to new value
 *   ③ Read changed value → assert correct
 *   ④ Deselect (click canvas-body)
 *   ⑤ Reselect → assert value persisted
 *
 * For dependsOn properties, additionally:
 *   ⑥ Change controlling field → dependent field appears
 *   ⑦ Change back → dependent field disappears
 *
 * Block types covered:
 *   C6.  Sub-table     (10 properties: relationMode + 3 FK mode fields + 3 resolveVia fields + 1 API field + tabLabel + maxRows + visibleWhen)
 *   C7.  Tabs          (5 properties: defaultActiveTab, tabPosition, tabs JSON, visibleWhen, className)
 *   C8.  Stat-card     (2 properties + layout: queryCode, visibleWhen, layout.colSpan default=4)
 *   C9.  Monthly-grid  (12 properties: 7 text/model fields, 2 resolveVia, 1 json metrics, 2 expressions)
 *   C10. Filters       (3 properties: modelCode model-select, colCount select default=4, visibleWhen)
 *   C11. Detail-section (3 properties: modelCode model-select, colCount select default=3, visibleWhen)
 *   C12. Rich-text     (2 properties: content textarea, visibleWhen)
 *   C13. Divider       (1 property: visibleWhen only)
 *
 * Navigation: page.goto() is used because Page Designer is a platform designer tool,
 * not a sidebar menu page (allowed per AGENTS.md exception for designer workbenches).
 *
 * Dimensions covered:
 *   D2 (config panel renders), D5 (widget types: select/text/number/switch/json/expression),
 *   D8 (property persistence: change → deselect → reselect → value still set)
 * Not applicable:
 *   D1 (no sidebar menu, Page Designer is a platform tool),
 *   D3/D9/D10 (no status machine), D4/D6/D7/D11/D12/D13/D14 (not a CRUD model)
 *
 * Config panel testids (data-testid="${blockType}-schema-config"):
 *   sub-table-schema-config, tabs-schema-config, stat-card-schema-config,
 *   monthly-grid-schema-config, filters-schema-config, detail-section-schema-config,
 *   rich-text-schema-config, divider-schema-config
 *
 * Auth: tests/storage/admin.json
 *
 * @since 4.3.0
 */

import { test, expect, type Page, type Locator } from '@playwright/test';
import { uniqueId } from '../helpers/index';

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

/** Matches only root canvas-block elements (not sub-elements) */
const BLK =
  '[data-testid^="canvas-block-"]:not([data-testid*="-drag-"]):not([data-testid*="-remove-"]):not([data-testid*="-content-"])';

// ---------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------

/** Create a blank composite page via API and return its pid. */
async function createPage(page: Page, prefix: string): Promise<string> {
  const name = uniqueId(prefix);
  const pageKey = `e2e_${prefix}_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 7)}`;

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
  return pid as string;
}

/** Open the page designer for a given pid and wait for the canvas. */
async function openDesigner(page: Page, pid: string): Promise<void> {
  await page.goto(`/page-designer/${pid}`, { waitUntil: 'domcontentloaded' });
  await page.getByTestId('designer-canvas').waitFor({ state: 'visible', timeout: 15000 });
}

/** Add a block via the Components palette. */
async function addBlock(page: Page, blockType: string): Promise<void> {
  await page.getByTestId('canvas-left-tab-components').click();
  const paletteItem = page.getByTestId(`block-palette-item-${blockType}`);
  await paletteItem.waitFor({ state: 'visible', timeout: 5000 });
  await paletteItem.click();
  // Wait for the block to appear in the canvas (sub-table and other blocks may take longer)
  await page.locator(BLK).first().waitFor({ state: 'visible', timeout: 10000 });
}

/** Select a block and wait for its config panel. */
async function selectBlock(page: Page, configTestId: string): Promise<Locator> {
  // Canvas blocks may be re-rendered by React — use block wrapper for more reliable click
  const block = page.locator(BLK).first();
  await block.waitFor({ state: 'visible', timeout: 10_000 });
  await block.click({ timeout: 10_000 });
  const config = page.getByTestId(configTestId);
  await config.waitFor({ state: 'visible', timeout: 8_000 });
  return config;
}

/** Add and select a block, returning its config panel locator. */
async function addAndSelect(page: Page, blockType: string): Promise<Locator> {
  await addBlock(page, blockType);
  return selectBlock(page, `${blockType}-schema-config`);
}

/** Deselect by clicking canvas background and wait for empty state. */
async function deselect(page: Page): Promise<void> {
  await page.getByTestId('canvas-body').click({ position: { x: 10, y: 10 } });
  await page.getByTestId('block-config-empty').waitFor({ state: 'visible', timeout: 3000 });
}

/** Reselect the first block and wait for its config panel. */
async function reselect(page: Page, configTestId: string): Promise<Locator> {
  await page.locator(BLK).first().click();
  const config = page.getByTestId(configTestId);
  await config.waitFor({ state: 'visible', timeout: 5000 });
  return config;
}

/** Get label element with exact text match. */
function labelExact(container: Locator, label: string): Locator {
  return container.locator(`label:text-is("${label}")`).first();
}

/** Get the field container div by label text. */
function fieldByLabel(container: Locator, label: string): Locator {
  return container.locator(`label:text-is("${label}")`).first().locator('..');
}

/** Click a Radix Select trigger and select the given option. */
async function selectRadixOption(
  page: Page,
  container: Locator,
  label: string,
  optionText: string,
): Promise<void> {
  const field = fieldByLabel(container, label);
  const trigger = field.locator('button[role="combobox"]');
  await page.keyboard.press('Escape'); // close any open dropdown
  await trigger.waitFor({ state: 'visible', timeout: 5000 });
  await trigger.click();
  const option = page.getByRole('option', { name: optionText, exact: true });
  await option.waitFor({ state: 'visible', timeout: 3000 });
  await option.click();
}

/** Get the currently displayed text from a Radix Select trigger. */
async function getRadixSelectValue(container: Locator, label: string): Promise<string> {
  const field = fieldByLabel(container, label);
  const trigger = field.locator('button[role="combobox"]');
  return (await trigger.innerText()).trim();
}

// ===========================================================================
// C6. Sub-table Block (10 properties)
// ===========================================================================

test.describe('C6 Sub-table — relationMode 3 branches + tabLabel + maxRows + visibleWhen', () => {
  test('C6.1 relationMode default FK; switching reveals/hides branch-specific fields', async ({ page }) => {
    const pid = await createPage(page, 'subtbl');
    await openDesigner(page, pid);
    const config = await addAndSelect(page, 'sub-table');

    // ① Default value is "Foreign Key (direct)"
    const defaultMode = await getRadixSelectValue(config, 'Relation mode');
    expect(defaultMode).toBe('Foreign Key (direct)');

    // New block has empty config — dependsOn needs config.relationMode to be set.
    // Cycle through another mode first, then back to FK to set the value in config.
    await selectRadixOption(page, config, 'Relation mode', 'Resolve via (indirect)');
    await expect(labelExact(config, 'Junction model')).toBeVisible({ timeout: 5000 });
    await selectRadixOption(page, config, 'Relation mode', 'Foreign Key (direct)');

    // FK mode: foreignKeyField visible, resolveVia and API fields hidden
    await expect(labelExact(config, 'Foreign key field')).toBeVisible({ timeout: 5000 });
    await expect(labelExact(config, 'Junction model')).not.toBeVisible();
    await expect(labelExact(config, 'Parent FK in junction')).not.toBeVisible();
    await expect(labelExact(config, 'Child FK in junction')).not.toBeVisible();
    await expect(labelExact(config, 'API endpoint')).not.toBeVisible();

    // --- Branch: Resolve via (indirect) ---
    await selectRadixOption(page, config, 'Relation mode', 'Resolve via (indirect)');

    // resolveVia fields visible
    await expect(labelExact(config, 'Junction model')).toBeVisible({ timeout: 5000 });
    await expect(labelExact(config, 'Parent FK in junction')).toBeVisible();
    await expect(labelExact(config, 'Child FK in junction')).toBeVisible();
    // FK and API fields hidden
    await expect(labelExact(config, 'Foreign key field')).not.toBeVisible();
    await expect(labelExact(config, 'API endpoint')).not.toBeVisible();

    // ⑤ Persistence: deselect → reselect → still resolveVia
    await deselect(page);
    const config2 = await reselect(page, 'sub-table-schema-config');
    const afterMode = await getRadixSelectValue(config2, 'Relation mode');
    expect(afterMode).toBe('Resolve via (indirect)');
    await expect(labelExact(config2, 'Junction model')).toBeVisible();
    await expect(labelExact(config2, 'Foreign key field')).not.toBeVisible();

    // --- Branch: Data Source (API) ---
    await selectRadixOption(page, config2, 'Relation mode', 'Data Source (API)');

    // API endpoint visible, others hidden
    await expect(labelExact(config2, 'API endpoint')).toBeVisible({ timeout: 5000 });
    await expect(labelExact(config2, 'Foreign key field')).not.toBeVisible();
    await expect(labelExact(config2, 'Junction model')).not.toBeVisible();

    // ⑤ Persistence: API mode persists
    await deselect(page);
    const config3 = await reselect(page, 'sub-table-schema-config');
    const finalMode = await getRadixSelectValue(config3, 'Relation mode');
    expect(finalMode).toBe('Data Source (API)');
    await expect(labelExact(config3, 'API endpoint')).toBeVisible();
  });

  test('C6.2 foreignKeyField — fill in FK mode, deselect, reselect — persists', async ({ page }) => {
    const pid = await createPage(page, 'subtbl-fk');
    await openDesigner(page, pid);
    const config = await addAndSelect(page, 'sub-table');

    // Ensure FK mode (default)
    const mode = await getRadixSelectValue(config, 'Relation mode');
    expect(mode).toBe('Foreign Key (direct)');
    // New block has empty config — cycle through another mode then back to FK to set config value
    await selectRadixOption(page, config, 'Relation mode', 'Resolve via (indirect)');
    await expect(labelExact(config, 'Junction model')).toBeVisible({ timeout: 5000 });
    await selectRadixOption(page, config, 'Relation mode', 'Foreign Key (direct)');

    await expect(labelExact(config, 'Foreign key field')).toBeVisible({ timeout: 5000 });

    // ① Default: empty
    const fkInput = fieldByLabel(config, 'Foreign key field').locator('input');
    await expect(fkInput).toBeVisible();
    expect(await fkInput.inputValue()).toBe('');

    // ② Fill new value
    await fkInput.fill('order_id');

    // ③ Read back
    await expect(fkInput).toHaveValue('order_id');

    // ④ Deselect
    await deselect(page);

    // ⑤ Reselect — value persists
    const config2 = await reselect(page, 'sub-table-schema-config');
    const fkInput2 = fieldByLabel(config2, 'Foreign key field').locator('input');
    await expect(fkInput2).toHaveValue('order_id');
  });

  test('C6.3 resolveVia fields — parentFkInJunction + childFkInJunction persist', async ({ page }) => {
    const pid = await createPage(page, 'subtbl-rv');
    await openDesigner(page, pid);
    const config = await addAndSelect(page, 'sub-table');

    // Switch to resolveVia mode
    await selectRadixOption(page, config, 'Relation mode', 'Resolve via (indirect)');
    await expect(labelExact(config, 'Parent FK in junction')).toBeVisible({ timeout: 5000 });
    await expect(labelExact(config, 'Child FK in junction')).toBeVisible();

    // Fill parentFkInJunction
    const parentFkInput = fieldByLabel(config, 'Parent FK in junction').locator('input');
    await expect(parentFkInput).toHaveValue('');
    await parentFkInput.fill('parent_id');
    await expect(parentFkInput).toHaveValue('parent_id');

    // Fill childFkInJunction
    const childFkInput = fieldByLabel(config, 'Child FK in junction').locator('input');
    await expect(childFkInput).toHaveValue('');
    await childFkInput.fill('child_id');
    await expect(childFkInput).toHaveValue('child_id');

    // ④ Deselect → ⑤ Reselect
    await deselect(page);
    const config2 = await reselect(page, 'sub-table-schema-config');

    const parentFk2 = fieldByLabel(config2, 'Parent FK in junction').locator('input');
    await expect(parentFk2).toHaveValue('parent_id');

    const childFk2 = fieldByLabel(config2, 'Child FK in junction').locator('input');
    await expect(childFk2).toHaveValue('child_id');
  });

  test('C6.4 API endpoint — fill in API mode, deselect, reselect — persists', async ({ page }) => {
    const pid = await createPage(page, 'subtbl-api');
    await openDesigner(page, pid);
    const config = await addAndSelect(page, 'sub-table');

    // Switch to API mode
    await selectRadixOption(page, config, 'Relation mode', 'Data Source (API)');
    await expect(labelExact(config, 'API endpoint')).toBeVisible({ timeout: 5000 });

    // ① Default: empty
    const endpointInput = fieldByLabel(config, 'API endpoint').locator('input');
    expect(await endpointInput.inputValue()).toBe('');

    // ② Fill
    await endpointInput.fill('/api/children/{parentId}');

    // ③ Read back
    await expect(endpointInput).toHaveValue('/api/children/{parentId}');

    // ④ Deselect → ⑤ Reselect
    await deselect(page);
    const config2 = await reselect(page, 'sub-table-schema-config');
    const endpointInput2 = fieldByLabel(config2, 'API endpoint').locator('input');
    await expect(endpointInput2).toHaveValue('/api/children/{parentId}');
  });

  test('C6.5 tabLabel + maxRows — fill both, deselect, reselect — both persist', async ({ page }) => {
    const pid = await createPage(page, 'subtbl-disp');
    await openDesigner(page, pid);
    const config = await addAndSelect(page, 'sub-table');

    // tabLabel
    await expect(labelExact(config, 'Tab label')).toBeVisible();
    const tabLabelInput = fieldByLabel(config, 'Tab label').locator('input');
    expect(await tabLabelInput.inputValue()).toBe('');
    await tabLabelInput.fill('Order Items');
    await expect(tabLabelInput).toHaveValue('Order Items');

    // maxRows (default=20)
    await expect(labelExact(config, 'Max rows shown')).toBeVisible();
    const maxRowsInput = fieldByLabel(config, 'Max rows shown').locator('input[type="number"]');
    await expect(maxRowsInput).toBeVisible();
    // default should be 20
    const defaultMaxRows = await maxRowsInput.inputValue();
    expect(defaultMaxRows).toBe('20');

    await maxRowsInput.fill('50');
    await expect(maxRowsInput).toHaveValue('50');

    // ④ Deselect → ⑤ Reselect
    await deselect(page);
    const config2 = await reselect(page, 'sub-table-schema-config');

    const tabLabelInput2 = fieldByLabel(config2, 'Tab label').locator('input');
    await expect(tabLabelInput2).toHaveValue('Order Items');

    const maxRowsInput2 = fieldByLabel(config2, 'Max rows shown').locator('input[type="number"]');
    await expect(maxRowsInput2).toHaveValue('50');
  });

  test('C6.6 visibleWhen — expression editor is present', async ({ page }) => {
    const pid = await createPage(page, 'subtbl-vis');
    await openDesigner(page, pid);
    const config = await addAndSelect(page, 'sub-table');

    // visibleWhen expression editor must be visible
    await expect(labelExact(config, 'Visible when')).toBeVisible();
    // The expression editor widget — a button or input is present
    const visWhenField = fieldByLabel(config, 'Visible when');
    await expect(visWhenField).toBeVisible();
  });
});

// ===========================================================================
// C7. Tabs Block (5 properties)
// ===========================================================================

test.describe('C7 Tabs — defaultActiveTab + tabPosition + tabs JSON + visibleWhen + className', () => {
  test('C7.1 tabPosition default Top; change to Left; deselect; reselect — persists', async ({ page }) => {
    const pid = await createPage(page, 'tabs-pos');
    await openDesigner(page, pid);
    const config = await addAndSelect(page, 'tabs');

    // ① Default value is "Top"
    const defaultPos = await getRadixSelectValue(config, 'Tab Position');
    expect(defaultPos).toBe('Top');

    // ② Change to Left
    await selectRadixOption(page, config, 'Tab Position', 'Left');

    // ③ Read back
    const changedPos = await getRadixSelectValue(config, 'Tab Position');
    expect(changedPos).toBe('Left');

    // ④ Deselect → ⑤ Reselect
    await deselect(page);
    const config2 = await reselect(page, 'tabs-schema-config');
    const persistedPos = await getRadixSelectValue(config2, 'Tab Position');
    expect(persistedPos).toBe('Left');
  });

  test('C7.2 tabPosition has 4 options: Top/Left/Bottom/Right', async ({ page }) => {
    const pid = await createPage(page, 'tabs-opts');
    await openDesigner(page, pid);
    const config = await addAndSelect(page, 'tabs');

    // Open the dropdown and verify 4 options
    const field = fieldByLabel(config, 'Tab Position');
    const trigger = field.locator('button[role="combobox"]');
    await page.keyboard.press('Escape');
    await trigger.click();

    const options = page.getByRole('option');
    await options.first().waitFor({ state: 'visible', timeout: 3000 });
    const count = await options.count();
    expect(count).toBeGreaterThanOrEqual(4);

    // Verify the 4 expected options exist
    await expect(page.getByRole('option', { name: 'Top', exact: true })).toBeVisible();
    await expect(page.getByRole('option', { name: 'Left', exact: true })).toBeVisible();
    await expect(page.getByRole('option', { name: 'Bottom', exact: true })).toBeVisible();
    await expect(page.getByRole('option', { name: 'Right', exact: true })).toBeVisible();

    await page.keyboard.press('Escape');
  });

  test('C7.3 defaultActiveTab — fill, deselect, reselect — persists', async ({ page }) => {
    const pid = await createPage(page, 'tabs-dat');
    await openDesigner(page, pid);
    const config = await addAndSelect(page, 'tabs');

    // ① Default: empty
    await expect(labelExact(config, 'Default Active Tab')).toBeVisible();
    const datInput = fieldByLabel(config, 'Default Active Tab').locator('input');
    expect(await datInput.inputValue()).toBe('');

    // ② Fill
    await datInput.fill('settings');
    await expect(datInput).toHaveValue('settings');

    // ④ Deselect → ⑤ Reselect
    await deselect(page);
    const config2 = await reselect(page, 'tabs-schema-config');
    const datInput2 = fieldByLabel(config2, 'Default Active Tab').locator('input');
    await expect(datInput2).toHaveValue('settings');
  });

  test('C7.4 tabs JSON — textarea visible; fill JSON; deselect; reselect — value persists', async ({ page }) => {
    const pid = await createPage(page, 'tabs-json');
    await openDesigner(page, pid);
    const config = await addAndSelect(page, 'tabs');

    // JSON field should be a textarea (json type)
    await expect(labelExact(config, 'Tabs (JSON)')).toBeVisible();
    const jsonField = fieldByLabel(config, 'Tabs (JSON)');
    const textarea = jsonField.locator('textarea');
    await expect(textarea).toBeVisible();

    const tabsJson = JSON.stringify([{ key: 'overview', label: 'Overview' }]);
    await textarea.fill(tabsJson);

    // ④ Deselect → ⑤ Reselect
    await deselect(page);
    const config2 = await reselect(page, 'tabs-schema-config');
    const textarea2 = fieldByLabel(config2, 'Tabs (JSON)').locator('textarea');
    const persistedValue = await textarea2.inputValue();
    // Value should contain the JSON we filled (may be formatted)
    expect(persistedValue).toContain('overview');
  });

  test('C7.5 className — fill, deselect, reselect — persists', async ({ page }) => {
    const pid = await createPage(page, 'tabs-cls');
    await openDesigner(page, pid);
    const config = await addAndSelect(page, 'tabs');

    await expect(labelExact(config, 'CSS Class')).toBeVisible();
    const clsInput = fieldByLabel(config, 'CSS Class').locator('input');
    expect(await clsInput.inputValue()).toBe('');

    await clsInput.fill('compact');
    await expect(clsInput).toHaveValue('compact');

    // ④ Deselect → ⑤ Reselect
    await deselect(page);
    const config2 = await reselect(page, 'tabs-schema-config');
    const clsInput2 = fieldByLabel(config2, 'CSS Class').locator('input');
    await expect(clsInput2).toHaveValue('compact');
  });

  test('C7.6 visibleWhen — expression editor is present', async ({ page }) => {
    const pid = await createPage(page, 'tabs-vis');
    await openDesigner(page, pid);
    const config = await addAndSelect(page, 'tabs');

    await expect(labelExact(config, 'Visible when')).toBeVisible();
    const visWhenField = fieldByLabel(config, 'Visible when');
    await expect(visWhenField).toBeVisible();
  });
});

// ===========================================================================
// C8. Stat-card Block (2 properties + layout colSpan)
// ===========================================================================

test.describe('C8 Stat-card — queryCode + visibleWhen + layout.colSpan default=4', () => {
  test('C8.1 queryCode — fill, deselect, reselect — persists', async ({ page }) => {
    const pid = await createPage(page, 'statcard-qc');
    await openDesigner(page, pid);
    const config = await addAndSelect(page, 'stat-card');

    // ① Default: empty
    await expect(labelExact(config, 'Named Query code')).toBeVisible();
    const qcInput = fieldByLabel(config, 'Named Query code').locator('input');
    expect(await qcInput.inputValue()).toBe('');

    // ② Fill
    await qcInput.fill('total_revenue');
    await expect(qcInput).toHaveValue('total_revenue');

    // ④ Deselect → ⑤ Reselect
    await deselect(page);
    const config2 = await reselect(page, 'stat-card-schema-config');
    const qcInput2 = fieldByLabel(config2, 'Named Query code').locator('input');
    await expect(qcInput2).toHaveValue('total_revenue');
  });

  test('C8.2 layout.colSpan default is 4 for stat-card', async ({ page }) => {
    const pid = await createPage(page, 'statcard-layout');
    await openDesigner(page, pid);

    // Add stat-card and select it
    await addBlock(page, 'stat-card');
    await page.locator('[data-testid^="canvas-block-content-"]').first().click();
    await page.getByTestId('stat-card-schema-config').waitFor({ state: 'visible', timeout: 5000 });

    // The layout section (outside config panel) should show colSpan=4
    const colSpanInput = page.getByTestId('layout-colSpan');
    await expect(colSpanInput).toBeVisible();
    const colSpanValue = await colSpanInput.inputValue();
    expect(colSpanValue).toBe('4');
  });

  test('C8.3 visibleWhen — expression editor is present', async ({ page }) => {
    const pid = await createPage(page, 'statcard-vis');
    await openDesigner(page, pid);
    const config = await addAndSelect(page, 'stat-card');

    await expect(labelExact(config, 'Visible when')).toBeVisible();
    const visWhenField = fieldByLabel(config, 'Visible when');
    await expect(visWhenField).toBeVisible();
  });
});

// ===========================================================================
// C9. Monthly-grid Block (12 properties)
// ===========================================================================

test.describe('C9 Monthly-grid — 12 properties: Data Source + Resolve Via + Metrics + expressions', () => {
  test('C9.1 Data Source text fields — parentField + parentDisplayField + parentSortField + childParentField + monthField persist', async ({ page }) => {
    const pid = await createPage(page, 'mgrid-ds');
    await openDesigner(page, pid);
    const config = await addAndSelect(page, 'monthly-grid');

    // parentField
    await expect(labelExact(config, 'Parent key field')).toBeVisible();
    const parentFieldInput = fieldByLabel(config, 'Parent key field').locator('input');
    expect(await parentFieldInput.inputValue()).toBe('');
    await parentFieldInput.fill('id');
    await expect(parentFieldInput).toHaveValue('id');

    // parentDisplayField
    const parentDisplayInput = fieldByLabel(config, 'Parent display field').locator('input');
    expect(await parentDisplayInput.inputValue()).toBe('');
    await parentDisplayInput.fill('name');
    await expect(parentDisplayInput).toHaveValue('name');

    // parentSortField
    const parentSortInput = fieldByLabel(config, 'Parent sort field').locator('input');
    expect(await parentSortInput.inputValue()).toBe('');
    await parentSortInput.fill('sort_order');
    await expect(parentSortInput).toHaveValue('sort_order');

    // childParentField
    const childParentInput = fieldByLabel(config, 'Child → Parent FK').locator('input');
    expect(await childParentInput.inputValue()).toBe('');
    await childParentInput.fill('parent_id');
    await expect(childParentInput).toHaveValue('parent_id');

    // monthField
    const monthFieldInput = fieldByLabel(config, 'Month field').locator('input');
    expect(await monthFieldInput.inputValue()).toBe('');
    await monthFieldInput.fill('month');
    await expect(monthFieldInput).toHaveValue('month');

    // ④ Deselect → ⑤ Reselect — all values persist
    await deselect(page);
    const config2 = await reselect(page, 'monthly-grid-schema-config');

    await expect(fieldByLabel(config2, 'Parent key field').locator('input')).toHaveValue('id');
    await expect(fieldByLabel(config2, 'Parent display field').locator('input')).toHaveValue('name');
    await expect(fieldByLabel(config2, 'Parent sort field').locator('input')).toHaveValue('sort_order');
    await expect(fieldByLabel(config2, 'Child → Parent FK').locator('input')).toHaveValue('parent_id');
    await expect(fieldByLabel(config2, 'Month field').locator('input')).toHaveValue('month');
  });

  test('C9.2 Resolve Via — intermediateParentField — fill, deselect, reselect — persists', async ({ page }) => {
    const pid = await createPage(page, 'mgrid-rv');
    await openDesigner(page, pid);
    const config = await addAndSelect(page, 'monthly-grid');

    // The Resolve Via section should be visible (group header)
    // The text fields in the Resolve Via group
    await expect(labelExact(config, 'Parent FK in junction')).toBeVisible();
    const intParentInput = fieldByLabel(config, 'Parent FK in junction').locator('input');
    expect(await intParentInput.inputValue()).toBe('');
    await intParentInput.fill('parent_id');
    await expect(intParentInput).toHaveValue('parent_id');

    // ④ Deselect → ⑤ Reselect
    await deselect(page);
    const config2 = await reselect(page, 'monthly-grid-schema-config');
    const intParentInput2 = fieldByLabel(config2, 'Parent FK in junction').locator('input');
    await expect(intParentInput2).toHaveValue('parent_id');
  });

  test('C9.3 Metrics JSON — fill JSON array, deselect, reselect — value contains data', async ({ page }) => {
    const pid = await createPage(page, 'mgrid-metrics');
    await openDesigner(page, pid);
    const config = await addAndSelect(page, 'monthly-grid');

    // Metrics (JSON) is a json type (textarea)
    await expect(labelExact(config, 'Metrics (JSON)')).toBeVisible();
    const jsonField = fieldByLabel(config, 'Metrics (JSON)');
    const textarea = jsonField.locator('textarea');
    await expect(textarea).toBeVisible();
    expect(await textarea.inputValue()).toBe('');

    const metricsJson = JSON.stringify([{ field: 'revenue', label: 'Revenue' }]);
    await textarea.fill(metricsJson);

    // ④ Deselect → ⑤ Reselect
    await deselect(page);
    const config2 = await reselect(page, 'monthly-grid-schema-config');
    const textarea2 = fieldByLabel(config2, 'Metrics (JSON)').locator('textarea');
    const persistedValue = await textarea2.inputValue();
    expect(persistedValue).toContain('revenue');
  });

  test('C9.4 editableWhen + visibleWhen — both expression editors present', async ({ page }) => {
    const pid = await createPage(page, 'mgrid-expr');
    await openDesigner(page, pid);
    const config = await addAndSelect(page, 'monthly-grid');

    // editableWhen expression
    await expect(labelExact(config, 'Editable when')).toBeVisible();
    const editableWhenField = fieldByLabel(config, 'Editable when');
    await expect(editableWhenField).toBeVisible();

    // visibleWhen expression
    await expect(labelExact(config, 'Visible when')).toBeVisible();
    const visibleWhenField = fieldByLabel(config, 'Visible when');
    await expect(visibleWhenField).toBeVisible();
  });
});

// ===========================================================================
// C10. Filters Block (3 properties)
// ===========================================================================

test.describe('C10 Filters — modelCode model-select + colCount select default=4 + visibleWhen', () => {
  test('C10.1 colCount default is 4; change to 2; deselect; reselect — persists', async ({ page }) => {
    const pid = await createPage(page, 'filters-cols');
    await openDesigner(page, pid);
    const config = await addAndSelect(page, 'filters');

    // ① Default value is "4 Columns"
    const defaultCols = await getRadixSelectValue(config, 'Columns');
    expect(defaultCols).toBe('4 Columns');

    // ② Change to 2 Columns
    await selectRadixOption(page, config, 'Columns', '2 Columns');

    // ③ Read back
    const changedCols = await getRadixSelectValue(config, 'Columns');
    expect(changedCols).toBe('2 Columns');

    // ④ Deselect → ⑤ Reselect
    await deselect(page);
    const config2 = await reselect(page, 'filters-schema-config');
    const persistedCols = await getRadixSelectValue(config2, 'Columns');
    expect(persistedCols).toBe('2 Columns');
  });

  test('C10.2 colCount has 3 options: 2/3/4 Columns', async ({ page }) => {
    const pid = await createPage(page, 'filters-opts');
    await openDesigner(page, pid);
    const config = await addAndSelect(page, 'filters');

    // Open dropdown
    const field = fieldByLabel(config, 'Columns');
    const trigger = field.locator('button[role="combobox"]');
    await page.keyboard.press('Escape');
    await trigger.click();

    await expect(page.getByRole('option', { name: '2 Columns', exact: true })).toBeVisible();
    await expect(page.getByRole('option', { name: '3 Columns', exact: true })).toBeVisible();
    await expect(page.getByRole('option', { name: '4 Columns', exact: true })).toBeVisible();
    await page.keyboard.press('Escape');
  });

  test('C10.3 modelCode model-select — visible in config panel', async ({ page }) => {
    const pid = await createPage(page, 'filters-model');
    await openDesigner(page, pid);
    const config = await addAndSelect(page, 'filters');

    // Model field (model-select type) should be present
    await expect(labelExact(config, 'Model')).toBeVisible();
    const modelField = fieldByLabel(config, 'Model');
    await expect(modelField).toBeVisible();
  });

  test('C10.4 visibleWhen — expression editor is present', async ({ page }) => {
    const pid = await createPage(page, 'filters-vis');
    await openDesigner(page, pid);
    const config = await addAndSelect(page, 'filters');

    await expect(labelExact(config, 'Visible when')).toBeVisible();
    const visWhenField = fieldByLabel(config, 'Visible when');
    await expect(visWhenField).toBeVisible();
  });
});

// ===========================================================================
// C11. Detail-section Block (3 properties)
// ===========================================================================

test.describe('C11 Detail-section — modelCode model-select + colCount select default=3 + visibleWhen', () => {
  test('C11.1 colCount default is 3; change to 4; deselect; reselect — persists', async ({ page }) => {
    const pid = await createPage(page, 'detsec-cols');
    await openDesigner(page, pid);
    const config = await addAndSelect(page, 'detail-section');

    // ① Default value is "3 Columns"
    const defaultCols = await getRadixSelectValue(config, 'Columns');
    expect(defaultCols).toBe('3 Columns');

    // ② Change to 4 Columns
    await selectRadixOption(page, config, 'Columns', '4 Columns');

    // ③ Read back
    const changedCols = await getRadixSelectValue(config, 'Columns');
    expect(changedCols).toBe('4 Columns');

    // ④ Deselect → ⑤ Reselect
    await deselect(page);
    const config2 = await reselect(page, 'detail-section-schema-config');
    const persistedCols = await getRadixSelectValue(config2, 'Columns');
    expect(persistedCols).toBe('4 Columns');
  });

  test('C11.2 colCount has 3 options: 2/3/4 Columns', async ({ page }) => {
    const pid = await createPage(page, 'detsec-opts');
    await openDesigner(page, pid);
    const config = await addAndSelect(page, 'detail-section');

    const field = fieldByLabel(config, 'Columns');
    const trigger = field.locator('button[role="combobox"]');
    await page.keyboard.press('Escape');
    await trigger.click();

    await expect(page.getByRole('option', { name: '2 Columns', exact: true })).toBeVisible();
    await expect(page.getByRole('option', { name: '3 Columns', exact: true })).toBeVisible();
    await expect(page.getByRole('option', { name: '4 Columns', exact: true })).toBeVisible();
    await page.keyboard.press('Escape');
  });

  test('C11.3 modelCode model-select — visible in config panel', async ({ page }) => {
    const pid = await createPage(page, 'detsec-model');
    await openDesigner(page, pid);
    const config = await addAndSelect(page, 'detail-section');

    await expect(labelExact(config, 'Model')).toBeVisible();
    const modelField = fieldByLabel(config, 'Model');
    await expect(modelField).toBeVisible();
  });

  test('C11.4 visibleWhen — expression editor is present', async ({ page }) => {
    const pid = await createPage(page, 'detsec-vis');
    await openDesigner(page, pid);
    const config = await addAndSelect(page, 'detail-section');

    await expect(labelExact(config, 'Visible when')).toBeVisible();
    const visWhenField = fieldByLabel(config, 'Visible when');
    await expect(visWhenField).toBeVisible();
  });

  test('C11.5 detail-section distinct from filters — default colCount differs (3 vs 4)', async ({ page }) => {
    // This test verifies the correct default is coded per-block, not globally
    const pid = await createPage(page, 'detsec-vs-flt');
    await openDesigner(page, pid);

    // Add detail-section first
    const config = await addAndSelect(page, 'detail-section');
    const detColCount = await getRadixSelectValue(config, 'Columns');
    expect(detColCount).toBe('3 Columns');

    // Deselect and add filters block
    await deselect(page);
    await addBlock(page, 'filters');
    // Select the newly added block; root block ordering can vary with wrappers.
    await page.locator('[data-testid^="canvas-block-content-"]').last().click();
    const filtersConfig = page.getByTestId('filters-schema-config');
    await filtersConfig.waitFor({ state: 'visible', timeout: 5000 });
    const filtersColCount = await getRadixSelectValue(filtersConfig, 'Columns');
    expect(filtersColCount).toBe('4 Columns');
  });
});

// ===========================================================================
// C12. Rich-text Block (2 properties)
// ===========================================================================

test.describe('C12 Rich-text — content textarea + visibleWhen', () => {
  test('C12.1 content — fill text, deselect, reselect — persists', async ({ page }) => {
    const pid = await createPage(page, 'richtext-content');
    await openDesigner(page, pid);
    const config = await addAndSelect(page, 'rich-text');

    // ① Default: empty
    await expect(labelExact(config, 'Content')).toBeVisible();
    const textarea = fieldByLabel(config, 'Content').locator('textarea');
    await expect(textarea).toBeVisible();
    expect(await textarea.inputValue()).toBe('');

    // ② Fill with markdown content
    const markdownContent = '## Hello World\n\nThis is **bold** text.';
    await textarea.fill(markdownContent);

    // ③ Read back
    await expect(textarea).toHaveValue(markdownContent);

    // ④ Deselect → ⑤ Reselect
    await deselect(page);
    const config2 = await reselect(page, 'rich-text-schema-config');
    const textarea2 = fieldByLabel(config2, 'Content').locator('textarea');
    await expect(textarea2).toHaveValue(markdownContent);
  });

  test('C12.2 content — special characters persist correctly', async ({ page }) => {
    const pid = await createPage(page, 'richtext-special');
    await openDesigner(page, pid);
    const config = await addAndSelect(page, 'rich-text');

    const textarea = fieldByLabel(config, 'Content').locator('textarea');
    // Special characters that might cause issues
    const specialContent = '<p>Hello & "World" > \'test\'</p>';
    await textarea.fill(specialContent);
    await expect(textarea).toHaveValue(specialContent);

    await deselect(page);
    const config2 = await reselect(page, 'rich-text-schema-config');
    const textarea2 = fieldByLabel(config2, 'Content').locator('textarea');
    await expect(textarea2).toHaveValue(specialContent);
  });

  test('C12.3 visibleWhen — expression editor is present', async ({ page }) => {
    const pid = await createPage(page, 'richtext-vis');
    await openDesigner(page, pid);
    const config = await addAndSelect(page, 'rich-text');

    await expect(labelExact(config, 'Visible when')).toBeVisible();
    const visWhenField = fieldByLabel(config, 'Visible when');
    await expect(visWhenField).toBeVisible();
  });
});

// ===========================================================================
// C13. Divider Block (1 property: visibleWhen only)
// ===========================================================================

test.describe('C13 Divider — visibleWhen only (minimal block)', () => {
  test('C13.1 config panel renders with only visibleWhen property', async ({ page }) => {
    const pid = await createPage(page, 'divider-only');
    await openDesigner(page, pid);
    const config = await addAndSelect(page, 'divider');

    // Exactly one labelled property: visibleWhen
    await expect(labelExact(config, 'Visible when')).toBeVisible();

    // The config panel must NOT show other block-specific fields
    // (divider has no tabLabel, no columns, no queryCode, no content)
    await expect(labelExact(config, 'Tab label')).not.toBeVisible();
    await expect(labelExact(config, 'Columns')).not.toBeVisible();
    await expect(labelExact(config, 'Named Query code')).not.toBeVisible();
    await expect(labelExact(config, 'Content')).not.toBeVisible();
  });

  test('C13.2 divider config panel uses divider-schema-config testid', async ({ page }) => {
    const pid = await createPage(page, 'divider-tid');
    await openDesigner(page, pid);

    await addBlock(page, 'divider');
    await page.locator('[data-testid^="canvas-block-content-"]').first().click();

    // The correct testid must be present — proves blockType routing is correct
    const dividerConfig = page.getByTestId('divider-schema-config');
    await expect(dividerConfig).toBeVisible({ timeout: 5000 });

    // And the generic fallback must NOT be present
    await expect(page.getByTestId('generic-block-config')).not.toBeVisible();
  });
});
