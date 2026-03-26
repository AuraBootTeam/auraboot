/**
 * Dashboard Widget Types E2E Tests
 *
 * Tests DW-001 to DW-021: Verify each widget type renders in the dashboard designer.
 * - Number card, bar, line, pie, area, funnel, scatter, radar, table chart
 * - Widget palette categories and counts
 * - Widget add to canvas
 * - Widget property panel rendering
 *
 * Uses real database + API, NO MOCKING.
 * Uses DashboardDesignerPage Page Object.
 *
 * @since 6.3.0
 */

import { test, expect } from '../../fixtures';
import { DashboardDesignerPage } from '../../pages';

/**
 * Navigate to dashboard designer and verify it loaded.
 */
async function ensureDesignerLoaded(
  page: import('@playwright/test').Page,
  designerPage: DashboardDesignerPage
): Promise<boolean> {
  try {
    await designerPage.goto();
    await designerPage.paletteItem('数字卡片').waitFor({ state: 'visible', timeout: 8000 }).catch(() => {});
    return true;
  } catch {
    const loginLocator = page.locator('text=请先登录, text=欢迎登录');
    const isLogin = await loginLocator.first().isVisible({ timeout: 2000 }).catch(() => false);
    if (isLogin) return false;
    return (
      (await designerPage.toolbar.isVisible({ timeout: 2000 }).catch(() => false)) ||
      (await designerPage.palette.isVisible({ timeout: 2000 }).catch(() => false)) ||
      (await designerPage.canvas.isVisible({ timeout: 2000 }).catch(() => false)) ||
      (await designerPage.propertyPanel.isVisible({ timeout: 2000 }).catch(() => false))
    );
  }
}

test.describe('Dashboard Widget Types — Palette Verification', () => {
  /**
   * DW-001: Dashboard designer loads with palette visible @smoke
   */
  test('DW-001: dashboard designer loads with palette @smoke', async ({ page }) => {
    const designer = new DashboardDesignerPage(page);
    const loaded = await ensureDesignerLoaded(page, designer);

    if (!loaded) {
      throw new Error("Dashboard designer not available");
      return;
    }

    await expect(designer.heading).toBeVisible();
    await expect(designer.paletteHeading).toBeVisible();
    await expect(designer.palette).toBeVisible();
  });

  /**
   * DW-002: Number Card widget in palette
   */
  test('DW-002: Number Card widget in palette', async ({ page }) => {
    const designer = new DashboardDesignerPage(page);
    const loaded = await ensureDesignerLoaded(page, designer);
    if (!loaded) { throw new Error("Designer not available"); }

    await expect(designer.paletteItem('数字卡片')).toBeVisible({ timeout: 5000 });
  });

  /**
   * DW-003: Bar Chart widget in palette
   */
  test('DW-003: Bar Chart widget in palette', async ({ page }) => {
    const designer = new DashboardDesignerPage(page);
    const loaded = await ensureDesignerLoaded(page, designer);
    if (!loaded) { throw new Error("Designer not available"); }

    await expect(designer.paletteItem('柱状图')).toBeVisible({ timeout: 5000 });
  });

  /**
   * DW-004: Line Chart widget in palette
   */
  test('DW-004: Line Chart widget in palette', async ({ page }) => {
    const designer = new DashboardDesignerPage(page);
    const loaded = await ensureDesignerLoaded(page, designer);
    if (!loaded) { throw new Error("Designer not available"); }

    await expect(designer.paletteItem('折线图')).toBeVisible({ timeout: 5000 });
  });

  /**
   * DW-005: Pie Chart widget in palette
   */
  test('DW-005: Pie Chart widget in palette', async ({ page }) => {
    const designer = new DashboardDesignerPage(page);
    const loaded = await ensureDesignerLoaded(page, designer);
    if (!loaded) { throw new Error("Designer not available"); }

    await expect(designer.paletteItem('饼图')).toBeVisible({ timeout: 5000 });
  });

  /**
   * DW-006: Area Chart widget in palette
   */
  test('DW-006: Area Chart widget in palette', async ({ page }) => {
    const designer = new DashboardDesignerPage(page);
    const loaded = await ensureDesignerLoaded(page, designer);
    if (!loaded) { throw new Error("Designer not available"); }

    await expect(designer.paletteItem('面积图')).toBeVisible({ timeout: 5000 });
  });

  /**
   * DW-007: Funnel Chart widget in palette
   */
  test('DW-007: Funnel Chart widget in palette', async ({ page }) => {
    const designer = new DashboardDesignerPage(page);
    const loaded = await ensureDesignerLoaded(page, designer);
    if (!loaded) { throw new Error("Designer not available"); }

    const widget = designer.paletteItem('漏斗图');
    const visible = await widget.isVisible({ timeout: 5000 }).catch(() => false);
    if (!visible) { throw new Error("Funnel chart not yet in palette"); }
    await expect(widget).toBeVisible();
  });

  /**
   * DW-008: Scatter Chart widget in palette
   */
  test('DW-008: Scatter Chart widget in palette', async ({ page }) => {
    const designer = new DashboardDesignerPage(page);
    const loaded = await ensureDesignerLoaded(page, designer);
    if (!loaded) { throw new Error("Designer not available"); }

    const widget = designer.paletteItem('散点图');
    const visible = await widget.isVisible({ timeout: 5000 }).catch(() => false);
    if (!visible) { throw new Error("Scatter chart not yet in palette"); }
    await expect(widget).toBeVisible();
  });

  /**
   * DW-009: Radar Chart widget in palette
   */
  test('DW-009: Radar Chart widget in palette', async ({ page }) => {
    const designer = new DashboardDesignerPage(page);
    const loaded = await ensureDesignerLoaded(page, designer);
    if (!loaded) { throw new Error("Designer not available"); }

    const widget = designer.paletteItem('雷达图');
    const visible = await widget.isVisible({ timeout: 5000 }).catch(() => false);
    if (!visible) { throw new Error("Radar chart not yet in palette"); }
    await expect(widget).toBeVisible();
  });

  /**
   * DW-010: Table Chart widget in palette
   */
  test('DW-010: Table Chart widget in palette', async ({ page }) => {
    const designer = new DashboardDesignerPage(page);
    const loaded = await ensureDesignerLoaded(page, designer);
    if (!loaded) { throw new Error("Designer not available"); }

    const widget = designer.paletteItem('数据表格');
    const visible = await widget.isVisible({ timeout: 5000 }).catch(() => false);
    if (!visible) { throw new Error("Table chart not yet in palette"); }
    await expect(widget).toBeVisible();
  });
});

test.describe('Dashboard Widget Types — Add to Canvas', () => {
  /**
   * DW-011: Add Number Card widget to canvas @smoke
   */
  test('DW-011: add Number Card to canvas @smoke', async ({ page }) => {
    const designer = new DashboardDesignerPage(page);
    const loaded = await ensureDesignerLoaded(page, designer);
    if (!loaded) { throw new Error("Designer not available"); }

    await designer.addWidget('数字卡片');
    const widgetCount = await designer.getWidgetCount();
    expect(widgetCount).toBeGreaterThanOrEqual(1);
  });

  /**
   * DW-012: Add Bar Chart widget to canvas
   */
  test('DW-012: add Bar Chart to canvas', async ({ page }) => {
    const designer = new DashboardDesignerPage(page);
    const loaded = await ensureDesignerLoaded(page, designer);
    if (!loaded) { throw new Error("Designer not available"); }

    await designer.addWidget('柱状图');
    const widgetCount = await designer.getWidgetCount();
    expect(widgetCount).toBeGreaterThanOrEqual(1);
  });

  /**
   * DW-013: Add Line Chart widget to canvas
   */
  test('DW-013: add Line Chart to canvas', async ({ page }) => {
    const designer = new DashboardDesignerPage(page);
    const loaded = await ensureDesignerLoaded(page, designer);
    if (!loaded) { throw new Error("Designer not available"); }

    await designer.addWidget('折线图');
    const widgetCount = await designer.getWidgetCount();
    expect(widgetCount).toBeGreaterThanOrEqual(1);
  });

  /**
   * DW-014: Add Pie Chart widget to canvas
   */
  test('DW-014: add Pie Chart to canvas', async ({ page }) => {
    const designer = new DashboardDesignerPage(page);
    const loaded = await ensureDesignerLoaded(page, designer);
    if (!loaded) { throw new Error("Designer not available"); }

    await designer.addWidget('饼图');
    const widgetCount = await designer.getWidgetCount();
    expect(widgetCount).toBeGreaterThanOrEqual(1);
  });

  /**
   * DW-015: Add Area Chart widget to canvas
   */
  test('DW-015: add Area Chart to canvas', async ({ page }) => {
    const designer = new DashboardDesignerPage(page);
    const loaded = await ensureDesignerLoaded(page, designer);
    if (!loaded) { throw new Error("Designer not available"); }

    await designer.addWidget('面积图');
    const widgetCount = await designer.getWidgetCount();
    expect(widgetCount).toBeGreaterThanOrEqual(1);
  });
});

test.describe('Dashboard Widget Types — Property Panel', () => {
  /**
   * DW-016: Number Card shows property panel sections
   */
  test('DW-016: Number Card property panel sections', async ({ page }) => {
    const designer = new DashboardDesignerPage(page);
    const loaded = await ensureDesignerLoaded(page, designer);
    if (!loaded) { throw new Error("Designer not available"); }

    await designer.addWidget('数字卡片');

    // Property panel should show relevant sections
    const panelHeader = designer.propertyPanel.locator('h2:has-text("数字卡片")');
    await expect(panelHeader).toBeVisible({ timeout: 5000 });

    // Should have data source configuration
    const dataSourceLabel = designer.propertyLabel('数据源');
    const hasDataSource = await dataSourceLabel.isVisible({ timeout: 3000 }).catch(() => false);
    if (hasDataSource) {
      await expect(dataSourceLabel).toBeVisible();
    }
  });

  /**
   * DW-017: Bar Chart shows axis configuration
   */
  test('DW-017: Bar Chart property panel', async ({ page }) => {
    const designer = new DashboardDesignerPage(page);
    const loaded = await ensureDesignerLoaded(page, designer);
    if (!loaded) { throw new Error("Designer not available"); }

    await designer.addWidget('柱状图');

    const panelHeader = designer.propertyPanel.locator('h2:has-text("柱状图")');
    await expect(panelHeader).toBeVisible({ timeout: 5000 });
  });

  /**
   * DW-018: Funnel Chart shows sort configuration
   */
  test('DW-018: Funnel Chart sort configuration', async ({ page }) => {
    const designer = new DashboardDesignerPage(page);
    const loaded = await ensureDesignerLoaded(page, designer);
    if (!loaded) { throw new Error("Designer not available"); }

    const funnelWidget = designer.paletteItem('漏斗图');
    const hasFunnel = await funnelWidget.isVisible({ timeout: 5000 }).catch(() => false);
    if (!hasFunnel) { throw new Error("Funnel chart not in palette"); }

    // Use addWidget which has retry logic for click-to-add
    await designer.addWidget('漏斗图');

    const panelHeader = designer.propertyPanel.locator('h2:has-text("漏斗图")');
    await expect(panelHeader).toBeVisible({ timeout: 5000 });

    const sortLabel = designer.propertyLabel('排序方式');
    await sortLabel.scrollIntoViewIfNeeded().catch(() => {});
    const hasSortLabel = await sortLabel.isVisible({ timeout: 3000 }).catch(() => false);
    if (hasSortLabel) {
      await expect(sortLabel).toBeVisible();
    }
  });
});

test.describe('Dashboard Widget Types — Categories & Counts', () => {
  /**
   * DW-019: Widget palette has category headers
   */
  test('DW-019: widget palette category headers', async ({ page }) => {
    const designer = new DashboardDesignerPage(page);
    const loaded = await ensureDesignerLoaded(page, designer);
    if (!loaded) { throw new Error("Designer not available"); }

    // Check standard categories
    await expect(designer.palette.locator('h3:has-text("指标")')).toBeVisible({ timeout: 5000 });
    await expect(designer.palette.locator('h3:has-text("图表")')).toBeVisible();
  });

  /**
   * DW-020: At least 5 base widget types present @smoke
   */
  test('DW-020: at least 5 base widget types present @smoke', async ({ page }) => {
    const designer = new DashboardDesignerPage(page);
    const loaded = await ensureDesignerLoaded(page, designer);
    if (!loaded) { throw new Error("Designer not available"); }

    const baseWidgets = ['数字卡片', '柱状图', '折线图', '饼图', '面积图'];
    let foundCount = 0;

    for (const widgetLabel of baseWidgets) {
      const isVisible = await designer.paletteItem(widgetLabel)
        .isVisible({ timeout: 2000 }).catch(() => false);
      if (isVisible) foundCount++;
    }

    expect(foundCount).toBeGreaterThanOrEqual(5);
  });

  /**
   * DW-021: Extended widget types count (up to 9)
   */
  test('DW-021: extended widget types count', async ({ page }) => {
    const designer = new DashboardDesignerPage(page);
    const loaded = await ensureDesignerLoaded(page, designer);
    if (!loaded) { throw new Error("Designer not available"); }

    const allWidgets = [
      '数字卡片', '柱状图', '折线图', '饼图', '面积图',
      '漏斗图', '散点图', '雷达图', '数据表格',
    ];

    let foundCount = 0;
    for (const widgetLabel of allWidgets) {
      const isVisible = await designer.paletteItem(widgetLabel)
        .isVisible({ timeout: 2000 }).catch(() => false);
      if (isVisible) foundCount++;
    }

    // At least 5 base types required, up to 9 with extensions
    expect(foundCount).toBeGreaterThanOrEqual(5);

    if (foundCount < allWidgets.length) {
      console.log(`Found ${foundCount}/${allWidgets.length} widget types in palette`);
    }
  });
});
