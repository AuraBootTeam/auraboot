import { test, expect } from '../../fixtures';
import fs from 'node:fs';
import path from 'node:path';
import * as XLSX from 'xlsx';
import { cleanupRows, openQuoteDetailFromList, seedDownloadableQuote } from './quote-e2e-helpers';

function numericCell(sheet: XLSX.WorkSheet, address: string): number {
  const value = sheet[address]?.v;
  expect(typeof value, `${address} must carry a numeric cached value`).toBe('number');
  return Number(value);
}

function expectFormula(sheet: XLSX.WorkSheet, address: string, expected: string): void {
  expect(String(sheet[address]?.f ?? ''), `${address} formula`).toBe(expected);
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

  const process = workbook.Sheets['加工明细'];
  for (let row = 3; row <= 11; row += 1) {
    expectFormula(process, `H${row}`, `F${row}*G${row}`);
  }
  expectFormula(process, 'H12', 'SUM(H3:H11)');
  const processDetailTotal = Array.from({ length: 9 }, (_, index) => numericCell(process, `H${index + 3}`))
    .reduce((total, value) => total + value, 0);
  expect(numericCell(process, 'H12')).toBeCloseTo(processDetailTotal, 8);

  const quote = workbook.Sheets['报价单'];
  expectFormula(quote, 'J15', '(H15+I15)*30%');
  expectFormula(quote, 'K15', 'ROUND((H15+I15+J15),2)');
  expectFormula(quote, 'L15', 'G15*K15');
  expectFormula(quote, 'P15', 'N15+M15+L15');
  expect(String(quote['K15']?.f ?? '')).not.toContain('L15/G15');

  const materialPerSet = numericCell(quote, 'H15');
  const processAndPackagingPerSet = numericCell(quote, 'I15');
  const managementProfit = numericCell(quote, 'J15');
  const unitPrice = numericCell(quote, 'K15');
  const setCount = numericCell(quote, 'G15');
  const recurringTotal = numericCell(quote, 'L15');
  const stencilFee = numericCell(quote, 'M15');
  const engineeringFee = numericCell(quote, 'N15');
  const finalTotal = numericCell(quote, 'P15');

  expect(managementProfit).toBeCloseTo((materialPerSet + processAndPackagingPerSet) * 0.3, 8);
  expect(unitPrice).toBeCloseTo(Math.round((materialPerSet + processAndPackagingPerSet + managementProfit) * 100) / 100, 8);
  expect(recurringTotal).toBeCloseTo(setCount * unitPrice, 8);
  expect(finalTotal).toBeCloseTo(engineeringFee + stencilFee + recurringTotal, 8);
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
      await openQuoteDetailFromList(page, created);
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
