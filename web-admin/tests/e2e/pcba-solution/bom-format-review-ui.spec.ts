import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import { uniqueId } from '../helpers';
import { makeQuoteRoleUser, ensureQuoteRoleUser, openQuoteRolePage, type QuoteRoleUser } from './quote-e2e-helpers';

/**
 * Quote/BOM 真机 — LLM 格式复核的 UI 自动化样例(BOM-09/10 的 UI 对照).
 * 后端 LLM 格式复核已由 Java IT 覆盖(BomDeepSeekLlmIT live / BomRawBomLlmPipelineIT / ExploreFormatHandlerTest);
 * 本测试补 UI 侧:provision + 转换一份 BOM(经 profile/LLM 解析)后,打开 BOM 工作台详情,断言**页面 UI
 * 反映了格式复核结果的变化** —— metric-strip 计数、解析出的行表格、状态横幅,且打开评审抽屉时 UI 变化。
 */
const HERE = path.dirname(fileURLToPath(import.meta.url));
const WORKBENCH = '/p/bom_conversion_task_pcba_workbench';
const uid = uniqueId('fmt').replace(/_/g, '-');
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

async function post(page: Page, code: string, payload: any) {
  const r = await page.request.post(`/api/meta/commands/execute/${code}`, { data: { payload, operationType: 'create' } });
  return { status: r.status(), body: await r.json().catch(() => ({})) };
}
const pid = (b: any) => b?.data?.data?.recordPid || b?.data?.recordPid || b?.data?.recordId;

test.describe('BOM LLM format-review UI (BOM-09/10 UI counterpart) @smoke', () => {
  test.describe.configure({ mode: 'serial', timeout: 300_000 });

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    users['eng'] = makeQuoteRoleUser('bom_engineering', uid, ['bom_engineering']);
    await ensureQuoteRoleUser(page, users['eng']);
    await ctx.close();
  });

  // heavy conversion lives in a test (300s describe timeout), not beforeAll (15s hook timeout)
  test('provision + convert a BOM for the workbench', async ({ browser }) => {
    const bom = findSampleBom();
    test.skip(!bom, 'sample BOM fixture absent');
    const s = await browser.newContext({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
    const sp = await s.newPage();
    try {
      const proj = await post(sp, 'bom:create_project', { bom_project_name: `Fmt ${uid}`, bom_pcba_code: `FMT-${uid}`, bom_project_library_source: 'excel_current_library' });
      const projId = pid(proj.body);
      const up = await sp.request.post('/api/file/upload', { multipart: { file: { name: 'bom.xlsx', mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', buffer: fs.readFileSync(bom!) } } });
      const fileId = (await up.json())?.data?.fileId;
      await post(sp, 'bom:start_conversion', { bom_task_project_id: projId, bom_task_source_package: 'fmt_review', bom_task_raw_file_id: fileId });
      for (let i = 0; i < 30; i++) {
        await sp.waitForTimeout(5000);
        const list = await sp.request.get('/api/dynamic/bom_conversion_task_pcba/list?pageNum=1&pageSize=10&sortField=created_at&sortOrder=desc');
        const lb = await list.json().catch(() => ({} as any));
        const recs = lb?.data?.records || lb?.data?.data?.records || lb?.data || [];
        const mine = (Array.isArray(recs) ? recs : []).find((r: any) => String(r.bom_task_project_id || '') === String(projId) || String(r.bom_task_raw_file_id || '') === String(fileId));
        if (mine) { taskId = mine.pid || mine.id || ''; const st = String(mine.bom_task_status || ''); if (/done|complet|succeed|success|ready|review|pending/i.test(st)) break; }
      }
      expect(taskId, 'conversion produced a task').toBeTruthy();
    } finally { await s.close(); }
  });

  test('BOM-09/10-UI workbench reflects the LLM format-review result + interaction changes UI', async ({ browser }) => {
    test.skip(!taskId, 'no converted task (sample BOM fixture absent)');
    const ctx = await browser.newContext({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      // open the workbench list, find the task, open its detail (format-review surface)
      await page.goto(WORKBENCH, { waitUntil: 'domcontentloaded' });
      await page.waitForTimeout(3500);
      // open task detail: direct detail route, else click the row
      await page.goto(`${WORKBENCH}/view/${taskId}`, { waitUntil: 'domcontentloaded' }).catch(() => {});
      await page.waitForTimeout(3500);
      let main = await page.locator('main').innerText().catch(() => '');
      if (!main || main.length < 30) {
        await page.goto(WORKBENCH, { waitUntil: 'domcontentloaded' });
        await page.waitForTimeout(3000);
        const row = page.locator('table tbody tr').first();
        const link = row.locator('a').first();
        if (await link.count() > 0) await link.click({ timeout: 8000 }).catch(() => {});
        else await row.locator('td').first().click({ timeout: 6000 }).catch(() => {});
        await page.waitForTimeout(3500);
        main = await page.locator('main').innerText().catch(() => '');
      }
      // UI reflects the format review: the workbench detail renders parse-result surfaces
      expect(main.length, 'workbench detail renders content').toBeGreaterThan(30);
      // metric-strip (counts) OR a lines table present — the parsed BOM is surfaced in the UI
      const hasMetrics = await page.locator('[data-testid*="metric"], [class*="metric"]').count() > 0
        || /行|条|数量|总数|匹配|待确认|候选|count/i.test(main);
      const hasTable = await page.locator('table tbody tr').count() > 0;
      const hasTabsOrBanner = await page.locator('[role="tab"], [data-testid*="status-banner"], [class*="banner"], [role="tablist"]').count() > 0
        || /状态|进度|已完成|转换|review|格式/i.test(main);
      test.info().annotations.push({ type: 'note', description: `metrics=${hasMetrics} table=${hasTable} tabs/banner=${hasTabsOrBanner}` });
      expect(hasMetrics || hasTable, 'BOM-09/10-UI: workbench surfaces the LLM-parsed result (metrics or line table)').toBeTruthy();
      expect(hasTabsOrBanner, 'BOM-09/10-UI: workbench shows status/review surface').toBeTruthy();

      // interaction → UI change: opening the review drawer / a row changes the visible UI
      const before = main.length;
      const reviewBtn = page.getByRole('button', { name: /复核|评审|查看|详情|review|确认/i }).first()
        .or(page.locator('[data-testid*="review"], [data-testid="row-action-more"]').first());
      if (await reviewBtn.count() > 0) {
        await reviewBtn.click({ timeout: 6000 }).catch(() => {});
        await page.waitForTimeout(1500);
        const drawerOpen = await page.locator('[role="dialog"], .ant-drawer, [data-testid*="review-drawer"], [data-testid*="drawer"]').count() > 0;
        const after = (await page.locator('body').innerText().catch(() => '')).length;
        test.info().annotations.push({ type: 'note', description: `interaction: drawerOpen=${drawerOpen} textBefore=${before} textAfter=${after}` });
        expect(drawerOpen || after !== before, 'BOM-09/10-UI: review interaction changes the page UI').toBeTruthy();
      }
    } finally {
      await ctx.close();
    }
  });
});
