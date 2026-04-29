/**
 * GA showcase SavedView deep coverage.
 *
 * Focuses on cross-layer persistence that regular showcase specs only sample:
 * UI create/configure -> SavedView API persistence -> reload -> runtime renderer.
 */

import { expect, test, type APIRequestContext, type Page } from '@playwright/test';

const MODEL_CODE = 'showcase_all_fields';
// Runtime ListPageContent scopes SavedViews by route tableName, not DSL pageKey.
const PAGE_KEY = 'showcase_all_fields';
const LIST_URL = '/p/showcase_all_fields';

let createdViewPids: string[] = [];
let defaultViewRestorePids: Array<string | null> = [];

type SavedViewRecord = {
  pid: string;
  viewType?: string;
  viewConfig?: Record<string, unknown>;
};

type SeededShowcaseRecord = {
  pid: string;
  scName: string;
};

test.describe.configure({ mode: 'serial' });

async function navigateToShowcaseListViaMenu(page: Page): Promise<void> {
  await page.addInitScript(() => localStorage.removeItem('sidebar-collapsed'));
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' }).catch(() => {});

  const listResponse = page
    .waitForResponse(
      (r) => r.url().includes(`/api/dynamic/${MODEL_CODE}/list`) && r.status() === 200,
      { timeout: 15_000 },
    )
    .catch(() => null);
  const firstRow = page.locator('[data-testid="dynamic-list"] table tbody tr').first();

  const parent = page
    .locator('button, [role="menuitem"]', {
      hasText: /字段展示|能力展示|Field Showcase|Showcase|menu\.sc_root/i,
    })
    .first();
  await parent.waitFor({ state: 'visible', timeout: 10_000 });
  await parent.evaluate((el: HTMLElement) => el.click());

  const leaf = page.locator(`a[href="${LIST_URL}"], a[href*="${LIST_URL}"]`).first();
  await leaf.waitFor({ state: 'attached', timeout: 5_000 });
  await leaf.evaluate((el: HTMLElement) => el.click());

  await expect(page).toHaveURL(new RegExp(`${LIST_URL}(?:$|\\?)`), { timeout: 10_000 });
  await Promise.race([
    listResponse,
    firstRow.waitFor({ state: 'visible', timeout: 15_000 }).catch(() => null),
  ]);
  await expect(firstRow).toBeVisible({ timeout: 15_000 });
  await page.evaluate(() => {
    document.querySelectorAll('vite-error-overlay').forEach((el) => el.remove());
  });
}

async function openViewManagePanel(page: Page) {
  const viewBtn = page.locator('button[aria-haspopup="listbox"]').first();
  await expect(viewBtn).toBeVisible({ timeout: 10_000 });
  await viewBtn.click();

  const panel = page.locator('[role="dialog"][aria-modal="true"]');
  await expect(panel).toBeVisible({ timeout: 5_000 });
  return panel;
}

async function openTypePicker(page: Page) {
  const newViewBtn = page.getByRole('button', { name: /New View/i });
  await expect(newViewBtn).toBeVisible({ timeout: 5_000 });
  await newViewBtn.click();
  await expect(page.getByText('Choose type')).toBeVisible({ timeout: 5_000 });
}

async function createConfiguredView(
  page: Page,
  viewType: 'kanban' | 'calendar' | 'gallery',
): Promise<{ pid: string; configValues: string[] }> {
  const panel = await openViewManagePanel(page);
  await openTypePicker(page);

  const typeLabel: Record<typeof viewType, string> = {
    kanban: 'Kanban',
    calendar: 'Calendar',
    gallery: 'Gallery',
  };

  const typeButton = panel.locator('.grid.grid-cols-4 button').filter({
    hasText: typeLabel[viewType],
  });
  await expect(typeButton).toBeVisible({ timeout: 5_000 });

  const createResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' && new URL(response.url()).pathname === '/api/views',
    { timeout: 15_000 },
  );
  await typeButton.click();
  const createResponse = await createResponsePromise;
  const createBody = await createResponse
    .json()
    .catch(async () => createResponse.text().catch(() => null));
  expect(
    createResponse.ok(),
    `Create view failed: ${createResponse.status()} ${JSON.stringify(createBody)}`,
  ).toBe(true);
  expect((createBody as { data?: { pid?: string } })?.data?.pid).toBeTruthy();

  await expect(
    panel.getByText(new RegExp(`Configure ${typeLabel[viewType]} View`, 'i')),
  ).toBeVisible({ timeout: 10_000 });

  const selects = panel.locator('select');
  const configSelectCount: Record<typeof viewType, number> = {
    kanban: 2,
    calendar: 2,
    gallery: 2,
  };
  const selectedFieldCount: Record<typeof viewType, number> = {
    kanban: 2,
    calendar: 1,
    gallery: 2,
  };
  await expect(async () => {
    const selectCount = await selects.count();
    expect(selectCount).toBe(configSelectCount[viewType]);
    const optCount = await selects.first().locator('option').count();
    expect(optCount).toBeGreaterThan(1);
  }).toPass({ timeout: 10_000 });

  const configValues: string[] = [];
  for (let index = 0; index < selectedFieldCount[viewType]; index += 1) {
    const select = selects.nth(index);
    await select.selectOption({ index: 1 });
    const selected = await select.inputValue();
    expect(selected).toBeTruthy();
    configValues.push(selected);
  }

  const doneBtn = panel.getByRole('button', { name: /^Done$/ });
  await expect(doneBtn).toBeEnabled();

  const navigation = page.waitForURL(/(?:\?|&)view=/, { timeout: 10_000 });
  await doneBtn.click();
  await navigation;

  const pid = new URL(page.url()).searchParams.get('view');
  expect(pid, 'created SavedView pid should be present in URL').toBeTruthy();
  createdViewPids.push(pid!);

  await panel.waitFor({ state: 'hidden', timeout: 10_000 }).catch(() => {});
  return { pid: pid!, configValues };
}

async function fetchSavedView(request: APIRequestContext, pid: string): Promise<SavedViewRecord> {
  const resp = await request.get(`/api/views/${pid}`);
  expect(resp.ok(), `SavedView fetch failed: ${resp.status()}`).toBe(true);
  const body = await resp.json();
  const record = body?.data ?? body;
  expect(record?.pid, `SavedView payload should contain pid: ${JSON.stringify(body)}`).toBe(pid);
  return record;
}

async function fetchDefaultSavedView(
  request: APIRequestContext,
): Promise<SavedViewRecord | null> {
  const resp = await request.get(`/api/views/default?modelCode=${MODEL_CODE}&pageKey=${PAGE_KEY}`);
  if (!resp.ok()) return null;
  const body = await resp.json();
  const record = body?.data ?? body;
  return record?.pid ? record : null;
}

async function createDefaultTableView(
  request: APIRequestContext,
  label: string,
): Promise<{ pid: string; name: string }> {
  const previousDefault = await fetchDefaultSavedView(request);
  defaultViewRestorePids.push(previousDefault?.pid ?? null);

  const name = `E2E Deep ${label} ${Date.now()} ${Math.random().toString(36).slice(2, 6)}`;
  const resp = await request.post('/api/views', {
    data: {
      name,
      modelCode: MODEL_CODE,
      pageKey: PAGE_KEY,
      scope: 'personal',
      viewType: 'table',
      isDefault: true,
      viewConfig: {},
    },
  });
  const body = await resp.json().catch(async () => resp.text().catch(() => null));
  expect(resp.ok(), `Create default table view failed: ${resp.status()} ${JSON.stringify(body)}`).toBe(true);
  const pid = (body as { data?: { pid?: string } })?.data?.pid;
  expect(pid, `Created default table view missing pid: ${JSON.stringify(body)}`).toBeTruthy();
  createdViewPids.push(pid!);
  return { pid: pid!, name };
}

async function seedShowcaseRecord(
  request: APIRequestContext,
  label: string,
): Promise<SeededShowcaseRecord> {
  const scName = `E2E Deep ${label} ${Date.now()} ${Math.random().toString(36).slice(2, 6)}`;
  const resp = await request.post('/api/meta/commands/execute/sc:create_showcase', {
    data: {
      operationType: 'create',
      payload: {
        sc_name: scName,
        sc_description: 'GA showcase deep SavedView filter seed',
        sc_quantity: 7,
        sc_price: 12.34,
        sc_priority: 'medium',
        sc_category: 'electronics',
      },
    },
  });
  const body = await resp.json().catch(async () => resp.text().catch(() => null));
  expect(resp.ok(), `Create showcase seed failed: ${resp.status()} ${JSON.stringify(body)}`).toBe(true);
  expect((body as { code?: string })?.code, `Create showcase seed non-zero: ${JSON.stringify(body)}`).toBe('0');
  const pid = (body as { data?: { data?: { recordId?: string } } })?.data?.data?.recordId;
  expect(pid, `Created showcase seed missing recordId: ${JSON.stringify(body)}`).toBeTruthy();
  return { pid: pid!, scName };
}

async function cleanupCreatedViews(request: APIRequestContext): Promise<void> {
  while (createdViewPids.length > 0) {
    const pid = createdViewPids.pop()!;
    await request.delete(`/api/views/${pid}`).catch(() => null);
  }
  while (defaultViewRestorePids.length > 0) {
    const pid = defaultViewRestorePids.pop();
    if (pid) {
      await request.post(`/api/views/${pid}/set-default`).catch(() => null);
    }
  }
}

test.describe('GA showcase SavedView deep persistence', () => {
  test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });

  test.afterEach(async ({ request }) => {
    await cleanupCreatedViews(request);
  });

  test('Kanban config persists through SavedView API and reload renderer', async ({
    page,
    request,
  }) => {
    test.setTimeout(90_000);

    await navigateToShowcaseListViaMenu(page);
    const { pid, configValues } = await createConfiguredView(page, 'kanban');

    const savedView = await fetchSavedView(request, pid);
    expect(savedView.viewType).toBe('kanban');
    expect(savedView.viewConfig?.groupByField).toBe(configValues[0]);
    expect(savedView.viewConfig?.titleField).toBe(configValues[1]);

    await page.reload({ waitUntil: 'load' });
    await expect(page).toHaveURL(new RegExp(`(?:\\?|&)view=${pid}(?:&|$)`), { timeout: 10_000 });

    const notConfigured = page.getByText('Kanban not configured');
    const kanbanBoard = page.locator('.flex.gap-4.overflow-x-auto').first();
    await expect
      .poll(
        async () => {
          if (await notConfigured.isVisible().catch(() => false)) return 'not-configured';
          if (await kanbanBoard.isVisible().catch(() => false)) return 'rendered';
          return 'pending';
        },
        { timeout: 10_000 },
      )
      .toBe('rendered');
  });

  test('Calendar config persists through SavedView API and reload renderer', async ({
    page,
    request,
  }) => {
    test.setTimeout(90_000);

    await navigateToShowcaseListViaMenu(page);
    const { pid, configValues } = await createConfiguredView(page, 'calendar');

    const savedView = await fetchSavedView(request, pid);
    expect(savedView.viewType).toBe('calendar');
    expect(savedView.viewConfig?.calendarDateField).toBe(configValues[0]);

    await page.reload({ waitUntil: 'load' });
    await expect(page).toHaveURL(new RegExp(`(?:\\?|&)view=${pid}(?:&|$)`), { timeout: 10_000 });

    const notConfigured = page.getByText('Calendar not configured');
    const calendar = page.locator('.fc').first();
    await expect
      .poll(
        async () => {
          if (await notConfigured.isVisible().catch(() => false)) return 'not-configured';
          if (await calendar.isVisible().catch(() => false)) return 'rendered';
          return 'pending';
        },
        { timeout: 10_000 },
      )
      .toBe('rendered');
  });

  test('Gallery config persists through SavedView API and reload renderer', async ({
    page,
    request,
  }) => {
    test.setTimeout(90_000);

    await navigateToShowcaseListViaMenu(page);
    const { pid, configValues } = await createConfiguredView(page, 'gallery');

    const savedView = await fetchSavedView(request, pid);
    expect(savedView.viewType).toBe('gallery');
    expect(savedView.viewConfig?.galleryImageField).toBe(configValues[0]);
    expect(savedView.viewConfig?.galleryTitleField).toBe(configValues[1]);

    await page.reload({ waitUntil: 'load' });
    await expect(page).toHaveURL(new RegExp(`(?:\\?|&)view=${pid}(?:&|$)`), { timeout: 10_000 });

    const notConfigured = page.getByText('Gallery not configured');
    const galleryView = page.getByTestId('gallery-view');
    await expect
      .poll(
        async () => {
          if (await notConfigured.isVisible().catch(() => false)) return 'not-configured';
          if (await galleryView.isVisible().catch(() => false)) return 'rendered';
          return 'pending';
        },
        { timeout: 10_000 },
      )
      .toBe('rendered');
  });

  test('Table sort config persists through SavedView API and reload renderer', async ({
    page,
    request,
  }) => {
    test.setTimeout(90_000);

    const { pid, name } = await createDefaultTableView(request, 'Sort');

    await navigateToShowcaseListViaMenu(page);
    await expect(page.locator('button[aria-haspopup="listbox"]').first()).toContainText(name, {
      timeout: 10_000,
    });

    const sortUpdate = page.waitForResponse(
      (response) =>
        response.request().method() === 'PUT' &&
        new URL(response.url()).pathname === `/api/views/${pid}` &&
        response.request().postData()?.includes('"sorts"') === true &&
        response.request().postData()?.includes('sc_code') === true,
      { timeout: 10_000 },
    );
    await page.getByTestId('table-header-sort-sc_code').click();
    await expect(
      page.getByTestId('table-header-sc_code').locator('svg path[fill="#2563eb"]').first(),
    ).toBeVisible({ timeout: 5_000 });
    await sortUpdate;

    await expect
      .poll(
        async () => {
          const savedView = await fetchSavedView(request, pid);
          const sorts = savedView.viewConfig?.sorts as Array<{ fieldCode?: string; direction?: string }> | undefined;
          return sorts?.[0];
        },
        { timeout: 10_000 },
      )
      .toEqual({ fieldCode: 'sc_code', direction: 'asc', priority: 0 });

    await page.reload({ waitUntil: 'load' });
    await expect(page.locator('[data-testid="dynamic-list"] table tbody tr').first()).toBeVisible({
      timeout: 15_000,
    });
    await expect(page.locator('button[aria-haspopup="listbox"]').first()).toContainText(name, {
      timeout: 10_000,
    });
    await expect(
      page.getByTestId('table-header-sc_code').locator('svg path[fill="#2563eb"]').first(),
    ).toBeVisible({ timeout: 5_000 });
  });

  test('Table column config persists through SavedView API and reload renderer', async ({
    page,
    request,
  }) => {
    test.setTimeout(90_000);

    const { pid, name } = await createDefaultTableView(request, 'Columns');

    await navigateToShowcaseListViaMenu(page);
    await expect(page.locator('button[aria-haspopup="listbox"]').first()).toContainText(name, {
      timeout: 10_000,
    });
    await expect(page.getByTestId('table-cell-0-sc_price')).toBeVisible({ timeout: 5_000 });

    await page.getByTestId('column-settings-btn').click();
    const panel = page.getByTestId('column-settings-panel');
    await expect(panel).toBeVisible({ timeout: 5_000 });

    const columnUpdate = page.waitForResponse(
      (response) =>
        response.request().method() === 'PUT' &&
        new URL(response.url()).pathname === `/api/views/${pid}` &&
        response.request().postData()?.includes('"columns"') === true &&
        response.request().postData()?.includes('sc_price') === true,
      { timeout: 10_000 },
    );

    const priceVisibleToggle = page.getByTestId('column-settings-visible-sc_price');
    await expect(priceVisibleToggle).toBeChecked();
    await priceVisibleToggle.uncheck();
    await expect(priceVisibleToggle).not.toBeChecked();
    await page.getByTestId('column-settings-width-sc_name').fill('260');
    await expect(priceVisibleToggle).not.toBeChecked();
    await page.getByTestId('column-settings-save').click();
    await columnUpdate;
    await expect(panel).toBeHidden({ timeout: 5_000 });

    await expect
      .poll(
        async () => {
          const savedView = await fetchSavedView(request, pid);
          const columns = savedView.viewConfig?.columns as
            | Array<{ fieldCode?: string; visible?: boolean; width?: number }>
            | undefined;
          const hiddenPrice = columns?.find((col) => col.fieldCode === 'sc_price');
          const resizedName = columns?.find((col) => col.fieldCode === 'sc_name');
          return {
            priceVisible: hiddenPrice?.visible,
            nameWidth: resizedName?.width,
          };
        },
        { timeout: 10_000 },
      )
      .toEqual({ priceVisible: false, nameWidth: 260 });

    await page.reload({ waitUntil: 'load' });
    await expect(page.locator('[data-testid="dynamic-list"] table tbody tr').first()).toBeVisible({
      timeout: 15_000,
    });
    await expect(page.locator('button[aria-haspopup="listbox"]').first()).toContainText(name, {
      timeout: 10_000,
    });
    await expect(page.getByTestId('table-cell-0-sc_name')).toBeVisible({ timeout: 5_000 });
    await expect(page.getByTestId('table-cell-0-sc_price')).toHaveCount(0);
  });

  test('Table toolbar action config persists through SavedView API and reload renderer', async ({
    page,
    request,
  }) => {
    test.setTimeout(90_000);

    const { pid, name } = await createDefaultTableView(request, 'Toolbar');

    await navigateToShowcaseListViaMenu(page);
    await expect(page.locator('button[aria-haspopup="listbox"]').first()).toContainText(name, {
      timeout: 10_000,
    });
    await expect(page.getByTestId('toolbar-btn-create')).toBeVisible({ timeout: 5_000 });

    await page.getByTestId('toolbar-more-menu').click();
    await page.getByTestId('more-menu-configure-buttons').click();
    await expect(page.getByTestId('action-config-panel')).toBeVisible({ timeout: 5_000 });
    await expect(page.getByTestId('action-config-row-create')).toBeVisible({ timeout: 5_000 });
    await expect(page.getByTestId('action-config-row-_export_csv')).toBeVisible({ timeout: 5_000 });

    const toolbarUpdate = page.waitForResponse(
      (response) =>
        response.request().method() === 'PUT' &&
        new URL(response.url()).pathname === `/api/views/${pid}` &&
        response.request().postData()?.includes('"toolbarActions"') === true &&
        response.request().postData()?.includes('create') === true &&
        response.request().postData()?.includes('_export_csv') === true,
      { timeout: 10_000 },
    );

    await page.getByTestId('action-config-pin-create').click();
    await expect(page.getByTestId('action-config-pin-create')).toHaveAttribute(
      'title',
      'Pin to toolbar',
      { timeout: 5_000 },
    );
    await page.getByTestId('action-config-visible-_export_csv').click();
    await expect(page.getByTestId('action-config-visible-_export_csv')).toHaveAttribute(
      'title',
      'Show button',
      { timeout: 5_000 },
    );
    await toolbarUpdate;

    await expect
      .poll(
        async () => {
          const savedView = await fetchSavedView(request, pid);
          const toolbarActions = savedView.viewConfig?.toolbarActions as
            | Array<{ code?: string; visible?: boolean; pinned?: boolean; order?: number }>
            | undefined;
          const create = toolbarActions?.find((item) => item.code === 'create');
          const exportCsv = toolbarActions?.find((item) => item.code === '_export_csv');
          return {
            createVisible: create?.visible,
            createPinned: create?.pinned,
            createOrder: create?.order,
            exportCsvVisible: exportCsv?.visible,
            exportCsvPinned: exportCsv?.pinned,
          };
        },
        { timeout: 10_000 },
      )
      .toEqual({
        createVisible: true,
        createPinned: false,
        createOrder: 0,
        exportCsvVisible: false,
        exportCsvPinned: false,
      });

    await page.getByTestId('action-config-close').click();
    await expect(page.getByTestId('action-config-panel')).toBeHidden({ timeout: 5_000 });
    await expect(page.getByTestId('toolbar-btn-create')).toHaveCount(0, { timeout: 5_000 });
    await page.getByTestId('toolbar-more-menu').click();
    await expect(page.getByTestId('more-menu-action-create')).toBeVisible({ timeout: 5_000 });
    await expect(page.getByTestId('more-menu-export-csv')).toHaveCount(0);

    await page.reload({ waitUntil: 'load' });
    await expect(page.locator('[data-testid="dynamic-list"] table tbody tr').first()).toBeVisible({
      timeout: 15_000,
    });
    await expect(page.locator('button[aria-haspopup="listbox"]').first()).toContainText(name, {
      timeout: 10_000,
    });
    await expect(page.getByTestId('toolbar-btn-create')).toHaveCount(0, { timeout: 5_000 });
    await page.getByTestId('toolbar-more-menu').click();
    await expect(page.getByTestId('more-menu-action-create')).toBeVisible({ timeout: 5_000 });
    await expect(page.getByTestId('more-menu-export-csv')).toHaveCount(0);
  });

  test('Table filter config persists through SavedView API and reload renderer', async ({
    page,
    request,
  }) => {
    test.setTimeout(90_000);

    const { pid, name } = await createDefaultTableView(request, 'Filters');
    const seed = await seedShowcaseRecord(request, 'Filters');

    await navigateToShowcaseListViaMenu(page);
    await expect(page.locator('button[aria-haspopup="listbox"]').first()).toContainText(name, {
      timeout: 10_000,
    });

    await page.getByTestId('filters-toggle').click();
    await expect(page.getByTestId('search-area')).toBeVisible({ timeout: 5_000 });

    const nameFilter = page.getByTestId('field-sc_name').locator('input, textarea').first();
    await expect(nameFilter).toBeVisible({ timeout: 5_000 });
    await nameFilter.fill(seed.scName);

    const filteredList = page.waitForResponse(
      (response) => {
        if (!response.url().includes(`/api/dynamic/${MODEL_CODE}/list`) || response.status() !== 200) {
          return false;
        }
        const filters = new URL(response.url()).searchParams.get('filters') ?? '';
        return filters.includes('sc_name') && filters.includes(seed.scName);
      },
      { timeout: 10_000 },
    );
    await page.getByTestId('filter-search').click();
    await filteredList;
    await expect(page.locator('[data-testid="dynamic-list"] table tbody tr').first()).toContainText(
      seed.scName,
      { timeout: 10_000 },
    );

    const filterUpdate = page.waitForResponse(
      (response) =>
        response.request().method() === 'PUT' &&
        new URL(response.url()).pathname === `/api/views/${pid}` &&
        response.request().postData()?.includes('"filters"') === true &&
        response.request().postData()?.includes(seed.scName) === true,
      { timeout: 10_000 },
    );
    await page.getByTestId('filter-save').click();
    await filterUpdate;

    await expect
      .poll(
        async () => {
          const savedView = await fetchSavedView(request, pid);
          const filters = savedView.viewConfig?.filters as
            | Array<{ fieldCode?: string; operator?: string; value?: unknown }>
            | undefined;
          const filter = filters?.find((item) => item.fieldCode === 'sc_name');
          return filter
            ? {
                fieldCode: filter.fieldCode,
                operator: filter.operator,
                value: filter.value,
              }
            : null;
        },
        { timeout: 10_000 },
      )
      .toEqual({ fieldCode: 'sc_name', operator: 'eq', value: seed.scName });

    const restoredList = page.waitForResponse(
      (response) => {
        if (!response.url().includes(`/api/dynamic/${MODEL_CODE}/list`) || response.status() !== 200) {
          return false;
        }
        const filters = new URL(response.url()).searchParams.get('filters') ?? '';
        return filters.includes('sc_name') && filters.includes(seed.scName);
      },
      { timeout: 15_000 },
    );
    await page.reload({ waitUntil: 'load' });
    await restoredList;
    await expect(page.locator('button[aria-haspopup="listbox"]').first()).toContainText(name, {
      timeout: 10_000,
    });
    await expect(page.locator('[data-testid="dynamic-list"] table tbody tr').first()).toContainText(
      seed.scName,
      { timeout: 10_000 },
    );

    await page.getByTestId('filters-toggle').click();
    await expect(page.getByTestId('field-sc_name').locator('input, textarea').first()).toHaveValue(
      seed.scName,
      { timeout: 5_000 },
    );
  });
});
