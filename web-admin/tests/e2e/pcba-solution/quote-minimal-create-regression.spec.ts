import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import { navigateToDynamicPage, queryFilteredList, waitForDynamicPageLoad, waitForFormReady } from '../helpers';
import { cleanupRows, executeCommand, type CreatedRows } from './quote-e2e-helpers';

const CREATE_URL = '/p/qo_quote_common/new?commandCode=qo_quote_common%3Acreate';

async function readDynamicRecord(
  page: Page,
  model: string,
  pid: string,
): Promise<Record<string, unknown>> {
  const resp = await page.request.get(`/api/dynamic/${model}/${pid}`, { timeout: 15_000 });
  const body = await resp.json().catch(() => ({}));
  expect(resp.ok(), `${model}/${pid} HTTP ${resp.status()}: ${JSON.stringify(body).slice(0, 500)}`).toBe(true);
  const record = ((body as any).data?.data ?? (body as any).data ?? body) as Record<string, unknown>;
  expect(record?.pid ?? record?.id, `${model}/${pid} should return a record`).toBeTruthy();
  return record;
}

async function selectCustomer(page: Page, accountId: string, accountName: string): Promise<void> {
  const trigger = page.getByTestId('select-trigger-qo_quote_crm_account_id');
  await expect(trigger).toBeVisible({ timeout: 15_000 });
  await trigger.click();

  const option = page.locator(`[role="option"][data-value="${accountId}"]`).first();
  await expect(option, `customer option ${accountId} should be loaded`).toBeVisible({
    timeout: 15_000,
  });
  await option.click();
  await expect(trigger).toContainText(accountName, { timeout: 5_000 });
}

async function visibleFormFieldIds(page: Page): Promise<string[]> {
  return page.locator('[data-testid^="form-field-"]').evaluateAll((nodes) =>
    nodes
      .filter((node) => {
        const element = node as HTMLElement;
        const style = window.getComputedStyle(element);
        return style.display !== 'none' && style.visibility !== 'hidden';
      })
      .map((node) => (node as HTMLElement).dataset.testid || '')
      .filter(Boolean)
      .sort(),
  );
}

async function pollAsyncTaskResult(page: Page, taskCode: string): Promise<Record<string, unknown>> {
  const terminal = new Set(['completed', 'failed', 'cancelled']);
  let resultData: Record<string, unknown> = {};

  await expect
    .poll(
      async () => {
        const resp = await page.request.get(`/api/async-tasks/${encodeURIComponent(taskCode)}`, {
          timeout: 15_000,
        });
        const body = await resp.json().catch(() => ({}));
        if (!resp.ok()) {
          return `http:${resp.status()}:${JSON.stringify(body).slice(0, 500)}`;
        }
        const task = ((body as any).data ?? {}) as Record<string, unknown>;
        const status = String(task.status ?? '').toLowerCase();
        if (terminal.has(status)) {
          if (status === 'completed') {
            resultData = ((task as any).resultData ?? {}) as Record<string, unknown>;
            return 'completed';
          }
          return `terminal:${status}:${JSON.stringify(task).slice(0, 800)}`;
        }
        return status || 'pending';
      },
      {
        timeout: 180_000,
        intervals: [1000, 1500, 2000, 3000],
        message: `async task ${taskCode} should complete`,
      },
    )
    .toBe('completed');

  return resultData;
}

async function unwrapCommandResponseData(
  page: Page,
  body: Record<string, unknown>,
): Promise<Record<string, unknown>> {
  const commandData = ((body as any).data?.data ?? {}) as Record<string, unknown>;
  if (commandData.async === true && typeof commandData.taskCode === 'string') {
    return pollAsyncTaskResult(page, commandData.taskCode);
  }
  return commandData;
}

async function tableHeaders(page: Page): Promise<string[]> {
  const headers = page.locator('thead th, [role="columnheader"]');
  await expect(headers.first()).toBeVisible({ timeout: 15_000 });
  return headers.evaluateAll((nodes) =>
    nodes
      .map((node) => (node.textContent || '').replace(/\s+/g, ' ').trim())
      .filter(Boolean),
  );
}

test.describe('PCBA quote minimal create regression', () => {
  test.describe.configure({ timeout: 90_000 });

  test('creates a quote from only customer and notes while preserving hidden RFQ links and simplified pages', async ({
    page,
  }) => {
    const suffix = `${Date.now()}${Math.random().toString(16).slice(2, 8)}`;
    const accountName = `ZZZ E2E Minimal Customer ${suffix}`;
    const notes = `Minimal quote note ${suffix}`;
    const created: CreatedRows = { quoteId: '', quoteCode: '', rows: [] };

    try {
      const accountResult = await executeCommand(
        page,
        'crm:create_account',
        {
          crm_acc_name: accountName,
          crm_acc_industry: 'electronics',
          crm_acc_rating: 'A',
        },
        undefined,
        'create',
      );
      const accountId = String(
        accountResult.recordId ?? accountResult.pid ?? accountResult.id ?? '',
      );
      expect(accountId, 'crm:create_account should return recordId').toBeTruthy();
      created.rows.push({ model: 'crm_account_common', pid: accountId });

      const accountOptionsLoaded = page
        .waitForResponse(
          (response) =>
            response.url().includes('/api/dynamic/crm_account_common/list') &&
            response.request().method() === 'GET' &&
            response.status() === 200,
          { timeout: 20_000 },
        )
        .catch(() => null);

      await page.goto(CREATE_URL, { waitUntil: 'domcontentloaded' });
      await waitForFormReady(page, 20_000);
      await accountOptionsLoaded;

      expect(await visibleFormFieldIds(page)).toEqual([
        'form-field-corrected_bom_file',
        'form-field-cpl_source_file',
        'form-field-gerber_source_file',
        'form-field-qo_quote_crm_account_id',
        'form-field-qo_quote_notes',
      ]);
      await expect(page.getByTestId('form-field-gerber_source_file')).toBeVisible();
      await expect(page.getByTestId('form-field-cpl_source_file')).toBeVisible();
      await expect(page.getByTestId('form-field-corrected_bom_file')).toBeVisible();
      await expect(page.getByTestId('form-field-qo_quote_customer')).toHaveCount(0);
      await expect(page.getByTestId('form-field-qo_quote_tax_rate')).toHaveCount(0);
      await expect(page.getByTestId('form-field-qo_quote_valid_until')).toHaveCount(0);

      await selectCustomer(page, accountId, accountName);
      await page
        .getByTestId('form-field-qo_quote_notes')
        .locator('textarea, input')
        .first()
        .fill(notes);

      const createResponsePromise = page.waitForResponse(
        (response) =>
          response.url().includes('/api/meta/commands/execute/qo_quote_common:create') &&
          response.request().method() === 'POST',
        { timeout: 30_000 },
      );
      await page.getByTestId('form-btn-save').click();
      const createResponse = await createResponsePromise;
      const createBody = await createResponse.json().catch(() => ({}));
      expect(
        String((createBody as any).code),
        `qo_quote_common:create response: ${JSON.stringify(createBody).slice(0, 800)}`,
      ).toBe('0');
      const quoteData = await unwrapCommandResponseData(
        page,
        createBody as Record<string, unknown>,
      );
      const quoteId = String(quoteData.recordId ?? quoteData.quoteId ?? quoteData.pid ?? '');
      expect(quoteId, 'quote create should return quote id').toBeTruthy();
      expect(quoteData.uploadedSourceCount).toBe(0);
      created.quoteId = quoteId;
      created.rows.push({ model: 'qo_quote_common', pid: quoteId });

      const quote = await readDynamicRecord(page, 'qo_quote_common', quoteId);
      created.quoteCode = String(quote.qo_quote_code ?? '');
      expect(quote.qo_quote_crm_account_id).toBe(accountId);
      expect(quote.qo_quote_customer).toBe(accountName);
      expect(quote.qo_quote_notes).toBe(notes);
      expect(quote.qo_quote_status).toBe('draft');
      const customerRequestId = String(quote.qo_quote_customer_request_id ?? '');
      expect(customerRequestId, 'quote should keep hidden customer request id').toBeTruthy();
      created.rows = [
        { model: 'crm_account_common', pid: accountId },
        { model: 'crm_customer_request_common', pid: customerRequestId },
        { model: 'qo_quote_common', pid: quoteId },
      ];

      const customerRequest = await readDynamicRecord(
        page,
        'crm_customer_request_common',
        customerRequestId,
      );
      expect(customerRequest.crm_cr_account_id).toBe(accountId);
      expect(customerRequest.crm_cr_summary).toBe(notes);
      expect(customerRequest.crm_cr_title).toBe(`PCBA quote request - ${accountName}`);

      const pcbaRfqIdFromCommand = String(quoteData.pcbaRfqId ?? '');
      const pcbaRfqs = pcbaRfqIdFromCommand
        ? [await readDynamicRecord(page, 'crm_customer_request_pcba_rfq', pcbaRfqIdFromCommand)]
        : await queryFilteredList(
            page,
            'crm_customer_request_pcba_rfq',
            'crm_customer_request_id',
            customerRequestId,
            { operator: 'EQ' },
          );
      expect(pcbaRfqs.length, 'hidden customer request should have a PCBA RFQ').toBeGreaterThan(0);
      const pcbaRfq = pcbaRfqs[0];
      const pcbaRfqId = String(pcbaRfq.pid ?? pcbaRfq.id ?? pcbaRfqIdFromCommand);
      expect(pcbaRfq.crm_customer_request_id).toBe(customerRequestId);
      expect(pcbaRfqId, 'pcba rfq id should be known for cleanup').toBeTruthy();
      created.rows = [
        { model: 'crm_account_common', pid: accountId },
        { model: 'crm_customer_request_common', pid: customerRequestId },
        { model: 'crm_customer_request_pcba_rfq', pid: pcbaRfqId },
        { model: 'qo_quote_common', pid: quoteId },
      ];

      await navigateToDynamicPage(page, 'qo_quote_common');
      expect(await tableHeaders(page)).toEqual(['报价单编号', '客户信息', '状态', '操作']);
      await expect(page.locator('thead, [role="rowgroup"]').first()).not.toContainText('CRM客户ID');
      await expect(page.locator('thead, [role="rowgroup"]').first()).not.toContainText('折扣%');
      await expect(page.locator('thead, [role="rowgroup"]').first()).not.toContainText('有效期至');

      await page.goto(`/p/qo_quote_common/view/${quoteId}`, { waitUntil: 'domcontentloaded' });
      await waitForDynamicPageLoad(page, 20_000);
      await expect(page.getByRole('tab', { name: /资料上传|Source Upload/ })).toBeVisible({
        timeout: 20_000,
      });
      await expect(page.getByRole('tab', { name: /BOM价格计算|BOM Price/i })).toBeVisible();
      await expect(page.getByTestId('toolbar-btn-upload_raw_bom')).toHaveCount(0);
      await expect(page.getByTestId('toolbar-btn-upload_gerber_package')).toBeVisible({
        timeout: 20_000,
      });
      await expect(page.getByTestId('toolbar-btn-upload_cpl')).toBeVisible();
      await expect(page.getByTestId('toolbar-btn-upload_corrected_bom')).toBeVisible();

      const main = page.locator('main');
      await expect(main).not.toContainText('资料准备中');
      await expect(main).not.toContainText('确认 RFQ原始资料和线下修正 BOM');
      await expect(main).not.toContainText('客户与RFQ');
      await expect(main).not.toContainText('PCBA RFQ');
      await expect(main).not.toContainText('PCBA数量');
    } finally {
      await cleanupRows(page, created);
    }
  });
});
