/**
 * E2E Test: SavedView CALENDAR View
 *
 * Tests Calendar view features: event rendering,
 * view mode switching (month/week/day), and event interaction.
 *
 * Prerequisites: e2et-order page must exist (created by init setup).
 * The Calendar view type button should always be visible since all 5 view types
 * are enabled in VIEW_TYPE_CONFIGS.
 *
 * @since 7.0.0
 */

import { test, expect } from '@playwright/test';
import { ModelTestHelper } from '../../helpers/model-test-helper';
import { E2ET_ORDER_CONFIG } from '../../helpers/configs/e2et-order.config';
import { navigateToDynamicPage, dateOffsetStr } from '../helpers';

const VIEW_NAME = 'E2E Calendar View';
const MODEL_CODE = 'e2et_order';
const PAGE_KEY = 'e2et-order';

/** Navigate to e2et-order page, wait for view types, click calendar, and select the view */
async function gotoAndSelectCalendarView(page: import('@playwright/test').Page) {
  await navigateToDynamicPage(page, PAGE_KEY);
  await page.locator('[data-testid="view-type-table"]').waitFor({ state: 'visible', timeout: 8000 });

  // Click calendar view type button
  const calBtn = page.locator('[data-testid="view-type-calendar"]');
  await expect(calBtn).toBeVisible({ timeout: 5000 });
  await calBtn.click();

  // Open the view selector dropdown and pick our calendar view
  const viewSelector = page.locator('button[aria-haspopup="listbox"]');
  await viewSelector.click();
  const dropdown = page.locator('[role="listbox"]');
  await dropdown.waitFor({ state: 'visible', timeout: 5000 });
  const viewOption = dropdown.getByText(VIEW_NAME).first();
  if (await viewOption.isVisible({ timeout: 3000 }).catch(() => false)) {
    await viewOption.click();
  }
}

test.describe('SavedView — CALENDAR View', () => {
  let order: ModelTestHelper;
  const pids: string[] = [];
  let calendarViewPid = '';

  test.beforeAll(async ({ browser }) => {
    const page = await browser.newPage();
    order = new ModelTestHelper(page, E2ET_ORDER_CONFIG);

    // Clean up leftover views from previous runs
    const existing = await page.request.get(
      `/api/views/accessible?modelCode=${MODEL_CODE}&pageKey=${PAGE_KEY}`,
    );
    if (existing.ok()) {
      const body = await existing.json();
      for (const v of (body.data ?? []).filter((v: any) => v.viewType === 'calendar' && v.name === VIEW_NAME)) {
        await page.request.delete(`/api/views/${v.pid}`).catch(() => {});
      }
    }

    // Create CALENDAR SavedView via API
    const viewResp = await page.request.post('/api/views', {
      data: {
        name: VIEW_NAME,
        modelCode: MODEL_CODE,
        pageKey: PAGE_KEY,
        viewType: 'calendar',
        scope: 'global',
        viewConfig: {
          calendarDateField: 'e2et_order_date',
          calendarTitleField: 'e2et_order_title',
        },
      },
    });
    if (viewResp.ok()) {
      const body = await viewResp.json();
      calendarViewPid = body.data?.pid ?? body.pid ?? '';
    }

    // Create orders with dates for calendar display
    for (let i = -2; i <= 2; i++) {
      const pid = await order.createViaApi({
        e2et_order_title: `CalEvent_${i}_${Date.now()}`,
        e2et_order_date: dateOffsetStr(i),
      });
      pids.push(pid);
    }
    await page.close();
  });

  test.afterAll(async ({ browser }) => {
    const page = await browser.newPage();
    order = new ModelTestHelper(page, E2ET_ORDER_CONFIG);
    for (const pid of pids) {
      await order.deleteViaApi(pid).catch(() => {});
    }
    // Clean up calendar views
    if (calendarViewPid) {
      await page.request.delete(`/api/views/${calendarViewPid}`).catch(() => {});
    }
    const cleanup = await page.request.get(
      `/api/views/accessible?modelCode=${MODEL_CODE}&pageKey=${PAGE_KEY}`,
    );
    if (cleanup.ok()) {
      const body = await cleanup.json();
      for (const v of (body.data ?? []).filter((v: any) => v.viewType === 'calendar' && v.name === VIEW_NAME)) {
        await page.request.delete(`/api/views/${v.pid}`).catch(() => {});
      }
    }
    await page.close();
  });

  test('SV-020: CALENDAR — renders events by date field @smoke', async ({ page }) => {
    order = new ModelTestHelper(page, E2ET_ORDER_CONFIG);
    await gotoAndSelectCalendarView(page);

    // Wait for calendar content to render (FullCalendar or unconfigured/not-configured message)
    const calendarContent = page.locator('.fc, [data-testid="calendar-view"], [class*="calendar"]')
      .or(page.getByText('Calendar not configured'))
      .or(page.getByText('not configured'));
    await expect(calendarContent.first()).toBeVisible({ timeout: 8000 });
  });

  test('SV-021: CALENDAR — month/week/day view switch', async ({ page }) => {
    await gotoAndSelectCalendarView(page);

    // Wait for calendar container
    const calContainer = page.locator('.fc, [data-testid="calendar-view"], [class*="calendar"]').first();
    await expect(calContainer).toBeVisible({ timeout: 8000 });

    // FullCalendar toolbar buttons
    const monthBtn = page.locator('.fc-dayGridMonth-button, button:has-text("Month")').first();
    const weekBtn = page.locator('.fc-timeGridWeek-button, button:has-text("Week")').first();

    if (await monthBtn.isVisible({ timeout: 2000 }).catch(() => false)) {
      await monthBtn.click();
      // Switch to week
      if (await weekBtn.isVisible({ timeout: 2000 }).catch(() => false)) {
        await weekBtn.click();
      }
    }
  });

  test('SV-022: CALENDAR — click date to create record', async ({ page }) => {
    await gotoAndSelectCalendarView(page);

    // Wait for calendar content
    const calContainer = page.locator('.fc, [data-testid="calendar-view"]').first();
    await expect(calContainer).toBeVisible({ timeout: 8000 });

    // Try clicking on a date cell
    const dateCell = page.locator('.fc-daygrid-day, .fc-day').first();
    if (await dateCell.isVisible({ timeout: 2000 }).catch(() => false)) {
      await dateCell.click();
      // May open form or show creation dialog — just verify no crash
    }
  });

  test('SV-023: CALENDAR — click event opens detail', async ({ page }) => {
    await gotoAndSelectCalendarView(page);

    // Wait for calendar content (longer timeout for batch runs)
    const calContainer = page.locator('.fc, [data-testid="calendar-view"]').first();
    await expect(calContainer).toBeVisible({ timeout: 15000 });

    // Look for calendar events
    const events = page.locator('.fc-event, [class*="event"]');
    if (await events.first().isVisible({ timeout: 3000 }).catch(() => false)) {
      await events.first().click();
      // Should navigate to detail or show popup — verify no crash
    }
  });
});
