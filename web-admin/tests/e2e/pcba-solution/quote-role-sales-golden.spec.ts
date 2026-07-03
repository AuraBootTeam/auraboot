import fs from 'node:fs';
import path from 'node:path';
import * as XLSX from 'xlsx';
import type { Browser, BrowserContext, Response } from '@playwright/test';
import { test, expect, type Page } from '../../fixtures';
import { ensureSidebarExpanded } from '../helpers';
import { loginViaUI } from '../../helpers/wd-fixtures';
import {
  createCorrectedBomWorkbook,
  executeCommand,
  isTransientViteDynamicImportIssue,
  openQuoteCreateFormFromList,
  openQuoteDetailFromList,
  openQuoteRolePage,
  queryDynamicRecords,
  readDynamicRecord,
  QUOTE_ROLE_TEST_PASSWORD,
  type CreatedRows,
  type QuoteRoleUser,
} from './quote-e2e-helpers';

/**
 * Quote full-chain deep golden AS THE SALES ROLE (qo_sales), not admin.
 *
 * Drives the sales person's real day: create customer + BOM project (own data, self-scope),
 * create a quote from the UI form (customer/project reference dropdowns + corrected-BOM
 * upload), record a manual price through the review drawer on the BOM price tab, roll up
 * cost, then generate and download the quote Excel and parse the workbook (3 sheets, no
 * broken formulas, no raw field codes). A zero-401/403 collector runs across the whole
 * session ("管理员能用 ≠ 系统能用", DDR-2026-06-29 §8).
 *
 * RUN (local host-first stack, business roles reconciled):
 *   PLAYWRIGHT_BASE_URL=http://127.0.0.1:<web> BACKEND_URL=http://127.0.0.1:<be> PW_SKIP_WEBSERVER=1 \
 *     node_modules/.bin/playwright test tests/e2e/pcba-solution/quote-role-sales-golden.spec.ts \
 *     --project=chromium --no-deps
 */

const ADMIN_EMAIL = process.env.ADMIN_EMAIL || 'admin@auraboot.com';
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || 'Test2026x';

const SALES_USER: QuoteRoleUser = {
  key: 'smoke_sales',
  email: 'smoke-sales@e2e.local',
  displayName: 'Smoke Sales',
  password: QUOTE_ROLE_TEST_PASSWORD,
  roleCodes: ['qo_sales'],
};

const MANUAL_UNIT_PRICE = 1.2345;
const MANUAL_SUPPLIER = 'Smoke Manual Supplier';
const MANUAL_REASON = 'sales role golden manual adoption';
const MANUAL_VALID_UNTIL = '2026-12-31';

type ForbiddenHit = { step: string; url: string; status: number };

function sheetRows(workbook: XLSX.WorkBook, sheetName: string): unknown[][] {
  const sheet = workbook.Sheets[sheetName];
  expect(sheet, `sheet ${sheetName} exists`).toBeTruthy();
  return XLSX.utils.sheet_to_json(sheet, { header: 1, raw: false, defval: '' }) as unknown[][];
}

function validateQuoteWorkbook(filePath: string): void {
  const workbook = XLSX.read(fs.readFileSync(filePath), { type: 'buffer', cellText: false, sheetStubs: true });
  expect(workbook.SheetNames).toEqual(['报价单', 'BOM明细', '加工明细']);
  const bomRows = sheetRows(workbook, 'BOM明细');
  expect(bomRows.length, 'BOM 明细 has header + imported lines').toBeGreaterThanOrEqual(3);
  for (const sheetName of workbook.SheetNames) {
    const sheet = workbook.Sheets[sheetName];
    for (const key of Object.keys(sheet)) {
      if (key.startsWith('!')) continue;
      const value = sheet[key] as { v?: unknown; f?: string };
      const text = `${value.v ?? ''}${value.f ?? ''}`;
      expect(text, `${sheetName}!${key} has no broken formula`).not.toMatch(/#REF!|#DIV\/0!|#VALUE!/);
    }
  }
  const flat = JSON.stringify(bomRows.slice(0, 4));
  expect(flat, 'no raw qo_* field codes leak into the workbook').not.toMatch(/qo_(quote|ql|pe)_[a-z_]+/);
}

async function pickReferenceOption(page: Page, triggerTestId: string, value: string): Promise<void> {
  const trigger = page.getByTestId(triggerTestId);
  await expect(trigger).toBeVisible({ timeout: 15_000 });
  await trigger.click();
  const option = page.locator(`[role="option"][data-value="${value}"]`).first();
  await expect(option, `${triggerTestId} option ${value} should be loaded`).toBeVisible({ timeout: 15_000 });
  await option.click();
}

test.describe('Quote full chain deep golden as qo_sales @smoke', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(300_000);

  let adminContext: BrowserContext;

  test.beforeAll(async ({ browser }: { browser: Browser }) => {
    adminContext = await browser.newContext({ storageState: { cookies: [], origins: [] } });
    const adminPage = await adminContext.newPage();
    await loginViaUI(adminPage, ADMIN_EMAIL, ADMIN_PASSWORD);
    const resp = await adminPage.request.post('/api/admin/users', {
      data: {
        email: SALES_USER.email,
        displayName: SALES_USER.displayName,
        initialPassword: SALES_USER.password,
        roleCodes: SALES_USER.roleCodes,
        sendInviteEmail: false,
      },
      timeout: 20_000,
    });
    if (!resp.ok()) {
      const text = await resp.text().catch(() => '');
      expect(
        /已存在|exists|duplicate|重复|conflict/i.test(text) || resp.status() === 409,
        `ensure smoke_sales failed: HTTP ${resp.status()} ${text.slice(0, 300)}`,
      ).toBe(true);
    }
    await adminContext.close();
  });

  test('sales: create quote via UI → manual price adoption → quote Excel download, zero forbidden', async ({ browser }, testInfo) => {
    const { context, page } = await openQuoteRolePage(browser, SALES_USER);
    const forbidden: ForbiddenHit[] = [];
    let step = 'login';
    page.on('response', (resp: Response) => {
      const status = resp.status();
      if ((status === 401 || status === 403) && resp.url().includes('/api/')) {
        forbidden.push({ step, url: resp.url(), status });
      }
    });
    const consoleIssues: string[] = [];
    page.on('console', (message) => {
      const text = message.text();
      if (isTransientViteDynamicImportIssue(text)) return;
      if (/Expression evaluation failed|Cannot read properties|ReferenceError|TypeError/i.test(text)) {
        consoleIssues.push(`${message.type()}: ${text}`);
      }
    });

    try {
      const suffix = `${Date.now()}${Math.random().toString(16).slice(2, 8)}`;
      const accountName = `ZZZ Smoke Sales Customer ${suffix}`;
      const projectName = `ZZZ Smoke Sales Project ${suffix}`;
      const notes = `sales role golden ${suffix}`;

      // 0. own prerequisites created AS SALES (self-scope keeps them visible in the dropdowns)
      step = 'seed own customer/project';
      const account = await executeCommand(page, 'crm:create_account', {
        crm_acc_name: accountName,
        crm_acc_industry: 'electronics',
        crm_acc_rating: 'A',
      });
      const accountId = String(account.recordId ?? '');
      expect(accountId, 'sales creates own customer').toBeTruthy();
      const project = await executeCommand(page, 'bom:create_project', {
        bom_project_name: projectName,
        bom_project_customer_id: accountId,
        bom_project_quality_level: 'industrial',
        bom_pcba_code: `SSG-${suffix}`.slice(0, 24),
        bom_project_library_source: 'excel_current_library',
      });
      const projectId = String(project.recordId ?? '');
      expect(projectId, 'sales creates own BOM project').toBeTruthy();

      // 1. UI create quote: customer + project dropdowns + corrected BOM upload
      step = 'create quote via UI form';
      await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
      await ensureSidebarExpanded(page);
      const sidebar = page.getByTestId('sidebar');
      await sidebar.locator('a[href="/p/qo_quote_common"]').first().click();
      await page.waitForURL((url) => url.pathname.startsWith('/p/qo_quote_common'), { timeout: 20_000 });
      await openQuoteCreateFormFromList(page);

      await pickReferenceOption(page, 'select-trigger-qo_quote_crm_account_id', accountId);
      await pickReferenceOption(page, 'select-trigger-qo_quote_project_id', projectId);

      const workbookPath = createCorrectedBomWorkbook(testInfo.outputPath('sales-role-corrected-bom.xlsx'));
      const uploadField = page.getByTestId('form-field-corrected_bom_file');
      await expect(uploadField).toBeVisible({ timeout: 15_000 });
      const uploadResponsePromise = page.waitForResponse(
        (response) => response.url().includes('/api/file/upload') && response.request().method() === 'POST',
        { timeout: 30_000 },
      );
      const fileInput = uploadField.locator('input[type="file"]').first();
      if ((await fileInput.count()) > 0) {
        await fileInput.setInputFiles(workbookPath);
      } else {
        const chooserPromise = page.waitForEvent('filechooser', { timeout: 10_000 });
        await uploadField.locator('button, [role="button"]').first().click();
        await (await chooserPromise).setFiles(workbookPath);
      }
      expect((await uploadResponsePromise).ok(), 'corrected BOM upload succeeds').toBe(true);

      await page.getByTestId('form-field-qo_quote_notes').locator('textarea, input').first().fill(notes);

      const createResponsePromise = page.waitForResponse(
        (response) =>
          response.url().includes('/api/meta/commands/execute/qo_quote_common:create') &&
          response.request().method() === 'POST',
        { timeout: 30_000 },
      );
      await page.getByTestId('form-btn-save').click();
      const createBody = (await (await createResponsePromise).json().catch(() => ({}))) as Record<string, unknown>;
      expect(String(createBody.code), `quote create response: ${JSON.stringify(createBody).slice(0, 600)}`).toBe('0');
      const createData = ((createBody.data as Record<string, unknown> | undefined)?.data ?? {}) as Record<string, unknown>;
      const quoteId = String(createData.recordId ?? createData.recordPid ?? createData.quoteId ?? '');
      expect(quoteId, 'quote create returns id').toBeTruthy();

      // 2. corrected BOM import lands as quote lines (async — poll the role's own read surface)
      step = 'await corrected BOM import';
      let lineId = '';
      await expect
        .poll(
          async () => {
            const lines = await queryDynamicRecords(page, 'qo_quote_line_common', [
              { fieldName: 'qo_ql_quote_id', operator: 'EQ', value: quoteId },
            ]);
            if (lines.length > 0) lineId = String(lines[0].pid ?? '');
            return lines.length;
          },
          { timeout: 60_000, intervals: [1000, 2000, 3000] },
        )
        .toBeGreaterThanOrEqual(2);
      expect(lineId, 'imported quote line id').toBeTruthy();

      const quote = await readDynamicRecord(page, 'qo_quote_common', quoteId);
      const created: CreatedRows = {
        quoteId,
        quoteCode: String(quote.qo_quote_code ?? ''),
        rows: [],
      };
      expect(created.quoteCode, 'quote code assigned').toBeTruthy();

      // 3. BOM price tab → review drawer → record manual price (adopted onto the line)
      step = 'record manual price';
      await openQuoteDetailFromList(page, created);
      await page.getByRole('tab', { name: /BOM价格|BOM Price/ }).click();
      const priceRow = page.getByTestId(`table-row-${lineId}`);
      await expect(priceRow).toBeVisible({ timeout: 20_000 });
      await priceRow.click();
      const drawer = page.getByTestId('review-drawer');
      await expect(drawer).toBeVisible({ timeout: 20_000 });
      await page.getByTestId('review-drawer-candidate-action-record_manual_price').click();
      // manual price collects via the platform FormDialog (standard DSL inputFields sugar)
      await expect(page.getByTestId('form-dialog')).toBeVisible({ timeout: 15_000 });
      await page.getByTestId('form-dialog-field-unitPrice').fill(String(MANUAL_UNIT_PRICE));
      await page.getByTestId('form-dialog-field-supplierName').fill(MANUAL_SUPPLIER);
      await page.getByTestId('form-dialog-field-reason').fill(MANUAL_REASON);
      await page.getByTestId('form-dialog-field-validUntil').fill(MANUAL_VALID_UNTIL);
      await page.getByTestId('form-dialog-field-sourceNote').fill('smoke sales golden');
      const manualResponsePromise = page.waitForResponse(
        (response) =>
          response.url().includes('/api/meta/commands/execute/qo_quote_line_common:record_manual_price') &&
          response.request().method() === 'POST',
        { timeout: 30_000 },
      );
      await page.getByTestId('form-dialog-submit').click();
      const manualBody = (await (await manualResponsePromise).json().catch(() => ({}))) as Record<string, unknown>;
      expect(String(manualBody.code), `record_manual_price response: ${JSON.stringify(manualBody).slice(0, 600)}`).toBe('0');

      await expect
        .poll(
          async () => {
            const evidences = await queryDynamicRecords(page, 'qo_price_evidence_common', [
              { fieldName: 'qo_pe_quote_line_id', operator: 'EQ', value: lineId },
              { fieldName: 'qo_pe_source', operator: 'EQ', value: 'manual' },
            ]);
            if (evidences.length === 0) return 'no-evidence';
            const line = await readDynamicRecord(page, 'qo_quote_line_common', lineId);
            return `${Number(evidences[0].qo_pe_unit_price).toFixed(4)}|${Number(line.qo_ql_unit_cost).toFixed(4)}`;
          },
          { timeout: 20_000, intervals: [500, 1000, 1500] },
        )
        .toBe(`${MANUAL_UNIT_PRICE.toFixed(4)}|${MANUAL_UNIT_PRICE.toFixed(4)}`);

      // 4. roll up cost (sales holds qo.quote.manage), then generate + download the quote Excel
      step = 'rollup + generate quote excel';
      await executeCommand(page, 'qo_quote_common:rollup_cost', {}, quoteId, 'update');
      await page.reload({ waitUntil: 'domcontentloaded' });
      await expect(page.getByRole('tab', { name: /报价Excel|Quote Excel/ })).toBeVisible({ timeout: 20_000 });
      await page.getByRole('tab', { name: /报价Excel|Quote Excel/ }).click();
      await expect(page.getByTestId('workbench-action-generate_quote_excel')).toBeVisible({ timeout: 15_000 });
      const generateResponsePromise = page.waitForResponse(
        (response) =>
          response.url().includes('/api/meta/commands/execute/qo_quote_common:generate_document') &&
          response.request().method() === 'POST',
        { timeout: 60_000 },
      );
      const downloadPromise = page.waitForEvent('download', { timeout: 60_000 });
      await page.getByTestId('workbench-action-generate_quote_excel').click();
      const generateBody = (await (await generateResponsePromise).json().catch(() => ({}))) as Record<string, unknown>;
      expect(String(generateBody.code), `generate_document response: ${JSON.stringify(generateBody).slice(0, 600)}`).toBe('0');
      const download = await downloadPromise;
      expect(download.suggestedFilename()).toMatch(/\.xlsx$/i);
      const exportPath = path.join(testInfo.outputDir, `role-sales-quote-${quoteId}.xlsx`);
      await download.saveAs(exportPath);
      validateQuoteWorkbook(exportPath);

      // 5. hard gates
      expect(consoleIssues, `console issues:\n${consoleIssues.join('\n')}`).toEqual([]);
      const hits = forbidden.map((h) => `[${h.step}] ${h.status} ${h.url}`);
      expect(hits, `forbidden API hits as qo_sales:\n${hits.join('\n')}`).toEqual([]);
    } finally {
      await context.close();
    }
  });
});
