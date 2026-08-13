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

const uid = uniqueId('CrmReceivables');
const suffix = uid.slice(-6).toUpperCase();
const accountName = `Cordys 应收标杆客户-${suffix}`;
const opportunityName = `CRM 应收退款闭环-${suffix}`;
const planName = `项目全额回款-${suffix}`;
const refundEvidence = `REFUND-BANK-${uid}`;

let accountPid = '';
let opportunityPid = '';
let orderPid = '';
let orderCode = '';
let contractPid = '';
let contractName = '';
let planPid = '';
let collectionPid = '';
let collectionCode = '';

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

async function currentUser(page: Page): Promise<{ pid: string; label: string }> {
  const meResponse = await page.request.get('/api/auth/me', { timeout: 20_000 });
  expect(meResponse.ok()).toBe(true);
  const meBody = await meResponse.json();
  const user = meBody?.data?.user ?? meBody?.user ?? {};
  const pid = String(user.pid ?? '');
  const label = String(user.displayName ?? user.display_name ?? user.userName ?? user.email ?? '');
  expect(pid).toBeTruthy();
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
    await nav.getByRole('button', { name: 'Sales', exact: true }).first().click();
  }
  if (!(await target.isVisible().catch(() => false))) {
    await nav.getByRole('button', { name: '销售管理', exact: true }).first().click();
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
  expectSuccess = true,
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
  if (expectSuccess) {
    expect(response.ok(), `${commandCode}: ${JSON.stringify(body)}`).toBe(true);
    expect(String(body.code), `${commandCode}: ${JSON.stringify(body)}`).toBe('0');
  } else {
    expect(response.ok() && String(body.code) === '0', JSON.stringify(body)).toBe(false);
  }
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

async function readRecord(page: Page, modelCode: string, pid: string): Promise<DynamicRecord> {
  const response = await page.request.get(`/api/dynamic/${modelCode}/${pid}`);
  expect(response.ok(), `${modelCode}/${pid} should be readable`).toBe(true);
  const body = await response.json();
  return (body.data ?? body) as DynamicRecord;
}

async function capture(page: Page, testInfo: TestInfo, fileName: string): Promise<void> {
  await page.screenshot({ path: testInfo.outputPath(fileName), fullPage: true });
}

async function captureViewport(page: Page, testInfo: TestInfo, fileName: string): Promise<void> {
  await page.screenshot({ path: testInfo.outputPath(fileName), fullPage: false });
}

test.describe('CRM receivables → allocation → reversal → credit → refund', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(240_000);
  test.use({ viewport: { width: 1440, height: 960 } });

  test.beforeAll(async ({ browser }, testInfo) => {
    testInfo.setTimeout(180_000);
    const context = await browser.newContext({
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
    });
    const page = await context.newPage();
    try {
      const admin = await currentUser(page);
      const account = await executeCommandViaApi(
        page,
        'crm:create_account',
        {
          crm_acc_name: accountName,
          crm_acc_industry: 'technology',
          crm_acc_status: 'active',
        },
        undefined,
        'create',
      );
      expect(account.code).toBe('0');
      accountPid = account.recordId;

      const opportunity = await executeCommandViaApi(
        page,
        'crm:create_opportunity',
        {
          crm_opp_name: opportunityName,
          crm_opp_account_id: accountPid,
          crm_opp_currency_code: 'CNY',
          crm_opp_expected_amount: 1000,
          crm_opp_expected_close_date: '2026-12-31T18:00:00+08:00',
        },
        undefined,
        'create',
      );
      expect(opportunity.code).toBe('0');
      opportunityPid = opportunity.recordId;

      expect(
        (
          await executeCommandViaApi(
            page,
            'crm:create_opp_line',
            {
              crm_ol_opportunity_id: opportunityPid,
              crm_ol_product_name: `企业服务订阅-${suffix}`,
              crm_ol_quantity: 1,
              crm_ol_unit_price: 1000,
              crm_ol_unit_cost: 600,
              crm_ol_amount: 1000,
            },
            undefined,
            'create',
          )
        ).code,
      ).toBe('0');
      for (const command of [
        'crm:qualify_opportunity',
        'crm:advance_opp_to_proposal',
        'crm:advance_opp_to_negotiation',
        'crm:win_opportunity',
      ]) {
        expect(
          (await executeCommandViaApi(page, command, {}, opportunityPid, 'state_transition')).code,
        ).toBe('0');
      }

      const orders = await queryFilteredList(
        page,
        'sl_sales_order_common',
        'sl_so_source_opp_id',
        opportunityPid,
        { operator: 'EQ' },
      );
      const contracts = await queryFilteredList(
        page,
        'sl_sales_contract_common',
        'sl_ctr_opportunity_id',
        opportunityPid,
        { operator: 'EQ' },
      );
      expect(orders).toHaveLength(1);
      expect(contracts).toHaveLength(1);
      orderPid = String(orders[0].pid);
      orderCode = String(orders[0].sl_so_code);
      contractPid = String(contracts[0].pid);
      contractName = String(contracts[0].sl_ctr_name);

      expect(
        (
          await executeCommandViaApi(
            page,
            'sl:update_sales_contract',
            {
              sl_ctr_name: contractName,
              sl_ctr_account_id: accountPid,
              sl_ctr_opportunity_id: opportunityPid,
              sl_ctr_owner: admin.pid,
              sl_ctr_start_date: todayStr(),
              sl_ctr_end_date: dateOffsetStr(365),
              sl_ctr_amount: 1000,
              sl_ctr_currency_code: 'CNY',
              sl_ctr_payment_terms: '全额到账后按客户发票核销；退款必须保留付款凭证。',
              sl_ctr_remark: `Cordys 对标应收闭环 ${suffix}`,
            },
            contractPid,
            'update',
          )
        ).code,
      ).toBe('0');
      expect(
        (
          await executeCommandViaApi(
            page,
            'sl:submit_sales_contract',
            {},
            contractPid,
            'state_transition',
          )
        ).code,
      ).toBe('0');
      expect(
        (
          await executeCommandViaApi(
            page,
            'sl:approve_sales_contract',
            {},
            contractPid,
            'state_transition',
          )
        ).code,
      ).toBe('0');

      const plan = await executeCommandViaApi(
        page,
        'sl:create_contract_payment_plan',
        {
          sl_cpp_name: planName,
          sl_cpp_contract_id: contractPid,
          sl_cpp_owner: admin.pid,
          sl_cpp_sequence: 1,
          sl_cpp_due_date: dateOffsetStr(30),
          sl_cpp_amount: 1000,
          sl_cpp_remark: '应收闭环测试全额回款',
        },
        undefined,
        'create',
      );
      expect(plan.code).toBe('0');
      planPid = plan.recordId;

      const collection = await executeCommandViaApi(
        page,
        'sl:create_sales_collection',
        {
          sl_col_order_id: orderPid,
          sl_col_contract_id: contractPid,
          sl_col_payment_plan_id: planPid,
          sl_col_date: todayStr(),
          sl_col_amount: 1000,
          sl_col_method: 'bank_transfer',
          sl_col_bank_ref: `COLLECTION-BANK-${uid}`,
          sl_col_remark: '到账后等待应收核销',
        },
        undefined,
        'create',
      );
      expect(collection.code).toBe('0');
      collectionPid = collection.recordId;
      expect(
        (
          await executeCommandViaApi(
            page,
            'sl:confirm_sales_collection',
            {},
            collectionPid,
            'state_transition',
          )
        ).code,
      ).toBe('0');
      collectionCode = String(
        (await readRecord(page, 'sl_sales_collection_common', collectionPid)).sl_col_code,
      );
    } finally {
      await context.close();
    }
  });

  test('operates every new receivables state through real pages with auditable evidence', async ({
    page,
  }, testInfo) => {
    await navigateToSalesList(
      page,
      '客户发票',
      'sl_customer_invoice_common',
      'sl_customer_invoice_common_list',
    );
    await page.getByTestId('toolbar-btn-create').click();
    await waitForFormReady(page);
    await page.getByTestId('form-btn-save').click();
    await expect(field(page, 'sl_inv_account_id')).toContainText('请选择客户');
    await expect(field(page, 'sl_inv_order_id')).toContainText('请选择销售订单');
    await chooseSelectOption(page, 'sl_inv_account_id', { value: accountPid, label: accountName });
    await chooseSelectOption(page, 'sl_inv_order_id', { value: orderPid, label: orderCode });
    await chooseSelectOption(page, 'sl_inv_contract_id', {
      value: contractPid,
      label: contractName,
    });
    await fillField(page, 'sl_inv_issue_date', todayStr());
    await fillField(page, 'sl_inv_due_date', dateOffsetStr(30));
    await fillField(page, 'sl_inv_amount', '1000');
    await chooseSelectOption(page, 'sl_inv_currency_code', { value: 'cny', label: '人民币' });
    await fillField(page, 'sl_inv_tax_document_ref', `TAX-EXT-${uid}`);
    await fillField(page, 'sl_inv_remark', '商业应收发票；法定税票由外部税务系统引用。');
    await expect(page.getByText('请选择客户', { exact: true }).first()).toBeHidden({
      timeout: 8_000,
    });
    await capture(page, testInfo, '01-customer-invoice-create.png');
    await saveForm(page, 'sl:create_customer_invoice');

    const invoices = await queryFilteredList(
      page,
      'sl_customer_invoice_common',
      'sl_inv_order_id',
      orderPid,
      { operator: 'EQ' },
    );
    expect(invoices).toHaveLength(1);
    const invoicePid = String(invoices[0].pid);
    const invoiceCode = String(invoices[0].sl_inv_code);
    expect(invoices[0].sl_inv_status).toBe('draft');

    await navigateToSalesList(page, '客户发票', 'sl_customer_invoice_common');
    let invoiceRow = await findRowInPaginatedList(page, invoiceCode, 15_000);
    await executeRowCommand(page, invoiceRow, 'issue', 'sl:issue_customer_invoice');
    let invoice = await readRecord(page, 'sl_customer_invoice_common', invoicePid);
    expect(invoice.sl_inv_status).toBe('issued');
    expect(Number(invoice.sl_inv_outstanding_amount)).toBe(1000);

    await navigateToSalesList(
      page,
      '收款核销',
      'sl_invoice_allocation_common',
      'sl_invoice_allocation_common_list',
    );
    await page.getByTestId('toolbar-btn-create').click();
    await waitForFormReady(page);
    await chooseSelectOption(page, 'sl_ia_invoice_id', {
      value: invoicePid,
      label: invoiceCode,
    });
    await chooseSelectOption(page, 'sl_ia_collection_id', {
      value: collectionPid,
      label: collectionCode,
    });
    await fillField(page, 'sl_ia_date', todayStr());
    await fillField(page, 'sl_ia_amount', '1000');
    await fillField(page, 'sl_ia_remark', '银行到账与客户发票全额匹配');
    await capture(page, testInfo, '02-allocation-create.png');
    await saveForm(page, 'sl:create_invoice_allocation');

    let allocations = await queryFilteredList(
      page,
      'sl_invoice_allocation_common',
      'sl_ia_invoice_id',
      invoicePid,
      { operator: 'EQ' },
    );
    expect(allocations).toHaveLength(1);
    const firstAllocationPid = String(allocations[0].pid);
    const firstAllocationCode = String(allocations[0].sl_ia_code);
    await navigateToSalesList(page, '收款核销', 'sl_invoice_allocation_common');
    let allocationRow = await findRowInPaginatedList(page, firstAllocationCode, 15_000);
    await executeRowCommand(page, allocationRow, 'post', 'sl:post_invoice_allocation');
    invoice = await readRecord(page, 'sl_customer_invoice_common', invoicePid);
    expect(invoice.sl_inv_status).toBe('settled');
    expect(Number(invoice.sl_inv_allocated_amount)).toBe(1000);
    expect(Number(invoice.sl_inv_outstanding_amount)).toBe(0);

    await navigateToSalesList(page, '客户发票', 'sl_customer_invoice_common');
    invoiceRow = await findRowInPaginatedList(page, invoiceCode, 15_000);
    await clickRowAction(page, invoiceRow, 'detail');
    await expect(page.getByText('发票与余额摘要', { exact: true })).toBeVisible();
    await expect(page.getByText('已结清', { exact: true }).first()).toBeVisible();
    await capture(page, testInfo, '03-invoice-settled.png');

    await navigateToSalesList(page, '收款核销', 'sl_invoice_allocation_common');
    allocationRow = await findRowInPaginatedList(page, firstAllocationCode, 15_000);
    await executeRowCommand(page, allocationRow, 'reverse', 'sl:reverse_invoice_allocation');
    invoice = await readRecord(page, 'sl_customer_invoice_common', invoicePid);
    expect(invoice.sl_inv_status).toBe('issued');
    expect(Number(invoice.sl_inv_allocated_amount)).toBe(0);
    expect(Number(invoice.sl_inv_outstanding_amount)).toBe(1000);
    const reversals = await queryFilteredList(
      page,
      'sl_allocation_reversal_common',
      'sl_iar_allocation_id',
      firstAllocationPid,
      { operator: 'EQ' },
    );
    expect(reversals).toHaveLength(1);
    expect(Number(reversals[0].sl_iar_amount)).toBe(1000);

    await navigateToSalesList(page, '收款核销', 'sl_invoice_allocation_common');
    allocationRow = await findRowInPaginatedList(page, firstAllocationCode, 15_000);
    await clickRowAction(page, allocationRow, 'detail');
    await expect(page.getByText('冲销审计事实', { exact: true })).toBeVisible();
    const reversalEvidence = page.getByText(String(reversals[0].sl_iar_code), { exact: true });
    await expect(reversalEvidence).toBeVisible({ timeout: 12_000 });
    await expect(page.getByText('核销冲销；原核销事实保留', { exact: true })).toBeVisible();
    await reversalEvidence.scrollIntoViewIfNeeded();
    await captureViewport(page, testInfo, '04-allocation-reversal-audit.png');

    await navigateToSalesList(page, '收款核销', 'sl_invoice_allocation_common');
    await page.getByTestId('toolbar-btn-create').click();
    await waitForFormReady(page);
    await chooseSelectOption(page, 'sl_ia_invoice_id', {
      value: invoicePid,
      label: invoiceCode,
    });
    await chooseSelectOption(page, 'sl_ia_collection_id', {
      value: collectionPid,
      label: collectionCode,
    });
    await fillField(page, 'sl_ia_date', todayStr());
    await fillField(page, 'sl_ia_amount', '1000');
    await fillField(page, 'sl_ia_remark', '冲销纠正后重新核销');
    await saveForm(page, 'sl:create_invoice_allocation');
    allocations = await queryFilteredList(
      page,
      'sl_invoice_allocation_common',
      'sl_ia_invoice_id',
      invoicePid,
      { operator: 'EQ' },
    );
    expect(allocations).toHaveLength(2);
    const secondAllocation = allocations.find(
      (candidate: DynamicRecord) => String(candidate.pid) !== firstAllocationPid,
    );
    expect(secondAllocation).toBeTruthy();
    const secondAllocationCode = String(secondAllocation?.sl_ia_code);
    await navigateToSalesList(page, '收款核销', 'sl_invoice_allocation_common');
    allocationRow = await findRowInPaginatedList(page, secondAllocationCode, 15_000);
    await executeRowCommand(page, allocationRow, 'post', 'sl:post_invoice_allocation');

    await navigateToSalesList(
      page,
      '信用凭证',
      'sl_credit_memo_common',
      'sl_credit_memo_common_list',
    );
    await page.getByTestId('toolbar-btn-create').click();
    await waitForFormReady(page);
    await chooseSelectOption(page, 'sl_cm_customer_id', {
      value: accountPid,
      label: accountName,
    });
    await chooseSelectOption(page, 'sl_cm_so_id', { value: orderPid, label: orderCode });
    await chooseSelectOption(page, 'sl_cm_invoice_id', {
      value: invoicePid,
      label: invoiceCode,
    });
    await chooseSelectOption(page, 'sl_cm_collection_id', {
      value: collectionPid,
      label: collectionCode,
    });
    await chooseSelectOption(page, 'sl_cm_resolution', {
      value: 'cash_refund',
      label: '退回客户',
    });
    await fillField(page, 'sl_cm_amount', '200');
    await fillField(page, 'sl_cm_reason', '客户服务范围缩减，退回已结算款项。');
    await capture(page, testInfo, '05-credit-memo-create.png');
    await saveForm(page, 'sl:create_credit_memo');

    const credits = await queryFilteredList(
      page,
      'sl_credit_memo_common',
      'sl_cm_invoice_id',
      invoicePid,
      { operator: 'EQ' },
    );
    expect(credits).toHaveLength(1);
    const creditCode = String(credits[0].sl_cm_code);
    await navigateToSalesList(page, '信用凭证', 'sl_credit_memo_common');
    let creditRow = await findRowInPaginatedList(page, creditCode, 15_000);
    await executeRowCommand(page, creditRow, 'approve', 'sl:approve_credit_memo');
    await navigateToSalesList(page, '信用凭证', 'sl_credit_memo_common');
    creditRow = await findRowInPaginatedList(page, creditCode, 15_000);
    await executeRowCommand(page, creditRow, 'apply', 'sl:apply_credit_memo');

    const refunds = await queryFilteredList(
      page,
      'sl_customer_refund_common',
      'sl_ref_invoice_id',
      invoicePid,
      { operator: 'EQ' },
    );
    expect(refunds).toHaveLength(1);
    const refundPid = String(refunds[0].pid);
    const refundCode = String(refunds[0].sl_ref_code);
    invoice = await readRecord(page, 'sl_customer_invoice_common', invoicePid);
    expect(invoice.sl_inv_status).toBe('refund_pending');
    expect(Number(invoice.sl_inv_credited_amount)).toBe(200);
    expect(Number(invoice.sl_inv_refund_due_amount)).toBe(200);

    await navigateToSalesList(page, '客户发票', 'sl_customer_invoice_common');
    invoiceRow = await findRowInPaginatedList(page, invoiceCode, 15_000);
    await clickRowAction(page, invoiceRow, 'detail');
    await expect(page.getByText('待退款', { exact: true }).first()).toBeVisible();
    await expect(page.getByText(refundCode, { exact: true })).toBeVisible();
    await capture(page, testInfo, '06-invoice-refund-pending.png');

    await navigateToSalesList(
      page,
      '客户退款',
      'sl_customer_refund_common',
      'sl_customer_refund_common_list',
    );
    let refundRow = await findRowInPaginatedList(page, refundCode, 15_000);
    await executeRowCommand(page, refundRow, 'approve', 'sl:approve_customer_refund');
    await navigateToSalesList(page, '客户退款', 'sl_customer_refund_common');
    refundRow = await findRowInPaginatedList(page, refundCode, 15_000);
    const rejectedPay = await executeRowCommand(
      page,
      refundRow,
      'pay',
      'sl:pay_customer_refund',
      false,
    );
    expect(JSON.stringify(rejectedPay)).toMatch(/payment evidence|凭证|method|reference/i);
    expect((await readRecord(page, 'sl_customer_refund_common', refundPid)).sl_ref_status).toBe(
      'approved',
    );

    await navigateToSalesList(page, '客户退款', 'sl_customer_refund_common');
    refundRow = await findRowInPaginatedList(page, refundCode, 15_000);
    await clickRowAction(page, refundRow, 'edit');
    await waitForFormReady(page);
    await fillField(page, 'sl_ref_date', todayStr());
    await chooseSelectOption(page, 'sl_ref_method', {
      value: 'bank_transfer',
      label: '银行转账',
    });
    await fillField(page, 'sl_ref_bank_ref', refundEvidence);
    await fillField(page, 'sl_ref_reason', '客户退款已复核；银行凭证与原收款、红冲凭证一致。');
    await capture(page, testInfo, '07-refund-evidence.png');
    await saveForm(page, 'sl:update_customer_refund');

    await navigateToSalesList(page, '客户退款', 'sl_customer_refund_common');
    refundRow = await findRowInPaginatedList(page, refundCode, 15_000);
    await executeRowCommand(page, refundRow, 'pay', 'sl:pay_customer_refund');
    const paidRefund = await readRecord(page, 'sl_customer_refund_common', refundPid);
    expect(paidRefund.sl_ref_status).toBe('paid');
    expect(paidRefund.sl_ref_bank_ref).toBe(refundEvidence);

    invoice = await readRecord(page, 'sl_customer_invoice_common', invoicePid);
    expect(invoice.sl_inv_status).toBe('settled');
    expect(Number(invoice.sl_inv_amount)).toBe(1000);
    expect(Number(invoice.sl_inv_allocated_amount)).toBe(800);
    expect(Number(invoice.sl_inv_credited_amount)).toBe(200);
    expect(Number(invoice.sl_inv_refunded_amount)).toBe(200);
    expect(Number(invoice.sl_inv_refund_due_amount)).toBe(0);
    expect(Number(invoice.sl_inv_outstanding_amount)).toBe(0);

    const collection = await readRecord(page, 'sl_sales_collection_common', collectionPid);
    expect(Number(collection.sl_col_amount)).toBe(1000);
    expect(Number(collection.sl_col_net_amount)).toBe(800);
    expect(Number(collection.sl_col_allocated_amount)).toBe(800);
    expect(Number(collection.sl_col_unallocated_amount)).toBe(0);
    expect(Number(collection.sl_col_refunded_amount)).toBe(200);
    const contract = await readRecord(page, 'sl_sales_contract_common', contractPid);
    const plan = await readRecord(page, 'sl_contract_payment_plan_common', planPid);
    const order = await readRecord(page, 'sl_sales_order_common', orderPid);
    expect(Number(contract.sl_ctr_collected_amount)).toBe(800);
    expect(contract.sl_ctr_payment_status).toBe('partial');
    expect(Number(plan.sl_cpp_collected_amount)).toBe(800);
    expect(plan.sl_cpp_status).toBe('partially_paid');
    expect(order.sl_so_payment_status).toBe('partial');

    await navigateToSalesList(page, '客户退款', 'sl_customer_refund_common');
    refundRow = await findRowInPaginatedList(page, refundCode, 15_000);
    await clickRowAction(page, refundRow, 'detail');
    await expect(page.getByText('退款链路与凭证', { exact: true })).toBeVisible();
    await expect(page.getByText(refundEvidence, { exact: true })).toBeVisible();
    await capture(page, testInfo, '08-refund-paid-audit.png');

    await navigateToSalesList(page, '客户发票', 'sl_customer_invoice_common');
    invoiceRow = await findRowInPaginatedList(page, invoiceCode, 15_000);
    await clickRowAction(page, invoiceRow, 'detail');
    await expect(page.getByText('已结清', { exact: true }).first()).toBeVisible();
    const creditEvidence = page.getByText(creditCode, { exact: true });
    const refundEvidenceRow = page.getByText(refundCode, { exact: true });
    await expect(creditEvidence).toBeVisible({ timeout: 12_000 });
    await expect(refundEvidenceRow).toBeVisible({ timeout: 12_000 });
    await refundEvidenceRow.scrollIntoViewIfNeeded();
    await captureViewport(page, testInfo, '09-invoice-final-audit-chain.png');
  });
});
