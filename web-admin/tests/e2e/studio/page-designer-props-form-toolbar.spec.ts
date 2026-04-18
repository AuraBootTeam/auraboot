/**
 * Page Designer — Form-section & Toolbar Block Deep Property Tests (C4 + C5)
 *
 * Two describe blocks:
 *   1. Form-section Block — 9 properties + dependsOn chains + persistence
 *   2. Toolbar Block      — 6 action.type branches + appearance props + persistence
 *
 * Verification pattern (5-step + dependsOn):
 *   ① Read default value  → assert correct
 *   ② Change to new value
 *   ③ Read changed value  → assert correct
 *   ④ Deselect (click canvas-body)
 *   ⑤ Reselect           → assert value persisted
 *   ⑥ dependsOn trigger  → dependent field appears / disappears
 *
 * Actual label names sourced from block schema files:
 *   form-section.ts: 'Submit command', 'After Submit', 'Columns', 'Submit button text',
 *                    'Show cancel button', 'Show reset button', 'Visible when'
 *   toolbar.ts:      'Action Type', 'Command', 'URL', 'Builtin Action', 'Handler',
 *                    'Flow Steps (JSON)', 'Primary', 'Danger', 'Icon',
 *                    'Visible when', 'Enable when', 'Confirm prompt'
 *
 * dependsOn behavior:
 *   - commandCode / afterSubmit: shown when mode = 'create' ONLY (not 'edit')
 *     (schema has dependsOn: { field: 'mode', value: 'create' })
 *   - action.command: shown when action.type ∈ ['command', 'state_transition']
 *
 * Navigation: page.goto() is allowed for Page Designer (platform designer tool
 * exception per AGENTS.md — not a sidebar menu page).
 *
 * Config panel testids:
 *   form-section: data-testid="form-section-schema-config"
 *   toolbar:      data-testid="toolbar-schema-config"
 *
 * Dimensions covered:
 *   D2 (config panel renders), D5 (widget types: select/text/switch/expression),
 *   D8 (persistence: change → deselect → reselect → value still set)
 * Not applicable:
 *   D1 (Page Designer is a platform tool, no sidebar menu),
 *   D3/D9/D10 (no status machine), D4/D6/D7/D11/D12/D13/D14 (not a CRUD model)
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
// Page / designer helpers
// ---------------------------------------------------------------------------

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

async function openDesigner(page: Page, pid: string): Promise<void> {
  await page.goto(`/page-designer/${pid}`, { waitUntil: 'domcontentloaded' });
  await page.getByTestId('designer-canvas').waitFor({ state: 'visible', timeout: 15000 });
}

/** Click canvas background to deselect all blocks */
async function deselect(page: Page): Promise<void> {
  await page.getByTestId('canvas-body').click({ position: { x: 10, y: 10 } });
}

// ---------------------------------------------------------------------------
// Field locator helpers (shared)
// ---------------------------------------------------------------------------

/** Find a label with exact text within a container */
function labelExact(container: Locator, labelText: string): Locator {
  return container.locator(`label:text-is("${labelText}")`).first();
}

/** Get the parent wrapper of a label (contains the control) */
function fieldByLabel(container: Locator, labelText: string): Locator {
  return container.locator(`label:text-is("${labelText}")`).first().locator('..');
}

/** Get the combobox (Radix Select trigger) for a labelled field */
function selectFor(container: Locator, labelText: string): Locator {
  return fieldByLabel(container, labelText).locator('button[role="combobox"]');
}

/** Get the text input for a labelled field */
function inputFor(container: Locator, labelText: string): Locator {
  return fieldByLabel(container, labelText).locator('input');
}

/** Get the switch button for a labelled field */
function switchFor(container: Locator, labelText: string): Locator {
  // BaseSwitch with labelPosition='right' renders: <div class="flex"><div><Switch/></div><label/></div>
  // Use direct-child selector only to avoid matching a parent container that includes multiple switches
  return container.locator(`div:has(> label:text-is("${labelText}")) button[role="switch"]`).first();
}

/** Click a Radix Select trigger and choose an option by exact name */
async function setSelect(page: Page, trigger: Locator, optionName: string): Promise<void> {
  // Close any open portal first
  await page.keyboard.press('Escape');
  await trigger.click();
  await page.getByRole('option', { name: optionName, exact: true }).click();
}

/** Get displayed text of a Select trigger */
async function getSelectText(trigger: Locator): Promise<string> {
  return (await trigger.textContent()) ?? '';
}

/** Get switch data-state attribute */
async function getSwitchState(sw: Locator): Promise<string> {
  return (await sw.getAttribute('data-state')) ?? 'unchecked';
}

// ===========================================================================
// C4 — Form-section Block Deep Property Tests
// ===========================================================================

test.describe('C4 — Form-section Block — Deep Property Tests', () => {
  /**
   * Add a form-section block and select it, opening the config panel.
   * Returns the config panel locator.
   */
  async function addAndSelectFormSection(page: Page): Promise<Locator> {
    await page.getByTestId('canvas-left-tab-components').click();
    const paletteItem = page.getByTestId('block-palette-item-form-section');
    await paletteItem.waitFor({ state: 'visible', timeout: 5000 });
    await paletteItem.click();

    // Wait for block to appear
    await page.locator(BLK).first().waitFor({ state: 'visible', timeout: 5000 });

    // Click content area to select
    await page.locator('[data-testid^="canvas-block-content-"]').first().click();

    // Wait for config panel
    const config = page.getByTestId('form-section-schema-config');
    await config.waitFor({ state: 'visible', timeout: 5000 });
    return config;
  }

  /** Click canvas body to deselect, then reselect form-section */
  async function reselectFormSection(page: Page): Promise<Locator> {
    await deselect(page);
    await page.locator('[data-testid^="canvas-block-content-"]').first().click();
    const config = page.getByTestId('form-section-schema-config');
    await config.waitFor({ state: 'visible', timeout: 5000 });
    return config;
  }

  // ─────────────────────────────────────────────────────────────────────────
  // C4.all — single comprehensive test covering all 9 properties in sequence
  // ─────────────────────────────────────────────────────────────────────────

  test(
    'C4.all: all form-section properties with dependsOn chains and persistence',
    async ({ page }) => {
      const pid = await createPage(page, 'fsec');
      await openDesigner(page, pid);
      let config = await addAndSelectFormSection(page);

      // ────────────────────────────────────────────────────────────────────
      // P1: mode (select, default = "Display")
      //     dependsOn: mode='create' → 'Submit command' + 'After Submit' appear
      //                mode='display' → they are hidden
      //     Note: dependsOn only triggers on 'create', not 'edit' (schema design)
      // ────────────────────────────────────────────────────────────────────
      const modeTrigger = selectFor(config, 'Mode');
      await expect(modeTrigger, 'mode: field should be visible').toBeVisible();

      const modeDefault = await getSelectText(modeTrigger);
      expect(modeDefault, 'mode: default should contain Display').toContain('Display');

      // P1-dependsOn: mode=Display → 'Submit command' and 'After Submit' NOT visible
      await expect(
        labelExact(config, 'Submit command'),
        'commandCode: should NOT be visible when mode=Display',
      ).not.toBeVisible();
      await expect(
        labelExact(config, 'After Submit'),
        'afterSubmit: should NOT be visible when mode=Display',
      ).not.toBeVisible();

      // Change mode to "Create"
      await setSelect(page, modeTrigger, 'Create');
      expect(await getSelectText(selectFor(config, 'Mode')), 'mode: should show Create').toContain('Create');

      // P1-dependsOn: mode=Create → 'Submit command' and 'After Submit' appear
      await expect(
        labelExact(config, 'Submit command'),
        'commandCode: should appear when mode=Create',
      ).toBeVisible();
      await expect(
        labelExact(config, 'After Submit'),
        'afterSubmit: should appear when mode=Create',
      ).toBeVisible();

      // Persist mode=Create
      config = await reselectFormSection(page);
      expect(
        await getSelectText(selectFor(config, 'Mode')),
        'mode: should persist Create after reselect',
      ).toContain('Create');

      // Dependent fields still visible after reselect
      await expect(
        labelExact(config, 'Submit command'),
        'commandCode: should still be visible after reselect with mode=Create',
      ).toBeVisible();
      await expect(
        labelExact(config, 'After Submit'),
        'afterSubmit: should still be visible after reselect with mode=Create',
      ).toBeVisible();

      // ────────────────────────────────────────────────────────────────────
      // P2: commandCode (text, label='Submit command', dependsOn mode=create)
      // ────────────────────────────────────────────────────────────────────
      const cmdCodeInput = inputFor(config, 'Submit command');
      await expect(cmdCodeInput, 'commandCode: input should be visible').toBeVisible();

      await cmdCodeInput.fill('create_order');
      await expect(cmdCodeInput, 'commandCode: should have filled value').toHaveValue('create_order');

      config = await reselectFormSection(page);
      await expect(
        inputFor(config, 'Submit command'),
        'commandCode: value should persist after reselect',
      ).toHaveValue('create_order');

      // ────────────────────────────────────────────────────────────────────
      // P3: afterSubmit (select, default='Show toast', dependsOn mode=create)
      // ────────────────────────────────────────────────────────────────────
      const afterSubmitTrigger = selectFor(config, 'After Submit');
      await expect(afterSubmitTrigger, 'afterSubmit: should be visible').toBeVisible();

      const afterSubmitDefault = await getSelectText(afterSubmitTrigger);
      expect(afterSubmitDefault, 'afterSubmit: default should contain toast').toContain('toast');

      // Change to "Navigate away"
      await setSelect(page, afterSubmitTrigger, 'Navigate away');
      expect(
        await getSelectText(selectFor(config, 'After Submit')),
        'afterSubmit: should show Navigate away',
      ).toContain('Navigate');

      config = await reselectFormSection(page);
      expect(
        await getSelectText(selectFor(config, 'After Submit')),
        'afterSubmit: should persist Navigate away after reselect',
      ).toContain('Navigate');

      // P3-dependsOn: switching mode back to Display → afterSubmit disappears
      await setSelect(page, selectFor(config, 'Mode'), 'Display');
      await expect(
        labelExact(config, 'After Submit'),
        'afterSubmit: should disappear when mode=Display',
      ).not.toBeVisible();
      await expect(
        labelExact(config, 'Submit command'),
        'commandCode: should disappear when mode=Display',
      ).not.toBeVisible();

      // Switch mode back to Create for remaining property tests
      await setSelect(page, selectFor(config, 'Mode'), 'Create');

      // ────────────────────────────────────────────────────────────────────
      // P4: colCount (select, label='Columns', default = '2')
      // ────────────────────────────────────────────────────────────────────
      const colCountTrigger = selectFor(config, 'Columns');
      await expect(colCountTrigger, 'colCount: field should be visible').toBeVisible();

      const colCountDefault = await getSelectText(colCountTrigger);
      expect(colCountDefault, 'colCount: default should be 2').toContain('2');

      // Change to "3"
      await setSelect(page, colCountTrigger, '3');
      expect(await getSelectText(selectFor(config, 'Columns')), 'colCount: should show 3').toContain('3');

      config = await reselectFormSection(page);
      expect(
        await getSelectText(selectFor(config, 'Columns')),
        'colCount: should persist 3 after reselect',
      ).toContain('3');

      // ────────────────────────────────────────────────────────────────────
      // P5: submitText (text, label='Submit button text')
      // ────────────────────────────────────────────────────────────────────
      const submitTextInput = inputFor(config, 'Submit button text');
      await expect(submitTextInput, 'submitText: field should be visible').toBeVisible();

      await submitTextInput.fill('Save Changes');
      await expect(submitTextInput, 'submitText: should have filled value').toHaveValue('Save Changes');

      config = await reselectFormSection(page);
      await expect(
        inputFor(config, 'Submit button text'),
        'submitText: value should persist after reselect',
      ).toHaveValue('Save Changes');

      // ────────────────────────────────────────────────────────────────────
      // P6: showCancel (switch, label='Show cancel button', default = OFF)
      // ────────────────────────────────────────────────────────────────────
      const showCancelSwitch = switchFor(config, 'Show cancel button');
      await expect(showCancelSwitch, 'showCancel: switch should be visible').toBeVisible();

      const showCancelDefault = await getSwitchState(showCancelSwitch);
      expect(showCancelDefault, 'showCancel: default should be unchecked').toBe('unchecked');

      // Toggle ON
      await showCancelSwitch.click();
      expect(
        await getSwitchState(switchFor(config, 'Show cancel button')),
        'showCancel: should be checked after click',
      ).toBe('checked');

      config = await reselectFormSection(page);
      expect(
        await getSwitchState(switchFor(config, 'Show cancel button')),
        'showCancel: should persist checked after reselect',
      ).toBe('checked');

      // ────────────────────────────────────────────────────────────────────
      // P7: showReset (switch, label='Show reset button', default = OFF)
      // ────────────────────────────────────────────────────────────────────
      const showResetSwitch = switchFor(config, 'Show reset button');
      await expect(showResetSwitch, 'showReset: switch should be visible').toBeVisible();

      const showResetDefault = await getSwitchState(showResetSwitch);
      expect(showResetDefault, 'showReset: default should be unchecked').toBe('unchecked');

      // Toggle ON
      await showResetSwitch.click();
      expect(
        await getSwitchState(switchFor(config, 'Show reset button')),
        'showReset: should be checked after click',
      ).toBe('checked');

      config = await reselectFormSection(page);
      expect(
        await getSwitchState(switchFor(config, 'Show reset button')),
        'showReset: should persist checked after reselect',
      ).toBe('checked');

      // ────────────────────────────────────────────────────────────────────
      // P8: visibleWhen (expression editor, label='Visible when')
      // ────────────────────────────────────────────────────────────────────
      const visibleWhenLabel = labelExact(config, 'Visible when');
      await expect(visibleWhenLabel, 'visibleWhen: expression editor label should be visible').toBeVisible();

      // The expression editor field container should exist
      const visibleWhenField = fieldByLabel(config, 'Visible when');
      await expect(visibleWhenField, 'visibleWhen: field container should be attached').toBeAttached();
    },
  );

  // ─────────────────────────────────────────────────────────────────────────
  // C4.dep — dedicated dependsOn chain test: Display vs Create
  // ─────────────────────────────────────────────────────────────────────────

  test(
    'C4.dep: mode=Create shows Submit command + After Submit; Display hides them',
    async ({ page }) => {
      const pid = await createPage(page, 'fsec-dep');
      await openDesigner(page, pid);
      const config = await addAndSelectFormSection(page);

      // Start in Display mode → deps hidden
      const modeTrigger = selectFor(config, 'Mode');
      expect(await getSelectText(modeTrigger), 'mode: starts as Display').toContain('Display');
      await expect(labelExact(config, 'Submit command')).not.toBeVisible();
      await expect(labelExact(config, 'After Submit')).not.toBeVisible();

      // Switch to Create → deps appear
      await setSelect(page, modeTrigger, 'Create');
      await expect(labelExact(config, 'Submit command'), 'Submit command visible in Create mode').toBeVisible();
      await expect(labelExact(config, 'After Submit'), 'After Submit visible in Create mode').toBeVisible();

      // Switch back to Display → deps disappear
      await setSelect(page, selectFor(config, 'Mode'), 'Display');
      await expect(labelExact(config, 'Submit command'), 'Submit command hidden in Display mode').not.toBeVisible();
      await expect(labelExact(config, 'After Submit'), 'After Submit hidden in Display mode').not.toBeVisible();

      // Verify Edit mode: the schema only shows these fields for 'create'
      // (dependsOn: { field: 'mode', value: 'create' }), so Edit mode hides them too
      await setSelect(page, selectFor(config, 'Mode'), 'Edit');
      await expect(labelExact(config, 'Submit command'), 'Submit command hidden in Edit mode').not.toBeVisible();
      await expect(labelExact(config, 'After Submit'), 'After Submit hidden in Edit mode').not.toBeVisible();
    },
  );
});

// ===========================================================================
// C5 — Toolbar / ActionDef Block Deep Property Tests
// ===========================================================================

test.describe('C5 — Toolbar Block — Deep ActionDef Tests', () => {
  /**
   * Add a toolbar block and select it.
   * Returns the config panel locator.
   */
  async function addAndSelectToolbar(page: Page): Promise<Locator> {
    // Palette items are both dnd-kit useDraggable and native HTML5 draggable.
    // The drag-init race can swallow a click, so retry once before failing.
    await page.getByTestId('canvas-left-tab-components').click();
    const paletteItem = page.getByTestId('block-palette-item-toolbar');
    await paletteItem.waitFor({ state: 'visible', timeout: 5000 });

    const firstBlock = page.locator(BLK).first();
    const clickPalette = async () => {
      await paletteItem.click();
      return firstBlock
        .waitFor({ state: 'visible', timeout: 3000 })
        .then(() => true)
        .catch(() => false);
    };
    const appeared = (await clickPalette()) || (await clickPalette());
    if (!appeared) {
      throw new Error('addAndSelectToolbar: toolbar block never appeared after 2 click attempts');
    }

    // Click content area to select
    await page.locator('[data-testid^="canvas-block-content-"]').first().click();

    // Wait for toolbar config panel
    const config = page.getByTestId('toolbar-schema-config');
    await config.waitFor({ state: 'visible', timeout: 5000 });
    return config;
  }

  /** Deselect and reselect the toolbar block */
  async function reselectToolbar(page: Page): Promise<Locator> {
    await deselect(page);
    await page.locator('[data-testid^="canvas-block-content-"]').first().click();
    const config = page.getByTestId('toolbar-schema-config');
    await config.waitFor({ state: 'visible', timeout: 5000 });
    return config;
  }

  // ─────────────────────────────────────────────────────────────────────────
  // C5.type.command — action.type=Command shows Command field (command-select)
  //
  // NOTE: The toolbar schema defaultValue is 'command', but the isVisible()
  // evaluator reads block.config directly (no defaultValue fallback). A freshly-
  // added block starts with config.action.type = undefined. Radix Select may
  // also skip onChange when the selected option matches the displayed value
  // (via defaultValue resolution). Therefore, to force 'command' into config,
  // we first switch to Navigate (different value), then switch back to Command.
  // This ensures config.action.type is explicitly written as 'command'.
  // ─────────────────────────────────────────────────────────────────────────

  test(
    'C5.type.command: after switching away and back to Command, Command field is visible',
    async ({ page }) => {
      const pid = await createPage(page, 'tb-cmd');
      await openDesigner(page, pid);
      const config = await addAndSelectToolbar(page);

      const actionTypeTrigger = selectFor(config, 'Action Type');
      await expect(actionTypeTrigger, 'action.type: trigger should be visible').toBeVisible();

      // Step 1: Switch to Navigate (writes 'navigate' to config)
      await setSelect(page, actionTypeTrigger, 'Navigate');
      expect(await getSelectText(selectFor(config, 'Action Type')), 'step1: type should be Navigate').toContain('Navigate');

      // URL appears, Command disappears
      await expect(labelExact(config, 'URL'), 'URL label visible after switching to Navigate').toBeVisible();
      await expect(labelExact(config, 'Command'), 'Command label hidden when type=Navigate').not.toBeVisible();

      // Step 2: Switch back to Command (writes 'command' to config — this time onChange fires)
      await setSelect(page, selectFor(config, 'Action Type'), 'Command');
      expect(await getSelectText(selectFor(config, 'Action Type')), 'step2: type should be Command').toContain('Command');

      // 'Command' field (command-select) label should now be visible
      await expect(
        labelExact(config, 'Command'),
        'action.command: Command label should be visible when type=Command is set from Navigate',
      ).toBeVisible();

      // URL should be hidden again
      await expect(
        labelExact(config, 'URL'),
        'action.to: URL label should NOT be visible when type=Command',
      ).not.toBeVisible();

      // 'Builtin Action' should NOT be visible
      await expect(
        labelExact(config, 'Builtin Action'),
        'action.name: Builtin Action should NOT be visible when type=Command',
      ).not.toBeVisible();
    },
  );

  // ─────────────────────────────────────────────────────────────────────────
  // C5.type.navigate — action.type=Navigate shows URL field; hides Command
  // ─────────────────────────────────────────────────────────────────────────

  test(
    'C5.type.navigate: action.type=Navigate shows URL field; persists value',
    async ({ page }) => {
      const pid = await createPage(page, 'tb-nav');
      await openDesigner(page, pid);
      const config = await addAndSelectToolbar(page);

      const actionTypeTrigger = selectFor(config, 'Action Type');

      // Switch to Navigate
      await setSelect(page, actionTypeTrigger, 'Navigate');
      expect(await getSelectText(selectFor(config, 'Action Type')), 'type: should show Navigate').toContain('Navigate');

      // 'URL' (action.to) field appears
      await expect(
        labelExact(config, 'URL'),
        'action.to: URL label should appear when type=Navigate',
      ).toBeVisible();

      // Fill action.to
      const toInput = inputFor(config, 'URL');
      await toInput.fill('/p/my_page');
      await expect(toInput, 'action.to: should have filled value').toHaveValue('/p/my_page');

      // 'Command' select should disappear
      await expect(
        labelExact(config, 'Command'),
        'action.command: Command label should NOT be visible when type=Navigate',
      ).not.toBeVisible();

      // Persist
      const config2 = await reselectToolbar(page);
      expect(
        await getSelectText(selectFor(config2, 'Action Type')),
        'action.type: Navigate should persist',
      ).toContain('Navigate');
      await expect(
        inputFor(config2, 'URL'),
        'action.to: /p/my_page should persist after reselect',
      ).toHaveValue('/p/my_page');
    },
  );

  // ─────────────────────────────────────────────────────────────────────────
  // C5.type.builtin — action.type=Builtin shows Builtin Action select (8 opts)
  // ─────────────────────────────────────────────────────────────────────────

  test(
    'C5.type.builtin: action.type=Builtin shows Builtin Action select with 8 options',
    async ({ page }) => {
      const pid = await createPage(page, 'tb-builtin');
      await openDesigner(page, pid);
      const config = await addAndSelectToolbar(page);

      const actionTypeTrigger = selectFor(config, 'Action Type');

      // Switch to Builtin
      await setSelect(page, actionTypeTrigger, 'Builtin');
      expect(await getSelectText(selectFor(config, 'Action Type')), 'type: should show Builtin').toContain('Builtin');

      // 'Builtin Action' select appears
      await expect(
        labelExact(config, 'Builtin Action'),
        'action.name: Builtin Action label should appear when type=Builtin',
      ).toBeVisible();

      const nameTrigger = selectFor(config, 'Builtin Action');
      await expect(nameTrigger, 'action.name: trigger should be visible').toBeVisible();

      // Open the dropdown and verify 8 options
      await nameTrigger.click();
      const options = page.getByRole('option');
      const optionCount = await options.count();
      expect(optionCount, 'action.name: should have 8 options').toBe(8);

      // Close with Escape to avoid interfering with the rest
      await page.keyboard.press('Escape');

      // Select "Delete" option
      await setSelect(page, selectFor(config, 'Builtin Action'), 'Delete');
      expect(
        await getSelectText(selectFor(config, 'Builtin Action')),
        'action.name: should show Delete',
      ).toContain('Delete');

      // Command / URL should be hidden
      await expect(
        labelExact(config, 'Command'),
        'action.command: Command should NOT be visible when type=Builtin',
      ).not.toBeVisible();
      await expect(
        labelExact(config, 'URL'),
        'action.to: URL should NOT be visible when type=Builtin',
      ).not.toBeVisible();

      // Persist
      const config2 = await reselectToolbar(page);
      expect(
        await getSelectText(selectFor(config2, 'Action Type')),
        'action.type: Builtin should persist',
      ).toContain('Builtin');
      expect(
        await getSelectText(selectFor(config2, 'Builtin Action')),
        'action.name: Delete should persist',
      ).toContain('Delete');
    },
  );

  // ─────────────────────────────────────────────────────────────────────────
  // C5.type.handler — action.type=Flow (Handler) shows Handler text field
  // ─────────────────────────────────────────────────────────────────────────

  test(
    'C5.type.handler: action.type=Flow (Handler) shows Handler text field; persists',
    async ({ page }) => {
      const pid = await createPage(page, 'tb-handler');
      await openDesigner(page, pid);
      const config = await addAndSelectToolbar(page);

      const actionTypeTrigger = selectFor(config, 'Action Type');

      // Switch to Flow (Handler)
      await setSelect(page, actionTypeTrigger, 'Flow (Handler)');
      expect(
        await getSelectText(selectFor(config, 'Action Type')),
        'type: should show Flow (Handler)',
      ).toContain('Flow');

      // 'Handler' field appears
      await expect(
        labelExact(config, 'Handler'),
        'action.handler: Handler label should appear when type=Flow(Handler)',
      ).toBeVisible();

      const handlerInput = inputFor(config, 'Handler');
      await handlerInput.fill('onSubmit');
      await expect(handlerInput, 'action.handler: should have filled value').toHaveValue('onSubmit');

      // Persist
      const config2 = await reselectToolbar(page);
      expect(
        await getSelectText(selectFor(config2, 'Action Type')),
        'action.type: Flow (Handler) should persist',
      ).toContain('Flow');
      await expect(
        inputFor(config2, 'Handler'),
        'action.handler: onSubmit should persist after reselect',
      ).toHaveValue('onSubmit');
    },
  );

  // ─────────────────────────────────────────────────────────────────────────
  // C5.type.steps — action.type=Flow (Steps) shows Flow Steps (JSON) field
  // ─────────────────────────────────────────────────────────────────────────

  test(
    'C5.type.steps: action.type=Flow (Steps) shows Flow Steps (JSON) field',
    async ({ page }) => {
      const pid = await createPage(page, 'tb-steps');
      await openDesigner(page, pid);
      const config = await addAndSelectToolbar(page);

      const actionTypeTrigger = selectFor(config, 'Action Type');

      // Switch to Flow (Steps)
      await setSelect(page, actionTypeTrigger, 'Flow (Steps)');
      expect(
        await getSelectText(selectFor(config, 'Action Type')),
        'type: should show Flow (Steps)',
      ).toContain('Flow');

      // 'Flow Steps (JSON)' label appears
      await expect(
        labelExact(config, 'Flow Steps (JSON)'),
        'action.steps: Flow Steps (JSON) label should appear when type=Flow(Steps)',
      ).toBeVisible();
    },
  );

  // ─────────────────────────────────────────────────────────────────────────
  // C5.type.state — action.type=State Transition shows Command field
  // ─────────────────────────────────────────────────────────────────────────

  test(
    'C5.type.state: action.type=State Transition shows Command field (same as Command type)',
    async ({ page }) => {
      const pid = await createPage(page, 'tb-state');
      await openDesigner(page, pid);
      const config = await addAndSelectToolbar(page);

      const actionTypeTrigger = selectFor(config, 'Action Type');

      // Switch to State Transition
      await setSelect(page, actionTypeTrigger, 'State Transition');
      expect(
        await getSelectText(selectFor(config, 'Action Type')),
        'type: should show State Transition',
      ).toContain('State Transition');

      // 'Command' field appears (schema shows command-select for both 'command' and 'state_transition')
      await expect(
        labelExact(config, 'Command'),
        'action.command: Command should appear when type=State Transition',
      ).toBeVisible();

      // URL should be hidden
      await expect(
        labelExact(config, 'URL'),
        'action.to: URL should NOT be visible when type=State Transition',
      ).not.toBeVisible();
    },
  );

  // ─────────────────────────────────────────────────────────────────────────
  // C5.appearance — primary, danger, icon, confirm, visibleWhen, enableWhen
  // ─────────────────────────────────────────────────────────────────────────

  test(
    'C5.appearance: primary, danger, icon, confirm prompt, visibleWhen, enableWhen — with persistence',
    async ({ page }) => {
      const pid = await createPage(page, 'tb-appear');
      await openDesigner(page, pid);
      let config = await addAndSelectToolbar(page);

      // ── primary (switch, label='Primary', default = OFF) ──────────────────
      const primarySwitch = switchFor(config, 'Primary');
      await expect(primarySwitch, 'primary: switch should be visible').toBeVisible();

      expect(await getSwitchState(primarySwitch), 'primary: default should be unchecked').toBe('unchecked');

      await primarySwitch.click();
      expect(
        await getSwitchState(switchFor(config, 'Primary')),
        'primary: should be checked after click',
      ).toBe('checked');

      config = await reselectToolbar(page);
      expect(
        await getSwitchState(switchFor(config, 'Primary')),
        'primary: should persist checked after reselect',
      ).toBe('checked');

      // ── danger (switch, label='Danger', default = OFF) ────────────────────
      const dangerSwitch = switchFor(config, 'Danger');
      await expect(dangerSwitch, 'danger: switch should be visible').toBeVisible();

      expect(await getSwitchState(dangerSwitch), 'danger: default should be unchecked').toBe('unchecked');

      await dangerSwitch.click();
      expect(
        await getSwitchState(switchFor(config, 'Danger')),
        'danger: should be checked after click',
      ).toBe('checked');

      config = await reselectToolbar(page);
      expect(
        await getSwitchState(switchFor(config, 'Danger')),
        'danger: should persist checked after reselect',
      ).toBe('checked');

      // ── icon (text, label='Icon') ──────────────────────────────────────────
      const iconInput = inputFor(config, 'Icon');
      await expect(iconInput, 'icon: input should be visible').toBeVisible();

      await iconInput.fill('edit');
      await expect(iconInput, 'icon: should have filled value').toHaveValue('edit');

      config = await reselectToolbar(page);
      await expect(
        inputFor(config, 'Icon'),
        'icon: value should persist after reselect',
      ).toHaveValue('edit');

      // ── confirm (text, label='Confirm prompt') ─────────────────────────────
      const confirmInput = inputFor(config, 'Confirm prompt');
      await expect(confirmInput, 'confirm: input should be visible').toBeVisible();

      await confirmInput.fill('Are you sure?');
      await expect(confirmInput, 'confirm: should have filled value').toHaveValue('Are you sure?');

      config = await reselectToolbar(page);
      await expect(
        inputFor(config, 'Confirm prompt'),
        'confirm: value should persist after reselect',
      ).toHaveValue('Are you sure?');

      // ── visibleWhen (expression editor, label='Visible when') ─────────────
      await expect(
        labelExact(config, 'Visible when'),
        'visibleWhen: expression editor label should be visible',
      ).toBeVisible();

      // ── enableWhen (expression editor, label='Enable when') ──────────────
      await expect(
        labelExact(config, 'Enable when'),
        'enableWhen: expression editor label should be visible',
      ).toBeVisible();
    },
  );

  // ─────────────────────────────────────────────────────────────────────────
  // C5.all — end-to-end persistence test: change multiple props → DSL reload
  // ─────────────────────────────────────────────────────────────────────────

  test(
    'C5.all: multiple toolbar properties persist across page reload (DSL save)',
    async ({ page }) => {
      const pid = await createPage(page, 'tb-all');
      await openDesigner(page, pid);
      let config = await addAndSelectToolbar(page);

      // Set action.type = Navigate
      await setSelect(page, selectFor(config, 'Action Type'), 'Navigate');

      // Fill action.to (label='URL')
      const toInput = inputFor(config, 'URL');
      await toInput.fill('/p/reload_test');
      await expect(toInput).toHaveValue('/p/reload_test');

      // Toggle primary ON
      await switchFor(config, 'Primary').click();
      expect(await getSwitchState(switchFor(config, 'Primary'))).toBe('checked');

      // Fill icon
      await inputFor(config, 'Icon').fill('save');

      // Fill confirm
      await inputFor(config, 'Confirm prompt').fill('Save this record?');

      // Deselect to trigger auto-save debounce
      await deselect(page);

      // Wait for auto-save (2s debounce + network)
      await page.waitForTimeout(4000);

      // Reload the page
      await openDesigner(page, pid);

      // Reselect the toolbar block
      config = await reselectToolbar(page);

      // Verify all persisted values
      expect(
        await getSelectText(selectFor(config, 'Action Type')),
        'action.type: Navigate should survive reload',
      ).toContain('Navigate');
      await expect(
        inputFor(config, 'URL'),
        'action.to: /p/reload_test should survive reload',
      ).toHaveValue('/p/reload_test');
      expect(
        await getSwitchState(switchFor(config, 'Primary')),
        'primary: checked state should survive reload',
      ).toBe('checked');
      await expect(
        inputFor(config, 'Icon'),
        'icon: save should survive reload',
      ).toHaveValue('save');
      await expect(
        inputFor(config, 'Confirm prompt'),
        'confirm: should survive reload',
      ).toHaveValue('Save this record?');
    },
  );
});
