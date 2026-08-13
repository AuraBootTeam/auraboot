import type { TestInfo } from '@playwright/test';
import { test, expect, type Locator, type Page } from '../../fixtures';
import {
  dateOffsetStr,
  executeCommandViaApi,
  findRowInPaginatedList,
  queryFilteredList,
  todayStr,
  uniqueId,
  waitForFormReady,
} from '../helpers';

const uid = uniqueId('CrmSalesBridge');
const businessSuffix = uid.slice(-6).toUpperCase();
const opportunityName = `华东智造云商业履约-${businessSuffix}`;
const contractName = opportunityName;
const paymentPlanName = `项目验收款-${businessSuffix}`;
const overrunPlanName = `超额计划-${businessSuffix}`;
let opportunityPid = '';

type DynamicRecord = Record<string, unknown> & { pid?: string };

function field(page: Page, code: string): Locator {
  return page.getByTestId(`form-field-${code}`);
}

async function fillField(page: Page, code: string, value: string): Promise<void> {
  const wrapper = field(page, code);
  await wrapper.scrollIntoViewIfNeeded();
  const input = wrapper.locator('input:not([type="hidden"]), textarea').first();
  await expect(input, `${code} should expose a writable control`).toBeVisible({ timeout: 10_000 });
  await input.fill(value);
  await expect(input).toHaveValue(value);
}

async function chooseSelectOption(
  page: Page,
  code: string,
  option: { value?: string; label: string },
): Promise<void> {
  const trigger = page.getByTestId(`select-trigger-${code}`);
  await expect(trigger, `${code} select trigger`).toBeVisible({ timeout: 15_000 });
  await trigger.click();
  const search = page
    .locator(
      '[role="listbox"] input[placeholder*="搜索"], [role="listbox"] input[placeholder*="Search"]',
    )
    .last();
  if (await search.isVisible({ timeout: 1_500 }).catch(() => false)) {
    await search.fill(option.label);
  }
  const byValue = option.value
    ? page.locator(`[role="option"][data-value="${option.value}"]`).first()
    : page.locator('[role="option"][data-value="__never__"]');
  const target =
    option.value && (await byValue.isVisible({ timeout: 5_000 }).catch(() => false))
      ? byValue
      : page.getByRole('option', { name: option.label, exact: true }).first();
  await expect(target, `${code} option ${option.label}`).toBeVisible({ timeout: 15_000 });
  await target.click();
  await expect(trigger).toContainText(option.label, { timeout: 8_000 });
}

async function chooseOwner(
  page: Page,
  code: 'sl_ctr_owner' | 'sl_cpp_owner',
  userPid: string,
  label: string,
): Promise<void> {
  await chooseSelectOption(page, code, { value: userPid, label });
}

async function currentUser(page: Page): Promise<{ pid: string; label: string }> {
  const response = await page.request.get('/api/auth/me');
  expect(response.ok()).toBe(true);
  const body = await response.json();
  const user = body?.data?.user ?? body?.user ?? {};
  const pid = String(user.pid ?? '');
  const fallbackLabel = String(
    user.displayName ??
      user.display_name ??
      user.nickName ??
      user.nick_name ??
      user.userName ??
      user.user_name ??
      user.email ??
      '',
  );
  expect(pid).toBeTruthy();
  const searchResponse = await page.request.get('/api/admin/users/search?keyword=&size=200');
  expect(searchResponse.ok()).toBe(true);
  const searchBody = await searchResponse.json();
  const users = Array.isArray(searchBody?.data) ? searchBody.data : [];
  const current = users.find((candidate: Record<string, unknown>) => String(candidate.pid) === pid);
  const label = String(current?.displayName ?? fallbackLabel);
  expect(label).toBeTruthy();
  return { pid, label };
}

async function navigateToSalesList(
  page: Page,
  label: string,
  modelCode: string,
  pageKey?: string,
): Promise<void> {
  if (pageKey) {
    const definition = await page.request.get(`/api/pages/key/${pageKey}`);
    expect(definition.ok(), `${pageKey} page definition must be published`).toBe(true);
  }
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
  const nav = page.locator('nav, aside, [role="navigation"]').first();
  const target = nav.getByRole('link', { name: label, exact: true }).first();
  if (!(await target.isVisible().catch(() => false))) {
    const salesRoot = nav.getByRole('button', { name: 'Sales', exact: true }).first();
    await expect(salesRoot, 'Sales root menu').toBeVisible({ timeout: 10_000 });
    await salesRoot.click();
  }
  if (!(await target.isVisible().catch(() => false))) {
    const salesDirectory = nav.getByRole('button', { name: '销售管理', exact: true }).first();
    await expect(salesDirectory, '销售管理 directory').toBeVisible({ timeout: 10_000 });
    await salesDirectory.click();
  }
  await expect(target, `${label} sidebar link`).toBeVisible({ timeout: 10_000 });
  const loaded = page.waitForResponse(
    (response) =>
      response.url().includes(`/api/dynamic/${modelCode}/list`) && response.status() === 200,
    { timeout: 20_000 },
  );
  await target.click();
  await loaded;
  await expect(page.locator('table').first()).toBeVisible({ timeout: 12_000 });
}

async function clickRowAction(page: Page, row: Locator, code: string): Promise<void> {
  await row.scrollIntoViewIfNeeded();
  await row.hover();
  const direct = row.getByTestId(`row-action-${code}`).first();
  if (await direct.isVisible({ timeout: 1_500 }).catch(() => false)) {
    await direct.click();
    return;
  }
  const more = row.getByTestId('row-action-more').first();
  await expect(more, `${code} must be available in the row action menu`).toBeVisible({
    timeout: 8_000,
  });
  await more.click();
  const action = page.getByTestId('row-action-dropdown').getByTestId(`row-action-${code}`).first();
  await expect(action, `${code} row action`).toBeVisible({ timeout: 8_000 });
  await action.click();
}

async function executeRowCommand(
  page: Page,
  row: Locator,
  actionCode: string,
  commandCode: string,
): Promise<Record<string, unknown>> {
  const responsePromise = page.waitForResponse(
    (response) =>
      response.url().includes(`/api/meta/commands/execute/${commandCode}`) &&
      response.request().method() === 'POST',
    { timeout: 20_000 },
  );
  await clickRowAction(page, row, actionCode);
  const dialog = page.getByTestId('confirm-dialog');
  if (await dialog.isVisible({ timeout: 2_000 }).catch(() => false)) {
    await dialog.getByTestId('confirm-ok').click();
  }
  const response = await responsePromise;
  const body = await response.json().catch(() => ({}));
  expect(response.ok(), `${commandCode}: ${JSON.stringify(body)}`).toBe(true);
  expect(String(body.code), `${commandCode}: ${JSON.stringify(body)}`).toBe('0');
  return body;
}

async function executeToolbarCommand(
  page: Page,
  actionCode: string,
  commandCode: string,
): Promise<Record<string, unknown>> {
  const responsePromise = page.waitForResponse(
    (response) =>
      response.url().includes(`/api/meta/commands/execute/${commandCode}`) &&
      response.request().method() === 'POST',
    { timeout: 20_000 },
  );
  await page.getByTestId(`toolbar-btn-${actionCode}`).click();
  const dialog = page.getByTestId('confirm-dialog');
  if (await dialog.isVisible({ timeout: 2_000 }).catch(() => false)) {
    await dialog.getByTestId('confirm-ok').click();
  }
  const response = await responsePromise;
  const body = await response.json().catch(() => ({}));
  expect(response.ok(), `${commandCode}: ${JSON.stringify(body)}`).toBe(true);
  expect(String(body.code), `${commandCode}: ${JSON.stringify(body)}`).toBe('0');
  return body;
}

async function saveForm(page: Page, commandCode: string): Promise<Record<string, unknown>> {
  const responsePromise = page.waitForResponse(
    (response) =>
      response.url().includes(`/api/meta/commands/execute/${commandCode}`) &&
      response.request().method() === 'POST',
    { timeout: 20_000 },
  );
  await page.getByTestId('form-btn-save').click();
  const response = await responsePromise;
  const body = await response.json().catch(() => ({}));
  expect(response.ok(), `${commandCode}: ${JSON.stringify(body)}`).toBe(true);
  expect(String(body.code), `${commandCode}: ${JSON.stringify(body)}`).toBe('0');
  return body;
}

async function saveFormExpectRejected(
  page: Page,
  commandCode: string,
): Promise<Record<string, unknown>> {
  const responsePromise = page.waitForResponse(
    (response) =>
      response.url().includes(`/api/meta/commands/execute/${commandCode}`) &&
      response.request().method() === 'POST',
    { timeout: 20_000 },
  );
  await page.getByTestId('form-btn-save').click();
  const response = await responsePromise;
  const body = await response.json().catch(() => ({}));
  expect(response.ok() && String(body.code) === '0', JSON.stringify(body)).toBe(false);
  return body;
}

async function readRecord(page: Page, modelCode: string, pid: string): Promise<DynamicRecord> {
  const response = await page.request.get(`/api/dynamic/${modelCode}/${pid}`);
  expect(response.ok()).toBe(true);
  const body = await response.json();
  return (body.data ?? body) as DynamicRecord;
}

async function capture(page: Page, testInfo: TestInfo, fileName: string): Promise<void> {
  await page.screenshot({ path: testInfo.outputPath(fileName), fullPage: true });
}

async function captureViewport(page: Page, testInfo: TestInfo, fileName: string): Promise<void> {
  await page.screenshot({ path: testInfo.outputPath(fileName), fullPage: false });
}

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

test.describe('CRM win → contract → collection commercial fulfillment', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(180_000);
  test.use({ viewport: { width: 1440, height: 960 } });

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
          crm_acc_name: `华东智造云-${businessSuffix}`,
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
          crm_ol_product_name: `工业控制主板-${businessSuffix}`,
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

  test('runs the full commercial journey through real Sales pages and leaves auditable evidence', async ({
    page,
  }, testInfo) => {
    const salesModel = await page.request.get('/api/meta/models/code/sl_sales_order_common');
    expect(salesModel.ok()).toBe(true);
    expect((await salesModel.json()).data?.code).toBe('sl_sales_order_common');
    const admin = await currentUser(page);

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
    expect(lines[0].sl_sol_item_description).toBe(`工业控制主板-${businessSuffix}`);
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

    const contracts = await queryFilteredList(
      page,
      'sl_sales_contract_common',
      'sl_ctr_opportunity_id',
      opportunityPid,
      { operator: 'EQ' },
    );
    expect(contracts).toHaveLength(1);
    const contractPid = String(contracts[0].pid);
    expect(contracts[0].sl_ctr_name).toBe(contractName);
    expect(contracts[0].sl_ctr_status).toBe('draft');
    expect(contracts[0].sl_ctr_order_id).toBe(orderPid);

    await navigateToSalesList(
      page,
      '销售合同',
      'sl_sales_contract_common',
      'sl_sales_contract_common_list',
    );
    const contractRow = await findRowInPaginatedList(page, contractName, 15_000);
    await clickRowAction(page, contractRow, 'edit');
    await expect(page).toHaveURL(/\/p\/sl_sales_contract_common\/edit\//, { timeout: 12_000 });
    await waitForFormReady(page);
    await expect(field(page, 'sl_ctr_name')).toContainText('合同名称');
    await chooseOwner(page, 'sl_ctr_owner', admin.pid, admin.label);
    await fillField(page, 'sl_ctr_start_date', todayStr());
    await fillField(page, 'sl_ctr_end_date', dateOffsetStr(365));
    await fillField(page, 'sl_ctr_payment_terms', '全额到款后进入合同结案，银行流水作为核销凭证。');
    await fillField(page, 'sl_ctr_remark', `CRM 赢单自动生成，验收批次 ${businessSuffix}`);
    await capture(page, testInfo, '01-contract-edit.png');
    await saveForm(page, 'sl:update_sales_contract');

    await navigateToSalesList(page, '销售合同', 'sl_sales_contract_common');
    let refreshedContractRow = await findRowInPaginatedList(page, contractName, 15_000);
    await executeRowCommand(page, refreshedContractRow, 'submit', 'sl:submit_sales_contract');
    let contract = await readRecord(page, 'sl_sales_contract_common', contractPid);
    expect(contract.sl_ctr_status).toBe('pending');

    await navigateToSalesList(page, '销售合同', 'sl_sales_contract_common');
    refreshedContractRow = await findRowInPaginatedList(page, contractName, 15_000);
    await executeRowCommand(page, refreshedContractRow, 'approve', 'sl:approve_sales_contract');
    contract = await readRecord(page, 'sl_sales_contract_common', contractPid);
    expect(contract.sl_ctr_status).toBe('active');
    expect(contract.sl_ctr_order_id).toBe(orderPid);
    const linkedOrders = await queryFilteredList(
      page,
      'sl_sales_order_common',
      'sl_so_contract_id',
      contractPid,
      { operator: 'EQ' },
    );
    expect(linkedOrders).toHaveLength(1);
    expect(String(linkedOrders[0].pid)).toBe(orderPid);

    await navigateToSalesList(page, '回款计划', 'sl_contract_payment_plan_common');
    await page.getByTestId('toolbar-btn-create').click();
    await expect(page).toHaveURL(/\/p\/sl_contract_payment_plan_common\/(?:new|create)/, {
      timeout: 12_000,
    });
    expect(
      (await page.request.get('/api/pages/key/sl_contract_payment_plan_common_form')).ok(),
    ).toBe(true);
    await waitForFormReady(page);
    await page.getByTestId('form-btn-save').click();
    await expect(field(page, 'sl_cpp_contract_id')).toContainText('请选择销售合同', {
      timeout: 5_000,
    });
    await expect(field(page, 'sl_cpp_owner')).toContainText('请选择负责人');
    await expect(field(page, 'sl_cpp_name')).toContainText('请填写计划名称');
    await fillField(page, 'sl_cpp_name', paymentPlanName);
    await chooseSelectOption(page, 'sl_cpp_contract_id', {
      value: contractPid,
      label: contractName,
    });
    await chooseOwner(page, 'sl_cpp_owner', admin.pid, admin.label);
    await fillField(page, 'sl_cpp_sequence', '1');
    await fillField(page, 'sl_cpp_due_date', dateOffsetStr(30));
    await fillField(page, 'sl_cpp_amount', '150');
    await fillField(page, 'sl_cpp_remark', '合同生效后 30 日内全额回款');
    await expect(page.getByText('请选择销售合同', { exact: true }).first()).toBeHidden({
      timeout: 8_000,
    });
    await capture(page, testInfo, '02-payment-plan-create.png');
    await saveForm(page, 'sl:create_contract_payment_plan');

    const plans = await queryFilteredList(
      page,
      'sl_contract_payment_plan_common',
      'sl_cpp_contract_id',
      contractPid,
      { operator: 'EQ' },
    );
    expect(plans).toHaveLength(1);
    const planPid = String(plans[0].pid);
    expect(plans[0].sl_cpp_status).toBe('planned');
    expect(Number(plans[0].sl_cpp_amount)).toBe(150);

    await navigateToSalesList(page, '回款计划', 'sl_contract_payment_plan_common');
    await page.getByTestId('toolbar-btn-create').click();
    await waitForFormReady(page);
    await fillField(page, 'sl_cpp_name', overrunPlanName);
    await chooseSelectOption(page, 'sl_cpp_contract_id', {
      value: contractPid,
      label: contractName,
    });
    await chooseOwner(page, 'sl_cpp_owner', admin.pid, admin.label);
    await fillField(page, 'sl_cpp_sequence', '2');
    await fillField(page, 'sl_cpp_due_date', dateOffsetStr(60));
    await fillField(page, 'sl_cpp_amount', '1');
    const overrunBody = await saveFormExpectRejected(page, 'sl:create_contract_payment_plan');
    expect(JSON.stringify(overrunBody)).toMatch(/exceeds contract amount/i);
    const plansAfterRejectedOverrun = await queryFilteredList(
      page,
      'sl_contract_payment_plan_common',
      'sl_cpp_contract_id',
      contractPid,
      { operator: 'EQ' },
    );
    expect(plansAfterRejectedOverrun).toHaveLength(1);
    expect(plansAfterRejectedOverrun[0].sl_cpp_name).toBe(paymentPlanName);

    await navigateToSalesList(page, '收款管理', 'sl_sales_collection_common');
    await page.getByTestId('toolbar-btn-create').click();
    await expect(page).toHaveURL(/\/p\/sl_sales_collection_common\/(?:new|create)/, {
      timeout: 12_000,
    });
    expect((await page.request.get('/api/pages/key/sl_sales_collection_common_form')).ok()).toBe(
      true,
    );
    await waitForFormReady(page);
    await chooseSelectOption(page, 'sl_col_order_id', {
      value: orderPid,
      label: String(order.sl_so_code),
    });
    await chooseSelectOption(page, 'sl_col_contract_id', {
      value: contractPid,
      label: contractName,
    });
    await chooseSelectOption(page, 'sl_col_payment_plan_id', {
      value: planPid,
      label: paymentPlanName,
    });
    await fillField(page, 'sl_col_date', todayStr());
    await fillField(page, 'sl_col_amount', '150');
    await chooseSelectOption(page, 'sl_col_method', {
      value: 'bank_transfer',
      label: '银行转账',
    });
    await fillField(page, 'sl_col_bank_ref', `BANK-${uid}`);
    await fillField(page, 'sl_col_remark', '真栈全额回款核销');
    await capture(page, testInfo, '03-collection-create.png');
    await saveForm(page, 'sl:create_sales_collection');

    const collections = await queryFilteredList(
      page,
      'sl_sales_collection_common',
      'sl_col_payment_plan_id',
      planPid,
      { operator: 'EQ' },
    );
    expect(collections).toHaveLength(1);
    const collectionPid = String(collections[0].pid);
    const collectionCode = String(collections[0].sl_col_code);
    expect(collections[0].sl_col_status).toBe('draft');

    await navigateToSalesList(page, '收款管理', 'sl_sales_collection_common');
    const collectionRow = await findRowInPaginatedList(page, collectionCode, 15_000);
    await executeRowCommand(page, collectionRow, 'confirm', 'sl:confirm_sales_collection');

    const confirmedCollection = await readRecord(page, 'sl_sales_collection_common', collectionPid);
    expect(confirmedCollection.sl_col_status).toBe('confirmed');
    expect(Number(confirmedCollection.sl_col_amount)).toBe(150);
    const paidPlan = await readRecord(page, 'sl_contract_payment_plan_common', planPid);
    expect(paidPlan.sl_cpp_status).toBe('paid');
    expect(Number(paidPlan.sl_cpp_collected_amount)).toBe(150);
    contract = await readRecord(page, 'sl_sales_contract_common', contractPid);
    expect(contract.sl_ctr_status).toBe('active');
    expect(contract.sl_ctr_payment_status).toBe('fully_paid');
    expect(Number(contract.sl_ctr_collected_amount)).toBe(150);
    const paidOrder = await readRecord(page, 'sl_sales_order_common', orderPid);
    expect(paidOrder.sl_so_payment_status).toBe('fully_paid');

    await navigateToSalesList(page, '回款计划', 'sl_contract_payment_plan_common');
    const paidPlanRow = await findRowInPaginatedList(page, paymentPlanName, 15_000);
    await clickRowAction(page, paidPlanRow, 'detail');
    await expect(page).toHaveURL(/\/p\/sl_contract_payment_plan_common\/view\//, {
      timeout: 12_000,
    });
    await expect(page.getByText(collectionCode, { exact: true })).toBeVisible({ timeout: 10_000 });

    await navigateToSalesList(page, '收款管理', 'sl_sales_collection_common');
    const confirmedCollectionRow = await findRowInPaginatedList(page, collectionCode, 15_000);
    await clickRowAction(page, confirmedCollectionRow, 'detail');
    await expect(page).toHaveURL(/\/p\/sl_sales_collection_common\/view\//, {
      timeout: 12_000,
    });
    await expect(page.getByText('已确认', { exact: true }).first()).toBeVisible({
      timeout: 10_000,
    });

    await navigateToSalesList(page, '销售合同', 'sl_sales_contract_common');
    refreshedContractRow = await findRowInPaginatedList(page, contractName, 15_000);
    await clickRowAction(page, refreshedContractRow, 'detail');
    await expect(page).toHaveURL(/\/p\/sl_sales_contract_common\/view\//, { timeout: 12_000 });
    await expect(page.getByText('回款计划', { exact: true }).last()).toBeVisible({
      timeout: 10_000,
    });
    await expect(page.getByText('履约订单', { exact: true }).last()).toBeVisible({
      timeout: 10_000,
    });
    await expect(page.getByText('实收记录', { exact: true }).last()).toBeVisible({
      timeout: 10_000,
    });
    const planEvidence = page.getByText(paymentPlanName, { exact: true });
    await expect(planEvidence).toHaveCount(2, { timeout: 10_000 });
    await expect(planEvidence.first()).toBeVisible();
    await expect(planEvidence.last()).toBeVisible();
    await expect(page.getByText(collectionCode, { exact: true })).toBeVisible({ timeout: 10_000 });
    await captureViewport(page, testInfo, '04-contract-summary.png');
    await page.getByText(collectionCode, { exact: true }).scrollIntoViewIfNeeded();
    await captureViewport(page, testInfo, '05-contract-audit-chain.png');
    await executeToolbarCommand(page, 'complete', 'sl:complete_sales_contract');
    contract = await readRecord(page, 'sl_sales_contract_common', contractPid);
    expect(contract.sl_ctr_status).toBe('completed');
    await expect(page.getByText(/已完成|Completed/).first()).toBeVisible({ timeout: 12_000 });
    await captureViewport(page, testInfo, '06-contract-completed.png');
  });
});
