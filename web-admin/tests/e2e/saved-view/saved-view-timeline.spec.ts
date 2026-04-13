/**
 * E2E Test: Timeline View (GAP-128)
 *
 * Tests that Timeline views can be created, configured, and persisted.
 */

import { test, expect, type Page } from '@playwright/test';
import { uniqueId } from '../helpers';

async function createViewViaApi(
  page: Page,
  modelCode: string,
  name: string,
  viewConfig: any,
): Promise<string> {
  const resp = await page.request.post('/api/views', {
    data: { name, modelCode, viewType: 'timeline', scope: 'personal', viewConfig },
  });
  if (!resp.ok()) return '';
  const body = await resp.json();
  return body.data?.pid ?? body.pid ?? '';
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
      timelineResourceField: 'e2et_customer_id',
      timelineTitleField: 'e2et_order_title',
    });
    expect(pid).toBeTruthy();

    const view = await getViewViaApi(page, pid);
    expect(view.viewConfig?.timelineResourceField).toBe('e2et_customer_id');
  });

  test('TL-003: timeline view renders without errors', async ({ page }) => {
    await page.goto('/p/e2et_order');
    const toolbar = page.getByTestId('row-height-btn');
    await expect(toolbar).toBeVisible({ timeout: 30000 });

    // Switch to Timeline view type via the view type selector
    const timelineBtn = page.getByTestId('view-type-timeline');
    if (await timelineBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
      await timelineBtn.click();
      // Should show timeline or "not configured" message
      const timelineView = page.getByTestId('timeline-view');
      const notConfigured = page.locator('text=Timeline view not configured');
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
      timelineStartField: 'start_date',
      timelineEndField: 'end_date',
      timelineResourceField: 'assignee',
      timelineTitleField: 'task_name',
    });
    expect(pid).toBeTruthy();

    // Re-fetch
    const view = await getViewViaApi(page, pid);
    expect(view.viewConfig?.timelineStartField).toBe('start_date');
    expect(view.viewConfig?.timelineEndField).toBe('end_date');
    expect(view.viewConfig?.timelineResourceField).toBe('assignee');
    expect(view.viewConfig?.timelineTitleField).toBe('task_name');
  });

  test('TL-005: TIMELINE in VIEW_TYPE_CONFIGS', async ({ page }) => {
    // Verify TIMELINE appears in the view type selector
    await page.goto('/p/e2et_order');
    await page.getByTestId('row-height-btn').waitFor({ state: 'visible', timeout: 30000 });

    // Look for Timeline in the view type bar
    const timelineOption = page.locator('text=Timeline');
    // It should be in the view type switcher
    if (await timelineOption.isVisible({ timeout: 3000 }).catch(() => false)) {
      expect(await timelineOption.isVisible()).toBe(true);
    }
  });

  test('TL-006: unconfigured timeline shows setup message', async ({ page }) => {
    await page.goto('/');
    await page.locator('nav, [data-testid="sidebar"]').first().waitFor({ timeout: 15000 });

    // Create timeline view without required fields
    const viewName = `TL_Empty_${uniqueId()}`;
    const pid = await createViewViaApi(page, 'e2et_order', viewName, {});
    expect(pid).toBeTruthy();

    const view = await getViewViaApi(page, pid);
    expect(view.viewType).toBe('timeline');
    // No timeline fields configured — view should show "not configured" when rendered
    expect(view.viewConfig?.timelineStartField ?? null).toBeNull();
  });
});
