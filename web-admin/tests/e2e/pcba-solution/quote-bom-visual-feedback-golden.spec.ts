import { writeFileSync } from 'node:fs';
import { utils as XLSXUtils, write } from 'xlsx';
import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import { waitForFormReady } from '../helpers';
import {
  cleanupRows,
  openQuoteCreateFormFromList,
  openQuoteDetailFromList,
  createQuoteFromReviewedBom,
  queryDynamicRecords,
  seedQuoteForCorrectedBomUpload,
  setYunhanMockScenario,
  type CreatedRows,
} from './quote-e2e-helpers';

function createInvalidCorrectedBomWorkbook(filePath: string): string {
  const workbook = XLSXUtils.book_new();
  const worksheet = XLSXUtils.aoa_to_sheet([
    ['描述', '数量'],
    ['opaque-row-value', 'unknown'],
  ]);
  XLSXUtils.book_append_sheet(workbook, worksheet, 'Invalid BOM');
  const bytes = write(workbook, { bookType: 'xlsx', type: 'buffer' });
  writeFileSync(filePath, bytes);
  return filePath;
}

test.describe('QuoteOps visual feedback golden', () => {

  test.describe.configure({ timeout: 120_000 });

  test('shows field-level validation and keeps invalid quote create from persisting dirty records', async ({
    page,
  }) => {
    const notes = `E2E empty quote validation ${Date.now()}${Math.random().toString(16).slice(2, 8)}`;

    await openQuoteCreateFormFromList(page);
    await waitForFormReady(page, 20_000);
    await page
      .getByTestId('form-field-qo_quote_notes')
      .locator('textarea, input')
      .first()
      .fill(notes);

    await page.getByTestId('form-btn-save').click();

    const customerField = page.getByTestId('form-field-qo_quote_crm_account_id');
    await expect(
      customerField
        .locator('p, [role="alert"], .text-red-600, .text-status-red')
        .filter({
          hasText: /请选择|客户|Customer|required/i,
        })
        .first(),
    ).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId('form-btn-save')).toBeEnabled();
    await expect(page).toHaveURL(/\/p\/qo_quote_common\/new/);
    await expect(page.locator('main')).not.toContainText(/Bad parameter|Command execution failed/i);

    const dirtyQuotes = await queryDynamicRecords(page, 'qo_quote_common', [
      { fieldName: 'qo_quote_notes', operator: 'EQ', value: notes },
    ]);
    expect(dirtyQuotes, 'empty submit must not persist a quote with the draft note').toHaveLength(
      0,
    );
  });

  test('surfaces pending-recognition feedback for an unreadable quick-lane BOM', async ({
    page,
  }, testInfo) => {
    await setYunhanMockScenario(page, 'unrecognized-bom');
    const created: CreatedRows = await seedQuoteForCorrectedBomUpload(page);
    const invalidWorkbookPath = createInvalidCorrectedBomWorkbook(
      testInfo.outputPath('invalid-corrected-bom.xlsx'),
    );

    try {
      const receipt = await createQuoteFromReviewedBom(page, created, invalidWorkbookPath);
      await testInfo.attach('create-command-response.json', {
        body: JSON.stringify(receipt.body, null, 2), contentType: 'application/json',
      });
      await openQuoteDetailFromList(page, created);
      await expect.poll(async () => {
        const rows = await queryDynamicRecords(page, 'qo_bom_import_common', [
          { fieldName: 'qo_bi_quote_id', operator: 'EQ', value: created.quoteId },
        ]);
        return rows[0]?.qo_bi_status;
      }).toBe('partial');

      const imports = await queryDynamicRecords(page, 'qo_bom_import_common', [
        { fieldName: 'qo_bi_quote_id', operator: 'EQ', value: created.quoteId },
      ]);
      expect(imports).toHaveLength(1);
      expect(imports[0]).toEqual(
        expect.objectContaining({
          qo_bi_status: 'partial',
          qo_bi_total_rows: 1,
          qo_bi_valid_rows: 0,
          qo_bi_error_rows: 0,
        }),
      );
      expect(String(imports[0].qo_bi_error_report ?? '')).toContain('待云汉识别');

      const importRows = await queryDynamicRecords(page, 'qo_bom_import_row_common', [
        { fieldName: 'qo_bir_quote_id', operator: 'EQ', value: created.quoteId },
      ]);
      expect(importRows).toHaveLength(1);
      expect(importRows[0].qo_bir_validation_status).toBe('pending');
      expect(String(importRows[0].qo_bir_validation_message ?? '')).toContain('待云汉识别');

      const quoteLines = await queryDynamicRecords(page, 'qo_quote_line_common', [
        { fieldName: 'qo_ql_quote_id', operator: 'EQ', value: created.quoteId },
      ]);
      expect(
        quoteLines,
        'unreadable raw row must be preserved for upstream recognition',
      ).toHaveLength(1);
      expect(quoteLines[0].qo_ql_validation_status).toBe('pending');
      expect(String(quoteLines[0].qo_ql_description ?? '')).toContain('opaque-row-value');

      const main = page.locator('main');
      await expect(main).toContainText('invalid-corrected-bom.xlsx', { timeout: 20_000 });
      await page.getByRole('tab', { name: /BOM价格计算|BOM Price/i }).click();
      const pendingRow = page.getByTestId(`table-row-${quoteLines[0].pid}`);
      await expect(pendingRow).toContainText('opaque-row-value');
      const pendingStatus = pendingRow.getByText('待云汉识别', { exact: true });
      await pendingStatus.evaluate((element) => element.scrollIntoView({ block: 'center', inline: 'center' }));
      await expect(pendingStatus).toBeInViewport();
      await testInfo.attach('pending-recognition-price-row.png', {
        body: await page.screenshot(), contentType: 'image/png',
      });
    } finally {
      await cleanupRows(page, created);
      await setYunhanMockScenario(page, 'release-default');
    }
  });
});
