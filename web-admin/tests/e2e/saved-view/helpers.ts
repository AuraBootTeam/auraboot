import { expect, type Page } from '@playwright/test';
import { waitForDynamicPageLoad } from '../helpers';

export interface CreateOrReuseSavedViewOptions {
  modelCode: string;
  pageKey?: string;
  name: string;
  viewType?: string;
  scope?: string;
  viewConfig?: Record<string, unknown>;
  expectSuccess?: boolean;
}

export interface CreateOrReuseSavedViewResult {
  pid: string;
  reused: boolean;
}

function matchesConfig(viewConfig: Record<string, unknown> | undefined, expected: Record<string, unknown>): boolean {
  return Object.entries(expected).every(([key, value]) => viewConfig?.[key] === value);
}

function matchesSavedView(view: any, options: Required<Pick<CreateOrReuseSavedViewOptions, 'modelCode' | 'name' | 'viewType' | 'scope'>> & CreateOrReuseSavedViewOptions): boolean {
  if (!view || view.modelCode !== options.modelCode) return false;
  if (options.pageKey && view.pageKey && view.pageKey !== options.pageKey) return false;
  if (String(view.viewType || 'table').toLowerCase() !== options.viewType.toLowerCase()) return false;
  if (String(view.scope || 'personal').toLowerCase() !== options.scope.toLowerCase()) return false;
  return matchesConfig(view.viewConfig, options.viewConfig ?? {});
}

async function findReusableSavedView(
  page: Page,
  options: Required<Pick<CreateOrReuseSavedViewOptions, 'modelCode' | 'name' | 'viewType' | 'scope'>> & CreateOrReuseSavedViewOptions,
): Promise<string> {
  const params = new URLSearchParams({ modelCode: options.modelCode });
  if (options.pageKey) {
    params.set('pageKey', options.pageKey);
  }
  const resp = await page.request.get(`/api/views/accessible?${params.toString()}`);
  if (!resp.ok()) return '';
  const body = await resp.json();
  const views = Array.isArray(body.data) ? body.data : Array.isArray(body) ? body : [];
  return views.find((view: any) => matchesSavedView(view, options))?.pid ?? '';
}

export async function createOrReuseSavedView(
  page: Page,
  options: CreateOrReuseSavedViewOptions,
): Promise<CreateOrReuseSavedViewResult> {
  const { expectSuccess = false, ...createOptions } = options;
  const normalized = {
    ...createOptions,
    viewType: createOptions.viewType ?? 'table',
    scope: createOptions.scope ?? 'personal',
    viewConfig: createOptions.viewConfig ?? {},
  };

  if (Object.keys(normalized.viewConfig).length > 0) {
    const existingPid = await findReusableSavedView(page, normalized);
    if (existingPid) {
      console.info(`[saved-view] reuse pid=${existingPid} model=${normalized.modelCode} viewType=${normalized.viewType}`);
      return { pid: existingPid, reused: true };
    }
  }

  const resp = await page.request.post('/api/views', {
    data: normalized,
  });
  if (!resp.ok()) {
    const text = await resp.text();
    const fallbackPid = Object.keys(normalized.viewConfig).length > 0
      ? await findReusableSavedView(page, normalized)
      : '';
    if (fallbackPid) {
      console.info(`[saved-view] reuse-after-create-failed pid=${fallbackPid} model=${normalized.modelCode} viewType=${normalized.viewType} status=${resp.status()} body=${text}`);
      return { pid: fallbackPid, reused: true };
    }
    if (expectSuccess) {
      throw new Error(
        `[saved-view] create failed model=${normalized.modelCode} pageKey=${normalized.pageKey ?? ''} viewType=${normalized.viewType} status=${resp.status()} body=${text}`,
      );
    }
    return { pid: '', reused: false };
  }

  const body = await resp.json();
  const pid = body.data?.pid ?? body.data?.view?.pid ?? body.pid ?? '';
  if (!pid && expectSuccess) {
    throw new Error(`[saved-view] create returned no pid model=${normalized.modelCode} viewType=${normalized.viewType} body=${JSON.stringify(body)}`);
  }
  return { pid, reused: false };
}

export async function navigateToOrderViaSidebar(page: Page): Promise<void> {
  await page.goto('/');
  const nav = page.locator('nav, aside, [data-testid="sidebar"], [role="navigation"]').first();
  await expect(nav).toBeVisible({ timeout: 15000 });

  const orderLink = nav
    .locator('a[href="/p/e2et_order"], a[href^="/p/e2et_order?"]')
    .first();
  await expect(orderLink).toBeVisible({ timeout: 15000 });

  const listResponsePromise = page
    .waitForResponse(
      (resp) => resp.url().includes('/api/dynamic/e2et_order/list') && resp.status() === 200,
      { timeout: 10000 },
    )
    .catch(() => null);

  await orderLink.click();
  await expect(page).toHaveURL(/\/p\/e2et_order(?:$|[?#])/, { timeout: 15000 });
  await waitForDynamicPageLoad(page);
  await listResponsePromise;
}
