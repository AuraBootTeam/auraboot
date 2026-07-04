import type { Browser, BrowserContext, Response } from '@playwright/test';
import { test, expect, type Page } from '../../fixtures';
import {
  clickRowActionByLocator,
  ensureSidebarExpanded,
  findRowInPaginatedList,
  waitForDynamicPageLoad,
} from '../helpers';
import { loginViaUI } from '../../helpers/wd-fixtures';
import { dynamicCreate, queryDynamicRecords } from './quote-e2e-helpers';

/**
 * BOM conversion-task SOFT-DELETE real-browser golden.
 *
 * The platform gained dynamic-model soft delete (OSS #1176) and the BOM conversion task
 * model is configured `softDelete: true` (plugins/bom-standardization/config/models.json).
 * The workbench list exposes a `delete` rowAction wired to `action.type=command,
 * command=bom:delete_task` with a `confirm.delete` confirmation. Prior verification only
 * covered the backend mechanism — this spec drives the REAL delete button in the browser:
 *
 *   admin opens the BOM workbench from the sidebar → the seeded task row is visible →
 *   click its delete rowAction → confirm the ConfirmDialog → the bom:delete_task command
 *   returns code=0 → the row disappears from the list and the API list no longer returns it.
 *
 * "Soft (not hard) delete" is proven out-of-band with psql on `deleted_flag` (see the
 * session report); the record stays in `mt_bom_conversion_task_pcba` with deleted_flag=true.
 * This spec logs the deleted task's bom_task_no so that psql check can target it.
 *
 * RUN (host-first quoteops golden stack):
 *   PW_PROFILE=quoteops PW_SKIP_WEBSERVER=1 PLAYWRIGHT_BASE_URL=http://127.0.0.1:<web> \
 *     BACKEND_URL=http://127.0.0.1:<be> \
 *     pnpm exec playwright test --project=quoteops --no-deps \
 *     tests/e2e/pcba-solution/quote-bom-soft-delete-golden.spec.ts
 */

const ADMIN_EMAIL = process.env.ADMIN_EMAIL || 'admin@auraboot.com';
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || 'Test2026x';
const WORKBENCH_HREF = '/p/bom_conversion_task_pcba_workbench';

test.describe('BOM conversion-task soft delete real-browser golden @smoke', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(180_000);

  let adminContext: BrowserContext;
  let adminPage: Page;
  let taskNo: string;
  const rows: { model: string; pid: string }[] = [];
  let taskPid = '';

  test.beforeAll(async ({ browser }: { browser: Browser }) => {
    adminContext = await browser.newContext({ storageState: { cookies: [], origins: [] } });
    adminPage = await adminContext.newPage();
    await loginViaUI(adminPage, ADMIN_EMAIL, ADMIN_PASSWORD);

    const suffix = `${Date.now()}${Math.random().toString(16).slice(2, 8)}`;
    taskNo = `E2E-DEL-${suffix}`;
    // Seed a minimal conversion task that renders in the workbench list (created_by=admin).
    taskPid = await dynamicCreate(
      adminPage,
      'bom_conversion_task_pcba',
      {
        bom_task_no: taskNo,
        bom_task_source_package: 'soft-delete-golden',
        bom_task_status: 'completed',
        bom_task_raw_filename: `${taskNo}.xlsx`,
        bom_task_completed_at: new Date().toISOString(),
        bom_task_total_rows: 1,
        bom_task_valid_rows: 1,
        bom_task_green_count: 1,
        bom_task_yellow_count: 0,
        bom_task_red_count: 0,
      },
      rows,
    );
    // eslint-disable-next-line no-console
    console.log(`[soft-delete-golden] seeded taskNo=${taskNo} taskPid=${taskPid}`);
  });

  test.afterAll(async () => {
    await adminContext?.close();
  });

  test('admin: workbench delete rowAction → confirm → row gone, record soft-deleted', async () => {
    const forbidden: { step: string; url: string; status: number }[] = [];
    const serverErrors: { step: string; url: string; status: number }[] = [];
    let step = 'seed-precondition';
    adminPage.on('response', (resp: Response) => {
      const status = resp.status();
      const url = resp.url();
      if (!url.includes('/api/')) return;
      if (status === 401 || status === 403) forbidden.push({ step, url, status });
      if (status >= 500) serverErrors.push({ step, url, status });
    });

    // Precondition: the task exists and is returned by the API list (not yet deleted).
    const before = await queryDynamicRecords(adminPage, 'bom_conversion_task_pcba', [
      { fieldName: 'bom_task_no', operator: 'EQ', value: taskNo },
    ]);
    expect(before.length, `seeded task ${taskNo} present before delete`).toBe(1);
    expect(String(before[0].deleted_flag ?? 'false')).toBe('false');

    // 1. sidebar → BOM workbench list, seeded row visible
    step = 'open workbench list';
    await adminPage.goto('/dashboards', { waitUntil: 'domcontentloaded' });
    await ensureSidebarExpanded(adminPage);
    const sidebar = adminPage.getByTestId('sidebar');
    await sidebar.locator(`a[href="${WORKBENCH_HREF}"]`).first().click();
    await waitForDynamicPageLoad(adminPage, 20_000);
    const row = await findRowInPaginatedList(adminPage, taskNo, 25_000);
    await expect(row).toContainText(taskNo);

    // 2. click the delete rowAction (second rowAction → lives under the "more" menu)
    step = 'click delete rowAction';
    const deleteResponsePromise = adminPage.waitForResponse(
      (r) =>
        r.url().includes('/api/meta/commands/execute/bom:delete_task') &&
        r.request().method() === 'POST',
      { timeout: 30_000 },
    );
    await clickRowActionByLocator(adminPage, row, 'delete', '删除');

    // 3. confirm dialog → confirm
    step = 'confirm delete';
    const confirmDialog = adminPage.getByTestId('confirm-dialog');
    await expect(confirmDialog).toBeVisible({ timeout: 10_000 });
    await adminPage.getByTestId('confirm-ok').click();

    // 4. bom:delete_task command succeeds
    const deleteBody = await (await deleteResponsePromise).json().catch(() => ({}));
    expect(
      String((deleteBody as { code?: unknown }).code),
      `bom:delete_task response: ${JSON.stringify(deleteBody).slice(0, 500)}`,
    ).toBe('0');

    // 5. row disappears from the list (reload to get a deterministic post-delete render)
    step = 'verify row gone from list';
    await adminPage.reload({ waitUntil: 'domcontentloaded' });
    await waitForDynamicPageLoad(adminPage, 20_000);
    await expect
      .poll(async () => (await adminPage.locator('main').innerText().catch(() => '')).includes(taskNo), {
        timeout: 15_000,
        intervals: [500, 1000, 1500],
      })
      .toBe(false);

    // 6. API list no longer returns the task (soft-delete filter hides it)
    step = 'verify api list hides task';
    await expect
      .poll(
        async () =>
          (
            await queryDynamicRecords(adminPage, 'bom_conversion_task_pcba', [
              { fieldName: 'bom_task_no', operator: 'EQ', value: taskNo },
            ])
          ).length,
        { timeout: 15_000, intervals: [500, 1000, 1500] },
      )
      .toBe(0);

    // 7. hard gates: no forbidden, no 500s across the whole flow
    expect(
      forbidden.map((h) => `[${h.step}] ${h.status} ${h.url}`),
      'no 401/403 during soft-delete flow',
    ).toEqual([]);
    expect(
      serverErrors.map((h) => `[${h.step}] ${h.status} ${h.url}`),
      'no 5xx during soft-delete flow',
    ).toEqual([]);

    // eslint-disable-next-line no-console
    console.log(`[soft-delete-golden] deleted taskNo=${taskNo} taskPid=${taskPid} — verify deleted_flag=true via psql`);
  });
});
