import { writeFileSync } from 'node:fs';
import { utils as XLSXUtils, write } from 'xlsx';
import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import { waitForFormReady } from '../helpers';
import {
  cleanupRows,
  openQuoteCreateFormFromList,
  openQuoteDetailFromList,
  queryDynamicRecords,
  seedQuoteForCorrectedBomUpload,
  type CreatedRows,
} from './quote-e2e-helpers';

function extractTaskCode(commandBody: any): string | undefined {
  const candidates = [
    commandBody?.data?.data?.taskCode,
    commandBody?.data?.handlerResults?.[0]?.taskCode,
    commandBody?.data?.handlerResults?.[0]?.data?.taskCode,
    commandBody?.data?.taskCode,
  ];
  return candidates.find((value) => typeof value === 'string' && value.trim().length > 0);
}

async function waitForAsyncTaskTerminal(page: Page, taskCode: string): Promise<any> {
  const terminalStatuses = new Set(['completed', 'failed', 'cancelled']);
  let latestTask: any;
  await expect
    .poll(
      async () => {
        const taskBody = await page.evaluate(async (code: string) => {
          const response = await fetch(`/api/async-tasks/${encodeURIComponent(code)}`);
          return response.json();
        }, taskCode);
        latestTask = taskBody?.data ?? taskBody;
        const status = String(latestTask?.status ?? '').toLowerCase();
        return terminalStatuses.has(status) ? status : '';
      },
      { timeout: 60_000, intervals: [1500] },
    )
    .toMatch(/^(completed|failed|cancelled)$/);
  return latestTask;
}

function createInvalidCorrectedBomWorkbook(filePath: string): string {
  const workbook = XLSXUtils.book_new();
  const worksheet = XLSXUtils.aoa_to_sheet([
    ['Part Number', 'Count'],
    ['BAD-HEADER-ROW', 1],
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
    expect(dirtyQuotes, 'empty submit must not persist a quote with the draft note').toHaveLength(0);
  });

  test('surfaces async failure feedback for an invalid corrected BOM upload', async ({
    page,
  }, testInfo) => {
    const created: CreatedRows = await seedQuoteForCorrectedBomUpload(page);
    const invalidWorkbookPath = createInvalidCorrectedBomWorkbook(
      testInfo.outputPath('invalid-corrected-bom.xlsx'),
    );

    try {
      await openQuoteDetailFromList(page, created);
      await expect(page.getByRole('tab', { name: /资料上传|Source Upload/ })).toBeVisible({
        timeout: 20_000,
      });
      await expect(page.getByTestId('toolbar-btn-upload_corrected_bom')).toBeVisible({
        timeout: 20_000,
      });

      const commandResponsePromise = page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          response.url().includes('/api/meta/commands/execute/qo_quote_common:import_corrected_bom'),
        { timeout: 60_000 },
      );
      const fileChooserPromise = page.waitForEvent('filechooser', { timeout: 10_000 });
      await page.getByTestId('toolbar-btn-upload_corrected_bom').click();
      const fileChooser = await fileChooserPromise;
      await fileChooser.setFiles(invalidWorkbookPath);

      const commandResponse = await commandResponsePromise;
      const commandBody = await commandResponse.json();
      await testInfo.attach('import-command-response.json', {
        body: JSON.stringify(commandBody, null, 2),
        contentType: 'application/json',
      });
      const taskCode = extractTaskCode(commandBody);
      expect(taskCode, JSON.stringify(commandBody)).toBeTruthy();

      await expect(page.getByText(/导入进行中|后台处理中|running/i).first()).toBeVisible({
        timeout: 20_000,
      });
      const task = await waitForAsyncTaskTerminal(page, taskCode!);
      expect(task.status).toBe('failed');

      // The corrected-BOM upload uses panel feedback (promptUpload.feedbackMode='panel'):
      // failure surfaces in the import-result modal (AsyncTaskProgressModal), NOT the inline
      // status-banner. Assert the visible failure the user actually sees — the modal shows the
      // failed state and the real backend error, so a bad file is never a silent success.
      // Prefer the stable testid (present post-merge); fall back to the modal's visible failed
      // text so this also passes on already-deployed builds without the testid.
      const failurePanelById = page.getByTestId('async-task-modal-failed');
      const failurePanel = (await failurePanelById.count())
        ? failurePanelById
        : page.getByText(/导入失败\s*\/\s*Failed/i).locator('xpath=ancestor::*[self::div][1]');
      await expect(failurePanel.first()).toBeVisible({ timeout: 20_000 });
      await expect(page.getByText(/导入失败\s*\/\s*Failed/i).first()).toBeVisible();
      // ImportCorrectedBomHandler.requireStandardBomFormat now rejects any upload whose
      // fixed 12-column standard-BOM header isn't on row 4 (see the workbook's "Part
      // Number"/"Count" 2-column header above), surfacing this message instead of the
      // old generic "missing required header row" text.
      await expect(page.getByText(/不是标准BOM格式/i).first()).toBeVisible({
        timeout: 20_000,
      });
      await expect(page.getByText(/第4行.*12列表头/i).first()).toBeVisible();

      const imports = await queryDynamicRecords(page, 'qo_bom_import_common', [
        { fieldName: 'qo_bi_quote_id', operator: 'EQ', value: created.quoteId },
      ]);
      const quoteLines = await queryDynamicRecords(page, 'qo_quote_line_common', [
        { fieldName: 'qo_ql_quote_id', operator: 'EQ', value: created.quoteId },
      ]);
      expect(imports, 'invalid workbook must not leave an import header').toHaveLength(0);
      expect(quoteLines, 'invalid workbook must not create quote lines').toHaveLength(0);
    } finally {
      await cleanupRows(page, created);
    }
  });
});
