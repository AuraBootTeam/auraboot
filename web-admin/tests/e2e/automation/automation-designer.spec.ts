/**
 * E2E Automation Designer Tests
 *
 * Tests AD-01 ~ AD-10: Automation designer (flow editor) UI
 * - AD-01: Designer page loads with three-column layout (palette/canvas/properties) @smoke
 * - AD-02: Palette shows node categories (trigger/action/control) @critical
 * - AD-03: Palette displays node items with proper icons (not raw text like "Plus")
 * - AD-04: Canvas area is visible and interactive
 * - AD-05: Click/select a node in palette (verify it's draggable)
 * - AD-06: Name and description inputs are editable in header
 * - AD-07: Save button triggers API call @critical
 * - AD-08: Debug button is visible when editing existing automation
 * - AD-09: i18n labels render correctly (not raw keys like $i18n:...)
 * - AD-10: Automation editor loads initial data (name/description populated)
 *
 * Uses real database, NO MOCKING.
 *
 * @since 7.0.0
 */

import { test, expect } from '../../fixtures';
import { uniqueId } from '../helpers';
import { openFlowDesignerPanel } from '../_helpers/flow-designer-harness';
import { ErrorCodes } from '~/shared/services/http-client/types';

// Keep the original viewport and drive responsive panels through their UI toggles.
test.use({ viewport: { width: 1680, height: 1050 } });

// ---------------------------------------------------------------------------
// Locator helpers — handle both Chinese (zh-CN) and English (en-US) i18n
// ---------------------------------------------------------------------------

/**
 * Locate the automation name input.
 * zh-CN placeholder: "自动化名称", en-US: "Automation name"
 */
function nameInput(page: import('@playwright/test').Page) {
  return page.getByTestId('automation-editor-name-input');
}

/**
 * Locate the automation description input.
 * zh-CN placeholder: "描述（可选）", en-US: "Description (optional)"
 */
function descriptionInput(page: import('@playwright/test').Page) {
  return page.getByTestId('automation-editor-description-input');
}

/**
 * Locate the FlowPalette panel (left sidebar inside the designer).
 */
function flowPalette(page: import('@playwright/test').Page) {
  return page.locator('[data-testid="flow-palette"]').first();
}

// ---------------------------------------------------------------------------
// API Helpers — used ONLY for data setup & cleanup
// ---------------------------------------------------------------------------

async function createAutomationViaApi(
  page: import('@playwright/test').Page,
  overrides: Record<string, unknown> = {}
): Promise<{ pid: string; name: string; description: string }> {
  const name = (overrides.name as string) ?? `Designer Test ${uniqueId()}`;
  const description = (overrides.description as string) ?? 'E2E designer test automation';
  const resp = await page.request.post('/api/automations', {
    data: {
      name,
      description,
      triggerType: 'on_record_create',
      modelCode: 'e2et_order',
      actions: [
        { type: 'send_notification', config: { notificationType: 'in_app', title: 'Designer test', content: 'E2E notification', recipients: 'ROLE:admin' }, sequence: 0, label: 'Notify' },
      ],
      enabled: false,
      ...overrides,
    },
  });
  expect(resp.status()).toBe(200);
  const body = await resp.json();
  if (String(body.code) !== ErrorCodes.SUCCESS) {
    throw new Error(`Failed to create automation: ${body.message || JSON.stringify(body)}`);
  }
  return { pid: body.data.pid, name, description };
}

async function deleteAutomationViaApi(
  page: import('@playwright/test').Page,
  pid: string
): Promise<void> {
  await page.request.delete(`/api/automations/${pid}`).catch(() => {});
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

test.describe('Automation Designer', () => {
  let testAutomation: { pid: string; name: string; description: string };
  const createdPids: string[] = [];

  test.beforeAll(async ({ browser }) => {
    const context = await browser.newContext({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
    const page = await context.newPage();
    try {
      testAutomation = await createAutomationViaApi(page);
      createdPids.push(testAutomation.pid);
    } finally {
      await context.close();
    }
  });

  test.afterAll(async ({ browser }) => {
    const context = await browser.newContext({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
    const page = await context.newPage();
    for (const pid of createdPids) {
      await deleteAutomationViaApi(page, pid);
    }
    await page.close();
    await context.close();
  });

  /**
   * AD-01: Designer page loads with three-column layout @smoke
   *
   * The FlowDesigner renders: Palette (left, w-64), Canvas (center, flex-1), Properties (right, w-80).
   */
  test('AD-01: designer page loads with three-column layout @smoke', async ({ page }) => {
    if (!testAutomation?.pid) { throw new Error(String('Test automation not created')); }

    await page.goto(`/automation/${testAutomation.pid}`);
    await page.waitForLoadState('domcontentloaded');
    await expect(page.locator('header[data-hydrated="true"]')).toBeVisible();

    // Verify the name input is visible (editor loaded)
    await expect(nameInput(page)).toBeVisible({ timeout: 10000 });

    // Palette column (left side — FlowPalette uses w-64 + overflow-y-auto)
    await openFlowDesignerPanel(page, 'palette');
    const palette = flowPalette(page);
    await expect(palette).toBeVisible({ timeout: 5000 });
    await page.screenshot({ path: test.info().outputPath('AD-01-palette.png'), fullPage: true });

    // Canvas area — ReactFlow renders inside a flex-1 container
    const canvas = page.locator('.react-flow__renderer').first();
    await expect(canvas).toBeVisible({ timeout: 8000 });

    await openFlowDesignerPanel(page, 'inspector');
    const properties = page.getByTestId('flow-inspector-shell');
    await expect(properties).toBeVisible({ timeout: 5000 });
    await page.screenshot({ path: test.info().outputPath('AD-01-inspector.png'), fullPage: true });
  });

  /**
   * AD-02: Palette shows node categories (trigger/action/control) @critical
   *
   * Category headers are rendered as toggle buttons in the palette.
   */
  test('AD-02: palette shows node categories @critical', async ({ page }) => {
    if (!testAutomation?.pid) { throw new Error(String('Test automation not created')); }

    await page.goto(`/automation/${testAutomation.pid}`);
    await page.waitForLoadState('domcontentloaded');
    await expect(page.locator('header[data-hydrated="true"]')).toBeVisible();

    await expect(nameInput(page)).toBeVisible({ timeout: 10000 });

    // The palette groups nodes by category with toggle buttons
    await openFlowDesignerPanel(page, 'palette');
    const palette = flowPalette(page);
    await expect(palette).toBeVisible({ timeout: 5000 });

    // Each category section renders a toggle button
    const categoryHeaders = palette.locator('[data-testid^="flow-palette-category-"] > button');
    await expect(categoryHeaders).not.toHaveCount(0, { timeout: 5000 });

    // Verify at least the three expected categories are present
    // zh-CN: "触发器", "操作", "控制"; en-US: "Triggers", "Actions", "Controls"
    const headerTexts = await categoryHeaders.allTextContents();
    expect(headerTexts.length).toBeGreaterThanOrEqual(3);
  });

  /**
   * AD-03: Palette displays node items with proper icons (not raw text like "Plus")
   *
   * After the fix, lucide icon names (Plus, Save, etc.) should render as SVG elements,
   * not as plain text strings.
   */
  test('AD-03: palette displays node items with proper icons', async ({ page }) => {
    if (!testAutomation?.pid) { throw new Error(String('Test automation not created')); }

    await page.goto(`/automation/${testAutomation.pid}`);
    await page.waitForLoadState('domcontentloaded');
    await expect(page.locator('header[data-hydrated="true"]')).toBeVisible();

    await expect(nameInput(page)).toBeVisible({ timeout: 10000 });

    // Find draggable items in the palette
    await openFlowDesignerPanel(page, 'palette');
    const palette = flowPalette(page);
    const paletteItems = palette.locator('[draggable="true"]');
    await expect(paletteItems.first()).toBeVisible({ timeout: 5000 });

    const itemCount = await paletteItems.count();
    expect(itemCount).toBeGreaterThan(0);

    // Check that lucide icon names (like "Plus", "Save", "Pencil", "Bell", etc.)
    // are NOT rendered as plain visible text. They should render as SVG icons.
    // The icon container is the first span.text-lg inside each item.
    const lucideIconNames = ['Plus', 'Save', 'Pencil', 'Bell', 'Terminal', 'Globe', 'Send', 'Play', 'FilePlus'];
    for (const iconName of lucideIconNames) {
      await expect(palette.getByText(iconName, { exact: true })).toHaveCount(0);
    }

    // Verify that SVG icons exist in the palette items (lucide renders as svg)
    const svgIcons = palette.locator('[draggable="true"] svg');
    const svgCount = await svgIcons.count();
    // Triggers and actions use lucide icons (at least 6+7=13 nodes have them)
    expect(svgCount).toBeGreaterThan(0);
  });

  /**
   * AD-04: Canvas area is visible and interactive
   */
  test('AD-04: canvas area is visible and interactive', async ({ page }) => {
    if (!testAutomation?.pid) { throw new Error(String('Test automation not created')); }

    await page.goto(`/automation/${testAutomation.pid}`);
    await page.waitForLoadState('domcontentloaded');
    await expect(page.locator('header[data-hydrated="true"]')).toBeVisible();

    await expect(nameInput(page)).toBeVisible({ timeout: 10000 });

    // ReactFlow canvas should be attached (outer wrapper may have 0x0 initially)
    const canvas = page.locator('.react-flow__renderer').first();
    await expect(canvas).toBeVisible({ timeout: 8000 });

    // ReactFlow renders a viewport container
    const viewport = page.locator('.react-flow__viewport').first();
    await expect(viewport).toBeVisible({ timeout: 5000 });

    // Canvas should accept drag-over (has onDragOver handler)
    const pane = page.locator('.react-flow__pane').first();
    await expect(pane).toBeVisible({ timeout: 5000 });
    const transform = await viewport.getAttribute('style');
    await page.locator('.react-flow__controls button').first().click();
    await expect(viewport).not.toHaveAttribute('style', transform!);
  });

  /**
   * AD-05: Palette items are draggable
   */
  test('AD-05: palette items are draggable', async ({ page }) => {
    if (!testAutomation?.pid) { throw new Error(String('Test automation not created')); }

    await page.goto(`/automation/${testAutomation.pid}`);
    await page.waitForLoadState('domcontentloaded');
    await expect(page.locator('header[data-hydrated="true"]')).toBeVisible();

    await expect(nameInput(page)).toBeVisible({ timeout: 10000 });

    // Find all draggable items in the palette
    await openFlowDesignerPanel(page, 'palette');
    const palette = flowPalette(page);
    const draggableItems = palette.locator('[draggable="true"]');

    await expect(draggableItems.first()).toBeVisible({ timeout: 5000 });
    const count = await draggableItems.count();
    expect(count).toBeGreaterThan(0);

    // Verify each item has draggable=true attribute
    for (let i = 0; i < Math.min(count, 3); i++) {
      const item = draggableItems.nth(i);
      await expect(item).toHaveAttribute('draggable', 'true');
    }

    // Verify items have cursor-grab class for visual feedback
    const firstItem = draggableItems.first();
    const classList = await firstItem.getAttribute('class');
    expect(classList).toContain('cursor-grab');
  });

  /**
   * AD-06: Name and description inputs are editable in header
   */
  test('AD-06: name and description inputs are editable', async ({ page }) => {
    if (!testAutomation?.pid) { throw new Error(String('Test automation not created')); }

    await page.goto(`/automation/${testAutomation.pid}`);
    await page.waitForLoadState('domcontentloaded');
    await expect(page.locator('header[data-hydrated="true"]')).toBeVisible();

    // Verify name input is editable
    const name = nameInput(page);
    await expect(name).toBeVisible({ timeout: 10000 });
    await expect(name).toBeEnabled();

    // Clear and type new name
    await name.clear();
    await name.fill('Updated Designer Name');
    const nameValue = await name.inputValue();
    expect(nameValue).toBe('Updated Designer Name');

    // Verify description input is editable
    const desc = descriptionInput(page);
    await expect(desc).toBeVisible({ timeout: 5000 });
    await expect(desc).toBeEnabled();

    // Clear and type new description
    await desc.clear();
    await desc.fill('Updated description text');
    const descValue = await desc.inputValue();
    expect(descValue).toBe('Updated description text');
  });

  /**
   * AD-07: Save button triggers API call @critical
   *
   * The FlowToolbar renders a Save button (bg-blue-600) when onSave is provided.
   * After modifying the name and clicking Save, it should call PUT /api/automations/{pid}.
   */
  test('AD-07: save button triggers API call @critical', async ({ page }) => {
    const saveFixture = await createAutomationViaApi(page);
    createdPids.push(saveFixture.pid);

    await page.goto(`/automation/${saveFixture.pid}`);
    await page.waitForLoadState('domcontentloaded');
    await expect(page.locator('header[data-hydrated="true"]')).toBeVisible();

    const name = nameInput(page);
    await expect(name).toBeVisible({ timeout: 10000 });

    // Modify the name to mark the editor as dirty
    await name.clear();
    await name.fill(`${saveFixture.name} Modified`);

    const updatedName = `${saveFixture.name} Modified`;
    await expect(name).toHaveValue(updatedName);
    const saveButton = page.getByTestId('automation-editor-toolbar-btn-save');
    await expect(saveButton).toBeEnabled();
    const responsePromise = page.waitForResponse(
      (resp) => new URL(resp.url()).pathname === `/api/automations/${saveFixture.pid}`
        && resp.request().method() === 'PUT',
    );
    await saveButton.click();
    const response = await responsePromise;
    expect(response.status()).toBe(200);
    // Read the exact record through an independent GET; browser PUT body retrieval can stall.
    const saved = await page.request.get(`/api/automations/${saveFixture.pid}`);
    expect(saved.status()).toBe(200);
    const body = await saved.json();
    expect(String(body.code)).toBe(ErrorCodes.SUCCESS);
    expect(body.data.name).toBe(updatedName);
    await page.reload();
    await expect(page.locator('header[data-hydrated="true"]')).toBeVisible();
    await expect(nameInput(page)).toHaveValue(updatedName);
    await page.screenshot({ path: test.info().outputPath('AD-07-saved.png'), fullPage: true });
  });

  /**
   * AD-08: Debug button is visible when editing existing automation
   *
   * The AutomationEditor shows a "Debug" button (bg-gray-800) when automationId is set.
   */
  test('AD-08: debug button is visible for existing automation', async ({ page }) => {
    if (!testAutomation?.pid) { throw new Error(String('Test automation not created')); }

    await page.goto(`/automation/${testAutomation.pid}`);
    await page.waitForLoadState('domcontentloaded');
    await expect(page.locator('header[data-hydrated="true"]')).toBeVisible();

    await expect(nameInput(page)).toBeVisible({ timeout: 10000 });

    // Debug button — zh-CN "调试", en-US "Debug"
    const debugButton = page.locator('button').filter({ hasText: /debug|调试/i }).first();
    await expect(debugButton).toBeVisible({ timeout: 5000 });

    // Debug button should NOT be visible on /automation/new (no automationId)
    await page.goto('/automation/new');
    await page.waitForLoadState('domcontentloaded');
    await expect(page.locator('header[data-hydrated="true"]')).toBeVisible();

    await expect(nameInput(page)).toBeVisible({ timeout: 10000 });

    const debugButtonOnNew = page.locator('button').filter({ hasText: /debug|调试/i }).first();
    await expect(debugButtonOnNew).toBeHidden({ timeout: 3000 });
  });

  /**
   * AD-09: i18n labels render correctly (not raw keys like $i18n:...)
   *
   * Verify that no visible text in the editor contains raw i18n key prefixes.
   */
  test('AD-09: i18n labels render without raw key prefixes', async ({ page }) => {
    if (!testAutomation?.pid) { throw new Error(String('Test automation not created')); }

    await page.goto(`/automation/${testAutomation.pid}`);
    await page.waitForLoadState('domcontentloaded');
    await expect(page.locator('header[data-hydrated="true"]')).toBeVisible();

    const name = nameInput(page);
    await expect(name).toBeVisible({ timeout: 10000 });

    // Check that no visible text contains the raw $i18n: prefix
    // This would indicate the i18n system failed to resolve the key
    const rawI18nElements = page.locator('text=/\\$i18n:/');
    await expect(rawI18nElements).toHaveCount(0);

    // At minimum, verify that placeholders and button text are not empty
    const placeholderValue = await name.getAttribute('placeholder');
    expect(placeholderValue).toBeTruthy();
    expect(placeholderValue).not.toBe('');
  });

  /**
   * AD-10: Automation editor loads initial data (name/description populated)
   */
  test('AD-10: editor loads initial data correctly', async ({ page }) => {
    if (!testAutomation?.pid) { throw new Error(String('Test automation not created')); }

    await page.goto(`/automation/${testAutomation.pid}`);
    await page.waitForLoadState('domcontentloaded');
    await expect(page.locator('header[data-hydrated="true"]')).toBeVisible();

    // Verify name input has the automation name
    const name = nameInput(page);
    await expect(name).toBeVisible({ timeout: 10000 });

    // The name might have been modified by AD-07, so check it contains either original or modified
    const nameValue = await name.inputValue();
    expect(nameValue).toBe(testAutomation.name);

    // Verify description input has the description
    const desc = descriptionInput(page);
    await expect(desc).toBeVisible({ timeout: 5000 });
    const descValue = await desc.inputValue();
    expect(descValue).toBe(testAutomation.description);

    // Verify the page title in the toolbar includes the automation name
    // zh-CN: "编辑自动化: {name}", en-US: "Edit Automation: {name}"
    const toolbarTitle = page.locator('h1').first();
    await expect(toolbarTitle).toBeVisible({ timeout: 5000 });
    const titleText = await toolbarTitle.textContent();
    expect(titleText).toContain(testAutomation.name);
  });
});
