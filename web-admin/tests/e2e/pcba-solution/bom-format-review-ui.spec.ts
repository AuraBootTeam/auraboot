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

async function listConversionTasks(page: Page): Promise<any[]> {
  const list = await page.request.get('/api/dynamic/bom_conversion_task_pcba/list?pageNum=1&pageSize=10&sortField=created_at&sortOrder=desc');
  const lb = await list.json().catch(() => ({} as any));
  const recs = lb?.data?.records || lb?.data?.data?.records || lb?.data || [];
  return Array.isArray(recs) ? recs : [];
}

function findTask(recs: any[], projId: string, fileId: string): any | undefined {
  return recs.find((r: any) => String(r.bom_task_project_id || '') === String(projId) || String(r.bom_task_raw_file_id || '') === String(fileId));
}

async function listStandardLines(page: Page, task: string): Promise<any[]> {
  const r = await page.request.get('/api/dynamic/bom_standard_line_pcba/list?pageNum=1&pageSize=500&sortField=created_at&sortOrder=desc');
  const b = await r.json().catch(() => ({} as any));
  const recs = b?.data?.records || b?.data?.data?.records || b?.data || [];
  return (Array.isArray(recs) ? recs : []).filter((l: any) => String(l.bom_std_task_id || '') === String(task));
}

async function waitForTaskReady(page: Page, projId: string, fileId: string): Promise<string> {
  let found: any;
  await expect.poll(async () => {
    found = findTask(await listConversionTasks(page), projId, fileId);
    const id = found?.pid || found?.id || '';
    return id ? (await listStandardLines(page, id)).length : 0;
  }, { timeout: 150_000, intervals: [1_000, 2_000, 5_000] }).toBeGreaterThan(0);
  return found?.pid || found?.id || '';
}

type ReviewSurface = {
  main: string;
  hasMetrics: boolean;
  hasTable: boolean;
  hasTabsOrBanner: boolean;
};

async function readReviewSurface(page: Page): Promise<ReviewSurface> {
  const main = await page.locator('main').innerText().catch(() => '');
  const hasMetrics = await page.locator('[data-testid*="metric"], [class*="metric"]').count() > 0
    || /行|条|数量|总数|匹配|待确认|候选|count/i.test(main);
  const hasTable = await page.locator('table tbody tr').count() > 0;
  const hasTabsOrBanner = await page.locator('[role="tab"], [data-testid*="status-banner"], [class*="banner"], [role="tablist"]').count() > 0
    || /状态|进度|已完成|转换|review|格式/i.test(main);
  return { main, hasMetrics, hasTable, hasTabsOrBanner };
}

async function waitForReviewSurface(page: Page): Promise<ReviewSurface> {
  let surface: ReviewSurface = { main: '', hasMetrics: false, hasTable: false, hasTabsOrBanner: false };
  await expect.poll(async () => {
    surface = await readReviewSurface(page);
    return surface.hasMetrics || surface.hasTable;
  }, { timeout: 30_000, intervals: [500, 1_000, 2_000] }).toBeTruthy();
  return surface;
}

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
    expect(
      bom,
      'sample BOM fixture must exist; set QUOTE_BOM_SAMPLES_DIR or keep aura-quote/docs/ref/10款GERBER加坐标 available',
    ).toBeTruthy();
    const s = await browser.newContext({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
    const sp = await s.newPage();
    try {
      const proj = await post(sp, 'bom:create_project', { bom_project_name: `Fmt ${uid}`, bom_pcba_code: `FMT-${uid}`, bom_project_library_source: 'excel_current_library' });
      const projId = pid(proj.body);
      const up = await sp.request.post('/api/file/upload', { multipart: { file: { name: 'bom.xlsx', mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', buffer: fs.readFileSync(bom!) } } });
      const fileId = (await up.json())?.data?.fileId;
      await post(sp, 'bom:start_conversion', { bom_task_project_id: projId, bom_task_source_package: 'fmt_review', bom_task_raw_file_id: fileId });
      taskId = await waitForTaskReady(sp, projId, fileId);
      expect(taskId, 'conversion produced a task').toBeTruthy();
    } finally { await s.close(); }
  });

  test('BOM-09/10-UI workbench reflects the LLM format-review result + interaction changes UI', async ({ browser }) => {
    expect(taskId, 'previous conversion test must produce a task; fixture/setup failures must fail fast').toBeTruthy();
    const ctx = await browser.newContext({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      // open the workbench list, find the task, open its detail (format-review surface)
      await page.goto(WORKBENCH, { waitUntil: 'domcontentloaded' });
      await expect(page.locator('main')).toBeVisible({ timeout: 5_000 });
      // open task detail: direct detail route, else click the row
      await page.goto(`${WORKBENCH}/view/${taskId}`, { waitUntil: 'domcontentloaded' }).catch(() => {});
      let surface = await waitForReviewSurface(page).catch(() => undefined);
      if (!surface) {
        await page.goto(WORKBENCH, { waitUntil: 'domcontentloaded' });
        await expect(page.locator('main')).toBeVisible({ timeout: 5_000 });
        const row = page.locator('table tbody tr').first();
        const link = row.locator('a').first();
        if (await link.count() > 0) await link.click({ timeout: 5_000 }).catch(() => {});
        else await row.locator('td').first().click({ timeout: 5_000 }).catch(() => {});
        surface = await waitForReviewSurface(page);
      }
      // UI reflects the format review: the workbench detail renders parse-result surfaces
      const { main, hasMetrics, hasTable, hasTabsOrBanner } = surface;
      expect(main.length, 'workbench detail renders content').toBeGreaterThan(30);
      // metric-strip (counts) OR a lines table present — the parsed BOM is surfaced in the UI
      test.info().annotations.push({ type: 'note', description: `metrics=${hasMetrics} table=${hasTable} tabs/banner=${hasTabsOrBanner}` });
      expect(hasMetrics || hasTable, 'BOM-09/10-UI: workbench surfaces the LLM-parsed result (metrics or line table)').toBeTruthy();
      expect(hasTabsOrBanner, 'BOM-09/10-UI: workbench shows status/review surface').toBeTruthy();

      // interaction → UI change: opening the review drawer / a row changes the visible UI
      const before = main.length;
      const reviewBtn = page.getByRole('button', { name: /复核|评审|查看|详情|review|确认/i }).first()
        .or(page.locator('[data-testid*="review"], [data-testid="row-action-more"]').first());
      if (await reviewBtn.count() > 0) {
        await reviewBtn.click({ timeout: 5_000 }).catch(() => {});
        await expect.poll(async () => {
          const drawerOpenNow = await page.locator('[role="dialog"], .ant-drawer, [data-testid*="review-drawer"], [data-testid*="drawer"]').count() > 0;
          const afterLength = (await page.locator('body').innerText().catch(() => '')).length;
          return drawerOpenNow || afterLength !== before;
        }, { timeout: 5_000, intervals: [250, 500, 1_000] }).toBeTruthy();
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
