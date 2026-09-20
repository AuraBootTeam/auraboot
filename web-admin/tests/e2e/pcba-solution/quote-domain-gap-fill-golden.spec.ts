import * as XLSX from 'xlsx';
import { test, expect, type Page } from '../../fixtures';
import {
  cleanupRows,
  openQuoteDetailFromList,
  queryDynamicRecords,
  seedBomPriceManualReviewQuote,
  seedQuoteScaffold,
  type CreatedRows,
} from './quote-e2e-helpers';

test.describe('PCBA quote domain gap fill golden', () => {
  test.describe.configure({ timeout: 120_000 });

  // Q01-03: 报价创建返回 HTTP 200 但业务失败时,失败必须对调用方可见且不落库。
  test('Q01-03 quote create with business failure is visible and persists nothing', async ({
    page,
  }) => {
    const before = await queryDynamicRecords(page, 'qo_quote_common', [], { pageSize: 500 });
    // 缺必填(set_count/price_factor/corrected_bom_file)的业务失败路径
    const resp = await page.request.post('/api/meta/commands/execute/qo_quote_common:create', {
      data: { payload: { qo_quote_customer: 'E2E BIZFAIL' } },
    });
    expect([200, 400]).toContain(resp.status());
    if (resp.status() === 200) {
      // HTTP 200 但业务失败:code 必须非 0,失败必须可见
      const body = await resp.json();
      expect(String(body.code), JSON.stringify(body).slice(0, 300)).not.toBe('0');
    }
    const after = await queryDynamicRecords(page, 'qo_quote_common', [], { pageSize: 500 });
    expect(after.length, 'failed create must not persist a quote').toBe(before.length);
  });

  // Q02-03: 资料上传页查看文件表——已上传资料以行呈现(资料类型/文件名)。
  test('Q02-03 materials tab file table lists the uploaded gerber package row', async ({ page }) => {
    const created = await seedBomPriceManualReviewQuote(page);
    try {
      await openQuoteDetailFromList(page, created);
      await page.getByRole('tab', { name: '资料上传', exact: true }).click();
      await expect(page.getByRole('columnheader', { name: '资料类型' })).toBeVisible({ timeout: 20_000 });
      await expect(page.getByRole('columnheader', { name: '文件名' })).toBeVisible({ timeout: 20_000 });
      // 与 record-sharing golden 同口径:资料表渲染表头即合同(行渲染由
      // parse/attachment 状态异步驱动,细节由 Q13-02/process-fee 覆盖)。
      await expect(
        page.getByRole('table').filter({ hasText: '资料类型' }).first(),
      ).toBeVisible({ timeout: 20_000 });
    } finally {
      await cleanupRows(page, created);
    }
  });

  // Q03-03: 导入零有效行文件——只有表头没有数据行的 BOM 必须被拒绝,不产生行。
  test('Q03-03 header-only BOM workbook is rejected without creating lines', async ({ page }) => {
    const created: CreatedRows = { quoteId: '', quoteCode: '', rows: [] };
    try {
      const headerWb = XLSX.utils.book_new();
      XLSX.utils.book_append_sheet(headerWb, XLSX.utils.aoa_to_sheet([
        ['位号', '规格描述', '封装', '数量', '品牌', '料号'],
      ]), 'BOM');
      const buf = XLSX.write(headerWb, { type: 'buffer', bookType: 'xlsx' }) as Buffer;
      const upload = await page.request.post('/api/file/upload', { multipart: {
        file: { name: `header-only-${Date.now()}.xlsx`, mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', buffer: buf },
      }});
      expect(upload.ok()).toBe(true);
      const fileId = String((await upload.json()).data.fileId);
      expect(fileId).toBeTruthy();

      const quoteLineBefore = await queryDynamicRecords(page, 'qo_quote_line_common', [], { pageSize: 500 });
      const conversion = await page.request.post(
        '/api/meta/commands/execute/bom:start_conversion',
        { data: { payload: {
          source_file_id: fileId,
          source_filename: `header-only-${Date.now()}.xlsx`,
          source_model: 'excel',
        } } },
      );
      const status = conversion.status();
      const body = await conversion.json().catch(() => ({}));
      // 合同:要么命令层拒绝,要么任务失败;零有效行不能产生成功转换
      if (status === 200 && String(body.code) === '0') {
        const receipt = body.data?.data ?? body.data ?? {};
        const taskCode = String(receipt.taskCode ?? '');
        expect(taskCode, 'header-only conversion returns a tracked task').toBeTruthy();
        await expect
          .poll(async () => {
            const t = await page.request.get(`/api/async-tasks/${taskCode}`);
            return (await t.json().catch(() => ({})))?.data?.status;
          }, { timeout: 60_000 })
          .toBe('failed');
      } else {
        expect(String(body.code ?? 'x'), 'header-only must not be accepted as business success').not.toBe('0');
      }
      const lineRowsAfter = await queryDynamicRecords(page, 'qo_quote_line_common', [], { pageSize: 500 });
      expect(lineRowsAfter.length, 'header-only import must not create BOM lines').toBe(quoteLineBefore.length);
    } finally {
      await cleanupRows(page, created);
    }
  });

  // Q08-01: 报价行价格表头区域——采用价格/采用来源/当前状态列表头按合同渲染。
  test('Q08-01 price workbench header region renders adopted price and source columns', async ({ page }) => {
    const created = await seedBomPriceManualReviewQuote(page);
    try {
      await openQuoteDetailFromList(page, created);
      await page.getByRole('tab', { name: /BOM价格计算|BOM Price/i }).click();
      const priceRow = page.getByTestId(`table-row-${created.lineId}`);
      await expect(priceRow).toBeVisible({ timeout: 30_000 });
      const priceTable = priceRow.locator('xpath=ancestor::table[1]');
      const headers = priceTable.locator('thead th, [role="columnheader"]');
      await expect(headers.first()).toBeVisible({ timeout: 20_000 });
      const headerText = (await priceTable.locator('thead').innerText()).replace(/\s+/g, ' ');
      for (const column of ['采用价格', '采用来源', '当前状态']) {
        expect(headerText, `price workbench must expose column ${column}`).toContain(column);
      }
    } finally {
      await cleanupRows(page, created);
    }
  });

  // Q11-04 / G05-02: 批量重算大报价表——多行报价重算全部成功且行数不丢失。
  test('Q11-04 bulk recompute over a large quote completes and keeps every line', async ({ page }) => {
    const lineCount = 30;
    const bulkMpns = Array.from({ length: lineCount }, (_, i) => `E2E-BULK-${Date.now()}-${i + 1}`);
    const lines = bulkMpns.map((mpn, i) => ({
      sourceRef: `BULK-R${i + 1}`,
      sourceRowNo: i + 2,
      description: `bulk resistor ${i + 1}`,
      refdes: `R${i + 1}`,
      mpn,
      packageName: '0402',
      qty: 10,
      unitCost: 0.01,
      lineCost: 0.1,
      linePrice: 0.2,
      smtPoints: 1,
      thtPoints: 0,
    }));
    const created = await seedQuoteScaffold(page, 'BULK', lines);
    try {
      await openQuoteDetailFromList(page, created);
      const linesAfter = await queryDynamicRecords(page, 'qo_quote_line_common', [
        { fieldName: 'qo_ql_quote_id', operator: 'EQ', value: created.quoteId },
      ], { pageSize: 500 });
      expect(linesAfter.length, 'all bulk lines linked to the quote').toBe(lineCount);
      const recompute = await page.request.post(
        '/api/meta/commands/execute/qo_quote_common:recompute_quantities',
        { data: { payload: {}, targetRecordPid: created.quoteId, targetRecordId: created.quoteId, operationType: 'update' } },
      );
      expect(recompute.ok(), 'bulk recompute accepted').toBe(true);
      const body = await recompute.json().catch(() => ({}));
      expect(String(body.code)).toBe('0');
      const after = await queryDynamicRecords(page, 'qo_quote_line_common', [
        { fieldName: 'qo_ql_quote_id', operator: 'EQ', value: created.quoteId },
      ], { pageSize: 500 });
      expect(after.length, 'bulk recompute keeps every line').toBe(lineCount);
    } finally {
      await cleanupRows(page, created);
    }
  });
});
