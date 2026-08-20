import { mkdirSync, writeFileSync } from 'node:fs';
import { Client } from 'pg';
import { test, expect } from '../../fixtures';
import { loginViaUI } from '../../helpers/wd-fixtures';
import { PG_CONN } from '../../helpers/environments';
import {
  ADMIN_EMAIL,
  CONTACT,
  PASSWORD,
  downloadTemplate,
  openImport,
  openModelFromMenu,
  uploadWorkbook,
} from './crm-import-lifecycle-helpers';

const EVIDENCE_DIR =
  process.env.CRM_IMPORT_EVIDENCE_DIR ||
  '/Users/ghj/work/auraboot/.workspace/evidence/crm-import-provider-lifecycle-20260813-s148';

mkdirSync(EVIDENCE_DIR, { recursive: true });
mkdirSync(`${EVIDENCE_DIR}/api-evidence`, { recursive: true });

test.describe('CRM import correction storage provider failure', () => {
  test.use({ viewport: { width: 1440, height: 960 } });
  test.setTimeout(90_000);

  test('CMM-C11-01 real MinIO PutObject denial keeps inline errors and zero report rows', async ({
    page,
  }) => {
    const suffix = `${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;
    const fileName = `contact-provider-denied-${suffix}.xlsx`;
    await loginViaUI(page, ADMIN_EMAIL, PASSWORD);
    await openModelFromMenu(page, CONTACT);
    await openImport(page);
    const template = await downloadTemplate(page, CONTACT);
    const validationResponsePromise = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        response.url().includes(`/api/meta/excel/validate/${CONTACT}?`),
    );

    await uploadWorkbook(
      page,
      fileName,
      [{ 所属客户: `MISSING-${suffix}`, 联系人姓名: `存储故障联系人-${suffix}` }],
      template,
    );
    const validationResponse = await validationResponsePromise;
    expect(validationResponse.ok(), await validationResponse.text()).toBeTruthy();
    const validationBody = await validationResponse.json();

    expect(validationBody?.data?.valid).toBe(false);
    expect(validationBody?.data?.errorReportFailed).toBe(true);
    expect(validationBody?.data?.errorReportUrl ?? null).toBeNull();
    await expect(page.getByText('预检未通过，请修正文件')).toBeVisible();
    await expect(page.getByTestId('import-error-report-unavailable')).toContainText(
      '修正工作簿暂时无法生成',
    );
    await expect(page.getByTestId('import-download-error-report')).toHaveCount(0);

    const db = new Client(PG_CONN);
    await db.connect();
    try {
      const jobs = await db.query<{ count: string }>(
        'SELECT count(*)::text AS count FROM ab_import_job WHERE file_name = $1',
        [fileName],
      );
      expect(Number(jobs.rows[0]?.count ?? -1)).toBe(0);
    } finally {
      await db.end();
    }

    writeFileSync(
      `${EVIDENCE_DIR}/api-evidence/cmm-11-provider-upload-failure.json`,
      `${JSON.stringify(validationBody, null, 2)}\n`,
    );
    await page.screenshot({
      path: `${EVIDENCE_DIR}/12-minio-upload-denied-inline-recovery.png`,
      fullPage: true,
    });
  });
});
