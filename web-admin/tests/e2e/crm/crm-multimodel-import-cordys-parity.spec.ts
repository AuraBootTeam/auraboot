import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { test, expect, type Page } from '../../fixtures';
import { read as xlsxRead, utils as XLSXUtils, write as xlsxWrite } from 'xlsx';
import { executeCommandViaApi, queryFilteredList } from '../helpers';
import { loginViaUI } from '../../helpers/wd-fixtures';

const ADMIN_EMAIL = 'admin@auraboot.com';
const PASSWORD = 'Test2026x';
const VIEWER_EMAIL = 'crm-import-viewer@e2e.local';
const LEAD = 'crm_lead_common';
const CONTACT = 'crm_contact_common';
const ACCOUNT = 'crm_account_common';
const OPPORTUNITY = 'crm_opportunity_common';
const EVIDENCE_DIR =
  process.env.CRM_IMPORT_EVIDENCE_DIR ||
  '/Users/ghj/work/auraboot/.workspace/evidence/crm-multimodel-import-20260813-s143';

mkdirSync(EVIDENCE_DIR, { recursive: true });

function workbook(rows: Array<Record<string, unknown>>, template: Buffer): Buffer {
  const book = xlsxRead(template, { type: 'buffer' });
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
  return xlsxWrite(book, { type: 'buffer', bookType: 'xlsx' }) as Buffer;
}

function templateHeaders(template: Buffer): string[] {
  const book = xlsxRead(template, { type: 'buffer' });
  const sheet = book.Sheets[book.SheetNames[0]];
  return (
    (XLSXUtils.sheet_to_json<unknown[]>(sheet, { header: 1, defval: '' })[0] || []) as unknown[]
  ).map(String);
}

function templateInstructions(template: Buffer): string {
  const book = xlsxRead(template, { type: 'buffer' });
  expect(book.SheetNames).toContain('填写说明');
  const rows = XLSXUtils.sheet_to_json<unknown[]>(book.Sheets['填写说明'], {
    header: 1,
    defval: '',
  });
  return rows.flat().map(String).join('\n');
}

async function openModelFromMenu(page: Page, model: string): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
  const nav = page.locator('nav, aside, [role="navigation"]').first();
  const link = nav.locator(`a[href="/p/${model}"]`).first();
  if (!(await link.isVisible().catch(() => false))) {
    await nav
      .getByRole('button', { name: /客户关系管理|crm/i })
      .first()
      .click();
  }
  if (!(await link.isVisible().catch(() => false))) {
    await nav
      .getByRole('button', { name: /业务档案|business records/i })
      .first()
      .click();
  }
  await expect(link).toBeVisible({ timeout: 10_000 });
  await link.click();
  await expect(page).toHaveURL(new RegExp(`/p/${model}(?:\\?.*)?$`), { timeout: 15_000 });
  await expect(page.getByTestId('toolbar-more-menu')).toBeVisible({ timeout: 15_000 });
}

async function openImport(page: Page): Promise<void> {
  await page.getByTestId('toolbar-more-menu').click();
  await page.getByTestId('more-menu-import').click();
  await expect(page.getByTestId('excel-import-dialog')).toBeVisible();
}

async function downloadTemplate(page: Page, model: string): Promise<Buffer> {
  const responsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'GET' &&
      response.url().includes(`/api/meta/excel/template/${model}?`),
  );
  await page.getByTestId('import-download-template').click();
  const response = await responsePromise;
  expect(response.ok(), await response.text()).toBeTruthy();
  expect(response.headers()['content-disposition']).toContain(`${model}-import-template.xlsx`);
  return Buffer.from(await response.body());
}

async function upload(
  page: Page,
  name: string,
  rows: Array<Record<string, unknown>>,
  template: Buffer,
  validationTimeout = 30_000,
): Promise<void> {
  const chooserPromise = page.waitForEvent('filechooser');
  await page.getByTestId('import-browse-file').click();
  const chooser = await chooserPromise;
  await chooser.setFiles({
    name,
    mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    buffer: workbook(rows, template),
  });
  await expect(page.getByTestId('import-validation-summary')).toBeVisible({
    timeout: validationTimeout,
  });
}

async function downloadCorrectionWorkbook(page: Page, model: string): Promise<Buffer> {
  const responsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'GET' &&
      response.url().includes(`/api/meta/excel/import/${model}/error-report/`),
  );
  const downloadPromise = page.waitForEvent('download');
  await page.getByTestId('import-download-error-report').click();
  const [response, download] = await Promise.all([responsePromise, downloadPromise]);
  expect(response.ok(), response.ok() ? undefined : await response.text()).toBeTruthy();
  expect(response.headers()['content-disposition']).toContain('import-errors.xlsx');
  expect(download.suggestedFilename()).toBe(`${model}-insert-import-errors.xlsx`);
  const downloadedPath = await download.path();
  expect(downloadedPath).not.toBeNull();
  const content = readFileSync(downloadedPath!);
  expect(content.subarray(0, 4).toString('hex')).toBe('504b0304');
  return content;
}

async function uploadCorrectionWorkbook(
  page: Page,
  name: string,
  buffer: Buffer,
): Promise<void> {
  const chooserPromise = page.waitForEvent('filechooser');
  await page.getByTestId('import-upload-correction').click();
  const chooser = await chooserPromise;
  await chooser.setFiles({
    name,
    mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    buffer,
  });
  await expect(page.getByTestId('import-validation-summary')).toBeVisible({ timeout: 30_000 });
}

function correctWorkbookValue(
  correction: Buffer,
  rowIndex: number,
  header: string,
  value: string,
): Buffer {
  const book = xlsxRead(correction, { type: 'buffer' });
  expect(book.SheetNames).toContain('Import errors');
  const sheet = book.Sheets[book.SheetNames[0]];
  const rows = XLSXUtils.sheet_to_json<Record<string, unknown>>(sheet, { defval: '' });
  expect(rows.length).toBeGreaterThan(rowIndex);
  const actualHeader = Object.keys(rows[0]).find((candidate) =>
    candidate.replace(/^\*\s+/, '').includes(header),
  );
  expect(actualHeader).toBeTruthy();
  rows[rowIndex][actualHeader!] = value;
  XLSXUtils.sheet_add_json(sheet, rows, {
    header: Object.keys(rows[0]),
    origin: 'A1',
    skipHeader: false,
  });
  return xlsxWrite(book, { type: 'buffer', bookType: 'xlsx' }) as Buffer;
}

async function submitAndExpect(
  page: Page,
  model: string,
  expected: { created: number; updated: number; total: number },
  timeout = 45_000,
): Promise<{ taskId: string }> {
  const importResponsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes(`/api/meta/excel/import/${model}?`),
  );
  await page.getByTestId('import-submit').click();
  const importResponse = await importResponsePromise;
  expect(importResponse.ok(), await importResponse.text()).toBeTruthy();
  const importBody = await importResponse.json();
  await expect(page.getByTestId('import-result')).toContainText('导入完成', { timeout });
  await expect(page.getByTestId('import-result-created')).toHaveText(String(expected.created));
  await expect(page.getByTestId('import-result-updated')).toHaveText(String(expected.updated));
  await expect(page.getByTestId('import-result-failed')).toHaveText('0');
  await expect(page.getByTestId('import-result-total')).toHaveText(String(expected.total));
  return { taskId: String(importBody?.data?.taskId ?? '') };
}

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

async function recordsByField(
  page: Page,
  model: string,
  field: string,
  value: string,
): Promise<Record<string, unknown>[]> {
  return queryFilteredList(page, model, field, value, {
    operator: 'EQ',
    pageSize: 20,
  });
}

async function filteredTotal(
  page: Page,
  model: string,
  field: string,
  searchText: string,
): Promise<number> {
  const filters = encodeURIComponent(
    JSON.stringify([{ fieldName: field, operator: 'LIKE', value: `%${searchText}%` }]),
  );
  const response = await page.request.get(
    `/api/dynamic/${model}/list?pageNum=1&pageSize=20&filters=${filters}`,
  );
  expect(response.ok(), await response.text()).toBeTruthy();
  const body = await response.json();
  return Number(body?.data?.total ?? 0);
}

test.describe('CRM multi-model Excel import — Cordys parity', () => {
  test.describe.configure({ mode: 'serial' });
  test.use({ viewport: { width: 1440, height: 960 } });
  test.setTimeout(90_000);

  const suffix = `${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;
  const accountName = `导入关联客户-${suffix}`;
  const duplicateAccountName = `重名客户-${suffix}`;
  const sourceLeadCompany = `来源线索-${suffix}`;
  const importedLeadCompany = `批量导入线索-${suffix}`;
  const updateOpportunityName = `待更新商机-${suffix}`;
  let accountPid = '';
  let accountCode = '';
  let sourceLeadPid = '';
  let sourceLeadCode = '';
  let updateOpportunityPid = '';
  let updateOpportunityCode = '';
  let importedLeadPid = '';
  let importedLeadCode = '';
  let leadTemplate: Buffer;
  let contactTemplate: Buffer;
  let opportunityTemplate: Buffer;

  test.beforeAll(async ({ browser }) => {
    const context = await browser.newContext({ storageState: { cookies: [], origins: [] } });
    const page = await context.newPage();
    try {
      await loginViaUI(page, ADMIN_EMAIL, PASSWORD);
      const account = await executeCommandViaApi(
        page,
        'crm:create_account',
        { crm_acc_name: accountName, crm_acc_industry: 'manufacturing' },
        undefined,
        'create',
      );
      accountPid = account.recordId;
      const accountRecord = await recordByPid(page, ACCOUNT, accountPid);
      accountCode = String(accountRecord.crm_acc_code);

      for (const marker of ['A', 'B']) {
        await executeCommandViaApi(
          page,
          'crm:create_account',
          {
            crm_acc_name: duplicateAccountName,
            crm_acc_industry: `manufacturing-${marker}`,
          },
          undefined,
          'create',
        );
      }

      const lead = await executeCommandViaApi(
        page,
        'crm:create_lead',
        {
          crm_lead_company: sourceLeadCompany,
          crm_lead_contact_name: '来源联系人',
          crm_lead_requirement: '用于商机业务键关联',
        },
        undefined,
        'create',
      );
      sourceLeadPid = lead.recordId;
      const leadRecord = await recordByPid(page, LEAD, sourceLeadPid);
      sourceLeadCode = String(leadRecord.crm_lead_code);

      const opportunity = await executeCommandViaApi(
        page,
        'crm:create_opportunity',
        {
          crm_opp_name: updateOpportunityName,
          crm_opp_account_id: accountPid,
          crm_opp_lead_id: sourceLeadPid,
          crm_opp_expected_amount: 88_000,
          crm_opp_currency_code: 'CNY',
          crm_opp_notes: '更新导入必须保留的备注',
        },
        undefined,
        'create',
      );
      updateOpportunityPid = opportunity.recordId;
      const opportunityRecord = await recordByPid(page, OPPORTUNITY, updateOpportunityPid);
      updateOpportunityCode = String(opportunityRecord.crm_opp_code);

      const probe = await page.request.post('/api/auth/login', {
        data: { email: VIEWER_EMAIL, password: PASSWORD },
      });
      if (!probe.ok()) {
        const provision = await page.request.post('/api/admin/users', {
          data: {
            email: VIEWER_EMAIL,
            displayName: 'CRM import viewer fixture',
            initialPassword: PASSWORD,
            roleCodes: ['crm_viewer'],
            sendInviteEmail: false,
          },
        });
        expect(provision.ok(), await provision.text()).toBeTruthy();
      }
    } finally {
      await context.close();
    }
  });

  test.beforeEach(async ({ page }) => {
    await loginViaUI(page, ADMIN_EMAIL, PASSWORD);
  });

  test('CMM-01 lead INSERT precheck creates command defaults', async ({ page }) => {
    await openModelFromMenu(page, LEAD);
    await openImport(page);
    leadTemplate = await downloadTemplate(page, LEAD);
    expect(templateHeaders(leadTemplate).some((header) => header.includes('公司名称'))).toBe(true);

    await upload(
      page,
      `lead-insert-${suffix}.xlsx`,
      [
        {
          公司名称: importedLeadCompany,
          联系人: '导入联系人',
          联系电话: '13800001111',
          需求描述: '更新导入的空单元格必须保留本字段',
        },
      ],
      leadTemplate,
    );
    await expect(page.getByText('预检通过，可以导入')).toBeVisible();
    await page.screenshot({ path: `${EVIDENCE_DIR}/01-lead-insert-precheck.png`, fullPage: true });
    await submitAndExpect(page, LEAD, { created: 1, updated: 0, total: 1 });

    const records = await recordsByField(page, LEAD, 'crm_lead_company', importedLeadCompany);
    expect(records).toHaveLength(1);
    importedLeadPid = String(records[0].pid);
    importedLeadCode = String(records[0].crm_lead_code);
    expect(importedLeadCode).toMatch(/^LEAD-\d{8}-\d+$/);
    expect(records[0].crm_lead_status).toBe('new');
  });

  test('CMM-02 lead UPDATE matches code and blank preserves existing value', async ({ page }) => {
    await openModelFromMenu(page, LEAD);
    await openImport(page);
    await page.getByTestId('import-mode-update').click();
    const updateTemplate = await downloadTemplate(page, LEAD);
    const updatedCompany = `${importedLeadCompany}-已更新`;
    await upload(
      page,
      `lead-update-${suffix}.xlsx`,
      [{ 线索编号: importedLeadCode, 公司名称: updatedCompany, 需求描述: '' }],
      updateTemplate,
    );
    await expect(page.getByText('预检通过，可以导入')).toBeVisible();
    await submitAndExpect(page, LEAD, { created: 0, updated: 1, total: 1 });
    const record = await recordByPid(page, LEAD, importedLeadPid);
    expect(record.crm_lead_company).toBe(updatedCompany);
    expect(record.crm_lead_requirement).toBe('更新导入的空单元格必须保留本字段');
  });

  test('CMM-03 contact template documents business keys; code and PID both resolve', async ({
    page,
  }) => {
    await openModelFromMenu(page, CONTACT);
    await openImport(page);
    contactTemplate = await downloadTemplate(page, CONTACT);
    const instructions = templateInstructions(contactTemplate);
    expect(instructions).toContain('所属客户');
    expect(instructions).toContain('crm_acc_code');
    expect(instructions).toContain('crm_acc_name');
    expect(instructions).toContain('PID');

    const codeContact = `编码关联联系人-${suffix}`;
    await upload(
      page,
      `contact-account-code-${suffix}.xlsx`,
      [{ 所属客户: accountCode, 联系人姓名: codeContact, 职位: '采购经理' }],
      contactTemplate,
    );
    await expect(page.getByText('预检通过，可以导入')).toBeVisible();
    await page.screenshot({ path: `${EVIDENCE_DIR}/02-contact-code-precheck.png`, fullPage: true });
    await submitAndExpect(page, CONTACT, { created: 1, updated: 0, total: 1 });
    const codeRecords = await recordsByField(page, CONTACT, 'crm_ct_name', codeContact);
    expect(codeRecords).toHaveLength(1);
    expect(codeRecords[0].crm_ct_account_id).toBe(accountPid);

    await openModelFromMenu(page, CONTACT);
    await openImport(page);
    const pidContact = `PID关联联系人-${suffix}`;
    await upload(
      page,
      `contact-account-pid-${suffix}.xlsx`,
      [{ 所属客户: accountPid, 联系人姓名: pidContact, 职位: '技术负责人' }],
      contactTemplate,
    );
    await expect(page.getByText('预检通过，可以导入')).toBeVisible();
    await submitAndExpect(page, CONTACT, { created: 1, updated: 0, total: 1 });
    const pidRecords = await recordsByField(page, CONTACT, 'crm_ct_name', pidContact);
    expect(pidRecords).toHaveLength(1);
    expect(pidRecords[0].crm_ct_account_id).toBe(accountPid);
  });

  test('CMM-04 failed precheck downloads, corrects, and re-uploads all pending rows', async ({
    page,
  }) => {
    const validContactName = `待保留有效联系人-${suffix}`;
    const invalidContactName = `待修正关联联系人-${suffix}`;
    expect(await recordsByField(page, CONTACT, 'crm_ct_name', validContactName)).toHaveLength(0);
    expect(await recordsByField(page, CONTACT, 'crm_ct_name', invalidContactName)).toHaveLength(0);
    await openModelFromMenu(page, CONTACT);
    await openImport(page);
    await upload(
      page,
      `contact-missing-account-${suffix}.xlsx`,
      [
        { 所属客户: accountCode, 联系人姓名: validContactName },
        { 所属客户: `MISSING-${suffix}`, 联系人姓名: invalidContactName },
      ],
      contactTemplate,
    );
    await expect(page.getByText('预检未通过，请修正文件')).toBeVisible();
    await expect(page.getByTestId('import-submit')).toBeDisabled();
    await expect(page.getByTestId('import-validation-summary')).toContainText(
      '关联记录不存在或无权访问',
    );
    await expect(page.getByTestId('import-download-error-report')).toBeVisible();
    await expect(page.getByTestId('import-upload-correction')).toBeVisible();
    await page.screenshot({
      path: `${EVIDENCE_DIR}/03-contact-correction-offered.png`,
      fullPage: true,
    });
    expect(await recordsByField(page, CONTACT, 'crm_ct_name', validContactName)).toHaveLength(0);
    expect(await recordsByField(page, CONTACT, 'crm_ct_name', invalidContactName)).toHaveLength(0);

    const correction = await downloadCorrectionWorkbook(page, CONTACT);
    writeFileSync(`${EVIDENCE_DIR}/04-contact-correction-original.xlsx`, correction);
    const correctionBook = xlsxRead(correction, { type: 'buffer' });
    const correctionRows = XLSXUtils.sheet_to_json<Record<string, unknown>>(
      correctionBook.Sheets[correctionBook.SheetNames[0]],
      { defval: '' },
    );
    expect(correctionRows).toHaveLength(2);
    expect(JSON.stringify(correctionRows)).toContain(validContactName);
    expect(JSON.stringify(correctionRows)).toContain(invalidContactName);
    const detailRows = XLSXUtils.sheet_to_json<Record<string, unknown>>(
      correctionBook.Sheets['Import errors'],
      { defval: '' },
    );
    expect(JSON.stringify(detailRows)).toContain('关联记录不存在或无权访问');

    const corrected = correctWorkbookValue(correction, 1, '所属客户', accountCode);
    writeFileSync(`${EVIDENCE_DIR}/04-contact-correction-fixed.xlsx`, corrected);
    await uploadCorrectionWorkbook(
      page,
      `contact-corrected-${suffix}.xlsx`,
      corrected,
    );
    await expect(page.getByText('预检通过，可以导入')).toBeVisible();
    await page.screenshot({
      path: `${EVIDENCE_DIR}/04-contact-correction-passed.png`,
      fullPage: true,
    });
    await submitAndExpect(page, CONTACT, { created: 2, updated: 0, total: 2 });
    expect(await recordsByField(page, CONTACT, 'crm_ct_name', validContactName)).toHaveLength(1);
    expect(await recordsByField(page, CONTACT, 'crm_ct_name', invalidContactName)).toHaveLength(1);
  });

  test('CMM-05 ambiguous account name blocks contact with zero writes', async ({ page }) => {
    const contactName = `重名客户联系人-${suffix}`;
    expect(await recordsByField(page, CONTACT, 'crm_ct_name', contactName)).toHaveLength(0);
    await openModelFromMenu(page, CONTACT);
    await openImport(page);
    await upload(
      page,
      `contact-ambiguous-account-${suffix}.xlsx`,
      [{ 所属客户: duplicateAccountName, 联系人姓名: contactName }],
      contactTemplate,
    );
    await expect(page.getByText('预检未通过，请修正文件')).toBeVisible();
    await expect(page.getByTestId('import-submit')).toBeDisabled();
    await expect(page.getByTestId('import-validation-summary')).toContainText(
      '关联值不唯一，请改用唯一业务编码或 PID',
    );
    await page.screenshot({ path: `${EVIDENCE_DIR}/05-contact-ambiguous-blocked.png`, fullPage: true });
    expect(await recordsByField(page, CONTACT, 'crm_ct_name', contactName)).toHaveLength(0);
  });

  test('CMM-06 opportunity resolves account and source lead business codes', async ({ page }) => {
    await openModelFromMenu(page, OPPORTUNITY);
    await openImport(page);
    opportunityTemplate = await downloadTemplate(page, OPPORTUNITY);
    const instructions = templateInstructions(opportunityTemplate);
    expect(instructions).toContain('crm_acc_code');
    expect(instructions).toContain('crm_lead_code');
    const opportunityName = `业务键关联商机-${suffix}`;
    await upload(
      page,
      `opportunity-business-keys-${suffix}.xlsx`,
      [
        {
          商机名称: opportunityName,
          关联客户: accountCode,
          来源线索: sourceLeadCode,
          货币: 'CNY',
          预期金额: 168000,
          备注: '通过客户编号和线索编号导入',
        },
      ],
      opportunityTemplate,
    );
    await expect(page.getByText('预检通过，可以导入')).toBeVisible();
    await submitAndExpect(page, OPPORTUNITY, { created: 1, updated: 0, total: 1 });
    await page.screenshot({ path: `${EVIDENCE_DIR}/06-opportunity-result.png`, fullPage: true });
    const records = await recordsByField(page, OPPORTUNITY, 'crm_opp_name', opportunityName);
    expect(records).toHaveLength(1);
    expect(records[0].crm_opp_account_id).toBe(accountPid);
    expect(records[0].crm_opp_lead_id).toBe(sourceLeadPid);
    expect(records[0].crm_opp_stage).toBe('discovery');
  });

  test('CMM-07 opportunity UPDATE matches code and blank preserves notes', async ({ page }) => {
    await openModelFromMenu(page, OPPORTUNITY);
    await openImport(page);
    await page.getByTestId('import-mode-update').click();
    const updateTemplate = await downloadTemplate(page, OPPORTUNITY);
    const updatedName = `${updateOpportunityName}-已更新`;
    await upload(
      page,
      `opportunity-update-${suffix}.xlsx`,
      [{ 商机编号: updateOpportunityCode, 商机名称: updatedName, 备注: '' }],
      updateTemplate,
    );
    await expect(page.getByText('预检通过，可以导入')).toBeVisible();
    await submitAndExpect(page, OPPORTUNITY, { created: 0, updated: 1, total: 1 });
    const record = await recordByPid(page, OPPORTUNITY, updateOpportunityPid);
    expect(record.crm_opp_name).toBe(updatedName);
    expect(record.crm_opp_notes).toBe('更新导入必须保留的备注');
  });

  test('CMM-08 viewer has no import entry and backend rejects validation', async ({ browser }) => {
    const context = await browser.newContext({ storageState: { cookies: [], origins: [] } });
    const page = await context.newPage();
    try {
      await loginViaUI(page, VIEWER_EMAIL, PASSWORD);
      await openModelFromMenu(page, CONTACT);
      await page.getByTestId('toolbar-more-menu').click();
      await expect(page.getByTestId('more-menu-import')).toHaveCount(0);
      const response = await page.request.post(`/api/meta/excel/validate/${CONTACT}?mode=insert`, {
        multipart: {
          file: {
            name: `viewer-denied-${suffix}.xlsx`,
            mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
            buffer: workbook(
              [{ 所属客户: accountCode, 联系人姓名: `越权联系人-${suffix}` }],
              contactTemplate,
            ),
          },
        },
      });
      expect(response.status()).toBe(403);
      await page.screenshot({ path: `${EVIDENCE_DIR}/07-viewer-no-import.png`, fullPage: true });
    } finally {
      await context.close();
    }
  });

  test('CMM-09 2000-row lead import completes durable job within 180s', async ({ page }) => {
    test.setTimeout(210_000);
    await openModelFromMenu(page, LEAD);
    await openImport(page);
    const bulkPrefix = `CMM-BULK-${suffix}`;
    const rows = Array.from({ length: 2000 }, (_, index) => ({
      公司名称: `${bulkPrefix}-${String(index + 1).padStart(4, '0')}`,
      联系人: `批量联系人-${index + 1}`,
      需求描述: 'Cordys 2000 行基准',
    }));
    const startedAt = Date.now();
    await upload(page, `lead-2000-${suffix}.xlsx`, rows, leadTemplate, 60_000);
    await expect(page.getByText('预检通过，可以导入')).toBeVisible();
    const { taskId } = await submitAndExpect(
      page,
      LEAD,
      { created: 2000, updated: 0, total: 2000 },
      180_000,
    );
    const elapsedMs = Date.now() - startedAt;
    expect(taskId).toMatch(/^[0-9A-HJKMNP-TV-Z]{26}$/);
    expect(elapsedMs).toBeLessThanOrEqual(180_000);
    const statusResponse = await page.request.get(`/api/meta/excel/import/${LEAD}/status/${taskId}`);
    expect(statusResponse.ok(), await statusResponse.text()).toBeTruthy();
    const statusBody = await statusResponse.json();
    expect(String(statusBody?.data?.status).toLowerCase()).toBe('completed');
    expect(statusBody?.data?.processedRows).toBe(2000);
    expect(statusBody?.data?.result?.createdCount).toBe(2000);
    expect(await filteredTotal(page, LEAD, 'crm_lead_company', bulkPrefix)).toBe(2000);
    await page.screenshot({ path: `${EVIDENCE_DIR}/08-lead-2000-result.png`, fullPage: true });
  });
});
