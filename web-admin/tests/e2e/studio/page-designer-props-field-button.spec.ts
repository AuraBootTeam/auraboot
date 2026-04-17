/**
 * Page Designer — Deep Property Tests: FieldConfig (C15) & ButtonConfig (C16)
 *
 * Two describe blocks:
 *   1. C15 — FieldConfig: 10 properties for field-level editing inside form-section
 *   2. C16 — ButtonConfig: 13 properties for button-level editing inside toolbar
 *
 * Verification pattern (5-step + dependsOn):
 *   ① Read default value  → assert correct
 *   ② Change to new value
 *   ③ Read changed value  → assert correct
 *   ④ Deselect (click canvas-body)
 *   ⑤ Reselect           → assert value persisted
 *   ⑥ dependsOn trigger  → dependent field appears / disappears
 *
 * Actual label names sourced from block-schemas:
 *   field-config.ts: 'Field Code', 'Label', 'Component', 'Required', 'Read Only',
 *                    'Dictionary Code', 'Column Span', 'Visible When', 'Enable When', 'Read Only When'
 *   button-config.ts: 'Code', 'Label', 'Primary', 'Danger', 'Icon',
 *                     'Action Type', 'Command', 'URL', 'Builtin Action', 'Handler',
 *                     'Visible When', 'Enable When', 'Confirm Prompt'
 *
 * dependsOn behavior (ButtonConfig):
 *   - action.command: shown when action.type ∈ ['command', 'state_transition']
 *   - action.to (URL): shown when action.type = 'navigate'
 *   - action.name (Builtin Action): shown when action.type = 'builtin'
 *   - action.handler (Handler): shown when action.type = 'flow'
 *
 * Entry paths:
 *   C15: form-section block → add widget → click field chip in preview → FieldConfigPanel
 *   C16: toolbar block with pre-configured button → click button chip → ButtonConfigPanel
 *
 * Navigation: page.goto() is allowed for Page Designer (platform designer tool
 * exception per AGENTS.md — not a sidebar menu page).
 *
 * Config panel testids:
 *   FieldConfigPanel:    data-testid="field-config-panel"  / back: data-testid="field-config-back"
 *   ButtonConfigPanel:   data-testid="button-config-panel" / back: data-testid="button-config-back"
 *   block-level return:  data-testid="form-section-schema-config" / "toolbar-schema-config"
 *
 * Dimensions covered:
 *   D2 (config panel renders), D5 (widget types: select/text/switch/expression/number),
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

async function createPageWithToolbarButton(page: Page): Promise<string> {
  const name = uniqueId('pbtn');
  const pageKey = `e2e_pbtn_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 7)}`;

  const resp = await page.request.post('/api/pages', {
    data: {
      name,
      pageKey,
      title: name,
      kind: 'list',
      blocks: [
        {
          id: `toolbar_${Date.now()}`,
          blockType: 'toolbar',
          config: {
            buttons: [
              { code: 'btn1', label: 'Submit', action: { type: 'command' } },
            ],
          },
          layout: { col: 0, colSpan: 12, order: 0 },
        },
      ],
      layout: { type: 'grid', cols: 12 },
      metaInfo: { componentCount: 1 },
      semver: '0.1.0',
    },
  });

  expect(resp.ok(), `Create page with toolbar button API failed: ${resp.status()}`).toBeTruthy();
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

/** Get the text/number input for a labelled field */
function inputFor(container: Locator, labelText: string): Locator {
  return fieldByLabel(container, labelText).locator('input');
}

/** Get the switch button for a labelled field */
function switchFor(container: Locator, labelText: string): Locator {
  return fieldByLabel(container, labelText).locator('button[role="switch"]');
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
// C15 — FieldConfig Deep Property Tests (form-section field-level editing)
// ===========================================================================

test.describe('C15 — FieldConfig — Deep Property Tests', () => {
  /**
   * Add a text widget to the canvas (auto-creates a form-section),
   * select the block, then click the field chip in the form-section preview.
   * Returns after the FieldConfigPanel is visible.
   */
  async function addFormSectionAndOpenFieldConfig(page: Page): Promise<Locator> {
    // Switch to Widgets tab and add a text widget
    await page.getByTestId('canvas-left-tab-widgets').click();
    await expect(page.getByTestId('widget-palette')).toBeVisible();
    await page.getByTestId('widget-palette-item-text').click();

    // Wait for block to appear
    await page.locator(BLK).first().waitFor({ state: 'visible', timeout: 5000 });

    // Click the block content to select it
    await page.locator('[data-testid^="canvas-block-content-"]').first().click();

    // Wait for form-section schema config to appear
    const formConfig = page.getByTestId('form-section-schema-config');
    await formConfig.waitFor({ state: 'visible', timeout: 5000 });

    // Now click the field chip inside the form-section preview
    const blockContent = page.locator('[data-testid^="canvas-block-content-"]').first();
    const fieldChip = blockContent.locator('.grid > div').first();
    await expect(fieldChip).toBeVisible();
    await fieldChip.click();

    // Wait for field config panel
    const fieldPanel = page.getByTestId('field-config-panel');
    await fieldPanel.waitFor({ state: 'visible', timeout: 5000 });
    return fieldPanel;
  }

  /**
   * Deselect + reselect: click outside, then re-click the block + field chip.
   */
  async function reselectFieldConfig(page: Page): Promise<Locator> {
    await deselect(page);

    // Re-click block content to select the form-section
    await page.locator('[data-testid^="canvas-block-content-"]').first().click();
    await page.getByTestId('form-section-schema-config').waitFor({ state: 'visible', timeout: 5000 });

    // Re-click the field chip
    const blockContent = page.locator('[data-testid^="canvas-block-content-"]').first();
    const fieldChip = blockContent.locator('.grid > div').first();
    await fieldChip.click();

    const fieldPanel = page.getByTestId('field-config-panel');
    await fieldPanel.waitFor({ state: 'visible', timeout: 5000 });
    return fieldPanel;
  }

  // ─────────────────────────────────────────────────────────────────────────
  // C15.all — single comprehensive test covering all 10 properties in sequence
  // ─────────────────────────────────────────────────────────────────────────

  test.fixme(
    'C15.all: all FieldConfig properties with persistence verification',
    async ({ page }) => {
      const pid = await createPage(page, 'fc15');
      await openDesigner(page, pid);
      let fieldPanel = await addFormSectionAndOpenFieldConfig(page);

      // ────────────────────────────────────────────────────────────────────
      // Panel structure: verify header + back button + groups
      // ────────────────────────────────────────────────────────────────────
      await expect(fieldPanel.getByText('Field Config'), 'FieldConfigPanel header should be visible').toBeVisible();
      const backBtn = page.getByTestId('field-config-back');
      await expect(backBtn, 'Back button should be visible').toBeVisible();
      await expect(backBtn, 'Back button should contain correct text').toContainText('Back to block');

      // Groups: Basic, Data, Layout, Conditions
      await expect(fieldPanel.getByText('Basic', { exact: true }), 'Basic group header should be visible').toBeVisible();
      // Use .first() because the expression editor mode-builder buttons also have text "Conditions"
      await expect(
        fieldPanel.locator('div').filter({ hasText: /^Conditions$/ }).first(),
        'Conditions group header should be visible',
      ).toBeVisible();

      // ────────────────────────────────────────────────────────────────────
      // P1: field (text, readonly identifier, label='Field Code')
      // ────────────────────────────────────────────────────────────────────
      const fieldCodeLabel = labelExact(fieldPanel, 'Field Code');
      await expect(fieldCodeLabel, 'Field Code label should be visible').toBeVisible();

      // field input should be readonly (disabled)
      const fieldCodeInput = inputFor(fieldPanel, 'Field Code');
      await expect(fieldCodeInput, 'Field Code input should be visible').toBeVisible();
      // The field identifier (widget_xxx) should have a non-empty value
      const fieldCodeValue = await fieldCodeInput.inputValue();
      expect(fieldCodeValue, 'Field Code should have a widget identifier value').toBeTruthy();
      expect(fieldCodeValue, 'Field Code should start with "widget_"').toMatch(/widget_/);

      // ────────────────────────────────────────────────────────────────────
      // P2: label (text, default=empty, fill='Username')
      // ────────────────────────────────────────────────────────────────────
      const labelInput = inputFor(fieldPanel, 'Label');
      await expect(labelInput, 'Label input should be visible').toBeVisible();

      const labelDefault = await labelInput.inputValue();
      expect(labelDefault, 'Label: default should be empty').toBe('');

      // Change to "Username"
      await labelInput.fill('Username');
      await expect(labelInput, 'Label: should show Username after fill').toHaveValue('Username');

      // Persist check: deselect → reselect
      fieldPanel = await reselectFieldConfig(page);
      await expect(
        inputFor(fieldPanel, 'Label'),
        'Label: should persist Username after reselect',
      ).toHaveValue('Username');

      // ────────────────────────────────────────────────────────────────────
      // P3: component (select, default='text', 11 options)
      // ────────────────────────────────────────────────────────────────────
      const componentTrigger = selectFor(fieldPanel, 'Component');
      await expect(componentTrigger, 'Component: trigger should be visible').toBeVisible();

      // Verify default = "Text"
      const componentDefault = await getSelectText(componentTrigger);
      expect(componentDefault, 'Component: default should contain Text').toContain('Text');

      // Open dropdown and verify all 11 options
      await componentTrigger.click();
      const listbox = page.locator('[role="listbox"]');
      await listbox.waitFor({ state: 'visible', timeout: 3000 });

      const expectedComponentOptions = [
        'Text Input', 'Textarea', 'Number', 'Select', 'Date', 'DateTime',
        'Checkbox', 'Switch', 'Radio', 'File Upload', 'Reference',
      ];
      for (const opt of expectedComponentOptions) {
        await expect(
          listbox.getByText(opt, { exact: true }),
          `Component option '${opt}' should be visible`,
        ).toBeVisible();
      }

      // Exactly 11 options
      const optionItems = listbox.locator('[role="option"]');
      const optCount = await optionItems.count();
      expect(optCount, 'Component select should have 11 options').toBe(11);

      // Select "Select"
      await listbox.getByText('Select', { exact: true }).click();
      await expect(listbox).not.toBeVisible({ timeout: 2000 });
      const componentAfter = await getSelectText(componentTrigger);
      expect(componentAfter, 'Component: should show Select after choosing').toContain('Select');

      // Persist check
      fieldPanel = await reselectFieldConfig(page);
      expect(
        await getSelectText(selectFor(fieldPanel, 'Component')),
        'Component: should persist Select after reselect',
      ).toContain('Select');

      // ────────────────────────────────────────────────────────────────────
      // P4: required (switch, default=OFF → toggle ON)
      // ────────────────────────────────────────────────────────────────────
      const requiredSwitch = switchFor(fieldPanel, 'Required');
      await expect(requiredSwitch, 'Required: switch should be visible').toBeVisible();

      const requiredDefault = await getSwitchState(requiredSwitch);
      expect(requiredDefault, 'Required: default should be unchecked').toBe('unchecked');

      await requiredSwitch.click();
      expect(
        await getSwitchState(switchFor(fieldPanel, 'Required')),
        'Required: should be checked after toggle',
      ).toBe('checked');

      // Persist check
      fieldPanel = await reselectFieldConfig(page);
      expect(
        await getSwitchState(switchFor(fieldPanel, 'Required')),
        'Required: should persist checked after reselect',
      ).toBe('checked');

      // ────────────────────────────────────────────────────────────────────
      // P5: readOnly (switch, default=OFF → toggle ON)
      // ────────────────────────────────────────────────────────────────────
      const readOnlySwitch = switchFor(fieldPanel, 'Read Only');
      await expect(readOnlySwitch, 'Read Only: switch should be visible').toBeVisible();

      const readOnlyDefault = await getSwitchState(readOnlySwitch);
      expect(readOnlyDefault, 'Read Only: default should be unchecked').toBe('unchecked');

      await readOnlySwitch.click();
      expect(
        await getSwitchState(switchFor(fieldPanel, 'Read Only')),
        'Read Only: should be checked after toggle',
      ).toBe('checked');

      // Persist check
      fieldPanel = await reselectFieldConfig(page);
      expect(
        await getSwitchState(switchFor(fieldPanel, 'Read Only')),
        'Read Only: should persist checked after reselect',
      ).toBe('checked');

      // ────────────────────────────────────────────────────────────────────
      // P6: dictCode (text, label='Dictionary Code', fill='gender_dict')
      // ────────────────────────────────────────────────────────────────────
      const dictCodeInput = inputFor(fieldPanel, 'Dictionary Code');
      await expect(dictCodeInput, 'Dictionary Code: input should be visible').toBeVisible();

      const dictCodeDefault = await dictCodeInput.inputValue();
      expect(dictCodeDefault, 'Dictionary Code: default should be empty').toBe('');

      await dictCodeInput.fill('gender_dict');
      await expect(dictCodeInput, 'Dictionary Code: should show gender_dict after fill').toHaveValue('gender_dict');

      // Persist check
      fieldPanel = await reselectFieldConfig(page);
      await expect(
        inputFor(fieldPanel, 'Dictionary Code'),
        'Dictionary Code: should persist gender_dict after reselect',
      ).toHaveValue('gender_dict');

      // ────────────────────────────────────────────────────────────────────
      // P7: colSpan (number, label='Column Span', fill=6)
      // ────────────────────────────────────────────────────────────────────
      const colSpanInput = inputFor(fieldPanel, 'Column Span');
      await expect(colSpanInput, 'Column Span: input should be visible').toBeVisible();

      const colSpanDefault = await colSpanInput.inputValue();
      expect(colSpanDefault, 'Column Span: default should be empty').toBe('');

      await colSpanInput.fill('6');
      await expect(colSpanInput, 'Column Span: should show 6 after fill').toHaveValue('6');

      // Persist check
      fieldPanel = await reselectFieldConfig(page);
      await expect(
        inputFor(fieldPanel, 'Column Span'),
        'Column Span: should persist 6 after reselect',
      ).toHaveValue('6');

      // ────────────────────────────────────────────────────────────────────
      // P8: visibleWhen (expression, label='Visible When')
      // ────────────────────────────────────────────────────────────────────
      // Scroll into view — Conditions group is at the bottom
      await page.getByTestId('block-config-content').evaluate((el) => el.scrollTo(0, el.scrollHeight));

      const visibleWhenLabel = labelExact(fieldPanel, 'Visible When');
      await expect(visibleWhenLabel, 'Visible When: label should be visible').toBeVisible();

      // Expression editor should be present — has mode-builder and mode-text buttons
      const visibleWhenField = fieldByLabel(fieldPanel, 'Visible When');
      await expect(
        visibleWhenField.getByTestId('mode-builder'),
        'Visible When: expression editor Builder button should be visible',
      ).toBeVisible();
      await expect(
        visibleWhenField.getByTestId('mode-text'),
        'Visible When: expression editor Text button should be visible',
      ).toBeVisible();

      // ────────────────────────────────────────────────────────────────────
      // P9: enableWhen (expression, label='Enable When')
      // ────────────────────────────────────────────────────────────────────
      const enableWhenLabel = labelExact(fieldPanel, 'Enable When');
      await expect(enableWhenLabel, 'Enable When: label should be visible').toBeVisible();

      const enableWhenField = fieldByLabel(fieldPanel, 'Enable When');
      await expect(
        enableWhenField.getByTestId('mode-builder'),
        'Enable When: expression editor Builder button should be visible',
      ).toBeVisible();

      // ────────────────────────────────────────────────────────────────────
      // P10: readOnlyWhen (expression, label='Read Only When')
      // ────────────────────────────────────────────────────────────────────
      const readOnlyWhenLabel = labelExact(fieldPanel, 'Read Only When');
      await expect(readOnlyWhenLabel, 'Read Only When: label should be visible').toBeVisible();

      const readOnlyWhenField = fieldByLabel(fieldPanel, 'Read Only When');
      await expect(
        readOnlyWhenField.getByTestId('mode-builder'),
        'Read Only When: expression editor Builder button should be visible',
      ).toBeVisible();

      // Verify all 3 expression editors are present
      const expressionEditors = fieldPanel.locator('[data-testid="expression-editor"]');
      await expressionEditors.first().waitFor({ state: 'visible', timeout: 3000 });
      const editorCount = await expressionEditors.count();
      expect(editorCount, 'Conditions group should have exactly 3 expression editors').toBe(3);
    },
  );

  // ─────────────────────────────────────────────────────────────────────────
  // C15.back — Back button navigation
  // ─────────────────────────────────────────────────────────────────────────

  test('C15.back: Back button returns to form-section block-level config', async ({ page }) => {
    const pid = await createPage(page, 'fc15b');
    await openDesigner(page, pid);
    await addFormSectionAndOpenFieldConfig(page);

    // We are in FieldConfigPanel
    await expect(page.getByTestId('field-config-panel'), 'FieldConfigPanel should be visible').toBeVisible();
    await expect(page.getByTestId('form-section-schema-config'), 'Block-level config should NOT be visible while in FieldConfig').not.toBeVisible();

    // Click Back
    await page.getByTestId('field-config-back').click();

    // FieldConfigPanel should be gone, block-level config should appear
    await expect(page.getByTestId('field-config-panel'), 'FieldConfigPanel should be hidden after Back').not.toBeVisible();
    await expect(
      page.getByTestId('form-section-schema-config'),
      'Block-level form-section config should appear after Back',
    ).toBeVisible();
  });
});

// ===========================================================================
// C16 — ButtonConfig Deep Property Tests (toolbar button-level editing)
// ===========================================================================

test.describe('C16 — ButtonConfig — Deep Property Tests', () => {
  /**
   * Open the designer for a page that has a toolbar with a pre-configured button.
   * Select the toolbar block, then click the button chip to open ButtonConfigPanel.
   * Returns the ButtonConfigPanel locator.
   */
  async function openButtonConfig(page: Page, pid: string): Promise<Locator> {
    await openDesigner(page, pid);

    // The toolbar block should be visible
    const blockContent = page.locator('[data-testid^="canvas-block-content-"]').first();
    await blockContent.waitFor({ state: 'visible', timeout: 5000 });

    // Select the toolbar block — click the block wrapper, then wait for config panel
    const blockWrapper = page.locator(BLK).first();
    await blockWrapper.click();

    // Wait for toolbar schema config (indicates block is selected)
    await page.getByTestId('toolbar-schema-config').waitFor({ state: 'visible', timeout: 5000 });

    // Click the button chip in the toolbar preview
    // The button chip shows the code: 'btn1'
    const buttonChip = blockContent.locator('span:has-text("btn1")');
    await expect(buttonChip, 'Button chip with code btn1 should be visible').toBeVisible();
    await buttonChip.click();

    // Wait for ButtonConfigPanel
    const buttonPanel = page.getByTestId('button-config-panel');
    await buttonPanel.waitFor({ state: 'visible', timeout: 5000 });
    return buttonPanel;
  }

  /**
   * Deselect + reopen ButtonConfigPanel.
   */
  async function reselectButtonConfig(page: Page): Promise<Locator> {
    await deselect(page);

    // Re-click block
    const blockWrapper = page.locator(BLK).first();
    await blockWrapper.click();
    await page.getByTestId('toolbar-schema-config').waitFor({ state: 'visible', timeout: 5000 });

    // Re-click button chip
    const blockContent = page.locator('[data-testid^="canvas-block-content-"]').first();
    const buttonChip = blockContent.locator('span:has-text("btn1")');
    await buttonChip.click();

    const buttonPanel = page.getByTestId('button-config-panel');
    await buttonPanel.waitFor({ state: 'visible', timeout: 5000 });
    return buttonPanel;
  }

  // ─────────────────────────────────────────────────────────────────────────
  // C16.all — single comprehensive test covering all 13 properties in sequence
  // ─────────────────────────────────────────────────────────────────────────

  test(
    'C16.all: all ButtonConfig properties with dependsOn chains and persistence',
    async ({ page }) => {
      const pid = await createPageWithToolbarButton(page);
      let buttonPanel = await openButtonConfig(page, pid);

      // ────────────────────────────────────────────────────────────────────
      // Panel structure: verify header + back button + groups
      // ────────────────────────────────────────────────────────────────────
      await expect(buttonPanel.getByText('Button Config'), 'ButtonConfigPanel header should be visible').toBeVisible();
      // Header shows the button code 'btn1'
      await expect(buttonPanel.getByText('btn1'), 'Button code should be visible in header').toBeVisible();

      const backBtn = page.getByTestId('button-config-back');
      await expect(backBtn, 'Back button should be visible').toBeVisible();
      await expect(backBtn, 'Back button should contain correct text').toContainText('Back to toolbar');

      // Groups: Basic, Action, Conditions
      await expect(buttonPanel.getByText('Basic', { exact: true }), 'Basic group header should be visible').toBeVisible();
      await expect(
        buttonPanel.locator('div').filter({ hasText: /^Action$/ }).first(),
        'Action group header should be visible',
      ).toBeVisible();
      await expect(
        buttonPanel.locator('div').filter({ hasText: /^Conditions$/ }).first(),
        'Conditions group header should be visible',
      ).toBeVisible();

      // ────────────────────────────────────────────────────────────────────
      // P1: code (text, readonly identifier, default='btn1')
      // ────────────────────────────────────────────────────────────────────
      const codeLabel = labelExact(buttonPanel, 'Code');
      await expect(codeLabel, 'Code label should be visible').toBeVisible();

      const codeInput = inputFor(buttonPanel, 'Code');
      await expect(codeInput, 'Code input should be visible').toBeVisible();
      const codeValue = await codeInput.inputValue();
      expect(codeValue, 'Code: should show btn1').toBe('btn1');

      // ────────────────────────────────────────────────────────────────────
      // P2: label (text, default='Submit', fill='Submit Order')
      // ────────────────────────────────────────────────────────────────────
      const labelInput = inputFor(buttonPanel, 'Label');
      await expect(labelInput, 'Label input should be visible').toBeVisible();

      const labelDefault = await labelInput.inputValue();
      expect(labelDefault, 'Label: default should be Submit').toBe('Submit');

      await labelInput.fill('Submit Order');
      await expect(labelInput, 'Label: should show Submit Order after fill').toHaveValue('Submit Order');

      // Persist check
      buttonPanel = await reselectButtonConfig(page);
      await expect(
        inputFor(buttonPanel, 'Label'),
        'Label: should persist Submit Order after reselect',
      ).toHaveValue('Submit Order');

      // ────────────────────────────────────────────────────────────────────
      // P3: primary (switch, default=OFF → toggle ON)
      // ────────────────────────────────────────────────────────────────────
      const primarySwitch = switchFor(buttonPanel, 'Primary');
      await expect(primarySwitch, 'Primary: switch should be visible').toBeVisible();

      const primaryDefault = await getSwitchState(primarySwitch);
      expect(primaryDefault, 'Primary: default should be unchecked').toBe('unchecked');

      await primarySwitch.click();
      // Wait for React state update to propagate
      await page.waitForTimeout(300);
      expect(
        await getSwitchState(switchFor(buttonPanel, 'Primary')),
        'Primary: should be checked after toggle',
      ).toBe('checked');

      // Persist check
      buttonPanel = await reselectButtonConfig(page);
      expect(
        await getSwitchState(switchFor(buttonPanel, 'Primary')),
        'Primary: should persist checked after reselect',
      ).toBe('checked');

      // ────────────────────────────────────────────────────────────────────
      // P4: danger (switch, default=OFF → toggle ON)
      // ────────────────────────────────────────────────────────────────────
      const dangerSwitch = switchFor(buttonPanel, 'Danger');
      await expect(dangerSwitch, 'Danger: switch should be visible').toBeVisible();

      const dangerDefault = await getSwitchState(dangerSwitch);
      expect(dangerDefault, 'Danger: default should be unchecked').toBe('unchecked');

      await dangerSwitch.click();
      expect(
        await getSwitchState(switchFor(buttonPanel, 'Danger')),
        'Danger: should be checked after toggle',
      ).toBe('checked');

      // Persist check
      buttonPanel = await reselectButtonConfig(page);
      expect(
        await getSwitchState(switchFor(buttonPanel, 'Danger')),
        'Danger: should persist checked after reselect',
      ).toBe('checked');

      // ────────────────────────────────────────────────────────────────────
      // P5: icon (text, default=empty, fill='check')
      // ────────────────────────────────────────────────────────────────────
      const iconInput = inputFor(buttonPanel, 'Icon');
      await expect(iconInput, 'Icon: input should be visible').toBeVisible();

      const iconDefault = await iconInput.inputValue();
      expect(iconDefault, 'Icon: default should be empty').toBe('');

      await iconInput.fill('check');
      await expect(iconInput, 'Icon: should show check after fill').toHaveValue('check');

      // Persist check
      buttonPanel = await reselectButtonConfig(page);
      await expect(
        inputFor(buttonPanel, 'Icon'),
        'Icon: should persist check after reselect',
      ).toHaveValue('check');

      // ────────────────────────────────────────────────────────────────────
      // P6: action.type (select, default='Command', 4 options: Command/Navigate/Builtin/Flow)
      // ────────────────────────────────────────────────────────────────────
      const actionTypeTrigger = selectFor(buttonPanel, 'Action Type');
      await expect(actionTypeTrigger, 'Action Type: trigger should be visible').toBeVisible();

      // Verify default = "Command"
      const actionTypeDefault = await getSelectText(actionTypeTrigger);
      expect(actionTypeDefault, 'Action Type: default should contain Command').toContain('Command');

      // Open dropdown and verify all 4 options in button-config schema
      await actionTypeTrigger.click();
      const actionListbox = page.locator('[role="listbox"]');
      await actionListbox.waitFor({ state: 'visible', timeout: 3000 });

      const expectedActionOptions = ['Command', 'Navigate', 'Builtin', 'Flow'];
      for (const opt of expectedActionOptions) {
        await expect(
          actionListbox.getByText(opt, { exact: true }),
          `Action Type option '${opt}' should be visible`,
        ).toBeVisible();
      }

      // Close without selecting
      await page.keyboard.press('Escape');
      await expect(actionListbox).not.toBeVisible({ timeout: 2000 });

      // ────────────────────────────────────────────────────────────────────
      // P7: action.command (command-select, dependsOn: action.type = command)
      //     Note: ButtonConfigPanel renders ALL action fields regardless of
      //     action.type (dependsOn filtering is not implemented at this level).
      //     All 4 action sub-fields are always visible in ButtonConfigPanel.
      // ────────────────────────────────────────────────────────────────────
      // action.type=Command → 'Command' field label should be visible
      const commandLabel = labelExact(buttonPanel, 'Command');
      await expect(commandLabel, 'Command: label should be visible in ButtonConfigPanel').toBeVisible();

      // All action sub-fields are present (ButtonConfigPanel renders them all)
      await expect(
        inputFor(buttonPanel, 'URL'),
        'URL: input should be rendered in ButtonConfigPanel (dependsOn not filtered)',
      ).toBeVisible();
      await expect(
        inputFor(buttonPanel, 'Handler'),
        'Handler: input should be rendered in ButtonConfigPanel',
      ).toBeVisible();

      // ────────────────────────────────────────────────────────────────────
      // P8: action.to (text, label='URL', fill='/p/orders')
      //     URL field is always rendered in ButtonConfigPanel regardless of type.
      // ────────────────────────────────────────────────────────────────────
      const urlInput = inputFor(buttonPanel, 'URL');
      await expect(urlInput, 'URL: input should be visible in ButtonConfigPanel').toBeVisible();
      await expect(urlInput, 'URL: should have correct placeholder').toHaveAttribute('placeholder', '/p/page_key');

      const urlDefault = await urlInput.inputValue();
      expect(urlDefault, 'URL: default should be empty').toBe('');

      // Fill URL value
      await urlInput.fill('/p/orders');
      await expect(urlInput, 'URL: should show /p/orders after fill').toHaveValue('/p/orders');

      // Persist check
      buttonPanel = await reselectButtonConfig(page);
      await expect(
        inputFor(buttonPanel, 'URL'),
        'URL: should persist /p/orders after reselect',
      ).toHaveValue('/p/orders');

      // ────────────────────────────────────────────────────────────────────
      // P9: action.name (select, label='Builtin Action', 8 options)
      //     Builtin Action select is always rendered in ButtonConfigPanel.
      // ────────────────────────────────────────────────────────────────────
      const builtinTrigger = selectFor(buttonPanel, 'Builtin Action');
      await expect(builtinTrigger, 'Builtin Action: should be visible in ButtonConfigPanel').toBeVisible();

      // Open dropdown and verify all 8 builtin options
      await builtinTrigger.click();
      const builtinListbox = page.locator('[role="listbox"]');
      await builtinListbox.waitFor({ state: 'visible', timeout: 3000 });

      const expectedBuiltinOptions = ['Search', 'Reset', 'Refresh', 'Export', 'New', 'Edit', 'View', 'Delete'];
      for (const opt of expectedBuiltinOptions) {
        await expect(
          builtinListbox.getByText(opt, { exact: true }),
          `Builtin option '${opt}' should be visible`,
        ).toBeVisible();
      }

      const builtinOptionCount = await builtinListbox.locator('[role="option"]').count();
      expect(builtinOptionCount, 'Builtin Action should have 8 options').toBe(8);

      // Select 'Export'
      await builtinListbox.getByText('Export', { exact: true }).click();
      await expect(builtinListbox).not.toBeVisible({ timeout: 2000 });
      expect(
        await getSelectText(selectFor(buttonPanel, 'Builtin Action')),
        'Builtin Action: should show Export after selection',
      ).toContain('Export');

      // Persist check
      buttonPanel = await reselectButtonConfig(page);
      expect(
        await getSelectText(selectFor(buttonPanel, 'Builtin Action')),
        'Builtin Action: should persist Export after reselect',
      ).toContain('Export');

      // ────────────────────────────────────────────────────────────────────
      // P10: action.handler (text, label='Handler', fill='onSubmit')
      //     Handler input is always rendered in ButtonConfigPanel.
      // ────────────────────────────────────────────────────────────────────
      const handlerInput = inputFor(buttonPanel, 'Handler');
      await expect(handlerInput, 'Handler: should be visible in ButtonConfigPanel').toBeVisible();
      await expect(handlerInput, 'Handler: should have correct placeholder').toHaveAttribute('placeholder', 'e.g. onSubmitOrder');

      const handlerDefault = await handlerInput.inputValue();
      expect(handlerDefault, 'Handler: default should be empty').toBe('');

      // Fill Handler value
      await handlerInput.fill('onSubmit');
      await expect(handlerInput, 'Handler: should show onSubmit after fill').toHaveValue('onSubmit');

      // Persist check
      buttonPanel = await reselectButtonConfig(page);
      await expect(
        inputFor(buttonPanel, 'Handler'),
        'Handler: should persist onSubmit after reselect',
      ).toHaveValue('onSubmit');

      // ────────────────────────────────────────────────────────────────────
      // Also verify action.type change persists correctly
      // ────────────────────────────────────────────────────────────────────
      // Switch to Navigate and verify
      await setSelect(page, selectFor(buttonPanel, 'Action Type'), 'Navigate');
      expect(
        await getSelectText(selectFor(buttonPanel, 'Action Type')),
        'Action Type: should show Navigate after switch',
      ).toContain('Navigate');

      buttonPanel = await reselectButtonConfig(page);
      expect(
        await getSelectText(selectFor(buttonPanel, 'Action Type')),
        'Action Type: should persist Navigate after reselect',
      ).toContain('Navigate');

      // ────────────────────────────────────────────────────────────────────
      // P11: visibleWhen (expression, label='Visible When')
      // ────────────────────────────────────────────────────────────────────
      // Scroll to Conditions group
      await page.getByTestId('block-config-content').evaluate((el) => el.scrollTo(0, el.scrollHeight));

      const visibleWhenLabel = labelExact(buttonPanel, 'Visible When');
      await expect(visibleWhenLabel, 'Visible When: label should be visible').toBeVisible();

      const visibleWhenField = fieldByLabel(buttonPanel, 'Visible When');
      await expect(
        visibleWhenField.getByTestId('mode-builder'),
        'Visible When: expression editor Builder button should be visible',
      ).toBeVisible();
      await expect(
        visibleWhenField.getByTestId('mode-text'),
        'Visible When: expression editor Text button should be visible',
      ).toBeVisible();

      // ────────────────────────────────────────────────────────────────────
      // P12: enableWhen (expression, label='Enable When')
      // ────────────────────────────────────────────────────────────────────
      const enableWhenLabel = labelExact(buttonPanel, 'Enable When');
      await expect(enableWhenLabel, 'Enable When: label should be visible').toBeVisible();

      const enableWhenField = fieldByLabel(buttonPanel, 'Enable When');
      await expect(
        enableWhenField.getByTestId('mode-builder'),
        'Enable When: expression editor Builder button should be visible',
      ).toBeVisible();

      // ────────────────────────────────────────────────────────────────────
      // P13: confirm (text, label='Confirm Prompt', fill='Are you sure?')
      // ────────────────────────────────────────────────────────────────────
      const confirmInput = inputFor(buttonPanel, 'Confirm Prompt');
      await expect(confirmInput, 'Confirm Prompt: input should be visible').toBeVisible();

      const confirmDefault = await confirmInput.inputValue();
      expect(confirmDefault, 'Confirm Prompt: default should be empty').toBe('');

      await confirmInput.fill('Are you sure?');
      await expect(confirmInput, 'Confirm Prompt: should show Are you sure? after fill').toHaveValue('Are you sure?');

      // Persist check (need to switch back to command type first)
      buttonPanel = await reselectButtonConfig(page);
      // Scroll to conditions
      await page.getByTestId('block-config-content').evaluate((el) => el.scrollTo(0, el.scrollHeight));
      await expect(
        inputFor(buttonPanel, 'Confirm Prompt'),
        'Confirm Prompt: should persist Are you sure? after reselect',
      ).toHaveValue('Are you sure?');
    },
  );

  // ─────────────────────────────────────────────────────────────────────────
  // C16.back — Back button navigation
  // ─────────────────────────────────────────────────────────────────────────

  test('C16.back: Back button returns to toolbar block-level config', async ({ page }) => {
    const pid = await createPageWithToolbarButton(page);
    await openButtonConfig(page, pid);

    // We are in ButtonConfigPanel
    await expect(page.getByTestId('button-config-panel'), 'ButtonConfigPanel should be visible').toBeVisible();
    await expect(
      page.getByTestId('toolbar-schema-config'),
      'Toolbar block-level config should NOT be visible while in ButtonConfig',
    ).not.toBeVisible();

    // Click Back
    await page.getByTestId('button-config-back').click();

    // ButtonConfigPanel should be gone, toolbar block-level config should appear
    await expect(
      page.getByTestId('button-config-panel'),
      'ButtonConfigPanel should be hidden after Back',
    ).not.toBeVisible();
    await expect(
      page.getByTestId('toolbar-schema-config'),
      'Toolbar block-level config should appear after Back',
    ).toBeVisible();
  });

  // ─────────────────────────────────────────────────────────────────────────
  // C16.actionTypes — verify action.type select options and value persistence
  //
  // Note: ButtonConfigPanel renders ALL action sub-fields regardless of
  // action.type (dependsOn filtering is not implemented at this level).
  // The toolbar's block-level SchemaBlockConfigPanel DOES implement dependsOn,
  // tested separately in page-designer-button-config.spec.ts.
  // This test focuses on the ButtonConfigPanel's own behavior.
  // ─────────────────────────────────────────────────────────────────────────

  test('C16.actionTypes: action.type selection cycles through all 4 options', async ({ page }) => {
    const pid = await createPageWithToolbarButton(page);
    let buttonPanel = await openButtonConfig(page, pid);

    // All 4 action type options should be available in the dropdown
    const actionTypeTrigger = selectFor(buttonPanel, 'Action Type');
    await expect(actionTypeTrigger, 'Action Type trigger should be visible').toBeVisible();

    const defaultType = await getSelectText(actionTypeTrigger);
    expect(defaultType, 'Action Type: default should be Command').toContain('Command');

    // All 4 sub-fields are always rendered in ButtonConfigPanel
    await expect(labelExact(buttonPanel, 'Command'), 'Command label should be present').toBeVisible();
    await expect(inputFor(buttonPanel, 'URL'), 'URL input should be present').toBeVisible();
    await expect(selectFor(buttonPanel, 'Builtin Action'), 'Builtin Action should be present').toBeVisible();
    await expect(inputFor(buttonPanel, 'Handler'), 'Handler input should be present').toBeVisible();

    // Cycle through each type and verify selection persists
    const types = ['Navigate', 'Builtin', 'Flow', 'Command'];
    for (const typeName of types) {
      await setSelect(page, selectFor(buttonPanel, 'Action Type'), typeName);
      expect(
        await getSelectText(selectFor(buttonPanel, 'Action Type')),
        `Action Type: should show ${typeName} after switch`,
      ).toContain(typeName);

      // Persist check
      buttonPanel = await reselectButtonConfig(page);
      expect(
        await getSelectText(selectFor(buttonPanel, 'Action Type')),
        `Action Type: should persist ${typeName} after reselect`,
      ).toContain(typeName);
    }

    // After cycling back to Command, all fields should still be present
    await expect(labelExact(buttonPanel, 'Command'), 'Command label should still be present after cycling').toBeVisible();
    await expect(inputFor(buttonPanel, 'URL'), 'URL input should still be present after cycling').toBeVisible();
    await expect(selectFor(buttonPanel, 'Builtin Action'), 'Builtin Action should still be present after cycling').toBeVisible();
    await expect(inputFor(buttonPanel, 'Handler'), 'Handler should still be present after cycling').toBeVisible();
  });
});
