/**
 * Page Designer — Table Block Property Panel Deep Tests (C2)
 *
 * Covers ALL 21 table properties with the 5-step verification pattern:
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
 * Properties tested:
 *   - queryType            (select, default="Default list")
 *   - queryCode            (text, dependsOn queryType=namedQuery)
 *   - pageSize             (number, default=20)
 *   - defaultSortField     (text)
 *   - features.search      (boolean, default=false)
 *   - features.filter      (boolean, default=false)
 *   - features.sort        (boolean, default=false)
 *   - features.create.enabled (boolean, default=false → commandCode appears)
 *   - features.create.commandCode (text, dependsOn enabled)
 *   - features.batchActions (boolean, default=false)
 *   - features.export      (boolean, default=false)
 *   - features.pagination.enabled (boolean, default=true)
 *   - rowClick             (select, default="Open drawer")
 *   - rowActionsEnabled    (boolean, default=false → rowActions JSON appears)
 *   - rowActions           (json, dependsOn rowActionsEnabled)
 *   - features.create.openMode (select, default="Modal")
 *   - defaultFilters       (json)
 *   - summary.enabled      (boolean, default=false → summary.fields appears)
 *   - summary.fields       (json, dependsOn summary.enabled)
 *   - visibleWhen          (expression editor)
 *   - className            (text)
 *
 * Dimensions covered: D2 (render), D5 (component types), D8 (property persistence)
 * Not applicable: D1 (designer tool, no sidebar menu), D3/D9/D10 (no status machine),
 *   D4/D6/D7/D11/D12/D13/D14 (not a CRUD model)
 *
 * Auth: tests/auth/admin.json
 *
 * @since 4.3.0
 */

import { test, expect, type Page, type Locator } from '@playwright/test';
import { uniqueId } from '../helpers/index';

// NOTE (2026-04-17): post-merge 5f72469b, kind=list routes to ListConfigPanel
// (schema-driven tab panel), NOT BlocksDesigner canvas. The Table block is
// list-only. Dragging it onto a canvas is no longer a supported UX — per
// design §5.1, equivalent configuration lives in ListConfigPanel →
// Columns / Filters / Toolbar / Behavior tabs.
//
// All describes in this file exercise the legacy "drag table to canvas,
// configure via block property panel" flow. They are skipped (describe.skip)
// as a reference for the properties that ListConfigPanel now owns. Parity
// coverage should be added in tests/e2e/studio/list-config-panel-*.spec.ts.

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

type SwitchState = 'checked' | 'unchecked';

// ---------------------------------------------------------------------------
// Page setup helpers
// ---------------------------------------------------------------------------

async function createPage(page: Page): Promise<string> {
  const name = uniqueId('tprop');
  const pageKey = `e2e_tprop_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 7)}`;

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

async function openDesigner(page: Page, pid: string): Promise<void> {
  await page.goto(`/page-designer/${pid}`, { waitUntil: 'domcontentloaded' });
  await page.getByTestId('designer-canvas').waitFor({ state: 'visible', timeout: 15000 });
}

async function addAndSelectTable(page: Page): Promise<void> {
  // The palette item double-arms both dnd-kit useDraggable and native HTML5
  // drag; under Playwright the drag-init logic can occasionally swallow the
  // click, so retry once if the block fails to appear.
  const blockContent = page.locator('[data-testid^="canvas-block-content-"]').first();
  const clickPalette = async (timeoutMs: number) => {
    const quickAdd = page.getByTestId('canvas-quick-add-table');
    if (await quickAdd.isVisible()) {
      await quickAdd.click();
    } else {
      await page.getByTestId('designer-tab-blocks').click();
      await page.getByTestId('block-palette-item-table').click();
    }
    return blockContent
      .waitFor({ state: 'visible', timeout: timeoutMs })
      .then(() => true)
      .catch(() => false);
  };

  // First attempt preserves the original long wait (15s) because initial
  // render after page load can be slow; retry with a short wait only if the
  // click was dropped entirely (drag-init race swallowed it).
  const appeared = (await clickPalette(15_000)) || (await clickPalette(3000));
  if (!appeared) {
    throw new Error('addAndSelectTable: table block never appeared after 2 click attempts');
  }

  // Click to select and wait for config panel to appear.
  // RGL re-keys children on layout/auto-flow changes, so the just-rendered
  // block-content node can be detached mid-click. We retry the click in a
  // small loop, re-resolving the locator each time, instead of letting one
  // long click() spin on a stale node handle.
  const configPanel = page.getByTestId('table-schema-config');
  let opened = false;
  for (let attempt = 0; attempt < 4 && !opened; attempt++) {
    try {
      await page
        .locator('[data-testid^="canvas-block-content-"]')
        .first()
        .click({ timeout: 3000 });
      opened = await configPanel
        .waitFor({ state: 'visible', timeout: 2000 })
        .then(() => true)
        .catch(() => false);
    } catch {
      // detached / not stable — re-resolve and retry
    }
  }
  if (!opened) {
    throw new Error('addAndSelectTable: table-schema-config did not open after 4 click attempts');
  }
}

/** Click canvas background to deselect blocks */
async function deselect(page: Page): Promise<void> {
  await page.getByTestId('canvas-body').click({ position: { x: 10, y: 10 } });
}

/** Reselect the first table block by clicking its content area */
async function reselectTable(page: Page): Promise<void> {
  await page.locator('[data-testid^="canvas-block-content-"]').first().click();
  await page.getByTestId('table-schema-config').waitFor({ state: 'visible', timeout: 5000 });
}

// ---------------------------------------------------------------------------
// Field locator helpers
// ---------------------------------------------------------------------------

/** Find a label by exact text within the config panel */
function labelExact(container: Locator, labelText: string): Locator {
  return container.locator(`label:text-is("${labelText}")`).first();
}

/**
 * Find the container wrapping a field by its label text.
 * For switches, the label is a sibling of the switch, both inside a flex parent.
 * For inputs/selects, the label is above the control inside FieldBase.
 */
function fieldByLabel(container: Locator, labelText: string): Locator {
  return container.locator(`label:text-is("${labelText}")`).first().locator('..');
}

/** Get the switch button for a field identified by label */
function switchFor(container: Locator, labelText: string): Locator {
  return fieldByLabel(container, labelText).locator('button[role="switch"]');
}

/** Get the combobox (Select trigger) for a field identified by label */
function selectFor(container: Locator, labelText: string): Locator {
  return fieldByLabel(container, labelText).locator('button[role="combobox"]');
}

/** Get the text input for a field identified by label */
function inputFor(container: Locator, labelText: string): Locator {
  return fieldByLabel(container, labelText).locator('input');
}

/** Get the textarea for a field identified by label */
function textareaFor(container: Locator, labelText: string): Locator {
  return fieldByLabel(container, labelText).locator('textarea');
}

// ---------------------------------------------------------------------------
// Interaction helpers
// ---------------------------------------------------------------------------

/** Set a Radix Select field to the given option name */
async function setSelect(page: Page, trigger: Locator, optionName: string): Promise<void> {
  await trigger.click();
  await page.getByRole('option', { name: optionName, exact: true }).click();
}

/** Get the current displayed text of a Select trigger */
async function getSelectText(trigger: Locator): Promise<string> {
  return (await trigger.textContent()) ?? '';
}

/** Get switch state */
async function getSwitchState(sw: Locator): Promise<SwitchState> {
  const state = await sw.getAttribute('data-state');
  return (state as SwitchState) ?? 'unchecked';
}

// ---------------------------------------------------------------------------
// C2.1 — Comprehensive single-test covering ALL table properties in sequence
// ---------------------------------------------------------------------------

test.describe.skip('C2 — Table Block Properties (all 21)', () => {
  test(
    'C2.all: covers all table properties in sequence with 5-step verification',
    async ({ page }) => {
      const pid = await createPage(page);
      await openDesigner(page, pid);
      await addAndSelectTable(page);

      const config = page.getByTestId('table-schema-config');

      // ──────────────────────────────────────────────────────────────────────
      // P1: queryType (select, default = "Default list")
      // ──────────────────────────────────────────────────────────────────────
      const queryTypeTrigger = selectFor(config, 'Query');
      await expect(queryTypeTrigger, 'queryType: field should be visible').toBeVisible();
      const queryTypeDefault = await getSelectText(queryTypeTrigger);
      expect(queryTypeDefault, 'queryType: default should be "Default list"').toContain(
        'Default list',
      );

      // Change to Named Query
      await setSelect(page, queryTypeTrigger, 'Named Query');
      expect(await getSelectText(queryTypeTrigger), 'queryType: should show Named Query').toContain(
        'Named Query',
      );

      // Deselect and reselect → verify persistence
      await deselect(page);
      await reselectTable(page);
      expect(
        await getSelectText(selectFor(config, 'Query')),
        'queryType: should persist after reselect',
      ).toContain('Named Query');

      // ──────────────────────────────────────────────────────────────────────
      // P2: queryCode (text, dependsOn queryType=namedQuery) — now visible
      // ──────────────────────────────────────────────────────────────────────
      const queryCodeLabel = labelExact(config, 'Query Code');
      await expect(queryCodeLabel, 'queryCode: should appear when queryType=namedQuery').toBeVisible();

      const queryCodeInput = inputFor(config, 'Query Code');
      await expect(queryCodeInput).toBeVisible();

      // Fill the query code
      await queryCodeInput.fill('my_custom_query');
      await expect(queryCodeInput).toHaveValue('my_custom_query');

      // Deselect and reselect → verify persistence
      await deselect(page);
      await reselectTable(page);
      await expect(labelExact(config, 'Query Code'), 'queryCode: should still be visible').toBeVisible();
      await expect(
        inputFor(config, 'Query Code'),
        'queryCode: value should persist',
      ).toHaveValue('my_custom_query');

      // Reset queryType to default (changes dependsOn state — queryCode disappears)
      await setSelect(page, selectFor(config, 'Query'), 'Default list');
      await expect(
        labelExact(config, 'Query Code'),
        'queryCode: should disappear when queryType=default',
      ).not.toBeVisible();

      // ──────────────────────────────────────────────────────────────────────
      // P3: pageSize (number, key=features.pagination.pageSize, default=20)
      // ──────────────────────────────────────────────────────────────────────
      const pageSizeInput = inputFor(config, 'Page Size');
      await expect(pageSizeInput, 'pageSize: should be visible').toBeVisible();

      const pageSizeDefault = await pageSizeInput.inputValue();
      expect(pageSizeDefault, 'pageSize: default should be 20').toBe('20');

      // Change to 50
      await pageSizeInput.fill('50');
      await expect(pageSizeInput).toHaveValue('50');

      // Deselect and reselect → verify persistence
      await deselect(page);
      await reselectTable(page);
      await expect(inputFor(config, 'Page Size'), 'pageSize: should persist after reselect').toHaveValue('50');

      // ──────────────────────────────────────────────────────────────────────
      // P4: defaultSortField (text, placeholder="Field code for default sort")
      // ──────────────────────────────────────────────────────────────────────
      const sortFieldInput = inputFor(config, 'Default Sort');
      await expect(sortFieldInput, 'defaultSortField: should be visible').toBeVisible();

      const sortFieldDefault = await sortFieldInput.inputValue();
      expect(sortFieldDefault, 'defaultSortField: default should be empty').toBe('');

      await sortFieldInput.fill('created_at');
      await expect(sortFieldInput).toHaveValue('created_at');

      // Deselect and reselect → verify persistence
      await deselect(page);
      await reselectTable(page);
      await expect(
        inputFor(config, 'Default Sort'),
        'defaultSortField: should persist after reselect',
      ).toHaveValue('created_at');

      // ──────────────────────────────────────────────────────────────────────
      // P5: features.search (boolean, default=false)
      // ──────────────────────────────────────────────────────────────────────
      const searchSwitch = switchFor(config, 'Search');
      await expect(searchSwitch, 'search: switch should be visible').toBeVisible();
      expect(await getSwitchState(searchSwitch), 'search: default should be OFF').toBe('unchecked');

      await searchSwitch.click();
      expect(await getSwitchState(searchSwitch), 'search: should be ON after click').toBe('checked');

      await deselect(page);
      await reselectTable(page);
      expect(
        await getSwitchState(switchFor(config, 'Search')),
        'search: ON state should persist',
      ).toBe('checked');

      // ──────────────────────────────────────────────────────────────────────
      // P6: features.filter (boolean, default=false)
      // ──────────────────────────────────────────────────────────────────────
      const filterSwitch = switchFor(config, 'Filter');
      await expect(filterSwitch, 'filter: switch should be visible').toBeVisible();
      expect(await getSwitchState(filterSwitch), 'filter: default should be OFF').toBe('unchecked');

      await filterSwitch.click();
      expect(await getSwitchState(filterSwitch), 'filter: should be ON after click').toBe('checked');

      await deselect(page);
      await reselectTable(page);
      expect(
        await getSwitchState(switchFor(config, 'Filter')),
        'filter: ON state should persist',
      ).toBe('checked');

      // ──────────────────────────────────────────────────────────────────────
      // P7: features.sort (boolean, default=false)
      // ──────────────────────────────────────────────────────────────────────
      // Note: use exact match to avoid matching "Default Sort" label
      const sortSwitch = switchFor(config, 'Sort');
      await expect(sortSwitch, 'sort: switch should be visible').toBeVisible();
      expect(await getSwitchState(sortSwitch), 'sort: default should be OFF').toBe('unchecked');

      await sortSwitch.click();
      expect(await getSwitchState(sortSwitch), 'sort: should be ON after click').toBe('checked');

      await deselect(page);
      await reselectTable(page);
      expect(
        await getSwitchState(switchFor(config, 'Sort')),
        'sort: ON state should persist',
      ).toBe('checked');

      // ──────────────────────────────────────────────────────────────────────
      // P8: features.create.enabled (boolean, default=false → commandCode appears)
      // ──────────────────────────────────────────────────────────────────────
      const createEnabledSwitch = switchFor(config, 'Create button');
      await expect(createEnabledSwitch, 'create.enabled: switch should be visible').toBeVisible();
      expect(await getSwitchState(createEnabledSwitch), 'create.enabled: default should be OFF').toBe('unchecked');

      // commandCode should be hidden by default (dependsOn create.enabled=true)
      await expect(
        labelExact(config, 'Create Command'),
        'create.commandCode: should be hidden by default',
      ).not.toBeVisible();

      // Toggle ON → commandCode should appear
      await createEnabledSwitch.click();
      expect(await getSwitchState(createEnabledSwitch), 'create.enabled: should be ON').toBe('checked');
      await expect(
        labelExact(config, 'Create Command'),
        'create.commandCode: should appear when create.enabled=true',
      ).toBeVisible();

      // Deselect and reselect → verify persistence
      await deselect(page);
      await reselectTable(page);
      expect(
        await getSwitchState(switchFor(config, 'Create button')),
        'create.enabled: ON state should persist',
      ).toBe('checked');
      await expect(
        labelExact(config, 'Create Command'),
        'create.commandCode: should still be visible after reselect',
      ).toBeVisible();

      // ──────────────────────────────────────────────────────────────────────
      // P9: features.create.commandCode (text, dependsOn create.enabled=true)
      // ──────────────────────────────────────────────────────────────────────
      const commandCodeInput = inputFor(config, 'Create Command');
      await expect(commandCodeInput, 'create.commandCode: input should be visible').toBeVisible();
      await expect(commandCodeInput).toHaveValue('');

      await commandCodeInput.fill('create_order');
      await expect(commandCodeInput).toHaveValue('create_order');

      await deselect(page);
      await reselectTable(page);
      await expect(
        inputFor(config, 'Create Command'),
        'create.commandCode: should persist after reselect',
      ).toHaveValue('create_order');

      // Toggle create.enabled OFF → commandCode disappears
      const createEnabledSwitch2 = switchFor(config, 'Create button');
      await createEnabledSwitch2.click();
      expect(await getSwitchState(createEnabledSwitch2), 'create.enabled: toggled back OFF').toBe('unchecked');
      await expect(
        labelExact(config, 'Create Command'),
        'create.commandCode: should disappear when create.enabled=false',
      ).not.toBeVisible();

      // ──────────────────────────────────────────────────────────────────────
      // P10: features.batchActions (boolean, default=false)
      // ──────────────────────────────────────────────────────────────────────
      const batchSwitch = switchFor(config, 'Batch actions');
      await expect(batchSwitch, 'batchActions: switch should be visible').toBeVisible();
      expect(await getSwitchState(batchSwitch), 'batchActions: default should be OFF').toBe('unchecked');

      await batchSwitch.click();
      expect(await getSwitchState(batchSwitch), 'batchActions: should be ON after click').toBe('checked');

      await deselect(page);
      await reselectTable(page);
      expect(
        await getSwitchState(switchFor(config, 'Batch actions')),
        'batchActions: ON state should persist',
      ).toBe('checked');

      // ──────────────────────────────────────────────────────────────────────
      // P11: features.export (boolean, default=false)
      // ──────────────────────────────────────────────────────────────────────
      const exportSwitch = switchFor(config, 'Export');
      await expect(exportSwitch, 'export: switch should be visible').toBeVisible();
      expect(await getSwitchState(exportSwitch), 'export: default should be OFF').toBe('unchecked');

      await exportSwitch.click();
      expect(await getSwitchState(exportSwitch), 'export: should be ON after click').toBe('checked');

      await deselect(page);
      await reselectTable(page);
      expect(
        await getSwitchState(switchFor(config, 'Export')),
        'export: ON state should persist',
      ).toBe('checked');

      // ──────────────────────────────────────────────────────────────────────
      // P12: features.pagination.enabled (boolean, default=TRUE)
      // ──────────────────────────────────────────────────────────────────────
      const paginationSwitch = switchFor(config, 'Pagination');
      await expect(paginationSwitch, 'pagination: switch should be visible').toBeVisible();
      expect(await getSwitchState(paginationSwitch), 'pagination: default should be ON').toBe('checked');

      // Toggle OFF
      await paginationSwitch.click();
      expect(await getSwitchState(paginationSwitch), 'pagination: should be OFF after click').toBe('unchecked');

      await deselect(page);
      await reselectTable(page);
      expect(
        await getSwitchState(switchFor(config, 'Pagination')),
        'pagination: OFF state should persist',
      ).toBe('unchecked');

      // Restore ON
      await switchFor(config, 'Pagination').click();

      // ──────────────────────────────────────────────────────────────────────
      // P13: rowClick (select, default="Open drawer", options: drawer/page/expand/none)
      // ──────────────────────────────────────────────────────────────────────
      const rowClickTrigger = selectFor(config, 'Row click');
      await expect(rowClickTrigger, 'rowClick: field should be visible').toBeVisible();
      const rowClickDefault = await getSelectText(rowClickTrigger);
      expect(rowClickDefault, 'rowClick: default should be "Open drawer"').toContain('Open drawer');

      // Change to "New page"
      await setSelect(page, rowClickTrigger, 'New page');
      expect(await getSelectText(rowClickTrigger), 'rowClick: should show "New page"').toContain(
        'New page',
      );

      await deselect(page);
      await reselectTable(page);
      expect(
        await getSelectText(selectFor(config, 'Row click')),
        'rowClick: should persist after reselect',
      ).toContain('New page');

      // ──────────────────────────────────────────────────────────────────────
      // P14: rowActionsEnabled (boolean, default=false → rowActions JSON appears)
      // ──────────────────────────────────────────────────────────────────────
      const rowActionsEnabledSwitch = switchFor(config, 'Row actions');
      await expect(rowActionsEnabledSwitch, 'rowActionsEnabled: switch should be visible').toBeVisible();
      expect(await getSwitchState(rowActionsEnabledSwitch), 'rowActionsEnabled: default should be OFF').toBe('unchecked');

      // rowActions JSON editor should be hidden by default
      await expect(
        labelExact(config, 'Row Actions (JSON)'),
        'rowActions: JSON editor should be hidden by default',
      ).not.toBeVisible();

      // Toggle ON → rowActions JSON editor appears
      await rowActionsEnabledSwitch.click();
      expect(await getSwitchState(rowActionsEnabledSwitch), 'rowActionsEnabled: should be ON').toBe('checked');
      await expect(
        labelExact(config, 'Row Actions (JSON)'),
        'rowActions: JSON editor should appear when rowActionsEnabled=true',
      ).toBeVisible();

      await deselect(page);
      await reselectTable(page);
      expect(
        await getSwitchState(switchFor(config, 'Row actions')),
        'rowActionsEnabled: ON state should persist',
      ).toBe('checked');
      await expect(
        labelExact(config, 'Row Actions (JSON)'),
        'rowActions: JSON editor should still be visible after reselect',
      ).toBeVisible();

      // ──────────────────────────────────────────────────────────────────────
      // P15: rowActions (json, dependsOn rowActionsEnabled=true)
      // ──────────────────────────────────────────────────────────────────────
      const rowActionsTextarea = textareaFor(config, 'Row Actions (JSON)');
      await expect(rowActionsTextarea, 'rowActions: textarea should be visible').toBeVisible();
      await expect(rowActionsTextarea).not.toBeDisabled();

      const rowActionsJson = '[{"label":"Edit","commandCode":"edit_record"}]';
      await rowActionsTextarea.fill(rowActionsJson);

      // Verify the value was accepted (may be pretty-printed)
      const rawValue = await rowActionsTextarea.inputValue();
      expect(
        JSON.stringify(JSON.parse(rawValue)),
        'rowActions: JSON value should be semantically correct',
      ).toBe(JSON.stringify(JSON.parse(rowActionsJson)));

      // Toggle rowActionsEnabled back OFF → textarea disappears
      await switchFor(config, 'Row actions').click();
      expect(await getSwitchState(switchFor(config, 'Row actions'))).toBe('unchecked');
      await expect(
        labelExact(config, 'Row Actions (JSON)'),
        'rowActions: JSON editor should disappear when disabled',
      ).not.toBeVisible();

      // ──────────────────────────────────────────────────────────────────────
      // P16: features.create.openMode (select, default="Modal")
      // ──────────────────────────────────────────────────────────────────────
      const openModeTrigger = selectFor(config, 'Create open mode');
      await expect(openModeTrigger, 'openMode: field should be visible').toBeVisible();
      const openModeDefault = await getSelectText(openModeTrigger);
      expect(openModeDefault, 'openMode: default should be "Modal"').toContain('Modal');

      // Change to "New page"
      await setSelect(page, openModeTrigger, 'New page');
      expect(await getSelectText(openModeTrigger), 'openMode: should show "New page"').toContain(
        'New page',
      );

      await deselect(page);
      await reselectTable(page);
      expect(
        await getSelectText(selectFor(config, 'Create open mode')),
        'openMode: should persist after reselect',
      ).toContain('New page');

      // ──────────────────────────────────────────────────────────────────────
      // P17: defaultFilters (json)
      // ──────────────────────────────────────────────────────────────────────
      const defaultFiltersTextarea = textareaFor(config, 'Default Filters (JSON)');
      await expect(defaultFiltersTextarea, 'defaultFilters: textarea should be visible').toBeVisible();
      await expect(defaultFiltersTextarea).not.toBeDisabled();

      const filtersJson = '[{"fieldName":"status","operator":"eq","value":"active"}]';
      await defaultFiltersTextarea.fill(filtersJson);

      const filtersRaw = await defaultFiltersTextarea.inputValue();
      expect(
        JSON.stringify(JSON.parse(filtersRaw)),
        'defaultFilters: JSON value should be semantically correct',
      ).toBe(JSON.stringify(JSON.parse(filtersJson)));

      await deselect(page);
      await reselectTable(page);

      const filtersAfterReselect = await textareaFor(config, 'Default Filters (JSON)').inputValue();
      expect(
        JSON.stringify(JSON.parse(filtersAfterReselect)),
        'defaultFilters: should persist after reselect',
      ).toBe(JSON.stringify(JSON.parse(filtersJson)));

      // ──────────────────────────────────────────────────────────────────────
      // P18: summary.enabled (boolean, default=false → summary.fields appears)
      // ──────────────────────────────────────────────────────────────────────
      const summaryEnabledSwitch = switchFor(config, 'Show summary row');
      await expect(summaryEnabledSwitch, 'summary.enabled: switch should be visible').toBeVisible();
      expect(await getSwitchState(summaryEnabledSwitch), 'summary.enabled: default should be OFF').toBe('unchecked');

      // summary.fields should be hidden by default
      await expect(
        labelExact(config, 'Summary Fields (JSON)'),
        'summary.fields: should be hidden by default',
      ).not.toBeVisible();

      // Toggle ON → summary.fields appears
      await summaryEnabledSwitch.click();
      expect(await getSwitchState(summaryEnabledSwitch), 'summary.enabled: should be ON').toBe('checked');
      await expect(
        labelExact(config, 'Summary Fields (JSON)'),
        'summary.fields: should appear when summary.enabled=true',
      ).toBeVisible();

      await deselect(page);
      await reselectTable(page);
      expect(
        await getSwitchState(switchFor(config, 'Show summary row')),
        'summary.enabled: ON state should persist',
      ).toBe('checked');
      await expect(
        labelExact(config, 'Summary Fields (JSON)'),
        'summary.fields: should still be visible after reselect',
      ).toBeVisible();

      // ──────────────────────────────────────────────────────────────────────
      // P19: summary.fields (json, dependsOn summary.enabled=true)
      // ──────────────────────────────────────────────────────────────────────
      const summaryFieldsTextarea = textareaFor(config, 'Summary Fields (JSON)');
      await expect(summaryFieldsTextarea, 'summary.fields: textarea should be visible').toBeVisible();
      await expect(summaryFieldsTextarea).not.toBeDisabled();

      const summaryJson = '[{"field":"amount","aggregation":"SUM"}]';
      await summaryFieldsTextarea.fill(summaryJson);

      const summaryRaw = await summaryFieldsTextarea.inputValue();
      expect(
        JSON.stringify(JSON.parse(summaryRaw)),
        'summary.fields: JSON value should be semantically correct',
      ).toBe(JSON.stringify(JSON.parse(summaryJson)));

      // summary.fields disappears when summary.enabled=false
      await switchFor(config, 'Show summary row').click();
      expect(await getSwitchState(switchFor(config, 'Show summary row'))).toBe('unchecked');
      await expect(
        labelExact(config, 'Summary Fields (JSON)'),
        'summary.fields: should disappear when disabled',
      ).not.toBeVisible();

      // ──────────────────────────────────────────────────────────────────────
      // P20: visibleWhen (expression editor)
      // ExpressionEditor renders in "builder" mode by default (ConditionBuilder)
      // with a toggle between "Conditions" and "Expression" modes.
      // The ExpressionEditor container has data-testid="expression-editor".
      // ──────────────────────────────────────────────────────────────────────
      // The label is inside ExpressionEditor itself (not in a FieldBase wrapper),
      // so we look for the expression-editor testid within the config panel.
      const expressionEditor = config.locator('[data-testid="expression-editor"]');
      await expect(expressionEditor, 'visibleWhen: expression editor component should be visible').toBeVisible();

      // Verify the mode toggle buttons are present (proves ExpressionEditor rendered correctly)
      await expect(
        expressionEditor.locator('[data-testid="mode-builder"]'),
        'visibleWhen: Conditions mode button should be visible',
      ).toBeVisible();
      await expect(
        expressionEditor.locator('[data-testid="mode-text"]'),
        'visibleWhen: Expression mode button should be visible',
      ).toBeVisible();

      // ──────────────────────────────────────────────────────────────────────
      // P21: className (text, placeholder="e.g. compact-table")
      // ──────────────────────────────────────────────────────────────────────
      const classNameInput = inputFor(config, 'CSS Class');
      await expect(classNameInput, 'className: input should be visible').toBeVisible();
      await expect(classNameInput).toHaveValue('');

      await classNameInput.fill('compact-table');
      await expect(classNameInput).toHaveValue('compact-table');

      await deselect(page);
      await reselectTable(page);
      await expect(
        inputFor(config, 'CSS Class'),
        'className: should persist after reselect',
      ).toHaveValue('compact-table');
    },
  );
});

// ---------------------------------------------------------------------------
// C2.dep1 — dependsOn chain: queryType → queryCode
// ---------------------------------------------------------------------------

test.describe.skip('C2 dependsOn — queryType controls queryCode visibility', () => {
  test('queryCode hidden when queryType=default, visible when queryType=namedQuery', async ({
    page,
  }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);
    await addAndSelectTable(page);

    const config = page.getByTestId('table-schema-config');

    // Step 1: Default state — queryType=default, queryCode hidden
    const queryTypeTrigger = selectFor(config, 'Query');
    await expect(queryTypeTrigger).toBeVisible();
    expect(await getSelectText(queryTypeTrigger)).toContain('Default list');
    await expect(
      labelExact(config, 'Query Code'),
      'queryCode: should be hidden by default',
    ).not.toBeVisible();

    // Step 2: Change queryType to namedQuery → queryCode appears
    await setSelect(page, queryTypeTrigger, 'Named Query');
    await expect(
      labelExact(config, 'Query Code'),
      'queryCode: should appear when queryType=namedQuery',
    ).toBeVisible();

    const queryCodeInput = inputFor(config, 'Query Code');
    await expect(queryCodeInput).toBeVisible();
    await queryCodeInput.fill('my_named_query');
    await expect(queryCodeInput).toHaveValue('my_named_query');

    // Step 3: Change queryType back to default → queryCode disappears
    await setSelect(page, selectFor(config, 'Query'), 'Default list');
    await expect(
      labelExact(config, 'Query Code'),
      'queryCode: should disappear when queryType=default',
    ).not.toBeVisible();
  });
});

// ---------------------------------------------------------------------------
// C2.dep2 — dependsOn chain: features.create.enabled → commandCode
// ---------------------------------------------------------------------------

test.describe.skip('C2 dependsOn — create.enabled controls commandCode visibility', () => {
  test('commandCode hidden by default, appears when create.enabled=true, disappears when false', async ({
    page,
  }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);
    await addAndSelectTable(page);

    const config = page.getByTestId('table-schema-config');

    // Step 1: Default state — create.enabled=false, commandCode hidden
    // Scroll into view since "Built-in Features" group may be below the fold
    const createLabel = config.locator('label:text-is("Create button")').first();
    await createLabel.scrollIntoViewIfNeeded();
    const createSwitch = switchFor(config, 'Create button');
    await createSwitch.scrollIntoViewIfNeeded();
    expect(await getSwitchState(createSwitch), 'create.enabled: default OFF').toBe('unchecked');
    await expect(
      labelExact(config, 'Create Command'),
      'commandCode: hidden by default',
    ).not.toBeVisible();

    // Step 2: Enable create → commandCode appears
    await createSwitch.click();
    expect(await getSwitchState(createSwitch)).toBe('checked');
    await expect(
      labelExact(config, 'Create Command'),
      'commandCode: visible when create.enabled=true',
    ).toBeVisible();

    // Fill commandCode
    const commandCodeInput = inputFor(config, 'Create Command');
    await commandCodeInput.fill('create_item');
    await expect(commandCodeInput).toHaveValue('create_item');

    // Step 3: Disable create → commandCode disappears
    await switchFor(config, 'Create button').click();
    expect(await getSwitchState(switchFor(config, 'Create button'))).toBe('unchecked');
    await expect(
      labelExact(config, 'Create Command'),
      'commandCode: disappears when create.enabled=false',
    ).not.toBeVisible();
  });
});

// ---------------------------------------------------------------------------
// C2.dep3 — dependsOn chain: rowActionsEnabled → rowActions JSON
// ---------------------------------------------------------------------------

test.describe.skip('C2 dependsOn — rowActionsEnabled controls rowActions JSON editor', () => {
  test('rowActions JSON hidden by default, appears when rowActionsEnabled=true', async ({
    page,
  }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);
    await addAndSelectTable(page);

    const config = page.getByTestId('table-schema-config');

    // Step 1: Default state — rowActionsEnabled=false, rowActions hidden
    // Scroll into view since "Row Actions" group may be below the fold
    const rowActionsLabel = config.locator('label:text-is("Row actions")').first();
    await rowActionsLabel.scrollIntoViewIfNeeded();
    const rowActionsSwitch = switchFor(config, 'Row actions');
    await rowActionsSwitch.scrollIntoViewIfNeeded();
    expect(await getSwitchState(rowActionsSwitch), 'rowActionsEnabled: default OFF').toBe('unchecked');
    await expect(
      labelExact(config, 'Row Actions (JSON)'),
      'rowActions: hidden by default',
    ).not.toBeVisible();

    // Step 2: Enable → rowActions JSON editor appears
    await rowActionsSwitch.click();
    expect(await getSwitchState(rowActionsSwitch)).toBe('checked');
    await expect(
      labelExact(config, 'Row Actions (JSON)'),
      'rowActions: visible when rowActionsEnabled=true',
    ).toBeVisible();

    // Verify textarea is editable
    const rowActionsTextarea = textareaFor(config, 'Row Actions (JSON)');
    await expect(rowActionsTextarea).toBeVisible();
    await expect(rowActionsTextarea).not.toBeDisabled();
    await rowActionsTextarea.fill('[]');

    // Step 3: Disable → rowActions JSON editor disappears
    await switchFor(config, 'Row actions').click();
    expect(await getSwitchState(switchFor(config, 'Row actions'))).toBe('unchecked');
    await expect(
      labelExact(config, 'Row Actions (JSON)'),
      'rowActions: hidden when rowActionsEnabled=false',
    ).not.toBeVisible();
  });
});

// ---------------------------------------------------------------------------
// C2.dep4 — dependsOn chain: summary.enabled → summary.fields JSON
// ---------------------------------------------------------------------------

test.describe.skip('C2 dependsOn — summary.enabled controls summary.fields JSON editor', () => {
  test('summary.fields JSON hidden by default, appears when summary.enabled=true', async ({
    page,
  }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);
    await addAndSelectTable(page);

    const config = page.getByTestId('table-schema-config');

    // Step 1: Default state — summary.enabled=false, summary.fields hidden
    const summarySwitch = switchFor(config, 'Show summary row');
    expect(await getSwitchState(summarySwitch), 'summary.enabled: default OFF').toBe('unchecked');
    await expect(
      labelExact(config, 'Summary Fields (JSON)'),
      'summary.fields: hidden by default',
    ).not.toBeVisible();

    // Step 2: Enable → summary.fields JSON editor appears
    await summarySwitch.click();
    expect(await getSwitchState(summarySwitch)).toBe('checked');
    await expect(
      labelExact(config, 'Summary Fields (JSON)'),
      'summary.fields: visible when summary.enabled=true',
    ).toBeVisible();

    // Verify textarea is editable with valid JSON
    const summaryFieldsTextarea = textareaFor(config, 'Summary Fields (JSON)');
    await expect(summaryFieldsTextarea).toBeVisible();
    await expect(summaryFieldsTextarea).not.toBeDisabled();
    await summaryFieldsTextarea.fill('[{"field":"total","aggregation":"SUM"}]');
    const rawVal = await summaryFieldsTextarea.inputValue();
    expect(
      JSON.parse(rawVal)[0].aggregation,
      'summary.fields: JSON content is correct',
    ).toBe('SUM');

    // Step 3: Disable → summary.fields JSON editor disappears
    await switchFor(config, 'Show summary row').click();
    expect(await getSwitchState(switchFor(config, 'Show summary row'))).toBe('unchecked');
    await expect(
      labelExact(config, 'Summary Fields (JSON)'),
      'summary.fields: hidden when summary.enabled=false',
    ).not.toBeVisible();
  });
});

// ---------------------------------------------------------------------------
// C2.select — Select field option cycling tests
// ---------------------------------------------------------------------------

test.describe.skip('C2 — Select field options', () => {
  test('queryType: all options available (Default list, Named Query)', async ({ page }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);
    await addAndSelectTable(page);

    const config = page.getByTestId('table-schema-config');
    const trigger = selectFor(config, 'Query');

    // Open and verify options
    await trigger.click();
    await expect(page.getByRole('option', { name: 'Default list' })).toBeVisible();
    await expect(page.getByRole('option', { name: 'Named Query' })).toBeVisible();
    // Close without selecting
    await page.keyboard.press('Escape');
  });

  test('rowClick: all options available (drawer/page/expand/none)', async ({ page }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);
    await addAndSelectTable(page);

    const config = page.getByTestId('table-schema-config');
    const trigger = selectFor(config, 'Row click');

    await trigger.click();
    await expect(page.getByRole('option', { name: 'Open drawer' })).toBeVisible();
    await expect(page.getByRole('option', { name: 'New page' })).toBeVisible();
    await expect(page.getByRole('option', { name: 'Expand inline' })).toBeVisible();
    await expect(page.getByRole('option', { name: 'None' })).toBeVisible();
    await page.keyboard.press('Escape');
  });

  test('openMode: all options available (Modal/New page/Inline)', async ({ page }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);
    await addAndSelectTable(page);

    const config = page.getByTestId('table-schema-config');
    const trigger = selectFor(config, 'Create open mode');

    await trigger.click();
    await expect(page.getByRole('option', { name: 'Modal' })).toBeVisible();
    await expect(page.getByRole('option', { name: 'New page' })).toBeVisible();
    await expect(page.getByRole('option', { name: 'Inline' })).toBeVisible();
    await page.keyboard.press('Escape');
  });

  test('rowClick: cycling through all 4 options persists correctly', async ({ page }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);
    await addAndSelectTable(page);

    const config = page.getByTestId('table-schema-config');
    const trigger = selectFor(config, 'Row click');

    // Default = drawer
    expect(await getSelectText(trigger)).toContain('Open drawer');

    // Cycle: drawer → page → expand → none → drawer
    for (const [optionName, expectedText] of [
      ['New page', 'New page'],
      ['Expand inline', 'Expand inline'],
      ['None', 'None'],
      ['Open drawer', 'Open drawer'],
    ]) {
      await setSelect(page, trigger, optionName as string);
      expect(await getSelectText(trigger)).toContain(expectedText as string);

      // Persist check for each value
      await deselect(page);
      await reselectTable(page);
      expect(await getSelectText(selectFor(config, 'Row click'))).toContain(expectedText as string);
    }
  });
});

// ---------------------------------------------------------------------------
// C2.switches — All boolean properties visible simultaneously
// ---------------------------------------------------------------------------

test.describe.skip('C2 — All boolean switches visible simultaneously', () => {
  test('all 7 boolean switches (excl. dependsOn) are visible in panel with correct defaults', async ({
    page,
  }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);
    await addAndSelectTable(page);

    const config = page.getByTestId('table-schema-config');

    const expectations: Array<{ label: string; defaultState: SwitchState }> = [
      { label: 'Search', defaultState: 'unchecked' },
      { label: 'Filter', defaultState: 'unchecked' },
      { label: 'Sort', defaultState: 'unchecked' },
      { label: 'Create button', defaultState: 'unchecked' },
      { label: 'Batch actions', defaultState: 'unchecked' },
      { label: 'Export', defaultState: 'unchecked' },
      { label: 'Pagination', defaultState: 'checked' }, // default ON
      { label: 'Row actions', defaultState: 'unchecked' },
      { label: 'Show summary row', defaultState: 'unchecked' },
    ];

    for (const { label, defaultState } of expectations) {
      const sw = switchFor(config, label);
      await expect(sw, `Switch for "${label}" should be visible`).toBeVisible();
      expect(
        await getSwitchState(sw),
        `Switch for "${label}" should default to ${defaultState}`,
      ).toBe(defaultState);
    }
  });
});

// ---------------------------------------------------------------------------
// C2.panels — All config groups are present
// ---------------------------------------------------------------------------

test.describe.skip('C2 — Config panel groups are rendered', () => {
  test('all expected config groups appear in the panel', async ({ page }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);
    await addAndSelectTable(page);

    const config = page.getByTestId('table-schema-config');
    await expect(config).toBeVisible();

    // Group header text is stored in DOM with original casing (CSS applies uppercase transform).
    // The SchemaBlockConfigPanel uses the `group` property from PropertySchema as-is.
    // Group headers are rendered as <div> elements (not buttons or labels).
    const expectedGroups = [
      'Data Source',
      'Built-in Features',
      'Behavior',
      'Default Filters',
      'Summary',
      'Conditions',
    ];

    for (const groupName of expectedGroups) {
      // Use div:text-is() to target group header divs specifically (avoids matching buttons)
      await expect(
        config.locator(`div:text-is("${groupName}")`).first(),
        `Group "${groupName}" should be visible in config panel`,
      ).toBeVisible();
    }
  });

  test('panel testid is table-schema-config and is distinct from other block types', async ({
    page,
  }) => {
    const pid = await createPage(page);
    await openDesigner(page, pid);
    await addAndSelectTable(page);

    // The config panel testid follows the pattern: {blockType}-schema-config
    await expect(page.getByTestId('table-schema-config')).toBeVisible();

    // Verify the testid is specific to the table block
    await expect(page.getByTestId('chart-schema-config')).not.toBeVisible();
    await expect(page.getByTestId('form-section-schema-config')).not.toBeVisible();
  });
});
