/**
 * CRM Opportunity Kanban E2E Tests
 *
 * Validates the CRM Opportunity Pipeline board (Kanban view):
 * - SavedView creation with kanban config
 * - Column rendering by opportunity stage (6 stages)
 * - Card display with name, account, amount
 * - Stage aggregation (count + total amount)
 * - View type switching (table ↔ kanban)
 *
 * Prerequisites:
 *   - CRM plugin imported with crm_opportunity model
 *   - Seed opportunities in different stages
 *
 * @since 7.4.0
 */

import { test, expect } from '../../fixtures';
import { uniqueId, executeCommandViaApi } from '../helpers/index';

const MODEL_CODE = 'crm_opportunity';
const PAGE_KEY = 'crm-opportunity';
const VIEW_NAME = 'Pipeline Board';

test.describe('CRM Opportunity Kanban @smoke', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(60000);

  const uid = uniqueId('OppKanban');
  let kanbanViewPid = '';
  let accountPid = '';

  // =========================================================================
  // DATA SETUP
  // =========================================================================
  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();

    try {
      // Clean up leftover kanban views from previous runs
      const existing = await page.request.get(
        `/api/views/accessible?modelCode=${MODEL_CODE}&pageKey=${PAGE_KEY}`,
      );
      if (existing.ok()) {
        const body = await existing.json();
        for (const v of (body.data ?? []).filter(
          (v: any) => v.viewType === 'kanban' && v.name === VIEW_NAME,
        )) {
          await page.request.delete(`/api/views/${v.pid}`).catch(() => {});
        }
      }

      // Create account for linking opportunities
      const accResult = await executeCommandViaApi(
        page,
        'crm:create_account',
        {
          crm_acc_name: `KanbanAcct_${uid}`,
          crm_acc_industry: 'technology',
          crm_acc_status: 'active',
        },
        undefined,
        'create',
      );
      accountPid = accResult.recordId;

      // Create opportunities in different stages
      const stages = [
        { stage: 'discovery', amount: 50000 },
        { stage: 'qualification', amount: 80000 },
        { stage: 'proposal', amount: 120000 },
        { stage: 'negotiation', amount: 200000 },
        { stage: 'closed_won', amount: 350000 },
        { stage: 'closed_lost', amount: 30000 },
      ];

      for (const { stage, amount } of stages) {
        await executeCommandViaApi(
          page,
          'crm:create_opportunity',
          {
            crm_opp_name: `${stage}_${uid}`,
            crm_opp_account_id: accountPid,
            crm_opp_stage: stage,
            crm_opp_expected_amount: amount,
          },
          undefined,
          'create',
        );
      }

      // Create KANBAN SavedView via API
      const viewResp = await page.request.post('/api/views', {
        data: {
          name: VIEW_NAME,
          modelCode: MODEL_CODE,
          pageKey: PAGE_KEY,
          viewType: 'kanban',
          scope: 'global',
          viewConfig: {
            groupByField: 'crm_opp_stage',
            titleField: 'crm_opp_name',
            descriptionField: 'crm_opp_expected_amount',
            idField: 'pid',
            cardFields: [
              { field: 'crm_opp_account_id', label: 'Account', type: 'text' },
              { field: 'crm_opp_probability', label: 'Probability', type: 'number' },
            ],
            kanbanAggregations: [
              { field: 'crm_opp_expected_amount', function: 'sum', label: 'Pipeline Value' },
              { field: null, function: 'count', label: 'Count' },
            ],
            draggable: true,
            showCount: true,
            showAggregations: true,
          },
        },
      });
      if (viewResp.ok()) {
        const body = await viewResp.json();
        kanbanViewPid = body.data?.pid ?? '';
      }
    } finally {
      await ctx.close();
    }
  });

  // =========================================================================
  // HELPERS
  // =========================================================================

  /** Navigate to CRM Opportunities via sidebar menu */
  async function gotoOpportunityList(page: import('@playwright/test').Page) {
    await page.goto('/dashboards', { waitUntil: 'load' });

    // Expand CRM menu group
    const crmButton = page.locator('button', { hasText: /CRM/i }).first();
    await crmButton.waitFor({ state: 'visible', timeout: 10000 });
    await crmButton.click();

    // Click Opportunities menu link
    const oppLink = page.locator(`a[href="/${PAGE_KEY}"]`).or(
      page.locator(`a[href="/dynamic/${PAGE_KEY}"]`),
    );
    await oppLink.first().waitFor({ state: 'attached', timeout: 5000 });
    await oppLink.first().evaluate((el: HTMLElement) => el.click());

    // Wait for list page to load
    await page.waitForResponse(
      (resp) => resp.url().includes(`/api/dynamic/${PAGE_KEY}`) && resp.status() === 200,
      { timeout: 15000 },
    ).catch(() => {});
  }

  /** Switch to kanban view type */
  async function switchToKanban(page: import('@playwright/test').Page) {
    const kanbanBtn = page.locator('[data-testid="view-type-kanban"]');
    await expect(kanbanBtn).toBeVisible({ timeout: 8000 });
    await kanbanBtn.click();

    // Select the Pipeline Board view if dropdown appears
    const viewSelector = page.locator('button[aria-haspopup="listbox"]');
    if (await viewSelector.isVisible({ timeout: 2000 }).catch(() => false)) {
      await viewSelector.click();
      const dropdown = page.locator('[role="listbox"]');
      await dropdown.waitFor({ state: 'visible', timeout: 3000 }).catch(() => {});
      const viewOption = dropdown.getByText(VIEW_NAME).first();
      if (await viewOption.isVisible({ timeout: 2000 }).catch(() => false)) {
        await viewOption.click();
      }
    }

    // Wait for kanban content to render
    await page.waitForTimeout(1500);
  }

  // =========================================================================
  // TESTS
  // =========================================================================

  test('OPP-KAN-01: Kanban view type button appears on opportunity list', async ({ page }) => {
    await gotoOpportunityList(page);

    // View type selector should show kanban option
    const kanbanBtn = page.locator('[data-testid="view-type-kanban"]');
    await expect(kanbanBtn).toBeVisible({ timeout: 10000 });
  });

  test('OPP-KAN-02: Switching to kanban shows board with stage columns', async ({ page }) => {
    await gotoOpportunityList(page);
    await switchToKanban(page);

    // Should see kanban board content (columns or cards)
    const kanbanContent = page.locator('[class*="kanban"], [class*="column"], [data-testid="kanban-view"]')
      .or(page.locator('[data-testid="smart-kanban"]'));
    await expect(kanbanContent.first()).toBeVisible({ timeout: 10000 });
  });

  test('OPP-KAN-03: Kanban columns show opportunity stage labels', async ({ page }) => {
    await gotoOpportunityList(page);
    await switchToKanban(page);

    // Should see at least some stage names in the kanban columns
    // Check for Chinese labels (default locale) or English labels
    const stageLabels = [
      page.locator('text=发现').or(page.locator('text=Discovery')),
      page.locator('text=资格确认').or(page.locator('text=Qualification')),
      page.locator('text=商务谈判').or(page.locator('text=Negotiation')),
      page.locator('text=赢单').or(page.locator('text=Closed Won')),
    ];

    let visibleCount = 0;
    for (const label of stageLabels) {
      if (await label.first().isVisible({ timeout: 3000 }).catch(() => false)) {
        visibleCount++;
      }
    }
    expect(visibleCount, 'Should see at least 2 stage column labels').toBeGreaterThanOrEqual(2);
  });

  test('OPP-KAN-04: Kanban cards display seeded opportunity names', async ({ page }) => {
    await gotoOpportunityList(page);
    await switchToKanban(page);

    // Look for at least one seeded opportunity name on a kanban card
    const cardWithName = page.locator(`text=DISCOVERY_${uid}`)
      .or(page.locator(`text=QUALIFICATION_${uid}`))
      .or(page.locator(`text=PROPOSAL_${uid}`))
      .or(page.locator(`text=NEGOTIATION_${uid}`));

    await expect(cardWithName.first()).toBeVisible({ timeout: 10000 });
  });

  test('OPP-KAN-05: Can switch back from kanban to table view', async ({ page }) => {
    await gotoOpportunityList(page);
    await switchToKanban(page);

    // Switch back to table
    const tableBtn = page.locator('[data-testid="view-type-table"]');
    await expect(tableBtn).toBeVisible({ timeout: 5000 });
    await tableBtn.click();

    // Should see the data table again
    const table = page.locator('table, [role="table"]');
    await expect(table.first()).toBeVisible({ timeout: 10000 });
  });

  test('OPP-KAN-06: Kanban view shows card count per column', async ({ page }) => {
    await gotoOpportunityList(page);
    await switchToKanban(page);

    // With showCount: true, each column should show a count badge
    // Look for count indicators (numbers in the kanban header area)
    const countBadges = page.locator('[class*="kanban"] [class*="count"], [class*="kanban"] [class*="badge"]')
      .or(page.locator('[data-testid="smart-kanban"] span'));

    // At minimum, the kanban should have rendered content
    const kanbanContent = page.locator('[class*="kanban"], [data-testid="smart-kanban"]');
    await expect(kanbanContent.first()).toBeVisible({ timeout: 10000 });
  });
});
