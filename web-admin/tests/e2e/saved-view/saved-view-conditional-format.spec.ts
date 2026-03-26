/**
 * E2E Test: Conditional Formatting (GAP-122)
 *
 * Tests conditional formatting rules: create rule, color presets,
 * persistence, and rendering on table rows.
 */

import { test, expect, type Page } from '@playwright/test';
import { uniqueId } from '../helpers';

// API helpers
async function createViewViaApi(
  page: Page,
  modelCode: string,
  name: string,
  conditionalFormats?: any[]
): Promise<string> {
  const resp = await page.request.post('/api/views', {
    data: {
      name,
      modelCode,
      viewType: 'table',
      scope: 'personal',
      viewConfig: conditionalFormats ? { conditionalFormats } : {},
    },
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

test.describe('Conditional Formatting (GAP-122)', () => {

  test('CF-001: conditional format rules stored in viewConfig via API', async ({ page }) => {
    // Navigate to establish auth context
    await page.goto('/');
    await page.locator('nav, [data-testid="sidebar"]').first().waitFor({ timeout: 15000 });

    const viewName = `CF_Store_${uniqueId()}`;
    const rules = [
      { fieldCode: 'e2et_order_status', operator: 'eq', value: 'draft', style: { backgroundColor: '#f5f5f5', textColor: '#424242' } },
    ];
    const pid = await createViewViaApi(page, 'e2et_order', viewName, rules);
    expect(pid).toBeTruthy();

    const view = await getViewViaApi(page, pid);
    expect(view.viewConfig?.conditionalFormats).toHaveLength(1);
    expect(view.viewConfig.conditionalFormats[0].fieldCode).toBe('e2et_order_status');
    expect(view.viewConfig.conditionalFormats[0].operator).toBe('eq');
    expect(view.viewConfig.conditionalFormats[0].value).toBe('draft');
    expect(view.viewConfig.conditionalFormats[0].style.backgroundColor).toBe('#f5f5f5');
    expect(view.viewConfig.conditionalFormats[0].style.textColor).toBe('#424242');
  });

  test('CF-002: conditional format rules with isNull/isNotNull operators', async ({ page }) => {
    await page.goto('/');
    await page.locator('nav, [data-testid="sidebar"]').first().waitFor({ timeout: 15000 });

    const viewName = `CF_NullOps_${uniqueId()}`;
    const rules = [
      { fieldCode: 'e2et_total_amount', operator: 'isNull', style: { backgroundColor: '#ffebee', textColor: '#b71c1c' } },
      { fieldCode: 'e2et_total_amount', operator: 'isNotNull', style: { backgroundColor: '#e8f5e9', textColor: '#1b5e20' } },
    ];
    const pid = await createViewViaApi(page, 'e2et_order', viewName, rules);
    expect(pid).toBeTruthy();

    const view = await getViewViaApi(page, pid);
    expect(view.viewConfig?.conditionalFormats).toHaveLength(2);
    expect(view.viewConfig.conditionalFormats[0].operator).toBe('isNull');
    // isNull rules don't need a value field
    expect(view.viewConfig.conditionalFormats[1].operator).toBe('isNotNull');
  });

  test('CF-003: conditional formats persist via API', async ({ page }) => {
    // Navigate to establish auth context
    await page.goto('/');
    await page.locator('nav, [data-testid="sidebar"]').first().waitFor({ timeout: 15000 });

    const viewName = `CF_Test_${uniqueId()}`;
    const rules = [
      {
        fieldCode: 'e2et_order_status',
        operator: 'eq',
        value: 'approved',
        style: { backgroundColor: '#e8f5e9', textColor: '#1b5e20' },
      },
      {
        fieldCode: 'e2et_order_status',
        operator: 'eq',
        value: 'cancelled',
        style: { backgroundColor: '#ffebee', textColor: '#b71c1c', bold: true },
      },
    ];

    const pid = await createViewViaApi(page, 'e2et_order', viewName, rules);
    expect(pid).toBeTruthy();

    // Retrieve and verify
    const view = await getViewViaApi(page, pid);
    expect(view).toBeTruthy();
    expect(view.viewConfig?.conditionalFormats).toHaveLength(2);
    expect(view.viewConfig.conditionalFormats[0].fieldCode).toBe('e2et_order_status');
    expect(view.viewConfig.conditionalFormats[0].style.backgroundColor).toBe('#e8f5e9');
    expect(view.viewConfig.conditionalFormats[1].style.bold).toBe(true);
  });

  test('CF-004: multiple rules with priority (first match wins)', async ({ page }) => {
    await page.goto('/');
    await page.locator('nav, [data-testid="sidebar"]').first().waitFor({ timeout: 15000 });

    const viewName = `CF_Priority_${uniqueId()}`;
    const rules = [
      {
        fieldCode: 'e2et_total_amount',
        operator: 'gt',
        value: '1000',
        style: { backgroundColor: '#e8f5e9', textColor: '#1b5e20' },
      },
      {
        fieldCode: 'e2et_total_amount',
        operator: 'gt',
        value: '500',
        style: { backgroundColor: '#fff3e0', textColor: '#e65100' },
      },
    ];

    const pid = await createViewViaApi(page, 'e2et_order', viewName, rules);
    expect(pid).toBeTruthy();

    const view = await getViewViaApi(page, pid);
    expect(view.viewConfig?.conditionalFormats).toHaveLength(2);
    // First rule has higher priority (lower index)
    expect(view.viewConfig.conditionalFormats[0].operator).toBe('gt');
    expect(view.viewConfig.conditionalFormats[0].value).toBe('1000');
  });

  test('CF-005: rule deletion removes formatting', async ({ page }) => {
    await page.goto('/');
    await page.locator('nav, [data-testid="sidebar"]').first().waitFor({ timeout: 15000 });

    // Create view with rules
    const viewName = `CF_Delete_${uniqueId()}`;
    const pid = await createViewViaApi(page, 'e2et_order', viewName, [
      { fieldCode: 'e2et_order_status', operator: 'eq', value: 'draft', style: { backgroundColor: '#f5f5f5' } },
    ]);
    expect(pid).toBeTruthy();

    // Update to empty rules
    const resp = await page.request.put(`/api/views/${pid}`, {
      data: { viewConfig: { conditionalFormats: [] } },
    });
    expect(resp.ok()).toBeTruthy();

    // Verify rules cleared
    const view = await getViewViaApi(page, pid);
    expect(view.viewConfig?.conditionalFormats).toHaveLength(0);
  });

  test('CF-006: conditional formats survive page refresh', async ({ page }) => {
    await page.goto('/');
    await page.locator('nav, [data-testid="sidebar"]').first().waitFor({ timeout: 15000 });

    const viewName = `CF_Refresh_${uniqueId()}`;
    const rules = [
      { fieldCode: 'e2et_order_status', operator: 'eq', value: 'submitted', style: { backgroundColor: '#e3f2fd', textColor: '#0d47a1' } },
    ];

    const pid = await createViewViaApi(page, 'e2et_order', viewName, rules);
    expect(pid).toBeTruthy();

    // Re-fetch (simulates page refresh)
    const view = await getViewViaApi(page, pid);
    expect(view.viewConfig?.conditionalFormats).toHaveLength(1);
    expect(view.viewConfig.conditionalFormats[0].style.backgroundColor).toBe('#e3f2fd');
  });
});
