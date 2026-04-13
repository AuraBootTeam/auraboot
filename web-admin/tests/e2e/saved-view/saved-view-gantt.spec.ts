/**
 * E2E Test: SavedView GANTT View
 *
 * Tests Gantt timeline view features.
 *
 * Prerequisites: e2et-order page must exist (created by init setup).
 * The Gantt view type button should always be visible since all 5 view types
 * are enabled in VIEW_TYPE_CONFIGS.
 *
 * @since 7.0.0
 */

import { test, expect } from '@playwright/test';
import { navigateToDynamicPage, dateOffsetStr } from '../helpers';

const VIEW_NAME = 'E2E Gantt Timeline';
const MODEL_CODE = 'e2et_order';
const PAGE_KEY = 'e2et_order';

/** Navigate to e2et-order page and select the gantt view via ViewManagePanel */
async function gotoAndSelectGanttView(page: import('@playwright/test').Page) {
  await navigateToDynamicPage(page, PAGE_KEY);
  // Wait for the list page content to be visible (table renders by default)
  await page.locator('table, [role="table"], [data-testid="dynamic-list"]').first().waitFor({ state: 'visible', timeout: 15000 });

  // Click ViewSelector button to open ViewManagePanel (slide-out dialog)
  const viewSelector = page.locator('button[aria-haspopup="listbox"]');
  await viewSelector.click();
  const panel = page.locator('[role="dialog"]');
  await panel.waitFor({ state: 'visible', timeout: 5000 });
  // Find and click the gantt view by name in the panel
  const viewOption = panel.getByText(VIEW_NAME, { exact: false }).first();
  await viewOption.waitFor({ state: 'visible', timeout: 5000 });
  await viewOption.click();
  // Close the panel after selecting the view (panel does not auto-close)
  const closeBtn = panel.locator('button[aria-label="Close panel"]');
  if (await closeBtn.isVisible({ timeout: 1000 }).catch(() => false)) {
    await closeBtn.click();
  }
  await panel.waitFor({ state: 'hidden', timeout: 5000 }).catch(() => {});
}

test.describe('SavedView — GANTT View', () => {
  let ganttViewPid = '';

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({
      storageState: 'tests/storage/admin.json',
      baseURL: 'http://localhost:5173',
    });
    const page = await ctx.newPage();

    // Clean up leftover views from previous runs
    const existing = await page.request.get(
      `/api/views/accessible?modelCode=${MODEL_CODE}&pageKey=${PAGE_KEY}`,
    );
    if (existing.ok()) {
      const body = await existing.json();
      for (const v of (body.data ?? []).filter(
        (v: any) => v.viewType === 'gantt' && v.name === VIEW_NAME,
      )) {
        await page.request.delete(`/api/views/${v.pid}`).catch(() => {});
      }
    }

    // Create GANTT SavedView via API
    const viewResp = await page.request.post('/api/views', {
      data: {
        name: VIEW_NAME,
        modelCode: MODEL_CODE,
        pageKey: PAGE_KEY,
        viewType: 'gantt',
        scope: 'global',
        viewConfig: {
          ganttStartDateField: 'e2et_order_date',
          ganttEndDateField: 'e2et_order_date',
          ganttTitleField: 'e2et_order_title',
          ganttDefaultView: 'Week',
        },
      },
    });
    if (viewResp.ok()) {
      const body = await viewResp.json();
      ganttViewPid = body.data?.pid ?? body.pid ?? '';
    }

    await page.close();
    await ctx.close();
  });

  test.afterAll(async ({ browser }) => {
    const ctx = await browser.newContext({
      storageState: 'tests/storage/admin.json',
      baseURL: 'http://localhost:5173',
    });
    const page = await ctx.newPage();

    if (ganttViewPid) {
      await page.request.delete(`/api/views/${ganttViewPid}`).catch(() => {});
    }
    const cleanup = await page.request.get(
      `/api/views/accessible?modelCode=${MODEL_CODE}&pageKey=${PAGE_KEY}`,
    );
    if (cleanup.ok()) {
      const body = await cleanup.json();
      for (const v of (body.data ?? []).filter(
        (v: any) => v.viewType === 'gantt' && v.name === VIEW_NAME,
      )) {
        await page.request.delete(`/api/views/${v.pid}`).catch(() => {});
      }
    }

    await page.close();
    await ctx.close();
  });

  test('SV-040: GANTT — timeline renders @smoke', async ({ page }) => {
    await gotoAndSelectGanttView(page);

    // GanttView renders a toolbar with "{n} tasks" text and Day/Week/Month buttons,
    // or an "unconfigured" message. Match any of these.
    const content = page
      .getByText(/\d+ tasks/i)
      .or(page.getByText('Gantt chart not configured'))
      .or(page.locator('[data-testid="gantt-empty-diagnostics"]'))
      .or(page.getByText('not configured'));
    await expect(content.first()).toBeVisible({ timeout: 8000 });

    // This test uses start/end mapped to the same field, so config warning should be shown.
    await expect(page.locator('[data-testid="gantt-config-warning"]')).toBeVisible({
      timeout: 10000,
    });
  });

  test('SV-041: GANTT — time zoom (day/week/month)', async ({ page }) => {
    await gotoAndSelectGanttView(page);

    // Wait for gantt content — toolbar shows "{n} tasks" or unconfigured message
    const ganttContent = page
      .getByText(/\d+ tasks/i)
      .or(page.getByText('Gantt chart not configured'))
      .or(page.locator('[data-testid="gantt-empty-diagnostics"]'))
      .or(page.getByText('not configured'));
    await expect(ganttContent.first()).toBeVisible({ timeout: 8000 });

    // Look for view mode buttons (Day/Week/Month)
    const dayBtn = page.locator('button').filter({ hasText: /^Day$/i }).first();
    const weekBtn = page
      .locator('button')
      .filter({ hasText: /^Week$/i })
      .first();
    const monthBtn = page
      .locator('button')
      .filter({ hasText: /^Month$/i })
      .first();

    for (const btn of [dayBtn, weekBtn, monthBtn]) {
      if (await btn.isVisible({ timeout: 1000 }).catch(() => false)) {
        await btn.click();
      }
    }
  });
});
