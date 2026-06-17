import { test, expect } from '../../fixtures';
import fs from 'node:fs';
import path from 'node:path';
import * as XLSX from 'xlsx';
import { cleanupRows, seedDownloadableQuote } from './quote-e2e-helpers';

function formulasOf(sheet: XLSX.WorkSheet): string[] {
  return Object.values(sheet)
    .filter((cell): cell is XLSX.CellObject => Boolean(cell) && typeof cell === 'object' && 'f' in cell)
    .map((cell) => String(cell.f ?? ''))
    .filter(Boolean);
}

function assertNoRef(workbook: XLSX.WorkBook): void {
  for (const sheetName of workbook.SheetNames) {
    const sheet = workbook.Sheets[sheetName];
    for (const [address, cell] of Object.entries(sheet)) {
      if (address.startsWith('!') || !cell || typeof cell !== 'object') continue;
      const formula = String((cell as XLSX.CellObject).f ?? '');
      const value = String((cell as XLSX.CellObject).v ?? '');
      expect(`${sheetName}!${address} formula ${formula}`).not.toContain('#REF!');
      expect(`${sheetName}!${address} value ${value}`).not.toContain('#REF!');
    }
  }
}

function validateQuoteWorkbook(filePath: string): void {
  const workbook = XLSX.read(fs.readFileSync(filePath), {
    type: 'buffer',
    cellFormula: true,
    cellText: false,
    sheetStubs: true,
  });
  expect(workbook.SheetNames).toEqual(['报价单', 'BOM明细', '加工明细']);
  assertNoRef(workbook);

  const bom = workbook.Sheets['BOM明细'];
  expect(bom['L2']?.v).toBe(1.25);
  expect(bom['M2']?.f).toBe('IF(L2="","",G2*L2)');
  expect(typeof bom['N2']?.v).toBe('number');
  expect(bom['O2']?.f).toBe('G2*N2');

  const processFormulas = formulasOf(workbook.Sheets['加工明细']);
  expect(processFormulas.some((formula) => formula.includes('BOM明细') && formula.toUpperCase().includes('SUM'))).toBe(true);
  expect(formulasOf(workbook.Sheets['报价单']).join('\n')).not.toContain('#REF!');
}

test.describe('PCBA quote Excel download', () => {
  test.describe.configure({ timeout: 90_000 });

  test('downloads the Jiejia 3-sheet workbook from the quote detail page', async ({ page }, testInfo) => {
    const created = await seedDownloadableQuote(page);
    const consoleMessages: string[] = [];
    page.on('console', (message) => {
      consoleMessages.push(`[${message.type()}] ${message.text()}`);
    });
    try {
      await page.goto(`/p/qo_quote_common/view/${created.quoteId}`, { waitUntil: 'domcontentloaded' });
      await expect(page.getByRole('tab', { name: /报价Excel|Quote Excel/ })).toBeVisible({ timeout: 20_000 });
      await page.getByRole('tab', { name: /报价Excel|Quote Excel/ }).click();
      await expect(page.getByTestId('workbench-action-generate_quote_excel')).toBeVisible({ timeout: 10_000 });

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
      expect(String((commandBody as any).code), `generate_document response: ${JSON.stringify(commandBody).slice(0, 500)}`).toBe('0');
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
      validateQuoteWorkbook(savedPath);
    } finally {
      await cleanupRows(page, created);
    }
  });
});
