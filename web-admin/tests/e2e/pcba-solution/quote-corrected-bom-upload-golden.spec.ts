import { test, expect } from '../../fixtures';
import {
  cleanupRows,
  createCorrectedBomWorkbook,
  isTransientViteDynamicImportIssue,
  openQuoteDetailFromList,
  queryDynamicRecords,
  seedQuoteForCorrectedBomUpload,
  type CreatedRows,
} from './quote-e2e-helpers';

test.describe('QuoteOps corrected BOM upload golden', () => {
  test.describe.configure({ timeout: 120_000 });

  test('uploads a corrected BOM workbook from the quote workbench and refreshes trace rows', async ({
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
      if (/Expression evaluation failed|Cannot read properties|ReferenceError|TypeError/i.test(text)) {
        consoleIssues.push(`${message.type()}: ${text}`);
      }
    });
    page.on('pageerror', (error) => {
      if (isTransientViteDynamicImportIssue(error.message)) return;
      consoleIssues.push(`pageerror: ${error.message}`);
    });

    try {
      await openQuoteDetailFromList(page, created);

      await expect(page.getByRole('tab', { name: /资料上传|Source Upload/ })).toBeVisible({
        timeout: 20_000,
      });
      await expect(page.getByTestId('toolbar-btn-upload_corrected_bom')).toBeVisible({
        timeout: 20_000,
      });
      await expect(page.getByTestId('toolbar-btn-upload_raw_bom')).toHaveCount(0);

      const uploadResponsePromise = page.waitForResponse(
        (response) =>
          response.url().includes('/api/file/upload') &&
          response.request().method() === 'POST',
        { timeout: 30_000 },
      );
      const commandResponsePromise = page.waitForResponse(
        (response) =>
          response.url().includes('/api/meta/commands/execute/qo_quote_common:import_corrected_bom') &&
          response.request().method() === 'POST',
        { timeout: 60_000 },
      );
      const fileChooserPromise = page.waitForEvent('filechooser', { timeout: 10_000 });

      await page.getByTestId('toolbar-btn-upload_corrected_bom').click();
      const fileChooser = await fileChooserPromise;
      await fileChooser.setFiles(workbookPath);

      const uploadResponse = await uploadResponsePromise;
      expect(uploadResponse.ok(), `file upload HTTP ${uploadResponse.status()}`).toBe(true);
      await expect(
        page.getByRole('alert').filter({ hasText: /customer-corrected-bom-e2e/ }).first(),
      ).toBeVisible({ timeout: 10_000 });

      const commandResponse = await commandResponsePromise;
      const commandBody = await commandResponse.json().catch(() => ({}));
      expect(
        String((commandBody as any).code),
        `import_corrected_bom response: ${JSON.stringify(commandBody).slice(0, 1000)}`,
      ).toBe('0');

      await expect(
        page.getByRole('alert').filter({ hasText: /completed|完成|导入|Imported/i }).first(),
      ).toBeVisible({ timeout: 20_000 });
      await expect(page.getByText(/导入完成\s*\/\s*Completed/i)).toBeVisible({
        timeout: 20_000,
      });

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
          qo_ql_source_row_no: 2,
          qo_ql_import_version: importVersion,
          qo_ql_validation_status: 'valid',
        }),
      );

      const main = page.locator('main');
      await expect(main).toContainText('customer-corrected-bom-e2e.xlsx', { timeout: 20_000 });
      await expect(main).toContainText(/partial|部分|3|2|1/i);
      await page.getByRole('button', { name: /^关闭$/ }).click();
      await expect(page.getByText(/导入完成\s*\/\s*Completed/i)).toBeHidden();

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
