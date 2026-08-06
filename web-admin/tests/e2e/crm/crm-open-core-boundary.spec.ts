import { test, expect, type Page } from '../../fixtures';
import { executeCommandViaApi, uniqueId } from '../helpers';

const uid = uniqueId('CrmOpenCore');
const opportunityName = `OSS Win ${uid}`;
let opportunityPid = '';

async function openOpportunityFromSidebar(page: Page): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
  const nav = page.locator('nav, aside, [role="navigation"]').first();
  const opportunityLink = nav.locator('a[href="/p/crm_opportunity_common"]').first();
  if (!(await opportunityLink.isVisible().catch(() => false))) {
    await nav
      .getByRole('button', { name: /客户关系管理|crm/i })
      .first()
      .click();
  }
  await opportunityLink.waitFor({ state: 'visible', timeout: 10_000 });
  await opportunityLink.click();
  await expect(page).toHaveURL(/\/p\/crm_opportunity_common(?:\?.*)?$/, { timeout: 15_000 });
  const row = page.locator('tbody tr').filter({ hasText: opportunityName }).first();
  await expect(row).toBeVisible({ timeout: 10_000 });
  await row
    .getByText(/^(查看|View)$/)
    .first()
    .click();
  await expect(page).toHaveURL(/\/p\/crm_opportunity_common\/view\//, { timeout: 10_000 });
}

test.describe('CRM public-core boundary', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(60_000);

  test.beforeAll(async ({ browser }) => {
    const context = await browser.newContext({
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
    });
    const page = await context.newPage();
    try {
      const account = await executeCommandViaApi(
        page,
        'crm:create_account',
        {
          crm_acc_name: `OSS Account ${uid}`,
          crm_acc_industry: 'technology',
          crm_acc_status: 'active',
        },
        undefined,
        'create',
      );
      const opportunity = await executeCommandViaApi(
        page,
        'crm:create_opportunity',
        {
          crm_opp_name: opportunityName,
          crm_opp_account_id: account.recordId,
          crm_opp_expected_amount: 120_000,
          crm_opp_expected_close_date: '2026-12-31T18:00:00+08:00',
        },
        undefined,
        'create',
      );
      opportunityPid = opportunity.recordId;
      for (const command of [
        'crm:qualify_opportunity',
        'crm:advance_opp_to_proposal',
        'crm:advance_opp_to_negotiation',
      ]) {
        await executeCommandViaApi(page, command, {}, opportunityPid, 'state_transition');
      }
    } finally {
      await context.close();
    }
  });

  test('OSS-only win closes the opportunity without promising a Sales order', async ({ page }) => {
    const salesModel = await page.request.get('/api/meta/models/code/sl_sales_order_common');
    const salesModelBody = await salesModel.json();
    expect(salesModelBody.data, 'Sales model must be absent in the public-only runtime').toBeNull();

    await openOpportunityFromSidebar(page);
    const winButton = page.getByRole('button', { name: '赢单', exact: true }).first();
    await expect(winButton).toBeVisible({ timeout: 10_000 });
    await winButton.click();

    const confirmation = page.getByText('确认将此商机标记为赢单？', { exact: true });
    await expect(confirmation).toBeVisible({ timeout: 8_000 });
    await expect(page.getByText(/销售订单|sales order/i)).toHaveCount(0);
    const [winResponse] = await Promise.all([
      page.waitForResponse(
        (response) =>
          response.url().includes('/api/meta/commands/execute/crm:win_opportunity') &&
          response.request().method() === 'POST',
      ),
      page
        .getByRole('button', { name: /确认|确定|OK|Confirm/ })
        .last()
        .click(),
    ]);
    expect(winResponse.ok()).toBe(true);

    const stageField = page.getByText('商机阶段', { exact: true }).locator('..');
    await expect(stageField.getByText('赢单', { exact: true })).toBeVisible({ timeout: 12_000 });
    const recordResponse = await page.request.get(
      `/api/dynamic/crm_opportunity_common/${opportunityPid}`,
    );
    expect(recordResponse.ok()).toBe(true);
    const recordBody = await recordResponse.json();
    expect((recordBody.data ?? recordBody).crm_opp_stage).toBe('closed_won');
  });
});
