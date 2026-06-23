import { test, expect, type Page } from '@playwright/test';
import { uniqueId, waitForDynamicPageLoad } from '../helpers';
import { navigateToOrderViaSidebar } from './helpers';

const ORDER_MODEL = 'e2et_order';
const ORDER_PAGE_KEY = 'e2et_order_list';
const SHOTS = 'test-results/saved-view-follow-up-golden';

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

async function getSavedView(page: Page, pid: string): Promise<any> {
  return apiData<any>(page, 'get', `/api/views/${pid}`);
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

async function ensureModifiedThisWeekPresetCopy(page: Page): Promise<void> {
  const existing = await findPersonalPresetSavedView(page, 'modified_this_week');
  if (existing?.pid) return;

  await apiData(page, 'post', '/api/views', {
    name: `Modified This Week ${uniqueId('preset')}`,
    modelCode: ORDER_MODEL,
    pageKey: ORDER_PAGE_KEY,
    scope: 'personal',
    viewType: 'table',
    viewConfig: modifiedThisWeekPresetConfig(),
  });
}

test.describe('SavedView Personal-only follow-up golden coverage', () => {
  test('SV-FU-003: quick preset saved copy shows saved, edited, and reset states', async ({
    page,
  }) => {
    await ensureModifiedThisWeekPresetCopy(page);
    await navigateToOrderViaSidebar(page);
    await expect(page.getByTestId('quick-filters')).toBeVisible({ timeout: 30_000 });
    await expect(page.getByTestId('preset-view-bar')).toHaveCount(0);

    const presetChip = page.getByTestId('quick-filter-modified_this_week');
    await expect(presetChip).toHaveAttribute('data-preset-saved', 'true', { timeout: 10_000 });
    await presetChip.click();
    await page.getByTestId('preset-view-save-as-personal').click();
    await expect(page).toHaveURL(/view=[^&]+/, { timeout: 10_000 });

    const viewPid = new URL(page.url()).searchParams.get('view');
    expect(viewPid).toBeTruthy();
    await expect(presetChip).toHaveAttribute('data-preset-saved', 'true');

    const currentView = await getSavedView(page, viewPid!);
    await apiData(page, 'put', `/api/views/${viewPid}`, {
      viewConfig: {
        ...(currentView.viewConfig ?? {}),
        filters: [
          {
            fieldCode: 'e2et_order_title',
            operator: 'eq',
            value: `edited-${uniqueId('preset')}`,
          },
        ],
        meta: {
          ...(currentView.viewConfig?.meta ?? {}),
          managedBy: 'user',
          originPresetKey: 'modified_this_week',
        },
      },
    });

    await page.reload({ waitUntil: 'domcontentloaded' });
    await waitForDynamicPageLoad(page);
    await expect(presetChip).toHaveAttribute('data-preset-edited', 'true');
    await expect(page.getByTestId('preset-view-reset-saved')).toBeVisible();
    await page.screenshot({ path: `${SHOTS}/04-preset-edited-state.png`, fullPage: true });

    const resetResponse = page.waitForResponse(
      (resp) => resp.request().method() === 'PUT' && resp.url().includes(`/api/views/${viewPid}`),
      { timeout: 10_000 },
    );
    await page.getByTestId('preset-view-reset-saved').click();
    await expect((await resetResponse).ok()).toBeTruthy();
    await expect(presetChip).toHaveAttribute('data-preset-edited', 'false');
    await expect(page.getByTestId('preset-view-reset-saved')).toHaveCount(0);

    const resetView = await getSavedView(page, viewPid!);
    expect(resetView.viewConfig?.filters?.some((filter: any) => filter.fieldCode === 'updated_at')).toBe(true);
    expect(resetView.viewConfig?.filters?.some((filter: any) => filter.fieldCode === 'e2et_order_title')).toBe(false);
    await page.screenshot({ path: `${SHOTS}/05-preset-reset-state.png`, fullPage: true });
  });
});
