import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import { uniqueId } from '../helpers';
import {
  makeQuoteRoleUser,
  ensureQuoteRoleUser,
  openQuoteRolePage,
  readDynamicRecord,
  queryDynamicRecords,
  type QuoteRoleUser,
} from './quote-e2e-helpers';

/**
 * Quote/BOM 真机 深度 — 报价行动点 QO-03(资料上传)/ QO-05(加工费)/ QO-06(Gerber)/
 * QO-08(DeepSeek 建议价)/ QO-09(采购价批量寻源). Provisions a quote, links a BOM conversion so
 * This is API integration with browser-context authentication. It checks async completion and
 * persisted results; the paired Quote UI goldens cover the corresponding user-click journeys.
 */
const HERE = path.dirname(fileURLToPath(import.meta.url));
const uid = uniqueId('qoa').replace(/_/g, '-');
const users: Record<string, QuoteRoleUser> = {};

const SKIP = /DEIta|Eletrum|RK3566|SmartHub|AfterMarket/i;
const PREFERRED = ['FUTROBO_MCU', 'HOLO_CV1812C', 'AGRC', 'A00104001', 'HD31'];
function sampleRoot(): string | undefined {
  const rel = 'aura-quote/docs/ref/10款GERBER加坐标';
  for (const r of [
    process.env.QUOTE_BOM_SAMPLES_DIR,
    path.resolve(HERE, '../../../../../' + rel),
    '/Users/ghj/work/auraboot/' + rel,
  ].filter(Boolean) as string[]) {
    if (fs.existsSync(r)) return r;
  }
  return undefined;
}
function pickSample(): { bom?: string; coord?: string; gerber?: string } {
  try {
    const root = sampleRoot();
    if (!root) return {};
    const dirs = fs
      .readdirSync(root)
      .filter((s) => !SKIP.test(s))
      .sort((a, b) => {
        const ra = PREFERRED.findIndex((p) => a.includes(p));
        const rb = PREFERRED.findIndex((p) => b.includes(p));
        return (ra < 0 ? 99 : ra) - (rb < 0 ? 99 : rb);
      });
    for (const s of dirs) {
      const base = path.join(root, s);
      const find = (sub: RegExp, ext: RegExp): string | undefined => {
        const d = fs.readdirSync(base).find((x) => sub.test(x));
        if (!d) return undefined;
        const p = path.join(base, d);
        if (!fs.statSync(p).isDirectory()) return undefined;
        const f = fs.readdirSync(p).find((x) => ext.test(x));
        return f ? path.join(p, f) : undefined;
      };
      const bom = find(/BOM/i, /\.xlsx$/i);
      const coord = find(/坐标|coord/i, /\.(xlsx|csv|txt)$/i);
      const gerber = find(/PCB|gerber/i, /\.(zip|ger|gbr|gtl|gbl)$/i);
      if (bom && coord && gerber) return { bom, coord, gerber };
    }
  } catch {
    /* Missing fixtures fail the mandatory presence assertions below. */
  }
  return {};
}
let S: { bom?: string; coord?: string; gerber?: string } = {};

async function post(page: Page, code: string, payload: any, op = 'create', target?: string) {
  const data: any = { payload, operationType: op };
  if (target) data.targetRecordPid = target;
  const r = await page.context().request.post(`/api/meta/commands/execute/${code}`, { data });
  const body = await r.json();
  expect(r.status(), `${code} HTTP success`).toBe(200);
  expect(String(body.code), `${code} business success`).toBe('0');
  const commandData = body?.data?.data;
  let resultData = commandData;
  if (commandData?.async === true) {
    expect(commandData.taskCode, `${code} async identity`).toBeTruthy();
    let terminal: any;
    await expect
      .poll(
        async () => {
          const response = await page
            .context()
            .request.get(`/api/async-tasks/${encodeURIComponent(commandData.taskCode)}`);
          expect(response.status()).toBe(200);
          const result = await response.json();
          expect(String(result.code)).toBe('0');
          terminal = result.data;
          return ['completed', 'failed', 'cancelled'].includes(String(terminal?.status));
        },
        { timeout: 180_000, intervals: [500, 1000, 2000] },
      )
      .toBe(true);
    expect(
      terminal.status,
      `${code} async terminal: ${JSON.stringify(terminal).slice(0, 1200)}`,
    ).toBe('completed');
    resultData = terminal.resultData;
    test.info().annotations.push({
      type: 'async-terminal',
      description: JSON.stringify({
        command: code,
        taskCode: commandData.taskCode,
        status: terminal.status,
        result: terminal.resultData,
      }).slice(0, 4000),
    });
  }
  return { status: r.status(), body, resultData };
}
const pid = (b: any) =>
  b?.data?.data?.recordPid ||
  b?.data?.data?.recordId ||
  b?.data?.data?.quote?.pid ||
  b?.data?.recordPid ||
  b?.data?.recordId;
function mimeFor(name: string): string {
  if (/\.xlsx$/i.test(name))
    return 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
  if (/\.zip$/i.test(name)) return 'application/zip';
  if (/\.csv$/i.test(name)) return 'text/csv';
  return 'application/octet-stream';
}
async function upload(page: Page, filePath: string, name: string) {
  const buf = fs.readFileSync(filePath);
  const r = await page.context().request.post('/api/file/upload', {
    multipart: { file: { name, mimeType: mimeFor(name), buffer: buf } },
  });
  expect(r.status(), `${name} upload HTTP`).toBe(200);
  const body = await r.json();
  expect(String(body.code), `${name} upload business result`).toBe('0');
  return body?.data?.fileId;
}

async function countQuoteLines(page: Page, quoteId: string): Promise<number> {
  const r = await page
    .context()
    .request.get(
      '/api/dynamic/qo_quote_line_common/list?pageNum=1&pageSize=500&sortField=created_at&sortOrder=desc',
    );
  const b = await r.json().catch(() => ({}) as any);
  const recs = b?.data?.records || b?.data?.data?.records || b?.data || [];
  return (Array.isArray(recs) ? recs : []).filter(
    (line: any) => String(line.qo_ql_quote_id || '') === String(quoteId),
  ).length;
}

test.describe('Quote actions deep — upload/process-fee/gerber/deepseek/source-prices (QO-03/05/06/08/09) @smoke', () => {
  test.describe.configure({ mode: 'serial', timeout: 300_000 });
  let quoteId = '';

  test.beforeAll(async ({ browser }) => {
    S = pickSample();
    const ctx = await browser.newContext({
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
    });
    const page = await ctx.newPage();
    users['sales'] = makeQuoteRoleUser('qo_sales', uid, ['qo_sales']);
    await ensureQuoteRoleUser(page, users['sales']);
    await ctx.close();
  });

  test('provision quote + linked conversion (real lines)', async ({ browser }) => {
    expect(S.bom, 'sample BOM present').toBeTruthy();
    expect(S.gerber, 'real Gerber fixture is mandatory; never substitute BOM bytes').toBeTruthy();
    expect(
      S.coord,
      'real coordinate fixture is mandatory; never substitute BOM bytes',
    ).toBeTruthy();
    const { context, page } = await openQuoteRolePage(browser, users['sales']);
    try {
      const proj = await post(page, 'bom:create_project', {
        bom_project_name: `QOA ${uid}`,
        bom_pcba_code: `QOA-${uid}`,
      });
      const projId = pid(proj.body);
      expect(projId, `project created (status=${proj.status})`).toBeTruthy();
      const bomFileId = await upload(page, S.bom!, 'bom.xlsx');
      expect(bomFileId, 'BOM uploaded').toBeTruthy();
      const corrected = JSON.stringify([
        { name: 'bom.xlsx', url: `/api/file/download/${bomFileId}`, fileId: bomFileId },
      ]);
      const gerberFileId = await upload(page, S.gerber!, path.basename(S.gerber!));
      const cplFileId = await upload(page, S.coord!, path.basename(S.coord!));
      expect(gerberFileId, 'Gerber uploaded').toBeTruthy();
      expect(cplFileId, 'CPL uploaded').toBeTruthy();
      const gerber = JSON.stringify([
        {
          name: path.basename(S.gerber!),
          url: `/api/file/download/${gerberFileId}`,
          fileId: gerberFileId,
        },
      ]);
      const cpl = JSON.stringify([
        {
          name: path.basename(S.coord!),
          url: `/api/file/download/${cplFileId}`,
          fileId: cplFileId,
        },
      ]);
      const cr = await post(page, 'qo_quote_common:create', {
        qo_quote_code: `QOA-${uid}`.slice(0, 28),
        qo_quote_customer: `QOA ${uid}`,
        qo_quote_project_id: projId,
        corrected_bom_file: corrected,
        corrected_bom_file_id: bomFileId,
        corrected_bom_filename: 'bom.xlsx',
        gerber_source_file: gerber,
        gerber_source_file_id: gerberFileId,
        gerber_source_filename: path.basename(S.gerber!),
        cpl_source_file: cpl,
        cpl_source_file_id: cplFileId,
        cpl_source_filename: path.basename(S.coord!),
      });
      quoteId = pid(cr.body);
      expect(
        quoteId,
        `quote created (status=${cr.status} resp=${JSON.stringify(cr.body?.data || cr.body).slice(0, 240)})`,
      ).toBeTruthy();
      // link a BOM conversion to the quote so it gets real lines
      await post(page, 'bom:start_conversion', {
        bom_task_project_id: projId,
        bom_task_source_package: 'qoa',
        bom_task_source_model: 'qo_quote_common',
        bom_task_source_id: quoteId,
        bom_task_raw_file_id: bomFileId,
        bom_task_raw_filename: 'bom.xlsx',
      });
      // wait for quote lines to exist (pricing precondition)
      let lines = 0;
      await expect
        .poll(
          async () => {
            lines = await countQuoteLines(page, quoteId);
            return lines;
          },
          { timeout: 150_000, intervals: [1_000, 2_000, 5_000] },
        )
        .toBeGreaterThan(0);
      test
        .info()
        .annotations.push({ type: 'note', description: `quote ${quoteId} lines=${lines}` });
    } finally {
      await context.close();
    }
  });

  test('QO-03 upload source attachments (raw_bom / gerber_package / cpl)', async ({ browser }) => {
    expect(quoteId, 'quote provisioned').toBeTruthy();
    const { context, page } = await openQuoteRolePage(browser, users['sales']);
    try {
      const types: Array<[string, string | undefined, string]> = [
        ['raw_bom', S.bom, 'bom.xlsx'],
        ['gerber_package', S.gerber, path.basename(S.gerber!)],
        ['cpl', S.coord, path.basename(S.coord!)],
      ];
      let ok = 0;
      for (const [type, fp, nm] of types) {
        expect(fp, `${type} fixture present`).toBeTruthy();
        const fid = await upload(page, fp!, nm);
        const r = await post(
          page,
          'qo_quote_common:upload_source_attachment',
          { source_file_id: fid, attachment_type: type, filename: nm },
          'update',
          quoteId,
        );
        test
          .info()
          .annotations.push({ type: 'note', description: `QO-03 ${type} status=${r.status}` });
        expect(r.resultData.quoteId).toBe(quoteId);
        expect(r.resultData.attachmentType).toBe(type);
        const saved = await readDynamicRecord(
          page,
          'qo_rfq_source_attachment_common',
          String(r.resultData.attachmentId),
        );
        expect(saved.qo_rsa_type).toBe(type);
        expect(saved.qo_rsa_file_id).toBe(fid);
        expect(saved.qo_rsa_filename).toBe(nm);
        if (r.status === 200) ok++;
      }
      expect(ok, 'QO-03: every attachment type recorded').toBe(3);
    } finally {
      await context.close();
    }
  });

  test('QO-05 compute process fee', async ({ browser }) => {
    const { context, page } = await openQuoteRolePage(browser, users['sales']);
    try {
      const r = await post(
        page,
        'qo_quote_common:compute_process_fee',
        { count_hole_mode: 'none' },
        'update',
        quoteId,
      );
      test.info().annotations.push({
        type: 'note',
        description: `QO-05 status=${r.status} body=${JSON.stringify(r.body?.data).slice(0, 160)}`,
      });
      expect(r.resultData.status).toBe('completed');
      expect(Number(r.resultData.totalPoints)).toBeGreaterThan(0);
      const hits = await queryDynamicRecords(page, 'qo_process_fee_rule_hit_common', [
        { fieldName: 'qo_pfrh_quote_id', operator: 'EQ', value: quoteId },
      ]);
      expect(hits.length).toBeGreaterThan(0);
      expect(
        hits.every(
          (row) => row.qo_pfrh_point_source === 'SIMPLE_COUNT_V1' && !row.qo_pfrh_quote_line_id,
        ),
      ).toBe(true);
      expect(hits.reduce((n, row) => n + Number(row.qo_pfrh_total_points), 0)).toBe(
        Number(r.resultData.totalPoints),
      );
    } finally {
      await context.close();
    }
  });

  test('QO-06 gerber package recorded as a source attachment', async ({ browser }) => {
    const { context, page } = await openQuoteRolePage(browser, users['sales']);
    try {
      // upload a gerber_package via the real attachment command — the gerber resource must be recorded
      // with attachmentType=gerber_package (parse-ready). Full point-count/SVG parse via the gerber
      // sidecar (:8410) is covered by the python L3 golden.
      const fid = await upload(page, S.gerber!, path.basename(S.gerber!));
      expect(fid, 'gerber file uploaded').toBeTruthy();
      const r = await post(
        page,
        'qo_quote_common:upload_source_attachment',
        {
          source_file_id: fid,
          attachment_type: 'gerber_package',
          filename: path.basename(S.gerber!),
        },
        'update',
        quoteId,
      );
      const body = r.body?.data?.data || r.body?.data || {};
      test.info().annotations.push({
        type: 'note',
        description: `QO-06 status=${r.status} body=${JSON.stringify(body).slice(0, 180)}`,
      });
      // post() verifies async completion; the saved file identity is checked below.
      // Parser count semantics are independently checked by the whole-board browser golden.
      expect(r.status, 'QO-06: gerber_package attachment command accepted').toBe(200);
      expect(r.resultData.quoteId).toBe(quoteId);
      const saved = await readDynamicRecord(
        page,
        'qo_rfq_source_attachment_common',
        String(r.resultData.attachmentId),
      );
      expect(saved.qo_rsa_type).toBe('gerber_package');
      expect(saved.qo_rsa_file_id).toBe(fid);
    } finally {
      await context.close();
    }
  });

  test('QO-08 deepseek price suggestions async terminal (provider boundary)', async ({
    browser,
  }) => {
    const { context, page } = await openQuoteRolePage(browser, users['sales']);
    try {
      const r = await post(
        page,
        'qo_quote_common:deepseek_price_suggestions',
        {},
        'update',
        quoteId,
      );
      const lines = await queryDynamicRecords(
        page,
        'qo_quote_line_common',
        [{ fieldName: 'qo_ql_quote_id', operator: 'EQ', value: quoteId }],
        { pageSize: 500 },
      );
      const missing = lines.filter((line) => Number(line.qo_ql_unit_cost || 0) <= 0);
      expect(missing.length, 'fixture must exercise missing-price suggestions').toBeGreaterThan(0);
      expect(Number(r.resultData.candidateCount)).toBe(missing.length);
      let suggested = 0;
      let failed = 0;
      for (const line of missing) {
        const evidence = await queryDynamicRecords(
          page,
          'qo_price_evidence_common',
          [
            { fieldName: 'qo_pe_quote_line_id', operator: 'EQ', value: line.pid },
            { fieldName: 'qo_pe_source', operator: 'EQ', value: 'deepseek_llm' },
          ],
          { pageSize: 100 },
        );
        expect(evidence, `one terminal result for ${line.pid}`).toHaveLength(1);
        const row = evidence[0];
        expect(['suggested', 'failed']).toContain(row.qo_pe_status);
        if (row.qo_pe_status === 'suggested') {
          suggested++;
          expect(Number(row.qo_pe_unit_price)).toBeGreaterThan(0);
        } else {
          failed++;
          const snapshot =
            typeof row.qo_pe_snapshot === 'string'
              ? JSON.parse(row.qo_pe_snapshot)
              : row.qo_pe_snapshot;
          expect(['source_unavailable', 'llm_timeout', 'llm_request_failed']).toContain(
            snapshot.failureCode,
          );
          expect(String(row.qo_pe_override_reason)).toBeTruthy();
          expect(Number(row.qo_pe_unit_price || 0)).toBe(0);
        }
        expect(Number(line.qo_ql_unit_cost || 0), 'suggestion must not adopt a price').toBe(0);
      }
      expect(Number(r.resultData.suggestedCount)).toBe(suggested);
      expect(suggested + failed).toBe(missing.length);
      test.info().annotations.push({
        type: 'provider-outcome',
        description: JSON.stringify({
          suggested,
          failed,
          authority:
            suggested === 0
              ? 'unavailable-provider boundary only'
              : 'persisted suggestions; provider quality not assessed',
        }),
      });
    } finally {
      await context.close();
    }
  });

  test('QO-09 batch source prices', async ({ browser }) => {
    const { context, page } = await openQuoteRolePage(browser, users['sales']);
    try {
      const r = await post(page, 'qo_quote_common:batch_source_prices', {}, 'update', quoteId);
      test.info().annotations.push({
        type: 'note',
        description: `QO-09 status=${r.status} body=${JSON.stringify(r.body?.data).slice(0, 160)}`,
      });
      expect(r.resultData.quoteId).toBe(quoteId);
      expect(r.resultData.processedCount).toBe(await countQuoteLines(page, quoteId));
      expect(r.resultData.processedCount).toBeGreaterThan(0);
      expect(r.resultData.lines).toHaveLength(r.resultData.processedCount);
      expect(r.resultData.failedCount).toBe(0);
      expect(r.resultData.exceptionCount).toBe(0);
    } finally {
      await context.close();
    }
  });
});
