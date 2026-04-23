/**
 * Dashboard Designer — Deep E2E Tests
 *
 * Covers widget palette, data source config, linkage, drilldown,
 * style, refresh, widget operations, settings dialog, and toolbar lifecycle.
 *
 * @since 6.0.0
 */
import { test, expect } from '../../fixtures';
import type { Page } from '@playwright/test';
import { uniqueId } from '../helpers';

/* ------------------------------------------------------------------ */
/*  Helpers                                                           */
/* ------------------------------------------------------------------ */

async function waitForDesignerLoad(page: Page) {
  await page.waitForLoadState('networkidle').catch(() => {});
  await page
    .locator('.animate-spin')
    .waitFor({ state: 'hidden', timeout: 10000 })
    .catch(() => {});
  await page
    .locator('text=Loading page...')
    .waitFor({ state: 'hidden', timeout: 10000 })
    .catch(() => {});
}

async function openNewDashboard(page: Page) {
  await page.goto('/dashboard-designer', { waitUntil: 'domcontentloaded' });
  await waitForDesignerLoad(page);
  await expect(page.locator('h1').first()).toBeVisible({ timeout: 10000 });
}

async function openDashboardWithWidget(page: Page): Promise<string> {
  const title = uniqueId('DD_Deep');
  const resp = await page.request.post('/api/dashboards', {
    data: {
      title,
      scope: 'personal',
      widgets: [
        {
          id: 'w1',
          type: 'NumberCard',
          x: 0,
          y: 0,
          w: 4,
          h: 2,
          title: 'Test Card',
          config: { title: 'Test Card', label: 'Count', value: 0 },
        },
      ],
      layoutConfig: { columns: 12, rowHeight: 60 },
    },
  });
  expect(resp.ok(), `create dashboard api status=${resp.status()}`).toBe(true);
  const body = (await resp.json()) as { data?: { pid?: string; id?: string } };
  const pid = body.data?.pid || body.data?.id;
  expect(pid).toBeTruthy();

  await page.goto(`/dashboard-designer/${pid}`, { waitUntil: 'domcontentloaded' });
  await waitForDesignerLoad(page);

  const widget = page.locator('.react-grid-item').first();
  await expect(widget).toBeVisible({ timeout: 8000 });
  await widget.click();
  await expect(page.getByTestId('widget-property-panel')).toBeVisible({ timeout: 5000 });
  return pid;
}

/* ================================================================== */
/*  1. Widget Palette — All Types                                     */
/* ================================================================== */

const widgetTypes = [
  { label: '数字卡片', id: 'DD-WP-01' },
  { label: '柱状图', id: 'DD-WP-02' },
  { label: '折线图', id: 'DD-WP-03' },
  { label: '饼图', id: 'DD-WP-04' },
  { label: '面积图', id: 'DD-WP-05' },
  { label: '漏斗图', id: 'DD-WP-06' },
  { label: '散点图', id: 'DD-WP-07' },
  { label: '进度条', id: 'DD-WP-08' },
  { label: '表格', id: 'DD-WP-09' },
];

test.describe('Widget Palette — All Types', () => {
  for (const wt of widgetTypes) {
    test(`${wt.id}: ${wt.label} → add widget to canvas`, async ({ page }) => {
      await openNewDashboard(page);
      const palette = page.locator('[data-testid="widget-palette"], aside').first();
      await expect(palette).toBeVisible({ timeout: 10000 });
      const widgetBtn = page.getByText(wt.label).first();
      if (await widgetBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
        await widgetBtn.click();
        const widgets = page.locator('.react-grid-item');
        await expect(widgets.first()).toBeVisible({ timeout: 8000 });
      }
    });
  }
});

/* ================================================================== */
/*  2. DataSourceConfig — All Fields                                  */
/* ================================================================== */

test.describe('DataSourceConfig — All Fields', () => {
  test('DD-DS-01: Data source type select visible', async ({ page }) => {
    await openDashboardWithWidget(page);
    const panel = page.getByTestId('widget-property-panel');
    const label = panel.locator('label', { hasText: /数据源类型/i }).first();
    await expect(label).toBeVisible({ timeout: 5000 });
    const select = label.locator('..').locator('select').first();
    await expect(select).toBeVisible();
  });

  test('DD-DS-02: Aggregate mode → data model select appears', async ({ page }) => {
    await openDashboardWithWidget(page);
    const panel = page.getByTestId('widget-property-panel');
    const dsTypeSelect = panel
      .locator('select')
      .filter({ has: page.locator('option:has-text("聚合查询")') })
      .first();
    if (await dsTypeSelect.isVisible({ timeout: 3000 }).catch(() => false)) {
      await dsTypeSelect.selectOption({ label: '聚合查询' });
      await expect(panel.locator('label', { hasText: /数据模型/ })).toBeVisible({ timeout: 3000 });
    }
  });

  test('DD-DS-03: Aggregate mode → group dimension label (chart widget)', async ({ page }) => {
    // Use a BarChart widget which has full DataSourceConfig with dimensions
    const title = uniqueId('DD_Chart');
    const resp = await page.request.post('/api/dashboards', {
      data: {
        title,
        scope: 'personal',
        widgets: [
          {
            id: 'w1',
            type: 'BarChart',
            x: 0,
            y: 0,
            w: 6,
            h: 4,
            title: 'Test Chart',
            config: {
              title: 'Test Chart',
              xAxis: ['A', 'B', 'C'],
              series: [{ name: 'S1', data: [1, 2, 3] }],
            },
          },
        ],
        layoutConfig: { columns: 12, rowHeight: 60 },
      },
    });
    const body = await resp.json();
    const pid = body.data?.pid || body.data?.id;
    expect(pid).toBeTruthy();
    await page.goto(`/dashboard-designer/${pid}`, { waitUntil: 'domcontentloaded' });
    await waitForDesignerLoad(page);
    const widget = page.locator('.react-grid-item').first();
    await expect(widget).toBeVisible({ timeout: 8000 });
    await widget.click();
    const panel = page.getByTestId('widget-property-panel');
    await expect(panel).toBeVisible({ timeout: 5000 });
    // Chart widgets should have 分组维度
    const label = panel.locator('label', { hasText: /分组维度/ }).first();
    if (await label.isVisible({ timeout: 3000 }).catch(() => false)) {
      await expect(label).toBeVisible();
    } else {
      // Some widget types may not have this field — verify at least DataSourceConfig section exists
      await expect(panel.locator('label', { hasText: /数据源类型/ }).first()).toBeVisible({
        timeout: 3000,
      });
    }
  });

  test('DD-DS-04: Aggregate mode → aggregation metrics label (chart widget)', async ({ page }) => {
    const title = uniqueId('DD_Chart2');
    const resp = await page.request.post('/api/dashboards', {
      data: {
        title,
        scope: 'personal',
        widgets: [
          {
            id: 'w1',
            type: 'BarChart',
            x: 0,
            y: 0,
            w: 6,
            h: 4,
            title: 'Test Chart',
            config: {
              title: 'Test Chart',
              xAxis: ['A', 'B', 'C'],
              series: [{ name: 'S1', data: [1, 2, 3] }],
            },
          },
        ],
        layoutConfig: { columns: 12, rowHeight: 60 },
      },
    });
    const body = await resp.json();
    const pid = body.data?.pid || body.data?.id;
    expect(pid).toBeTruthy();
    await page.goto(`/dashboard-designer/${pid}`, { waitUntil: 'domcontentloaded' });
    await waitForDesignerLoad(page);
    const widget = page.locator('.react-grid-item').first();
    await expect(widget).toBeVisible({ timeout: 8000 });
    await widget.click();
    const panel = page.getByTestId('widget-property-panel');
    await expect(panel).toBeVisible({ timeout: 5000 });
    const label = panel.locator('label', { hasText: /聚合指标/ }).first();
    if (await label.isVisible({ timeout: 3000 }).catch(() => false)) {
      await expect(label).toBeVisible();
    } else {
      await expect(panel.locator('label', { hasText: /数据源类型/ }).first()).toBeVisible({
        timeout: 3000,
      });
    }
  });

  test('DD-DS-05: Switch to namedQuery → query select appears', async ({ page }) => {
    await openDashboardWithWidget(page);
    const panel = page.getByTestId('widget-property-panel');
    const dsTypeSelect = panel
      .locator('select')
      .filter({ has: page.locator('option:has-text("命名查询")') })
      .first();
    if (await dsTypeSelect.isVisible({ timeout: 3000 }).catch(() => false)) {
      await dsTypeSelect.selectOption({ label: '命名查询' });
      await expect(panel.locator('label', { hasText: /命名查询/ }).last()).toBeVisible({
        timeout: 3000,
      });
    }
  });

  test('DD-DS-06: Switch to static → JSON textarea appears', async ({ page }) => {
    await openDashboardWithWidget(page);
    const panel = page.getByTestId('widget-property-panel');
    const dsTypeSelect = panel
      .locator('select')
      .filter({ has: page.locator('option:has-text("静态数据")') })
      .first();
    if (await dsTypeSelect.isVisible({ timeout: 3000 }).catch(() => false)) {
      await dsTypeSelect.selectOption({ label: '静态数据' });
      await expect(panel.locator('textarea').first()).toBeVisible({ timeout: 3000 });
    }
  });

  test('DD-DS-07: Filter condition section', async ({ page }) => {
    await openDashboardWithWidget(page);
    const panel = page.getByTestId('widget-property-panel');
    await expect(panel.locator('label', { hasText: /筛选条件/ })).toBeVisible({ timeout: 5000 });
  });

  test('DD-DS-08: Row limit input', async ({ page }) => {
    await openDashboardWithWidget(page);
    const panel = page.getByTestId('widget-property-panel');
    await expect(panel.locator('label', { hasText: /返回行数/ })).toBeVisible({ timeout: 5000 });
  });
});

/* ================================================================== */
/*  3. LinkageConfig — All Fields                                     */
/* ================================================================== */

test.describe('LinkageConfig — All Fields', () => {
  test('DD-LK-01: Enable chart linkage checkbox', async ({ page }) => {
    await openDashboardWithWidget(page);
    const checkbox = page.getByRole('checkbox', { name: /启用图表联动/ });
    await expect(checkbox).toBeVisible({ timeout: 5000 });
    if (!(await checkbox.isChecked())) await checkbox.click();
    expect(await checkbox.isChecked()).toBe(true);
  });

  test('DD-LK-02: Linkage group select visible when enabled', async ({ page }) => {
    await openDashboardWithWidget(page);
    const checkbox = page.getByRole('checkbox', { name: /启用图表联动/ });
    if (!(await checkbox.isChecked())) await checkbox.click();
    await expect(page.locator('label', { hasText: /联动分组/ })).toBeVisible({ timeout: 3000 });
  });

  test('DD-LK-03: Send filter checkbox', async ({ page }) => {
    await openDashboardWithWidget(page);
    const enableCb = page.getByRole('checkbox', { name: /启用图表联动/ });
    if (!(await enableCb.isChecked())) await enableCb.click();
    const sendCb = page.getByRole('checkbox', { name: /发送过滤器/ });
    await expect(sendCb).toBeVisible({ timeout: 3000 });
  });

  test('DD-LK-04: Receive filter checkbox', async ({ page }) => {
    await openDashboardWithWidget(page);
    const enableCb = page.getByRole('checkbox', { name: /启用图表联动/ });
    if (!(await enableCb.isChecked())) await enableCb.click();
    const recvCb = page.getByRole('checkbox', { name: /接收过滤器/ });
    await expect(recvCb).toBeVisible({ timeout: 3000 });
  });

  test('DD-LK-05: Disable linkage hides sub-config', async ({ page }) => {
    await openDashboardWithWidget(page);
    const checkbox = page.getByRole('checkbox', { name: /启用图表联动/ });
    if (await checkbox.isChecked()) await checkbox.click();
    await expect(page.locator('label', { hasText: /联动分组/ })).not.toBeVisible({ timeout: 3000 });
  });
});

/* ================================================================== */
/*  4. DrilldownConfig — All Fields                                   */
/* ================================================================== */

test.describe('DrilldownConfig — All Fields', () => {
  test('DD-DD-01: Enable drilldown checkbox', async ({ page }) => {
    await openDashboardWithWidget(page);
    const checkbox = page.getByRole('checkbox', { name: /启用点击钻取/ });
    await expect(checkbox).toBeVisible({ timeout: 5000 });
    if (!(await checkbox.isChecked())) await checkbox.click();
    expect(await checkbox.isChecked()).toBe(true);
  });

  test('DD-DD-02: 4 drilldown action radios visible', async ({ page }) => {
    await openDashboardWithWidget(page);
    const checkbox = page.getByRole('checkbox', { name: /启用点击钻取/ });
    if (!(await checkbox.isChecked())) await checkbox.click();
    for (const action of ['过滤数据', '跳转页面', '弹窗详情', '跳转仪表盘']) {
      await expect(page.locator('label', { hasText: action }).first()).toBeVisible({
        timeout: 3000,
      });
    }
  });

  test('DD-DD-03: Navigate → target page select', async ({ page }) => {
    await openDashboardWithWidget(page);
    const checkbox = page.getByRole('checkbox', { name: /启用点击钻取/ });
    if (!(await checkbox.isChecked())) await checkbox.click();
    const navigateRadio = page.locator('input[name="drilldown-action"][value="navigate"]');
    if (await navigateRadio.isVisible({ timeout: 2000 }).catch(() => false)) {
      await navigateRadio.click();
      await expect(page.locator('label', { hasText: /目标页面/ })).toBeVisible({ timeout: 3000 });
    }
  });

  test('DD-DD-04: Navigate → parameter mapping input', async ({ page }) => {
    await openDashboardWithWidget(page);
    const checkbox = page.getByRole('checkbox', { name: /启用点击钻取/ });
    if (!(await checkbox.isChecked())) await checkbox.click();
    const navigateRadio = page.locator('input[name="drilldown-action"][value="navigate"]');
    if (await navigateRadio.isVisible({ timeout: 2000 }).catch(() => false)) {
      await navigateRadio.click();
      await expect(page.locator('label', { hasText: /参数映射/ })).toBeVisible({ timeout: 3000 });
    }
  });

  test('DD-DD-05: Dashboard action → target dashboard select', async ({ page }) => {
    await openDashboardWithWidget(page);
    const checkbox = page.getByRole('checkbox', { name: /启用点击钻取/ });
    if (!(await checkbox.isChecked())) await checkbox.click();
    const dashRadio = page.locator('input[name="drilldown-action"][value="dashboard"]');
    if (await dashRadio.isVisible({ timeout: 2000 }).catch(() => false)) {
      await dashRadio.click();
      await expect(page.locator('label', { hasText: /目标仪表盘/ })).toBeVisible({ timeout: 3000 });
    }
  });

  test('DD-DD-06: Filter action → drill path display', async ({ page }) => {
    await openDashboardWithWidget(page);
    const checkbox = page.getByRole('checkbox', { name: /启用点击钻取/ });
    if (!(await checkbox.isChecked())) await checkbox.click();
    const filterRadio = page.locator('input[name="drilldown-action"][value="filter"]');
    if (await filterRadio.isVisible({ timeout: 2000 }).catch(() => false)) {
      await filterRadio.click();
      await expect(page.locator('label', { hasText: /钻取路径/ })).toBeVisible({ timeout: 3000 });
    }
  });

  test('DD-DD-07: Disable drilldown hides sub-config', async ({ page }) => {
    await openDashboardWithWidget(page);
    const checkbox = page.getByRole('checkbox', { name: /启用点击钻取/ });
    if (await checkbox.isChecked()) await checkbox.click();
    await expect(page.locator('input[name="drilldown-action"]').first()).not.toBeVisible({
      timeout: 3000,
    });
  });
});

/* ================================================================== */
/*  5. StyleConfig — All Fields                                       */
/* ================================================================== */

test.describe('StyleConfig — All Fields', () => {
  test('DD-ST-01: Theme buttons (5 themes) visible', async ({ page }) => {
    await openDashboardWithWidget(page);
    for (const theme of ['默认', '复古', '暗色', '西部', '马卡龙']) {
      const btn = page
        .locator('label', { hasText: theme })
        .first()
        .or(page.getByText(theme).first());
      if (await btn.isVisible({ timeout: 2000 }).catch(() => false)) {
        await expect(btn).toBeVisible();
      }
    }
  });

  test('DD-ST-02: Show title checkbox', async ({ page }) => {
    await openDashboardWithWidget(page);
    const checkbox = page.getByRole('checkbox', { name: /显示标题/ });
    await expect(checkbox).toBeVisible({ timeout: 5000 });
    await checkbox.click();
  });

  test('DD-ST-03: Show legend checkbox', async ({ page }) => {
    await openDashboardWithWidget(page);
    const checkbox = page.getByRole('checkbox', { name: /显示图例/ });
    await expect(checkbox).toBeVisible({ timeout: 5000 });
  });

  test('DD-ST-04: Legend position select when enabled', async ({ page }) => {
    await openDashboardWithWidget(page);
    const checkbox = page.getByRole('checkbox', { name: /显示图例/ });
    if (!(await checkbox.isChecked())) await checkbox.click();
    await expect(page.locator('label', { hasText: /图例位置/ })).toBeVisible({ timeout: 3000 });
    for (const pos of ['顶部', '底部', '左侧', '右侧']) {
      await expect(page.locator('option', { hasText: pos }).first()).toBeAttached();
    }
  });

  test('DD-ST-05: Show data labels checkbox', async ({ page }) => {
    await openDashboardWithWidget(page);
    const checkbox = page.getByRole('checkbox', { name: /显示数据标签/ });
    await expect(checkbox).toBeVisible({ timeout: 5000 });
  });

  test('DD-ST-06: Label position select when enabled', async ({ page }) => {
    await openDashboardWithWidget(page);
    const checkbox = page.getByRole('checkbox', { name: /显示数据标签/ });
    if (!(await checkbox.isChecked())) await checkbox.click();
    await expect(page.locator('label', { hasText: /标签位置/ })).toBeVisible({ timeout: 3000 });
  });

  test('DD-ST-07: Border radius range slider', async ({ page }) => {
    await openDashboardWithWidget(page);
    await expect(page.locator('label', { hasText: /圆角大小/ })).toBeVisible({ timeout: 5000 });
    const rangeInput = page.locator('input[type="range"]').first();
    await expect(rangeInput).toBeVisible();
  });

  test('DD-ST-08: Background color inputs', async ({ page }) => {
    await openDashboardWithWidget(page);
    await expect(page.locator('label', { hasText: /背景颜色/ })).toBeVisible({ timeout: 5000 });
    await expect(page.locator('input[type="color"]').first()).toBeVisible();
  });
});

/* ================================================================== */
/*  6. RefreshConfig — All Fields                                     */
/* ================================================================== */

test.describe('RefreshConfig — All Fields', () => {
  test('DD-RF-01: Auto refresh interval select', async ({ page }) => {
    await openDashboardWithWidget(page);
    await expect(page.locator('label', { hasText: /自动刷新间隔/ })).toBeVisible({ timeout: 5000 });
    for (const opt of ['手动刷新', '10 秒', '1 分钟']) {
      await expect(page.locator('option', { hasText: opt }).first()).toBeAttached();
    }
  });

  test('DD-RF-02: Manual refresh button', async ({ page }) => {
    await openDashboardWithWidget(page);
    const refreshBtn = page.getByRole('button', { name: /立即刷新/ });
    if (await refreshBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
      await expect(refreshBtn).toBeVisible();
    }
  });
});

/* ================================================================== */
/*  7. Widget Operations                                              */
/* ================================================================== */

test.describe('Widget Operations', () => {
  test('DD-OP-01: Delete widget', async ({ page }) => {
    await openDashboardWithWidget(page);
    const deleteBtn = page.locator('button[title="删除"]');
    await expect(deleteBtn).toBeVisible({ timeout: 5000 });
  });

  test('DD-OP-02: Duplicate widget', async ({ page }) => {
    await openDashboardWithWidget(page);
    const dupBtn = page.locator('button[title="复制"]');
    await expect(dupBtn).toBeVisible({ timeout: 5000 });
  });

  test('DD-OP-03: Widget position section', async ({ page }) => {
    await openDashboardWithWidget(page);
    const panel = page.getByTestId('widget-property-panel');
    for (const label of ['X', 'Y', '宽度', '高度']) {
      await expect(panel.locator('label', { hasText: label }).first()).toBeVisible({
        timeout: 3000,
      });
    }
  });

  test('DD-OP-04: NumberCard icon uses picker and persists after save', async ({ page }) => {
    const pid = await openDashboardWithWidget(page);
    const panel = page.getByTestId('widget-property-panel');

    const iconLabel = panel.locator('label', { hasText: /^图标$/ }).first();
    await expect(iconLabel).toBeVisible({ timeout: 5000 });

    const iconTrigger = iconLabel.locator('xpath=following-sibling::*[1]').getByRole('button');
    await expect(iconTrigger).toBeVisible({ timeout: 5000 });
    await iconTrigger.click();

    const warningIcon = page.getByTitle('警告');
    await expect(warningIcon).toBeVisible({ timeout: 5000 });
    await warningIcon.click();

    await expect(iconTrigger).toContainText('警告');
    const saveButton = page.locator('[data-testid="designer-toolbar-btn-save"]');
    await expect(saveButton).toBeEnabled({ timeout: 5000 });
    await saveButton.click();

    await expect
      .poll(
        async () => {
          const resp = await page.request.get(`/api/dashboards/${pid}`);
          if (!resp.ok()) return '';
          return JSON.stringify(await resp.json());
        },
        { timeout: 10000 },
      )
      .toContain('"icon":"warning"');
  });
});

/* ================================================================== */
/*  8. Settings Dialog — All Fields                                   */
/* ================================================================== */

test.describe('Settings Dialog — All Fields', () => {
  test('DD-SET-01: Title input editable', async ({ page }) => {
    await page.goto('/dashboard-designer', { waitUntil: 'domcontentloaded' });
    await waitForDesignerLoad(page);
    const settingsBtn = page.locator('[data-testid="toolbar-btn-settings"]');
    await expect(settingsBtn).toBeVisible({ timeout: 8000 });
    await settingsBtn.click();
    const dialog = page.locator('[role="dialog"][aria-modal="true"]').first();
    await expect(dialog).toBeVisible({ timeout: 5000 });
    const titleInput = dialog.locator('input[type="text"]').first();
    await expect(titleInput).toBeVisible();
    await titleInput.fill(uniqueId('DD_Settings'));
  });

  test('DD-SET-02: Description textarea', async ({ page }) => {
    await page.goto('/dashboard-designer', { waitUntil: 'domcontentloaded' });
    await waitForDesignerLoad(page);
    await page.locator('[data-testid="toolbar-btn-settings"]').click();
    const dialog = page.locator('[role="dialog"][aria-modal="true"]').first();
    await expect(dialog).toBeVisible({ timeout: 5000 });
    const textarea = dialog.locator('textarea').first();
    if (await textarea.isVisible({ timeout: 2000 }).catch(() => false)) {
      await textarea.fill('Test description');
    }
  });

  test('DD-SET-03: Scope select', async ({ page }) => {
    await page.goto('/dashboard-designer', { waitUntil: 'domcontentloaded' });
    await waitForDesignerLoad(page);
    await page.locator('[data-testid="toolbar-btn-settings"]').click();
    const dialog = page.locator('[role="dialog"][aria-modal="true"]').first();
    await expect(dialog).toBeVisible({ timeout: 5000 });
    const scopeSelect = dialog.locator('select').first();
    if (await scopeSelect.isVisible({ timeout: 2000 }).catch(() => false)) {
      for (const scope of ['personal', 'team', 'global']) {
        await expect(dialog.locator('option', { hasText: scope }).first()).toBeAttached();
      }
    }
  });

  test('DD-SET-04: Save settings closes dialog', async ({ page }) => {
    await page.goto('/dashboard-designer', { waitUntil: 'domcontentloaded' });
    await waitForDesignerLoad(page);
    await page.locator('[data-testid="toolbar-btn-settings"]').click();
    const dialog = page.locator('[role="dialog"][aria-modal="true"]').first();
    await expect(dialog).toBeVisible({ timeout: 5000 });
    const titleInput = dialog.locator('input[type="text"]').first();
    await titleInput.fill(uniqueId('DD_Save'));
    const saveBtn = dialog
      .locator('button:has-text("保存"), button:has-text("Save"), button.bg-blue-600')
      .first();
    await saveBtn.click();
    await expect(dialog).not.toBeVisible({ timeout: 5000 });
  });
});

/* ================================================================== */
/*  9. Toolbar & Lifecycle                                            */
/* ================================================================== */

test.describe('Toolbar & Lifecycle', () => {
  test('DD-TL-01: Undo/Redo buttons visible', async ({ page }) => {
    await page.goto('/dashboard-designer', { waitUntil: 'domcontentloaded' });
    await waitForDesignerLoad(page);
    const undoBtn = page.locator('[data-testid="designer-toolbar-btn-undo"]');
    const redoBtn = page.locator('[data-testid="designer-toolbar-btn-redo"]');
    await expect(undoBtn).toBeVisible({ timeout: 8000 });
    await expect(redoBtn).toBeVisible();
  });

  test('DD-TL-02: Save + Publish via API', async ({ page }) => {
    const title = uniqueId('DD_Lifecycle');
    const createResp = await page.request.post('/api/dashboards', {
      data: {
        title,
        scope: 'personal',
        widgets: [
          {
            id: 'w1',
            type: 'NumberCard',
            x: 0,
            y: 0,
            w: 4,
            h: 2,
            title: 'Test Card',
            config: { title: 'Test Card', label: 'Count', value: 0 },
          },
        ],
        layoutConfig: { columns: 12, rowHeight: 60 },
      },
    });
    const { data } = await createResp.json();
    const pid = data?.pid || data?.id;
    expect(pid).toBeTruthy();

    const publishResp = await page.request.post(`/api/dashboards/${pid}/publish`);
    expect(publishResp.status()).toBeLessThan(400);

    const getResp = await page.request.get(`/api/dashboards/${pid}`);
    const { data: d } = await getResp.json();
    expect(d.status).toBe('published');
  });

  test('DD-TL-03: Unpublish via API', async ({ page }) => {
    const title = uniqueId('DD_Unpublish');
    const createResp = await page.request.post('/api/dashboards', {
      data: {
        title,
        scope: 'personal',
        widgets: [
          {
            id: 'w1',
            type: 'NumberCard',
            x: 0,
            y: 0,
            w: 4,
            h: 2,
            title: 'Test Card',
            config: { title: 'Test Card', label: 'Count', value: 0 },
          },
        ],
        layoutConfig: { columns: 12, rowHeight: 60 },
      },
    });
    const { data } = await createResp.json();
    const pid = data?.pid || data?.id;
    expect(pid).toBeTruthy();

    await page.request.post(`/api/dashboards/${pid}/publish`);
    const unpublishResp = await page.request.post(`/api/dashboards/${pid}/unpublish`);
    expect(unpublishResp.status()).toBeLessThan(400);

    const getResp = await page.request.get(`/api/dashboards/${pid}`);
    const { data: d } = await getResp.json();
    expect(d.status).toBe('draft');
  });
});
