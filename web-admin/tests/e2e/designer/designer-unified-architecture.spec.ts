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
test.use({ locale: 'zh-CN' });

// ---------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------

async function openReportDesigner(page: import('@playwright/test').Page) {
  await page.goto('/report-designer', { waitUntil: 'domcontentloaded' });
  // SSR already contains the default title; wait for actual client event binding.
  await expect(page.locator('header[data-hydrated="true"]')).toBeVisible({ timeout: 30000 });
  // Wait for block palette to appear (may be SSR-rendered or client-rendered)
  await expect(page.getByTestId('block-palette')).toBeVisible({ timeout: 30000 });
  // Confirm the initialized localized document before editing.
  const titleInput = page.getByPlaceholder(/^(报表标题|Report Title)$/);
  await expect(titleInput).toHaveValue('未命名报表', { timeout: 15000 });
}

async function saveAndVerifyReport(page: import('@playwright/test').Page, title: string) {
  const saveResponse = page.waitForResponse(
    (response) =>
      /^\/api\/report-definitions(?:\/[^/]+)?$/.test(new URL(response.url()).pathname) &&
      ['POST', 'PUT'].includes(response.request().method()),
  );
  await page.getByTestId('report-designer-toolbar-btn-save').click();
  const response = await saveResponse;
  expect(response.status()).toBe(200);
  const saved = await response.json();
  expect(Number(saved.code)).toBe(0);
  expect(saved.data.pid).toBeTruthy();
  const persistedResponse = await page.request.get(`/api/report-definitions/${saved.data.pid}`);
  expect(persistedResponse.status()).toBe(200);
  const persisted = await persistedResponse.json();
  expect(Number(persisted.code)).toBe(0);
  expect(persisted.data.title).toBe(title);
  expect(persisted.data.dsl.title).toBe(title);
  return saved.data.pid as string;
}

async function openSavedVersionHistory(page: import('@playwright/test').Page, pid: string) {
  const historyResponse = page.waitForResponse(
    (response) => new URL(response.url()).pathname === `/api/report-definitions/${pid}/versions`,
  );
  await page.getByTitle(/^(版本历史|Version History)$/).click();
  const response = await historyResponse;
  expect(response.status()).toBe(200);
  const history = await response.json();
  expect(Number(history.code)).toBe(0);
  expect(Array.isArray(history.data)).toBe(true);
  expect(history.data.length).toBeGreaterThan(0);
  const panel = page.getByTestId('version-history-panel');
  await expect(panel.getByRole('heading', { name: '版本历史', exact: true })).toBeVisible();
  await expect(panel.getByText(`v${history.data[0].version}`, { exact: true })).toBeVisible();
  await expect(panel.getByText(`${history.data.length} 个可用版本`, { exact: true })).toBeVisible();
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
    await page.screenshot({ path: test.info().outputPath('DUA-01.png'), fullPage: true });
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
    await page.screenshot({ path: test.info().outputPath('DUA-02.png'), fullPage: true });
  });

  test('DUA-03: Keyboard shortcuts work alongside toolbar buttons', async ({ page }) => {
    await openReportDesigner(page);
    const canvas = page.getByTestId('report-canvas');

    // Add a block via palette testId
    const palette = page.getByTestId('block-palette');
    await palette.getByTestId('block-palette-item-rich-text').click();
    await expect(canvas.getByText('点击添加文本内容', { exact: true })).toBeVisible({
      timeout: 10000,
    });

    // Ctrl+Z undoes
    await page.keyboard.press('ControlOrMeta+z');
    await expect(canvas.getByText('点击添加文本内容', { exact: true })).not.toBeVisible({
      timeout: 5000,
    });

    // Ctrl+Y redoes
    await page.keyboard.press('ControlOrMeta+y');
    await expect(canvas.getByText('点击添加文本内容', { exact: true })).toBeVisible({
      timeout: 5000,
    });
    await page.screenshot({ path: test.info().outputPath('DUA-03.png'), fullPage: true });
  });
});

// =========================================================================
// GAP 2: Report Version History Panel
// =========================================================================

test.describe('GAP 2: Report Version History', () => {
  test('DUA-06: History button visible, opens panel with empty state', async ({ page }) => {
    await openReportDesigner(page);

    // History button visible
    const historyBtn = page.getByTitle(/^(版本历史|Version History)$/);
    await expect(historyBtn).toBeVisible();

    // Click opens panel
    await historyBtn.click();
    await expect(
      page
        .getByTestId('version-history-panel')
        .getByRole('heading', { name: '版本历史', exact: true }),
    ).toBeVisible({ timeout: 5000 });
    await expect(
      page.getByTestId('version-history-panel').getByText('暂无版本记录', { exact: true }),
    ).toBeVisible({ timeout: 5000 });
    await expect(
      page
        .getByTestId('version-history-panel')
        .getByText('保存后会生成第一个版本', { exact: true }),
    ).toBeVisible();
    await page.screenshot({ path: test.info().outputPath('DUA-06.png'), fullPage: true });
  });

  test('DUA-07: Version panel opens with ESC close', async ({ page }) => {
    await openReportDesigner(page);
    const panel = page.getByTestId('version-history-panel');

    // Open panel
    await page.getByTitle(/^(版本历史|Version History)$/).click();
    await expect(panel).toHaveClass(/translate-x-0/, { timeout: 5000 });

    // Verify close button exists in panel header
    await expect(panel.getByRole('button', { name: '关闭版本面板', exact: true })).toBeVisible();

    // Close with ESC
    await page.keyboard.press('Escape');
    await expect(panel).toHaveClass(/translate-x-full/, { timeout: 5000 });
    await page.screenshot({ path: test.info().outputPath('DUA-07.png'), fullPage: true });
  });

  test('DUA-09: Save report then check version panel loads', async ({ page }) => {
    await openReportDesigner(page);

    // Set unique title
    const title = `VH Test ${uniqueId('vh')}`;
    await page.getByPlaceholder(/^(报表标题|Report Title)$/).fill(title);

    // Save
    const saveBtn = page.getByTestId('report-designer-toolbar-btn-save');
    await expect(saveBtn).toBeEnabled();

    const pid = await saveAndVerifyReport(page, title);
    await expect(saveBtn).toBeDisabled();
    await openSavedVersionHistory(page, pid);

    await page.screenshot({ path: test.info().outputPath('DUA-09.png'), fullPage: true });
  });
});

// =========================================================================
// GAP 3: Shared DataSource Components
// =========================================================================

test.describe('GAP 3: Report DataSource — Shared Pickers', () => {
  test('DUA-11: Add data source shows type selector with Model/NQ/API', async ({ page }) => {
    await openReportDesigner(page);

    // Add data-table block and select it
    await page.getByTestId('block-palette-item-table').click();
    await expect(
      page.getByTestId('report-canvas').getByText('请在属性面板中配置列', { exact: true }),
    ).toBeVisible({ timeout: 10000 });
    await page
      .getByTestId('report-canvas')
      .getByText('请在属性面板中配置列', { exact: true })
      .click();

    const panel = page.getByTestId('block-property-panel');
    await panel.getByRole('button', { name: '+ 添加数据源', exact: true }).click();

    // The DS type selector is the FIRST select in the add-source form (appears right after "Key" input)
    // It has options: Model, Named Query, API
    const addDsForm = panel.getByPlaceholder('名称（例如 main）', { exact: true }).locator('..'); // add-ds form has gray bg
    const typeSelect = addDsForm
      .locator('select')
      .filter({ has: page.locator('option[value="namedQuery"]') });
    await expect(typeSelect).toBeVisible();
    const options = await typeSelect.locator('option').allTextContents();
    expect(options).toContain('模型');
    expect(options).toContain('命名查询');
    expect(options).toContain('API');
    await page.screenshot({ path: test.info().outputPath('DUA-11.png'), fullPage: true });
  });

  test('DUA-12: Model type loads shared ModelPicker with API data', async ({ page }) => {
    await openReportDesigner(page);

    await page.getByTestId('block-palette-item-table').click();
    await expect(
      page.getByTestId('report-canvas').getByText('请在属性面板中配置列', { exact: true }),
    ).toBeVisible({ timeout: 10000 });
    await page
      .getByTestId('report-canvas')
      .getByText('请在属性面板中配置列', { exact: true })
      .click();

    const panel = page.getByTestId('block-property-panel');
    const modelsResponse = page.waitForResponse(
      (resp) => new URL(resp.url()).pathname === '/api/meta/models',
    );
    await panel.getByRole('button', { name: '+ 添加数据源', exact: true }).click();

    const response = await modelsResponse;
    expect(response.status()).toBe(200);
    const payload = await response.json();
    expect(Number(payload.code)).toBe(0);
    const models = Array.isArray(payload.data) ? payload.data : payload.data.records;
    expect(models.length).toBeGreaterThan(0);

    // ModelPicker renders as a select with "Select model" placeholder
    const modelSelect = panel
      .locator('select')
      .filter({ has: page.locator('option[value=""]', { hasText: '选择模型' }) });
    await expect(modelSelect).toBeVisible();
    const optionCount = await modelSelect.locator('option').count();
    expect(optionCount).toBeGreaterThan(1);
    await modelSelect.selectOption(models[0].code);
    await expect(modelSelect).toHaveValue(models[0].code);
    await page.screenshot({ path: test.info().outputPath('DUA-12.png'), fullPage: true });
  });

  test('DUA-13: NamedQuery type shows shared NamedQueryPicker', async ({ page }) => {
    await openReportDesigner(page);

    await page.getByTestId('block-palette-item-table').click();
    await expect(
      page.getByTestId('report-canvas').getByText('请在属性面板中配置列', { exact: true }),
    ).toBeVisible({ timeout: 10000 });
    await page
      .getByTestId('report-canvas')
      .getByText('请在属性面板中配置列', { exact: true })
      .click();

    const panel = page.getByTestId('block-property-panel');
    await panel.getByRole('button', { name: '+ 添加数据源', exact: true }).click();

    // Switch to namedQuery using the DS type selector (first select in the add-form)
    const addDsForm = panel.getByPlaceholder('名称（例如 main）', { exact: true }).locator('..');
    const typeSelect = addDsForm
      .locator('select')
      .filter({ has: page.locator('option[value="namedQuery"]') });
    const queriesResponse = page.waitForResponse(
      (resp) => new URL(resp.url()).pathname === '/api/meta/named-queries',
    );
    await typeSelect.selectOption('namedQuery');

    const response = await queriesResponse;
    expect(response.status()).toBe(200);
    const payload = await response.json();
    expect(Number(payload.code)).toBe(0);
    expect(Array.isArray(payload.data.content)).toBe(true);

    // NamedQueryPicker visible
    const nqSelect = panel
      .locator('select')
      .filter({ has: page.locator('option[value=""]', { hasText: '选择命名查询' }) });
    await expect(nqSelect).toBeVisible();
    await page.screenshot({ path: test.info().outputPath('DUA-13.png'), fullPage: true });
  });

  test('DUA-14: API type shows URL text input', async ({ page }) => {
    await openReportDesigner(page);

    await page.getByTestId('block-palette-item-table').click();
    await expect(
      page.getByTestId('report-canvas').getByText('请在属性面板中配置列', { exact: true }),
    ).toBeVisible({ timeout: 10000 });
    await page
      .getByTestId('report-canvas')
      .getByText('请在属性面板中配置列', { exact: true })
      .click();

    const panel = page.getByTestId('block-property-panel');
    await panel.getByRole('button', { name: '+ 添加数据源', exact: true }).click();

    // Switch to API using the DS type selector
    const addDsForm = panel.getByPlaceholder('名称（例如 main）', { exact: true }).locator('..');
    const typeSelect = addDsForm
      .locator('select')
      .filter({ has: page.locator('option[value="namedQuery"]') });
    await typeSelect.selectOption('api');

    // URL input visible
    const urlInput = panel.getByPlaceholder('API 地址', { exact: true });
    await expect(urlInput).toBeVisible();
    await urlInput.fill('/api/custom/data');
    await expect(urlInput).toHaveValue('/api/custom/data');
    await page.screenshot({ path: test.info().outputPath('DUA-14.png'), fullPage: true });
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
    await page.getByPlaceholder(/^(报表标题|Report Title)$/).fill(title);

    // 2. Add block via palette testId
    await page.getByTestId('block-palette-item-rich-text').click();
    await expect(canvas.getByText('点击添加文本内容', { exact: true })).toBeVisible({
      timeout: 10000,
    });
    await expect(undoBtn).toBeEnabled();

    // 3. Save
    const pid = await saveAndVerifyReport(page, title);
    await expect(saveBtn).toBeDisabled();

    // 4. Verify the saved report's real version history, then close it.
    await openSavedVersionHistory(page, pid);
    await page.keyboard.press('Escape');
    await expect(page.getByTestId('version-history-panel')).toHaveClass(/translate-x-full/);

    // 5. Undo block addition
    await undoBtn.click();
    await expect(canvas.getByText('点击添加文本内容', { exact: true })).not.toBeVisible({
      timeout: 5000,
    });

    // 6. Unsaved indicator visible (undid after save)
    await expect(
      page.getByTestId('report-designer-toolbar').getByText('未保存', { exact: true }),
    ).toBeVisible({ timeout: 3000 });
    await page.screenshot({ path: test.info().outputPath('DUA-15.png'), fullPage: true });
  });
});
