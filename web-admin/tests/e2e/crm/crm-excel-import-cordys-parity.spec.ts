import { mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { test, expect, type Page } from '../../fixtures';
import { read as xlsxRead, utils as XLSXUtils, write as xlsxWrite } from 'xlsx';
import { loginViaUI } from '../../helpers/wd-fixtures';

const MODEL = 'crm_account_common';
const EVIDENCE_DIR =
  process.env.CRM_IMPORT_EVIDENCE_DIR ||
  '/Users/ghj/work/auraboot/.workspace/evidence/crm-excel-import-cordys-20260813-s133-r1';
const SOURCE_IDS = [
  'api:customer:customer:download-import-tpl',
  'api:customer:customer:pre-check',
  'api:customer:customer:real-import',
] as const;
const completedSourceIds = new Set<string>();
const sourceScreenshots: string[] = [];

function workbook(rows: Array<Record<string, unknown>>, template?: Buffer): Buffer {
  const book = template ? xlsxRead(template, { type: 'buffer' }) : XLSXUtils.book_new();
  if (template) {
    const sheet = book.Sheets[book.SheetNames[0]];
    const headerRows = XLSXUtils.sheet_to_json<unknown[]>(sheet, { header: 1, defval: '' });
    const headers = (headerRows[0] || []).map(String);
    const actualHeader = new Map(headers.map((header) => [header.replace(/^\*\s+/, ''), header]));
    const filledRows = rows.map((row) =>
      Object.fromEntries(
        Object.entries(row).map(([key, value]) => [actualHeader.get(key) || key, value]),
      ),
    );
    XLSXUtils.sheet_add_json(sheet, filledRows, {
      header: headers,
      origin: 'A2',
      skipHeader: true,
    });
  } else {
    XLSXUtils.book_append_sheet(book, XLSXUtils.json_to_sheet(rows), 'Import');
  }
  return xlsxWrite(book, { type: 'buffer', bookType: 'xlsx' }) as Buffer;
}

function templateHeaders(template: Buffer): string[] {
  const book = xlsxRead(template, { type: 'buffer' });
  const sheet = book.Sheets[book.SheetNames[0]];
  return (
    (XLSXUtils.sheet_to_json<unknown[]>(sheet, { header: 1, defval: '' })[0] || []) as unknown[]
  ).map(String);
}

async function downloadTemplate(page: Page): Promise<Buffer> {
  const responsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'GET' &&
      response.url().includes(`/api/meta/excel/template/${MODEL}?`),
  );
  await page.getByTestId('import-download-template').click();
  const response = await responsePromise;
  expect(response.ok(), await response.text()).toBeTruthy();
  expect(response.headers()['content-disposition']).toContain('import-template.xlsx');
  // The managed Chromium does not emit a Playwright download event for a client-side
  // blob URL. The clicked product action and its exact authenticated XLSX response are
  // still authoritative, while BFF unit/byte-integrity checks cover binary passthrough.
  return Buffer.from(await response.body());
}

async function openImport(page: Page) {
  await page.getByTestId('toolbar-more-menu').click();
  await page.getByTestId('more-menu-import').click();
  await expect(page.getByTestId('excel-import-dialog')).toBeVisible();
}

async function upload(
  page: Page,
  name: string,
  rows: Array<Record<string, unknown>>,
  template?: Buffer,
) {
  const chooserPromise = page.waitForEvent('filechooser');
  await page.getByTestId('import-browse-file').click();
  const chooser = await chooserPromise;
  await chooser.setFiles({
    name,
    mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    buffer: workbook(rows, template),
  });
  await expect(page.getByTestId('import-validation-summary')).toBeVisible({ timeout: 15_000 });
}

async function findAccount(page: Page, name: string): Promise<Record<string, unknown>> {
  const response = await page.request.get(`/api/dynamic/${MODEL}/list?pageNum=1&pageSize=500`);
  expect(response.ok(), await response.text()).toBeTruthy();
  const body = await response.json();
  const records = body?.data?.records ?? body?.data?.list ?? body?.data ?? [];
  const record = records.find((item: Record<string, unknown>) => item.crm_acc_name === name);
  expect(record, `account ${name} should exist`).toBeTruthy();
  return record;
}

test.describe('CRM Excel import — Cordys parity slice', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(90_000);

  const suffix = `${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;
  const accountName = `Cordys Import ${suffix}`;
  const accountPids = new Set<string>();
  let accountPid = '';
  let insertTemplate: Buffer;
  let updateTemplate: Buffer;

  test.afterAll(async ({ browser }) => {
    const context = await browser.newContext({
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
    });
    try {
      for (const pid of accountPids) {
        const cleanup = await context.request.delete(`/api/dynamic/${MODEL}/${pid}`);
        expect(
          cleanup.ok(),
          `failed to clean CRM import fixture ${pid}: ${await cleanup.text()}`,
        ).toBeTruthy();
      }
    } finally {
      await context.close();
    }

    const evidenceRoot = process.env.AURA_EVIDENCE_ROOT || EVIDENCE_DIR;
    const sourceIds = SOURCE_IDS.map((sourceId) => ({
      sourceId,
      verdict: completedSourceIds.has(sourceId) ? 'pass' : 'untested',
    }));
    const verdict = sourceIds.every((item) => item.verdict === 'pass') ? 'pass' : 'fail';
    mkdirSync(evidenceRoot, { recursive: true });
    writeFileSync(
      path.join(evidenceRoot, `crm-account-import-${Date.now()}.json`),
      `${JSON.stringify(
        {
          schemaVersion: 1,
          runId: process.env.CRM_ACCOUNT_IMPORT_RUN_ID || `crm-account-import-${Date.now()}`,
          runtime: process.env.AURA_RUNTIME_NAME || null,
          verdict,
          technicalVerdict: verdict,
          dataMigration: 'not-required-development-stage',
          runner: { browser: 'chromium', workers: 1, retries: 0 },
          sourceIds,
          screenshots: sourceScreenshots,
          productOwnerScreenshotSignOff: 'pending-human-signature',
        },
        null,
        2,
      )}\n`,
    );
  });

  test('CEI-001 INSERT: precheck, command defaults, result and screenshot', async ({ page }) => {
    await page.goto(`/p/${MODEL}`, { waitUntil: 'domcontentloaded' });
    await expect(page.locator('table').first()).toBeVisible({ timeout: 15_000 });
    await openImport(page);
    await page.keyboard.press('Escape');
    await expect(page.getByTestId('excel-import-dialog')).toBeHidden();
    await openImport(page);
    await page.getByTestId('excel-import-backdrop').click({ position: { x: 8, y: 8 } });
    await expect(page.getByTestId('excel-import-dialog')).toBeHidden();
    await openImport(page);

    insertTemplate = await downloadTemplate(page);
    completedSourceIds.add('api:customer:customer:download-import-tpl');
    const headers = templateHeaders(insertTemplate);
    expect(headers.some((header) => header.includes('客户名称'))).toBeTruthy();
    expect(headers.some((header) => header.includes('crm_acc_'))).toBeFalsy();

    await upload(
      page,
      `crm-account-insert-${suffix}.xlsx`,
      [
        {
          客户名称: accountName,
          行业: 'manufacturing',
          网站: 'https://before.example.test',
          电话: '021-55550001',
          备注: 'Cordys parity insert',
        },
      ],
      insertTemplate,
    );

    await expect(page.getByText('预检通过，可以导入')).toBeVisible();
    completedSourceIds.add('api:customer:customer:pre-check');
    await expect(page.getByTestId('import-submit')).toBeEnabled();
    await page.screenshot({
      path: `${EVIDENCE_DIR}/01-insert-precheck.png`,
      fullPage: true,
    });
    sourceScreenshots.push(`${EVIDENCE_DIR}/01-insert-precheck.png`);

    await page.getByTestId('import-submit').click();
    const result = page.getByTestId('import-result');
    await expect(result).toContainText('导入完成', { timeout: 20_000 });
    await expect(page.getByTestId('import-result-created')).toHaveText('1');
    await expect(page.getByTestId('import-result-failed')).toHaveText('0');
    await page.screenshot({ path: `${EVIDENCE_DIR}/02-insert-result.png`, fullPage: true });
    sourceScreenshots.push(`${EVIDENCE_DIR}/02-insert-result.png`);

    const record = await findAccount(page, accountName);
    accountPid = String(record.pid);
    accountPids.add(accountPid);
    expect(String(record.crm_acc_code)).toMatch(/^ACC-\d{8}-\d+$/);
    expect(record.crm_acc_status).toBe('active');
    expect(record.crm_acc_website).toBe('https://before.example.test');
    completedSourceIds.add('api:customer:customer:real-import');
  });

  test('CEI-002 UPDATE: code match, no create, blank cell preserves value', async ({ page }) => {
    const before = await findAccount(page, accountName);
    const accountCode = String(before.crm_acc_code);
    expect(accountCode).toBeTruthy();

    await page.goto(`/p/${MODEL}`, { waitUntil: 'domcontentloaded' });
    await expect(page.locator('table').first()).toBeVisible({ timeout: 15_000 });
    await openImport(page);
    await page.getByTestId('import-mode-update').click();
    updateTemplate = await downloadTemplate(page);

    await upload(
      page,
      `crm-account-update-${suffix}.xlsx`,
      [
        {
          客户编号: accountCode,
          客户名称: `${accountName} Updated`,
          网站: '',
          电话: '021-55559999',
        },
      ],
      updateTemplate,
    );

    await expect(page.getByText('预检通过，可以导入')).toBeVisible();
    await page.getByTestId('import-submit').click();
    const result = page.getByTestId('import-result');
    await expect(result).toContainText('导入完成', { timeout: 20_000 });
    await expect(page.getByTestId('import-result-updated')).toHaveText('1');
    await expect(page.getByTestId('import-result-created')).toHaveText('0');
    await page.screenshot({ path: `${EVIDENCE_DIR}/03-update-result.png`, fullPage: true });

    const response = await page.request.get(`/api/dynamic/${MODEL}/${accountPid}`);
    expect(response.ok()).toBeTruthy();
    const body = await response.json();
    const record = body?.data ?? body;
    expect(record.crm_acc_name).toBe(`${accountName} Updated`);
    expect(record.crm_acc_phone).toBe('021-55559999');
    expect(record.crm_acc_website).toBe('https://before.example.test');
  });

  test('CEI-003 invalid INSERT: required error blocks submission', async ({ page }) => {
    await page.goto(`/p/${MODEL}`, { waitUntil: 'domcontentloaded' });
    await expect(page.locator('table').first()).toBeVisible({ timeout: 15_000 });
    await openImport(page);

    await upload(
      page,
      `crm-account-invalid-${suffix}.xlsx`,
      [{ 客户名称: '', 电话: '021-00000000' }],
      insertTemplate,
    );

    await expect(page.getByText('预检未通过，请修正文件')).toBeVisible();
    await expect(page.getByTestId('import-submit')).toBeDisabled();
    await expect(page.getByTestId('import-validation-summary').getByText(/客户名称/)).toBeVisible();
    await expect(page.getByTestId('import-validation-summary')).not.toContainText('crm_acc_name');
    await page.screenshot({ path: `${EVIDENCE_DIR}/04-invalid-blocked.png`, fullPage: true });
  });

  test('CEI-004 viewer: entry hidden and backend validation forbidden', async ({
    page: adminPage,
    browser,
  }) => {
    // Intentional reusable test fixture: the admin role can provision users but cannot
    // delete tenant members (member_management), so unique-per-run users would leak.
    const viewerEmail = 'crm-import-viewer@e2e.local';
    const password = 'Test2026x';
    const loginProbe = await adminPage.request.post('/api/auth/login', {
      data: { email: viewerEmail, password },
    });
    if (!loginProbe.ok()) {
      const provision = await adminPage.request.post('/api/admin/users', {
        data: {
          email: viewerEmail,
          displayName: 'CRM import viewer fixture',
          initialPassword: password,
          roleCodes: ['crm_viewer'],
          sendInviteEmail: false,
        },
      });
      expect(provision.ok(), await provision.text()).toBeTruthy();
    }

    const context = await browser.newContext({ storageState: { cookies: [], origins: [] } });
    const page = await context.newPage();
    try {
      await loginViaUI(page, viewerEmail, password);
      await page.goto(`/p/${MODEL}`, { waitUntil: 'domcontentloaded' });
      await expect(page.locator('table').first()).toBeVisible({ timeout: 15_000 });
      await page.getByTestId('toolbar-more-menu').click();
      await expect(page.getByTestId('more-menu-import')).toHaveCount(0);

      const response = await page.request.post(`/api/meta/excel/validate/${MODEL}?mode=insert`, {
        multipart: {
          file: {
            name: `viewer-denied-${suffix}.xlsx`,
            mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
            buffer: workbook([{ 客户名称: `Denied ${suffix}` }], insertTemplate),
          },
        },
      });
      expect(response.status()).toBe(403);
      await page.screenshot({ path: `${EVIDENCE_DIR}/05-viewer-no-import.png`, fullPage: true });
    } finally {
      await context.close();
    }
  });

  test('CEI-005 small INSERT stays synchronous and returns all rows', async ({ page }) => {
    const synchronousNames = [1, 2, 3].map((index) => `Cordys Sync ${suffix}-${index}`);

    await page.goto(`/p/${MODEL}`, { waitUntil: 'domcontentloaded' });
    await expect(page.locator('table').first()).toBeVisible({ timeout: 15_000 });
    await openImport(page);
    await upload(
      page,
      `crm-account-sync-${suffix}.xlsx`,
      synchronousNames.map((name, index) => ({
        客户名称: name,
        行业: 'manufacturing',
        电话: `021-5555010${index}`,
      })),
      insertTemplate,
    );

    await expect(page.getByText('预检通过，可以导入')).toBeVisible();
    const importResponsePromise = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        response.url().includes(`/api/meta/excel/import/${MODEL}?`),
    );
    await page.getByTestId('import-submit').click();
    const importResponse = await importResponsePromise;
    expect(importResponse.ok(), await importResponse.text()).toBeTruthy();
    const importBody = await importResponse.json();
    const taskId = String(importBody?.data?.taskId ?? '');
    expect(taskId).toBe('');
    expect(importBody?.data?.asyncTask).toBe(false);

    const result = page.getByTestId('import-result');
    await expect(result).toContainText('导入完成', { timeout: 30_000 });
    await expect(page.getByTestId('import-result-created')).toHaveText('3');
    await expect(page.getByTestId('import-result-failed')).toHaveText('0');
    await expect(page.getByTestId('import-result-total')).toHaveText('3');

    await page.screenshot({ path: `${EVIDENCE_DIR}/06-sync-result.png`, fullPage: true });

    for (const name of synchronousNames) {
      const record = await findAccount(page, name);
      accountPids.add(String(record.pid));
      expect(String(record.crm_acc_code)).toMatch(/^ACC-\d{8}-\d+$/);
      expect(record.crm_acc_status).toBe('active');
    }
  });
});
