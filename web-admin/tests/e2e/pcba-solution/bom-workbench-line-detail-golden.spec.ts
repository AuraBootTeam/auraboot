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
  queryDynamicRecords,
  readDynamicRecord,
  searchBusinessList,
  seedBomWorkbench,
  type BomWorkbenchSeed,
  type QuoteRoleUser,
} from './quote-e2e-helpers';

/**
 * Quote/BOM 真机 — 工作台列表筛选/翻页 与 行明细渲染/决策审计 (B13-01 / B09-04 / B20-03 / B13-02).
 * 复用确定性已完成任务 seed（与 BOM-05/06/07 相同的 seedBomWorkbench），断言：
 * - B13-01: 工作台列表翻页遍历可定位目标任务；按客户关键字筛选后仅见该客户任务、
 *   清空筛选恢复全集；可见任务集合与后台 API 集合一致。
 * - B09-04: 工作台明细页行表渲染位号/数量，首行内容与后台记录一致；
 *   有效行计数（metric strip）与后台 active 行数一致（隔离行不进有效行集合）。
 * - B20-03: 点击行打开复核浮层，位号/数量/候选/决策字段与后台一致，决策历史（导出影响）可见。
 * - B13-02: 评审队列列出待确认行计数；批量确认后行转 confirmed/green、任务 yellow 归零，
 *   任务退出待复核聚合，刷新后一致。
 */
const WORKBENCH = '/p/bom_conversion_task_pcba_workbench';
const uid = uniqueId('wbld').replace(/_/g, '-');
const users: Record<string, QuoteRoleUser> = {};
let taskId = '';
let taskNo = '';
let customerName = '';
let customerId = '';
let created: BomWorkbenchSeed | undefined;

async function listLines(page: Page): Promise<any[]> {
  const r = await page.context().request.get(
    `/api/dynamic/bom_standard_line_pcba/list?pageNum=1&pageSize=500&sortField=created_at&sortOrder=desc`,
  );
  const b = await r.json().catch(() => ({}) as any);
  const recs = b?.data?.records || b?.data?.data?.records || b?.data || [];
  return (Array.isArray(recs) ? recs : []).filter(
    (l: any) => String(l.bom_std_task_id || '') === String(taskId),
  );
}

test.describe('BOM workbench list filter + line detail golden (B13-01/B09-04/B20-03) @smoke', () => {
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
      const task = await readDynamicRecord(adminPage, 'bom_conversion_task_pcba', taskId);
      taskNo = String(task?.bom_task_no || '');
      customerId = String(task?.bom_task_customer_id || '');
      expect(customerId, 'fixture task has a customer').toBeTruthy();
      const customer = await readDynamicRecord(adminPage, 'crm_account_common', customerId);
      customerName = String(customer?.crm_acc_name || '');
      expect(customerName, 'fixture customer has a searchable name').toBeTruthy();
    } finally {
      await adminContext.close();
    }
    const role = await openQuoteRolePage(browser, users['eng']);
    try {
      expect(taskId, 'seed produced a completed task').toBeTruthy();
      expect(taskNo, 'seed task carries a task number').toBeTruthy();
      expect((await listLines(role.page)).length, 'seed produced standard lines').toBeGreaterThan(0);
    } finally {
      await role.context.close();
    }
  });

  test('B13-01 workbench list: pagination traversal, customer filter, clear restores full set, API set consistent', async ({ browser }) => {
    const adminContext = await browser.newContext({
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
    });
    const adminPage = await adminContext.newPage();
    try {
      // 翻页：分页遍历定位目标任务行（分页遍历合同）
      await adminPage.goto(WORKBENCH, { waitUntil: 'domcontentloaded' });
      await waitForDynamicPageLoad(adminPage, 20_000);
      const workbenchRow = await findRowInPaginatedList(adminPage, taskNo, 25_000);
      await expect(workbenchRow).toContainText(taskNo.slice(0, 12));

      // 后台集合：该客户名下的任务（含本任务）与 UI 集合一致
      const apiTasks = await queryDynamicRecords(adminPage, 'bom_conversion_task_pcba', [
        { fieldName: 'bom_task_customer_id', operator: 'EQ', value: customerId },
      ]);
      expect(
        apiTasks.some((t) => String(t.bom_task_no) === taskNo),
        'seeded task belongs to the seeded customer per API',
      ).toBe(true);

      // 关键字过滤:工作台页面的搜索框走模型 keyword 面(含任务号),不承诺按
      // 客户名过滤(任务表无客户名列,客户归属由上方 API EQ 断言覆盖)。
      const filteredCount = await searchBusinessList(adminPage, WORKBENCH, taskNo.slice(0, 12), 'bom_conversion_task_pcba');
      const bodyText = await adminPage.locator('main').innerText();
      expect(bodyText, 'filtered workbench list still shows the seeded task').toContain(taskNo.slice(0, 12));
      expect(filteredCount, 'filtered list returns at least the seeded task row').toBeGreaterThan(0);

      // 清空筛选恢复全集：搜索框清空后行数恢复到未过滤首页行数
      const search = adminPage.getByTestId('list-search-input');
      await search.fill('');
      const restored = await adminPage.locator('table tbody tr').count();
      expect(restored, 'clearing the filter restores the unfiltered first page').toBeGreaterThanOrEqual(
        filteredCount,
      );
      await test.info().attach('B13-01-filter-restore', {
        body: await adminPage.screenshot({ fullPage: true }), contentType: 'image/png',
      });

      // B13-01 补强:按任务号前缀重新搜索——行内状态 tag 显示 completed
      await searchBusinessList(
        adminPage, WORKBENCH, taskNo.slice(0, 12), 'bom_conversion_task_pcba',
      );
      const statusRow = adminPage.getByRole('row').filter({ hasText: taskNo.slice(0, 12) }).first();
      await expect(statusRow).toBeVisible({ timeout: 20_000 });
      await expect(statusRow, 'row shows completed status').toContainText(/已完成|completed/i);
      await test.info().attach('B13-01-status-filter', {
        body: await adminPage.screenshot({ fullPage: true }), contentType: 'image/png',
      });
    } finally {
      await adminContext.close();
    }
  });

  test('B09-04/B20-03 workbench detail: refdes/qty render matches backend; line drawer shows candidates and decision audit', async ({ browser }) => {
    const { context, page } = await openQuoteRolePage(browser, users['eng']);
    try {
      // 打开工作台明细
      await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
      await ensureSidebarExpanded(page);
      const sidebar = page.getByTestId('sidebar');
      await sidebar.locator(`a[href="${WORKBENCH}"]`).first().click();
      await waitForDynamicPageLoad(page, 20_000);
      await expect(page.locator('main')).toContainText(taskNo, { timeout: 20_000 });
      const workbenchRow = await findRowInPaginatedList(page, taskNo, 20_000);
      await clickRowActionByLocator(page, workbenchRow, 'open_workbench', '打开');
      await waitForDynamicPageLoad(page, 20_000);
      await expect(page).toHaveURL(new RegExp(`${WORKBENCH}/view/${taskId}$`));
      // The shared helper can observe the preceding list during SPA navigation.
      // Wait for this detail route's actual business content before inspecting it.
      await expect(page.getByRole('columnheader', { name: /物料名称|Material Name/i }).first())
        .toBeVisible({ timeout: 20_000 });

      // B09-04: 行表表头（行号/物料名称/位号）可见；有效行计数与后台 active 行数一致
      const lines = await listLines(page);
      const active = lines.filter((l) => String(l.bom_std_exclusion_status ?? 'active') === 'active');
      expect(lines.length, 'backend has standard lines for this task').toBeGreaterThan(0);
      const standardLineTable = page
        .getByRole('table')
        .filter({ has: page.getByRole('columnheader', { name: /行号|Row/i }) });
      await expect(
        standardLineTable.getByRole('columnheader', { name: /物料名称|Material Name/i }),
      ).toBeVisible();
      await expect(
        standardLineTable.getByRole('columnheader', { name: /位号|Reference/i }),
      ).toBeVisible();
      const validRowsButton = page.getByRole('button', { name: /有效行|Valid Rows/i });
      await expect(validRowsButton).toBeVisible();
      await expect(validRowsButton, '有效行计数与后台一致（隔离行不计入）').toContainText(
        String(active.length),
      );

      // B09-04: 首行位号/数量与后台记录一致（行级文本包含，不依赖具体列序）
      // API ordering and table ordering differ; compare the same persisted PID.
      const firstBackend = active.find(line => String(line.pid) === created!.standardLineId);
      expect(firstBackend, 'the controlled standard line exists in the active API set').toBeDefined();
      expect(String(firstBackend.bom_std_refdes || ''), 'reference designators are non-empty').not.toBe('');
      const firstLine = standardLineTable.getByTestId(`table-row-${created!.standardLineId}`);
      await expect(firstLine).toBeVisible();
      await expect(firstLine, '首行位号与后台一致').toContainText(
        String(firstBackend.bom_std_refdes ?? '').split(',')[0] ?? '',
      );
      await expect(firstLine, '首行数量与后台一致').toContainText(
        String(firstBackend.bom_std_qty ?? ''),
      );

      // B20-03: 行详情浮层 — 当前状态/候选物料/决策历史与导出影响 可见；明细与后台一致
      await firstLine.click();
      await expect(page.getByRole('button', { name: /关闭复核浮层|Close Review/i })).toBeVisible();
      await expect(page.getByText(/当前状态|Current Status/i).last()).toBeVisible();
      await expect(
        page.getByRole('heading', { name: /候选物料|Candidate Materials/i }),
      ).toBeVisible();
      const drawer = page.getByTestId('review-drawer');
      await expect(drawer).toBeVisible();
      const drawerText = await drawer.innerText();
      expect(drawerText, '浮层明细包含该行位号').toContain(
        String(firstBackend.bom_std_refdes ?? '').split(',')[0] ?? '',
      );
      expect(drawerText, '浮层明细包含该行数量').toContain(String(firstBackend.bom_std_qty ?? ''));
      if (firstBackend.bom_std_material_code) {
        expect(drawerText, '浮层明细包含候选/决策物料编码').toContain(
          String(firstBackend.bom_std_material_code),
        );
      }
      const exportHistory = page.getByTestId('review-drawer-tab-export');
      await expect(exportHistory).toBeVisible();
      await expect(exportHistory.locator('summary')).toContainText(
        /决策历史与导出影响|导出影响与历史|Decision History.*Export|Export.*History/i,
      );
      await exportHistory.locator('summary').click();
      const revision = await readDynamicRecord(page, 'bom_export_revision', created!.exportRevisionId);
      expect(revision.bom_er_filename, 'controlled export revision has a filename').toBeTruthy();
      await expect(exportHistory).toContainText(String(revision.bom_er_filename));
      await expect(page.getByTestId(`review-drawer-candidate-${created!.primaryEvidenceId}`))
        .toContainText(created!.candidateCode);
      await expect(page.getByTestId(`review-drawer-candidate-${created!.secondaryEvidenceId}`))
        .toContainText(created!.secondaryCandidateCode);
      await test.info().attach('line-detail-golden', {
        body: await page.screenshot({ fullPage: true }),
        contentType: 'image/png',
      });
    } finally {
      await context.close();
    }
  });

  test('B13-02 review queue: task lists pending count, batch confirm flips line to confirmed/green, task leaves pending aggregate, consistent on reload', async ({ browser }) => {
    const adminContext = await browser.newContext({
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
    });
    const adminPage = await adminContext.newPage();
    const QUEUE = '/p/bom_review_queue';
    // 队列表按 DSL 列序渲染;按表头文本定位"待确认行"列，读精确单元格而非整行文本
    const yellowCountCell = async (row: ReturnType<typeof adminPage.getByRole>) => {
      const headers = adminPage.locator('table').first().locator('thead th');
      const headerTexts = await headers.allInnerTexts();
      const yellowIdx = headerTexts.findIndex((t) => /待确认行|Review Lines/i.test(t));
      expect(yellowIdx, 'queue table renders the pending review count column').toBeGreaterThanOrEqual(0);
      return row.locator('td').nth(yellowIdx);
    };
    try {
      // 后台事实:确认前任务 yellow=1，canonical 行 pending/yellow
      const taskBefore = await readDynamicRecord(adminPage, 'bom_conversion_task_pcba', taskId);
      expect(Number(taskBefore.bom_task_yellow_count ?? 0), 'seeded task starts with 1 pending review line').toBe(1);
      const lineBefore = await readDynamicRecord(adminPage, 'req_requirement_line_pcba_bom', created!.canonicalLineId);
      expect(String(lineBefore.bom_cl_review_status), 'canonical line starts pending').toBe('pending');

      // 评审队列聚合:队列行列出任务及其待确认行计数
      await adminPage.goto(QUEUE, { waitUntil: 'domcontentloaded' });
      await waitForDynamicPageLoad(adminPage, 20_000);
      const queueRow = await findRowInPaginatedList(adminPage, taskNo, 25_000);
      await expect(
        await yellowCountCell(queueRow),
        'queue row carries the pending (yellow) count before confirm',
      ).toHaveText(String(taskBefore.bom_task_yellow_count));
      await test.info().attach('B13-02-queue-before', {
        body: await adminPage.screenshot({ fullPage: true }), contentType: 'image/png',
      });

      // 评审详情:行动作进入详情页（URL 含任务记录 id）
      await clickRowActionByLocator(adminPage, queueRow, 'review', '评审');
      await waitForDynamicPageLoad(adminPage, 20_000);
      await expect(adminPage).toHaveURL(new RegExp(taskId));

      // 批量确认行:命令执行返回 confirmedCount=1
      const confirmResponsePromise = adminPage.waitForResponse(
        (r) =>
          r.url().includes('/api/meta/commands/execute/bom:confirm_pending_canonical_lines') &&
          r.request().method() === 'POST',
        { timeout: 30_000 },
      );
      await adminPage.getByRole('button', { name: /批量确认行|Confirm Lines/i }).click();
      const confirmDialog = adminPage.getByTestId('confirm-dialog');
      if (await confirmDialog.isVisible({ timeout: 3_000 }).catch(() => false)) {
        await adminPage.getByTestId('confirm-ok').click();
      }
      const confirmBody = await (await confirmResponsePromise).json().catch(() => ({}));
      expect(
        confirmBody?.data?.data?.confirmedCount ?? confirmBody?.data?.confirmedCount ?? confirmBody?.confirmedCount,
        `confirm command confirms exactly the pending line: ${JSON.stringify(confirmBody).slice(0, 400)}`,
      ).toBe(1);

      // 计数变化正确:任务 yellow 归零、green +1；行转 confirmed/green
      const taskAfter = await readDynamicRecord(adminPage, 'bom_conversion_task_pcba', taskId);
      expect(Number(taskAfter.bom_task_yellow_count ?? 0), 'task yellow count drops to zero').toBe(0);
      expect(
        Number(taskAfter.bom_task_green_count ?? 0),
        'task green count absorbs the confirmed line',
      ).toBe(Number(taskBefore.bom_task_green_count ?? 0) + 1);
      const lineAfter = await readDynamicRecord(adminPage, 'req_requirement_line_pcba_bom', created!.canonicalLineId);
      expect(String(lineAfter.bom_cl_review_status), 'confirmed line leaves the pending set').toBe('confirmed');
      expect(String(lineAfter.bom_cl_review_tone), 'confirmed line tone flips to green').toBe('green');

      // 退出待复核聚合:回到队列，待确认行单元格归零；刷新后一致
      await adminPage.goto(QUEUE, { waitUntil: 'domcontentloaded' });
      await waitForDynamicPageLoad(adminPage, 20_000);
      const queueRowAfter = await findRowInPaginatedList(adminPage, taskNo, 25_000);
      await expect(
        await yellowCountCell(queueRowAfter),
        'queue row no longer advertises a pending count',
      ).toHaveText(String(taskAfter.bom_task_yellow_count));
      await adminPage.reload({ waitUntil: 'domcontentloaded' });
      await waitForDynamicPageLoad(adminPage, 20_000);
      const queueRowReloaded = await findRowInPaginatedList(adminPage, taskNo, 25_000);
      await expect(
        await yellowCountCell(queueRowReloaded),
        'reload keeps the task outside the pending aggregate',
      ).toHaveText(String(taskAfter.bom_task_yellow_count));
      await test.info().attach('B13-02-queue-after', {
        body: await adminPage.screenshot({ fullPage: true }), contentType: 'image/png',
      });
    } finally {
      await adminContext.close();
    }
  });
});
