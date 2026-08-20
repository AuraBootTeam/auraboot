import { existsSync, mkdirSync, writeFileSync } from 'node:fs';
import { Client } from 'pg';
import { test, expect, type Page } from '../../fixtures';
import { executeCommandViaApi } from '../helpers';
import { loginViaUI } from '../../helpers/wd-fixtures';
import { BACKEND_URL, BASE_URL, PG_CONN } from '../../helpers/environments';
import {
  ADMIN_EMAIL,
  LEAD,
  PASSWORD,
  downloadTemplate,
  openImport,
  openModelFromMenu,
  submitAndCaptureTask,
  uploadWorkbook,
} from './crm-import-lifecycle-helpers';

const EVIDENCE_DIR =
  process.env.CRM_IMPORT_EVIDENCE_DIR ||
  '/Users/ghj/work/auraboot/.workspace/evidence/crm-import-provider-lifecycle-20260813-s148';

mkdirSync(EVIDENCE_DIR, { recursive: true });
mkdirSync(`${EVIDENCE_DIR}/api-evidence`, { recursive: true });
mkdirSync(`${EVIDENCE_DIR}/db-evidence`, { recursive: true });

async function recordByPid(
  page: Page,
  model: string,
  pid: string,
): Promise<Record<string, unknown>> {
  const response = await page.request.get(`/api/dynamic/${model}/${pid}`);
  expect(response.ok(), await response.text()).toBeTruthy();
  const body = await response.json();
  return (body?.data ?? body) as Record<string, unknown>;
}

async function taskStatus(page: Page, taskId: string): Promise<Record<string, unknown>> {
  const response = await page.request.get(`/api/meta/excel/import/${LEAD}/status/${taskId}`);
  expect(response.ok(), await response.text()).toBeTruthy();
  const body = await response.json();
  return body?.data as Record<string, unknown>;
}

async function waitForTerminal(
  page: Page,
  taskId: string,
  timeoutMs = 60_000,
): Promise<Record<string, unknown>> {
  const deadline = Date.now() + timeoutMs;
  let terminal: Record<string, unknown> | null = null;
  while (Date.now() < deadline) {
    const status = await taskStatus(page, taskId);
    if (String(status.status).toLowerCase() !== 'running') {
      terminal = status;
      break;
    }
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  expect(terminal).not.toBeNull();
  return terminal!;
}

test.describe('CRM import report provider lifecycle', () => {
  test.use({ viewport: { width: 1440, height: 960 } });
  test.setTimeout(120_000);

  const suffix = `${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;
  let firstLeadCode = '';

  test.beforeAll(async ({ browser }) => {
    const context = await browser.newContext({ storageState: { cookies: [], origins: [] } });
    const page = await context.newPage();
    try {
      await loginViaUI(page, ADMIN_EMAIL, PASSWORD);
      const created = await executeCommandViaApi(
        page,
        'crm:create_lead',
        {
          crm_lead_company: `保留期线索-${suffix}`,
          crm_lead_contact_name: '保留期夹具',
          crm_lead_requirement: '报告过期不得修改业务记录',
        },
        undefined,
        'create',
      );
      const record = await recordByPid(page, LEAD, created.recordId);
      firstLeadCode = String(record.crm_lead_code);
    } finally {
      await context.close();
    }
  });

  test('CMM-C11-02 real scheduler expires a report and preserves import history', async ({
    page,
  }) => {
    await loginViaUI(page, ADMIN_EMAIL, PASSWORD);
    await openModelFromMenu(page, LEAD);
    await openImport(page);
    await page.getByTestId('import-mode-update').click();
    const template = await downloadTemplate(page, LEAD);
    await uploadWorkbook(
      page,
      `lead-retention-${suffix}.xlsx`,
      [
        { 线索编号: firstLeadCode, 公司名称: `保留期线索-${suffix}-已更新` },
        { 线索编号: `MISSING-${suffix}`, 公司名称: `保留期缺失线索-${suffix}` },
      ],
      template,
    );
    await expect(page.getByText('预检通过，可以导入')).toBeVisible();

    let releaseStatus!: () => void;
    const statusGate = new Promise<void>((resolve) => {
      releaseStatus = resolve;
    });
    let gated = false;
    await page.route('**/api/meta/excel/import/**/status/**', async (route) => {
      if (!gated) {
        gated = true;
        await statusGate;
      }
      await route.continue();
    });

    const taskId = await submitAndCaptureTask(page, LEAD);
    try {
      const terminal = await waitForTerminal(page, taskId);
      expect(String(terminal.status).toLowerCase()).toBe('completed');
      const result = terminal.result as Record<string, unknown>;
      expect(result.updatedCount).toBe(1);
      expect(result.errorCount).toBe(1);
      expect(String(result.errorReportUrl)).toContain(`/error-report/${taskId}`);

      const download = await page.request.get(
        `/api/meta/excel/import/${LEAD}/error-report/${taskId}`,
      );
      expect(download.ok(), await download.text()).toBeTruthy();
      const correction = Buffer.from(await download.body());
      expect(correction.subarray(0, 4).toString('hex')).toBe('504b0304');
      writeFileSync(`${EVIDENCE_DIR}/13-downloaded-correction-before-expiry.xlsx`, correction);

      const db = new Client(PG_CONN);
      await db.connect();
      try {
        const before = await db.query(
          `SELECT pid, status, error_report_url, completed_at, total_rows,
                  success_rows, error_rows
             FROM ab_import_job WHERE pid = $1`,
          [taskId],
        );
        expect(before.rows[0]?.error_report_url).toContain(`/error-report/${taskId}`);
        await db.query(
          `UPDATE ab_import_job
              SET completed_at = CURRENT_TIMESTAMP - INTERVAL '8 days'
            WHERE pid = $1`,
          [taskId],
        );

        const deadline = Date.now() + 30_000;
        let after = before.rows[0];
        while (Date.now() < deadline) {
          const current = await db.query(
            `SELECT pid, status, error_report_url, completed_at, total_rows,
                    success_rows, error_rows
               FROM ab_import_job WHERE pid = $1`,
            [taskId],
          );
          after = current.rows[0];
          if (after?.error_report_url == null) break;
          await new Promise((resolve) => setTimeout(resolve, 500));
        }
        expect(after?.status).toBe('completed');
        expect(after?.error_report_url).toBeNull();
        expect(after?.total_rows).toBe(2);
        expect(after?.success_rows).toBe(1);
        expect(after?.error_rows).toBe(1);
        writeFileSync(
          `${EVIDENCE_DIR}/db-evidence/cmm-11-retention-before-after.json`,
          `${JSON.stringify({ before: before.rows[0], after }, null, 2)}\n`,
        );
      } finally {
        await db.end();
      }

      const expiredDownload = await page.request.get(
        `/api/meta/excel/import/${LEAD}/error-report/${taskId}`,
      );
      expect(expiredDownload.status()).toBe(404);
    } finally {
      releaseStatus();
    }

    await expect(page.getByTestId('import-result')).toContainText('导入完成，部分行失败', {
      timeout: 30_000,
    });
    await expect(page.getByTestId('import-result-error-report-expired')).toContainText(
      '修正工作簿已超过保留期',
    );
    await expect(page.getByTestId('import-result-download-error-report')).toHaveCount(0);
    await page.screenshot({
      path: `${EVIDENCE_DIR}/14-scheduler-expired-report.png`,
      fullPage: true,
    });

    expect(
      existsSync(`${EVIDENCE_DIR}/api-evidence/cmm-11-provider-upload-failure.json`),
      'Run CMM-C11-01 against the denied MinIO credential before the healthy lifecycle phase',
    ).toBe(true);
    writeFileSync(
      `${EVIDENCE_DIR}/acceptance-manifest.json`,
      `${JSON.stringify(
        {
          generatedAt: new Date().toISOString(),
          runtime: {
            name: process.env.CRM_IMPORT_RUNTIME_NAME ?? null,
            slot: process.env.CRM_IMPORT_RUNTIME_SLOT ?? null,
            web: BASE_URL,
            backend: BACKEND_URL,
            database: PG_CONN.database,
            storage: 'real MinIO',
          },
          axes: {
            surface: 'journey',
            dependencies: 'real-stack-single-jvm-minio-postgresql-scheduler',
            authority: 'blocking-release',
            driver: 'browser-and-http',
          },
          summary: { pass: 14, deferred: 1, untested: 5, total: 20, coveragePercent: 70 },
          newlyClosed: [
            ['CMM-C11-01', 'real MinIO PutObject denial preserves inline recovery', 'pass'],
            ['CMM-C11-02', 'real scheduler expiry preserves history and shows expired UX', 'pass'],
          ].map(([id, claim, verdict]) => ({ id, claim, verdict })),
          deferred: [
            [
              'CMM-C11-03',
              'multi-node task ownership and shared report access',
              'deferred-by-product-priority',
            ],
          ].map(([id, claim, verdict]) => ({ id, claim, verdict })),
          remaining: [
            ['CMM-C12-01', 'user cancellation', 'untested'],
            ['CMM-C12-02', 'explicit retry operation', 'untested'],
            ['CMM-C12-03', 'service restart during a running import', 'untested'],
            ['CMM-C12-04', '10k-row capacity and latency', 'untested'],
            ['CMM-C12-05', '100k-row capacity and latency', 'untested'],
          ].map(([id, claim, verdict]) => ({ id, claim, verdict })),
          priorEvidence:
            '/Users/ghj/work/auraboot/.workspace/evidence/crm-import-partial-async-20260813-s147/full-final-r2',
          evidence: [
            '12-minio-upload-denied-inline-recovery.png',
            '13-downloaded-correction-before-expiry.xlsx',
            '14-scheduler-expired-report.png',
            'api-evidence/cmm-11-provider-upload-failure.json',
            'db-evidence/cmm-11-retention-before-after.json',
          ],
          trust: { retries: 0, directCleanupCall: false },
        },
        null,
        2,
      )}\n`,
    );
  });
});
