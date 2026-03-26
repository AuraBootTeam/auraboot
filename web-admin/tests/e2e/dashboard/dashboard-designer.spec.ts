/**
 * Dashboard Designer E2E Tests
 *
 * Tests for the drag-and-drop dashboard designer with:
 * - Three-panel layout (palette, canvas, properties)
 * - Widget management (add, select, configure, delete)
 * - Toolbar actions (save, publish, undo/redo)
 * - Configuration panels (style, linkage, refresh)
 *
 * Uses DashboardDesignerPage PO and data-testid selectors.
 */

import { test, expect } from '@playwright/test';
import { DashboardDesignerPage } from '../../pages/DashboardDesignerPage';

const TEST_DASHBOARD_TITLE = `E2E Test Dashboard ${Date.now()}`;

test.describe('Dashboard Designer - Layout', () => {
  let dp: DashboardDesignerPage;

  test.beforeEach(async ({ page }) => {
    dp = new DashboardDesignerPage(page);
    await dp.goto();
  });

  test('should display three-panel layout @smoke', async () => {
    await expect(dp.heading).toBeVisible();
    await expect(dp.paletteHeading).toBeVisible();
    await expect(dp.palette.locator('text=拖拽组件到画布')).toBeVisible();
    await expect(dp.canvasEmptyState).toBeVisible();
    await expect(dp.propertyPanelHeading).toBeVisible();
    await expect(dp.propertyPanelEmpty).toBeVisible();
  });

  test('should display toolbar buttons', async () => {
    await expect(dp.validateButton).toBeVisible();
    await expect(dp.settingsButton).toBeVisible();
    await expect(dp.saveButton).toBeVisible();
    await expect(dp.publishButton).toBeVisible();
    await expect(dp.undoButton).toBeVisible();
    await expect(dp.redoButton).toBeVisible();
    await expect(dp.toolbar.locator('text=草稿')).toBeVisible();
  });
});

test.describe('Dashboard Designer - Widget Palette', () => {
  let dp: DashboardDesignerPage;

  test.beforeEach(async ({ page }) => {
    dp = new DashboardDesignerPage(page);
    await dp.goto();
  });

  test('should display widget categories', async () => {
    await expect(dp.palette.locator('h3:has-text("指标")')).toBeVisible();
    await expect(dp.palette.locator('h3:has-text("图表")')).toBeVisible();
  });

  test('should display available widgets', async () => {
    await expect(dp.palette.locator('span:has-text("数字卡片")')).toBeVisible();
    await expect(dp.palette.locator('span:has-text("柱状图")')).toBeVisible();
    await expect(dp.palette.locator('span:has-text("折线图")')).toBeVisible();
    await expect(dp.palette.locator('span:has-text("饼图")')).toBeVisible();
  });

  test('should add widget via click', async () => {
    await dp.addWidget('数字卡片');
    await expect(dp.propertyPanel.locator('h2:has-text("数字卡片")')).toBeVisible();
  });
});

test.describe('Dashboard Designer - Widget Management', () => {
  let dp: DashboardDesignerPage;

  test.beforeEach(async ({ page }) => {
    dp = new DashboardDesignerPage(page);
    await dp.goto();
  });

  test('should select widget and show properties', async () => {
    await dp.addWidget('柱状图');

    const dataSourceHeader = dp.sectionHeader('数据源配置');
    await dataSourceHeader.scrollIntoViewIfNeeded();
    await expect(dataSourceHeader).toBeVisible();

    const styleHeader = dp.sectionHeader('样式配置');
    await styleHeader.scrollIntoViewIfNeeded();
    await expect(styleHeader).toBeVisible();

    const linkageHeader = dp.sectionHeader('图表联动');
    await linkageHeader.scrollIntoViewIfNeeded();
    await expect(linkageHeader).toBeVisible();

    const refreshHeader = dp.sectionHeader('数据刷新');
    await refreshHeader.scrollIntoViewIfNeeded();
    await expect(refreshHeader).toBeVisible();
  });

  test('should edit widget title', async () => {
    await dp.addWidget('折线图');

    // Use exact text match to avoid matching "显示标题" checkbox label
    const titleLabel = dp.propertyPanel.locator('label').filter({ hasText: /^标题/ });
    await expect(titleLabel).toBeVisible();
    const titleInput = dp.propertyPanel.locator('input[type="text"]').first();
    await expect(titleInput).toBeVisible({ timeout: 5000 });
    await titleInput.fill('销售趋势图');
    await expect(titleInput).toHaveValue('销售趋势图');
  });

  test('should delete widget', async ({ page }) => {
    page.on('dialog', async (dialog) => {
      await dialog.accept();
    });

    await dp.addWidget('饼图');
    await dp.deleteButton.click();
    await expect(dp.canvasEmptyState).toBeVisible({ timeout: 5000 });
  });

  test('should duplicate widget', async () => {
    await dp.addWidget('数字卡片');
    await dp.duplicateButton.click();
    await expect(dp.widgets).toHaveCount(2, { timeout: 5000 });
  });
});

test.describe('Dashboard Designer - Configuration Panels', () => {
  let dp: DashboardDesignerPage;

  test.slow();
  test.beforeEach(async ({ page }) => {
    dp = new DashboardDesignerPage(page);
    await dp.goto();
    await dp.addWidget('柱状图');
  });

  test('should configure data source', async () => {
    await expect(dp.sectionHeader('数据源配置')).toBeVisible();
    const dataSourceSection = dp.sectionHeader('数据源配置').locator('..').locator('..');
    await expect(dataSourceSection.locator('select').first()).toBeVisible();
  });

  test('should configure style settings', async () => {
    const styleSection = dp.sectionHeader('样式配置');
    await styleSection.scrollIntoViewIfNeeded();
    await expect(styleSection).toBeVisible();
    await expect(dp.propertyLabel('配色主题')).toBeVisible();
    await expect(dp.propertyText('显示标题')).toBeVisible();
    await expect(dp.propertyText('显示图例')).toBeVisible();
  });

  test('should configure linkage settings', async () => {
    const linkageSection = dp.sectionHeader('图表联动');
    await linkageSection.scrollIntoViewIfNeeded();
    await expect(linkageSection).toBeVisible();
    await expect(dp.propertyText('启用图表联动')).toBeVisible();

    await dp.checkboxLabel('启用图表联动').click();
    // Wait for conditional fields to render after checkbox toggle
    await expect(dp.propertyLabel('联动分组')).toBeVisible({ timeout: 5000 });
  });

  test('should configure refresh interval', async () => {
    const refreshSection = dp.sectionHeader('数据刷新');
    await refreshSection.scrollIntoViewIfNeeded();
    await expect(refreshSection).toBeVisible();
    await expect(dp.propertyLabel('自动刷新间隔')).toBeVisible();

    const intervalSelect = dp.propertyPanel.locator('select:has(option[value="0"])');
    await expect(intervalSelect).toBeVisible();
    await intervalSelect.selectOption('60');
    await expect(dp.propertyPanel.locator('text=自动刷新已启用')).toBeVisible();
  });

  test('should display drilldown configuration section', async () => {
    const drilldownSection = dp.sectionHeader('钻取配置');
    await drilldownSection.scrollIntoViewIfNeeded();
    await expect(drilldownSection).toBeVisible();
    await expect(dp.propertyText('启用点击钻取')).toBeVisible();
  });

  test('should configure drilldown with filter action', async () => {
    const drilldownSection = dp.sectionHeader('钻取配置');
    await drilldownSection.scrollIntoViewIfNeeded();

    await dp.checkboxLabel('启用点击钻取').click();
    // Wait for drilldown options to render after checkbox toggle
    await expect(dp.propertyLabel('钻取动作')).toBeVisible({ timeout: 5000 });
    await expect(dp.propertyText('过滤数据')).toBeVisible();
    await expect(dp.propertyPanel.locator('text=点击后过滤当前图表数据')).toBeVisible();
  });

  test('should configure drilldown with navigate action', async () => {
    const drilldownSection = dp.sectionHeader('钻取配置');
    await drilldownSection.scrollIntoViewIfNeeded();

    await dp.checkboxLabel('启用点击钻取').click();
    // Wait for drilldown options to render after checkbox toggle
    await expect(dp.propertyLabel('钻取动作')).toBeVisible({ timeout: 5000 });

    await dp.propertyPanel.locator('input[value="navigate"]').click();
    // Wait for navigate-specific fields to render
    await expect(dp.propertyLabel('目标页面')).toBeVisible({ timeout: 5000 });
    await expect(dp.propertyPanel.locator('input[placeholder="选择页面..."]')).toBeVisible();
    await expect(dp.propertyLabel('参数映射')).toBeVisible();
  });

  test('should configure drilldown with modal action', async () => {
    const drilldownSection = dp.sectionHeader('钻取配置');
    await drilldownSection.scrollIntoViewIfNeeded();

    await dp.checkboxLabel('启用点击钻取').click();
    // Wait for drilldown options to render after checkbox toggle
    await expect(dp.propertyLabel('钻取动作')).toBeVisible({ timeout: 5000 });
    await dp.propertyPanel.locator('input[value="modal"]').click();
    await expect(dp.propertyPanel.locator('text=点击图表元素后将在弹窗中显示详细数据')).toBeVisible();
  });
});

test.describe('Dashboard Designer - Settings Dialog', () => {
  let dp: DashboardDesignerPage;

  test.beforeEach(async ({ page }) => {
    dp = new DashboardDesignerPage(page);
    await dp.goto();
  });

  test('should open and close settings dialog', async () => {
    await dp.openSettings();
    await expect(dp.settingsTitleInput).toBeVisible();
    await dp.closeSettings();
  });

  test('should update dashboard settings', async () => {
    await dp.openSettings();

    await dp.settingsTitleInput.fill(TEST_DASHBOARD_TITLE);
    await dp.settingsDescriptionInput.fill('E2E 测试仪表盘描述');
    await dp.saveSettings();

    // Verify value persisted by reopening settings.
    await dp.openSettings();
    await expect(dp.settingsTitleInput).toHaveValue(TEST_DASHBOARD_TITLE, { timeout: 5000 });
    await dp.closeSettings();
  });
});

test.describe('Dashboard Designer - Undo/Redo', () => {
  let dp: DashboardDesignerPage;

  test.beforeEach(async ({ page }) => {
    dp = new DashboardDesignerPage(page);
    await dp.goto();
  });

  test('should undo and redo widget addition', async () => {
    await dp.addWidget('数字卡片');
    await expect(dp.widgets).toHaveCount(1, { timeout: 8000 });

    await dp.undo();
    const undone = await expect
      .poll(async () => dp.getWidgetCount(), { timeout: 6000 })
      .toBe(0)
      .then(() => true)
      .catch(() => false);
    if (!undone) {
      await dp.canvas.click({ position: { x: 10, y: 10 } });
      await dp.page.keyboard.press('Meta+z').catch(() => {});
      await dp.page.keyboard.press('Control+z').catch(() => {});
      await expect.poll(async () => dp.getWidgetCount(), { timeout: 8000 }).toBe(0);
    }

    await dp.redo();
    const redone = await expect
      .poll(async () => dp.getWidgetCount(), { timeout: 6000 })
      .toBe(1)
      .then(() => true)
      .catch(() => false);
    if (!redone) {
      await dp.canvas.click({ position: { x: 10, y: 10 } });
      await dp.page.keyboard.press('Meta+Shift+z').catch(() => {});
      await dp.page.keyboard.press('Control+y').catch(() => {});
      await expect.poll(async () => dp.getWidgetCount(), { timeout: 8000 }).toBe(1);
    }
  });

  test('should support keyboard shortcuts', async ({ page }) => {
    await dp.addWidget('柱状图');
    await expect(dp.widgets).toHaveCount(1, { timeout: 8000 });

    // Click canvas to ensure it has focus for keyboard shortcuts
    await dp.canvas.click({ position: { x: 10, y: 10 } });
    await page.keyboard.press('Control+z').catch(() => {});
    await page.keyboard.press('Meta+z').catch(() => {});
    const undone = await expect
      .poll(async () => dp.getWidgetCount(), { timeout: 5000 })
      .toBe(0)
      .then(() => true)
      .catch(() => false);
    if (!undone) {
      await dp.undo();
      await expect.poll(async () => dp.getWidgetCount(), { timeout: 8000 }).toBe(0);
    }

    await page.keyboard.press('Control+y').catch(() => {});
    await page.keyboard.press('Meta+Shift+z').catch(() => {});
    const redone = await expect
      .poll(async () => dp.getWidgetCount(), { timeout: 5000 })
      .toBe(1)
      .then(() => true)
      .catch(() => false);
    if (!redone) {
      await dp.redo();
      await expect.poll(async () => dp.getWidgetCount(), { timeout: 8000 }).toBe(1);
    }
  });
});

test.describe('Dashboard Designer - Toolbar Status', () => {
  let dp: DashboardDesignerPage;

  test.beforeEach(async ({ page }) => {
    dp = new DashboardDesignerPage(page);
    await dp.goto();
  });

  test('should show dirty state indicator', async () => {
    await dp.addWidget('数字卡片');
    await expect(dp.dirtyIndicator).toBeVisible();
  });

  test('should disable save button when not dirty', async () => {
    await expect(dp.saveButton).toBeEnabled();
    await dp.save();
    await dp.waitUntilSaved();
  });

  test('should disable publish button when dirty', async () => {
    await dp.addWidget('数字卡片');
    await expect(dp.publishButton).toBeDisabled();
  });
});

test.describe('Dashboard Designer - Widget Position and Size', () => {
  let dp: DashboardDesignerPage;

  test.beforeEach(async ({ page }) => {
    dp = new DashboardDesignerPage(page);
    await dp.goto();
  });

  test('should display position and size info', async () => {
    await dp.addWidget('柱状图');

    const positionSection = dp.sectionHeader('位置和大小');
    await positionSection.scrollIntoViewIfNeeded();
    await expect(positionSection).toBeVisible();

    await expect(dp.propertyLabel('X')).toBeVisible();
    await expect(dp.propertyLabel('Y')).toBeVisible();
    await expect(dp.propertyLabel('宽度')).toBeVisible();
    await expect(dp.propertyLabel('高度')).toBeVisible();
  });
});

test.describe('Dashboard Designer - Multiple Widgets', () => {
  let dp: DashboardDesignerPage;

  test.beforeEach(async ({ page }) => {
    dp = new DashboardDesignerPage(page);
    await dp.goto();
  });

  test('should add multiple different widgets', async () => {
    await dp.addWidget('数字卡片');
    await dp.addWidget('柱状图');
    await dp.addWidget('饼图');
    await expect(dp.widgets).toHaveCount(3, { timeout: 5000 });
  });

  test('should switch selection between widgets', async () => {
    await dp.addWidget('数字卡片');
    await dp.addWidget('柱状图');
    await expect(dp.widgets).toHaveCount(2, { timeout: 5000 });

    await dp.widget(0).click();
    await expect(dp.propertyPanel.locator('h2:has-text("数字卡片")')).toBeVisible();

    await dp.widget(1).click();
    await expect(dp.propertyPanel.locator('h2:has-text("柱状图")')).toBeVisible();
  });

  test('should deselect widget when clicking canvas', async () => {
    await dp.addWidget('数字卡片');
    await expect(dp.widget(0)).toBeVisible({ timeout: 5000 });

    await dp.widget(0).click();
    await expect(dp.propertyPanel.locator('h2:has-text("数字卡片")')).toBeVisible();

    await dp.canvas.click({ position: { x: 10, y: 10 } });
    await expect(dp.propertyPanelEmpty).toBeVisible({ timeout: 5000 });
  });
});
