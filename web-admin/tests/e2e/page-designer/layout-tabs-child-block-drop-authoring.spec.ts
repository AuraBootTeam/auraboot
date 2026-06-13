import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import { uniqueId } from '../helpers';

async function createTabsChildDropAuthoringPage(page: Page) {
  const id = uniqueId('pd_layout_tabs_child_drop');
  const pageKey = id.replace(/-/g, '_');
  const title = `Layout tabs child drop ${id}`;
  const payload = {
    name: title,
    pageKey,
    title,
    kind: 'form',
    modelCode: 'page_schema',
    profile: 'admin',
    layout: { type: 'stack', gap: 12 },
    blocks: [
      {
        id: 'tabs_drag_target',
        blockType: 'tabs',
        title: { 'en-US': 'Tabs', 'zh-CN': '标签页' },
        tabs: [
          {
            key: 'all',
            label: { 'en-US': 'All', 'zh-CN': '全部' },
            filter: null,
          },
        ],
      },
    ],
    dataSources: {},
    schemaVersion: 4,
    metaInfo: { runtimeE2E: true, layoutTabsChildDropAuthoring: true },
    semver: '0.1.0',
  };

  const createResp = await page.request.post('/api/pages', { data: payload });
  expect(createResp.ok(), `Create tabs child drop page failed: ${createResp.status()}`).toBeTruthy();
  const createBody = await createResp.json();
  expect(createBody.code, 'create page API code').toBe('0');
  const pid = String(createBody.data?.pid || '');
  expect(pid, 'created tabs child drop pid').toBeTruthy();
  return { pid, tabsBlockId: 'tabs_drag_target' };
}

async function openDesignerByPid(page: Page, pid: string) {
  await page.goto(`/page-designer/${pid}`, { waitUntil: 'domcontentloaded' });
  await expect(page.getByTestId('designer-canvas')).toBeVisible();
  await expect(page.getByTestId('toolbar-save')).toBeVisible();
}

async function canvasBlockIds(page: Page): Promise<string[]> {
  return page.locator('[data-testid="sortable-block"][data-block-id]').evaluateAll((elements) =>
    elements
      .map((element) => (element as HTMLElement).getAttribute('data-block-id') || '')
      .filter(Boolean),
  );
}

async function selectCanvasBlock(page: Page, blockId: string) {
  const block = page.locator(`[data-testid="sortable-block"][data-block-id="${blockId}"]`);
  await expect(block).toBeVisible();
  await block.click();
}

async function dragPaletteBlockToTabChildDropZone(page: Page, blockType: string) {
  await page.getByTestId('designer-tab-blocks').click();
  await expect(page.getByTestId('library-tab-blocks')).toBeVisible();

  const source = page.getByTestId(`block-palette-item-${blockType}`);
  const target = page.getByTestId('tab-child-drop-zone');
  await expect(source).toBeVisible();
  await expect(target).toBeVisible();

  await source.scrollIntoViewIfNeeded();
  await source.dragTo(target);
}

async function saveDesignerAndWait(page: Page, pid: string) {
  const saveButton = page.getByTestId('toolbar-save');
  await expect(saveButton).toBeVisible();
  await expect.poll(async () => saveButton.isEnabled().catch(() => false)).toBe(true);

  const saveResp = page.waitForResponse(
    (response) =>
      response.url().includes(`/api/pages/${pid}`) &&
      response.request().method() === 'PUT' &&
      response.status() < 400,
  );
  await saveButton.click();
  await saveResp;
}

async function fetchPageByPid(page: Page, pid: string): Promise<Record<string, any>> {
  const resp = await page.request.get(`/api/pages/${pid}`);
  expect(resp.ok(), `Fetch page ${pid} failed: ${resp.status()}`).toBeTruthy();
  const body = await resp.json();
  expect(body.code, 'fetch page API code').toBe('0');
  return body.data ?? {};
}

function savedBlockById(savedPage: Record<string, any>, blockId: string): Record<string, any> {
  const block = (savedPage.blocks ?? []).find((item: any) => item.id === blockId);
  expect(block, `saved block ${blockId}`).toBeTruthy();
  return block;
}

test.describe('Page Designer layout tabs child block drop authoring', () => {
  test('drags a text block into the selected tab child block list', async ({ page }) => {
    const { pid, tabsBlockId } = await createTabsChildDropAuthoringPage(page);
    await openDesignerByPid(page, pid);

    await selectCanvasBlock(page, tabsBlockId);
    await expect(page.getByTestId('tab-child-blocks-editor')).toBeVisible();

    await page.getByTestId('tab-filter-key-input').fill('overview');
    await page.getByTestId('tab-filter-label-en-input').fill('Overview');
    await page.getByTestId('tab-filter-label-zh-input').fill('概览');

    const beforeTopLevelIds = await canvasBlockIds(page);
    await dragPaletteBlockToTabChildDropZone(page, 'text');

    await expect(page.getByTestId('tab-child-block-0')).toBeVisible();
    await page.getByTestId('tab-child-text-content-0').fill('Dragged nested overview copy');
    await expect.poll(() => canvasBlockIds(page)).toEqual(beforeTopLevelIds);

    await saveDesignerAndWait(page, pid);
    const savedPage = await fetchPageByPid(page, pid);
    const savedBlock = savedBlockById(savedPage, tabsBlockId);
    expect(savedBlock).toMatchObject({
      blockType: 'tabs',
      tabs: [
        {
          key: 'overview',
          label: { 'en-US': 'Overview', 'zh-CN': '概览' },
          filter: null,
          blocks: [
            {
              blockType: 'text',
              title: { 'en-US': 'Text', 'zh-CN': '文本内容' },
              props: { content: 'Dragged nested overview copy' },
            },
          ],
        },
      ],
    });

    await openDesignerByPid(page, pid);
    await selectCanvasBlock(page, tabsBlockId);
    await expect(page.getByTestId('tab-child-text-content-0')).toHaveValue(
      'Dragged nested overview copy',
    );
  });

  test('drags a stat card child block and edits its data settings', async ({ page }) => {
    const { pid, tabsBlockId } = await createTabsChildDropAuthoringPage(page);
    await openDesignerByPid(page, pid);

    await selectCanvasBlock(page, tabsBlockId);
    await expect(page.getByTestId('tab-child-blocks-editor')).toBeVisible();

    await page.getByTestId('tab-filter-key-input').fill('metrics');
    await page.getByTestId('tab-filter-label-en-input').fill('Metrics');
    await page.getByTestId('tab-filter-label-zh-input').fill('指标');

    const beforeTopLevelIds = await canvasBlockIds(page);
    await dragPaletteBlockToTabChildDropZone(page, 'stat-card');

    await expect(page.getByTestId('tab-child-block-0')).toBeVisible();
    await expect(page.getByTestId('tab-child-block-0')).toContainText('stat-card');
    await page.getByTestId('tab-child-title-en-input-0').fill('Nested metric card');
    await page.getByTestId('tab-child-title-zh-input-0').fill('嵌套指标卡片');
    await page.getByTestId('tab-child-stat-data-source-input-0').fill('nested_stats');
    await page.getByTestId('tab-child-stat-value-field-input-0').fill('totalCount');
    await page.getByTestId('tab-child-stat-change-field-input-0').fill('deltaRate');
    await page.getByTestId('tab-child-stat-prefix-input-0').fill('USD ');
    await page.getByTestId('tab-child-stat-suffix-input-0').fill('records');
    await page.getByTestId('tab-child-stat-color-select-0').selectOption('green');
    await page.getByTestId('tab-child-stat-refresh-interval-input-0').fill('1500');
    await expect.poll(() => canvasBlockIds(page)).toEqual(beforeTopLevelIds);

    await saveDesignerAndWait(page, pid);
    const savedPage = await fetchPageByPid(page, pid);
    const savedBlock = savedBlockById(savedPage, tabsBlockId);
    expect(savedBlock).toMatchObject({
      blockType: 'tabs',
      tabs: [
        {
          key: 'metrics',
          label: { 'en-US': 'Metrics', 'zh-CN': '指标' },
          filter: null,
          blocks: [
            {
              blockType: 'stat-card',
              title: { 'en-US': 'Nested metric card', 'zh-CN': '嵌套指标卡片' },
              dataSource: 'nested_stats',
              refreshInterval: 1500,
              props: {
                valueField: 'totalCount',
                changeField: 'deltaRate',
                prefix: 'USD ',
                suffix: 'records',
                color: 'green',
              },
            },
          ],
        },
      ],
    });

    await openDesignerByPid(page, pid);
    await selectCanvasBlock(page, tabsBlockId);
    await expect(page.getByTestId('tab-child-title-en-input-0')).toHaveValue(
      'Nested metric card',
    );
    await expect(page.getByTestId('tab-child-title-zh-input-0')).toHaveValue('嵌套指标卡片');
    await expect(page.getByTestId('tab-child-stat-data-source-input-0')).toHaveValue(
      'nested_stats',
    );
    await expect(page.getByTestId('tab-child-stat-value-field-input-0')).toHaveValue(
      'totalCount',
    );
    await expect(page.getByTestId('tab-child-stat-change-field-input-0')).toHaveValue(
      'deltaRate',
    );
    await expect(page.getByTestId('tab-child-stat-prefix-input-0')).toHaveValue('USD ');
    await expect(page.getByTestId('tab-child-stat-suffix-input-0')).toHaveValue('records');
    await expect(page.getByTestId('tab-child-stat-color-select-0')).toHaveValue('green');
    await expect(page.getByTestId('tab-child-stat-refresh-interval-input-0')).toHaveValue('1500');
  });

  test('drags a chart child block and edits its data settings', async ({ page }) => {
    const { pid, tabsBlockId } = await createTabsChildDropAuthoringPage(page);
    await openDesignerByPid(page, pid);

    await selectCanvasBlock(page, tabsBlockId);
    await expect(page.getByTestId('tab-child-blocks-editor')).toBeVisible();

    await page.getByTestId('tab-filter-key-input').fill('charts');
    await page.getByTestId('tab-filter-label-en-input').fill('Charts');
    await page.getByTestId('tab-filter-label-zh-input').fill('图表');

    const beforeTopLevelIds = await canvasBlockIds(page);
    await dragPaletteBlockToTabChildDropZone(page, 'chart-card');

    await expect(page.getByTestId('tab-child-block-0')).toBeVisible();
    await expect(page.getByTestId('tab-child-block-0')).toContainText('chart-card');
    await page.getByTestId('tab-child-title-en-input-0').fill('Nested trend chart');
    await page.getByTestId('tab-child-title-zh-input-0').fill('嵌套趋势图');
    await page.getByTestId('tab-child-chart-data-source-input-0').fill('nested_chart_ds');
    await page.getByTestId('tab-child-chart-type-select-0').selectOption('line');
    await page.getByTestId('tab-child-chart-x-field-input-0').fill('category');
    await page.getByTestId('tab-child-chart-y-field-input-0').fill('amount');
    await page.getByTestId('tab-child-chart-refresh-interval-input-0').fill('1500');
    await page.getByTestId('tab-child-chart-smooth-switch-0').click();
    await page.getByTestId('tab-child-chart-legend-switch-0').click();
    await page.getByTestId('tab-child-chart-height-input-0').fill('240');
    await expect.poll(() => canvasBlockIds(page)).toEqual(beforeTopLevelIds);

    await saveDesignerAndWait(page, pid);
    const savedPage = await fetchPageByPid(page, pid);
    const savedBlock = savedBlockById(savedPage, tabsBlockId);
    expect(savedBlock).toMatchObject({
      blockType: 'tabs',
      tabs: [
        {
          key: 'charts',
          label: { 'en-US': 'Charts', 'zh-CN': '图表' },
          filter: null,
          blocks: [
            {
              blockType: 'chart-card',
              title: { 'en-US': 'Nested trend chart', 'zh-CN': '嵌套趋势图' },
              dataSource: 'nested_chart_ds',
              refreshInterval: 1500,
              props: {
                chartType: 'line',
                xField: 'category',
                yField: 'amount',
                smooth: false,
                showLegend: false,
                height: 240,
              },
            },
          ],
        },
      ],
    });

    await openDesignerByPid(page, pid);
    await selectCanvasBlock(page, tabsBlockId);
    await expect(page.getByTestId('tab-child-title-en-input-0')).toHaveValue(
      'Nested trend chart',
    );
    await expect(page.getByTestId('tab-child-title-zh-input-0')).toHaveValue('嵌套趋势图');
    await expect(page.getByTestId('tab-child-chart-data-source-input-0')).toHaveValue(
      'nested_chart_ds',
    );
    await expect(page.getByTestId('tab-child-chart-type-select-0')).toHaveValue('line');
    await expect(page.getByTestId('tab-child-chart-x-field-input-0')).toHaveValue('category');
    await expect(page.getByTestId('tab-child-chart-y-field-input-0')).toHaveValue('amount');
    await expect(page.getByTestId('tab-child-chart-refresh-interval-input-0')).toHaveValue(
      '1500',
    );
    await expect(page.getByTestId('tab-child-chart-smooth-switch-0')).toHaveAttribute(
      'aria-checked',
      'false',
    );
    await expect(page.getByTestId('tab-child-chart-legend-switch-0')).toHaveAttribute(
      'aria-checked',
      'false',
    );
    await expect(page.getByTestId('tab-child-chart-height-input-0')).toHaveValue('240');
  });

  test('drags a custom child block and edits its runtime props', async ({ page }) => {
    const { pid, tabsBlockId } = await createTabsChildDropAuthoringPage(page);
    await openDesignerByPid(page, pid);

    await selectCanvasBlock(page, tabsBlockId);
    await expect(page.getByTestId('tab-child-blocks-editor')).toBeVisible();

    await page.getByTestId('tab-filter-key-input').fill('custom');
    await page.getByTestId('tab-filter-label-en-input').fill('Custom');
    await page.getByTestId('tab-filter-label-zh-input').fill('自定义');

    const beforeTopLevelIds = await canvasBlockIds(page);
    await dragPaletteBlockToTabChildDropZone(page, 'custom');

    await expect(page.getByTestId('tab-child-block-0')).toBeVisible();
    await expect(page.getByTestId('tab-child-block-0')).toContainText('custom');
    await page.getByTestId('tab-child-title-en-input-0').fill('Nested custom runtime');
    await page.getByTestId('tab-child-title-zh-input-0').fill('嵌套自定义运行时');
    await page.getByTestId('tab-child-custom-component-input-0').fill('decision-field-impact');
    await page.getByTestId('tab-child-custom-props-json-input-0').fill(
      JSON.stringify({ initialCurrentDataType: 'number', tone: 'critical' }, null, 2),
    );
    await page.getByTestId('tab-child-custom-value-field-input-0').fill('pid');
    await expect.poll(() => canvasBlockIds(page)).toEqual(beforeTopLevelIds);

    await saveDesignerAndWait(page, pid);
    const savedPage = await fetchPageByPid(page, pid);
    const savedBlock = savedBlockById(savedPage, tabsBlockId);
    expect(savedBlock).toMatchObject({
      blockType: 'tabs',
      tabs: [
        {
          key: 'custom',
          label: { 'en-US': 'Custom', 'zh-CN': '自定义' },
          filter: null,
          blocks: [
            {
              blockType: 'custom',
              title: { 'en-US': 'Nested custom runtime', 'zh-CN': '嵌套自定义运行时' },
              component: 'decision-field-impact',
              props: {
                initialCurrentDataType: 'number',
                tone: 'critical',
                valueField: 'pid',
              },
            },
          ],
        },
      ],
    });

    await openDesignerByPid(page, pid);
    await selectCanvasBlock(page, tabsBlockId);
    await expect(page.getByTestId('tab-child-title-en-input-0')).toHaveValue(
      'Nested custom runtime',
    );
    await expect(page.getByTestId('tab-child-title-zh-input-0')).toHaveValue(
      '嵌套自定义运行时',
    );
    await expect(page.getByTestId('tab-child-custom-component-input-0')).toHaveValue(
      'decision-field-impact',
    );
    await expect(page.getByTestId('tab-child-custom-value-field-input-0')).toHaveValue('pid');
    await expect(page.getByTestId('tab-child-custom-props-json-input-0')).toContainText(
      'initialCurrentDataType',
    );
    await expect(page.getByTestId('tab-child-custom-props-json-input-0')).toContainText(
      'critical',
    );
  });

  test('drags a detail section child block and edits layout behavior settings', async ({ page }) => {
    const { pid, tabsBlockId } = await createTabsChildDropAuthoringPage(page);
    await openDesignerByPid(page, pid);

    await selectCanvasBlock(page, tabsBlockId);
    await expect(page.getByTestId('tab-child-blocks-editor')).toBeVisible();

    await page.getByTestId('tab-filter-key-input').fill('details');
    await page.getByTestId('tab-filter-label-en-input').fill('Details');
    await page.getByTestId('tab-filter-label-zh-input').fill('详情');

    const beforeTopLevelIds = await canvasBlockIds(page);
    await dragPaletteBlockToTabChildDropZone(page, 'detail-section');

    await expect(page.getByTestId('tab-child-block-0')).toBeVisible();
    await expect(page.getByTestId('tab-child-block-0')).toContainText('detail-section');
    await page.getByTestId('tab-child-title-en-input-0').fill('Nested detail section');
    await page.getByTestId('tab-child-title-zh-input-0').fill('嵌套详情区段');
    await page.getByTestId('tab-child-visible-input-0').fill('{{ record.status == "OPEN" }}');
    await page.getByTestId('tab-child-span-select-0').selectOption('6');
    await page.getByTestId('tab-child-section-columns-select-0').selectOption('3');
    await page.getByTestId('tab-child-section-gutter-select-0').selectOption('24');
    await expect(page.getByTestId('tab-child-section-collapsible-switch-0')).toHaveAttribute(
      'aria-checked',
      'false',
    );
    await page.getByTestId('tab-child-section-collapsible-switch-0').click();
    await expect(page.getByTestId('tab-child-section-collapsible-switch-0')).toHaveAttribute(
      'aria-checked',
      'true',
    );
    await expect(page.getByTestId('tab-child-section-default-collapsed-switch-0')).toBeVisible();
    await page.getByTestId('tab-child-section-default-collapsed-switch-0').click();
    await expect.poll(() => canvasBlockIds(page)).toEqual(beforeTopLevelIds);

    await saveDesignerAndWait(page, pid);
    const savedPage = await fetchPageByPid(page, pid);
    const savedBlock = savedBlockById(savedPage, tabsBlockId);
    expect(savedBlock).toMatchObject({
      blockType: 'tabs',
      tabs: [
        {
          key: 'details',
          label: { 'en-US': 'Details', 'zh-CN': '详情' },
          filter: null,
          blocks: [
            {
              blockType: 'detail-section',
              title: { 'en-US': 'Nested detail section', 'zh-CN': '嵌套详情区段' },
              visible: '{{ record.status == "OPEN" }}',
              span: 6,
              props: {
                columns: 3,
                gutter: 24,
              },
              collapsible: true,
              defaultCollapsed: true,
            },
          ],
        },
      ],
    });

    await openDesignerByPid(page, pid);
    await selectCanvasBlock(page, tabsBlockId);
    await expect(page.getByTestId('tab-child-title-en-input-0')).toHaveValue(
      'Nested detail section',
    );
    await expect(page.getByTestId('tab-child-title-zh-input-0')).toHaveValue('嵌套详情区段');
    await expect(page.getByTestId('tab-child-visible-input-0')).toHaveValue(
      '{{ record.status == "OPEN" }}',
    );
    await expect(page.getByTestId('tab-child-span-select-0')).toHaveValue('6');
    await expect(page.getByTestId('tab-child-section-columns-select-0')).toHaveValue('3');
    await expect(page.getByTestId('tab-child-section-gutter-select-0')).toHaveValue('24');
    await expect(page.getByTestId('tab-child-section-collapsible-switch-0')).toHaveAttribute(
      'aria-checked',
      'true',
    );
    await expect(
      page.getByTestId('tab-child-section-default-collapsed-switch-0'),
    ).toHaveAttribute('aria-checked', 'true');
  });

  test('drags a form buttons child block and edits layout settings', async ({ page }) => {
    const { pid, tabsBlockId } = await createTabsChildDropAuthoringPage(page);
    await openDesignerByPid(page, pid);

    await selectCanvasBlock(page, tabsBlockId);
    await expect(page.getByTestId('tab-child-blocks-editor')).toBeVisible();

    await page.getByTestId('tab-filter-key-input').fill('actions');
    await page.getByTestId('tab-filter-label-en-input').fill('Actions');
    await page.getByTestId('tab-filter-label-zh-input').fill('操作');

    const beforeTopLevelIds = await canvasBlockIds(page);
    await dragPaletteBlockToTabChildDropZone(page, 'form-buttons');

    await expect(page.getByTestId('tab-child-block-0')).toBeVisible();
    await expect(page.getByTestId('tab-child-block-0')).toContainText('form-buttons');
    await page.getByTestId('tab-child-title-en-input-0').fill('Nested footer actions');
    await page.getByTestId('tab-child-title-zh-input-0').fill('嵌套底部操作');
    await page.getByTestId('tab-child-visible-input-0').fill('{{ record.status != "CLOSED" }}');
    await page.getByTestId('tab-child-span-select-0').selectOption('8');
    await page.getByTestId('tab-child-button-align-select-0').selectOption('right');
    await expect(page.getByTestId('tab-child-button-align-select-0')).toHaveValue('right');
    await expect(page.getByTestId('tab-child-button-actions-0')).toContainText(
      'No configured buttons',
    );
    await expect(page.getByTestId('tab-child-button-add-0')).toBeEnabled();
    await page.getByTestId('tab-child-button-add-0').click();
    await expect(page.getByTestId('tab-child-button-0-0')).toBeVisible();
    await page.getByTestId('tab-child-button-code-input-0-0').fill('submit_review');
    await page.getByTestId('tab-child-button-label-en-input-0-0').fill('Submit review');
    await page.getByTestId('tab-child-button-label-zh-input-0-0').fill('提交审核');
    await page
      .getByTestId('tab-child-button-action-command-input-0-0')
      .fill('pgm:update_page_schema');
    await page.getByTestId('tab-child-button-primary-checkbox-0-0').click();
    await page.getByTestId('tab-child-button-danger-checkbox-0-0').click();
    await expect.poll(() => canvasBlockIds(page)).toEqual(beforeTopLevelIds);

    await saveDesignerAndWait(page, pid);
    const savedPage = await fetchPageByPid(page, pid);
    const savedBlock = savedBlockById(savedPage, tabsBlockId);
    expect(savedBlock).toMatchObject({
      blockType: 'tabs',
      tabs: [
        {
          key: 'actions',
          label: { 'en-US': 'Actions', 'zh-CN': '操作' },
          filter: null,
          blocks: [
            {
              blockType: 'form-buttons',
              title: { 'en-US': 'Nested footer actions', 'zh-CN': '嵌套底部操作' },
              visible: '{{ record.status != "CLOSED" }}',
              span: 8,
              buttons: [
                {
                  code: 'submit_review',
                  label: { 'en-US': 'Submit review', 'zh-CN': '提交审核' },
                  action: { type: 'command', command: 'pgm:update_page_schema' },
                  primary: true,
                  danger: true,
                },
              ],
              props: { align: 'right' },
            },
          ],
        },
      ],
    });

    await openDesignerByPid(page, pid);
    await selectCanvasBlock(page, tabsBlockId);
    await expect(page.getByTestId('tab-child-title-en-input-0')).toHaveValue(
      'Nested footer actions',
    );
    await expect(page.getByTestId('tab-child-title-zh-input-0')).toHaveValue('嵌套底部操作');
    await expect(page.getByTestId('tab-child-visible-input-0')).toHaveValue(
      '{{ record.status != "CLOSED" }}',
    );
    await expect(page.getByTestId('tab-child-span-select-0')).toHaveValue('8');
    await expect(page.getByTestId('tab-child-button-align-select-0')).toHaveValue('right');
    await expect(page.getByTestId('tab-child-button-code-input-0-0')).toHaveValue(
      'submit_review',
    );
    await expect(page.getByTestId('tab-child-button-label-en-input-0-0')).toHaveValue(
      'Submit review',
    );
    await expect(page.getByTestId('tab-child-button-label-zh-input-0-0')).toHaveValue('提交审核');
    await expect(page.getByTestId('tab-child-button-action-command-input-0-0')).toHaveValue(
      'pgm:update_page_schema',
    );
    await expect(page.getByTestId('tab-child-button-primary-checkbox-0-0')).toBeChecked();
    await expect(page.getByTestId('tab-child-button-danger-checkbox-0-0')).toBeChecked();
  });
});
