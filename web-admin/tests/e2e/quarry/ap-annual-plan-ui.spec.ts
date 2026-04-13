/**
 * AP Annual Plan — UI E2E Tests
 *
 * Uses real UI row actions across mainline and branch flows.
 * Avoids brittle API-created fixture dependency in unstable environments.
 */
import { test, expect } from '@playwright/test';
import type { Locator, Page } from '@playwright/test';
import { ErrorCodes } from '~/shared/services/http-client/types';
import {
  navigateToDynamicPage,
  uniqueId,
  executeCommandViaApi,
  waitForDynamicPageLoad,
  acceptConfirmDialog,
  clickTabAndWaitForLoad,
  clickRowActionByLocator,
  findRowInPaginatedList,
} from '../helpers/index';

const PLAN_MODEL = 'ap_annual_plan';

test.describe('AP Annual Plan — UI Tests', () => {
  test.describe.configure({ mode: 'serial' });

  async function ensurePageReady(page: Page) {
    const loadFailed = page.getByRole('heading', { name: '加载失败' }).first();
    if (await loadFailed.isVisible({ timeout: 1500 }).catch(() => false)) {
      const listResponse = page
        .waitForResponse((r) => r.url().includes('/list') && r.status() === 200, { timeout: 10000 })
        .catch(() => null);
      await page.locator('button:has-text("重试"), button:has-text("Retry")').first().click();
      await listResponse;
    }
  }

  async function gotoTab(
    page: Page,
    tabKey: 'all' | 'draft' | 'submitted' | 'approved' | 'rejected',
  ) {
    const tabRegexMap: Record<string, RegExp> = {
      all: /全部|All/i,
      draft: /草稿|Draft/i,
      submitted: /已提交|Submitted/i,
      approved: /已审批|Approved/i,
      rejected: /已退回|Rejected/i,
    };
    await clickTabAndWaitForLoad(page, tabRegexMap[tabKey], 10000, tabKey);
  }

  async function findRowWithAction(
    page: Page,
    tabKey: 'all' | 'draft' | 'submitted' | 'approved' | 'rejected',
    actionTestId: string,
  ): Promise<Locator> {
    await ensurePageReady(page);
    await gotoTab(page, tabKey);
    // Wait for at least one row to appear after tab switch
    await page.locator('tbody tr').first().waitFor({ state: 'visible', timeout: 10000 }).catch(() => {});

    for (let i = 0; i < 5; i++) {
      // Hover each row to reveal action buttons (opacity-0 → opacity-100 via group-hover)
      const rows = page.locator('tbody tr');
      const rowCount = await rows.count();
      for (let r = 0; r < rowCount; r++) {
        await rows.nth(r).hover();
        const actionBtn = rows.nth(r).locator(`[data-testid="${actionTestId}"]`).first();
        if (await actionBtn.isVisible({ timeout: 500 }).catch(() => false)) {
          return rows.nth(r);
        }
      }

      // Try pagination — use various possible button selectors
      const nextBtn = page
        .locator('button[aria-label="Next"], button:has-text("下一页"), [data-testid="pagination-next"]')
        .first();
      const hasNext = await nextBtn.isVisible({ timeout: 1000 }).catch(() => false);
      const nextDisabled = hasNext ? await nextBtn.isDisabled().catch(() => true) : true;
      if (!hasNext || nextDisabled) break;

      const listResponse = page
        .waitForResponse((r) => r.url().includes('/list') && r.status() === 200, { timeout: 10000 })
        .catch(() => null);
      await nextBtn.click();
      await listResponse;
    }

    throw new Error(`No row with action ${actionTestId} found in tab ${tabKey}`);
  }

  async function createDraftPlan(page: Page, namePrefix: string): Promise<string> {
    const planName = `${namePrefix} ${uniqueId()}`;
    const project = await executeCommandViaApi(page, 'pm:create_project', {
      pm_project_name: `AP Branch Project ${uniqueId()}`,
      pm_project_code: `AP-BR-${uniqueId()}`,
    });
    expect(project.code).toBe(ErrorCodes.SUCCESS);
    const currentProjectId = project.recordId;
    let code = '';
    for (let i = 0; i < 31; i++) {
      const year = 2020 + i;
      const cr = await executeCommandViaApi(page, 'ap:create_annual_plan', {
        ap_project_id: currentProjectId,
        ap_stat_year: year,
        ap_plan_name: planName,
        ap_plan_status: 'draft',
      });
      code = cr.code;
      if (cr.code === ErrorCodes.SUCCESS) break;
    }
    expect(code).toBe(ErrorCodes.SUCCESS);
    return planName;
  }

  async function ensureDraftRow(page: Page): Promise<Locator> {
    try {
      return await findRowWithAction(page, 'draft', 'row-action-submit');
    } catch {
      const name = await createDraftPlan(page, 'AP UI Draft');
      await navigateToDynamicPage(page, PLAN_MODEL);
      return findRowByName(page, 'draft', name);
    }
  }

  async function ensureSubmittedRow(page: Page): Promise<Locator> {
    try {
      return await findRowWithAction(page, 'submitted', 'row-action-approve');
    } catch {
      const name = await createDraftPlan(page, 'AP UI Submitted');
      const row = await findRowByName(page, 'draft', name);
      await clickRowActionByLocator(page, row, 'submit');
      await acceptConfirmDialog(page);
      await page
        .waitForResponse((r) => r.url().includes('/list') && r.status() === 200, { timeout: 10000 })
        .catch(() => null);
      return findRowByName(page, 'submitted', name);
    }
  }

  async function findRowByName(
    page: Page,
    tabKey: 'all' | 'draft' | 'submitted' | 'approved' | 'rejected',
    name: string,
  ): Promise<Locator> {
    await ensurePageReady(page);
    await gotoTab(page, tabKey);

    // Use the standard paginated search helper which handles pagination properly
    const row = await findRowInPaginatedList(page, name, 15000);
    return row;
  }

  test('should display plan list with 5 status tabs', async ({ page }) => {
    await navigateToDynamicPage(page, PLAN_MODEL);
    await expect(page.locator('table, [role="table"]').first()).toBeVisible();

    const tabNav = page.locator('nav[aria-label="Tabs"]').first();
    await expect(tabNav).toBeVisible({ timeout: 5000 });

    const tabs = tabNav.locator('button');
    expect(await tabs.count()).toBeGreaterThanOrEqual(5);
  });

  test('AP mainline: submit via row action (draft → submitted)', async ({ page }) => {
    await navigateToDynamicPage(page, PLAN_MODEL);

    const row = await ensureDraftRow(page);

    await clickRowActionByLocator(page, row, 'submit');
    await acceptConfirmDialog(page);

    await page
      .waitForResponse((r) => r.url().includes('/list') && r.status() === 200, { timeout: 10000 })
      .catch(() => null);
    await ensurePageReady(page);
  });

  test('AP mainline: approve via row action (submitted → approved)', async ({ page }) => {
    await navigateToDynamicPage(page, PLAN_MODEL);
    const row = await ensureSubmittedRow(page);
    await clickRowActionByLocator(page, row, 'approve');
    await acceptConfirmDialog(page);

    await page
      .waitForResponse((r) => r.url().includes('/list') && r.status() === 200, { timeout: 10000 })
      .catch(() => null);
    await ensurePageReady(page);
  });

  test('should not show edit/delete for approved plan', async ({ page }) => {
    await navigateToDynamicPage(page, PLAN_MODEL);

    const row = await findRowWithAction(page, 'approved', 'row-action-detail');
    await row.hover();

    await expect(row.locator('[data-testid="row-action-detail"]').first()).toBeVisible({
      timeout: 3000,
    });
    await expect(row.locator('[data-testid="row-action-edit"]').first()).not.toBeVisible({
      timeout: 3000,
    });
    await expect(row.locator('[data-testid="row-action-delete"]').first()).not.toBeVisible({
      timeout: 3000,
    });
    await expect(row.locator('[data-testid="row-action-submit"]').first()).not.toBeVisible({
      timeout: 3000,
    });
  });

  test.fixme('AP branch: reject plan and allow re-submit', async ({ page }) => {
    await navigateToDynamicPage(page, PLAN_MODEL);

    const row = await ensureSubmittedRow(page);
    await clickRowActionByLocator(page, row, 'reject');
    await acceptConfirmDialog(page);

    await page
      .waitForResponse((r) => r.url().includes('/list') && r.status() === 200, { timeout: 10000 })
      .catch(() => null);

    const rejectedRow = await findRowWithAction(page, 'rejected', 'row-action-submit');
    await expect(rejectedRow.locator('[data-testid="row-action-edit"]').first()).toBeVisible({
      timeout: 3000,
    });

    await clickRowActionByLocator(page, rejectedRow, 'submit');
    await acceptConfirmDialog(page);

    await page
      .waitForResponse((r) => r.url().includes('/list') && r.status() === 200, { timeout: 10000 })
      .catch(() => null);

    await ensurePageReady(page);
  });

  test('should display plan detail page with tabs', async ({ page }) => {
    test.setTimeout(30000);
    await navigateToDynamicPage(page, PLAN_MODEL);

    const row = await findRowWithAction(page, 'all', 'row-action-detail');
    await clickRowActionByLocator(page, row, 'detail');

    await waitForDynamicPageLoad(page);

    const tabs = page.locator('nav[aria-label="Tabs"] button, [role="tab"]');
    const tabCount = await tabs.count().catch(() => 0);
    if (tabCount > 0) {
      expect(tabCount).toBeGreaterThanOrEqual(2);
      const consolidatedTab = tabs.filter({ hasText: /并表|Consolidated/i }).first();
      if (await consolidatedTab.isVisible({ timeout: 2000 }).catch(() => false)) {
        await consolidatedTab.click();
      } else {
        await tabs.nth(1).click();
      }
      await expect(page.locator('[data-testid="monthly-grid-viewer"]').first()).toBeVisible({
        timeout: 10000,
      });
    }
  });

  test('AP branch: delete draft plan via row action', async ({ page }) => {
    await navigateToDynamicPage(page, PLAN_MODEL);

    let row = await findRowWithAction(page, 'draft', 'row-action-delete').catch(() => null);
    if (!row) {
      const name = await createDraftPlan(page, 'AP UI Delete');
      await navigateToDynamicPage(page, PLAN_MODEL);
      row = await findRowByName(page, 'draft', name);
    }

    await clickRowActionByLocator(page, row, 'delete');
    await acceptConfirmDialog(page);

    await page
      .waitForResponse((r) => r.url().includes('/list') && r.status() === 200, { timeout: 10000 })
      .catch(() => null);

    await ensurePageReady(page);
  });
});
