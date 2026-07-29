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
 * Quote/BOM 真机 深度 L3 — 已完成 BOM + 工作台渲染 + 导出内容细粒度 (BOM-05 / BOM-08 / XLS-B).
 * Seeds a deterministic completed conversion task, verifies the workbench renders candidate lines,
 * regenerates the export as the engineering role and parses
 * the downloaded xlsx to assert the standard-code / match-status columns + row content.
 * Real parser/LLM conversion quality is owned by the BOM golden dataset and backend integration gates.
 */
const WORKBENCH = '/p/bom_conversion_task_pcba_workbench';
const uid = uniqueId('bd').replace(/_/g, '-');
const users: Record<string, QuoteRoleUser> = {};

async function post(page: Page, code: string, payload: any, op = 'create', target?: string) {
  const data: any = { payload, operationType: op };
  if (target) data.targetRecordPid = target;
  const r = await page.request.post(`/api/meta/commands/execute/${code}`, { data });
  return { status: r.status(), body: await r.json().catch(() => ({})) };
}

async function countStandardLines(page: Page, taskId: string): Promise<number> {
  const r = await page.request.get(
    '/api/dynamic/bom_standard_line_pcba/list?pageNum=1&pageSize=500&sortField=created_at&sortOrder=desc',
  );
  const b = await r.json().catch(() => ({}) as any);
  const recs = b?.data?.records || b?.data?.data?.records || b?.data || [];
  return (Array.isArray(recs) ? recs : []).filter(
    (line: any) => String(line.bom_std_task_id || '') === String(taskId),
  ).length;
}

test.describe('BOM workbench + export deep (BOM-05/08 + XLS-B) @smoke', () => {
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

  test('BOM-05/08/XLS-B completed task → workbench → export content', async ({ browser }) => {
    const adminContext = await browser.newContext({
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
    });
    const adminPage = await adminContext.newPage();
    let created: BomWorkbenchSeed | undefined;
    created = await seedBomWorkbench(adminPage, { ownerEmail: users['eng'].email });
    const { context, page } = await openQuoteRolePage(browser, users['eng']);
    try {
      const taskId = created.taskId;
      expect(taskId, 'seeded completed task exists').toBeTruthy();
      expect(await countStandardLines(page, taskId)).toBeGreaterThan(0);
      test.info().annotations.push({
        type: 'note',
        description: `deterministic completed taskId=${taskId}`,
      });

      // BOM-05: workbench renders the task / candidate lines
      await page.goto(WORKBENCH, { waitUntil: 'domcontentloaded' });
      let mainText = '';
      await expect
        .poll(
          async () => {
            mainText = await page
              .locator('main')
              .innerText()
              .catch(() => '');
            return mainText.length;
          },
          { timeout: 10_000, intervals: [250, 500, 1_000] },
        )
        .toBeGreaterThan(0);
      expect(mainText.length, 'BOM-05: workbench renders content').toBeGreaterThan(0);

      // BOM-08 / XLS-B: regenerate export → download → parse xlsx columns
      const exp = await post(
        page,
        'bom:regenerate_export',
        { sourceRecordId: taskId },
        'update',
        taskId,
      );
      // export fileId may surface under various keys
      const eb = exp.body?.data || {};
      const exportFileId =
        eb?.data?.exportFileId ||
        eb.exportFileId ||
        eb.fileId ||
        eb.bom_task_export_file_id ||
        eb.export_file_id ||
        (typeof eb === 'object' &&
          JSON.stringify(eb).match(
            /"(?:export[_A-Za-z]*[fF]ile[_A-Za-z]*[iI]d)":"([^"]+)"/,
          )?.[1]) ||
        '';
      test.info().annotations.push({
        type: 'note',
        description: `regenerate_export status=${exp.status} body=${JSON.stringify(eb).slice(0, 300)}`,
      });
      expect(exp.status, 'regenerate_export accepted').toBe(200);
      expect(
        exportFileId,
        'XLS-B: command response contains a downloadable export file id',
      ).toBeTruthy();
      const dl = await page.request.get(`/api/file/download/${exportFileId}`);
      expect(dl.status(), 'export downloadable').toBe(200);
      const wb = XLSX.read(await dl.body(), { type: 'buffer' });
      expect(wb.SheetNames, 'XLS-B: standard workbook has all contract sheets').toEqual([
        'BOM',
        '变更记录',
        '转换明细',
      ]);
      const sheet = wb.Sheets.BOM;
      const rows = XLSX.utils.sheet_to_json(sheet, { header: 1 }) as any[][];
      expect(rows.length, 'XLS-B: export has data rows').toBeGreaterThan(4);
      // the export has a company banner/title row first; scan the first ~8 rows for the header row
      const head = rows
        .slice(0, 8)
        .map((r) => (r || []).join('|'))
        .join(' || ');
      const allText = wb.SheetNames.map((name) =>
        XLSX.utils
          .sheet_to_json(wb.Sheets[name], { header: 1 })
          .map((row: any) => (row || []).join('|'))
          .join('\n'),
      ).join('\n');
      // XLS-B: standard-code / match-status / material columns present (chinese headers)
      expect(
        /标准|编码|匹配|状态|原因|物料|料号|规格|Material|Code|Status/i.test(head),
        `XLS-B: export has expected columns in first rows (got: ${head.slice(0, 160)})`,
      ).toBeTruthy();
      // no raw field codes leaked anywhere in the workbook
      expect(
        /\bbom_[a-z_]{3,}\b/.test(allText),
        'XLS-B: no raw bom_* field code leaked in export',
      ).toBeFalsy();
    } finally {
      await context.close();
      if (created) await cleanupRows(adminPage, created);
      await adminContext.close();
    }
  });
});
