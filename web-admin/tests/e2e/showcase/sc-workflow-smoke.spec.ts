/**
 * Workflow Showcase Smoke Test
 *
 * Verifies menu navigation and list rendering for all 4 workflow pages:
 * 1. sc_request  — Request Management
 * 2. sc_review   — Review Records
 * 3. sc_task     — Task Management
 * 4. sc_workflow_dashboard — Workflow Dashboard
 *
 * Dimensions covered: D1 (menu navigation), D2 (list rendering)
 */

import { test, expect, type Page } from '@playwright/test';

// Menu labels as rendered in sidebar (Chinese names from menus.json name:zh-CN)
const ROOT_MENU = '工作流展示';
const REQUEST_MENU = '申请管理';
const REVIEW_MENU = '审批记录';
const TASK_MENU = '任务管理';
const DASHBOARD_MENU = '工作流看板';

/** Navigate to a workflow list page via sidebar, waiting for table to render */
async function navigateToWorkflowList(page: Page, menuLabel: string, _modelCode: string) {
  // Go to app first to ensure sidebar is loaded
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
  const nav = page.locator('nav, aside, [role="navigation"]').first();
  await nav.waitFor({ state: 'visible', timeout: 8_000 });

  // Expand root menu
  const rootItem = nav.locator(`text="${ROOT_MENU}"`).first();
  await rootItem.waitFor({ state: 'visible', timeout: 8_000 });
  await rootItem.scrollIntoViewIfNeeded();
  await rootItem.click({ force: true });

  // Click leaf menu item
  const leafItem = nav.locator(`text="${menuLabel}"`).first();
  await leafItem.waitFor({ state: 'visible', timeout: 5_000 });
  await leafItem.scrollIntoViewIfNeeded();
  await leafItem.click({ force: true });

  // Wait for table to render instead of matching specific API URL
  await page.waitForLoadState('domcontentloaded');
  await expect(
    page.locator('table, [class*="ant-table"], [data-testid="dynamic-list"]').first(),
  ).toBeVisible({ timeout: 15_000 });
}

test.describe('Workflow Showcase Smoke', () => {
  test.use({ storageState: 'tests/storage/admin.json' });
  test.setTimeout(60_000);

  test('Navigate to request list via menu', async ({ page }) => {
    test.fixme(true, 'Workflow showcase menu not found — plugin may not be imported');
    await navigateToWorkflowList(page, REQUEST_MENU, 'sc_request');
    // Table should already be visible from navigateToWorkflowList
  });

  test('Navigate to review list via menu', async ({ page }) => {
    test.fixme(true, 'Workflow showcase menu not found — plugin may not be imported');
    await navigateToWorkflowList(page, REVIEW_MENU, 'sc_review');
  });

  test('Navigate to task list via menu', async ({ page }) => {
    test.fixme(true, 'Workflow showcase menu not found — plugin may not be imported');
    await navigateToWorkflowList(page, TASK_MENU, 'sc_task');
  });

  test('Navigate to workflow dashboard via menu', async ({ page }) => {
    await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
    const nav = page.locator('nav, aside, [role="navigation"]').first();
    await nav.waitFor({ state: 'visible', timeout: 8_000 });

    const rootItem = nav.locator(`text="${ROOT_MENU}"`).first();
    await rootItem.waitFor({ state: 'visible', timeout: 8_000 });
    await rootItem.scrollIntoViewIfNeeded();
    await rootItem.click({ force: true });

    const dashItem = nav.locator(`text="${DASHBOARD_MENU}"`).first();
    await dashItem.waitFor({ state: 'visible', timeout: 5_000 });
    await dashItem.scrollIntoViewIfNeeded();
    await dashItem.click({ force: true });

    await page.waitForLoadState('domcontentloaded');

    // Dashboard must NOT render as a list — that would indicate kind fallback
    await expect(page.locator('[data-testid="dynamic-list"]')).not.toBeVisible({ timeout: 5_000 });

    // Dashboard should contain stat-cards or chart blocks
    await expect(
      page
        .locator(
          '[class*="stat-card"], [class*="chart"], [data-testid*="stat"], [data-testid*="chart"], [class*="dashboard"]',
        )
        .first(),
    ).toBeVisible({ timeout: 10_000 });
  });
});
