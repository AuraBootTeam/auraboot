import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import { uniqueId } from '../helpers';

const STANDARD_LIST_BLOCK_TYPES = [
  'filters',
  'toolbar',
  'table',
  'text',
  'stat-card',
  'chart-card',
  'selection-info',
  'detail-section',
] as const;

const STANDARD_FORM_BLOCK_TYPES = ['form-section', 'form-buttons'] as const;

type PageKind = 'list' | 'form';

async function publishPage(page: Page, payload: Record<string, unknown>) {
  const createResp = await page.request.post('/api/pages', { data: payload });
  expect(createResp.ok(), `Create standard block page failed: ${createResp.status()}`).toBeTruthy();
  const createBody = await createResp.json();
  expect(createBody.code, 'create page API code').toBe('0');
  const pid = String(createBody.data?.pid || '');
  expect(pid, 'created page pid').toBeTruthy();

  const updateResp = await page.request.put(`/api/pages/${pid}`, { data: payload });
  expect(updateResp.ok(), `Update standard block page failed: ${updateResp.status()}`).toBeTruthy();
  const updateBody = await updateResp.json();
  expect(updateBody.code, 'update page API code').toBe('0');

  const publishResp = await page.request.post(`/api/pages/${pid}/publish`);
  expect(publishResp.ok(), `Publish standard block page failed: ${publishResp.status()}`).toBeTruthy();
  const publishBody = await publishResp.json();
  expect(publishBody.code, 'publish page API code').toBe('0');
  expect(publishBody.data?.status, 'published page status').toBe('published');

  return pid;
}

function buildPageBase(kind: PageKind, pageKey: string, title: string) {
  return {
    name: title,
    pageKey,
    title,
    kind,
    modelCode: 'page_schema',
    profile: 'admin',
    layout: { type: 'stack', gap: 12 },
    schemaVersion: 4,
    metaInfo: { runtimeE2E: true, standardBlocks: true },
    semver: '0.1.0',
  };
}

async function createPublishedStandardListPage(page: Page) {
  const id = uniqueId('standard_runtime_list');
  const pageKey = id.replace(/-/g, '_');
  const title = `Standard list runtime ${id}`;
  const blocks = [
    {
      id: 'std_filters',
      blockType: 'filters',
      fields: [
        {
          field: 'name',
          label: 'Runtime page name',
          component: 'SmartInput',
          layout: { colSpan: 4 },
        },
      ],
    },
    {
      id: 'std_toolbar',
      blockType: 'toolbar',
      buttons: [
        {
          code: 'runtime_ping',
          label: 'Runtime ping',
          variant: 'primary',
        },
      ],
    },
    {
      id: 'std_select_action',
      blockType: 'workbench-action-bar',
      surface: 'bare',
      align: 'start',
      actions: [
        {
          code: 'select_sample',
          label: 'Select runtime row',
          variant: 'secondary',
          onClick: {
            action: 'state.set',
            args: {
              selectedRows: [{ pid: 'SEL-1', title: 'Selected runtime row' }],
            },
          },
        },
      ],
    },
    {
      id: 'std_selection',
      blockType: 'selection-info',
      title: 'Selection runtime',
      selection: { bind: 'selectedRows' },
    },
    {
      id: 'std_text',
      blockType: 'text',
      props: {
        content: '<strong>Runtime text alias rendered</strong>',
      },
    },
    {
      id: 'std_stat',
      blockType: 'stat-card',
      title: 'Runtime stat card',
      dataSource: 'ds_stats',
      props: {
        valueField: 'total',
        suffix: 'items',
        trend: '+12%',
        trendDirection: 'up',
      },
    },
    {
      id: 'std_chart',
      blockType: 'chart-card',
      title: 'Runtime chart alias',
      props: {
        chartType: 'bar',
        xField: 'stage',
        yField: 'count',
      },
      chartConfig: {
        dataSource: {
          type: 'static',
          staticData: [
            { stage: 'Draft', count: 2 },
            { stage: 'Published', count: 1 },
          ],
          dimensions: ['stage'],
          metrics: [{ field: 'count', aggregation: 'sum', alias: 'count' }],
        },
      },
    },
    {
      id: 'std_detail',
      blockType: 'detail-section',
      title: 'Runtime detail section',
      fields: [
        {
          field: 'name',
          label: 'Runtime detail name',
          component: 'SmartInput',
          layout: { colSpan: 6 },
        },
      ],
    },
    {
      id: 'std_table',
      blockType: 'table',
      table: {
        columns: [
          { field: 'name', label: 'Name', width: 260 },
          { field: 'page_key', label: 'Page Key', width: 220 },
          { field: 'status', label: 'Status', width: 160 },
        ],
        selection: true,
        pagination: { pageSize: 10 },
        rowActions: [
          {
            code: 'runtime_inspect',
            label: 'Inspect row',
            action: { type: 'navigate', to: `/p/c/${pageKey}?runtimeRow={pid}` },
          },
        ],
      },
      columns: [
        { field: 'name', label: 'Name', width: 260 },
        { field: 'page_key', label: 'Page Key', width: 220 },
        { field: 'status', label: 'Status', width: 160 },
      ],
    },
  ];
  const payload = {
    ...buildPageBase('list', pageKey, title),
    blocks,
    dataSources: {
      ds_stats: {
        type: 'static',
        adaptor: 'records',
        data: [{ total: 42 }],
      },
    },
    extension: {
      skipFieldMeta: true,
      hideSavedViews: true,
      hideQuickFilters: true,
      hideSort: true,
      hideColumnSettings: true,
      hideRowHeight: true,
      hideFilterChips: true,
      miscBlocksPosition: 'beforeTable',
    },
  };

  const pid = await publishPage(page, payload);
  return { pageKey, title, blocks, pid };
}

async function createPublishedStandardFormPage(page: Page) {
  const id = uniqueId('standard_runtime_form');
  const pageKey = id.replace(/-/g, '_');
  const blocks = [
    {
      id: 'std_form_section',
      blockType: 'form-section',
      title: 'Runtime form section',
      fields: [
        {
          field: 'name',
          label: 'Runtime name',
          component: 'SmartInput',
          required: true,
          layout: { colSpan: 6 },
        },
        {
          field: 'page_key',
          label: 'Runtime page key',
          component: 'SmartInput',
          layout: { colSpan: 6 },
        },
      ],
    },
    {
      id: 'std_form_buttons',
      blockType: 'form-buttons',
      buttons: [
        {
          code: 'submit',
          label: 'Runtime save',
          primary: true,
        },
        {
          code: 'cancel',
          label: 'Runtime cancel',
          navigateTo: '/p/page_schema',
        },
      ],
    },
  ];
  const payload = {
    ...buildPageBase('form', pageKey, `Standard form runtime ${id}`),
    blocks,
    dataSources: {},
    extension: {
      afterSubmitRedirect: `/p/c/${pageKey}`,
    },
  };

  await publishPage(page, payload);
  return { pageKey, blocks };
}

test.describe('Page Designer standard block runtime', () => {
  test('persists and renders list-oriented standard blocks in a real custom page', async ({
    page,
  }) => {
    const { pageKey } = await createPublishedStandardListPage(page);

    const readbackResp = await page.request.get(`/api/pages/key/${pageKey}`);
    expect(readbackResp.ok(), `Readback standard list page failed: ${readbackResp.status()}`).toBeTruthy();
    const readback = await readbackResp.json();
    expect(readback.code, 'readback API code').toBe('0');
    expect(readback.data?.status, 'readback page status').toBe('published');
    expect(readback.data?.extension?.skipFieldMeta, 'skip field metadata runtime flag').toBe(true);
    const persistedTypes = (readback.data?.blocks || []).map((block: any) => block.blockType);
    expect(persistedTypes, 'all standard list block types persisted').toEqual(
      expect.arrayContaining([...STANDARD_LIST_BLOCK_TYPES, 'workbench-action-bar']),
    );
    expect(Object.keys(readback.data?.dataSources || {}), 'stat data source persisted').toEqual([
      'ds_stats',
    ]);

    await page.goto(`/p/c/${pageKey}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('dynamic-list')).toBeVisible({ timeout: 15_000 });

    await expect(page.getByTestId('filters-toggle')).toBeVisible();
    await page.getByTestId('filters-toggle').click();
    await expect(page.getByTestId('search-area')).toBeVisible();
    await expect(page.getByTestId('search-area').getByTestId('field-name')).toContainText(
      'Runtime page name',
    );
    await expect(page.getByTestId('filter-search')).toBeVisible();
    await expect(page.getByTestId('filter-reset')).toBeVisible();

    await expect(page.getByTestId('toolbar-btn-runtime_ping')).toBeVisible();
    await expect(page.getByTestId('list-misc-blocks')).toBeVisible();
    await expect(page.locator('.block-text')).toContainText('Runtime text alias rendered');

    await expect(page.getByTestId('stat-card-block')).toContainText('Runtime stat card');
    await expect(page.getByTestId('stat-card-value')).toHaveText('42');
    await expect(page.getByTestId('stat-card-block')).toContainText('items');
    await expect(page.getByTestId('stat-card-trend')).toHaveText('+12%');

    const chartBlock = page.locator('.block-chart-card');
    await expect(chartBlock).toBeVisible();
    await expect(chartBlock).not.toContainText('Unknown block type');
    await expect(chartBlock).not.toContainText('Unsupported chart type');
    await expect(chartBlock.locator('canvas, svg').first()).toBeVisible({ timeout: 10_000 });

    await expect(page.locator('.block-detail-section')).toContainText('Runtime detail section');
    await expect(page.locator('.block-detail-section')).toContainText('Runtime detail name');

    await expect(page.getByTestId('selection-info-title')).toHaveText('Selection runtime');
    await expect(page.getByTestId('selection-info-count')).toHaveText('0');
    await page.getByTestId('workbench-action-select_sample').click();
    await expect(page.getByTestId('selection-info-count')).toHaveText('1');
    await expect(page.getByTestId('selection-info-label')).toHaveText('Selected runtime row');

    await expect(page.getByTestId('ab:list:page_schema:table')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByTestId('table-cell-0-name')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByTestId('table-cell-0-status')).toBeVisible();
    await expect(page.getByTestId('table-cell-0-actions')).toBeVisible();
    await expect(page.locator('body')).not.toContainText('Unknown block type');
  });

  test('table runtime supports search, row action navigation, and bulk toolbar actions', async ({
    page,
  }) => {
    const { pageKey, title, pid } = await createPublishedStandardListPage(page);

    await page.goto(`/p/c/${pageKey}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('dynamic-list')).toBeVisible({ timeout: 15_000 });

    const searchResponse = page.waitForResponse(
      (response) =>
        response.url().includes('/api/dynamic/page_schema/list') && response.status() === 200,
      { timeout: 15_000 },
    );
    await page.getByTestId('list-search-input').fill(pageKey);
    await page.getByTestId('list-search-input').press('Enter');
    await searchResponse;

    await expect(page.getByTestId('table-row-0')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByTestId('table-cell-0-name')).toContainText(title);
    await expect(page.getByTestId('table-cell-0-page_key')).toContainText(pageKey);

    await page.getByTestId('table-row-0').hover();
    await expect(page.getByTestId('row-action-runtime_inspect')).toBeVisible();
    await page.getByTestId('row-action-runtime_inspect').click();
    await expect(page).toHaveURL(new RegExp(`/p/c/${pageKey}\\?runtimeRow=${pid}`));

    await page.goto(`/p/c/${pageKey}?keyword=${encodeURIComponent(pageKey)}`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(page.getByTestId('table-row-0')).toBeVisible({ timeout: 15_000 });
    await page.getByTestId('row-checkbox-0').check();
    await expect(page.getByRole('button', { name: 'Edit' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Delete' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Clear selection' })).toBeVisible();

    await page.getByRole('button', { name: 'Edit' }).click();
    await expect(page.getByRole('heading', { name: 'Bulk Edit' })).toBeVisible();
    await expect(page.getByText('Update 1 selected records')).toBeVisible();
    await expect(page.getByRole('combobox')).toBeVisible();
    await page.getByRole('button', { name: 'Cancel' }).click();
    await expect(page.getByRole('heading', { name: 'Bulk Edit' })).toHaveCount(0);

    await page.getByRole('button', { name: 'Clear selection' }).click();
    await expect(page.getByRole('button', { name: 'Edit' })).toHaveCount(0);
  });

  test('persists and renders form-section and form-buttons in a real custom form page', async ({
    page,
  }) => {
    const { pageKey } = await createPublishedStandardFormPage(page);

    const readbackResp = await page.request.get(`/api/pages/key/${pageKey}`);
    expect(readbackResp.ok(), `Readback standard form page failed: ${readbackResp.status()}`).toBeTruthy();
    const readback = await readbackResp.json();
    expect(readback.code, 'readback API code').toBe('0');
    expect(readback.data?.kind, 'readback page kind').toBe('form');
    const persistedTypes = (readback.data?.blocks || []).map((block: any) => block.blockType);
    expect(persistedTypes, 'all standard form block types persisted').toEqual(
      expect.arrayContaining([...STANDARD_FORM_BLOCK_TYPES]),
    );

    await page.goto(`/p/c/${pageKey}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('dynamic-form')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByTestId('dynamic-form')).toContainText('Runtime form section');
    await expect(page.getByTestId('form-field-name')).toContainText('Runtime name');
    await expect(page.getByTestId('form-field-page_key')).toContainText('Runtime page key');
    await expect(page.getByTestId('field-name')).toBeVisible();
    await expect(page.getByTestId('field-page_key')).toBeVisible();
    await expect(page.getByTestId('form-btn-submit')).toHaveText('Runtime save');
    await expect(page.getByTestId('form-btn-cancel')).toHaveText('Runtime cancel');
    await page.getByTestId('form-btn-cancel').click();
    await expect(page).toHaveURL(/\/p\/page_schema/);
    await expect(page.locator('body')).not.toContainText('Unknown block type');
  });
});
