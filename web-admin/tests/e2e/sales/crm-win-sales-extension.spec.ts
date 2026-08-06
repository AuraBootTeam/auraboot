import { test, expect, type Page } from '../../fixtures';
import { executeCommandViaApi, queryFilteredList, uniqueId } from '../helpers';

const uid = uniqueId('CrmSalesBridge');
const opportunityName = `CRM Sales Bridge ${uid}`;
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

test.describe('CRM win → optional Sales extension', () => {
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
          crm_acc_name: `CRM Sales Account ${uid}`,
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
          crm_opp_currency_code: 'CNY',
          crm_opp_expected_amount: 150,
          crm_opp_expected_close_date: '2026-12-31T18:00:00+08:00',
        },
        undefined,
        'create',
      );
      opportunityPid = opportunity.recordId;

      await executeCommandViaApi(
        page,
        'crm:create_opp_line',
        {
          crm_ol_opportunity_id: opportunityPid,
          crm_ol_product_name: `Bridge Product ${uid}`,
          crm_ol_quantity: 2,
          crm_ol_unit_price: 75,
          crm_ol_unit_cost: 50,
          crm_ol_amount: 150,
        },
        undefined,
        'create',
      );
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

  test('winning in CRM atomically creates one draft Sales order with replicated lines', async ({
    page,
  }) => {
    const salesModel = await page.request.get('/api/meta/models/code/sl_sales_order_common');
    expect(salesModel.ok()).toBe(true);
    expect((await salesModel.json()).data?.code).toBe('sl_sales_order_common');

    await openOpportunityFromSidebar(page);
    const winButton = page.getByRole('button', { name: '赢单', exact: true }).first();
    await expect(winButton).toBeVisible({ timeout: 10_000 });
    await winButton.click();
    await expect(page.getByText('确认将此商机标记为赢单？', { exact: true })).toBeVisible({
      timeout: 8_000,
    });

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
    const winBody = await winResponse.json();
    expect(winBody.code).toBe('0');

    const stageField = page.getByText('商机阶段', { exact: true }).locator('..');
    await expect(stageField.getByText('赢单', { exact: true })).toBeVisible({ timeout: 12_000 });

    const orders = await queryFilteredList(
      page,
      'sl_sales_order_common',
      'sl_so_source_opp_id',
      opportunityPid,
      { operator: 'EQ' },
    );
    expect(orders).toHaveLength(1);
    const order = orders[0];
    expect(order.sl_so_status).toBe('draft');
    expect(Number(order.sl_so_total_qty)).toBe(2);
    expect(Number(order.sl_so_total_amount)).toBe(150);

    const orderPid = String(order.pid);
    const lines = await queryFilteredList(
      page,
      'sl_sales_order_line_common',
      'sl_sol_order_id',
      orderPid,
      { operator: 'EQ' },
    );
    expect(lines).toHaveLength(1);
    expect(lines[0].sl_sol_item_description).toBe(`Bridge Product ${uid}`);
    expect(Number(lines[0].sl_sol_qty)).toBe(2);
    expect(Number(lines[0].sl_sol_amount)).toBe(150);

    const replayRequest = winResponse.request().postDataJSON();
    const replay = await page.request.post('/api/meta/commands/execute/crm:win_opportunity', {
      data: replayRequest,
    });
    expect(replay.ok()).toBe(true);
    const replayBody = await replay.json();
    expect(replayBody.code).toBe('0');
    expect(replayBody.data?.idempotentReplay).toBe(true);

    const ordersAfterReplay = await queryFilteredList(
      page,
      'sl_sales_order_common',
      'sl_so_source_opp_id',
      opportunityPid,
      { operator: 'EQ' },
    );
    expect(ordersAfterReplay).toHaveLength(1);
  });
});
