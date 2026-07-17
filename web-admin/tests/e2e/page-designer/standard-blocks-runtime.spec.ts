import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import { executeCommandViaApi, uniqueId } from '../helpers';

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

async function createPublishedCustomBlockListPage(page: Page) {
  const id = uniqueId('standard_runtime_custom');
  const pageKey = id.replace(/-/g, '_');
  const title = `Standard custom runtime ${id}`;
  const blocks = [
    {
      id: 'custom_field_impact',
      blockType: 'custom',
      component: 'decision-field-impact',
      props: {
        initialCurrentDataType: 'string',
      },
    },
    {
      id: 'custom_hidden',
      blockType: 'custom',
      component: 'DecisionFieldImpactBlock',
      visibleWhen: '${false}',
    },
    {
      id: 'custom_unknown_component',
      blockType: 'custom',
      component: 'E2EMissingCustomRuntimeBlock',
    },
  ];
  const payload = {
    ...buildPageBase('list', pageKey, title),
    blocks,
    dataSources: {},
    extension: {
      customOnly: true,
      skipFieldMeta: true,
      hideSavedViews: true,
      hideQuickFilters: true,
      hideSort: true,
      hideColumnSettings: true,
      hideRowHeight: true,
      hideFilterChips: true,
    },
  };

  await publishPage(page, payload);
  return { pageKey, title, blocks };
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
        {
          field: 'kind',
          label: 'Runtime kind',
          component: 'SmartSelect',
          defaultValue: 'list',
          layout: { colSpan: 4 },
        },
        {
          field: 'model_code',
          label: 'Runtime model code',
          component: 'SmartInput',
          defaultValue: 'page_schema',
          layout: { colSpan: 4 },
        },
        {
          field: 'profile',
          label: 'Runtime profile',
          component: 'SmartInput',
          defaultValue: 'admin',
          layout: { colSpan: 4 },
        },
        {
          field: 'runtime_readonly_note',
          label: 'Runtime readonly note',
          component: 'SmartInput',
          defaultValue: 'Read only seed',
          readOnly: true,
          layout: { colSpan: 6 },
        },
        {
          field: 'runtime_conditional_note',
          label: 'Runtime conditional note',
          component: 'SmartInput',
          visibleWhen: '${form.name === "show-extra"}',
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
          action: { type: 'command', command: 'pgm:create_page_schema' },
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

async function fillPageSchemaForm(
  page: Page,
  values: {
    name: string;
    pageKey: string;
    kind?: string;
    modelCode?: string;
    profile?: string;
  },
) {
  await page.getByTestId('field-name').locator('input, textarea').first().fill(values.name);
  await page
    .getByTestId('field-page_key')
    .locator('input, textarea')
    .first()
    .fill(values.pageKey);

  if (values.kind) {
    await page.getByTestId('field-kind').locator('select').first().selectOption(values.kind);
  }
  if (values.modelCode) {
    await page
      .getByTestId('field-model_code')
      .locator('input, textarea')
      .first()
      .fill(values.modelCode);
  }
  if (values.profile) {
    const profileInput = page.getByTestId('field-profile').locator('input, textarea').first();
    if (await profileInput.isVisible().catch(() => false)) {
      await profileInput.fill(values.profile);
    } else {
      await expect(profileInput).toHaveValue(values.profile);
    }
  }
}

function extractCommandRecordId(body: Record<string, any>): string {
  const resultData = body?.data?.data ?? body?.data ?? {};
  // pid-only public contract: create commands return recordPid (data-and-api.md §Public Record).
  return String(resultData.recordPid ?? resultData.recordId ?? resultData.pid ?? resultData.id ?? '');
}

async function createE2etOrder(page: Page, title: string): Promise<string> {
  const result = await executeCommandViaApi(page, 'e2et:create_order', {
    e2et_order_title: title,
    e2et_order_type: 'normal',
    e2et_order_urgent: false,
  });
  expect(result.code, 'create e2et_order code').toBe('0');
  expect(result.recordId, 'created e2et_order pid').toBeTruthy();
  return result.recordId;
}

async function deleteE2etOrder(page: Page, orderPid: string): Promise<void> {
  await executeCommandViaApi(
    page,
    'e2et:delete_order',
    {},
    orderPid,
    'delete',
    { allowHttpError: true },
  );
}

async function listOrderItems(page: Page, orderPid: string): Promise<Record<string, any>[]> {
  const filters = JSON.stringify([
    { fieldName: 'e2et_order_id', operator: 'EQ', value: orderPid },
  ]);
  const resp = await page.request.get('/api/dynamic/e2et_order_item/list', {
    params: {
      pageNum: '1',
      pageSize: '20',
      filters,
    },
  });
  expect(resp.ok(), `List e2et_order_item failed: ${resp.status()}`).toBeTruthy();
  const body = await resp.json();
  return body.data?.records ?? [];
}

async function fetchOrder(page: Page, orderPid: string): Promise<Record<string, any>> {
  const resp = await page.request.get(`/api/dynamic/e2et_order/${orderPid}`);
  expect(resp.ok(), `Fetch e2et_order failed: ${resp.status()}`).toBeTruthy();
  const body = await resp.json();
  return body.data ?? {};
}

async function fetchPageByKey(page: Page, pageKey: string): Promise<Record<string, any>> {
  const resp = await page.request.get(`/api/pages/key/${pageKey}`);
  expect(resp.ok(), `Fetch page by key failed: ${resp.status()}`).toBeTruthy();
  const body = await resp.json();
  expect(body.code, 'fetch page by key code').toBe('0');
  return body.data ?? {};
}

async function fetchPageByPid(page: Page, pid: string): Promise<Record<string, any>> {
  const resp = await page.request.get(`/api/pages/${pid}`);
  expect(resp.ok(), `Fetch page by pid failed: ${resp.status()}`).toBeTruthy();
  const body = await resp.json();
  expect(body.code, 'fetch page by pid code').toBe('0');
  return body.data ?? {};
}

async function listPageSchemasByKeyword(
  page: Page,
  keyword: string,
): Promise<Record<string, any>[]> {
  const resp = await page.request.get('/api/dynamic/page_schema/list', {
    params: {
      pageNum: '1',
      pageSize: '20',
      keyword,
    },
  });
  expect(resp.ok(), `List page_schema failed: ${resp.status()}`).toBeTruthy();
  const body = await resp.json();
  expect(body.code, 'list page_schema code').toBe('0');
  return body.data?.records ?? [];
}

async function clickSubTableAdd(page: Page) {
  const emptyAction = page.getByTestId('subtable-empty-action');
  if (await emptyAction.isVisible({ timeout: 2_000 }).catch(() => false)) {
    await emptyAction.click();
    return;
  }
  await page.getByTestId('subtable-add-row').click();
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

  test('custom blocks load registered runtime components and surface unresolved components', async ({
    page,
  }) => {
    const { pageKey, title } = await createPublishedCustomBlockListPage(page);

    const readback = await fetchPageByKey(page, pageKey);
    expect(readback.name, 'custom host page name').toBe(title);
    expect(readback.extension?.customOnly, 'custom-only runtime flag').toBe(true);
    expect(readback.extension?.skipFieldMeta, 'custom host skips model metadata').toBe(true);
    expect(
      (readback.blocks || []).map((block: any) => ({
        id: block.id,
        blockType: block.blockType,
        component: block.component,
        visibleWhen: block.visibleWhen,
      })),
      'custom blocks persisted for runtime host',
    ).toEqual([
      {
        id: 'custom_field_impact',
        blockType: 'custom',
        component: 'decision-field-impact',
        visibleWhen: undefined,
      },
      {
        id: 'custom_hidden',
        blockType: 'custom',
        component: 'DecisionFieldImpactBlock',
        visibleWhen: '${false}',
      },
      {
        id: 'custom_unknown_component',
        blockType: 'custom',
        component: 'E2EMissingCustomRuntimeBlock',
        visibleWhen: undefined,
      },
    ]);

    await page.goto(`/p/c/${pageKey}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('list-misc-blocks')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByTestId('decision-field-impact')).toHaveCount(1, { timeout: 15_000 });
    await expect(page.getByLabel('field-impact-ref')).toBeVisible();
    await expect(page.getByLabel('field-impact-current-type')).toHaveValue('string');
    await expect(page.getByTestId('field-impact-load')).toBeDisabled();
    await expect(page.locator('body')).toContainText('Failed to load E2EMissingCustomRuntimeBlock', {
      timeout: 15_000,
    });
    await expect(page.locator('body')).not.toContainText('Unknown block type');
  });

  test('table runtime supports search, row action navigation, and bulk toolbar actions', async ({
    page,
  }) => {
    const { pageKey, title, pid } = await createPublishedStandardListPage(page);

    await page.goto(`/p/c/${pageKey}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('dynamic-list')).toBeVisible({ timeout: 15_000 });

    const searchResponse = page.waitForResponse(
      (response) => {
        if (!response.url().includes('/api/dynamic/page_schema/list') || response.status() !== 200) {
          return false;
        }
        return new URL(response.url()).searchParams.get('keyword') === pageKey;
      },
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

  test('table bulk edit and delete persist through dynamic batch APIs', async ({ page }) => {
    const { pageKey, pid } = await createPublishedStandardListPage(page);
    const target = await createPublishedStandardListPage(page);
    const editedName = `Bulk edited ${uniqueId('page_schema')}`;

    await page.goto(`/p/c/${pageKey}?keyword=${encodeURIComponent(pageKey)}`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(page.getByTestId('dynamic-list')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByTestId('table-row-0')).toBeVisible({ timeout: 15_000 });
    await page.getByTestId('row-checkbox-0').check();

    const bulkEditResponse = page.waitForResponse(
      (response) =>
        response.url().includes('/api/dynamic/page_schema/batch') &&
        response.request().method() === 'PUT',
      { timeout: 15_000 },
    );
    await page.getByRole('button', { name: 'Edit' }).click();
    await page.getByRole('combobox').selectOption('name');
    await page.getByPlaceholder('Enter new value...').fill(editedName);
    await page.getByRole('button', { name: 'Update 1 Records' }).click();
    expect((await bulkEditResponse).ok(), 'bulk edit response').toBeTruthy();

    await expect
      .poll(async () => String((await fetchPageByKey(page, pageKey)).name ?? ''), {
        timeout: 10_000,
      })
      .toBe(editedName);
    await expect(page.getByRole('heading', { name: 'Bulk Edit' })).toHaveCount(0);
    await expect(page.getByTestId('table-cell-0-name')).toContainText(editedName, {
      timeout: 15_000,
    });

    await page.goto(`/p/c/${pageKey}?keyword=${encodeURIComponent(target.pageKey)}`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(page.getByTestId('table-row-0')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByTestId('table-cell-0-page_key')).toContainText(target.pageKey);
    await page.getByTestId('row-checkbox-0').check();

    const bulkDeleteResponse = page.waitForResponse(
      (response) =>
        response.url().includes('/api/dynamic/page_schema/batch') &&
        response.request().method() === 'DELETE',
      { timeout: 15_000 },
    );
    await page.getByRole('button', { name: 'Delete' }).click();
    await page.getByRole('button', { name: /Confirm|确认/ }).click();
    expect((await bulkDeleteResponse).ok(), 'bulk delete response').toBeTruthy();

    await expect
      .poll(
        async () => {
          const records = await listPageSchemasByKeyword(page, target.pageKey);
          return records.some((record) => String(record.pid) === target.pid);
        },
        { timeout: 10_000 },
      )
      .toBe(false);
    await expect(page.locator('body')).not.toContainText(target.pageKey);

    const hostRecords = await listPageSchemasByKeyword(page, pageKey);
    expect(
      hostRecords.some((record) => String(record.pid) === pid),
      'host page remains after deleting only the target row',
    ).toBe(true);
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

  test('form buttons execute create and update commands with persisted side effects', async ({
    page,
  }) => {
    const { pageKey } = await createPublishedStandardFormPage(page);
    const createdPageKey = uniqueId('form_cmd_page').replace(/-/g, '_');
    const createdName = `Form command ${createdPageKey}`;
    const updatedName = `Updated ${createdName}`;

    await page.goto(`/p/c/${pageKey}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('dynamic-form')).toBeVisible({ timeout: 15_000 });
    await fillPageSchemaForm(page, {
      name: createdName,
      pageKey: createdPageKey,
      kind: 'list',
      modelCode: 'page_schema',
      profile: 'admin',
    });

    const createCommandResponse = page.waitForResponse(
      (response) =>
        response.url().includes('/api/meta/commands/execute/pgm:create_page_schema') &&
        response.request().method() === 'POST',
      { timeout: 15_000 },
    );
    await page.getByTestId('form-btn-submit').click();
    const createResp = await createCommandResponse;
    expect(createResp.ok(), 'form create command response').toBeTruthy();
    const createBody = await createResp.json();
    expect(createBody.code, 'form create command code').toBe('0');
    const createdPid = extractCommandRecordId(createBody);
    expect(createdPid, 'form create command record id').toBeTruthy();

    await expect
      .poll(async () => String((await fetchPageByPid(page, createdPid)).name ?? ''), {
        timeout: 10_000,
      })
      .toBe(createdName);
    expect(String((await fetchPageByPid(page, createdPid)).pageKey ?? '')).toBe(createdPageKey);

    await page.goto(
      `/p/page_schema/edit/${createdPid}?commandCode=${encodeURIComponent('pgm:update_page_schema')}`,
      { waitUntil: 'domcontentloaded' },
    );
    await expect(page.getByTestId('dynamic-form')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByTestId('field-name').locator('input, textarea').first()).toHaveValue(
      createdName,
      { timeout: 15_000 },
    );
    await page.getByTestId('field-name').locator('input, textarea').first().fill(updatedName);

    const updateCommandResponse = page.waitForResponse(
      (response) =>
        response.url().includes('/api/meta/commands/execute/pgm:update_page_schema') &&
        response.request().method() === 'POST',
      { timeout: 15_000 },
    );
    await page.getByTestId('form-btn-submit').click();
    const updateResp = await updateCommandResponse;
    expect(updateResp.ok(), 'form update command response').toBeTruthy();
    const updateBody = await updateResp.json();
    expect(updateBody.code, 'form update command code').toBe('0');

    await expect
      .poll(async () => String((await fetchPageByPid(page, createdPid)).name ?? ''), {
        timeout: 10_000,
      })
      .toBe(updatedName);
  });

  test('form fields enforce required, readonly, and visibleWhen semantics', async ({ page }) => {
    const { pageKey } = await createPublishedStandardFormPage(page);
    const commandRequests: string[] = [];
    page.on('request', (request) => {
      if (request.url().includes('/api/meta/commands/execute/pgm:create_page_schema')) {
        commandRequests.push(request.url());
      }
    });

    await page.goto(`/p/c/${pageKey}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('dynamic-form')).toBeVisible({ timeout: 15_000 });

    const readonlyInput = page
      .getByTestId('field-runtime_readonly_note')
      .locator('input, textarea')
      .first();
    await expect(readonlyInput).toHaveValue('Read only seed');
    await expect(readonlyInput).toHaveJSProperty('readOnly', true);
    await expect(page.getByTestId('field-runtime_conditional_note')).toHaveCount(0);

    await page.getByTestId('form-btn-submit').click();
    await expect(page.getByTestId('field-name')).toContainText(/required|必填|不能为空|请填写/i, {
      timeout: 5_000,
    });
    expect(commandRequests, 'invalid required submit must not execute create command').toHaveLength(
      0,
    );

    await page.getByTestId('field-name').locator('input, textarea').first().fill('show-extra');
    await expect(page.getByTestId('field-runtime_conditional_note')).toBeVisible({
      timeout: 5_000,
    });
  });

  test('sub-table runtime creates, edits, deletes child rows, and updates aggregate state', async ({
    page,
  }) => {
    const orderTitle = `Sub-table runtime ${uniqueId('std_subtable')}`;
    const itemName = `Runtime child ${uniqueId('line')}`;
    const orderPid = await createE2etOrder(page, orderTitle);

    try {
      await page.goto(
        `/p/e2et_order/${orderPid}/edit?commandCode=${encodeURIComponent('e2et:update_order')}`,
        { waitUntil: 'domcontentloaded' },
      );
      await expect(page.getByTestId('dynamic-form')).toBeVisible({ timeout: 15_000 });
      await expect(page.getByRole('heading', { name: /订单明细|Order Items/ })).toBeVisible({
        timeout: 15_000,
      });
      await expect(page.getByTestId('subtable-viewer')).toBeVisible({ timeout: 15_000 });

      await clickSubTableAdd(page);
      await expect(page.getByTestId('subtable-add-form')).toBeVisible({ timeout: 5_000 });
      await page.getByTestId('subtable-add-e2et_item_name').fill(itemName);
      await page.getByTestId('subtable-add-e2et_item_spec').fill('spec_m');
      await page.getByTestId('subtable-add-e2et_item_qty').fill('3');
      await page.getByTestId('subtable-add-e2et_item_price').fill('7');

      const createLineResp = page.waitForResponse(
        (response) =>
          response.url().includes('/api/meta/commands/execute/e2et:create_order_item') &&
          response.request().method() === 'POST',
        { timeout: 15_000 },
      );
      await page.getByTestId('subtable-save-btn').click();
      expect((await createLineResp).ok(), 'create child command response').toBeTruthy();

      await expect(page.getByTestId('subtable-table')).toContainText(itemName, {
        timeout: 15_000,
      });
      await expect(page.getByTestId('subtable-table')).toContainText('21', { timeout: 15_000 });

      const createdItems = await listOrderItems(page, orderPid);
      const createdItem = createdItems.find((item) => item.e2et_item_name === itemName);
      expect(createdItem, 'created sub-table child row persisted').toBeTruthy();
      expect(Number(createdItem?.e2et_item_subtotal), 'created subtotal persisted').toBe(21);

      await expect
        .poll(async () => Number((await fetchOrder(page, orderPid)).e2et_order_amount ?? 0), {
          timeout: 10_000,
        })
        .toBe(21);

      await page.getByTestId('subtable-edit-0').click();
      await page.getByTestId('inline-edit-e2et_item_qty').fill('4');
      const updateLineResp = page.waitForResponse(
        (response) =>
          response.url().includes('/api/meta/commands/execute/e2et:update_order_item') &&
          response.request().method() === 'POST',
        { timeout: 15_000 },
      );
      await page.getByTestId('subtable-edit-save-0').click();
      expect((await updateLineResp).ok(), 'update child command response').toBeTruthy();

      await expect(page.getByTestId('subtable-table')).toContainText('28', { timeout: 15_000 });
      await expect
        .poll(async () => {
          const [item] = await listOrderItems(page, orderPid);
          return Number(item?.e2et_item_subtotal ?? 0);
        }, { timeout: 10_000 })
        .toBe(28);
      await expect
        .poll(async () => Number((await fetchOrder(page, orderPid)).e2et_order_amount ?? 0), {
          timeout: 10_000,
        })
        .toBe(28);

      const deleteLineResp = page.waitForResponse(
        (response) =>
          response.url().includes('/api/meta/commands/execute/e2et:delete_order_item') &&
          response.request().method() === 'POST',
        { timeout: 15_000 },
      );
      await page.getByTestId('subtable-delete-0').click();
      expect((await deleteLineResp).ok(), 'delete child command response').toBeTruthy();

      await expect
        .poll(async () => (await listOrderItems(page, orderPid)).length, { timeout: 10_000 })
        .toBe(0);
      await expect(page.getByTestId('subtable-empty-state')).toBeVisible({ timeout: 15_000 });
    } finally {
      await deleteE2etOrder(page, orderPid);
    }
  });
});
