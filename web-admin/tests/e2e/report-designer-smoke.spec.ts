/**
 * Report Designer — Smoke E2E Tests
 *
 * Verifies:
 * 1. Report designer loads at /report-designer
 * 2. Adding a data-table block
 * 3. Adding header and footer
 * 4. Block selection and property panel interaction
 * 5. Page settings, preview mode, unsaved indicator
 */

import { test, expect } from '@playwright/test';
import { uniqueId } from './helpers';

const reportTitle = `E2E Report ${uniqueId('rpt')}`;

test.describe('Report Designer', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/report-designer', { waitUntil: 'load' });
    // Wait for navigation and the interactive designer to render
    await expect(page.getByRole('main').getByTestId('block-palette')).toBeVisible();
    await expect(page.getByRole('main').getByTestId('report-canvas')).toBeVisible();
    // New-report initialization runs in a client effect; SSR visibility is insufficient.
    await expect(
      page.getByRole('main').getByTestId('report-designer-toolbar').getByText(/^(未保存|Unsaved)$/),
    ).toBeVisible();
  });

  test('should load designer with 3-panel layout', async ({ page }) => {
    await expect(page.getByRole('main').getByTestId('block-palette')).toBeVisible();
    await expect(page.getByRole('main').getByTestId('report-canvas')).toBeVisible();
    await expect(page.getByRole('main').getByTestId('block-property-panel')).toBeVisible();

    // Toolbar elements
    await expect(page.getByRole('main').getByPlaceholder(/^(报表标题|Report Title)$/)).toBeVisible();
    await expect(page.getByRole('button', { name: /^(保存|Save)$/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /^(预览|Preview)$/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /^(导出 PDF|Export PDF)$/ })).toBeVisible();
  });

  test('should set report title', async ({ page }) => {
    const titleInput = page.getByRole('main').getByPlaceholder(/^(报表标题|Report Title)$/);
    await titleInput.fill(reportTitle);
    await expect(titleInput).toHaveValue(reportTitle);
  });

  test('should add a data-table block', async ({ page }) => {
    // Click "Data Table" button in palette using role selector for full button
    await page
      .getByTestId('block-palette')
      .getByRole('button', { name: /数据表格|Data Table/ })
      .click();

    // Wait for the block to appear on canvas
    await expect(
      page
        .getByTestId('report-canvas')
        .getByText(/^(请在属性面板中配置列|Configure columns in the property panel)$/),
    ).toBeVisible({ timeout: 10000 });
  });

  test('should add page header', async ({ page }) => {
    // Click "Page Header" button
    await page
      .getByTestId('block-palette')
      .getByRole('button', { name: /页眉|Page Header/ })
      .click();

    // Canvas should show "Header" label
    await expect(
      page.getByRole('main').getByTestId('report-canvas').getByText(/页眉|Header/, { exact: true }),
    ).toBeVisible({ timeout: 10000 });
  });

  test('should add page footer', async ({ page }) => {
    // Click "Page Footer" button
    await page
      .getByTestId('block-palette')
      .getByRole('button', { name: /页脚|Page Footer/ })
      .click();

    // Canvas should show "Footer" label
    await expect(
      page.getByRole('main').getByTestId('report-canvas').getByText(/页脚|Footer/, { exact: true }),
    ).toBeVisible({ timeout: 10000 });
  });

  test('should select block and show property panel', async ({ page }) => {
    // Add a data-table block first
    await page
      .getByTestId('block-palette')
      .getByRole('button', { name: /数据表格|Data Table/ })
      .click();
    await expect(
      page
        .getByTestId('report-canvas')
        .getByText(/^(请在属性面板中配置列|Configure columns in the property panel)$/),
    ).toBeVisible({ timeout: 10000 });

    // Click on the block in canvas to select it
    await page
      .getByTestId('report-canvas')
      .getByText(/^(请在属性面板中配置列|Configure columns in the property panel)$/)
      .click();

    // Property panel should show "Data Table" heading and editor
    await expect(
      page.getByRole('main').getByTestId('block-property-panel').getByText(/^(数据表格|Data Table)$/),
    ).toBeVisible();
    // Should show title input for the block
    await expect(
      page.getByRole('main').getByTestId('block-property-panel').getByPlaceholder(/^(表格标题|Table title)$/),
    ).toBeVisible();
  });

  test('should open page settings dialog', async ({ page }) => {
    // Click Settings button in toolbar
    await page
      .getByTestId('report-designer-toolbar')
      .getByRole('button', { name: /^(设置|Settings)$/ })
      .click();

    // Settings dialog should appear
    await expect(page.getByText(/^(页面设置|Page Settings)$/)).toBeVisible();
    // Check page size select has A4 selected
    await expect(page.locator('select').first()).toHaveValue('A4');

    // Close dialog
    await page.getByRole('button', { name: /^(取消|Cancel)$/ }).click();
    await expect(page.getByText(/^(页面设置|Page Settings)$/)).not.toBeVisible();
  });

  test('should toggle preview mode', async ({ page }) => {
    // Enter preview mode
    await page.getByRole('button', { name: /^(预览|Preview)$/ }).click();

    // In preview mode, the button should say "Edit"
    await expect(page.getByRole('button', { name: /^(编辑|Edit)$/ })).toBeVisible();

    // Canvas/palette should not be visible in preview mode
    await expect(page.getByRole('main').getByTestId('block-palette')).not.toBeVisible();

    // Toggle back to design mode
    await page.getByRole('button', { name: /^(编辑|Edit)$/ }).click();
    await expect(page.getByRole('main').getByTestId('block-palette')).toBeVisible();
  });

  test('should show unsaved indicator', async ({ page }) => {
    // New reports start as dirty (unsaved)
    await expect(page.getByText(/^(未保存|Unsaved)$/)).toBeVisible();
  });

  // ==================== Phase 2b: New Block Types ====================

  test('should add a grouped-table block', async ({ page }) => {
    await page
      .getByTestId('block-palette')
      .getByRole('button', { name: /分组表格|Grouped Table/ })
      .click();

    // Canvas should show the grouped table placeholder
    await expect(
      page
        .getByTestId('report-canvas')
        .getByText(/请在属性面板中选择分组字段|Select\ a\ group\-by\ field/),
    ).toBeVisible({ timeout: 10000 });
  });

  test('should add a stat-card block', async ({ page }) => {
    await page
      .getByTestId('block-palette')
      .getByRole('button', { name: /指标卡片|Stat Card/ })
      .click();

    // Canvas should show the stat card with sample value
    await expect(page.getByRole('main').getByTestId('report-canvas').getByText('12,345')).toBeVisible({
      timeout: 10000,
    });
  });

  test('should add a rich-text block', async ({ page }) => {
    await page
      .getByTestId('block-palette')
      .getByRole('button', { name: /富文本|Rich Text/ })
      .click();

    // Canvas should show the rich text placeholder
    await expect(
      page.getByRole('main').getByTestId('report-canvas').getByText(/点击添加文本内容|Click\ to\ add\ text\ content/),
    ).toBeVisible({ timeout: 10000 });
  });

  test('should select grouped-table and show property panel', async ({ page }) => {
    await page
      .getByTestId('block-palette')
      .getByRole('button', { name: /分组表格|Grouped Table/ })
      .click();
    await expect(
      page
        .getByTestId('report-canvas')
        .getByText(/请在属性面板中选择分组字段|Select\ a\ group\-by\ field/),
    ).toBeVisible({ timeout: 10000 });

    // Click the block to select it
    await page
      .getByTestId('report-canvas')
      .getByText(/请在属性面板中选择分组字段|Select\ a\ group\-by\ field/)
      .click();

    // Property panel should show "Grouped Table"
    await expect(
      page.getByRole('main').getByTestId('block-property-panel').getByText(/^(分组表格|Grouped Table)$/),
    ).toBeVisible();
    // Should show group by field input
    await expect(
      page
        .getByTestId('block-property-panel')
        .getByPlaceholder(/^(用于分组的字段|Field name to group by)$/),
    ).toBeVisible();
  });

  test('should select stat-card and show color picker', async ({ page }) => {
    await page
      .getByTestId('block-palette')
      .getByRole('button', { name: /指标卡片|Stat Card/ })
      .click();
    await expect(page.getByRole('main').getByTestId('report-canvas').getByText(/指标|Metric/)).toBeVisible({
      timeout: 10000,
    });

    await page
      .getByTestId('report-canvas')
      .getByText(/指标|Metric/)
      .click();

    await expect(
      page.getByRole('main').getByTestId('block-property-panel').getByText(/^(指标卡片|Stat Card)$/),
    ).toBeVisible();
    await expect(
      page.getByRole('main').getByTestId('block-property-panel').getByText(/^(颜色|Color)$/),
    ).toBeVisible();
  });

  test('should select rich-text and show content editor', async ({ page }) => {
    await page
      .getByTestId('block-palette')
      .getByRole('button', { name: /富文本|Rich Text/ })
      .click();
    await expect(
      page.getByRole('main').getByTestId('report-canvas').getByText(/点击添加文本内容|Click\ to\ add\ text\ content/),
    ).toBeVisible({ timeout: 10000 });

    await page
      .getByTestId('report-canvas')
      .getByText(/点击添加文本内容|Click\ to\ add\ text\ content/)
      .click();

    await expect(
      page.getByRole('main').getByTestId('block-property-panel').getByText(/^(富文本|Rich Text)$/),
    ).toBeVisible();
    const panel = page.getByRole('main').getByTestId('block-property-panel');
    const content = panel.getByPlaceholder(/^(输入文本内容…|Enter text content\.\.\.)$/);
    await expect(content).toBeVisible();
    await content.fill('Localized report editor content');
    await expect(
      page.getByRole('main').getByTestId('report-canvas').getByText('Localized report editor content'),
    ).toBeVisible();
    await panel.getByRole('button', { name: /^(居中|Center)$/ }).click();
    await expect(panel.getByRole('button', { name: /^(居中|Center)$/ })).toHaveClass(
      /border-blue-300/,
    );
    await expect(panel.getByText(/^(字号（磅）|Font Size \(pt\))$/)).toBeVisible();
    await page.screenshot({
      path: `${process.env.AURA_EVIDENCE_DIR}/report-rich-text-properties.png`,
      fullPage: true,
    });
  });

  test('should have all 10 block types in palette', async ({ page }) => {
    await expect(
      page.getByRole('main').getByTestId('block-palette').getByRole('button', { name: /数据表格|Data Table/ }),
    ).toBeVisible();
    await expect(
      page.getByRole('main').getByTestId('block-palette').getByRole('button', { name: /分组表格|Grouped Table/ }),
    ).toBeVisible();
    await expect(
      page.getByRole('main').getByTestId('block-palette').getByRole('button', { name: /指标卡片|Stat Card/ }),
    ).toBeVisible();
    await expect(
      page.getByRole('main').getByTestId('block-palette').getByRole('button', { name: /富文本|Rich Text/ }),
    ).toBeVisible();
    await expect(
      page.getByRole('main').getByTestId('block-palette').getByRole('button', { name: /交叉表|Cross Tab/ }),
    ).toBeVisible();
    await expect(
      page.getByRole('main').getByTestId('block-palette').getByRole('button', { name: /图表|Chart/ }),
    ).toBeVisible();
    await expect(
      page.getByRole('main').getByTestId('block-palette').getByRole('button', { name: /页眉|Page Header/ }),
    ).toBeVisible();
    await expect(
      page.getByRole('main').getByTestId('block-palette').getByRole('button', { name: /页脚|Page Footer/ }),
    ).toBeVisible();
    await expect(
      page.getByRole('main').getByTestId('block-palette').getByRole('button', { name: /条码|Barcode/ }),
    ).toBeVisible();
    await expect(
      page.getByRole('main').getByTestId('block-palette').getByRole('button', { name: /水印|Watermark/ }),
    ).toBeVisible();
  });

  // ==================== Phase 2c: Cross Tab + Chart + Parameters ====================

  test('should add a cross-tab block', async ({ page }) => {
    await page
      .getByTestId('block-palette')
      .getByRole('button', { name: /交叉表|Cross Tab/ })
      .click();
    await expect(
      page
        .getByTestId('report-canvas')
        .getByText(/请配置行、列和数值字段|Configure\ row,\ column,\ and\ value\ fields/),
    ).toBeVisible({ timeout: 10000 });
  });

  test('should add a chart block', async ({ page }) => {
    await page
      .getByTestId('block-palette')
      .getByRole('button', { name: /图表|Chart/ })
      .click();
    await expect(
      page
        .getByTestId('report-canvas')
        .getByText(/请配置分类和数值字段|Configure\ category\ and\ value\ fields/),
    ).toBeVisible({ timeout: 10000 });
  });

  test('should select cross-tab and show property panel', async ({ page }) => {
    await page
      .getByTestId('block-palette')
      .getByRole('button', { name: /交叉表|Cross Tab/ })
      .click();
    await expect(
      page
        .getByTestId('report-canvas')
        .getByText(/请配置行、列和数值字段|Configure\ row,\ column,\ and\ value\ fields/),
    ).toBeVisible({ timeout: 10000 });

    await page
      .getByTestId('report-canvas')
      .getByText(/请配置行、列和数值字段|Configure\ row,\ column,\ and\ value\ fields/)
      .click();
    await expect(
      page.getByRole('main').getByTestId('block-property-panel').getByText(/^(交叉表|Cross Tab)$/),
    ).toBeVisible();
    await expect(
      page
        .getByTestId('block-property-panel')
        .getByPlaceholder(/^(用于行分组的字段|Field for row grouping)$/),
    ).toBeVisible();
  });

  test('should select chart and show chart type selector', async ({ page }) => {
    await page
      .getByTestId('block-palette')
      .getByRole('button', { name: /图表|Chart/ })
      .click();
    await expect(
      page
        .getByTestId('report-canvas')
        .getByText(/请配置分类和数值字段|Configure\ category\ and\ value\ fields/),
    ).toBeVisible({ timeout: 10000 });

    await page
      .getByTestId('report-canvas')
      .getByText(/请配置分类和数值字段|Configure\ category\ and\ value\ fields/)
      .click();
    await expect(
      page.getByRole('main').getByTestId('block-property-panel').getByText(/^(图表|Chart)$/),
    ).toBeVisible();
    // Chart type buttons
    await expect(
      page
        .getByTestId('block-property-panel')
        .getByRole('button', { name: /^(柱状图|Bar)$/, exact: true }),
    ).toBeVisible();
    await expect(
      page
        .getByTestId('block-property-panel')
        .getByRole('button', { name: /^(饼图|Pie)$/, exact: true }),
    ).toBeVisible();
  });

  test('should show parameter editor in report properties', async ({ page }) => {
    // Click empty canvas area to deselect any block
    await page.getByRole('main').getByTestId('report-canvas').click({ position: { x: 10, y: 10 } });

    // Property panel should show "Report Properties" with Parameter section
    await expect(
      page.getByRole('main').getByTestId('block-property-panel').getByText(/^(参数|Parameters)$/, { exact: true }),
    ).toBeVisible();
    const panel = page.getByRole('main').getByTestId('block-property-panel');
    await panel.getByRole('button', { name: /^(\+ 添加|\+ Add)$/ }).click();
    await panel.getByPlaceholder(/^(参数名|Parameter name)$/).fill('report_limit');
    await panel.getByPlaceholder(/^(显示名称|Display label)$/).fill('Row limit');
    await panel.getByRole('button', { name: /^(添加|Add)$/, exact: true }).scrollIntoViewIfNeeded();
    await page.screenshot({
      path: `${process.env.AURA_EVIDENCE_DIR}/report-parameter-add.png`,
      fullPage: true,
    });
    await panel.getByRole('button', { name: /^(添加|Add)$/, exact: true }).click();
    await expect(panel.getByText(/^Row limit \((文本|Text)\)$/)).toBeVisible();
    await expect(panel.getByPlaceholder(/^(参数名|name)$/)).toHaveValue('report_limit');
    await panel.getByRole('button', { name: /^(删除参数|Remove parameter)$/ }).click();
    await expect(panel.getByText(/^Row limit/)).toHaveCount(0);
  });
});
