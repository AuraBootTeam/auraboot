import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import type { Browser, BrowserContext, Response } from '@playwright/test';
import { test, expect, type Page } from '../../fixtures';
import { ensureSidebarExpanded, findRowInPaginatedList, waitForDynamicPageLoad } from '../helpers';
import { loginViaUI } from '../../helpers/wd-fixtures';
import {
  dynamicCreate,
  queryDynamicRecords,
  QUOTE_ROLE_TEST_PASSWORD,
  type QuoteRoleUser,
} from './quote-e2e-helpers';

/**
 * BOM workbench SELF-SCOPE real-browser golden (data-permission slice 7).
 *
 * The Quote/BOM deployment gives business roles `self` data scope on
 * `bom_conversion_task_pcba` read (aura-quote business-roles.json #243, reconciled into
 * `ab_role_data_scope`). Concretely: a `bom_engineering` user must only see the conversion
 * tasks it created; a tenant admin (no scope rows) sees everyone's. Prior verification was
 * backend-only — this spec proves the scope end-to-end in the browser:
 *
 *   admin seeds an ADMIN-owned task; eng creates its OWN task through the real command
 *   pipeline (create_project → upload BOM → start_conversion). Then, logged in AS eng, the
 *   BOM workbench list opened from the sidebar shows eng's own task and DOES NOT show the
 *   admin-owned task; admin (all-scope) sees BOTH. API cross-checks pin the same truth.
 *
 * NOTE: this directly contradicts the stale "conversion tasks are not self-scoped" comment
 * in bom-workbench-role-eng-golden.spec.ts — self scope IS enforced on this stack (verified
 * empirically: eng's list returns only eng-owned rows). See the session report.
 *
 * RUN (host-first quoteops golden stack):
 *   PW_PROFILE=quoteops PW_SKIP_WEBSERVER=1 PLAYWRIGHT_BASE_URL=http://127.0.0.1:<web> \
 *     BACKEND_URL=http://127.0.0.1:<be> \
 *     pnpm exec playwright test --project=quoteops --no-deps \
 *     tests/e2e/pcba-solution/bom-workbench-self-scope-golden.spec.ts
 */

const ADMIN_EMAIL = process.env.ADMIN_EMAIL || 'admin@auraboot.com';
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || 'Test2026x';
const WORKBENCH_HREF = '/p/bom_conversion_task_pcba_workbench';

const ENG_USER: QuoteRoleUser = {
  key: 'smoke_eng',
  email: 'smoke-eng@e2e.local',
  displayName: 'Smoke Engineering',
  password: QUOTE_ROLE_TEST_PASSWORD,
  roleCodes: ['bom_engineering'],
};

const HERE = path.dirname(fileURLToPath(import.meta.url));
function findSampleBom(): string | undefined {
  const rel = 'aura-quote/docs/ref/10款GERBER加坐标';
  const roots = [
    process.env.QUOTE_BOM_SAMPLES_DIR,
    path.resolve(HERE, '../../../../../' + rel),
    '/Users/ghj/work/auraboot/' + rel,
  ].filter(Boolean) as string[];
  for (const root of roots) {
    const d = path.join(root, 'FUTROBO_MCU', 'BOM');
    if (fs.existsSync(d)) {
      const x = fs.readdirSync(d).find((f) => /\.xlsx$/i.test(f));
      if (x) return path.join(d, x);
    }
  }
  return undefined;
}

async function execCommand(page: Page, code: string, payload: Record<string, unknown>) {
  const r = await page.request.post(`/api/meta/commands/execute/${code}`, {
    data: { payload, operationType: 'create' },
    timeout: 30_000,
  });
  return { status: r.status(), body: (await r.json().catch(() => ({}))) as any };
}

test.describe('BOM workbench self-scope real-browser golden @smoke', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(240_000);

  let adminContext: BrowserContext;
  let adminPage: Page;
  let adminTaskNo: string;
  const adminRows: { model: string; pid: string }[] = [];

  test.beforeAll(async ({ browser }: { browser: Browser }) => {
    adminContext = await browser.newContext({ storageState: { cookies: [], origins: [] } });
    adminPage = await adminContext.newPage();
    await loginViaUI(adminPage, ADMIN_EMAIL, ADMIN_PASSWORD);

    // ensure the fixed smoke_eng account exists (idempotent — shared with the other role specs)
    const resp = await adminPage.request.post('/api/admin/users', {
      data: {
        email: ENG_USER.email,
        displayName: ENG_USER.displayName,
        initialPassword: ENG_USER.password,
        roleCodes: ENG_USER.roleCodes,
        sendInviteEmail: false,
      },
      timeout: 20_000,
    });
    if (!resp.ok()) {
      const text = await resp.text().catch(() => '');
      expect(
        /已存在|exists|duplicate|重复|conflict/i.test(text) || resp.status() === 409,
        `ensure smoke_eng failed: HTTP ${resp.status()} ${text.slice(0, 300)}`,
      ).toBe(true);
    }

    // admin-owned task (created_by=admin)
    const suffix = `${Date.now()}${Math.random().toString(16).slice(2, 8)}`;
    adminTaskNo = `E2E-SCOPE-ADMIN-${suffix}`;
    await dynamicCreate(
      adminPage,
      'bom_conversion_task_pcba',
      {
        bom_task_no: adminTaskNo,
        bom_task_source_package: 'self-scope-golden',
        bom_task_status: 'completed',
        bom_task_raw_filename: `${adminTaskNo}.xlsx`,
        bom_task_completed_at: new Date().toISOString(),
        bom_task_total_rows: 1,
      },
      adminRows,
    );
    // eslint-disable-next-line no-console
    console.log(`[self-scope-golden] admin task=${adminTaskNo}`);
  });

  test.afterAll(async () => {
    await adminContext?.close();
  });

  test('eng sees only own task; admin sees all', async ({ browser }) => {
    const sample = findSampleBom();
    expect(sample, 'sample BOM fixture present').toBeTruthy();

    const engContext = await browser.newContext({ storageState: { cookies: [], origins: [] } });
    const engPage = await engContext.newPage();
    const forbidden: { step: string; url: string; status: number }[] = [];
    const serverErrors: { step: string; url: string; status: number }[] = [];
    let step = 'login';
    engPage.on('response', (resp: Response) => {
      const status = resp.status();
      const url = resp.url();
      if (!url.includes('/api/')) return;
      if (status === 401 || status === 403) forbidden.push({ step, url, status });
      if (status >= 500) serverErrors.push({ step, url, status });
    });

    try {
      await loginViaUI(engPage, ENG_USER.email, ENG_USER.password);

      // 1. eng creates its OWN task via the real command pipeline (created_by=eng)
      step = 'eng create project';
      const uid = `${Date.now()}${Math.random().toString(16).slice(2, 6)}`;
      const proj = await execCommand(engPage, 'bom:create_project', {
        bom_project_name: `SelfScope ${uid}`,
        bom_pcba_code: `SS-${uid}`,
        bom_project_library_source: 'excel_current_library',
        bom_project_remark: 'self-scope golden',
      });
      const projId =
        proj.body?.data?.data?.recordPid || proj.body?.data?.recordPid || proj.body?.data?.recordId;
      expect(projId, `project created (resp=${JSON.stringify(proj.body?.data).slice(0, 200)})`).toBeTruthy();

      step = 'eng upload bom';
      const up = await engPage.request.post('/api/file/upload', {
        multipart: {
          file: {
            name: 'bom.xlsx',
            mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
            buffer: fs.readFileSync(sample!),
          },
        },
        timeout: 30_000,
      });
      const fileId = (await up.json())?.data?.fileId;
      expect(fileId, 'BOM uploaded').toBeTruthy();

      step = 'eng start conversion';
      const conv = await execCommand(engPage, 'bom:start_conversion', {
        bom_task_project_id: projId,
        bom_task_source_package: `self-scope-${uid}`,
        bom_task_raw_file_id: fileId,
      });
      expect(conv.status, 'start_conversion accepted').toBe(200);

      // poll (as eng) until eng's own task record appears; capture its bom_task_no
      step = 'eng poll own task';
      let engTaskNo = '';
      await expect
        .poll(
          async () => {
            const recs = await queryDynamicRecords(engPage, 'bom_conversion_task_pcba', [
              { fieldName: 'bom_task_project_id', operator: 'EQ', value: projId },
            ]);
            if (recs.length > 0) {
              engTaskNo = String(recs[0].bom_task_no ?? '');
              return engTaskNo.length > 0;
            }
            return false;
          },
          { timeout: 90_000, intervals: [2000, 3000, 3000, 3000] },
        )
        .toBe(true);
      // eslint-disable-next-line no-console
      console.log(`[self-scope-golden] eng task=${engTaskNo}`);

      // 2. API cross-check as eng: sees own task, does NOT see admin's task
      step = 'eng api cross-check';
      const engSeesOwn = await queryDynamicRecords(engPage, 'bom_conversion_task_pcba', [
        { fieldName: 'bom_task_no', operator: 'EQ', value: engTaskNo },
      ]);
      expect(engSeesOwn.length, `eng must see own task ${engTaskNo}`).toBe(1);
      const engSeesAdmin = await queryDynamicRecords(engPage, 'bom_conversion_task_pcba', [
        { fieldName: 'bom_task_no', operator: 'EQ', value: adminTaskNo },
      ]);
      expect(
        engSeesAdmin.length,
        `SELF-SCOPE: eng must NOT see admin task ${adminTaskNo} (got ${engSeesAdmin.length})`,
      ).toBe(0);

      // 3. real browser AS eng: workbench list shows own task, hides admin's task
      step = 'eng open workbench list';
      await engPage.goto('/dashboards', { waitUntil: 'domcontentloaded' });
      await ensureSidebarExpanded(engPage);
      const engSidebar = engPage.getByTestId('sidebar');
      await engSidebar.locator(`a[href="${WORKBENCH_HREF}"]`).first().click();
      await waitForDynamicPageLoad(engPage, 20_000);
      // own task visible
      const engRow = await findRowInPaginatedList(engPage, engTaskNo, 25_000);
      await expect(engRow).toContainText(engTaskNo);
      // admin task NOT rendered anywhere in eng's scoped list
      step = 'eng workbench hides admin task';
      const engMain = await engPage.locator('main').innerText().catch(() => '');
      expect(
        engMain.includes(adminTaskNo),
        `SELF-SCOPE (UI): eng workbench must not render admin task ${adminTaskNo}`,
      ).toBe(false);

      // 4. admin (all-scope) sees BOTH — API definitive
      const adminSeesAdmin = await queryDynamicRecords(adminPage, 'bom_conversion_task_pcba', [
        { fieldName: 'bom_task_no', operator: 'EQ', value: adminTaskNo },
      ]);
      expect(adminSeesAdmin.length, `admin must see admin task ${adminTaskNo}`).toBe(1);
      const adminSeesEng = await queryDynamicRecords(adminPage, 'bom_conversion_task_pcba', [
        { fieldName: 'bom_task_no', operator: 'EQ', value: engTaskNo },
      ]);
      expect(adminSeesEng.length, `admin (all-scope) must see eng task ${engTaskNo}`).toBe(1);

      // 4b. admin real browser: workbench list renders the eng-owned task (cross-owner visibility)
      await adminPage.goto('/dashboards', { waitUntil: 'domcontentloaded' });
      await ensureSidebarExpanded(adminPage);
      await adminPage.getByTestId('sidebar').locator(`a[href="${WORKBENCH_HREF}"]`).first().click();
      await waitForDynamicPageLoad(adminPage, 20_000);
      const adminRow = await findRowInPaginatedList(adminPage, engTaskNo, 25_000);
      await expect(adminRow).toContainText(engTaskNo);

      // 5. hard gates for the eng session: no 403 (scope must filter, not forbid) / no 5xx
      expect(
        forbidden.map((h) => `[${h.step}] ${h.status} ${h.url}`),
        'eng session: scope filters rows, never 401/403',
      ).toEqual([]);
      expect(
        serverErrors.map((h) => `[${h.step}] ${h.status} ${h.url}`),
        'eng session: no 5xx',
      ).toEqual([]);
    } finally {
      await engContext.close();
    }
  });
});
