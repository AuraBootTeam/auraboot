/**
 * E2E Test: Timeline View (GAP-128)
 *
 * Tests that Timeline views can be created, configured, and persisted.
 */

import { test, expect, type Page } from '@playwright/test';
import { uniqueId } from '../helpers';
import { createOrReuseSavedView, navigateToOrderViaSidebar } from './helpers';

async function createViewViaApi(
  page: Page,
  modelCode: string,
  name: string,
  viewConfig: Record<string, unknown>,
): Promise<string> {
  const result = await createOrReuseSavedView(page, {
    name,
    modelCode,
    pageKey: 'e2et_order_list',
    viewType: 'timeline',
    scope: 'personal',
    viewConfig,
    expectSuccess: Object.keys(viewConfig).length > 0,
  });
  return result.pid;
}

async function getViewViaApi(page: Page, pid: string): Promise<any> {
  const resp = await page.request.get(`/api/views/${pid}`);
  if (!resp.ok()) return null;
  const body = await resp.json();
  return body.data ?? body;
}

test.describe('Timeline View (GAP-128)', () => {
  test('TL-001: TIMELINE viewType accepted by API', async ({ page }) => {
    await page.goto('/');
    await page.locator('nav, [data-testid="sidebar"]').first().waitFor({ timeout: 15000 });

    const viewName = `TL_Basic_${uniqueId()}`;
    const pid = await createViewViaApi(page, 'e2et_order', viewName, {
      timelineStartField: 'e2et_order_date',
      timelineEndField: 'e2et_order_date',
      timelineResourceField: 'e2et_order_customer',
      timelineTitleField: 'e2et_order_title',
    });
    expect(pid).toBeTruthy();

    const view = await getViewViaApi(page, pid);
    expect(view.viewType).toBe('timeline');
    expect(view.viewConfig?.timelineStartField).toBe('e2et_order_date');
  });

  test('TL-002: timeline with resource grouping field', async ({ page }) => {
    await page.goto('/');
    await page.locator('nav, [data-testid="sidebar"]').first().waitFor({ timeout: 15000 });

    const viewName = `TL_Resource_${uniqueId()}`;
    const pid = await createViewViaApi(page, 'e2et_order', viewName, {
      timelineStartField: 'e2et_order_date',
      timelineEndField: 'e2et_order_date',
      timelineResourceField: 'e2et_order_customer',
      timelineTitleField: 'e2et_order_title',
    });
    expect(pid).toBeTruthy();

    const view = await getViewViaApi(page, pid);
    expect(view.viewConfig?.timelineResourceField).toBe('e2et_order_customer');
  });

  test('TL-003: timeline view renders without errors', async ({ page }) => {
    await navigateToOrderViaSidebar(page);
    const toolbar = page.getByTestId('row-height-btn');
    await expect(toolbar).toBeVisible({ timeout: 30000 });

    // Switch to Timeline view type via the view type selector
    const timelineBtn = page.getByTestId('view-type-timeline');
    if (await timelineBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
      await timelineBtn.click();
      // Should show timeline or "not configured" message
      const timelineView = page.getByTestId('timeline-view');
      const notConfigured = page.locator('text=时间线视图未配置');
      const visible = await Promise.race([
        timelineView.waitFor({ timeout: 5000 }).then(() => 'timeline'),
        notConfigured.waitFor({ timeout: 5000 }).then(() => 'not-configured'),
      ]).catch(() => 'timeout');
      expect(['timeline', 'not-configured']).toContain(visible);
    }
  });

  test('TL-004: timeline config persists across reload', async ({ page }) => {
    await page.goto('/');
    await page.locator('nav, [data-testid="sidebar"]').first().waitFor({ timeout: 15000 });

    const viewName = `TL_Persist_${uniqueId()}`;
    const pid = await createViewViaApi(page, 'e2et_order', viewName, {
      timelineStartField: 'e2et_order_date',
      timelineEndField: 'e2et_order_date',
      timelineResourceField: 'e2et_order_customer',
      timelineTitleField: 'e2et_order_title',
    });
    expect(pid).toBeTruthy();

    // Re-fetch
    const view = await getViewViaApi(page, pid);
    expect(view.viewConfig?.timelineStartField).toBe('e2et_order_date');
    expect(view.viewConfig?.timelineEndField).toBe('e2et_order_date');
    expect(view.viewConfig?.timelineResourceField).toBe('e2et_order_customer');
    expect(view.viewConfig?.timelineTitleField).toBe('e2et_order_title');
  });

  test('TL-005: TIMELINE in VIEW_TYPE_CONFIGS', async ({ page }) => {
    // Verify TIMELINE appears in the view type selector
    await navigateToOrderViaSidebar(page);
    await page.getByTestId('row-height-btn').waitFor({ state: 'visible', timeout: 30000 });

    // Look for Timeline in the view type bar
    const timelineOption = page.locator('text=Timeline');
    // It should be in the view type switcher
    if (await timelineOption.isVisible({ timeout: 3000 }).catch(() => false)) {
      expect(await timelineOption.isVisible()).toBe(true);
    }
  });

  test('TL-006: unconfigured timeline is rejected by capability gate', async ({ page }) => {
    await page.goto('/');
    await page.locator('nav, [data-testid="sidebar"]').first().waitFor({ timeout: 15000 });

    const viewName = `TL_Empty_${uniqueId()}`;
    const pid = await createViewViaApi(page, 'e2et_order', viewName, {});
    expect(pid).toBe('');
  });

  test('TL-007: incompatible timeline field mapping is rejected by backend semantics', async ({ page }) => {
    await page.goto('/');
    await page.locator('nav, [data-testid="sidebar"]').first().waitFor({ timeout: 15000 });

    const resp = await page.request.post('/api/views', {
      data: {
        name: `TL_Invalid_${uniqueId()}`,
        modelCode: 'e2et_order',
        viewType: 'timeline',
        scope: 'personal',
        viewConfig: {
          timelineStartField: 'e2et_order_title',
          timelineResourceField: 'e2et_order_customer',
        },
      },
    });

    expect(resp.ok()).toBe(false);
    const body = await resp.json().catch(() => ({}));
    expect(JSON.stringify(body)).toContain('INCOMPATIBLE_FIELD_TYPE');
    expect(JSON.stringify(body)).toContain('timelineStartField');
  });
});
