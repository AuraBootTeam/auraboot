import { expect } from '@playwright/test';
import fs from 'node:fs';
import * as XLSX from 'xlsx';

export type QuoteWorkbookExpectations = {
  expectedSetCount?: number;
  expectedBomLine?: {
    mpn: string;
    unitCost: number;
  };
  expectedFirstBomUnitPrice?: number;
};

function numericCell(sheet: XLSX.WorkSheet, address: string): number {
  const value = sheet[address]?.v;
  expect(typeof value, `${address} must carry a numeric cached value`).toBe('number');
  return Number(value);
}

function expectFormula(sheet: XLSX.WorkSheet, address: string, expected: string): void {
  expect(String(sheet[address]?.f ?? ''), `${address} formula`).toBe(expected);
}

function sheetRows(workbook: XLSX.WorkBook, sheetName: string): unknown[][] {
  const sheet = workbook.Sheets[sheetName];
  expect(sheet, `sheet ${sheetName} exists`).toBeTruthy();
  return XLSX.utils.sheet_to_json(sheet, { header: 1, raw: false, defval: '' }) as unknown[][];
}

function assertNoFormulaErrors(workbook: XLSX.WorkBook): void {
  const excelError = /#(?:NULL!|DIV\/0!|VALUE!|REF!|NAME\?|NUM!|N\/A|GETTING_DATA)/;
  for (const sheetName of workbook.SheetNames) {
    const sheet = workbook.Sheets[sheetName];
    for (const [address, cell] of Object.entries(sheet)) {
      if (address.startsWith('!') || !cell || typeof cell !== 'object') continue;
      const typedCell = cell as XLSX.CellObject;
      const formula = String(typedCell.f ?? '');
      const value = String(typedCell.v ?? '');
      expect(typedCell.t, `${sheetName}!${address} must not be an Excel error cell`).not.toBe('e');
      expect(`${sheetName}!${address} formula ${formula}`).not.toMatch(excelError);
      expect(`${sheetName}!${address} value ${value}`).not.toMatch(excelError);
    }
  }
}

function assertBomRows(workbook: XLSX.WorkBook, expectations: QuoteWorkbookExpectations): number {
  const bom = workbook.Sheets['BOM明细'];
  const range = XLSX.utils.decode_range(bom['!ref'] ?? 'A1:A1');
  const populatedRows: number[] = [];

  for (let zeroBasedRow = 1; zeroBasedRow <= range.e.r; zeroBasedRow += 1) {
    const excelRow = zeroBasedRow + 1;
    const populated = ['B', 'C', 'D', 'E', 'F', 'G', 'L', 'N'].some(
      (column) => String(bom[`${column}${excelRow}`]?.v ?? '').trim() !== '',
    );
    if (!populated) continue;
    populatedRows.push(excelRow);
    expectFormula(bom, `M${excelRow}`, `IF(L${excelRow}="","",G${excelRow}*L${excelRow})`);
    expectFormula(bom, `O${excelRow}`, `G${excelRow}*N${excelRow}`);
    expect(typeof bom[`N${excelRow}`]?.v, `BOM明细!N${excelRow} cached point value`).toBe('number');
  }

  expect(populatedRows.length, 'BOM 明细 must contain at least one material row').toBeGreaterThan(
    0,
  );
  if (expectations.expectedFirstBomUnitPrice !== undefined) {
    expect(numericCell(bom, `L${populatedRows[0]}`)).toBeCloseTo(
      expectations.expectedFirstBomUnitPrice,
      8,
    );
  }

  const bomRows = sheetRows(workbook, 'BOM明细');
  expect(JSON.stringify(bomRows), 'no raw qo_* field codes leak into the workbook').not.toMatch(
    /qo_(quote|ql|pe)_[a-z_]+/,
  );

  if (expectations.expectedBomLine) {
    const { mpn, unitCost } = expectations.expectedBomLine;
    const headerIndex = bomRows.findIndex((row) => row.some((cell) => String(cell) === '材料单价'));
    expect(headerIndex, 'BOM 明细应包含材料单价表头').toBeGreaterThanOrEqual(0);
    const headers = bomRows[headerIndex].map(String);
    const unitPriceColumn = headers.indexOf('材料单价');
    const processPointColumn = headers.indexOf('加工点数');
    const materialRow = bomRows
      .slice(headerIndex + 1)
      .find((row) => row.some((cell) => String(cell).includes(mpn)));
    expect(materialRow, `BOM 明细应包含修正后的物料 ${mpn}`).toBeTruthy();
    expect(Number(materialRow?.[unitPriceColumn])).toBeCloseTo(unitCost, 4);
    expect(
      Number(materialRow?.[processPointColumn]),
      '修正物料的加工点数应完成重算',
    ).toBeGreaterThan(0);
  }

  return populatedRows.at(-1) ?? 1;
}

function assertProcessRows(workbook: XLSX.WorkBook): void {
  const process = workbook.Sheets['加工明细'];
  for (let row = 3; row <= 11; row += 1) {
    expectFormula(process, `H${row}`, `F${row}*G${row}`);
  }
  expectFormula(process, 'H12', 'SUM(H3:H11)');
  const detailTotal = Array.from({ length: 9 }, (_, index) =>
    numericCell(process, `H${index + 3}`),
  ).reduce((total, value) => total + value, 0);
  expect(numericCell(process, 'H12')).toBeCloseTo(detailTotal, 8);
}

function assertQuoteTotals(
  workbook: XLSX.WorkBook,
  lastBomRow: number,
  expectations: QuoteWorkbookExpectations,
): void {
  const quote = workbook.Sheets['报价单'];
  expectFormula(quote, 'H15', `SUM('BOM明细'!$M$2:$M$${lastBomRow})`);
  expectFormula(quote, 'I15', `'加工明细'!$H$12`);
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

  if (expectations.expectedSetCount !== undefined) {
    expect(setCount, '报价单!G15 单次订单量来自报价套数').toBe(expectations.expectedSetCount);
  }
  expect(managementProfit).toBeCloseTo((materialPerSet + processAndPackagingPerSet) * 0.3, 8);
  expect(unitPrice).toBeCloseTo(
    Math.round((materialPerSet + processAndPackagingPerSet + managementProfit) * 100) / 100,
    8,
  );
  expect(recurringTotal).toBeCloseTo(setCount * unitPrice, 8);
  expect(finalTotal).toBeCloseTo(engineeringFee + stencilFee + recurringTotal, 8);
}

export function validateQuoteWorkbook(
  filePath: string,
  expectations: QuoteWorkbookExpectations = {},
): void {
  const workbook = XLSX.read(fs.readFileSync(filePath), {
    type: 'buffer',
    cellFormula: true,
    cellText: false,
    sheetStubs: true,
  });

  expect(workbook.SheetNames).toEqual(['报价单', 'BOM明细', '加工明细']);
  assertNoFormulaErrors(workbook);
  const lastBomRow = assertBomRows(workbook, expectations);
  assertProcessRows(workbook);
  assertQuoteTotals(workbook, lastBomRow, expectations);
}
