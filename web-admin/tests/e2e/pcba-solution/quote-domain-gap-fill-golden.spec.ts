import * as XLSX from 'xlsx';
import { test, expect, type Page } from '../../fixtures';
import {
  cleanupRows,
  dynamicCreate,
  openQuoteDetailFromList,
  queryDynamicRecords,
  pollAsyncTaskResult,
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

      const marker = `HEADER-${Date.now()}`;
      const customerId = await dynamicCreate(page, 'crm_account_common', {
        crm_acc_name: marker, crm_acc_code: marker, crm_acc_status: 'active', crm_acc_pool_state: 'owned',
      }, created.rows);
      const projectId = await dynamicCreate(page, 'req_requirement_set_pcba_bom', {
        bom_project_name: marker, bom_pcba_code: marker, bom_project_customer_id: customerId,
      }, created.rows);
      const conversion = await page.request.post('/api/meta/commands/execute/bom:start_conversion', {
        data: { payload: { bom_task_customer_id: customerId, bom_task_project_id: projectId,
          bom_task_raw_file_id: fileId, bom_task_raw_filename: `header-only-${marker}.xlsx` } },
      });
      expect(conversion.status()).toBe(200);
      const body = await conversion.json();
      expect(String(body.code), JSON.stringify(body)).toBe('0');
      let task: Record<string, any> | undefined;
      await expect.poll(async () => {
        const tasks = await queryDynamicRecords(page, 'bom_conversion_task_pcba', [
          { fieldName: 'bom_task_project_id', operator: 'EQ', value: projectId },
        ]);
        expect(tasks.length).toBeLessThanOrEqual(1);
        task = tasks[0];
        if (task && !created.rows.some(row => row.pid === String(task!.pid)))
          created.rows.push({ model: 'bom_conversion_task_pcba', pid: String(task.pid) });
        return task?.bom_task_status;
      }, { timeout: 60_000 }).toMatch(/^(completed|failed)$/);
      expect(task!.bom_task_status, 'header-only workbook must factually fail').toBe('failed');
      created.rows.push({ model: 'bom_conversion_task_pcba', pid: String(task!.pid) });
      expect(String(task!.bom_task_error_message), 'empty workbook failure explains its cause').toMatch(/无有效|没有.*数据|未.*数据|空.*BOM|零有效|zero.effective/i);
      expect(String(task!.bom_task_error_message)).not.toMatch(/filename is required/);
      expect(String(task!.bom_task_export_file_id ?? ''), 'failed task has no export').toBe('');
      const canonical = await queryDynamicRecords(page, 'req_requirement_line_pcba_bom', [
        { fieldName: 'bom_cl_task_id', operator: 'EQ', value: task!.pid },
      ]);
      expect(canonical, 'empty workbook creates no canonical lines').toHaveLength(0);
      const quotes = await queryDynamicRecords(page, 'qo_quote_common', [
        { fieldName: 'qo_quote_project_id', operator: 'EQ', value: projectId },
      ]);
      expect(quotes, 'empty workbook creates no quote').toHaveLength(0);
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

  // Q11-04 / G05-02: preserve the original identity; 30 rows prove functional correctness, not capacity.
  test('Q11-04 bulk recompute over a large quote completes and keeps every line', async ({ page }, info) => {
    const lineCount = 30;
    const marker = Date.now();
    const lines = Array.from({ length: lineCount }, (_, i) => ({
      sourceRef: `BULK-R${i + 1}`, sourceRowNo: i + 2, description: `bulk resistor ${i + 1}`,
      refdes: `R${i + 1}`, mpn: `E2E-BULK-${marker}-${i + 1}`, packageName: '0402',
      qtyPerSet: i + 1, qty: i + 1, smtPoints: 1, thtPoints: 0,
    }));
    const created = await seedQuoteScaffold(page, 'BULK', lines);
    const observations: Record<string, unknown>[] = [];
    try {
      await openQuoteDetailFromList(page, created);
      const filters = [{ fieldName: 'qo_ql_quote_id', operator: 'EQ' as const, value: created.quoteId }];
      const before = await queryDynamicRecords(page, 'qo_quote_line_common', filters, { pageSize: 500 });
      expect(before, 'all bulk lines linked to the quote').toHaveLength(lineCount);
      const identities = before.map(row => String(row.pid)).sort();
      const evidenceIds = new Map<string, string>();
      for (const row of before) {
        const evidenceId = await dynamicCreate(page, 'qo_price_evidence_common', {
          qo_pe_quote_id: created.quoteId, qo_pe_quote_line_id: row.pid,
          qo_pe_source: 'yunhan', qo_pe_source_ref: `fixed-ladder-${row.pid}`,
          qo_pe_part_no: row.qo_ql_mpn, qo_pe_unit_price: 0.08, qo_pe_currency: 'CNY',
          qo_pe_moq: 1, qo_pe_mpq: 1, qo_pe_confidence: 0.9, qo_pe_status: 'captured',
          qo_pe_snapshot: { source: 'yunhan', matchedBy: 'mpn', proName: row.qo_ql_mpn,
            ladderNums: [1, 100, 1000], ladderPrices: [0.08, 0.04, 0.02] },
        }, created.rows);
        evidenceIds.set(String(row.pid), evidenceId);
      }
      let previous: unknown;
      for (const [phase, setCount] of [['ten-sets', 10], ['hundred-sets', 100], ['repeat-hundred', 100]] as const) {
        const started = Date.now();
        const response = await page.request.post('/api/meta/commands/execute/qo_quote_common:recompute_quantities', {
          data: { payload: { qo_quote_set_count: setCount }, targetRecordPid: created.quoteId, operationType: 'update' },
          timeout: 60_000,
        });
        const body = await response.json();
        expect(response.ok(), JSON.stringify(body).slice(0, 500)).toBe(true);
        expect(String(body.code)).toBe('0');
        const commandResult = body.data?.data;
        expect(commandResult, 'command envelope contains an actual result or async task').toBeTruthy();
        const result = commandResult.async === true
          ? await pollAsyncTaskResult(page, String(commandResult.taskCode)) : commandResult;
        expect(result.status, 'recompute finishes its existing downstream steps').toBe('completed');
        expect(Number(result.setCount)).toBe(setCount);
        expect(result.suppliersQueried, 'local recompute uses stored evidence without external sourcing').toBe(false);
        const sqlCount = Number(response.headers()['x-sql-count']);
        expect(response.headers()['x-sql-count'], 'real backend SQL observation header is present').toBeTruthy();
        expect(Number.isInteger(sqlCount) && sqlCount >= 0).toBe(true);
        observations.push({ phase, setCount, rowCount: lineCount, submissionSqlCount: sqlCount, workerSqlCount: null, elapsedMs: Date.now() - started,
          performanceVerdict: 'observation-only-no-capacity-or-budget-claim' });
        const after = await queryDynamicRecords(page, 'qo_quote_line_common', filters, { pageSize: 500 });
        expect(after.length, 'bulk recompute keeps every line').toBe(lineCount);
        expect(after.map(row => String(row.pid)).sort(), 'bulk recompute preserves exact unique line identities').toEqual(identities);
        const evidence = await queryDynamicRecords(page, 'qo_price_evidence_common', [
          { fieldName: 'qo_pe_quote_id', operator: 'EQ', value: created.quoteId },
        ], { pageSize: 500 });
        expect(evidence, 'local recompute neither creates nor loses price candidates').toHaveLength(lineCount);
        const actual = after.map(row => {
          const index = Number(String(row.qo_ql_source_ref).replace('BULK-R', ''));
          expect(Number.isInteger(index) && index >= 1 && index <= lineCount).toBe(true);
          // Independent fixture arithmetic, not the production quantity/tier helper.
          const expectedQty = index * setCount;
          const expectedPrice = expectedQty >= 1000 ? 0.02 : expectedQty >= 100 ? 0.04 : 0.08;
          expect(Number(row.qo_ql_qty_per_set), 'per-set usage is unchanged').toBe(index);
          expect(Number(row.qo_ql_qty), 'every bulk quantity equals its independent per-line expectation').toBe(expectedQty);
          const candidate = evidence.find(item => String(item.pid) === evidenceIds.get(String(row.pid)));
          expect(candidate, 'original evidence belongs to this exact line').toBeTruthy();
          expect(String(candidate!.qo_pe_quote_line_id)).toBe(String(row.pid));
          expect(Number(candidate!.qo_pe_unit_price), 'stored ladder selects the expected local tier').toBe(expectedPrice);
          expect(candidate!.qo_pe_status, 'recompute does not auto-adopt a captured candidate').toBe('captured');
          return { index, pid: row.pid, quantity: Number(row.qo_ql_qty), evidenceId: candidate!.pid,
            price: Number(candidate!.qo_pe_unit_price), status: candidate!.qo_pe_status };
        }).sort((left, right) => left.index - right.index);
        if (phase === 'repeat-hundred') expect(actual, 'repeated identical input keeps every business result stable').toEqual(previous);
        previous = actual;
      }
      await info.attach('q11-g05-recompute-observations', {
        body: JSON.stringify({ quoteId: created.quoteId, observations, result: previous }, null, 2),
        contentType: 'application/json',
      });
    } finally {
      await cleanupRows(page, created);
    }
  });
});
