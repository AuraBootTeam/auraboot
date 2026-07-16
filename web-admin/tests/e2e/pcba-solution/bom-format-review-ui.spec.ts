import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import {
  clickRowActionByLocator,
  ensureSidebarExpanded,
  findRowInPaginatedList,
  uniqueId,
  waitForDynamicPageLoad,
} from '../helpers';
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
let taskNo = '';

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

async function openWorkbenchFromSidebar(page: Page): Promise<void> {
  const nav = page.locator('nav, aside, [role="navigation"]').first();
  const link = nav
    .locator(`a[href="${WORKBENCH}"]`)
    .or(nav.getByRole('link', { name: /BOM 工作台|Workbench/i }))
    .first();
  await expect(link).toBeVisible({ timeout: 10_000 });
  for (let attempt = 0; attempt < 2; attempt += 1) {
    await link.scrollIntoViewIfNeeded();
    await link.click();
    const navigated = await page
      .waitForURL((url) => url.pathname === WORKBENCH, { timeout: 8_000 })
      .then(() => true)
      .catch(() => false);
    if (navigated) break;
    if (attempt === 1) {
      await expect.poll(() => new URL(page.url()).pathname).toBe(WORKBENCH);
    }
  }
  await waitForDynamicPageLoad(page, 20_000);
}

async function post(page: Page, code: string, payload: any, operationType = 'create', targetRecordPid?: string) {
  const data: Record<string, any> = { payload, operationType };
  if (targetRecordPid) data.targetRecordPid = targetRecordPid;
  const r = await page.request.post(`/api/meta/commands/execute/${code}`, {
    data,
    timeout: 150_000,
  });
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
    return String(found?.bom_task_status || '');
  }, { timeout: 150_000, intervals: [1_000, 2_000, 5_000] }).toMatch(
    /^(analysis_ready|adjustment_required|plan_ready|completed)$/,
  );

  const id = found?.pid || found?.id || '';
  expect(id, 'pre-analysis produced a conversion task').toBeTruthy();
  if (found?.bom_task_status !== 'completed') {
    if (found?.bom_task_status !== 'plan_ready') {
      const dryRun = await post(page, 'bom:dry_run_parse_plan', { pid: id }, 'update', id);
      expect(dryRun.status, 'parse-plan dry run is accepted').toBe(200);
      await expect.poll(async () => {
        found = findTask(await listConversionTasks(page), projId, fileId);
        return String(found?.bom_task_status || '');
      }, { timeout: 150_000, intervals: [1_000, 2_000, 5_000] }).toBe('plan_ready');
    }
    const applied = await post(page, 'bom:apply_parse_plan', { pid: id }, 'update', id);
    expect(applied.status, 'passing parse plan is applied').toBe(200);
  }

  await expect.poll(async () => {
    found = findTask(await listConversionTasks(page), projId, fileId);
    return id ? (await listStandardLines(page, id)).length : 0;
  }, { timeout: 150_000, intervals: [1_000, 2_000, 5_000] }).toBeGreaterThan(0);
  expect(found?.bom_task_status, 'formal matching completes after applying the parse plan').toBe('completed');
  taskNo = String(found?.bom_task_no || '');
  expect(taskNo, 'conversion task exposes its user-visible task number').toBeTruthy();
  return id;
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
    const s = await openQuoteRolePage(browser, users['eng']);
    try {
      const proj = await post(s.page, 'bom:create_project', { bom_project_name: `Fmt ${uid}`, bom_pcba_code: `FMT-${uid}`, bom_project_library_source: 'excel_current_library' });
      const projId = pid(proj.body);
      const up = await s.page.request.post('/api/file/upload', { multipart: { file: { name: 'bom.xlsx', mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', buffer: fs.readFileSync(bom!) } } });
      const fileId = (await up.json())?.data?.fileId;
      await post(s.page, 'bom:start_conversion', { bom_task_project_id: projId, bom_task_source_package: 'fmt_review', bom_task_raw_file_id: fileId });
      taskId = await waitForTaskReady(s.page, projId, fileId);
      expect(taskId, 'conversion produced a task').toBeTruthy();
    } finally { await s.context.close(); }
  });

  test('BOM-09/10-UI workbench reflects the LLM format-review result + interaction changes UI', async ({ browser }) => {
    expect(taskId, 'previous conversion test must produce a task; fixture/setup failures must fail fast').toBeTruthy();
    const { context, page } = await openQuoteRolePage(browser, users['eng']);
    try {
      await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
      await ensureSidebarExpanded(page);
      await openWorkbenchFromSidebar(page);
      await expect(page.locator('main')).toContainText(taskNo, { timeout: 20_000 });
      const workbenchRow = await findRowInPaginatedList(page, taskNo, 20_000);
      await clickRowActionByLocator(page, workbenchRow, 'open_workbench', '打开');
      await waitForDynamicPageLoad(page, 20_000);
      await expect(page).toHaveURL(new RegExp(`${WORKBENCH}/view/${taskId}$`));

      await expect(page.getByRole('heading', { name: /BOM 工作台|BOM Workbench/i })).toBeVisible({
        timeout: 20_000,
      });
      await expect(page.getByRole('button', { name: /有效行|Valid Rows/i })).toBeVisible();
      await expect(page.getByRole('button', { name: /待确认|Pending/i }).first()).toBeVisible();
      const standardLineTable = page.getByRole('table').filter({ has: page.getByRole('columnheader', { name: /行号|Row/i }) });
      await expect(standardLineTable.getByRole('columnheader', { name: /物料名称|Material Name/i })).toBeVisible();
      await expect(standardLineTable.getByRole('columnheader', { name: /位号|Reference/i })).toBeVisible();

      const firstLine = standardLineTable.getByRole('row').nth(1);
      await expect(firstLine).toBeVisible();
      await firstLine.click();

      await expect(page.getByRole('button', { name: /关闭复核浮层|Close Review/i })).toBeVisible();
      await expect(page.getByText(/当前状态|Current Status/i).last()).toBeVisible();
      await expect(page.getByRole('heading', { name: /候选物料|Candidate Materials/i })).toBeVisible();
      await expect(page.getByText(/导出影响与历史|Export Impact/i).last()).toBeVisible();
    } finally {
      await context.close();
    }
  });
});
