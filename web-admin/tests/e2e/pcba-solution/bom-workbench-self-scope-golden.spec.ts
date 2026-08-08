import * as fs from 'fs';
import * as path from 'path';
import { createHash } from 'crypto';
import { fileURLToPath } from 'url';
import type { Browser, BrowserContext, Response } from '@playwright/test';
import { test, expect, type Page } from '../../fixtures';
import { ensureSidebarExpanded, waitForDynamicPageLoad } from '../helpers';
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
const INCIDENT_FIXTURE = 'E1-error (1).xlsx';
const INCIDENT_SHA256 = '06748c113435afda0409eb0ca119715be141f67fb65808d094211b10f5d52801';

function findIncidentBom(): string | undefined {
  const roots = [
    process.env.SOT_BOM_INCIDENT_FIXTURE,
    process.env.BOM_GOLDEN_VERIFY_DIR
      ? path.join(process.env.BOM_GOLDEN_VERIFY_DIR, INCIDENT_FIXTURE)
      : undefined,
    process.env.AURA_ENTERPRISE_ROOT
      ? path.join(process.env.AURA_ENTERPRISE_ROOT, 'doa/jiejia_tech/bom-verify', INCIDENT_FIXTURE)
      : undefined,
    path.resolve(
      HERE,
      '../../../../../../auraboot-enterprise/doa/jiejia_tech/bom-verify',
      INCIDENT_FIXTURE,
    ),
    path.join(
      '/Users/ghj/work/auraboot/auraboot-enterprise/doa/jiejia_tech/bom-verify',
      INCIDENT_FIXTURE,
    ),
  ].filter(Boolean) as string[];
  for (const candidate of roots) {
    if (fs.existsSync(candidate) && fs.statSync(candidate).isFile()) return candidate;
  }
  return undefined;
}

function sha256(filePath: string): string {
  return createHash('sha256').update(fs.readFileSync(filePath)).digest('hex');
}

async function execCommand(page: Page, code: string, payload: Record<string, unknown>) {
  const r = await page.request.post(`/api/meta/commands/execute/${code}`, {
    data: { payload, operationType: 'create' },
    timeout: 30_000,
  });
  return { status: r.status(), body: (await r.json().catch(() => ({}))) as any };
}

function commandRecordPid(body: any): string {
  return String(
    body?.data?.data?.recordPid ||
      body?.data?.recordPid ||
      body?.data?.data?.recordId ||
      body?.data?.recordId ||
      '',
  );
}

async function selectReference(page: Page, field: string, pid: string, label: string) {
  const trigger = page.getByTestId(`select-trigger-${field}`);
  await expect(trigger, `${field} reference trigger`).toBeVisible({ timeout: 15_000 });
  await expect(trigger, `${field} reference trigger`).toBeEnabled({ timeout: 15_000 });
  await trigger.click();
  const exact = page.locator(`[role="option"][data-value="${pid}"]`).first();
  const option = (await exact.isVisible({ timeout: 10_000 }).catch(() => false))
    ? exact
    : page.getByRole('option', { name: label, exact: true }).first();
  await expect(option, `${field} option ${label}`).toBeVisible({ timeout: 15_000 });
  await option.click();
  await expect(trigger).toContainText(label, { timeout: 5_000 });
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
    const incident = findIncidentBom();
    expect(incident, 'fixed E1 incident fixture present').toBeTruthy();
    expect(sha256(incident!), 'fixed E1 incident fixture checksum').toBe(INCIDENT_SHA256);

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
      step = 'eng create customer/project';
      const uid = `${Date.now()}${Math.random().toString(16).slice(2, 6)}`;
      const customerName = `SelfScope Customer ${uid}`;
      const account = await execCommand(engPage, 'crm:create_account', {
        crm_acc_name: customerName,
        crm_acc_industry: 'pcba',
      });
      const customerId = commandRecordPid(account.body);
      expect(customerId, 'customer created').toBeTruthy();
      const projectName = `SelfScope ${uid}`;
      const proj = await execCommand(engPage, 'bom:create_project', {
        bom_project_name: projectName,
        bom_pcba_code: `SS-${uid}`,
        bom_project_customer_id: customerId,
        bom_project_library_source: 'excel_current_library',
        bom_project_remark: 'self-scope golden',
      });
      const projId = commandRecordPid(proj.body);
      expect(
        projId,
        `project created (resp=${JSON.stringify(proj.body?.data).slice(0, 200)})`,
      ).toBeTruthy();

      // Drive the user-visible core action through the real workbench form.
      step = 'eng open upload form';
      await engPage.goto('/dashboards', { waitUntil: 'domcontentloaded' });
      await ensureSidebarExpanded(engPage);
      const sidebar = engPage.getByTestId('sidebar');
      await sidebar.locator(`a[href="${WORKBENCH_HREF}"]`).first().click();
      await waitForDynamicPageLoad(engPage, 20_000);
      const uploadButton = engPage
        .getByTestId('toolbar-btn-upload_bom')
        .or(engPage.getByRole('button', { name: /上传 BOM|Upload BOM/i }))
        .first();
      await expect(uploadButton).toBeVisible({ timeout: 15_000 });
      await uploadButton.click();

      step = 'eng select customer/project';
      await selectReference(engPage, 'bom_task_customer_id', customerId, customerName);
      await selectReference(engPage, 'bom_task_project_id', projId, projectName);

      step = 'eng upload incident fixture';
      const fileField = engPage.getByTestId('form-field-bom_task_raw_file_id');
      await expect(fileField).toBeVisible({ timeout: 15_000 });
      const uploadResponsePromise = engPage.waitForResponse(
        (response) =>
          response.url().includes('/api/file/upload') && response.request().method() === 'POST',
        { timeout: 60_000 },
      );
      await fileField.locator('input[type="file"]').first().setInputFiles(incident!);
      const uploadResponse = await uploadResponsePromise;
      expect(uploadResponse.ok(), `file upload HTTP ${uploadResponse.status()}`).toBe(true);
      await expect(fileField).toContainText(INCIDENT_FIXTURE, { timeout: 10_000 });

      step = 'eng start conversion from form';
      const startResponsePromise = engPage.waitForResponse(
        (response) =>
          response.url().includes('/api/meta/commands/execute/bom:start_conversion') &&
          response.request().method() === 'POST',
        { timeout: 60_000 },
      );
      const startButton = engPage
        .getByTestId('form-btn-start_conversion')
        .or(engPage.getByRole('button', { name: /开始转换|Start Conversion/i }))
        .first();
      await expect(startButton).toBeVisible({ timeout: 15_000 });
      await startButton.click();
      const startResponse = await startResponsePromise;
      const startBody = (await startResponse.json().catch(() => ({}))) as any;
      expect(
        startResponse.status(),
        `start_conversion response: ${JSON.stringify(startBody)}`,
      ).toBe(200);
      expect(
        String(startBody?.code ?? '0'),
        `start_conversion body: ${JSON.stringify(startBody)}`,
      ).toBe('0');

      // The previous golden stopped as soon as the record appeared. The incident
      // happens later inside the async handler, so the release verdict must wait
      // for the authoritative terminal state.
      step = 'eng poll own task to completed';
      let engTaskNo = '';
      let engTask: Record<string, unknown> = {};
      await expect
        .poll(
          async () => {
            const recs = await queryDynamicRecords(engPage, 'bom_conversion_task_pcba', [
              { fieldName: 'bom_task_project_id', operator: 'EQ', value: projId },
            ]);
            if (recs.length > 0) {
              engTask = recs[0];
              engTaskNo = String(engTask.bom_task_no ?? '');
              const status = String(engTask.bom_task_status ?? '');
              if (
                [
                  'failed',
                  'format_exploration_required',
                  'adjustment_required',
                  'cancelled',
                ].includes(status)
              ) {
                return `${status}: ${String(engTask.bom_task_error_message ?? '')}`;
              }
              return status;
            }
            return '';
          },
          { timeout: 240_000, intervals: [2_000, 3_000, 5_000] },
        )
        .toBe('completed');
      expect(engTaskNo, 'completed task has task number').toBeTruthy();
      // The workbench intentionally ellipsizes long task numbers in rendered
      // cell text. Keep API assertions on the complete identifier, while browser
      // assertions use a unique prefix that remains visible in the DOM.
      const engTaskUiToken = engTaskNo.slice(0, 12);
      expect(
        JSON.stringify(engTask),
        'completed incident task has no ambiguity warning',
      ).not.toContain('系统无法安全判断部分内容');
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
      // This task has just been created and the workbench defaults to newest
      // first, so assert the unfiltered first-page rendering directly. Using
      // the generic search helper here can open the app-wide search dialog,
      // because task number is not a keyword-searchable workbench column.
      const engRow = engPage.locator('tbody tr', { hasText: engTaskUiToken }).first();
      await expect(engRow).toContainText(engTaskUiToken, { timeout: 25_000 });
      // admin task NOT rendered anywhere in eng's scoped list
      step = 'eng workbench hides admin task';
      const engMain = await engPage
        .locator('main')
        .innerText()
        .catch(() => '');
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
      const adminRow = adminPage.locator('tbody tr', { hasText: engTaskUiToken }).first();
      await expect(adminRow).toContainText(engTaskUiToken, { timeout: 25_000 });

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
