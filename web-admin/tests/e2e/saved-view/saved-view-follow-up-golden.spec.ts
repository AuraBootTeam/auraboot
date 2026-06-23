import { test, expect, type Page } from '@playwright/test';
import { uniqueId } from '../helpers';
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
  test('SV-FU-003: saved quick preset opens as a personal view without toolbar preset state', async ({
    page,
  }) => {
    await ensureModifiedThisWeekPresetCopy(page);
    await navigateToOrderViaSidebar(page);
    await expect(page.getByTestId('quick-filters')).toBeVisible({ timeout: 30_000 });
    await expect(page.getByTestId('preset-view-bar')).toHaveCount(0);

    const presetChip = page.getByTestId('quick-filter-modified_this_week');
    await expect(presetChip).not.toHaveAttribute('data-preset-saved', 'true');
    await expect(presetChip).not.toHaveAttribute('data-preset-edited', 'true');
    await expect(page.getByTestId('preset-view-reset-saved')).toHaveCount(0);

    await presetChip.click();
    await expect(presetChip).toHaveAttribute('data-preset-active', 'true', { timeout: 10_000 });
    await expect
      .poll(() => new URL(page.url()).searchParams.get('preset'), { timeout: 10_000 })
      .toBe('modified_this_week');

    await page.getByTestId('preset-view-save-as-personal').click();
    await expect(page).toHaveURL(/view=[^&]+/, { timeout: 10_000 });
    await expect
      .poll(() => new URL(page.url()).searchParams.get('preset'), { timeout: 10_000 })
      .toBeNull();
    await expect(presetChip).toHaveAttribute('data-preset-active', 'false');
    await expect(presetChip).not.toHaveAttribute('data-preset-saved', 'true');
    await expect(presetChip).not.toHaveAttribute('data-preset-edited', 'true');
    await expect(page.getByTestId('preset-view-save-as-personal')).toHaveCount(0);
    await expect(page.getByTestId('preset-view-reset-saved')).toHaveCount(0);
    await page.screenshot({ path: `${SHOTS}/04-preset-saved-as-personal-clean-state.png`, fullPage: true });
  });
});
