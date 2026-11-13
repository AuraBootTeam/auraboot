import { readFileSync } from 'node:fs';
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
        crm_acc_pool_state: 'owned',
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
            { refdes: 'HS1', description: '铝散热片 结构件', pkg: '', qty: '1', brand: 'E2E', mpn: `HS-${marker}` },
            { refdes: 'R3', description: '10kΩ 0402 电阻 DNP', pkg: '0402', qty: 'DNP', brand: 'YAGEO', mpn: `DNP-${marker}` },
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
          bom_task_raw_filename: `b09-mixed-${marker}.xlsx`,
        } } },
      );
      const body = await conversion.json().catch(() => ({}));
      expect(conversion.status()).toBe(200);
      expect(String(body.code), JSON.stringify(body)).toBe('0');
      let task: Record<string, any> | undefined;
      await expect.poll(async () => {
        const tasks = await queryDynamicRecords(page, 'bom_conversion_task_pcba', [
          { fieldName: 'bom_task_project_id', operator: 'EQ', value: project },
        ], { pageSize: 5 });
        expect(tasks.length, 'one task for this project').toBeLessThanOrEqual(1);
        task = tasks[0];
        if (task && !created.rows.some(row => row.pid === String(task!.pid)))
          created.rows.push({ model: 'bom_conversion_task_pcba', pid: String(task.pid) });
        return task?.bom_task_status;
      }, { timeout: 120_000 }).toMatch(/^(completed|failed)$/);
      expect(task!.bom_task_status, `B09 conversion: ${task!.bom_task_error_message ?? ''}`).toBe('completed');
      const taskId = String(task!.pid);
      created.rows.push({ model: 'bom_conversion_task_pcba', pid: taskId });
      const [raw, standard] = await Promise.all([
        queryDynamicRecords(page, 'bom_raw_line_pcba', [
          { fieldName: 'bom_raw_task_id', operator: 'EQ', value: taskId },
        ]),
        queryDynamicRecords(page, 'bom_standard_line_pcba', [
          { fieldName: 'bom_std_task_id', operator: 'EQ', value: taskId },
        ]),
      ]);
      expect(raw, 'all mixed source rows retained').toHaveLength(4);
      expect(standard, 'all mixed standard rows retained').toHaveLength(4);
      for (const [mpn, qty] of [[`BRKT-${marker}`, '1'], [`HS-${marker}`, '1'], [`DNP-${marker}`, 'DNP']]) {
        const source = raw.filter(row => row.bom_raw_mpn === mpn);
        expect(source, `one source row for ${mpn}`).toHaveLength(1);
        expect(String(source[0].bom_raw_qty)).toBe(qty);
      }
      const dnp = standard.filter(row => row.bom_std_mpn === `DNP-${marker}`);
      expect(dnp).toHaveLength(1);
      const quantity = JSON.parse(String(dnp[0].bom_std_quantity_json));
      expect(quantity.semantics).toBe('DNP');
      expect(quantity.rawValue).toBe('DNP');
      expect(String(dnp[0].bom_std_reason_code)).toBe('qty_invalid');
      expect(String(dnp[0].bom_std_material_code ?? '')).toBe('');
      expect(String(dnp[0].bom_std_candidate_codes ?? '')).toBe('');
      const evidence = await queryDynamicRecords(page, 'bom_match_evidence', [
        { fieldName: 'bom_me_canonical_line_id', operator: 'EQ', value: dnp[0].pid },
      ]);
      expect(evidence, 'DNP must not enter candidate matching').toHaveLength(0);
    } finally {
      await cleanupRows(page, created);
    }
  });

  // B02-03: 上传超大文件(100MB 级)——网关按合同拒绝或稳定接受,不崩溃。
  test('B02-03 oversized upload is handled without crashing the gateway', async ({ page }) => {
    const filename = `oversized-B02-${Date.now()}.bin`;
    const config = readFileSync(new URL('../../../../platform/platform-app/src/main/resources/application.yml', import.meta.url), 'utf8');
    const configured = config.match(/max-file-size:\s*(\d+)MB/);
    expect(configured, 'reviewed upload limit is explicitly configured').not.toBeNull();
    const limitBytes = Number(configured![1]) * 1024 * 1024;
    const resp = await page.request.post('/api/file/upload', { multipart: {
      file: { name: filename, mimeType: 'application/octet-stream', buffer: Buffer.alloc(limitBytes + 1, 48) },
    }, timeout: 120_000 });
    expect([400, 413], 'a file above the configured limit must be rejected').toContain(resp.status());
    const tasks = await queryDynamicRecords(page, 'bom_conversion_task_pcba', [
      { fieldName: 'bom_task_raw_filename', operator: 'EQ', value: filename },
    ]);
    expect(tasks, 'rejected upload creates no conversion task').toHaveLength(0);
  });
});
