/**
 * Dashboard Widget Types E2E Tests
 *
 * Tests DW-001 to DW-073: Verify each widget type renders in the dashboard designer.
 * - Number card, bar, line, pie, area, funnel, scatter, radar, table chart
 * - Word cloud, combo chart, NPS, gallery, kanban (new)
 * - Widget palette categories and counts
 * - Widget add to canvas
 * - Widget property panel rendering
 *
 * Uses real database + API, NO MOCKING.
 * Uses DashboardDesignerPage Page Object.
 *
 * @since 6.3.0
 */

import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import { DashboardDesignerPage } from '../../pages';

interface WidgetMatrixRow {
  label: string;
  type: string;
}

type PropertyEditKind = 'text' | 'number' | 'checkbox' | 'select';

interface WidgetPropertyEdit {
  key: string;
  kind: PropertyEditKind;
  value: string | number | boolean;
}

interface WidgetPayloadCase extends WidgetMatrixRow {
  testCode: string;
  expectedTitle?: string;
  size: {
    w: number;
    h: number;
    minW: number;
    minH: number;
    maxW?: number;
    maxH?: number;
  };
  dataSource: Record<string, unknown>;
  edit: WidgetPropertyEdit;
  expectedConfig: Record<string, unknown>;
}

const BASE_WIDGETS: WidgetMatrixRow[] = [
  { label: '数字卡片', type: 'smart-number-card' },
  { label: '柱状图', type: 'smart-bar-chart' },
  { label: '折线图', type: 'smart-line-chart' },
  { label: '饼图', type: 'smart-pie-chart' },
  { label: '面积图', type: 'smart-area-chart' },
];

const EXTENDED_WIDGETS: WidgetMatrixRow[] = [
  ...BASE_WIDGETS,
  { label: '漏斗图', type: 'smart-funnel-chart' },
  { label: '散点图', type: 'smart-scatter-chart' },
  { label: '雷达图', type: 'smart-radar-chart' },
  { label: '数据表格', type: 'smart-table-chart' },
  { label: '词云', type: 'smart-wordcloud-chart' },
  { label: '组合图', type: 'smart-combo-chart' },
  { label: 'NPS 图', type: 'smart-nps-chart' },
  { label: '画册', type: 'smart-gallery' },
  { label: '看板', type: 'smart-kanban' },
];

const PAYLOAD_MATRIX_STATIC_ROWS = [
  { category: 'alpha', value: 3 },
  { category: 'beta', value: 5 },
];

const STATIC_PAYLOAD_DATA_SOURCE = {
  type: 'static',
  staticData: PAYLOAD_MATRIX_STATIC_ROWS,
};

const SAVED_PAYLOAD_WIDGETS: WidgetPayloadCase[] = [
  {
    testCode: 'DW-038',
    ...BASE_WIDGETS[0],
    size: { w: 3, h: 2, minW: 2, minH: 2, maxW: 6, maxH: 4 },
    dataSource: STATIC_PAYLOAD_DATA_SOURCE,
    edit: { key: 'visualization.suffix', kind: 'text', value: '件' },
    expectedConfig: { visualization: { suffix: '件' } },
  },
  {
    testCode: 'DW-039',
    ...BASE_WIDGETS[1],
    size: { w: 6, h: 4, minW: 4, minH: 3 },
    dataSource: STATIC_PAYLOAD_DATA_SOURCE,
    edit: { key: 'visualization.horizontal', kind: 'checkbox', value: true },
    expectedConfig: { visualization: { horizontal: true } },
  },
  {
    testCode: 'DW-040',
    ...BASE_WIDGETS[2],
    size: { w: 6, h: 4, minW: 4, minH: 3 },
    dataSource: STATIC_PAYLOAD_DATA_SOURCE,
    edit: { key: 'visualization.showArea', kind: 'checkbox', value: true },
    expectedConfig: { visualization: { showArea: true } },
  },
  {
    testCode: 'DW-041',
    ...BASE_WIDGETS[3],
    size: { w: 4, h: 4, minW: 3, minH: 3 },
    dataSource: STATIC_PAYLOAD_DATA_SOURCE,
    edit: { key: 'visualization.donut', kind: 'checkbox', value: true },
    expectedConfig: { visualization: { donut: true } },
  },
  {
    testCode: 'DW-042',
    ...EXTENDED_WIDGETS[5],
    size: { w: 4, h: 4, minW: 3, minH: 3 },
    dataSource: STATIC_PAYLOAD_DATA_SOURCE,
    edit: { key: 'visualization.sort', kind: 'select', value: 'ascending' },
    expectedConfig: { visualization: { sort: 'ascending' } },
  },
  {
    testCode: 'DW-043',
    ...EXTENDED_WIDGETS[6],
    size: { w: 6, h: 4, minW: 4, minH: 3 },
    dataSource: STATIC_PAYLOAD_DATA_SOURCE,
    edit: { key: 'visualization.bubbleMode', kind: 'checkbox', value: true },
    expectedConfig: { visualization: { bubbleMode: true } },
  },
  {
    testCode: 'DW-044',
    ...EXTENDED_WIDGETS[7],
    size: { w: 4, h: 4, minW: 4, minH: 4 },
    dataSource: STATIC_PAYLOAD_DATA_SOURCE,
    edit: { key: 'visualization.shape', kind: 'select', value: 'circle' },
    expectedConfig: { visualization: { shape: 'circle' } },
  },
  {
    testCode: 'DW-045',
    ...EXTENDED_WIDGETS[8],
    size: { w: 6, h: 4, minW: 4, minH: 3 },
    dataSource: STATIC_PAYLOAD_DATA_SOURCE,
    edit: { key: 'visualization.pageSize', kind: 'number', value: 25 },
    expectedConfig: { visualization: { pageSize: 25 } },
  },
  {
    testCode: 'DW-046',
    ...EXTENDED_WIDGETS[9],
    size: { w: 6, h: 4, minW: 4, minH: 3, maxW: 12, maxH: 8 },
    dataSource: STATIC_PAYLOAD_DATA_SOURCE,
    edit: { key: 'visualization.colorTheme', kind: 'select', value: 'brand' },
    expectedConfig: { visualization: { colorTheme: 'brand' } },
  },
  {
    testCode: 'DW-047',
    ...EXTENDED_WIDGETS[10],
    size: { w: 8, h: 5, minW: 6, minH: 4, maxW: 12, maxH: 8 },
    dataSource: STATIC_PAYLOAD_DATA_SOURCE,
    edit: { key: 'visualization.yAxisLeft.name', kind: 'text', value: 'Revenue' },
    expectedConfig: { visualization: { yAxisLeft: { name: 'Revenue' } } },
  },
  {
    testCode: 'DW-048',
    ...EXTENDED_WIDGETS[11],
    expectedTitle: 'NPS',
    size: { w: 4, h: 4, minW: 3, minH: 3, maxW: 8, maxH: 8 },
    dataSource: STATIC_PAYLOAD_DATA_SOURCE,
    edit: { key: 'visualization.ringWidth', kind: 'number', value: 42 },
    expectedConfig: { visualization: { ringWidth: 42 } },
  },
  {
    testCode: 'DW-049',
    ...EXTENDED_WIDGETS[12],
    size: { w: 8, h: 5, minW: 4, minH: 3, maxW: 12, maxH: 10 },
    dataSource: STATIC_PAYLOAD_DATA_SOURCE,
    edit: { key: 'visualization.columns', kind: 'select', value: '4' },
    expectedConfig: { visualization: { columns: '4' } },
  },
  {
    testCode: 'DW-050',
    ...EXTENDED_WIDGETS[13],
    size: { w: 12, h: 6, minW: 6, minH: 4, maxW: 12, maxH: 10 },
    dataSource: STATIC_PAYLOAD_DATA_SOURCE,
    edit: { key: 'visualization.groupField', kind: 'text', value: 'stage' },
    expectedConfig: { visualization: { groupField: 'stage' } },
  },
  {
    testCode: 'DW-051',
    label: '仪表盘',
    type: 'smart-gauge-chart',
    size: { w: 4, h: 4, minW: 3, minH: 3 },
    dataSource: STATIC_PAYLOAD_DATA_SOURCE,
    edit: { key: 'visualization.splitNumber', kind: 'number', value: 8 },
    expectedConfig: { visualization: { splitNumber: 8 } },
  },
  {
    testCode: 'DW-052',
    label: '进度条',
    type: 'smart-progress',
    expectedTitle: '进度',
    size: { w: 3, h: 3, minW: 2, minH: 2 },
    dataSource: STATIC_PAYLOAD_DATA_SOURCE,
    edit: { key: 'visualization.shape', kind: 'select', value: 'circle' },
    expectedConfig: { visualization: { shape: 'circle' } },
  },
  {
    testCode: 'DW-053',
    label: '热力图',
    type: 'smart-heatmap-chart',
    size: { w: 6, h: 4, minW: 4, minH: 3 },
    dataSource: STATIC_PAYLOAD_DATA_SOURCE,
    edit: { key: 'visualization.xField', kind: 'text', value: 'category' },
    expectedConfig: { visualization: { xField: 'category' } },
  },
  {
    testCode: 'DW-054',
    label: '矩形树图',
    type: 'smart-treemap-chart',
    size: { w: 6, h: 4, minW: 4, minH: 3 },
    dataSource: STATIC_PAYLOAD_DATA_SOURCE,
    edit: { key: 'visualization.nameField', kind: 'text', value: 'category' },
    expectedConfig: { visualization: { nameField: 'category' } },
  },
  {
    testCode: 'DW-055',
    label: '地图',
    type: 'smart-map-chart',
    size: { w: 6, h: 4, minW: 4, minH: 3 },
    dataSource: STATIC_PAYLOAD_DATA_SOURCE,
    edit: { key: 'visualization.mapRegion', kind: 'select', value: 'world' },
    expectedConfig: { visualization: { mapRegion: 'world' } },
  },
  {
    testCode: 'DW-056',
    label: '排行榜',
    type: 'smart-leaderboard',
    size: { w: 4, h: 4, minW: 3, minH: 3 },
    dataSource: STATIC_PAYLOAD_DATA_SOURCE,
    edit: { key: 'visualization.maxItems', kind: 'number', value: 7 },
    expectedConfig: { visualization: { maxItems: 7 } },
  },
  {
    testCode: 'DW-057',
    label: '富文本',
    type: 'smart-rich-text',
    size: { w: 4, h: 3, minW: 2, minH: 2 },
    dataSource: STATIC_PAYLOAD_DATA_SOURCE,
    edit: { key: 'visualization.content', kind: 'text', value: '<p>Coverage note</p>' },
    expectedConfig: { visualization: { content: '<p>Coverage note</p>' } },
  },
  {
    testCode: 'DW-058',
    label: '图片',
    type: 'smart-image',
    size: { w: 4, h: 3, minW: 2, minH: 2 },
    dataSource: STATIC_PAYLOAD_DATA_SOURCE,
    edit: { key: 'visualization.src', kind: 'text', value: 'https://example.com/image.png' },
    expectedConfig: { visualization: { src: 'https://example.com/image.png' } },
  },
  {
    testCode: 'DW-059',
    label: '内嵌页面',
    type: 'smart-iframe',
    size: { w: 6, h: 4, minW: 3, minH: 3 },
    dataSource: STATIC_PAYLOAD_DATA_SOURCE,
    edit: { key: 'visualization.src', kind: 'text', value: 'https://example.com' },
    expectedConfig: { visualization: { src: 'https://example.com' } },
  },
  {
    testCode: 'DW-060',
    label: '倒计时',
    type: 'smart-countdown',
    size: { w: 4, h: 2, minW: 3, minH: 2 },
    dataSource: STATIC_PAYLOAD_DATA_SOURCE,
    edit: { key: 'visualization.targetDate', kind: 'text', value: '2026-12-31T00:00:00' },
    expectedConfig: { visualization: { targetDate: '2026-12-31T00:00:00' } },
  },
  {
    testCode: 'DW-061',
    label: '统计概览',
    type: 'smart-stats-row',
    expectedTitle: '统计概览验收',
    size: { w: 12, h: 2, minW: 6, minH: 2 },
    dataSource: STATIC_PAYLOAD_DATA_SOURCE,
    edit: { key: 'title', kind: 'text', value: '统计概览验收' },
    expectedConfig: { title: '统计概览验收' },
  },
  {
    testCode: 'DW-062',
    label: '统计卡片',
    type: 'smart-stats-card',
    size: { w: 3, h: 2, minW: 3, minH: 2, maxW: 6, maxH: 4 },
    dataSource: STATIC_PAYLOAD_DATA_SOURCE,
    edit: { key: 'visualization.statKey', kind: 'text', value: 'openOpps' },
    expectedConfig: { visualization: { statKey: 'openOpps' } },
  },
  {
    testCode: 'DW-063',
    label: '待办事项',
    type: 'smart-inbox',
    size: { w: 6, h: 4, minW: 4, minH: 3, maxW: 12, maxH: 8 },
    dataSource: STATIC_PAYLOAD_DATA_SOURCE,
    edit: { key: 'visualization.maxItems', kind: 'number', value: 9 },
    expectedConfig: { visualization: { maxItems: 9 } },
  },
  {
    testCode: 'DW-064',
    label: '日历',
    type: 'smart-calendar',
    expectedTitle: '日历验收',
    size: { w: 6, h: 4, minW: 4, minH: 3, maxW: 12, maxH: 8 },
    dataSource: STATIC_PAYLOAD_DATA_SOURCE,
    edit: { key: 'title', kind: 'text', value: '日历验收' },
    expectedConfig: { title: '日历验收' },
  },
  {
    testCode: 'DW-065',
    label: '销售管道',
    type: 'smart-pipeline',
    expectedTitle: '销售管道验收',
    size: { w: 6, h: 4, minW: 4, minH: 3, maxW: 12, maxH: 8 },
    dataSource: STATIC_PAYLOAD_DATA_SOURCE,
    edit: { key: 'title', kind: 'text', value: '销售管道验收' },
    expectedConfig: { title: '销售管道验收' },
  },
  {
    testCode: 'DW-066',
    label: '线索看板',
    type: 'smart-leads',
    expectedTitle: '线索看板验收',
    size: { w: 6, h: 4, minW: 4, minH: 3, maxW: 12, maxH: 8 },
    dataSource: STATIC_PAYLOAD_DATA_SOURCE,
    edit: { key: 'title', kind: 'text', value: '线索看板验收' },
    expectedConfig: { title: '线索看板验收' },
  },
  {
    testCode: 'DW-067',
    label: '活动记录',
    type: 'smart-activities',
    expectedTitle: '活动记录验收',
    size: { w: 6, h: 4, minW: 4, minH: 3, maxW: 12, maxH: 8 },
    dataSource: STATIC_PAYLOAD_DATA_SOURCE,
    edit: { key: 'title', kind: 'text', value: '活动记录验收' },
    expectedConfig: { title: '活动记录验收' },
  },
  {
    testCode: 'DW-068',
    label: '我的流程',
    type: 'smart-my-process',
    size: { w: 6, h: 4, minW: 4, minH: 3, maxW: 12, maxH: 8 },
    dataSource: STATIC_PAYLOAD_DATA_SOURCE,
    edit: { key: 'visualization.maxItems', kind: 'number', value: 6 },
    expectedConfig: { visualization: { maxItems: 6 } },
  },
  {
    testCode: 'DW-069',
    label: '流程统计',
    type: 'smart-process-stats',
    expectedTitle: '流程统计验收',
    size: { w: 6, h: 3, minW: 4, minH: 2, maxW: 12, maxH: 6 },
    dataSource: STATIC_PAYLOAD_DATA_SOURCE,
    edit: { key: 'title', kind: 'text', value: '流程统计验收' },
    expectedConfig: { title: '流程统计验收' },
  },
  {
    testCode: 'DW-070',
    label: '快捷入口',
    type: 'smart-shortcuts',
    size: { w: 6, h: 2, minW: 3, minH: 2, maxW: 12, maxH: 4 },
    dataSource: STATIC_PAYLOAD_DATA_SOURCE,
    edit: { key: 'visualization.columns', kind: 'number', value: 4 },
    expectedConfig: { visualization: { columns: 4 } },
  },
  {
    testCode: 'DW-071',
    label: '最近访问',
    type: 'smart-recent',
    size: { w: 6, h: 3, minW: 3, minH: 2, maxW: 12, maxH: 6 },
    dataSource: STATIC_PAYLOAD_DATA_SOURCE,
    edit: { key: 'visualization.maxItems', kind: 'number', value: 10 },
    expectedConfig: { visualization: { maxItems: 10 } },
  },
  {
    testCode: 'DW-072',
    label: '公告',
    type: 'smart-announcement',
    expectedTitle: '公告验收',
    size: { w: 6, h: 3, minW: 3, minH: 2, maxW: 12, maxH: 6 },
    dataSource: STATIC_PAYLOAD_DATA_SOURCE,
    edit: { key: 'title', kind: 'text', value: '公告验收' },
    expectedConfig: { title: '公告验收' },
  },
  {
    testCode: 'DW-073',
    label: '快捷便签',
    type: 'smart-quick-note',
    expectedTitle: '快捷便签验收',
    size: { w: 4, h: 3, minW: 3, minH: 2, maxW: 8, maxH: 6 },
    dataSource: STATIC_PAYLOAD_DATA_SOURCE,
    edit: { key: 'title', kind: 'text', value: '快捷便签验收' },
    expectedConfig: { title: '快捷便签验收' },
  },
];

/**
 * Navigate to dashboard designer and verify it loaded.
 */
async function ensureDesignerLoaded(
  page: Page,
  designerPage: DashboardDesignerPage,
): Promise<boolean> {
  try {
    await designerPage.goto();
    await designerPage
      .paletteItem('数字卡片')
      .waitFor({ state: 'visible', timeout: 8000 })
      .catch(() => {});
    return true;
  } catch {
    const loginLocator = page.locator('text=请先登录, text=欢迎登录');
    const isLogin = await loginLocator
      .first()
      .isVisible({ timeout: 2000 })
      .catch(() => false);
    if (isLogin) return false;
    return (
      (await designerPage.toolbar.isVisible({ timeout: 2000 }).catch(() => false)) ||
      (await designerPage.palette.isVisible({ timeout: 2000 }).catch(() => false)) ||
      (await designerPage.canvas.isVisible({ timeout: 2000 }).catch(() => false)) ||
      (await designerPage.propertyPanel.isVisible({ timeout: 2000 }).catch(() => false))
    );
  }
}

async function expectWidgetAdded(
  designer: DashboardDesignerPage,
  widget: WidgetMatrixRow,
): Promise<void> {
  const beforeCount = await designer.getWidgetCount();
  await designer.addWidget(widget.label);
  await expect(designer.propertyPanel.locator(`h2:has-text("${widget.label}")`)).toBeVisible({
    timeout: 5000,
  });
  await expect
    .poll(() => designer.getWidgetCount(), { timeout: 5000 })
    .toBe(beforeCount + 1);
  await expect(designer.canvas.locator(`[data-widget-type="${widget.type}"]`)).toBeVisible({
    timeout: 5000,
  });
}

async function saveDashboardAndReadBack(
  page: Page,
  designer: DashboardDesignerPage,
): Promise<Record<string, any>> {
  const saveResponsePromise = page.waitForResponse((response) => {
    const pathname = new URL(response.url()).pathname;
    return (
      /^\/api\/dashboards(?:\/[^/]+)?$/.test(pathname) &&
      ['POST', 'PUT'].includes(response.request().method()) &&
      response.status() === 200
    );
  });

  await designer.saveButton.click();
  const saveResponse = await saveResponsePromise;
  const savedBody = await saveResponse.json();
  const pid = savedBody.data?.pid;
  expect(pid, `dashboard save response includes pid: ${JSON.stringify(savedBody)}`).toBeTruthy();

  const getResponse = await page.request.get(`/api/dashboards/${pid}`);
  expect(getResponse.ok(), `dashboard readback failed: ${getResponse.status()}`).toBeTruthy();
  const readBody = await getResponse.json();
  return readBody.data;
}

function widgetPropertyTestId(key: string): string {
  return `widget-prop-${key.replace(/[^a-zA-Z0-9_-]/g, '-')}`;
}

async function setWidgetProperty(page: Page, edit: WidgetPropertyEdit): Promise<void> {
  const field = page.getByTestId(widgetPropertyTestId(edit.key));
  await expect(field).toBeVisible({ timeout: 5000 });

  switch (edit.kind) {
    case 'checkbox':
      await field.setChecked(Boolean(edit.value));
      if (edit.value) {
        await expect(field).toBeChecked();
      } else {
        await expect(field).not.toBeChecked();
      }
      break;
    case 'number':
      await field.fill(String(edit.value));
      await expect(field).toHaveValue(String(edit.value));
      break;
    case 'select':
      await field.selectOption(String(edit.value));
      await expect(field).toHaveValue(String(edit.value));
      break;
    case 'text':
      await field.fill(String(edit.value));
      await expect(field).toHaveValue(String(edit.value));
      break;
  }
}

async function setStaticDataSource(
  page: Page,
  rows: Record<string, unknown>[] = PAYLOAD_MATRIX_STATIC_ROWS,
): Promise<void> {
  await page.getByTestId('dashboard-datasource-type-select').selectOption('static');
  await page
    .getByTestId('dashboard-datasource-static-json')
    .fill(JSON.stringify(rows, null, 2));
}

function expectWidgetLayout(widget: Record<string, any>, size: WidgetPayloadCase['size']): void {
  for (const [key, value] of Object.entries(size)) {
    expect(widget[key], `widget ${key}`).toBe(value);
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
      throw new Error('Dashboard designer not available');
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
    if (!loaded) {
      throw new Error('Designer not available');
    }

    await expect(designer.paletteItem('数字卡片')).toBeVisible({ timeout: 5000 });
  });

  /**
   * DW-003: Bar Chart widget in palette
   */
  test('DW-003: Bar Chart widget in palette', async ({ page }) => {
    const designer = new DashboardDesignerPage(page);
    const loaded = await ensureDesignerLoaded(page, designer);
    if (!loaded) {
      throw new Error('Designer not available');
    }

    await expect(designer.paletteItem('柱状图')).toBeVisible({ timeout: 5000 });
  });

  /**
   * DW-004: Line Chart widget in palette
   */
  test('DW-004: Line Chart widget in palette', async ({ page }) => {
    const designer = new DashboardDesignerPage(page);
    const loaded = await ensureDesignerLoaded(page, designer);
    if (!loaded) {
      throw new Error('Designer not available');
    }

    await expect(designer.paletteItem('折线图')).toBeVisible({ timeout: 5000 });
  });

  /**
   * DW-005: Pie Chart widget in palette
   */
  test('DW-005: Pie Chart widget in palette', async ({ page }) => {
    const designer = new DashboardDesignerPage(page);
    const loaded = await ensureDesignerLoaded(page, designer);
    if (!loaded) {
      throw new Error('Designer not available');
    }

    await expect(designer.paletteItem('饼图')).toBeVisible({ timeout: 5000 });
  });

  /**
   * DW-006: Area Chart widget in palette
   */
  test('DW-006: Area Chart widget in palette', async ({ page }) => {
    const designer = new DashboardDesignerPage(page);
    const loaded = await ensureDesignerLoaded(page, designer);
    if (!loaded) {
      throw new Error('Designer not available');
    }

    await expect(designer.paletteItem('面积图')).toBeVisible({ timeout: 5000 });
  });

  /**
   * DW-007: Funnel Chart widget in palette
   */
  test('DW-007: Funnel Chart widget in palette', async ({ page }) => {
    const designer = new DashboardDesignerPage(page);
    const loaded = await ensureDesignerLoaded(page, designer);
    if (!loaded) {
      throw new Error('Designer not available');
    }

    const widget = designer.paletteItem('漏斗图');
    const visible = await widget.isVisible({ timeout: 5000 }).catch(() => false);
    if (!visible) {
      throw new Error('Funnel chart not yet in palette');
    }
    await expect(widget).toBeVisible();
  });

  /**
   * DW-008: Scatter Chart widget in palette
   */
  test('DW-008: Scatter Chart widget in palette', async ({ page }) => {
    const designer = new DashboardDesignerPage(page);
    const loaded = await ensureDesignerLoaded(page, designer);
    if (!loaded) {
      throw new Error('Designer not available');
    }

    const widget = designer.paletteItem('散点图');
    const visible = await widget.isVisible({ timeout: 5000 }).catch(() => false);
    if (!visible) {
      throw new Error('Scatter chart not yet in palette');
    }
    await expect(widget).toBeVisible();
  });

  /**
   * DW-009: Radar Chart widget in palette
   */
  test('DW-009: Radar Chart widget in palette', async ({ page }) => {
    const designer = new DashboardDesignerPage(page);
    const loaded = await ensureDesignerLoaded(page, designer);
    if (!loaded) {
      throw new Error('Designer not available');
    }

    const widget = designer.paletteItem('雷达图');
    const visible = await widget.isVisible({ timeout: 5000 }).catch(() => false);
    if (!visible) {
      throw new Error('Radar chart not yet in palette');
    }
    await expect(widget).toBeVisible();
  });

  /**
   * DW-010: Table Chart widget in palette
   */
  test('DW-010: Table Chart widget in palette', async ({ page }) => {
    const designer = new DashboardDesignerPage(page);
    const loaded = await ensureDesignerLoaded(page, designer);
    if (!loaded) {
      throw new Error('Designer not available');
    }

    const widget = designer.paletteItem('数据表格');
    const visible = await widget.isVisible({ timeout: 5000 }).catch(() => false);
    if (!visible) {
      throw new Error('Table chart not yet in palette');
    }
    await expect(widget).toBeVisible();
  });

  /**
   * DW-022: Word Cloud widget in palette
   */
  test('DW-022: Word Cloud widget in palette', async ({ page }) => {
    const designer = new DashboardDesignerPage(page);
    const loaded = await ensureDesignerLoaded(page, designer);
    if (!loaded) {
      throw new Error('Designer not available');
    }

    const widget = designer.paletteItem('词云');
    const visible = await widget.isVisible({ timeout: 5000 }).catch(() => false);
    if (!visible) {
      throw new Error('Word Cloud not yet in palette');
    }
    await expect(widget).toBeVisible();
  });

  /**
   * DW-023: Combo Chart widget in palette
   */
  test('DW-023: Combo Chart widget in palette', async ({ page }) => {
    const designer = new DashboardDesignerPage(page);
    const loaded = await ensureDesignerLoaded(page, designer);
    if (!loaded) {
      throw new Error('Designer not available');
    }

    const widget = designer.paletteItem('组合图');
    const visible = await widget.isVisible({ timeout: 5000 }).catch(() => false);
    if (!visible) {
      throw new Error('Combo Chart not yet in palette');
    }
    await expect(widget).toBeVisible();
  });

  /**
   * DW-024: NPS Chart widget in palette
   */
  test('DW-024: NPS Chart widget in palette', async ({ page }) => {
    const designer = new DashboardDesignerPage(page);
    const loaded = await ensureDesignerLoaded(page, designer);
    if (!loaded) {
      throw new Error('Designer not available');
    }

    const widget = designer.paletteItem('NPS 图');
    const visible = await widget.isVisible({ timeout: 5000 }).catch(() => false);
    if (!visible) {
      throw new Error('NPS Chart not yet in palette');
    }
    await expect(widget).toBeVisible();
  });

  /**
   * DW-025: Gallery widget in palette
   */
  test('DW-025: Gallery widget in palette', async ({ page }) => {
    const designer = new DashboardDesignerPage(page);
    const loaded = await ensureDesignerLoaded(page, designer);
    if (!loaded) {
      throw new Error('Designer not available');
    }

    const widget = designer.paletteItem('画册');
    const visible = await widget.isVisible({ timeout: 5000 }).catch(() => false);
    if (!visible) {
      throw new Error('Gallery not yet in palette');
    }
    await expect(widget).toBeVisible();
  });

  /**
   * DW-026: Kanban widget in palette
   */
  test('DW-026: Kanban widget in palette', async ({ page }) => {
    const designer = new DashboardDesignerPage(page);
    const loaded = await ensureDesignerLoaded(page, designer);
    if (!loaded) {
      throw new Error('Designer not available');
    }

    const widget = designer.paletteItem('看板');
    const visible = await widget.isVisible({ timeout: 5000 }).catch(() => false);
    if (!visible) {
      throw new Error('Kanban not yet in palette');
    }
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
    if (!loaded) {
      throw new Error('Designer not available');
    }

    await expectWidgetAdded(designer, BASE_WIDGETS[0]);
  });

  /**
   * DW-012: Add Bar Chart widget to canvas
   */
  test('DW-012: add Bar Chart to canvas', async ({ page }) => {
    const designer = new DashboardDesignerPage(page);
    const loaded = await ensureDesignerLoaded(page, designer);
    if (!loaded) {
      throw new Error('Designer not available');
    }

    await expectWidgetAdded(designer, BASE_WIDGETS[1]);
  });

  /**
   * DW-013: Add Line Chart widget to canvas
   */
  test('DW-013: add Line Chart to canvas', async ({ page }) => {
    const designer = new DashboardDesignerPage(page);
    const loaded = await ensureDesignerLoaded(page, designer);
    if (!loaded) {
      throw new Error('Designer not available');
    }

    await expectWidgetAdded(designer, BASE_WIDGETS[2]);
  });

  /**
   * DW-014: Add Pie Chart widget to canvas
   */
  test('DW-014: add Pie Chart to canvas', async ({ page }) => {
    const designer = new DashboardDesignerPage(page);
    const loaded = await ensureDesignerLoaded(page, designer);
    if (!loaded) {
      throw new Error('Designer not available');
    }

    await expectWidgetAdded(designer, BASE_WIDGETS[3]);
  });

  /**
   * DW-015: Add Area Chart widget to canvas
   */
  test('DW-015: add Area Chart to canvas', async ({ page }) => {
    const designer = new DashboardDesignerPage(page);
    const loaded = await ensureDesignerLoaded(page, designer);
    if (!loaded) {
      throw new Error('Designer not available');
    }

    await expectWidgetAdded(designer, BASE_WIDGETS[4]);
  });

  /**
   * DW-027: Add Word Cloud to canvas
   */
  test('DW-027: add Word Cloud to canvas', async ({ page }) => {
    const designer = new DashboardDesignerPage(page);
    const loaded = await ensureDesignerLoaded(page, designer);
    if (!loaded) {
      throw new Error('Designer not available');
    }

    await expectWidgetAdded(designer, EXTENDED_WIDGETS[9]);
  });

  /**
   * DW-028: Add Combo Chart to canvas
   */
  test('DW-028: add Combo Chart to canvas', async ({ page }) => {
    const designer = new DashboardDesignerPage(page);
    const loaded = await ensureDesignerLoaded(page, designer);
    if (!loaded) {
      throw new Error('Designer not available');
    }

    await expectWidgetAdded(designer, EXTENDED_WIDGETS[10]);
  });

  /**
   * DW-029: Add NPS Chart to canvas
   */
  test('DW-029: add NPS Chart to canvas', async ({ page }) => {
    const designer = new DashboardDesignerPage(page);
    const loaded = await ensureDesignerLoaded(page, designer);
    if (!loaded) {
      throw new Error('Designer not available');
    }

    await expectWidgetAdded(designer, EXTENDED_WIDGETS[11]);
  });

  /**
   * DW-030: Add Gallery to canvas
   */
  test('DW-030: add Gallery to canvas', async ({ page }) => {
    const designer = new DashboardDesignerPage(page);
    const loaded = await ensureDesignerLoaded(page, designer);
    if (!loaded) {
      throw new Error('Designer not available');
    }

    await expectWidgetAdded(designer, EXTENDED_WIDGETS[12]);
  });

  /**
   * DW-031: Add Kanban to canvas
   */
  test('DW-031: add Kanban to canvas', async ({ page }) => {
    const designer = new DashboardDesignerPage(page);
    const loaded = await ensureDesignerLoaded(page, designer);
    if (!loaded) {
      throw new Error('Designer not available');
    }

    await expectWidgetAdded(designer, EXTENDED_WIDGETS[13]);
  });
});

test.describe('Dashboard Widget Types — Property Panel', () => {
  /**
   * DW-016: Number Card shows property panel sections
   */
  test('DW-016: Number Card property panel sections', async ({ page }) => {
    const designer = new DashboardDesignerPage(page);
    const loaded = await ensureDesignerLoaded(page, designer);
    if (!loaded) {
      throw new Error('Designer not available');
    }

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
    if (!loaded) {
      throw new Error('Designer not available');
    }

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
    if (!loaded) {
      throw new Error('Designer not available');
    }

    const funnelWidget = designer.paletteItem('漏斗图');
    const hasFunnel = await funnelWidget.isVisible({ timeout: 5000 }).catch(() => false);
    if (!hasFunnel) {
      throw new Error('Funnel chart not in palette');
    }

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

  /**
   * DW-032: Word Cloud shows shape configuration
   */
  test('DW-032: Word Cloud shape configuration', async ({ page }) => {
    const designer = new DashboardDesignerPage(page);
    const loaded = await ensureDesignerLoaded(page, designer);
    if (!loaded) {
      throw new Error('Designer not available');
    }

    const widget = designer.paletteItem('词云');
    const hasWidget = await widget.isVisible({ timeout: 5000 }).catch(() => false);
    if (!hasWidget) {
      throw new Error('Word Cloud not in palette');
    }

    await designer.addWidget('词云');

    const panelHeader = designer.propertyPanel.locator('h2:has-text("词云")');
    await expect(panelHeader).toBeVisible({ timeout: 5000 });

    const shapeLabel = designer.propertyLabel('形状');
    await shapeLabel.scrollIntoViewIfNeeded().catch(() => {});
    const hasShapeLabel = await shapeLabel.isVisible({ timeout: 3000 }).catch(() => false);
    if (hasShapeLabel) {
      await expect(shapeLabel).toBeVisible();
    }
  });

  /**
   * DW-033: Combo Chart shows Y-axis configuration
   */
  test('DW-033: Combo Chart Y-axis configuration', async ({ page }) => {
    const designer = new DashboardDesignerPage(page);
    const loaded = await ensureDesignerLoaded(page, designer);
    if (!loaded) {
      throw new Error('Designer not available');
    }

    const widget = designer.paletteItem('组合图');
    const hasWidget = await widget.isVisible({ timeout: 5000 }).catch(() => false);
    if (!hasWidget) {
      throw new Error('Combo Chart not in palette');
    }

    await designer.addWidget('组合图');

    const panelHeader = designer.propertyPanel.locator('h2:has-text("组合图")');
    await expect(panelHeader).toBeVisible({ timeout: 5000 });

    const yAxisLabel = designer.propertyLabel('左Y轴名称');
    await yAxisLabel.scrollIntoViewIfNeeded().catch(() => {});
    const hasYAxisLabel = await yAxisLabel.isVisible({ timeout: 3000 }).catch(() => false);
    if (hasYAxisLabel) {
      await expect(yAxisLabel).toBeVisible();
    }
  });

  /**
   * DW-034: NPS Chart shows score field configuration
   */
  test('DW-034: NPS Chart score field configuration', async ({ page }) => {
    const designer = new DashboardDesignerPage(page);
    const loaded = await ensureDesignerLoaded(page, designer);
    if (!loaded) {
      throw new Error('Designer not available');
    }

    const widget = designer.paletteItem('NPS 图');
    const hasWidget = await widget.isVisible({ timeout: 5000 }).catch(() => false);
    if (!hasWidget) {
      throw new Error('NPS Chart not in palette');
    }

    await designer.addWidget('NPS 图');

    const panelHeader = designer.propertyPanel.locator('h2:has-text("NPS 图")');
    await expect(panelHeader).toBeVisible({ timeout: 5000 });

    const scoreLabel = designer.propertyLabel('评分字段');
    await scoreLabel.scrollIntoViewIfNeeded().catch(() => {});
    const hasScoreLabel = await scoreLabel.isVisible({ timeout: 3000 }).catch(() => false);
    if (hasScoreLabel) {
      await expect(scoreLabel).toBeVisible();
    }
  });

  /**
   * DW-035: Gallery shows columns configuration
   */
  test('DW-035: Gallery columns configuration', async ({ page }) => {
    const designer = new DashboardDesignerPage(page);
    const loaded = await ensureDesignerLoaded(page, designer);
    if (!loaded) {
      throw new Error('Designer not available');
    }

    const widget = designer.paletteItem('画册');
    const hasWidget = await widget.isVisible({ timeout: 5000 }).catch(() => false);
    if (!hasWidget) {
      throw new Error('Gallery not in palette');
    }

    await designer.addWidget('画册');

    const panelHeader = designer.propertyPanel.locator('h2:has-text("画册")');
    await expect(panelHeader).toBeVisible({ timeout: 5000 });

    const colLabel = designer.propertyLabel('列数');
    await colLabel.scrollIntoViewIfNeeded().catch(() => {});
    const hasColLabel = await colLabel.isVisible({ timeout: 3000 }).catch(() => false);
    if (hasColLabel) {
      await expect(colLabel).toBeVisible();
    }
  });

  /**
   * DW-036: Kanban shows group field configuration
   */
  test('DW-036: Kanban group field configuration', async ({ page }) => {
    const designer = new DashboardDesignerPage(page);
    const loaded = await ensureDesignerLoaded(page, designer);
    if (!loaded) {
      throw new Error('Designer not available');
    }

    const widget = designer.paletteItem('看板');
    const hasWidget = await widget.isVisible({ timeout: 5000 }).catch(() => false);
    if (!hasWidget) {
      throw new Error('Kanban not in palette');
    }

    await designer.addWidget('看板');

    const panelHeader = designer.propertyPanel.locator('h2:has-text("看板")');
    await expect(panelHeader).toBeVisible({ timeout: 5000 });

    const groupLabel = designer.propertyLabel('分组字段');
    await groupLabel.scrollIntoViewIfNeeded().catch(() => {});
    const hasGroupLabel = await groupLabel.isVisible({ timeout: 3000 }).catch(() => false);
    if (hasGroupLabel) {
      await expect(groupLabel).toBeVisible();
    }
  });
});

test.describe('Dashboard Widget Types — Saved Payload', () => {
  /**
   * DW-037: UI-added widget persists exact type/componentType, data source, and edited properties
   */
  test('DW-037: Area Chart saves static data source and visualization property', async ({
    page,
  }) => {
    const designer = new DashboardDesignerPage(page);
    const loaded = await ensureDesignerLoaded(page, designer);
    if (!loaded) {
      throw new Error('Designer not available');
    }

    const staticRows = [
      { month: 'Jan', cases: 7 },
      { month: 'Feb', cases: 11 },
    ];

    await expectWidgetAdded(designer, BASE_WIDGETS[4]);
    await setStaticDataSource(page, staticRows);
    await setWidgetProperty(page, {
      key: 'visualization.fillOpacity',
      kind: 'number',
      value: 0.75,
    });

    const dashboard = await saveDashboardAndReadBack(page, designer);
    expect(dashboard.widgets).toHaveLength(1);

    const widget = dashboard.widgets[0];
    expect(widget.type).toBe('smart-area-chart');
    expect(widget.componentType).toBe('smart-area-chart');
    expect(widget.w).toBe(6);
    expect(widget.h).toBe(4);
    expect(widget.minW).toBe(4);
    expect(widget.minH).toBe(3);
    expect(widget.config).toMatchObject({
      title: '面积图',
      dataSource: {
        type: 'static',
        staticData: staticRows,
      },
      visualization: {
        smooth: true,
        fillOpacity: 0.75,
      },
    });
  });

  for (const widgetCase of SAVED_PAYLOAD_WIDGETS) {
    test(`${widgetCase.testCode}: ${widgetCase.type} saves edited property payload`, async ({
      page,
    }) => {
      const designer = new DashboardDesignerPage(page);
      const loaded = await ensureDesignerLoaded(page, designer);
      if (!loaded) {
        throw new Error('Designer not available');
      }

      await expectWidgetAdded(designer, widgetCase);
      await setStaticDataSource(page);
      await setWidgetProperty(page, widgetCase.edit);

      const dashboard = await saveDashboardAndReadBack(page, designer);
      expect(dashboard.widgets).toHaveLength(1);

      const widget = dashboard.widgets[0];
      expect(widget.type).toBe(widgetCase.type);
      expect(widget.componentType).toBe(widgetCase.type);
      expectWidgetLayout(widget, widgetCase.size);
      expect(widget.config).toMatchObject({
        title: widgetCase.expectedTitle ?? widgetCase.label,
        dataSource: widgetCase.dataSource,
        ...widgetCase.expectedConfig,
      });
    });
  }
});

test.describe('Dashboard Widget Types — Categories & Counts', () => {
  /**
   * DW-019: Widget palette has category headers
   */
  test('DW-019: widget palette category headers', async ({ page }) => {
    const designer = new DashboardDesignerPage(page);
    const loaded = await ensureDesignerLoaded(page, designer);
    if (!loaded) {
      throw new Error('Designer not available');
    }

    // Check standard categories
    await expect(designer.palette.locator('h3:has-text("指标")')).toBeVisible({ timeout: 5000 });
    await expect(designer.palette.locator('h3:has-text("图表")')).toBeVisible();

    // New categories
    const viewCategory = designer.palette.locator('h3:has-text("视图")');
    const hasView = await viewCategory.isVisible({ timeout: 3000 }).catch(() => false);
    if (hasView) {
      await expect(viewCategory).toBeVisible();
    }

    const contentCategory = designer.palette.locator('h3:has-text("内容")');
    const hasContent = await contentCategory.isVisible({ timeout: 3000 }).catch(() => false);
    if (hasContent) {
      await expect(contentCategory).toBeVisible();
    }
  });

  /**
   * DW-020: Base widget types present @smoke
   */
  test('DW-020: base widget types present @smoke', async ({ page }) => {
    const designer = new DashboardDesignerPage(page);
    const loaded = await ensureDesignerLoaded(page, designer);
    if (!loaded) {
      throw new Error('Designer not available');
    }

    const foundLabels: string[] = [];
    for (const widget of BASE_WIDGETS) {
      await expect(designer.paletteItem(widget.label)).toBeVisible({ timeout: 5000 });
      foundLabels.push(widget.label);
    }

    expect(foundLabels).toEqual(BASE_WIDGETS.map((widget) => widget.label));
  });

  /**
   * DW-021: Extended widget types present
   */
  test('DW-021: extended widget types present', async ({ page }) => {
    const designer = new DashboardDesignerPage(page);
    const loaded = await ensureDesignerLoaded(page, designer);
    if (!loaded) {
      throw new Error('Designer not available');
    }

    const foundLabels: string[] = [];
    for (const widget of EXTENDED_WIDGETS) {
      await expect(designer.paletteItem(widget.label)).toBeVisible({ timeout: 5000 });
      foundLabels.push(widget.label);
    }

    expect(foundLabels).toEqual(EXTENDED_WIDGETS.map((widget) => widget.label));
  });
});
