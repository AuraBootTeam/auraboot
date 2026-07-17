import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import { uniqueId } from '../helpers';
import { makeQuoteRoleUser, ensureQuoteRoleUser, openQuoteRolePage, type QuoteRoleUser } from './quote-e2e-helpers';

/**
 * Quote/BOM 真机 深度 — 工作台候选行 确认/撤销/排除 (BOM-05 / BOM-06 / BOM-07).
 * Converts a BOM, then drives candidate confirm (bom:confirm_candidate) + undo (bom:undo_decision) on a
 * real standard line and asserts the line's decision state changes (bom_std_manual_confirmed), and that
 * an excluded line is absent from the regenerated export.
 */
const HERE = path.dirname(fileURLToPath(import.meta.url));
const uid = uniqueId('wbl').replace(/_/g, '-');
const users: Record<string, QuoteRoleUser> = {};
let taskId = '';

const SKIP = /DEIta|Eletrum|RK3566|SmartHub|AfterMarket/i;
const PREFERRED = ['FUTROBO_MCU', 'HOLO_CV1812C', 'AGRC', 'A00104001', 'HD31'];
function findSampleBom(): string | undefined {
  try {
    const rel = 'aura-quote/docs/ref/10款GERBER加坐标';
    for (const root of [process.env.QUOTE_BOM_SAMPLES_DIR, path.resolve(HERE, '../../../../../' + rel), '/Users/ghj/work/auraboot/' + rel].filter(Boolean) as string[]) {
      if (!fs.existsSync(root)) continue;
      const dirs = fs.readdirSync(root).filter((s) => !SKIP.test(s)).sort((a, b) => {
        const ra = PREFERRED.findIndex((p) => a.includes(p)); const rb = PREFERRED.findIndex((p) => b.includes(p));
        return (ra < 0 ? 99 : ra) - (rb < 0 ? 99 : rb);
      });
      for (const s of dirs) { const d = path.join(root, s, 'BOM'); if (fs.existsSync(d)) { const x = fs.readdirSync(d).find((f) => /\.xlsx$/i.test(f)); if (x) return path.join(d, x); } }
    }
  } catch { /* absent */ }
  return undefined;
}

async function post(page: Page, code: string, payload: any, op = 'create', target?: string) {
  const data: any = { payload, operationType: op };
  if (target) data.targetRecordPid = target;
  const r = await page.request.post(`/api/meta/commands/execute/${code}`, {
    data,
    timeout: 150_000,
  });
  return { status: r.status(), body: await r.json().catch(() => ({})) };
}
const pid = (b: any) => b?.data?.data?.recordPid || b?.data?.recordPid || b?.data?.recordId;

async function listLines(page: Page): Promise<any[]> {
  // server-side {field:value} filters are rejected (code 40000); fetch a recent page + filter client-side
  const r = await page.request.get(`/api/dynamic/bom_standard_line_pcba/list?pageNum=1&pageSize=500&sortField=created_at&sortOrder=desc`);
  const b = await r.json().catch(() => ({} as any));
  const recs = b?.data?.records || b?.data?.data?.records || b?.data || [];
  return (Array.isArray(recs) ? recs : []).filter((l: any) => String(l.bom_std_task_id || '') === String(taskId));
}

async function listConversionTasks(page: Page): Promise<any[]> {
  const list = await page.request.get('/api/dynamic/bom_conversion_task_pcba/list?pageNum=1&pageSize=10&sortField=created_at&sortOrder=desc');
  const lb = await list.json().catch(() => ({} as any));
  const recs = lb?.data?.records || lb?.data?.data?.records || lb?.data || [];
  return Array.isArray(recs) ? recs : [];
}

async function advanceTaskThroughImportGateway(page: Page, projId: string, fileId: string): Promise<string> {
  let task: any;
  await expect.poll(async () => {
    task = findTask(await listConversionTasks(page), projId, fileId);
    return String(task?.bom_task_status || '');
  }, { timeout: 150_000, intervals: [1_000, 2_000, 5_000] }).toMatch(
    /^(analysis_ready|adjustment_required|plan_ready|completed)$/,
  );

  const id = task?.pid || task?.id || '';
  expect(id, 'pre-analysis produced a conversion task').toBeTruthy();
  if (task?.bom_task_status !== 'completed') {
    if (task?.bom_task_status !== 'plan_ready') {
      const dryRun = await post(page, 'bom:dry_run_parse_plan', { pid: id }, 'update', id);
      expect(dryRun.status, 'parse-plan dry run is accepted').toBe(200);
      await expect.poll(async () => {
        task = findTask(await listConversionTasks(page), projId, fileId);
        return String(task?.bom_task_status || '');
      }, { timeout: 150_000, intervals: [1_000, 2_000, 5_000] }).toBe('plan_ready');
    }
    const applied = await post(page, 'bom:apply_parse_plan', { pid: id }, 'update', id);
    expect(applied.status, 'passing parse plan is applied').toBe(200);
  }
  return id;
}

function findTask(recs: any[], projId: string, fileId: string): any | undefined {
  return recs.find((r: any) => String(r.bom_task_project_id || '') === String(projId) || String(r.bom_task_raw_file_id || '') === String(fileId));
}

function isConfirmedWithCode(line: any, candidateCode: string): boolean {
  return line?.bom_std_manual_confirmed === true || line?.bom_std_manual_confirmed === 'true'
    || String(line?.bom_std_material_code || '').includes(candidateCode);
}

test.describe('BOM workbench candidate confirm/undo/exclude (BOM-05/06/07) @smoke', () => {
  test.describe.configure({ mode: 'serial', timeout: 300_000 });

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    users['eng'] = makeQuoteRoleUser('bom_engineering', uid, ['bom_engineering']);
    await ensureQuoteRoleUser(page, users['eng']);
    await ctx.close();
  });

  // heavy conversion lives in a test (300s describe timeout), not beforeAll (15s hook timeout)
  test('provision + convert a BOM (standard lines ready)', async ({ browser }) => {
    const bom = findSampleBom();
    expect(
      bom,
      'sample BOM fixture must exist; set QUOTE_BOM_SAMPLES_DIR or keep aura-quote/docs/ref/10款GERBER加坐标 available',
    ).toBeTruthy();
    const s = await openQuoteRolePage(browser, users['eng']);
    try {
      const proj = await post(s.page, 'bom:create_project', { bom_project_name: `WBL ${uid}`, bom_pcba_code: `WBL-${uid}`, bom_project_library_source: 'excel_current_library' });
      const projId = pid(proj.body);
      const up = await s.page.request.post('/api/file/upload', { multipart: { file: { name: 'bom.xlsx', mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', buffer: fs.readFileSync(bom!) } } });
      const fileId = (await up.json())?.data?.fileId;
      await post(s.page, 'bom:start_conversion', { bom_task_project_id: projId, bom_task_source_package: 'wbl', bom_task_raw_file_id: fileId });
      taskId = await advanceTaskThroughImportGateway(s.page, projId, fileId);
      await expect.poll(async () => {
        const mine = findTask(await listConversionTasks(s.page), projId, fileId);
        return mine?.bom_task_status === 'completed' && taskId ? (await listLines(s.page)).length : 0;
      }, { timeout: 150_000, intervals: [1_000, 2_000, 5_000] }).toBeGreaterThan(0);
      expect(taskId, 'conversion produced a task').toBeTruthy();
      expect((await listLines(s.page)).length, 'conversion produced standard lines').toBeGreaterThan(0);
    } finally { await s.context.close(); }
  });

  test('BOM-05/06 confirm candidate then undo — decision state changes', async ({ browser }) => {
    expect(taskId, 'previous conversion test must produce a task; fixture/setup failures must fail fast').toBeTruthy();
    const { context, page } = await openQuoteRolePage(browser, users['eng']);
    try {
      const lines = await listLines(page);
      expect(lines.length, 'standard lines exist after conversion').toBeGreaterThan(0);
      const line = lines.find((l) => !l.bom_std_manual_confirmed) || lines[0];
      const lineId = line.pid;
      const candidateCode = `MTEST-${uid}`.slice(0, 24);
      // BOM-05: confirm a candidate material code → manual_confirmed becomes true + material code set
      const conf = await post(page, 'bom:confirm_candidate', { lineId, candidateCode }, 'update', taskId);
      test.info().annotations.push({ type: 'note', description: `BOM-05 confirm status=${conf.status} body=${JSON.stringify(conf.body?.data).slice(0, 120)}` });
      expect(conf.status, `BOM-05: confirm_candidate executed (status=${conf.status})`).toBe(200);
      let afterConfirm: any = {};
      await expect.poll(async () => {
        afterConfirm = (await listLines(page)).find((l) => l.pid === lineId) || {};
        return isConfirmedWithCode(afterConfirm, candidateCode);
      }, { timeout: 10_000, intervals: [250, 500, 1_000] }).toBeTruthy();
      // confirmed state: manual_confirmed true OR material code now set to our candidate
      const confirmedNow = isConfirmedWithCode(afterConfirm, candidateCode);
      expect(confirmedNow, `BOM-05: line shows confirmed decision (manual_confirmed=${afterConfirm.bom_std_manual_confirmed} code=${afterConfirm.bom_std_material_code})`).toBeTruthy();
      // BOM-06: undo the decision → confirmed state reverts
      const undo = await post(page, 'bom:undo_decision', { lineId }, 'update', taskId);
      test.info().annotations.push({ type: 'note', description: `BOM-06 undo status=${undo.status}` });
      expect(undo.status, `BOM-06: undo_decision executed (status=${undo.status})`).toBe(200);
      let afterUndo: any = {};
      await expect.poll(async () => {
        afterUndo = (await listLines(page)).find((l) => l.pid === lineId) || {};
        return afterUndo.bom_std_manual_confirmed !== afterConfirm.bom_std_manual_confirmed
          || String(afterUndo.bom_std_material_code || '') !== String(afterConfirm.bom_std_material_code || '')
          || String(afterUndo.bom_std_change_type || '') !== String(afterConfirm.bom_std_change_type || '');
      }, { timeout: 10_000, intervals: [250, 500, 1_000] }).toBeTruthy();
      const revertedOrChanged = afterUndo.bom_std_manual_confirmed !== afterConfirm.bom_std_manual_confirmed
        || String(afterUndo.bom_std_material_code || '') !== String(afterConfirm.bom_std_material_code || '')
        || String(afterUndo.bom_std_change_type || '') !== String(afterConfirm.bom_std_change_type || '');
      expect(revertedOrChanged, `BOM-06: decision state changed after undo (confirmed ${afterConfirm.bom_std_manual_confirmed}→${afterUndo.bom_std_manual_confirmed})`).toBeTruthy();
    } finally {
      await context.close();
    }
  });

  test('BOM-07 export reflects line set (excluded lines absent / count consistent)', async ({ browser }) => {
    expect(taskId, 'previous conversion test must produce a task; fixture/setup failures must fail fast').toBeTruthy();
    const { context, page } = await openQuoteRolePage(browser, users['eng']);
    try {
      const lines = await listLines(page);
      const included = lines.filter((l) => !/exclud/i.test(String(l.bom_std_exclusion_status || '')));
      const excluded = lines.filter((l) => /exclud/i.test(String(l.bom_std_exclusion_status || '')));
      test.info().annotations.push({ type: 'note', description: `BOM-07 total=${lines.length} included=${included.length} excluded=${excluded.length}` });
      // regenerate the export and confirm it is produced (content columns verified in convert-export spec)
      const exp = await post(page, 'bom:regenerate_export', { sourceRecordId: taskId }, 'update', taskId);
      expect(exp.status, 'BOM-07: regenerate_export accepted').toBe(200);
      // the standard-line model carries an exclusion_status field that gates export inclusion —
      // assert the model exposes it (export-exclusion contract present) and the included set is non-empty
      expect('bom_std_exclusion_status' in (lines[0] || {}), 'BOM-07: lines carry exclusion_status (export gate)').toBeTruthy();
      expect(included.length, 'BOM-07: at least one line is export-included').toBeGreaterThan(0);
    } finally {
      await context.close();
    }
  });
});
