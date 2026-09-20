import { test, expect } from '../../fixtures';
import {
  cleanupRows,
  createCorrectedBomWorkbook,
  isTransientViteDynamicImportIssue,
  openQuoteDetailFromList,
  createQuoteFromReviewedBom,
  queryDynamicRecords,
  seedQuoteForCorrectedBomUpload,
  type CreatedRows,
} from './quote-e2e-helpers';

test.describe('QuoteOps corrected BOM upload golden', () => {

  test.describe.configure({ timeout: 120_000 });

  test('creates a quote with reviewed standard BOM and preserves valid/error row traceability', async ({
    page,
  }, testInfo) => {
    const created: CreatedRows = await seedQuoteForCorrectedBomUpload(page);
    const workbookPath = createCorrectedBomWorkbook(
      testInfo.outputPath('customer-corrected-bom-e2e.xlsx'),
    );
    const consoleIssues: string[] = [];
    page.on('console', (message) => {
      const text = message.text();
      if (isTransientViteDynamicImportIssue(text)) return;
      if (
        /Expression evaluation failed|Cannot read properties|ReferenceError|TypeError/i.test(text)
      ) {
        consoleIssues.push(`${message.type()}: ${text}`);
      }
    });
    page.on('pageerror', (error) => {
      if (isTransientViteDynamicImportIssue(error.message)) return;
      consoleIssues.push(`pageerror: ${error.message}`);
    });

    try {
      await createQuoteFromReviewedBom(page, created, workbookPath);
      await openQuoteDetailFromList(page, created);
      await expect(page.getByTestId('toolbar-btn-upload_corrected_bom')).toHaveCount(0);
      await expect
        .poll(
          async () => {
            const imports = await queryDynamicRecords(page, 'qo_bom_import_common', [
              { fieldName: 'qo_bi_quote_id', operator: 'EQ', value: created.quoteId },
            ]);
            return imports.map((row) => ({
              filename: row.qo_bi_filename,
              status: row.qo_bi_status,
              totalRows: row.qo_bi_total_rows,
              validRows: row.qo_bi_valid_rows,
              errorRows: row.qo_bi_error_rows,
            }));
          },
          { timeout: 20_000, intervals: [500, 1000, 1500] },
        )
        .toEqual([
          expect.objectContaining({
            filename: 'customer-corrected-bom-e2e.xlsx',
            status: 'partial',
            totalRows: 3,
            validRows: 2,
            errorRows: 1,
          }),
        ]);
      const importHeaders = await queryDynamicRecords(page, 'qo_bom_import_common', [
        { fieldName: 'qo_bi_quote_id', operator: 'EQ', value: created.quoteId },
      ]);
      expect(importHeaders).toHaveLength(1);
      const importVersion = String(importHeaders[0].qo_bi_import_version ?? '');
      expect(importVersion).toBeTruthy();

      await expect
        .poll(
          async () => {
            const rows = await queryDynamicRecords(page, 'qo_bom_import_row_common', [
              { fieldName: 'qo_bir_quote_id', operator: 'EQ', value: created.quoteId },
            ]);
            return {
              total: rows.length,
              errors: rows.filter((row) => row.qo_bir_validation_status === 'error').length,
              messages: rows.map((row) => String(row.qo_bir_validation_message ?? '')).join('\n'),
            };
          },
          { timeout: 20_000, intervals: [500, 1000, 1500] },
        )
        .toEqual(expect.objectContaining({ total: 3, errors: 1 }));

      const quoteLines = await queryDynamicRecords(page, 'qo_quote_line_common', [
        { fieldName: 'qo_ql_quote_id', operator: 'EQ', value: created.quoteId },
      ]);
      expect(quoteLines).toHaveLength(2);
      expect(quoteLines.map((row) => row.qo_ql_mpn).sort()).toEqual([
        'RC0603FR-0710KL',
        'STM32F103C8T6',
      ]);
      expect(quoteLines.find((row) => row.qo_ql_mpn === 'RC0603FR-0710KL')).toEqual(
        expect.objectContaining({
          qo_ql_source_workbook: 'customer-corrected-bom-e2e.xlsx',
          // Standard BOM format's fixed 12-column header now lives on row 4 (index 3,
          // after the free-form preamble on rows 1-3 — see createCorrectedBomWorkbook),
          // so data rows are 1-based from row 5, not row 2 as with the old non-standard
          // fixture layout.
          qo_ql_source_row_no: 5,
          qo_ql_import_version: importVersion,
          qo_ql_validation_status: 'valid',
          // Standard BOM has no package/footprint or process-point columns, so the
          // handler leaves these at their defaults instead of inferring them.
          qo_ql_package: '',
          qo_ql_smt_points: 0,
          qo_ql_tht_points: 0,
        }),
      );

      const main = page.locator('main');
      await expect(main).toContainText('customer-corrected-bom-e2e.xlsx', { timeout: 20_000 });
      await page.reload();

      await page.getByRole('tab', { name: /BOM价格计算|BOM Price/i }).click();
      await expect(page.getByTestId('metric-strip-qo_bom_price_metrics')).toBeVisible({
        timeout: 20_000,
      });
      await expect(page.getByRole('tab', { name: /加工点数|Process/i })).toBeVisible();
      await expect(consoleIssues).toEqual([]);
    } finally {
      await cleanupRows(page, created);
    }
  });
});
