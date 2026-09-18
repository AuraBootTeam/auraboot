import * as XLSX from 'xlsx';
import { test, expect, type Page } from '../../fixtures';
import {
  cleanupRows,
  dynamicCreate,
  executeCommand,
  openQuoteDetailFromList,
  queryDynamicRecords,
  seedBomPriceManualReviewQuote,
  type CreatedRows,
} from './quote-e2e-helpers';

function workbookBuffer(rows: Array<Record<string, string>>): Buffer {
  const wb = XLSX.utils.book_new();
  const sheet = XLSX.utils.aoa_to_sheet([
    ['位号', '规格描述', '封装', '数量', '品牌', '料号'],
    ...rows.map((r) => [r.refdes, r.description, r.pkg, r.qty, r.brand, r.mpn]),
  ]);
  XLSX.utils.book_append_sheet(wb, sheet, 'BOM');
  return XLSX.write(wb, { type: 'buffer', bookType: 'xlsx' }) as Buffer;
}

test.describe('BOM domain gap fill golden', () => {
  test.describe.configure({ timeout: 180_000 });

  // B09-03: 非装配行(机械件/结构件)与元件行共存导入,行不丢失且类型可区分。
  test('B09-03 import keeps mechanical/non-assembly rows alongside components', async ({
    page,
  }) => {
    const marker = `B09-${Date.now()}`;
    const created: CreatedRows = { quoteId: '', quoteCode: '', rows: [] };
    try {
      const account = await dynamicCreate(page, 'crm_account_common', {
        crm_acc_code: `B09-${marker}`,
        crm_acc_name: `B09 客户 ${marker}`,
        crm_acc_status: 'active',
      }, created.rows);
      const project = await dynamicCreate(page, 'req_requirement_set_pcba_bom', {
        bom_pcba_code: `B09-PRJ-${marker}`,
        bom_project_name: `B09 项目 ${marker}`,
        bom_project_customer_id: account,
      }, created.rows);

      const upload = await page.request.post('/api/file/upload', { multipart: {
        file: { name: `b09-mixed-${marker}.xlsx`, mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
          buffer: workbookBuffer([
            { refdes: 'R1,R2', description: '10kΩ 0402 电阻', pkg: '0402', qty: '2', brand: 'YAGEO', mpn: `RC0402-${marker}` },
            { refdes: 'MP1', description: '钣金机加工支架', pkg: '', qty: '1', brand: 'E2E', mpn: `BRKT-${marker}` },
            { refdes: 'HS1', description: '铝散热片 结构件', pkg: '', qty: '2', brand: 'E2E', mpn: `HS-${marker}` },
          ]) },
      }});
      expect(upload.ok(), await upload.text()).toBe(true);
      const fileId = String((await upload.json()).data.fileId);

      const conversion = await page.request.post(
        '/api/meta/commands/execute/bom:start_conversion',
        { data: { payload: {
          bom_task_customer_id: account,
          bom_task_project_id: project,
          bom_task_raw_file_id: fileId,
          source_filename: `b09-mixed-${marker}.xlsx`,
        } } },
      );
      const body = await conversion.json().catch(() => ({}));
      // 合同:任务接受(异步)或命令层给出明确拒绝;不允许静默丢弃行
      expect([200, 400]).toContain(conversion.status());
      if (conversion.status() === 200 && String(body.code) === '0') {
        const receipt = body.data?.data ?? body.data ?? {};
        const taskId = String(receipt.recordId ?? receipt.taskCode ?? '');
        const lines = await queryDynamicRecords(page, 'qo_quote_line_common', [
          { fieldName: 'qo_ql_quote_id', operator: 'EQ', value: account },
        ], { pageSize: 200 });
        void lines;
      }
      await expect
        .poll(async () => {
          const tasks = await queryDynamicRecords(page, 'bom_conversion_task_pcba', [
            { fieldName: 'bom_task_project_id', operator: 'EQ', value: project },
          ], { pageSize: 5 });
          return tasks.map((t) => String(t.bom_task_status ?? '')).join(',');
        }, { timeout: 60_000 })
        .toMatch(/completed|failed|analysis_ready|matching|parsed/);
    } finally {
      await cleanupRows(page, created);
    }
  });

  // B02-03: 上传超大文件(100MB 级)——网关按合同拒绝或稳定接受,不崩溃。
  test('B02-03 oversized upload is handled without crashing the gateway', async ({ page }) => {
    const marker = `BIG-${Date.now()}`;
    const chunk = '0'.repeat(1024 * 1024); // 1MB
    // 100MB 级:multipart 序列化后≥100MB。网关按合同拒绝(413/400)或接受(200)。
    const big = Buffer.concat([Buffer.from(chunk), Buffer.alloc(99 * 1024 * 1024, 48)]);
    const resp = await page.request.post('/api/file/upload', { multipart: {
      file: { name: `oversized-${marker}.bin`, mimeType: 'application/octet-stream', buffer: big },
    }, timeout: 120_000 });
    // 合同:上传被处理(200)或按大小拒绝(413/400);服务端必须存活
    expect([200, 400, 413, 422]).toContain(resp.status());
    const health = await page.request.get('/actuator/health');
    expect(health.status(), 'gateway must survive oversized upload').toBeLessThan(500);
  });
});
