import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import {
  cleanupRows,
  dynamicCreate,
  openQuoteDetailFromList,
  queryDynamicRecords,
  queryNamedDataSourceRecords,
  type CreatedRows,
} from './quote-e2e-helpers';

type DeepSeekQuoteSeed = CreatedRows & {
  lineId: string;
  mpn: string;
};

function extractTaskCode(commandBody: any): string | undefined {
  const candidates = [
    commandBody?.data?.data?.taskCode,
    commandBody?.data?.handlerResults?.[0]?.taskCode,
    commandBody?.data?.handlerResults?.[0]?.data?.taskCode,
    commandBody?.data?.taskCode,
  ];
  return candidates.find((value) => typeof value === 'string' && value.trim().length > 0);
}

async function waitForAsyncTaskCompleted(page: Page, taskCode: string): Promise<any> {
  const terminalStatuses = new Set(['completed', 'failed', 'cancelled']);
  let latestTask: any;

  await expect
    .poll(
      async () => {
        const response = await page.request.get(`/api/async-tasks/${encodeURIComponent(taskCode)}`, {
          timeout: 15_000,
        });
        const body = await response.json().catch(() => ({}));
        latestTask = body?.data ?? body;
        const status = String(latestTask?.status ?? '').toLowerCase();
        return terminalStatuses.has(status) ? status : status || 'pending';
      },
      {
        timeout: 180_000,
        intervals: [1500, 2500, 5000],
        message: `async task ${taskCode} should complete`,
      },
    )
    .toBe('completed');

  return latestTask;
}

async function seedMinimalDeepSeekQuote(page: Page): Promise<DeepSeekQuoteSeed> {
  const suffix = `${Date.now()}${Math.random().toString(16).slice(2, 8)}`;
  const quoteCode = `QO-E2E-DS-${suffix}`;
  const mpn = `E2E-DS-${suffix}`;
  const created: DeepSeekQuoteSeed = { quoteId: '', quoteCode, rows: [], lineId: '', mpn };

  try {
    const accountId = await dynamicCreate(
      page,
      'crm_account_common',
      {
        crm_acc_code: `ACC-E2E-DS-${suffix}`,
        crm_acc_name: `E2E DeepSeek Customer ${suffix}`,
        crm_acc_industry: 'electronics',
        crm_acc_status: 'active',
      },
      created.rows,
    );

    const projectId = await dynamicCreate(
      page,
      'req_requirement_set_pcba_bom',
      {
        bom_project_name: `E2E DeepSeek Project ${suffix}`,
        bom_project_customer_id: accountId,
        bom_project_quality_level: 'industrial',
        bom_pcba_code: `PCBA-DS-${suffix}`,
        bom_project_remark: 'Seeded by DeepSeek BOM price E2E',
      },
      created.rows,
    );

    const customerRequestId = await dynamicCreate(
      page,
      'crm_customer_request_common',
      {
        crm_cr_code: `CR-E2E-DS-${suffix}`,
        crm_cr_title: `E2E DeepSeek request ${suffix}`,
        crm_cr_account_id: accountId,
        crm_cr_type: 'pcba_quote',
        crm_cr_status: 'draft',
        crm_cr_priority: 'normal',
        crm_cr_source_channel: 'quote_deepseek_e2e',
      },
      created.rows,
    );

    created.quoteId = await dynamicCreate(
      page,
      'qo_quote_common',
      {
        qo_quote_customer: `E2E DeepSeek Customer ${suffix}`,
        qo_quote_code: quoteCode,
        qo_quote_status: 'draft',
        qo_quote_version_no: 1,
        qo_quote_crm_account_id: accountId,
        qo_quote_project_id: projectId,
        qo_quote_customer_request_id: customerRequestId,
        qo_quote_tax_rate: 0.13,
        qo_quote_factory_class: 'consumer',
        qo_quote_industry: 'pcba',
        corrected_bom_file: `e2e-corrected-bom-${suffix}`,
      },
      created.rows,
    );

    created.lineId = await dynamicCreate(
      page,
      'qo_quote_line_common',
      {
        qo_ql_quote_id: created.quoteId,
        qo_ql_item_type: 'component',
        qo_ql_source_ref: `BOM-DS-${suffix}`,
        qo_ql_source_row_no: 2,
        qo_ql_description: 'DeepSeek price E2E resistor 10K 1%',
        qo_ql_refdes: 'R1',
        qo_ql_mpn: mpn,
        qo_ql_package: '0603',
        qo_ql_unit: 'pcs',
        qo_ql_qty: 10,
        qo_ql_unit_cost: 0,
        qo_ql_line_cost: 0,
        qo_ql_line_price: 0,
        qo_ql_smt_points: 1,
        qo_ql_tht_points: 0,
      },
      created.rows,
    );

    return created;
  } catch (error) {
    await cleanupRows(page, created);
    throw error;
  }
}

test.describe('QuoteOps DeepSeek BOM price suggestions', () => {
  test.describe.configure({ timeout: 240_000 });

  test('executes from the BOM price tab with quote target and hides backend exception text', async ({
    page,
  }) => {
    const created = await seedMinimalDeepSeekQuote(page);

    try {
      await openQuoteDetailFromList(page, created);
      await page.getByRole('tab', { name: /BOM价格计算|BOM Price/i }).click();
      const priceRow = page.getByTestId(`table-row-${created.lineId}`);
      await expect(priceRow).toBeVisible({ timeout: 30_000 });

      const commandResponsePromise = page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          response
            .url()
            .includes('/api/meta/commands/execute/qo_quote_common:deepseek_price_suggestions'),
        { timeout: 60_000 },
      );
      await page
        .getByTestId('workbench-action-deepseek_suggestions')
        .or(page.getByRole('button', { name: /DeepSeek建议价|DeepSeek Suggestions/i }))
        .click();

      const commandResponse = await commandResponsePromise;
      const commandBody = await commandResponse.json().catch(() => ({}));
      const requestBody = commandResponse.request().postDataJSON() as Record<string, any>;
      const targetRecordPid =
        requestBody?.targetRecordPid ?? requestBody?.targetRecordId ?? requestBody?.params?.targetRecordPid;
      expect(targetRecordPid, JSON.stringify(requestBody)).toBe(created.quoteId);

      const taskCode = extractTaskCode(commandBody);
      expect(taskCode, JSON.stringify(commandBody).slice(0, 800)).toBeTruthy();
      await waitForAsyncTaskCompleted(page, taskCode!);

      await expect
        .poll(
          async () => {
            const rows = await queryNamedDataSourceRecords(page, 'qo_quote_bom_price_waterfall', {
              quoteId: created.quoteId,
            });
            return rows.find((row) => String(row.pid ?? '') === created.lineId)
              ?.deepseek_suggested_price;
          },
          { timeout: 30_000, intervals: [1000, 2000, 3000] },
        )
        .toMatch(/CNY|待定|\d/);

      const evidenceRows = await queryDynamicRecords(
        page,
        'qo_price_evidence_common',
        [{ fieldName: 'qo_pe_quote_line_id', operator: 'EQ', value: created.lineId }],
        { pageSize: 100 },
      );
      const evidenceText = JSON.stringify(evidenceRows);
      expect(evidenceText).not.toMatch(/java\.lang|IllegalStateException|MetaContext not initialized/i);
      expect(evidenceRows.some((row) => row.qo_pe_source === 'deepseek_llm')).toBe(true);

      await page.reload({ waitUntil: 'domcontentloaded' });
      await page.getByRole('tab', { name: /BOM价格计算|BOM Price/i }).click();
      await expect(page.locator('main')).not.toContainText(
        /java\.lang|IllegalStateException|MetaContext not initialized/i,
        { timeout: 20_000 },
      );
    } finally {
      await cleanupRows(page, created);
    }
  });
});
