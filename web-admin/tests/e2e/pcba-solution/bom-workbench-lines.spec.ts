import * as XLSX from 'xlsx';
import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import { uniqueId } from '../helpers';
import {
  cleanupRows,
  makeQuoteRoleUser,
  ensureQuoteRoleUser,
  openQuoteRolePage,
  seedBomWorkbench,
  type BomWorkbenchSeed,
  type QuoteRoleUser,
} from './quote-e2e-helpers';

/**
 * Quote/BOM 真机 深度 — 工作台候选行 确认/撤销/排除 (BOM-05 / BOM-06 / BOM-07).
 * Seeds a deterministic completed task, then drives candidate confirm (bom:confirm_candidate) + undo (bom:undo_decision) on a
 * real standard line and asserts the line's decision state changes (bom_std_manual_confirmed), and that
 * an excluded line is absent from the regenerated export.
 */
const uid = uniqueId('wbl').replace(/_/g, '-');
const users: Record<string, QuoteRoleUser> = {};
let taskId = '';
let created: BomWorkbenchSeed | undefined;

async function post(page: Page, code: string, payload: any, op = 'create', target?: string) {
  const data: any = { payload, operationType: op };
  if (target) data.targetRecordPid = target;
  const r = await page.context().request.post(`/api/meta/commands/execute/${code}`, {
    data,
    timeout: 150_000,
  });
  return { status: r.status(), body: await r.json().catch(() => ({})) };
}
async function listLines(page: Page): Promise<any[]> {
  // server-side {field:value} filters are rejected (code 40000); fetch a recent page + filter client-side
  const r = await page.context().request.get(
    `/api/dynamic/bom_standard_line_pcba/list?pageNum=1&pageSize=500&sortField=created_at&sortOrder=desc`,
  );
  const b = await r.json().catch(() => ({}) as any);
  const recs = b?.data?.records || b?.data?.data?.records || b?.data || [];
  return (Array.isArray(recs) ? recs : []).filter(
    (l: any) => String(l.bom_std_task_id || '') === String(taskId),
  );
}

function isConfirmedWithCode(line: any, candidateCode: string): boolean {
  return (
    line?.bom_std_manual_confirmed === true ||
    line?.bom_std_manual_confirmed === 'true' ||
    String(line?.bom_std_material_code || '').includes(candidateCode)
  );
}

test.describe('BOM workbench candidate confirm/undo/exclude (BOM-05/06/07) @smoke', () => {
  test.describe.configure({ mode: 'serial', timeout: 300_000 });

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
    });
    const page = await ctx.newPage();
    users['eng'] = makeQuoteRoleUser('bom_engineering', uid, ['bom_engineering']);
    await ensureQuoteRoleUser(page, users['eng']);
    await ctx.close();
  });

  test.afterAll(async ({ browser }) => {
    if (!created) return;
    const context = await browser.newContext({
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
    });
    const page = await context.newPage();
    try {
      await cleanupRows(page, created);
    } finally {
      await context.close();
    }
  });

  test('provision a deterministic completed task (standard lines ready)', async ({ browser }) => {
    const adminContext = await browser.newContext({
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
    });
    const adminPage = await adminContext.newPage();
    try {
      created = await seedBomWorkbench(adminPage, { ownerEmail: users['eng'].email });
      taskId = created.taskId;
    } finally {
      await adminContext.close();
    }
    const role = await openQuoteRolePage(browser, users['eng']);
    try {
      expect(taskId, 'seed produced a completed task').toBeTruthy();
      expect((await listLines(role.page)).length, 'seed produced standard lines').toBeGreaterThan(
        0,
      );
    } finally {
      await role.context.close();
    }
  });

  test('BOM-05/06 confirm candidate then undo — decision state changes', async ({ browser }) => {
    expect(
      taskId,
      'previous conversion test must produce a task; fixture/setup failures must fail fast',
    ).toBeTruthy();
    const { context, page } = await openQuoteRolePage(browser, users['eng']);
    try {
      const lines = await listLines(page);
      expect(lines.length, 'standard lines exist after conversion').toBeGreaterThan(0);
      const line =
        lines.find(
          (candidate) =>
            String(candidate.bom_std_reason_code || '') === 'match_multi_candidate' ||
            String(candidate.bom_std_candidate_codes || '').length > 0,
        ) || lines[0];
      const lineId = line.pid;
      const candidateCode = created?.candidateCode || `MTEST-${uid}`.slice(0, 24);
      // BOM-05: confirm a candidate material code → manual_confirmed becomes true + material code set
      const conf = await post(
        page,
        'bom:confirm_candidate',
        { lineId, candidateCode },
        'update',
        lineId,
      );
      test.info().annotations.push({
        type: 'note',
        description: `BOM-05 confirm status=${conf.status} body=${JSON.stringify(conf.body?.data).slice(0, 120)}`,
      });
      expect(conf.status, `BOM-05: confirm_candidate executed (status=${conf.status})`).toBe(200);
      let afterConfirm: any = {};
      await expect
        .poll(
          async () => {
            afterConfirm = (await listLines(page)).find((l) => l.pid === lineId) || {};
            return isConfirmedWithCode(afterConfirm, candidateCode);
          },
          { timeout: 10_000, intervals: [250, 500, 1_000] },
        )
        .toBeTruthy();
      // confirmed state: manual_confirmed true OR material code now set to our candidate
      const confirmedNow = isConfirmedWithCode(afterConfirm, candidateCode);
      expect(
        confirmedNow,
        `BOM-05: line shows confirmed decision (manual_confirmed=${afterConfirm.bom_std_manual_confirmed} code=${afterConfirm.bom_std_material_code})`,
      ).toBeTruthy();
      // BOM-06: undo the decision → confirmed state reverts
      const undo = await post(page, 'bom:undo_decision', { lineId }, 'update', lineId);
      test
        .info()
        .annotations.push({ type: 'note', description: `BOM-06 undo status=${undo.status}` });
      expect(
        undo.status,
        `BOM-06: undo_decision executed (status=${undo.status}, body=${JSON.stringify(undo.body).slice(0, 600)})`,
      ).toBe(200);
      let afterUndo: any = {};
      await expect
        .poll(
          async () => {
            afterUndo = (await listLines(page)).find((l) => l.pid === lineId) || {};
            return (
              afterUndo.bom_std_manual_confirmed !== afterConfirm.bom_std_manual_confirmed ||
              String(afterUndo.bom_std_material_code || '') !==
                String(afterConfirm.bom_std_material_code || '') ||
              String(afterUndo.bom_std_change_type || '') !==
                String(afterConfirm.bom_std_change_type || '')
            );
          },
          { timeout: 10_000, intervals: [250, 500, 1_000] },
        )
        .toBeTruthy();
      const revertedOrChanged =
        afterUndo.bom_std_manual_confirmed !== afterConfirm.bom_std_manual_confirmed ||
        String(afterUndo.bom_std_material_code || '') !==
          String(afterConfirm.bom_std_material_code || '') ||
        String(afterUndo.bom_std_change_type || '') !==
          String(afterConfirm.bom_std_change_type || '');
      expect(
        revertedOrChanged,
        `BOM-06: decision state changed after undo (confirmed ${afterConfirm.bom_std_manual_confirmed}→${afterUndo.bom_std_manual_confirmed})`,
      ).toBeTruthy();
    } finally {
      await context.close();
    }
  });

  test('BOM-07 export reflects line set (excluded lines absent / count consistent)', async ({
    browser,
  }) => {
    expect(
      taskId,
      'previous conversion test must produce a task; fixture/setup failures must fail fast',
    ).toBeTruthy();
    const { context, page } = await openQuoteRolePage(browser, users['eng']);
    try {
      expect(created?.directLineId, 'fixture exposes the direct-copy line to exclude').toBeTruthy();
      const exclusion = await post(
        page,
        'bom:set_standard_line_exclusion',
        {
          lineId: created!.directLineId,
          status: 'confirmed_excluded',
          reason: 'E2E verifies confirmed exclusion is omitted from export',
          operator: users['eng'].email,
        },
        'update',
        created!.directLineId,
      );
      expect(
        exclusion.status,
        `BOM-07: exclusion state transition accepted (body=${JSON.stringify(exclusion.body).slice(0, 600)})`,
      ).toBe(200);

      let lines: any[] = [];
      await expect
        .poll(
          async () => {
            lines = await listLines(page);
            return lines.find((line) => line.pid === created!.directLineId)
              ?.bom_std_exclusion_status;
          },
          { timeout: 10_000, intervals: [250, 500, 1_000] },
        )
        .toBe('confirmed_excluded');
      const included = lines.filter(
        (l) => !['confirmed_excluded', 'deleted'].includes(String(l.bom_std_exclusion_status)),
      );
      const excluded = lines.filter((l) =>
        ['confirmed_excluded', 'deleted'].includes(String(l.bom_std_exclusion_status)),
      );
      test.info().annotations.push({
        type: 'note',
        description: `BOM-07 total=${lines.length} included=${included.length} excluded=${excluded.length}`,
      });
      // regenerate the export and confirm it is produced (content columns verified in convert-export spec)
      const exp = await post(
        page,
        'bom:regenerate_export',
        { sourceRecordId: taskId },
        'update',
        taskId,
      );
      expect(exp.status, 'BOM-07: regenerate_export accepted').toBe(200);
      const result = exp.body?.data || {};
      const exportFileId =
        result?.data?.exportFileId ||
        result.exportFileId ||
        result.fileId ||
        result.bom_task_export_file_id ||
        result.export_file_id ||
        (typeof result === 'object' &&
          JSON.stringify(result).match(
            /"(?:export[_A-Za-z]*[fF]ile[_A-Za-z]*[iI]d)":"([^"]+)"/,
          )?.[1]) ||
        '';
      expect(exportFileId, 'BOM-07: regenerate response exposes export file id').toBeTruthy();
      const download = await page.context().request.get(`/api/file/download/${exportFileId}`);
      expect(download.status(), 'BOM-07: regenerated export is downloadable').toBe(200);
      const workbook = XLSX.read(await download.body(), { type: 'buffer' });
      const bomText = XLSX.utils
        .sheet_to_json(workbook.Sheets.BOM, { header: 1 })
        .map((row: any) => (row || []).join('|'))
        .join('\n');

      expect(included.length, 'BOM-07: at least one line is export-included').toBeGreaterThan(0);
      expect(excluded.length, 'BOM-07: fixture has one confirmed-excluded line').toBe(1);
      expect(bomText, 'BOM-07: active resistor remains in the exported BOM').toContain(
        'RC0603FR-0710KL',
      );
      expect(bomText, 'BOM-07: confirmed-excluded MCU is absent from the exported BOM').not.toContain(
        'STM32F103C8T6',
      );
    } finally {
      await context.close();
    }
  });
});
