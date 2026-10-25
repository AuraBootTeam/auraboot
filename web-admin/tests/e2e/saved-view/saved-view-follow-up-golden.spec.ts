import { test, expect, type Page } from '@playwright/test';
import { uniqueId } from '../helpers';
import { navigateToOrderViaSidebar } from './helpers';
import { ModelTestHelper } from '../../helpers/model-test-helper';
import { E2ET_ORDER_CONFIG } from '../../helpers/configs/e2et-order.config';

import { acquireSavedViewLock, releaseSavedViewLock } from './_saved-view-lock';

// Serialize e2et_order saved-view specs — they share the model's per-user view
// state (active view / created views) under the shared admin storageState.
test.beforeAll(async () => { await acquireSavedViewLock('saved-view-follow-up-golden'); });
test.afterAll(() => { releaseSavedViewLock('saved-view-follow-up-golden'); });

const ORDER_MODEL = 'e2et_order';
const ORDER_PAGE_KEY = 'e2et_order_list';

async function apiData<T>(
  page: Page,
  method: 'get' | 'post' | 'put',
  url: string,
  data?: unknown,
): Promise<T> {
  const resp =
    method === 'get'
      ? await page.request.get(url)
      : method === 'post'
        ? await page.request.post(url, { data })
        : await page.request.put(url, { data });
  const text = await resp.text();
  expect(resp.ok(), `${method.toUpperCase()} ${url} failed: ${resp.status()} ${text}`).toBe(true);
  const body = text ? JSON.parse(text) : {};
  const successCodes = new Set(['0', 'SUCCESS', 'OK']);
  if (body?.code != null && !successCodes.has(String(body.code))) {
    throw new Error(`${method.toUpperCase()} ${url} returned ${body.code}: ${body.desc ?? body.message ?? text}`);
  }
  return (body?.data ?? body) as T;
}

function toLocalDateString(d: Date): string {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

function modifiedThisWeekPresetConfig(): Record<string, unknown> {
  const now = new Date();
  const today = toLocalDateString(now);
  const weekAgo = toLocalDateString(new Date(now.getTime() - 7 * 86400000));
  return {
    filters: [
      {
        fieldCode: 'updated_at',
        operator: 'between',
        value: { start: weekAgo, end: `${today}T23:59:59` },
      },
    ],
    meta: {
      managedBy: 'user',
      originPresetKey: 'modified_this_week',
    },
  };
}

async function findPersonalPresetSavedView(page: Page, presetKey: string): Promise<any | null> {
  const views = await apiData<any[]>(
    page,
    'get',
    `/api/views/accessible?modelCode=${ORDER_MODEL}&pageKey=${ORDER_PAGE_KEY}`,
  );
  return (
    views.find(
      (view) =>
        String(view.scope || '').toLowerCase() === 'personal' &&
        view.viewConfig?.meta?.originPresetKey === presetKey,
    ) ?? null
  );
}

async function ensureModifiedThisWeekPresetCopy(page: Page): Promise<{ pid: string; name: string }> {
  const existing = await findPersonalPresetSavedView(page, 'modified_this_week');
  if (existing?.pid) return existing;

  const created = await apiData<{ pid: string; name: string }>(page, 'post', '/api/views', {
    name: `Modified This Week ${uniqueId('preset')}`,
    modelCode: ORDER_MODEL,
    pageKey: ORDER_PAGE_KEY,
    scope: 'personal',
    viewType: 'table',
    viewConfig: modifiedThisWeekPresetConfig(),
  });
  expect(created.pid, 'Preset fixture must have a public ID').toBeTruthy();
  return created;
}

async function expectOwnedOrderRow(page: Page, title: string): Promise<void> {
  const row = page.getByRole('row').filter({ has: page.getByRole('cell', { name: title, exact: true }) });
  await expect(row).toHaveCount(1);
  await expect(row).toContainText(title);
}

async function searchForOwnedOrder(
  page: Page,
  pid: string,
  title: string,
  range: { fieldName: string; operator: string; values: string[] },
): Promise<void> {
  const responsePromise = page.waitForResponse(response => {
    const url = new URL(response.url());
    return url.pathname === '/api/dynamic/e2et_order/list' && url.searchParams.get('keyword') === title;
  });
  const search = page.getByTestId('list-search-input');
  await search.fill(title);
  await search.press('Enter');
  const response = await responsePromise;
  expect(response.ok()).toBe(true);
  const conditions = JSON.parse(new URL(response.url()).searchParams.get('filters') || '[]');
  expect(conditions).toEqual([range]);
  const body = await response.json();
  expect(body.code).toBe('0');
  expect(body.data.records).toHaveLength(1);
  expect(body.data.records[0]).toMatchObject({ pid, e2et_order_title: title });
  await expectOwnedOrderRow(page, title);
}

test.describe('SavedView Personal-only follow-up golden coverage', () => {
  test('SV-FU-003: saved quick preset opens as a personal view without toolbar preset state', async ({
    page,
  }) => {
    const orderTitle = `Saved preset range ${uniqueId('SVFU')}`;
    const order = new ModelTestHelper(page, E2ET_ORDER_CONFIG);
    const orderPid = await order.createViaApi({ e2et_order_title: orderTitle });
    const savedView = await ensureModifiedThisWeekPresetCopy(page);
    const presetChip = page.getByTestId('quick-filter-modified_this_week');
    let releaseViews!: () => void;
    let heldViewReads = 0;
    const viewsReleased = new Promise<void>((resolve) => { releaseViews = resolve; });
    // Preserve the real response while exercising a user choice made before initial hydration.
    await page.route(url => url.pathname === '/api/views/accessible'
      && url.searchParams.get('modelCode') === ORDER_MODEL
      && url.searchParams.get('pageKey') === ORDER_PAGE_KEY, async route => {
      const response = await route.fetch();
      heldViewReads++;
      await viewsReleased;
      await route.fulfill({ response });
    });
    try {
      await navigateToOrderViaSidebar(page);
      await expect(page.getByTestId('quick-filters')).toBeVisible({ timeout: 30_000 });
      await expect(page.getByTestId('preset-view-bar')).toHaveCount(0);
      await expect(presetChip).not.toHaveAttribute('data-preset-saved', 'true');
      await expect(presetChip).not.toHaveAttribute('data-preset-edited', 'true');
      await expect(page.getByTestId('preset-view-reset-saved')).toHaveCount(0);

      await presetChip.click();
      await expect(presetChip).toHaveAttribute('data-preset-active', 'true', { timeout: 10_000 });
      await expect
        .poll(() => new URL(page.url()).searchParams.get('preset'), { timeout: 10_000 })
        .toBe('modified_this_week');
      await expect.poll(() => heldViewReads).toBeGreaterThan(0);
    } finally {
      releaseViews();
    }
    await expect(page.getByTestId('view-selector-trigger')).toBeEnabled();

    await page.getByTestId('preset-view-save-as-personal').click();
    await expect(page).toHaveURL(/view=[^&]+/, { timeout: 10_000 });
    await expect.poll(() => new URL(page.url()).searchParams.get('view')).toBe(savedView.pid);
    await expect
      .poll(() => new URL(page.url()).searchParams.get('preset'), { timeout: 10_000 })
      .toBeNull();
    await expect(presetChip).toHaveAttribute('data-preset-active', 'false');
    await expect(presetChip).not.toHaveAttribute('data-preset-saved', 'true');
    await expect(presetChip).not.toHaveAttribute('data-preset-edited', 'true');
    await expect(page.getByTestId('preset-view-save-as-personal')).toHaveCount(0);
    await expect(page.getByTestId('preset-view-reset-saved')).toHaveCount(0);
    await expect(page.getByTestId('view-selector-trigger')).toContainText(savedView.name);
    const legacyRange = (modifiedThisWeekPresetConfig().filters as Array<{
      value: { start: string; end: string };
    }>)[0].value;
    await searchForOwnedOrder(page, orderPid, orderTitle, {
      fieldName: 'updated_at', operator: 'BETWEEN', values: [legacyRange.start, legacyRange.end],
    });
    await expect(page.getByTestId('list-toolbar')).not.toContainText('{"end"');
    await expect(page.getByTestId('list-toolbar')).not.toContainText('{"start"');
    await page.screenshot({ path: test.info().outputPath('04-preset-saved-as-personal-clean-state.png'), fullPage: true });

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('view-selector-trigger')).toContainText(savedView.name);
    await expect.poll(() => new URL(page.url()).searchParams.get('view')).toBe(savedView.pid);
    await expect.poll(() => new URL(page.url()).searchParams.get('preset')).toBeNull();
    await expect(page.getByTestId('quick-filter-modified_this_week')).toHaveAttribute('data-preset-active', 'false');
    await expect(page.getByTestId('preset-view-save-as-personal')).toHaveCount(0);
    const persisted = await findPersonalPresetSavedView(page, 'modified_this_week');
    expect(persisted?.pid).toBe(savedView.pid);
    expect(persisted?.viewConfig?.meta?.originPresetKey).toBe('modified_this_week');
    expect(persisted?.viewConfig?.filters).toHaveLength(1);
    expect(persisted.viewConfig.filters.map(
      ({ fieldCode, operator, value }: { fieldCode: string; operator: string; value: unknown }) =>
        ({ fieldCode, operator, value }),
    )).toEqual(modifiedThisWeekPresetConfig().filters);
    await expectOwnedOrderRow(page, orderTitle);
    await page.screenshot({ path: test.info().outputPath('05-personal-view-after-reload.png'), fullPage: true });

    await page.getByRole('banner').getByRole('link', { name: /AuraBoot/ }).click();
    await expect(page).toHaveURL(/\/home(?:$|[?#])/);
    await page.goBack();
    await expect(page.getByTestId('view-selector-trigger')).toContainText(savedView.name);
    await expect.poll(() => new URL(page.url()).searchParams.get('view')).toBe(savedView.pid);
    await expect.poll(() => new URL(page.url()).searchParams.get('preset')).toBeNull();
    await expect(page.getByTestId('quick-filter-modified_this_week')).toHaveAttribute('data-preset-active', 'false');
    await expectOwnedOrderRow(page, orderTitle);
    await page.screenshot({ path: test.info().outputPath('06-personal-view-after-back.png'), fullPage: true });
  });

  test('SV-FU-004: a new personal date preset persists scalar bounds and finds the owned row', async ({ page }) => {
    const existing = await findPersonalPresetSavedView(page, 'created_today');
    expect(existing, 'This owned fixture needs no pre-existing created_today personal copy').toBeNull();
    const orderTitle = `New saved preset ${uniqueId('SVFU')}`;
    const order = new ModelTestHelper(page, E2ET_ORDER_CONFIG);
    const orderPid = await order.createViaApi({ e2et_order_title: orderTitle });
    let createdViewPid = '';
    try {
      await navigateToOrderViaSidebar(page);
      await page.getByTestId('quick-filter-created_today').click();
      const createdResponse = page.waitForResponse(response =>
        response.request().method() === 'POST' && new URL(response.url()).pathname === '/api/views');
      await page.getByTestId('preset-view-save-as-personal').click();
      const response = await createdResponse;
      expect(response.ok()).toBe(true);
      const body = await response.json();
      expect(body.code).toBe('0');
      createdViewPid = body.data.pid;
      expect(createdViewPid).toBeTruthy();
      const today = toLocalDateString(new Date());
      const expectedBounds = [today, `${today}T23:59:59`];
      const submitted = response.request().postDataJSON();
      expect(submitted.viewConfig.filters).toEqual([{
        fieldCode: 'created_at', operator: 'between', value: expectedBounds,
      }]);
      await expect.poll(() => new URL(page.url()).searchParams.get('view')).toBe(createdViewPid);
      await expect.poll(() => new URL(page.url()).searchParams.get('preset')).toBeNull();
      await searchForOwnedOrder(page, orderPid, orderTitle, {
        fieldName: 'created_at', operator: 'BETWEEN', values: expectedBounds,
      });
      await page.reload({ waitUntil: 'domcontentloaded' });
      await expect.poll(() => new URL(page.url()).searchParams.get('view')).toBe(createdViewPid);
      await expectOwnedOrderRow(page, orderTitle);
      const persisted = await apiData<any>(page, 'get', `/api/views/${createdViewPid}`);
      expect(persisted.viewConfig.filters).toHaveLength(1);
      expect(persisted.viewConfig.filters[0]).toMatchObject({
        fieldCode: 'created_at', operator: 'between', value: expectedBounds,
      });
      await expect(page.getByTestId('list-toolbar')).not.toContainText('{"start"');
      await page.screenshot({ path: test.info().outputPath('07-new-personal-range-after-reload.png'), fullPage: true });
    } finally {
      if (createdViewPid) {
        const deleted = await page.request.delete(`/api/views/${createdViewPid}`);
        expect(deleted.ok(), 'Only this test-created personal copy is cleaned up').toBe(true);
        expect((await deleted.json()).code).toBe('0');
      }
    }
  });
});
