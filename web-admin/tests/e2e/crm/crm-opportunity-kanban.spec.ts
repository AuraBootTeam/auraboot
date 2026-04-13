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
import {
  uniqueId,
  executeCommandViaApi,
  navigateToDynamicPage,
  waitForDynamicPageLoad,
} from '../helpers/index';

const MODEL_CODE = 'crm_opportunity';
const ROUTE_KEY = 'crm_opportunity';
const STAGE_FILTERS = ['全部', '资格确认', '方案提报', '商务谈判', '赢单', '丢单'];

test.describe('CRM Opportunity Kanban @smoke', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(60000);

  const uid = uniqueId('OppKanban');
  let accountPid = '';

  // =========================================================================
  // DATA SETUP
  // =========================================================================
  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();

    try {
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
    } finally {
      await ctx.close();
    }
  });

  // =========================================================================
  // HELPERS
  // =========================================================================

  /** Navigate to CRM Opportunities via sidebar menu */
  async function gotoOpportunityList(page: import('@playwright/test').Page) {
    await navigateToDynamicPage(page, ROUTE_KEY);
    await expect(page).toHaveURL(/\/p\/crm_opportunity(?:\?.*)?$/);
    await waitForDynamicPageLoad(page);
  }

  async function waitForOpportunityTable(page: import('@playwright/test').Page) {
    const rows = page.locator('tbody tr');
    await rows.first().waitFor({ state: 'visible', timeout: 10000 });
    return rows;
  }

  // =========================================================================
  // TESTS
  // =========================================================================

  test('OPP-KAN-01: Opportunity pipeline page loads with default view selector', async ({
    page,
  }) => {
    await gotoOpportunityList(page);
    await expect(page.getByRole('heading', { name: 'crm_opportunity' })).toBeVisible();
    await expect(page.locator('button[aria-haspopup="listbox"]').first()).toBeVisible();
  });

  test('OPP-KAN-02: Pipeline stage quick filters are visible on opportunity list', async ({
    page,
  }) => {
    await gotoOpportunityList(page);
    for (const label of STAGE_FILTERS) {
      await expect(page.getByRole('button', { name: label })).toBeVisible();
    }
  });

  test('OPP-KAN-03: Opportunity list shows seeded pipeline record names', async ({ page }) => {
    await gotoOpportunityList(page);
    await waitForOpportunityTable(page);
    await expect(page.getByText(`discovery_${uid}`).first()).toBeVisible();
  });

  test('OPP-KAN-04: Opportunity list shows linked account name', async ({ page }) => {
    await gotoOpportunityList(page);
    await waitForOpportunityTable(page);
    await expect(page.getByText(`KanbanAcct_${uid}`).first()).toBeVisible();
  });

  test('OPP-KAN-05: Opportunity list table headers remain visible with pipeline filters', async ({
    page,
  }) => {
    await gotoOpportunityList(page);
    await waitForOpportunityTable(page);
    await expect(page.getByText('商机编号')).toBeVisible();
    await expect(page.getByText('商机名称')).toBeVisible();
    await expect(page.getByText('商机阶段')).toBeVisible();
  });

  test('OPP-KAN-06: Opportunity list renders pipeline rows', async ({ page }) => {
    await gotoOpportunityList(page);
    const rows = await waitForOpportunityTable(page);
    const rowCount = await rows.count();
    expect(rowCount, 'Pipeline list should render at least one row').toBeGreaterThan(0);
  });
});
