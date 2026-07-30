import { test, expect } from '../../fixtures';
import path from 'node:path';
import { cleanupRows, openQuoteDetailFromList, seedDownloadableQuote } from './quote-e2e-helpers';
import { validateQuoteWorkbook } from './quote-workbook-assertions';

test.describe('PCBA quote Excel download', () => {
  test.describe.configure({ timeout: 90_000 });

  test('downloads the Jiejia 3-sheet workbook from the quote detail page', async ({
    page,
  }, testInfo) => {
    const created = await seedDownloadableQuote(page);
    const consoleMessages: string[] = [];
    page.on('console', (message) => {
      consoleMessages.push(`[${message.type()}] ${message.text()}`);
    });
    try {
      await openQuoteDetailFromList(page, created);
      await expect(page.getByRole('tab', { name: /报价Excel|Quote Excel/ })).toBeVisible({
        timeout: 20_000,
      });
      await page.getByRole('tab', { name: /报价Excel|Quote Excel/ }).click();
      await expect(page.getByTestId('workbench-action-generate_quote_excel')).toBeVisible({
        timeout: 10_000,
      });

      const commandResponsePromise = page.waitForResponse(
        (response) =>
          response.url().includes('/api/meta/commands/execute/') &&
          response.url().includes('generate_document') &&
          response.request().method() === 'POST',
        { timeout: 60_000 },
      );
      const downloadPromise = page.waitForEvent('download', { timeout: 60_000 });
      await page.getByTestId('workbench-action-generate_quote_excel').click();
      const commandResponse = await commandResponsePromise;
      const commandBody = await commandResponse.json().catch(() => ({}));
      expect(
        String((commandBody as any).code),
        `generate_document response: ${JSON.stringify(commandBody).slice(0, 500)}`,
      ).toBe('0');
      const download = await downloadPromise.catch((error: unknown) => {
        throw new Error(
          [
            error instanceof Error ? error.message : String(error),
            `generate_document response: ${JSON.stringify(commandBody).slice(0, 800)}`,
            `browser console: ${consoleMessages.slice(-20).join('\n')}`,
          ].join('\n'),
        );
      });

      expect(download.suggestedFilename()).toContain(created.quoteCode);
      expect(download.suggestedFilename()).toMatch(/\.xlsx$/);
      const savedPath = path.join(testInfo.outputDir, 'quote-download.xlsx');
      await download.saveAs(savedPath);
      validateQuoteWorkbook(savedPath, { expectedFirstBomUnitPrice: 1.25 });
    } finally {
      await cleanupRows(page, created);
    }
  });
});
