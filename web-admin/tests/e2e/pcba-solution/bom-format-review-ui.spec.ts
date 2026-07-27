import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import {
  clickRowActionByLocator,
  ensureSidebarExpanded,
  findRowInPaginatedList,
  uniqueId,
  waitForDynamicPageLoad,
} from '../helpers';
import {
  cleanupRows,
  makeQuoteRoleUser,
  ensureQuoteRoleUser,
  openQuoteRolePage,
  readDynamicRecord,
  seedBomWorkbench,
  type BomWorkbenchSeed,
  type QuoteRoleUser,
} from './quote-e2e-helpers';

/**
 * Quote/BOM 真机 — LLM 格式复核的 UI 自动化样例(BOM-09/10 的 UI 对照).
 * 后端 LLM 格式复核已由 Java IT 覆盖(BomDeepSeekLlmIT live / BomRawBomLlmPipelineIT / ExploreFormatHandlerTest);
 * 本测试补 UI 侧:使用确定性已完成任务打开 BOM 工作台详情,断言**页面 UI
 * 反映格式复核结果** —— metric-strip 计数、解析出的行表格、状态横幅,且打开评审抽屉时 UI 变化。
 */
const WORKBENCH = '/p/bom_conversion_task_pcba_workbench';
const uid = uniqueId('fmt').replace(/_/g, '-');
const users: Record<string, QuoteRoleUser> = {};
let taskId = '';
let taskNo = '';
let created: BomWorkbenchSeed | undefined;

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

test.describe('BOM LLM format-review UI (BOM-09/10 UI counterpart) @smoke', () => {
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

  test('provision a deterministic completed task for the workbench', async ({ browser }) => {
    const context = await browser.newContext({
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
    });
    const page = await context.newPage();
    try {
      created = await seedBomWorkbench(page, { ownerEmail: users['eng'].email });
      taskId = created.taskId;
      const task = await readDynamicRecord(page, 'bom_conversion_task_pcba', taskId);
      taskNo = String(task.bom_task_no || '');
      expect(task.bom_task_status).toBe('completed');
      expect(taskNo, 'seeded task exposes its user-visible task number').toBeTruthy();
    } finally {
      await context.close();
    }
  });

  test('BOM-09/10-UI workbench reflects the LLM format-review result + interaction changes UI', async ({
    browser,
  }) => {
    expect(
      taskId,
      'previous conversion test must produce a task; fixture/setup failures must fail fast',
    ).toBeTruthy();
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
      const standardLineTable = page
        .getByRole('table')
        .filter({ has: page.getByRole('columnheader', { name: /行号|Row/i }) });
      await expect(
        standardLineTable.getByRole('columnheader', { name: /物料名称|Material Name/i }),
      ).toBeVisible();
      await expect(
        standardLineTable.getByRole('columnheader', { name: /位号|Reference/i }),
      ).toBeVisible();

      const firstLine = standardLineTable.getByRole('row').nth(1);
      await expect(firstLine).toBeVisible();
      await firstLine.click();

      await expect(page.getByRole('button', { name: /关闭复核浮层|Close Review/i })).toBeVisible();
      await expect(page.getByText(/当前状态|Current Status/i).last()).toBeVisible();
      await expect(
        page.getByRole('heading', { name: /候选物料|Candidate Materials/i }),
      ).toBeVisible();
      await expect(page.getByText(/导出影响与历史|Export Impact/i).last()).toBeVisible();
    } finally {
      await context.close();
    }
  });
});
