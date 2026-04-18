/**
 * DEPRECATED: Deep Component Coverage E2E Tests
 *
 * This file tested the CanvasEditor for kind='composite' pages.
 * Composite kind was removed in V2 unification (2026-04-15).
 * All tests in this file are SKIPPED.
 *
 * Migration:
 * - Dashboard pages now use Dashboard DSL instead
 * - Page Designer is for CRUD pages (list/form/detail)
 * - Widget pages use blocks structure (kind='dashboard')
 *
 * @since 4.0.0
 * @deprecated 4.0.0 — composite kind no longer supported
 */

import { test, expect, type Page } from '../../fixtures';
import { uniqueId } from '../helpers/index';

const BLOCK_SEL =
  '[data-testid^="canvas-block-"]' +
  ':not([data-testid*="-drag-"])' +
  ':not([data-testid*="-remove-"])' +
  ':not([data-testid*="-content-"])' +
  ':not([data-testid*="-drop-"])';
const PREFIX = uniqueId('PDV2D');

let pagePid: string;
let pageKey: string;

test.beforeEach(async ({ page }) => {
  const resp = await page.request.post('/api/pages', {
    data: {
      name: `${PREFIX}_deep`,
      pageKey: `${PREFIX.toLowerCase()}_deep`,
      title: 'Untitled',
      kind: 'list',
      modelCode: 'tenant',
      blocks: [],
      semver: '0.1.0',
    },
  });
  const body = await resp.json();
  pagePid = body.data.pid;
  pageKey = body.data.pageKey;
});

test.afterEach(async ({ page }) => {
  if (!pagePid) return;
  await page.request.delete(`/api/pages/${pagePid}`).catch(() => null);
});

/** Navigate to editor and wait for canvas */
async function openEditor(page: Page) {
  await page.goto(`/page-designer/${pagePid}`);
  await expect(page.getByTestId('designer-canvas')).toBeVisible({ timeout: 15_000 });
}

function configInput(page: Page, name: string) {
  return page.locator(`[name="${name}"], [id="${name}"]`).first();
}

async function selectConfigOption(page: Page, fieldId: string, optionText: string): Promise<void> {
  await page.locator(`[id="${fieldId}"]`).first().click();
  await page
    .locator('[role="option"], [data-radix-collection-item]')
    .filter({ hasText: optionText })
    .first()
    .click();
}

/** Add a block via palette click and return block count */
async function addBlockViaPalette(page: Page, blockType: string): Promise<number> {
  const tab = page.getByTestId('canvas-left-tab-components');
  if (await tab.isVisible({ timeout: 1_000 }).catch(() => false)) await tab.click();
  await page.getByTestId(`block-palette-item-${blockType}`).click();
  const blocks = page.getByTestId('canvas-body').locator(BLOCK_SEL);
  const count = await blocks.count();
  return count;
}

/** Wait for auto-save PUT */
async function waitForSave(page: Page): Promise<void> {
  await page.waitForResponse(
    r => r.url().includes('/api/pages/') && r.request().method() === 'PUT' && r.status() === 200,
    { timeout: 10_000 },
  );
}

// Skip all tests: composite kind removed in V2 unification
test.describe.skip('Composite kind removed in V2 unification (2026-04-15)', () => {

// =============================================================================
// Section 1: Layout Structure
// =============================================================================
test.describe('Layout Structure', () => {
  test('three-panel layout renders correctly', async ({ page }) => {
    await openEditor(page);

    // Main container
    await expect(page.getByTestId('designer-canvas')).toBeVisible();

    // Left panel + tabs
    await expect(page.getByTestId('canvas-left-panel')).toBeVisible();
    await expect(page.getByTestId('canvas-left-tabs')).toBeVisible();
    await expect(page.getByTestId('canvas-left-tab-components')).toBeVisible();
    await expect(page.getByTestId('canvas-left-tab-fields')).toBeVisible();
    await expect(page.getByTestId('canvas-left-tab-outline')).toBeVisible();

    // Center canvas
    await expect(page.getByTestId('canvas-body')).toBeVisible();
    await expect(page.getByTestId('canvas-inline-title')).toBeVisible();

    // Right panel
    await expect(page.getByTestId('canvas-right-panel')).toBeVisible();
  });
});

// =============================================================================
// Section 2: Empty State + Quick-Add
// =============================================================================
test.describe('Empty State + Quick-Add', () => {
  test('empty canvas shows quick-add buttons', async ({ page }) => {
    await openEditor(page);
    await expect(page.getByTestId('canvas-empty-state')).toBeVisible();
    await expect(page.getByTestId('canvas-quick-add-table')).toBeVisible();
    await expect(page.getByTestId('canvas-quick-add-form')).toBeVisible();
    await expect(page.getByTestId('canvas-quick-add-chart')).toBeVisible();
  });

  test.fixme('quick-add table creates block and hides empty state', async ({ page }) => {
    await openEditor(page);
    await page.getByTestId('canvas-quick-add-table').click();
    const blocks = page.getByTestId('canvas-body').locator(BLOCK_SEL);
    await expect(blocks).toHaveCount(1);
    await expect(page.getByTestId('canvas-empty-state')).not.toBeVisible();
  });

  test('quick-add form creates form-section block', async ({ page }) => {
    await openEditor(page);
    await page.getByTestId('canvas-quick-add-form').click();
    const blocks = page.getByTestId('canvas-body').locator(BLOCK_SEL);
    await expect(blocks).toHaveCount(1);
  });

  test('quick-add chart creates chart block', async ({ page }) => {
    await openEditor(page);
    await page.getByTestId('canvas-quick-add-chart').click();
    const blocks = page.getByTestId('canvas-body').locator(BLOCK_SEL);
    await expect(blocks).toHaveCount(1);
  });
});

// =============================================================================
// Section 3: Inline Title
// =============================================================================
test.describe('Inline Title', () => {
  test('title input accepts text and holds value', async ({ page }) => {
    await openEditor(page);
    const input = page.getByTestId('canvas-title-input');
    await expect(input).toBeVisible();
    await input.fill(`${PREFIX} Deep Title`);
    await expect(input).toHaveValue(`${PREFIX} Deep Title`);
  });

  test('description input accepts text', async ({ page }) => {
    await openEditor(page);
    const input = page.getByTestId('canvas-description-input');
    await expect(input).toBeVisible();
    await input.fill('Deep test description');
    await expect(input).toHaveValue('Deep test description');
  });
});

// =============================================================================
// Section 4: Block Palette (all 8 types)
// =============================================================================
test.describe('Block Palette', () => {
  const BLOCK_TYPES = ['table', 'sub-table', 'chart', 'stat-card', 'tabs', 'divider', 'form-section', 'rich-text'];

  test('all 8 block types visible in palette', async ({ page }) => {
    await openEditor(page);
    await page.getByTestId('canvas-left-tab-components').click();
    await expect(page.getByTestId('block-palette')).toBeVisible();

    for (const type of BLOCK_TYPES) {
      await expect(page.getByTestId(`block-palette-item-${type}`)).toBeVisible();
    }
  });

  test('palette search filters block types', async ({ page }) => {
    await openEditor(page);
    await page.getByTestId('canvas-left-tab-components').click();

    const searchInput = page.getByTestId('block-palette-search');
    await expect(searchInput).toBeVisible();

    // Search for "table" — should show table and sub-table
    await searchInput.fill('table');
    await expect(page.getByTestId('block-palette-item-table')).toBeVisible();
    await expect(page.getByTestId('block-palette-item-sub-table')).toBeVisible();
    await expect(page.getByTestId('block-palette-item-chart')).not.toBeVisible();

    // Clear search — all visible again
    await searchInput.fill('');
    await expect(page.getByTestId('block-palette-item-chart')).toBeVisible();
  });

  for (const type of BLOCK_TYPES) {
    test(`clicking palette item "${type}" adds block to canvas`, async ({ page }) => {
      await openEditor(page);
      const count = await addBlockViaPalette(page, type);
      expect(count).toBeGreaterThanOrEqual(1);
    });
  }
});

// =============================================================================
// Section 5: Block Selection + Config Panel Routing
// =============================================================================
test.describe('Block Selection + Config Panel', () => {
  test('no selection shows empty config', async ({ page }) => {
    await openEditor(page);
    await expect(page.getByTestId('block-config-empty')).toBeVisible();
  });

  test('selecting block shows config panel with correct tabs', async ({ page }) => {
    await openEditor(page);
    await addBlockViaPalette(page, 'table');

    const block = page.getByTestId('canvas-body').locator(BLOCK_SEL).first();
    await block.click();

    await expect(page.getByTestId('block-config-panel')).toBeVisible();
    await expect(page.getByTestId('block-config-tabs')).toBeVisible();
    await expect(page.getByTestId('block-config-tab-properties')).toBeVisible();
    await expect(page.getByTestId('block-config-tab-interaction')).toBeVisible();
    await expect(page.getByTestId('block-config-tab-page')).toBeVisible();
    await expect(page.getByTestId('block-config-empty')).not.toBeVisible();
  });

  test('selecting table block shows TableBlockConfig', async ({ page }) => {
    await openEditor(page);
    await addBlockViaPalette(page, 'table');
    // Click the block to select it and wait for config panel to render
    const block = page.getByTestId('canvas-body').locator(BLOCK_SEL).first();
    await block.click();
    // Wait for right panel to update after selection
    await expect(page.getByTestId('block-config-panel')).toBeVisible({ timeout: 5000 });
    await expect(page.getByTestId('table-schema-config')).toBeVisible({ timeout: 5000 });
  });

  test('selecting form-section shows FormSectionConfig', async ({ page }) => {
    await openEditor(page);
    await addBlockViaPalette(page, 'form-section');
    await page.getByTestId('canvas-body').locator(BLOCK_SEL).first().click();
    await expect(page.getByTestId('form-section-schema-config')).toBeVisible();
  });

  test('selecting chart shows ChartBlockConfig', async ({ page }) => {
    await openEditor(page);
    await addBlockViaPalette(page, 'chart');
    await page.getByTestId('canvas-body').locator(BLOCK_SEL).first().click();
    await expect(page.getByTestId('chart-schema-config')).toBeVisible();
  });

  test('selecting tabs shows TabsBlockConfig', async ({ page }) => {
    await openEditor(page);
    await addBlockViaPalette(page, 'tabs');
    await page.getByTestId('canvas-body').locator(BLOCK_SEL).first().click();
    await expect(page.getByTestId('tabs-schema-config')).toBeVisible();
  });

  test('selecting sub-table shows SubTableConfig', async ({ page }) => {
    await openEditor(page);
    await addBlockViaPalette(page, 'sub-table');
    await page.getByTestId('canvas-body').locator(BLOCK_SEL).first().click();
    await expect(page.getByTestId('sub-table-schema-config')).toBeVisible();
  });

  test('selecting stat-card shows StatCardConfig', async ({ page }) => {
    await openEditor(page);
    await addBlockViaPalette(page, 'stat-card');
    await page.getByTestId('canvas-body').locator(BLOCK_SEL).first().click();
    await expect(page.getByTestId('stat-card-schema-config')).toBeVisible();
  });

  test('selecting divider shows schema config', async ({ page }) => {
    await openEditor(page);
    await addBlockViaPalette(page, 'divider');
    await page.getByTestId('canvas-body').locator(BLOCK_SEL).first().click();
    await expect(page.getByTestId('divider-schema-config')).toBeVisible();
  });

  test('interaction tab shows V2 placeholder', async ({ page }) => {
    await openEditor(page);
    await addBlockViaPalette(page, 'table');
    await page.getByTestId('canvas-body').locator(BLOCK_SEL).first().click();
    await page.getByTestId('block-config-tab-interaction').click();
    await expect(page.getByTestId('block-config-interaction')).toBeVisible();
  });

  test('page tab shows PageSettingsPanel', async ({ page }) => {
    await openEditor(page);
    await page.getByTestId('block-config-tab-page').click();
    await expect(page.getByTestId('page-settings-panel')).toBeVisible();
    await expect(page.getByTestId('page-settings-title')).toBeVisible();
    // page-settings-key only renders when pageKey is set (conditional render)
    await expect(page.getByTestId('page-settings-description')).toBeVisible();
  });
});

// =============================================================================
// Section 6: TableBlockConfig — every field
// =============================================================================
test.describe('TableBlockConfig — all fields', () => {
  test('data source section: model, query, pageSize, sort', async ({ page }) => {
    await openEditor(page);
    await addBlockViaPalette(page, 'table');
    await page.getByTestId('canvas-body').locator(BLOCK_SEL).first().click();
    await expect(page.getByTestId('table-schema-config')).toBeVisible();
    await expect(page.getByText('Data Source')).toBeVisible();
    await expect(page.locator('label').filter({ hasText: 'Model' }).first()).toBeVisible();
    await expect(configInput(page, 'queryType')).toBeVisible();

    const pageSize = configInput(page, 'features.pagination.pageSize');
    await expect(pageSize).toBeVisible();
    await pageSize.fill('50');
    await expect(pageSize).toHaveValue('50');

    await expect(configInput(page, 'defaultSortField')).toBeVisible();
  });

  test('query code input appears when NamedQuery selected', async ({ page }) => {
    await openEditor(page);
    await addBlockViaPalette(page, 'table');
    await page.getByTestId('canvas-body').locator(BLOCK_SEL).first().click();

    await selectConfigOption(page, 'queryType', 'Named Query');
    const queryCode = configInput(page, 'queryCode');
    await expect(queryCode).toBeVisible();
    await queryCode.fill('nq:my_query');
    await expect(queryCode).toHaveValue('nq:my_query');
  });

  test('7 feature toggles are visible and interactive', async ({ page }) => {
    await openEditor(page);
    await addBlockViaPalette(page, 'table');
    await page.getByTestId('canvas-body').locator(BLOCK_SEL).first().click();

    const config = page.getByTestId('table-schema-config');
    const toggles = config.locator('button[role="switch"]');
    const count = await toggles.count();
    expect(count).toBeGreaterThanOrEqual(7);
  });

  test('create command select appears when create enabled', async ({ page }) => {
    await openEditor(page);
    await addBlockViaPalette(page, 'table');
    await page.getByTestId('canvas-body').locator(BLOCK_SEL).first().click();

    await configInput(page, 'features.create.enabled').click();
    await expect(configInput(page, 'features.create.commandCode')).toBeVisible();
  });

  test('row click and create mode selects', async ({ page }) => {
    await openEditor(page);
    await addBlockViaPalette(page, 'table');
    await page.getByTestId('canvas-body').locator(BLOCK_SEL).first().click();

    await expect(configInput(page, 'rowClick')).toBeVisible();
    await selectConfigOption(page, 'rowClick', 'New page');
    await expect(page.locator('[id="rowClick"]')).toContainText('New page');

    await expect(configInput(page, 'features.create.openMode')).toBeVisible();
    await selectConfigOption(page, 'features.create.openMode', 'New page');
    await expect(page.locator('[id="features.create.openMode"]')).toContainText('New page');
  });

  test('default filters and summary fields render progressively', async ({ page }) => {
    await openEditor(page);
    await addBlockViaPalette(page, 'table');
    await page.getByTestId('canvas-body').locator(BLOCK_SEL).first().click();
    await expect(configInput(page, 'defaultFilters')).toBeVisible();
    await expect(configInput(page, 'summary.fields')).not.toBeVisible();
    await configInput(page, 'summary.enabled').click();
    await expect(configInput(page, 'summary.fields')).toBeVisible();
  });
});

// =============================================================================
// Section 7: FormSectionConfig — every field
// =============================================================================
test.describe('FormSectionConfig — all fields', () => {
  test('model select and mode group visible', async ({ page }) => {
    await openEditor(page);
    await addBlockViaPalette(page, 'form-section');
    const block = page.getByTestId('canvas-body').locator(BLOCK_SEL).first();
    await block.waitFor({ state: 'visible', timeout: 8_000 });
    await block.click({ timeout: 10_000 });

    await expect(page.getByTestId('form-section-schema-config')).toBeVisible();
    await expect(page.locator('label').filter({ hasText: 'Model' }).first()).toBeVisible();
    await expect(configInput(page, 'mode')).toBeVisible();
  });

  test.fixme('3 mode buttons: display, create, edit', async ({ page }) => {
    await openEditor(page);
    await addBlockViaPalette(page, 'form-section');
    await page.getByTestId('canvas-body').locator(BLOCK_SEL).first().click();

    await page.locator('[id="mode"]').click();
    await expect(page.locator('[role="option"], [data-radix-collection-item]').filter({ hasText: 'Display' }).first()).toBeVisible();
    await expect(page.locator('[role="option"], [data-radix-collection-item]').filter({ hasText: 'Create' }).first()).toBeVisible();
    await expect(page.locator('[role="option"], [data-radix-collection-item]').filter({ hasText: 'Edit' }).first()).toBeVisible();
    await page.keyboard.press('Escape');
    await selectConfigOption(page, 'mode', 'Create');
    await expect(configInput(page, 'commandCode')).toBeVisible();
    await expect(configInput(page, 'afterSubmit')).toBeVisible();
  });

  test('afterSubmit has 4 options', async ({ page }) => {
    await openEditor(page);
    await addBlockViaPalette(page, 'form-section');
    await page.getByTestId('canvas-body').locator(BLOCK_SEL).first().click();
    await selectConfigOption(page, 'mode', 'Create');
    await page.locator('[id="afterSubmit"]').click();
    const options = page.locator('[role="option"], [data-radix-collection-item]');
    await expect(options.filter({ hasText: 'Show toast' }).first()).toBeVisible();
    await expect(options.filter({ hasText: 'Refresh page' }).first()).toBeVisible();
    await expect(options.filter({ hasText: 'Navigate away' }).first()).toBeVisible();
    await expect(options.filter({ hasText: 'Clear form' }).first()).toBeVisible();
  });

  test('column count selector (2/3/4)', async ({ page }) => {
    await openEditor(page);
    await addBlockViaPalette(page, 'form-section');
    await page.getByTestId('canvas-body').locator(BLOCK_SEL).first().click();

    await expect(configInput(page, 'colCount')).toBeVisible();
    await page.locator('[id="colCount"]').click();
    await expect(page.locator('[role="option"], [data-radix-collection-item]').filter({ hasText: '2' }).first()).toBeVisible();
    await expect(page.locator('[role="option"], [data-radix-collection-item]').filter({ hasText: '3' }).first()).toBeVisible();
    await expect(page.locator('[role="option"], [data-radix-collection-item]').filter({ hasText: '4' }).first()).toBeVisible();
  });

  test('submit text and button toggles are editable', async ({ page }) => {
    await openEditor(page);
    await addBlockViaPalette(page, 'form-section');
    await page.getByTestId('canvas-body').locator(BLOCK_SEL).first().click();

    const submitText = configInput(page, 'submitText');
    await expect(submitText).toBeVisible();
    await submitText.fill('Submit');
    await expect(submitText).toHaveValue('Submit');
    await expect(configInput(page, 'showCancel')).toBeVisible();
    await expect(configInput(page, 'showReset')).toBeVisible();
  });
});

// =============================================================================
// Section 8: ChartBlockConfig — every field
// =============================================================================
test.describe('ChartBlockConfig — all fields', () => {
  test('data source mode selector (3 modes)', async ({ page }) => {
    await openEditor(page);
    await addBlockViaPalette(page, 'chart');
    await page.getByTestId('canvas-body').locator(BLOCK_SEL).first().click();

    await expect(page.getByTestId('chart-schema-config')).toBeVisible();
    await expect(configInput(page, 'dataSourceMode')).toBeVisible();
    await page.locator('[id="dataSourceMode"]').click();
    await expect(page.locator('[role="option"], [data-radix-collection-item]').filter({ hasText: 'Model Aggregate' }).first()).toBeVisible();
    await expect(page.locator('[role="option"], [data-radix-collection-item]').filter({ hasText: 'Named Query' }).first()).toBeVisible();
    await expect(page.locator('[role="option"], [data-radix-collection-item]').filter({ hasText: 'Custom API' }).first()).toBeVisible();
  });

  test('model aggregate mode: model, metric, aggregation, group', async ({ page }) => {
    await openEditor(page);
    await addBlockViaPalette(page, 'chart');
    await page.getByTestId('canvas-body').locator(BLOCK_SEL).first().click();

    await expect(page.locator('label').filter({ hasText: 'Model' }).first()).toBeVisible();
    await expect(configInput(page, 'metricField')).toBeVisible();
    await expect(configInput(page, 'aggregation')).toBeVisible();
    await expect(configInput(page, 'groupDimension')).toBeVisible();
  });

  test('namedQuery mode: query code input', async ({ page }) => {
    await openEditor(page);
    await addBlockViaPalette(page, 'chart');
    await page.getByTestId('canvas-body').locator(BLOCK_SEL).first().click();

    await selectConfigOption(page, 'dataSourceMode', 'Named Query');
    const qc = configInput(page, 'dataSource.queryCode');
    await expect(qc).toBeVisible();
    await qc.fill('nq:my_chart_query');
    await expect(qc).toHaveValue('nq:my_chart_query');
  });

  test('custom API mode: endpoint + params', async ({ page }) => {
    await openEditor(page);
    await addBlockViaPalette(page, 'chart');
    await page.getByTestId('canvas-body').locator(BLOCK_SEL).first().click();

    await selectConfigOption(page, 'dataSourceMode', 'Custom API');
    await expect(configInput(page, 'dataSource.endpoint')).toBeVisible();
    await expect(configInput(page, 'dataSource.params')).toBeVisible();
  });

  test('chart type selector includes common types', async ({ page }) => {
    await openEditor(page);
    await addBlockViaPalette(page, 'chart');
    await page.getByTestId('canvas-body').locator(BLOCK_SEL).first().click();

    await expect(configInput(page, 'chartType')).toBeVisible();
    await page.locator('[id="chartType"]').click();
    await expect(page.locator('[role="option"], [data-radix-collection-item]').filter({ hasText: 'Bar' }).first()).toBeVisible();
    await expect(page.locator('[role="option"], [data-radix-collection-item]').filter({ hasText: 'Line' }).first()).toBeVisible();
    await expect(page.locator('[role="option"], [data-radix-collection-item]').filter({ hasText: 'Pie' }).first()).toBeVisible();
    await expect(page.locator('[role="option"], [data-radix-collection-item]').filter({ hasText: 'Area' }).first()).toBeVisible();
    await page.keyboard.press('Escape');
    await selectConfigOption(page, 'chartType', 'Line');
    await expect(page.locator('[id="chartType"]')).toContainText('Line');
  });

  test('style: title + height inputs', async ({ page }) => {
    await openEditor(page);
    await addBlockViaPalette(page, 'chart');
    await page.getByTestId('canvas-body').locator(BLOCK_SEL).first().click();

    const title = configInput(page, 'chartTitle');
    await expect(title).toBeVisible();
    await title.fill('My Chart');
    await expect(title).toHaveValue('My Chart');

    const height = configInput(page, 'height');
    await expect(height).toBeVisible();
    await height.fill('400');
    await expect(height).toHaveValue('400');
  });
});

// =============================================================================
// Section 9: TabsBlockConfig
// =============================================================================
test.describe('TabsBlockConfig', () => {
  test('tabs schema shows current fields', async ({ page }) => {
    await openEditor(page);
    await addBlockViaPalette(page, 'tabs');
    await page.getByTestId('canvas-body').locator(BLOCK_SEL).first().click();

    await expect(page.getByTestId('tabs-schema-config')).toBeVisible();
    await expect(configInput(page, 'defaultActiveTab')).toBeVisible();
    await expect(configInput(page, 'tabPosition')).toBeVisible();
    await expect(configInput(page, 'tabs')).toBeVisible();
  });

  test('tab position select updates', async ({ page }) => {
    await openEditor(page);
    await addBlockViaPalette(page, 'tabs');
    await page.getByTestId('canvas-body').locator(BLOCK_SEL).first().click();

    await selectConfigOption(page, 'tabPosition', 'Left');
    await expect(page.locator('[id="tabPosition"]')).toContainText('Left');
  });

  test('tabs JSON field accepts content', async ({ page }) => {
    await openEditor(page);
    await addBlockViaPalette(page, 'tabs');
    await page.getByTestId('canvas-body').locator(BLOCK_SEL).first().click();

    const tabsJson = configInput(page, 'tabs');
    await tabsJson.fill('[{\"key\":\"overview\",\"label\":\"Overview\"}]');
    await expect(tabsJson).toContainText('overview');
  });
});

// =============================================================================
// Section 10: SubTableConfig
// =============================================================================
test.describe('SubTableConfig', () => {
  test('all fields visible', async ({ page }) => {
    await openEditor(page);
    await addBlockViaPalette(page, 'sub-table');
    await page.getByTestId('canvas-body').locator(BLOCK_SEL).first().click();

    await expect(page.getByTestId('sub-table-schema-config')).toBeVisible();
    await expect(configInput(page, 'relationMode')).toBeVisible();
    await expect(page.locator('label').filter({ hasText: 'Child model' }).first()).toBeVisible();
  });

  test('foreignKey mode shows FK field', async ({ page }) => {
    await openEditor(page);
    await addBlockViaPalette(page, 'sub-table');
    await page.getByTestId('canvas-body').locator(BLOCK_SEL).first().click();

    await expect(configInput(page, 'foreignKeyField')).toBeVisible();
  });

  test('resolveVia mode shows junction fields', async ({ page }) => {
    await openEditor(page);
    await addBlockViaPalette(page, 'sub-table');
    await page.getByTestId('canvas-body').locator(BLOCK_SEL).first().click();

    await selectConfigOption(page, 'relationMode', 'Resolve via (indirect)');
    await expect(page.locator('label').filter({ hasText: 'Junction model' }).first()).toBeVisible();
    await expect(configInput(page, 'parentFkInJunction')).toBeVisible();
    await expect(configInput(page, 'childFkInJunction')).toBeVisible();
  });

  test('dataSource mode shows endpoint', async ({ page }) => {
    await openEditor(page);
    await addBlockViaPalette(page, 'sub-table');
    await page.getByTestId('canvas-body').locator(BLOCK_SEL).first().click();

    await selectConfigOption(page, 'relationMode', 'Data Source (API)');
    await expect(configInput(page, 'dataSource.endpoint')).toBeVisible();
  });

  test('tab label and max rows inputs', async ({ page }) => {
    await openEditor(page);
    await addBlockViaPalette(page, 'sub-table');
    await page.getByTestId('canvas-body').locator(BLOCK_SEL).first().click();

    const tabLabel = configInput(page, 'tabLabel');
    await expect(tabLabel).toBeVisible();
    await tabLabel.fill('Cost Items');
    await expect(tabLabel).toHaveValue('Cost Items');

    const maxRows = configInput(page, 'maxRows');
    await expect(maxRows).toBeVisible();
    await maxRows.fill('100');
    await expect(maxRows).toHaveValue('100');
  });
});

// =============================================================================
// Section 11: StatCardConfig
// =============================================================================
test.describe('StatCardConfig', () => {
  test('query code and conditions field visible', async ({ page }) => {
    await openEditor(page);
    await addBlockViaPalette(page, 'stat-card');
    await page.getByTestId('canvas-body').locator(BLOCK_SEL).first().click();

    await expect(page.getByTestId('stat-card-schema-config')).toBeVisible();
    await expect(configInput(page, 'dataSource.queryCode')).toBeVisible();
    await expect(page.locator('label').filter({ hasText: 'Visible when' }).first()).toBeVisible();
  });

  test('query code input accepts text', async ({ page }) => {
    await openEditor(page);
    await addBlockViaPalette(page, 'stat-card');
    await page.getByTestId('canvas-body').locator(BLOCK_SEL).first().click();

    const qc = configInput(page, 'dataSource.queryCode');
    await qc.fill('nq:dashboard_stats');
    await expect(qc).toHaveValue('nq:dashboard_stats');
  });

  test('layout section remains visible for stat card', async ({ page }) => {
    await openEditor(page);
    await addBlockViaPalette(page, 'stat-card');
    await page.getByTestId('canvas-body').locator(BLOCK_SEL).first().click();

    await expect(page.getByTestId('layout-section')).toBeVisible();
  });

  test('visibleWhen expression field is rendered', async ({ page }) => {
    await openEditor(page);
    await addBlockViaPalette(page, 'stat-card');
    await page.getByTestId('canvas-body').locator(BLOCK_SEL).first().click();

    await expect(page.locator('label').filter({ hasText: 'Visible when' }).first()).toBeVisible();
  });
});

// =============================================================================
// Section 12: PageSettingsPanel
// =============================================================================
test.describe('PageSettingsPanel', () => {
  test('all settings fields visible and editable', async ({ page }) => {
    await openEditor(page);

    // Switch to Page tab
    // First ensure we see the right panel tabs — may need to select a block first or click Page tab directly
    const pageTab = page.getByTestId('block-config-tab-page');
    if (await pageTab.isVisible({ timeout: 2_000 }).catch(() => false)) {
      await pageTab.click();
    }

    await expect(page.getByTestId('page-settings-panel')).toBeVisible();

    // Title
    const title = page.getByTestId('page-settings-title');
    await expect(title).toBeVisible();
    await title.fill('Settings Title Test');
    await expect(title).toHaveValue('Settings Title Test');

    // Page key (readonly) — only renders when pageKey is set
    const key = page.getByTestId('page-settings-key');
    const keyVisible = await key.isVisible({ timeout: 1_000 }).catch(() => false);
    if (keyVisible) {
      await expect(key).toBeVisible();
    }

    // Description
    const desc = page.getByTestId('page-settings-description');
    await expect(desc).toBeVisible();
    await desc.fill('Settings description');
    await expect(desc).toHaveValue('Settings description');
  });
});

// =============================================================================
// Section 13: Block Deletion
// =============================================================================
test.describe('Block Deletion', () => {
  test.fixme('remove button deletes block from canvas', async ({ page }) => {
    await openEditor(page);
    await addBlockViaPalette(page, 'table');
    await addBlockViaPalette(page, 'chart');

    const blocks = page.getByTestId('canvas-body').locator(BLOCK_SEL);
    await expect(blocks).toHaveCount(2);

    // Get first block id and delete it
    const firstBlock = blocks.first();
    const testid = await firstBlock.getAttribute('data-testid');
    const blockId = testid?.replace('canvas-block-', '');
    expect(blockId).toBeTruthy();

    await firstBlock.hover();
    await page.getByTestId(`canvas-block-remove-${blockId}`).click();
    await expect(blocks).toHaveCount(1);
  });

  test('deleting all blocks shows empty state again', async ({ page }) => {
    await openEditor(page);
    await addBlockViaPalette(page, 'table');

    const blocks = page.getByTestId('canvas-body').locator(BLOCK_SEL);
    await expect(blocks).toHaveCount(1);

    const testid = await blocks.first().getAttribute('data-testid');
    const blockId = testid?.replace('canvas-block-', '');
    await blocks.first().hover();
    const removeBtn = page.getByTestId(`canvas-block-remove-${blockId}`);
    await expect(removeBtn).toBeVisible({ timeout: 5000 });
    await removeBtn.click();

    await expect(blocks).toHaveCount(0, { timeout: 5000 });
    await expect(page.getByTestId('canvas-empty-state')).toBeVisible({ timeout: 5000 });
  });
});

// =============================================================================
// Section 14: Outline Panel
// =============================================================================
test.describe('Outline Panel', () => {
  test('outline shows blocks and click selects', async ({ page }) => {
    await openEditor(page);
    await addBlockViaPalette(page, 'table');
    await addBlockViaPalette(page, 'form-section');

    // Switch to outline tab
    await page.getByTestId('canvas-left-tab-outline').click();
    await expect(page.getByTestId('outline-panel')).toBeVisible();

    // Should show 2 items
    const items = page.getByTestId('outline-panel').locator('[data-testid^="outline-item-"]');
    await expect(items).toHaveCount(2);

    // Click first outline item → should select the block
    await items.first().click();
    await expect(page.getByTestId('block-config-panel')).toBeVisible();
    await expect(page.getByTestId('block-config-empty')).not.toBeVisible();
  });
});

// =============================================================================
// Section 15: Left Panel Tab Switching
// =============================================================================
test.describe('Left Panel Tabs', () => {
  test('switching tabs shows correct panel content', async ({ page }) => {
    await openEditor(page);

    // Components tab (default)
    await page.getByTestId('canvas-left-tab-components').click();
    await expect(page.getByTestId('block-palette')).toBeVisible();

    // Fields tab
    await page.getByTestId('canvas-left-tab-fields').click();
    await expect(page.getByTestId('field-palette')).toBeVisible();
    await expect(page.getByTestId('field-palette-model-select')).toBeVisible();

    // Outline tab
    await page.getByTestId('canvas-left-tab-outline').click();
    await expect(page.getByTestId('outline-panel')).toBeVisible();

    // Back to components
    await page.getByTestId('canvas-left-tab-components').click();
    await expect(page.getByTestId('block-palette')).toBeVisible();
  });
});
}); // end skip: composite kind removed
