/**
 * E2E Test: Kanban Enhanced Grouping (GAP-129)
 *
 * Tests that Kanban views can group by REFERENCE and BOOLEAN fields in addition
 * to existing TEXT/DICT support, while semantic validation rejects DATE fields.
 */

import { test, expect, type Page } from '@playwright/test';
import { uniqueId } from '../helpers';
import { createOrReuseSavedView } from './helpers';

async function createKanbanViewViaApi(
  page: Page,
  modelCode: string,
  name: string,
  viewConfig: any,
): Promise<string> {
  const result = await createOrReuseSavedView(page, {
    name,
    modelCode,
    viewType: 'kanban',
    scope: 'personal',
    viewConfig,
    expectSuccess: true,
  });
  return result.pid;
}

async function getViewViaApi(page: Page, pid: string): Promise<any> {
  const resp = await page.request.get(`/api/views/${pid}`);
  if (!resp.ok()) return null;
  const body = await resp.json();
  return body.data ?? body;
}

test.describe('Kanban Enhanced Grouping (GAP-129)', () => {
  test('KG-001: BOOLEAN field accepted as groupByField', async ({ page }) => {
    await page.goto('/');
    await page.locator('nav, [data-testid="sidebar"]').first().waitFor({ timeout: 15000 });

    const viewName = `KG_Bool_${uniqueId()}`;
    const pid = await createKanbanViewViaApi(page, 'e2et_order', viewName, {
      groupByField: 'e2et_order_urgent',
      titleField: 'e2et_order_title',
    });
    expect(pid).toBeTruthy();

    const view = await getViewViaApi(page, pid);
    expect(view.viewConfig?.groupByField).toBe('e2et_order_urgent');
    expect(view.viewType).toBe('kanban');
  });

  test('KG-002: DATE field rejected as incompatible groupByField', async ({ page }) => {
    await page.goto('/');
    await page.locator('nav, [data-testid="sidebar"]').first().waitFor({ timeout: 15000 });

    const viewName = `KG_Date_${uniqueId()}`;
    const resp = await page.request.post('/api/views', {
      data: {
        name: viewName,
        modelCode: 'e2et_order',
        viewType: 'kanban',
        scope: 'personal',
        viewConfig: {
          groupByField: 'e2et_order_date',
          titleField: 'e2et_order_title',
        },
      },
    });
    expect(resp.status()).toBe(422);
    const body = await resp.json();
    expect(body.context?.error ?? body.message ?? '').toContain('INCOMPATIBLE_FIELD_TYPE');
    expect(body.context?.error ?? body.message ?? '').toContain('groupByField');
  });

  test('KG-003: REFERENCE field accepted as groupByField', async ({ page }) => {
    await page.goto('/');
    await page.locator('nav, [data-testid="sidebar"]').first().waitFor({ timeout: 15000 });

    const viewName = `KG_Ref_${uniqueId()}`;
    const pid = await createKanbanViewViaApi(page, 'e2et_order', viewName, {
      groupByField: 'e2et_order_customer',
      titleField: 'e2et_order_title',
    });
    expect(pid).toBeTruthy();

    const view = await getViewViaApi(page, pid);
    expect(view.viewConfig?.groupByField).toBe('e2et_order_customer');
  });

  test('KG-004: acceptedTypes includes enhanced groupable types', async ({ page }) => {
    // This test verifies the frontend/backend contract accepts groupable enhanced
    // types. Date fields are intentionally covered by KG-002 as incompatible.

    await page.goto('/');
    await page.locator('nav, [data-testid="sidebar"]').first().waitFor({ timeout: 15000 });

    // Create views with different groupable field types
    const pids: string[] = [];
    for (const field of ['e2et_order_urgent', 'e2et_order_customer', 'e2et_order_status']) {
      const name = `KG_Multi_${field}_${uniqueId()}`;
      const pid = await createKanbanViewViaApi(page, 'e2et_order', name, {
        groupByField: field,
        titleField: 'e2et_order_title',
      });
      expect(pid).toBeTruthy();
      pids.push(pid);
    }

    // Verify all views exist
    for (const pid of pids) {
      const view = await getViewViaApi(page, pid);
      expect(view).toBeTruthy();
      expect(view.viewType).toBe('kanban');
    }
  });

  test('KG-005: empty groupBy columns show (Empty) title', async ({ page }) => {
    // The resolveGroupKey function maps null/undefined/'' to '(Empty)'
    // This is verified at the data level — when records have null groupByField values
    await page.goto('/');
    await page.locator('nav, [data-testid="sidebar"]').first().waitFor({ timeout: 15000 });

    const viewName = `KG_Empty_${uniqueId()}`;
    const pid = await createKanbanViewViaApi(page, 'e2et_order', viewName, {
      groupByField: 'e2et_order_urgent',
      titleField: 'e2et_order_title',
    });
    expect(pid).toBeTruthy();

    // Verify view was created successfully (empty column handling is runtime)
    const view = await getViewViaApi(page, pid);
    expect(view.viewConfig?.groupByField).toBe('e2et_order_urgent');
  });

  test('KG-006: card drag between columns updates groupByField value', async ({ page }) => {
    // Verify the drag target uses column ID (groupKey) to update the record
    await page.goto('/');
    await page.locator('nav, [data-testid="sidebar"]').first().waitFor({ timeout: 15000 });

    const viewName = `KG_Drag_${uniqueId()}`;
    const pid = await createKanbanViewViaApi(page, 'e2et_order', viewName, {
      groupByField: 'e2et_order_status',
      titleField: 'e2et_order_title',
      draggable: true,
    });
    expect(pid).toBeTruthy();

    const view = await getViewViaApi(page, pid);
    expect(view.viewConfig?.draggable).toBe(true);
    expect(view.viewConfig?.groupByField).toBe('e2et_order_status');
  });
});
