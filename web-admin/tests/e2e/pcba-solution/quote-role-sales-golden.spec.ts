import fs from 'node:fs';
import path from 'node:path';
import * as XLSX from 'xlsx';
import type { Browser, BrowserContext, Response, TestInfo } from '@playwright/test';
import { test, expect, type Page } from '../../fixtures';
import { ensureSidebarExpanded } from '../helpers';
import { loginViaUI } from '../../helpers/wd-fixtures';
import {
  createCorrectedBomWorkbook,
  dynamicCreate,
  executeCommand,
  isTransientViteDynamicImportIssue,
  openQuoteCreateFormFromList,
  openQuoteDetailFromList,
  openQuoteRolePage,
  queryDynamicRecords,
  readDynamicRecord,
  reassignRecordOwnerByEmail,
  QUOTE_ROLE_TEST_PASSWORD,
  type CreatedRows,
  type QuoteRoleUser,
} from './quote-e2e-helpers';

/**
 * Quote full-chain deep golden AS THE SALES ROLE (qo_sales), not admin.
 *
 * Drives the sales person's real day: create customer + BOM project (own data, self-scope),
 * create a quote from the UI form (customer/project reference dropdowns + corrected-BOM
 * upload), adopt recent-purchase/Yunhan/manual prices, change sets + price factor, edit a
 * material and reprice it through the local Yunhan mock, then generate and parse the quote
 * Excel after every business state (3 sheets, no broken formulas, no raw field codes).
 * A zero-401/403 collector runs across the owner's whole session, followed by a second sales
 * employee proving that every confidential child record remains hidden ("管理员能用 ≠ 系统能用",
 * DDR-2026-06-29 §8).
 *
 * RUN (local host-first stack, business roles reconciled):
 *   PLAYWRIGHT_BASE_URL=http://127.0.0.1:<web> BACKEND_URL=http://127.0.0.1:<be> PW_SKIP_WEBSERVER=1 \
 *     YUNHAN_MOCK_CONTROL_URL=http://127.0.0.1:18091 \
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

const SALES_B_USER: QuoteRoleUser = {
  key: 'smoke_sales_b',
  email: 'smoke-sales-b@e2e.local',
  displayName: 'Smoke Sales B',
  password: QUOTE_ROLE_TEST_PASSWORD,
  roleCodes: ['qo_sales'],
};

const MANUAL_UNIT_PRICE = 1.2345;
const MANUAL_SUPPLIER = 'Smoke Manual Supplier';
const MANUAL_REASON = 'sales role golden manual adoption';
const MANUAL_VALID_UNTIL = '2026-12-31';
const SET_COUNT = 200;
const PRICE_FACTOR = 105;
const RECENT_UNIT_PRICE = 0.021;
const YUNHAN_UNIT_PRICE = 0.0163;
const REPRICE_MPN = 'TEST-REPRICE-0603';

type ForbiddenHit = { step: string; url: string; status: number };

async function fillDialogField(page: Page, field: string, value: string): Promise<void> {
  const testId = `form-dialog-field-${field}`;
  for (let attempt = 0; attempt < 3; attempt += 1) {
    const input = page.getByTestId(testId);
    await expect(input).toBeVisible();
    await input.fill(value);
    try {
      await expect(input).toHaveValue(value, { timeout: 2_000 });
      return;
    } catch (error) {
      if (attempt === 2) {
        throw error;
      }
    }
  }
}

function sheetRows(workbook: XLSX.WorkBook, sheetName: string): unknown[][] {
  const sheet = workbook.Sheets[sheetName];
  expect(sheet, `sheet ${sheetName} exists`).toBeTruthy();
  return XLSX.utils.sheet_to_json(sheet, { header: 1, raw: false, defval: '' }) as unknown[][];
}

function validateQuoteWorkbook(
  filePath: string,
  expectedSetCount: number,
  expectedLine?: { mpn: string; unitCost: number },
): void {
  const workbook = XLSX.read(fs.readFileSync(filePath), {
    type: 'buffer',
    cellText: false,
    sheetStubs: true,
  });
  expect(workbook.SheetNames).toEqual(['报价单', 'BOM明细', '加工明细']);
  const quoteSheet = workbook.Sheets['报价单'];
  expect(Number(quoteSheet.G15?.v), '报价单!G15 单次订单量来自报价套数').toBe(expectedSetCount);
  expect(String(quoteSheet.H15?.f ?? '')).toContain('BOM明细');
  expect(String(quoteSheet.I15?.f ?? '')).toContain('加工明细');
  expect(String(quoteSheet.J15?.f ?? '')).toBe('ROUND(K15-H15-I15,2)');
  expect(String(quoteSheet.K15?.f ?? '')).toBe('IF(G15=0,0,ROUND(L15/G15,6))');
  expect(String(quoteSheet.P15?.f ?? '')).toBe('ROUND(N15+M15+L15,2)');
  const bomRows = sheetRows(workbook, 'BOM明细');
  expect(bomRows.length, 'BOM 明细 has header + imported lines').toBeGreaterThanOrEqual(3);
  for (const sheetName of workbook.SheetNames) {
    const sheet = workbook.Sheets[sheetName];
    for (const key of Object.keys(sheet)) {
      if (key.startsWith('!')) continue;
      const value = sheet[key] as { v?: unknown; f?: string };
      const text = `${value.v ?? ''}${value.f ?? ''}`;
      expect(text, `${sheetName}!${key} has no broken formula`).not.toMatch(
        /#REF!|#DIV\/0!|#VALUE!/,
      );
    }
  }
  const flat = JSON.stringify(bomRows.slice(0, 4));
  expect(flat, 'no raw qo_* field codes leak into the workbook').not.toMatch(
    /qo_(quote|ql|pe)_[a-z_]+/,
  );
  if (expectedLine) {
    const headerIndex = bomRows.findIndex((row) => row.some((cell) => String(cell) === '材料单价'));
    expect(headerIndex, 'BOM 明细应包含材料单价表头').toBeGreaterThanOrEqual(0);
    const headers = bomRows[headerIndex].map(String);
    const unitPriceColumn = headers.indexOf('材料单价');
    const processPointColumn = headers.indexOf('加工点数');
    const materialRow = bomRows
      .slice(headerIndex + 1)
      .find((row) => row.some((cell) => String(cell).includes(expectedLine.mpn)));
    expect(materialRow, `BOM 明细应包含修正后的物料 ${expectedLine.mpn}`).toBeTruthy();
    expect(Number(materialRow?.[unitPriceColumn])).toBeCloseTo(expectedLine.unitCost, 4);
    expect(
      Number(materialRow?.[processPointColumn]),
      '修正物料的加工点数应完成重算',
    ).toBeGreaterThan(0);
  }
}

function parseJsonObject(value: unknown): Record<string, unknown> {
  let current = value;
  for (let depth = 0; depth < 4; depth += 1) {
    if (typeof current === 'string') {
      try {
        current = JSON.parse(current);
        continue;
      } catch {
        return {};
      }
    }
    if (
      current &&
      typeof current === 'object' &&
      !Array.isArray(current) &&
      'value' in current &&
      ['json', 'jsonb'].includes(
        String((current as Record<string, unknown>).type ?? '').toLowerCase(),
      )
    ) {
      current = (current as Record<string, unknown>).value;
      continue;
    }
    break;
  }
  return current && typeof current === 'object' && !Array.isArray(current)
    ? (current as Record<string, unknown>)
    : {};
}

async function adoptEvidence(
  page: Page,
  lineId: string,
  evidenceId: string,
  expectedSource: string,
): Promise<void> {
  const openDrawerClose = page.getByRole('button', {
    name: /关闭复核浮层|Close review drawer/,
  });
  if (await openDrawerClose.isVisible().catch(() => false)) {
    await openDrawerClose.click();
    await expect(page.getByTestId('review-drawer')).toBeHidden();
  }
  await page.getByRole('tab', { name: /BOM价格|BOM Price/ }).click();
  const priceRow = page.getByTestId(`table-row-${lineId}`);
  await expect(priceRow).toBeVisible({ timeout: 20_000 });
  await priceRow.click();
  const drawer = page.getByTestId('review-drawer');
  await expect(drawer).toBeVisible({ timeout: 20_000 });
  const candidate = page.getByTestId(`review-drawer-candidate-${evidenceId}`);
  await expect(candidate).toBeVisible({ timeout: 20_000 });
  await candidate.click();
  const responsePromise = page.waitForResponse(
    (response) =>
      response
        .url()
        .includes('/api/meta/commands/execute/qo_quote_line_common:confirm_cost_from_evidence') &&
      response.request().method() === 'POST',
    { timeout: 30_000 },
  );
  await page.getByTestId('review-drawer-candidate-action-confirm_price').click();
  const body = (await (await responsePromise).json().catch(() => ({}))) as Record<string, unknown>;
  expect(String(body.code), `confirm price response: ${JSON.stringify(body).slice(0, 600)}`).toBe(
    '0',
  );
  await expect
    .poll(
      async () => {
        const decisions = await queryDynamicRecords(page, 'qo_quote_line_price_decision_common', [
          { fieldName: 'qo_qlpd_quote_line_id', operator: 'EQ', value: lineId },
          { fieldName: 'qo_qlpd_status', operator: 'EQ', value: 'accepted' },
        ]);
        return decisions.map((row) => String(row.qo_qlpd_source ?? '')).join(',');
      },
      { timeout: 20_000, intervals: [500, 1000, 1500] },
    )
    .toContain(expectedSource);
  const close = page.getByRole('button', { name: /关闭复核浮层|Close review drawer/ });
  if (await close.isVisible().catch(() => false)) {
    await close.click();
  }
}

async function generateAndValidateWorkbook(
  page: Page,
  quoteId: string,
  label: string,
  expectedSetCount: number,
  testInfo: TestInfo,
  expectedLine?: { mpn: string; unitCost: number },
): Promise<string> {
  const drawerClose = page.getByRole('button', {
    name: /关闭复核浮层|Close review drawer/,
  });
  if (await drawerClose.isVisible().catch(() => false)) {
    await drawerClose.click();
    await expect(page.getByTestId('review-drawer')).toBeHidden();
  }
  await page.getByRole('tab', { name: /报价Excel|Quote Excel/ }).click();
  const action = page.getByTestId('workbench-action-generate_quote_excel');
  await expect(action).toBeVisible({ timeout: 15_000 });
  const responsePromise = page.waitForResponse(
    (response) =>
      response.url().includes('/api/meta/commands/execute/qo_quote_common:generate_document') &&
      response.request().method() === 'POST',
    { timeout: 60_000 },
  );
  const downloadPromise = page.waitForEvent('download', { timeout: 60_000 });
  await action.click();
  const body = (await (await responsePromise).json().catch(() => ({}))) as Record<string, unknown>;
  expect(
    String(body.code),
    `generate_document(${label}): ${JSON.stringify(body).slice(0, 600)}`,
  ).toBe('0');
  const download = await downloadPromise;
  const exportPath = path.join(testInfo.outputDir, `role-sales-${label}-${quoteId}.xlsx`);
  await download.saveAs(exportPath);
  validateQuoteWorkbook(exportPath, expectedSetCount, expectedLine);
  return exportPath;
}

function editAndRepriceAudit(commandBody: Record<string, unknown>): Record<string, unknown> {
  const data = parseJsonObject(commandBody.data);
  const handlerResults = Array.isArray(data.handlerResults) ? data.handlerResults : [];
  const first = parseJsonObject(handlerResults[0]);
  return (
    [parseJsonObject(data.data), parseJsonObject(first.data), first, data].find((candidate) =>
      Array.isArray(candidate.externalSourcesRequested),
    ) ?? {}
  );
}

async function pickReferenceOption(
  page: Page,
  triggerTestId: string,
  value: string,
): Promise<void> {
  const trigger = page.getByTestId(triggerTestId);
  await expect(trigger).toBeVisible({ timeout: 15_000 });
  await trigger.click();
  const option = page.locator(`[role="option"][data-value="${value}"]`).first();
  await expect(option, `${triggerTestId} option ${value} should be loaded`).toBeVisible({
    timeout: 15_000,
  });
  await option.click();
}

test.describe('Quote full chain deep golden as qo_sales @smoke', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(600_000);

  let adminContext: BrowserContext;

  test.beforeAll(async ({ browser }: { browser: Browser }) => {
    adminContext = await browser.newContext({ storageState: { cookies: [], origins: [] } });
    const adminPage = await adminContext.newPage();
    await loginViaUI(adminPage, ADMIN_EMAIL, ADMIN_PASSWORD);
    for (const user of [SALES_USER, SALES_B_USER]) {
      const resp = await adminPage.request.post('/api/admin/users', {
        data: {
          email: user.email,
          displayName: user.displayName,
          initialPassword: user.password,
          roleCodes: user.roleCodes,
          sendInviteEmail: false,
        },
        timeout: 20_000,
      });
      if (!resp.ok()) {
        const text = await resp.text().catch(() => '');
        expect(
          /已存在|exists|duplicate|重复|conflict/i.test(text) || resp.status() === 409,
          `ensure ${user.key} failed: HTTP ${resp.status()} ${text.slice(0, 300)}`,
        ).toBe(true);
      }
    }
    await adminContext.close();
  });

  test('sales: one import → all pricing channels + set/factor + reprice → Excel each step, zero forbidden', async ({
    browser,
  }, testInfo) => {
    const { context, page } = await openQuoteRolePage(browser, SALES_USER);
    const mockControlUrl = String(process.env.YUNHAN_MOCK_CONTROL_URL ?? '').replace(/\/$/, '');
    expect(
      mockControlUrl,
      'YUNHAN_MOCK_CONTROL_URL is required; never consume live Yunhan quota in E2E',
    ).toBeTruthy();
    const defaultScenarioResponse = await page.request.post(
      `${mockControlUrl}/__control/scenario/release-default`,
    );
    expect(
      defaultScenarioResponse.ok(),
      `reset Yunhan mock scenario: HTTP ${defaultScenarioResponse.status()} ${await defaultScenarioResponse.text()}`,
    ).toBe(true);
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
      if (
        /Expression evaluation failed|Cannot read properties|ReferenceError|TypeError/i.test(text)
      ) {
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
      await page.waitForURL((url) => url.pathname.startsWith('/p/qo_quote_common'), {
        timeout: 20_000,
      });
      await openQuoteCreateFormFromList(page);

      await pickReferenceOption(page, 'select-trigger-qo_quote_crm_account_id', accountId);
      await pickReferenceOption(page, 'select-trigger-qo_quote_project_id', projectId);

      const workbookPath = createCorrectedBomWorkbook(
        testInfo.outputPath('sales-role-corrected-bom.xlsx'),
      );
      const uploadField = page.getByTestId('form-field-corrected_bom_file');
      await expect(uploadField).toBeVisible({ timeout: 15_000 });
      const uploadResponsePromise = page.waitForResponse(
        (response) =>
          response.url().includes('/api/file/upload') && response.request().method() === 'POST',
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

      await page
        .getByTestId('form-field-qo_quote_notes')
        .locator('textarea, input')
        .first()
        .fill(notes);

      const createResponsePromise = page.waitForResponse(
        (response) =>
          response.url().includes('/api/meta/commands/execute/qo_quote_common:create') &&
          response.request().method() === 'POST',
        { timeout: 30_000 },
      );
      await page.getByTestId('form-btn-save').click();
      const createBody = (await (await createResponsePromise).json().catch(() => ({}))) as Record<
        string,
        unknown
      >;
      expect(
        String(createBody.code),
        `quote create response: ${JSON.stringify(createBody).slice(0, 600)}`,
      ).toBe('0');
      const createData = ((createBody.data as Record<string, unknown> | undefined)?.data ??
        {}) as Record<string, unknown>;
      const quoteId = String(
        createData.recordId ?? createData.recordPid ?? createData.quoteId ?? '',
      );
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
            const target = lines.find((line) => String(line.qo_ql_mpn ?? '') === 'RC0603FR-0710KL');
            if (target) lineId = String(target.pid ?? '');
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

      // 3. Seed deterministic channel evidence through admin setup, then hand only those fixture
      // rows to the quote owner. All user actions below remain real qo_sales browser actions.
      step = 'seed deterministic recent + yunhan evidence';
      const seededRows: CreatedRows['rows'] = [];
      const setupContext = await browser.newContext({ storageState: { cookies: [], origins: [] } });
      try {
        const setupPage = await setupContext.newPage();
        await loginViaUI(setupPage, ADMIN_EMAIL, ADMIN_PASSWORD);
        const recentEvidenceId = await dynamicCreate(
          setupPage,
          'qo_price_evidence_common',
          {
            qo_pe_quote_line_id: lineId,
            qo_pe_part_no: 'RC0603FR-0710KL',
            qo_pe_source: 'purchase_analysis_recent_price',
            qo_pe_source_ref: `ordinary-sales-recent-${suffix}`,
            qo_pe_supplier_name: '普通员工近期采购价夹具',
            qo_pe_unit_price: RECENT_UNIT_PRICE,
            qo_pe_currency: 'CNY',
            qo_pe_moq: 1,
            qo_pe_mpq: 1,
            qo_pe_status: 'captured',
            qo_pe_snapshot: {
              source: 'purchase_analysis_recent_price',
              matchedBy: 'mpn',
              purchaseDate: '2026-07-01',
            },
          },
          seededRows,
        );
        const yunhanEvidenceId = await dynamicCreate(
          setupPage,
          'qo_price_evidence_common',
          {
            qo_pe_quote_line_id: lineId,
            qo_pe_part_no: 'RC0603FR-0710KL',
            qo_pe_source: 'yunhan',
            qo_pe_source_ref: `yunhan:ordinary-sales-${suffix}`,
            qo_pe_supplier_name: '云汉芯城协议 Mock',
            qo_pe_unit_price: YUNHAN_UNIT_PRICE,
            qo_pe_currency: 'CNY',
            qo_pe_moq: 50,
            qo_pe_mpq: 100,
            qo_pe_status: 'captured',
            qo_pe_snapshot: {
              source: 'yunhan',
              matchedBy: 'mpn',
              keyword: 'RC0603FR-0710KL',
              ladderNums: [50, 500, 5000],
              ladderPrices: [YUNHAN_UNIT_PRICE, 0.0148, 0.012],
              detailUrl: `https://www.ickey.cn/detail/mock/ordinary-sales-${suffix}.html`,
            },
          },
          seededRows,
        );
        await dynamicCreate(
          setupPage,
          'qo_offline_material_price_common',
          {
            qo_omp_part_no: REPRICE_MPN,
            qo_omp_mpn: REPRICE_MPN,
            qo_omp_description: 'E2E local recent-purchase candidate 0402',
            qo_omp_unit_price: 0.018,
            qo_omp_recent_purchase_price: 0.018,
            qo_omp_currency: 'CNY',
            qo_omp_status: 'active',
            qo_omp_source_filename: `ordinary-sales-reprice-${suffix}.xlsx`,
            qo_omp_source_row_no: 2,
          },
          seededRows,
        );
        // qo_ql_mpn is the Kingdee/internal material code. Repricing resolves that code through
        // bom_material_master before it calls Yunhan, so the fixture must carry the same mapping a
        // production material has; otherwise the handler correctly falls back to description
        // search and this test would not exercise the exact-MPN lane it claims to cover.
        const materialMasters = await queryDynamicRecords(setupPage, 'bom_material_master', [
          { fieldName: 'bom_mm_material_code', operator: 'EQ', value: REPRICE_MPN },
        ]);
        const materialMaster = materialMasters[0];
        const materialPayload = {
          bom_mm_material_name: '贴片电容',
          bom_mm_spec_model: '100nF 50V 0402',
          bom_mm_unit: 'PCS',
          bom_mm_brand: 'Mock Manufacturer',
          bom_mm_mpn: REPRICE_MPN,
          bom_mm_package: '0402',
          bom_mm_category: 'capacitor',
          bom_mm_enabled: true,
        };
        if (materialMaster) {
          await executeCommand(
            setupPage,
            'bom:update_material',
            materialPayload,
            String(materialMaster.pid),
            'update',
          );
        } else {
          await executeCommand(setupPage, 'bom:create_material', {
            bom_mm_material_code: REPRICE_MPN,
            ...materialPayload,
          });
        }
        await reassignRecordOwnerByEmail(
          [
            { model: 'qo_price_evidence_common', pid: recentEvidenceId },
            { model: 'qo_price_evidence_common', pid: yunhanEvidenceId },
          ],
          SALES_USER.email,
        );

        await openQuoteDetailFromList(page, created);

        // 4. A single imported quote walks each price channel. Every accepted decision is read
        // back as the ordinary employee, directly covering the production 403 regression.
        step = 'adopt recent purchase and export';
        await adoptEvidence(page, lineId, recentEvidenceId, 'purchase_analysis_recent_price');
        await expect
          .poll(
            async () =>
              Number(
                (await readDynamicRecord(page, 'qo_quote_line_common', lineId)).qo_ql_unit_cost,
              ),
            { timeout: 20_000, intervals: [500, 1000, 1500] },
          )
          .toBeCloseTo(RECENT_UNIT_PRICE, 6);
        await executeCommand(page, 'qo_quote_common:rollup_cost', {}, quoteId, 'update');
        await generateAndValidateWorkbook(page, quoteId, 'recent-purchase', 1, testInfo);

        step = 'adopt yunhan ladder and export';
        await adoptEvidence(page, lineId, yunhanEvidenceId, 'yunhan');
        await expect
          .poll(
            async () =>
              Number(
                (await readDynamicRecord(page, 'qo_quote_line_common', lineId)).qo_ql_unit_cost,
              ),
            { timeout: 20_000, intervals: [500, 1000, 1500] },
          )
          .toBeCloseTo(YUNHAN_UNIT_PRICE, 6);
        await executeCommand(page, 'qo_quote_common:rollup_cost', {}, quoteId, 'update');
        await generateAndValidateWorkbook(page, quoteId, 'yunhan', 1, testInfo);

        // 5. Modify sets and factor from the actual toolbar. The async command must finish,
        // replace the accepted decision version, and keep the selected Yunhan evidence raw.
        step = 'modify set count and price factor';
        await page.getByRole('tab', { name: /资料上传|Materials/ }).click();
        await page.getByRole('button', { name: /修改套数|Edit Sets/ }).click();
        await expect(page.getByTestId('form-dialog')).toBeVisible({ timeout: 15_000 });
        await fillDialogField(page, 'qo_quote_set_count', String(SET_COUNT));
        await fillDialogField(page, 'qo_quote_price_factor', String(PRICE_FACTOR));
        const recomputeResponsePromise = page.waitForResponse(
          (response) =>
            response
              .url()
              .includes('/api/meta/commands/execute/qo_quote_common:recompute_quantities') &&
            response.request().method() === 'POST',
          { timeout: 30_000 },
        );
        await page.getByTestId('form-dialog-submit').click();
        const recomputeBody = (await (await recomputeResponsePromise)
          .json()
          .catch(() => ({}))) as Record<string, unknown>;
        expect(
          String(recomputeBody.code),
          `recompute_quantities response: ${JSON.stringify(recomputeBody).slice(0, 600)}`,
        ).toBe('0');
        const recomputeClose = page.getByRole('button', { name: /^(关闭|Close)$/ });
        await expect(recomputeClose).toBeVisible({ timeout: 60_000 });
        await recomputeClose.click();
        await expect(page.getByTestId('form-dialog')).toBeHidden();
        await expect
          .poll(
            async () => {
              const currentQuote = await readDynamicRecord(page, 'qo_quote_common', quoteId);
              const currentLine = await readDynamicRecord(page, 'qo_quote_line_common', lineId);
              const decisions = await queryDynamicRecords(
                page,
                'qo_quote_line_price_decision_common',
                [
                  { fieldName: 'qo_qlpd_quote_line_id', operator: 'EQ', value: lineId },
                  { fieldName: 'qo_qlpd_status', operator: 'EQ', value: 'accepted' },
                ],
              );
              const accepted = decisions[0] ?? {};
              return [
                Number(currentQuote.qo_quote_set_count),
                Number(currentQuote.qo_quote_price_factor),
                Number(currentLine.qo_ql_qty),
                Number(currentLine.qo_ql_unit_cost).toFixed(6),
                String(accepted.qo_qlpd_source ?? ''),
              ].join('|');
            },
            { timeout: 60_000, intervals: [1000, 1500, 2500] },
          )
          .toBe(
            [
              SET_COUNT,
              PRICE_FACTOR,
              7600 * SET_COUNT,
              (YUNHAN_UNIT_PRICE * (PRICE_FACTOR / 100)).toFixed(6),
              'yunhan',
            ].join('|'),
          );

        // Visual contract: the table uses factored channel prices, while the drawer keeps both
        // raw supplier evidence and factored prices. The ladder repeats the same two-column price
        // semantics and marks the active quantity tier without a full-width solid-blue slab.
        await page.getByRole('tab', { name: /BOM价格|BOM Price/ }).click();
        const factoredRow = page.getByTestId(`table-row-${lineId}`);
        await expect(factoredRow).toBeVisible({ timeout: 15_000 });
        const waterfallTable = page.locator('table').filter({ has: factoredRow }).first();
        await expect(waterfallTable).toBeVisible();
        const headers = (
          await waterfallTable.locator('thead th, thead [role="columnheader"]').allInnerTexts()
        ).map((text) => text.replace(/\s+/g, ' ').trim());
        const yunhanColumn = headers.findIndex((text) =>
          /云汉\(系数后\)|Yunhan \(After Factor\)/.test(text),
        );
        expect(
          yunhanColumn,
          `waterfall headers: ${JSON.stringify(headers)}`,
        ).toBeGreaterThanOrEqual(0);
        await expect(factoredRow.locator('td, [role="cell"]').nth(yunhanColumn)).toContainText(
          '0.0171',
        );
        await factoredRow.click();
        const recentCandidateAfterFactor = page.getByTestId(
          `review-drawer-candidate-${recentEvidenceId}`,
        );
        await expect(recentCandidateAfterFactor).toContainText('原始单价');
        await expect(recentCandidateAfterFactor).toContainText('系数后单价');
        await expect(recentCandidateAfterFactor).toContainText('0.0210');
        await expect(recentCandidateAfterFactor).toContainText('0.0221');
        await recentCandidateAfterFactor.screenshot({
          path: testInfo.outputPath('ordinary-sales-non-ladder-factor-price.png'),
        });
        const yunhanCandidate = page.getByTestId(`review-drawer-candidate-${yunhanEvidenceId}`);
        await expect(yunhanCandidate).toContainText('原始单价');
        await expect(yunhanCandidate).toContainText('系数后单价');
        await expect(yunhanCandidate).toContainText('0.0163');
        await expect(yunhanCandidate).toContainText('0.0171');
        await expect(yunhanCandidate).toContainText('当前');
        await expect(yunhanCandidate).toContainText('0.0126');
        const yunhanLadder = page.getByTestId(`review-drawer-candidate-${yunhanEvidenceId}-ladder`);
        await expect(yunhanLadder).toBeVisible();
        await yunhanLadder.scrollIntoViewIfNeeded();
        await yunhanLadder.screenshot({
          path: testInfo.outputPath('ordinary-sales-yunhan-factor-ladder.png'),
        });
        await page.getByRole('button', { name: /关闭复核浮层|Close review drawer/ }).click();

        await executeCommand(page, 'qo_quote_common:rollup_cost', {}, quoteId, 'update');
        await generateAndValidateWorkbook(page, quoteId, 'sets-factor', SET_COUNT, testInfo);
      } finally {
        await setupContext.close();
      }

      // 6. Record and adopt a manual price under the updated factor, then export again.
      step = 'record manual price and export';
      await page.getByRole('tab', { name: /BOM价格|BOM Price/ }).click();
      const priceRow = page.getByTestId(`table-row-${lineId}`);
      await expect(priceRow).toBeVisible({ timeout: 20_000 });
      await priceRow.click();
      const drawer = page.getByTestId('review-drawer');
      await expect(drawer).toBeVisible({ timeout: 20_000 });
      await page.getByTestId('review-drawer-candidate-action-record_manual_price').click();
      // manual price collects via the platform FormDialog (standard DSL inputFields sugar)
      await expect(page.getByTestId('form-dialog')).toBeVisible({ timeout: 15_000 });
      await fillDialogField(page, 'unitPrice', String(MANUAL_UNIT_PRICE));
      await fillDialogField(page, 'supplierName', MANUAL_SUPPLIER);
      await fillDialogField(page, 'sourceNote', 'smoke sales golden');
      await fillDialogField(page, 'reason', MANUAL_REASON);
      await fillDialogField(page, 'validUntil', MANUAL_VALID_UNTIL);
      await expect(page.getByTestId('form-dialog-field-unitPrice')).toHaveValue(
        String(MANUAL_UNIT_PRICE),
      );
      await expect(page.getByTestId('form-dialog-field-validUntil')).toHaveValue(
        MANUAL_VALID_UNTIL,
      );
      const manualResponsePromise = page.waitForResponse(
        (response) =>
          response
            .url()
            .includes('/api/meta/commands/execute/qo_quote_line_common:record_manual_price') &&
          response.request().method() === 'POST',
        { timeout: 30_000 },
      );
      await page.getByTestId('form-dialog-submit').click();
      const manualBody = (await (await manualResponsePromise).json().catch(() => ({}))) as Record<
        string,
        unknown
      >;
      expect(
        String(manualBody.code),
        `record_manual_price response: ${JSON.stringify(manualBody).slice(0, 600)}`,
      ).toBe('0');

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
        .toBe(
          `${MANUAL_UNIT_PRICE.toFixed(4)}|${(MANUAL_UNIT_PRICE * (PRICE_FACTOR / 100)).toFixed(4)}`,
        );

      await executeCommand(page, 'qo_quote_common:rollup_cost', {}, quoteId, 'update');
      await generateAndValidateWorkbook(page, quoteId, 'manual', SET_COUNT, testInfo);

      // 7. Edit material facts and reprice. This switches only the local fake service to its
      // recorded deterministic scenario; the browser still executes the production command.
      step = 'edit material and reprice through Yunhan mock';
      const scenarioResponse = await page.request.post(
        `${mockControlUrl}/__control/scenario/reprice-v2`,
      );
      expect(
        scenarioResponse.ok(),
        `switch Yunhan mock scenario: HTTP ${scenarioResponse.status()} ${await scenarioResponse.text()}`,
      ).toBe(true);

      await page.getByRole('tab', { name: /BOM价格|BOM Price/ }).click();
      await page.getByTestId(`table-row-${lineId}`).click();
      await expect(page.getByTestId('review-drawer')).toBeVisible({ timeout: 20_000 });
      await page.getByTestId('review-drawer-edit-open').click();
      const descriptionInput = page
        .getByTestId('review-drawer-edit-field-qo_ql_description')
        .locator('input')
        .first();
      const mpnInput = page
        .getByTestId('review-drawer-edit-field-qo_ql_mpn')
        .locator('input')
        .first();
      await descriptionInput.fill('贴片电容 100nF 50V 0402');
      await mpnInput.fill(REPRICE_MPN);
      const repriceResponsePromise = page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          response
            .url()
            .includes('/api/meta/commands/execute/qo_quote_line_common:edit_and_reprice'),
        { timeout: 60_000 },
      );
      await page.getByTestId('review-drawer-edit-submit').click();
      const repriceBody = (await (await repriceResponsePromise).json().catch(() => ({}))) as Record<
        string,
        unknown
      >;
      expect(String(repriceBody.code), JSON.stringify(repriceBody).slice(0, 800)).toBe('0');
      const repriceAudit = editAndRepriceAudit(repriceBody);
      expect(
        repriceAudit.externalSourcesRequested,
        JSON.stringify(repriceBody).slice(0, 1200),
      ).toEqual(['yunhan']);
      expect(repriceAudit.localLookups, JSON.stringify(repriceBody).slice(0, 1200)).toEqual([
        'purchase_analysis_recent_price',
      ]);
      const mockRequestsResponse = await page.request.get(`${mockControlUrl}/__control/requests`);
      expect(mockRequestsResponse.ok(), 'Yunhan mock request audit is readable').toBe(true);
      const mockRequestsBody = (await mockRequestsResponse.json()) as {
        requests?: Array<{ path?: string; form?: Record<string, unknown> }>;
      };
      const singleSearchRequest = [...(mockRequestsBody.requests ?? [])]
        .reverse()
        .find((request) => request.path?.endsWith('/get-single-goods-new'));
      expect(singleSearchRequest, JSON.stringify(mockRequestsBody).slice(0, 1200)).toBeTruthy();
      expect(singleSearchRequest?.form?.keyword).toEqual([REPRICE_MPN]);
      expect(singleSearchRequest?.form?.is_exact_match).toEqual(['1']);
      await testInfo.attach('ordinary-sales-edit-and-reprice-response.json', {
        body: JSON.stringify(repriceBody, null, 2),
        contentType: 'application/json',
      });
      await testInfo.attach('ordinary-sales-yunhan-mock-requests.json', {
        body: JSON.stringify(mockRequestsBody, null, 2),
        contentType: 'application/json',
      });

      let refreshedYunhan: Record<string, unknown> = {};
      await expect
        .poll(
          async () => {
            const evidences = await queryDynamicRecords(page, 'qo_price_evidence_common', [
              { fieldName: 'qo_pe_quote_line_id', operator: 'EQ', value: lineId },
              { fieldName: 'qo_pe_source', operator: 'EQ', value: 'yunhan' },
            ]);
            refreshedYunhan =
              evidences.find((row) => {
                const snapshot = parseJsonObject(row.qo_pe_snapshot);
                return (
                  String(row.qo_pe_part_no ?? '') === REPRICE_MPN &&
                  String(row.qo_pe_status ?? '') === 'captured' &&
                  String(snapshot.matchedBy ?? '') !== 'recent_cache'
                );
              }) ?? {};
            return String(refreshedYunhan.pid ?? '');
          },
          { timeout: 60_000, intervals: [1000, 1500, 2500] },
        )
        .not.toBe('');
      const refreshedSnapshot = parseJsonObject(refreshedYunhan.qo_pe_snapshot);
      expect(String(refreshedSnapshot.matchedBy ?? '')).not.toBe('recent_cache');
      expect(refreshedSnapshot.reusedFromEvidence).toBeUndefined();
      expect(String(refreshedSnapshot.detailUrl ?? '')).toMatch(/^https:\/\/www\.ickey\.cn\//);
      expect(refreshedSnapshot.ladderNums).toEqual([1, 100, 1000]);
      expect(refreshedSnapshot.ladderPrices).toEqual([0.02, 0.015, 0.01]);

      const processHits = await queryDynamicRecords(page, 'qo_process_fee_rule_hit_common', [
        { fieldName: 'qo_pfrh_quote_line_id', operator: 'EQ', value: lineId },
      ]);
      const matchedProcessHit = processHits.find(
        (row) =>
          String(row.qo_pfrh_match_status ?? '') === 'matched' &&
          Number(row.qo_pfrh_unit_points ?? 0) > 0,
      );
      expect(matchedProcessHit, JSON.stringify(processHits).slice(0, 1200)).toBeTruthy();

      const refreshedEvidenceId = String(refreshedYunhan.pid);
      await adoptEvidence(page, lineId, refreshedEvidenceId, 'yunhan');
      const refreshedLine = await readDynamicRecord(page, 'qo_quote_line_common', lineId);
      expect(String(refreshedLine.qo_ql_mpn ?? '')).toBe(REPRICE_MPN);
      const refreshedUnitCost = Number(refreshedLine.qo_ql_unit_cost);
      expect(refreshedUnitCost).toBeCloseTo(0.01 * (PRICE_FACTOR / 100), 6);
      await executeCommand(page, 'qo_quote_common:rollup_cost', {}, quoteId, 'update');
      await generateAndValidateWorkbook(page, quoteId, 'reprice', SET_COUNT, testInfo, {
        mpn: REPRICE_MPN,
        unitCost: refreshedUnitCost,
      });
      await page.getByRole('tab', { name: /BOM价格|BOM Price/ }).click();
      await expect(page.getByTestId(`table-row-${lineId}`)).toBeVisible({ timeout: 20_000 });
      await page.screenshot({
        path: testInfo.outputPath('ordinary-sales-reprice-result.png'),
        fullPage: true,
      });

      // 8. A second sales employee has the same capability atoms but cannot read any record
      // owned by the first employee. This explicitly covers quote child records involved in
      // recompute/export/reprice, not just the quote root.
      step = 'cross-employee SELF isolation';
      const ownedRecordGroups: Array<{ model: string; records: Record<string, unknown>[] }> = [
        { model: 'qo_quote_common', records: [{ pid: quoteId }] },
        { model: 'qo_quote_line_common', records: [{ pid: lineId }] },
        {
          model: 'qo_quote_line_price_decision_common',
          records: await queryDynamicRecords(page, 'qo_quote_line_price_decision_common', [
            { fieldName: 'qo_qlpd_quote_id', operator: 'EQ', value: quoteId },
          ]),
        },
        {
          model: 'qo_price_evidence_common',
          records: await queryDynamicRecords(page, 'qo_price_evidence_common', [
            { fieldName: 'qo_pe_quote_line_id', operator: 'EQ', value: lineId },
          ]),
        },
        {
          model: 'qo_cost_item_common',
          records: await queryDynamicRecords(page, 'qo_cost_item_common', [
            { fieldName: 'qo_ci_quote_id', operator: 'EQ', value: quoteId },
          ]),
        },
        {
          model: 'qo_quote_document_common',
          records: await queryDynamicRecords(page, 'qo_quote_document_common', [
            { fieldName: 'qo_qd_quote_id', operator: 'EQ', value: quoteId },
          ]),
        },
        { model: 'qo_process_fee_rule_hit_common', records: processHits },
      ];
      for (const group of ownedRecordGroups) {
        expect(group.records.length, `${group.model} should have owner records`).toBeGreaterThan(0);
      }

      const other = await openQuoteRolePage(browser, SALES_B_USER);
      try {
        for (const group of ownedRecordGroups) {
          const pid = String(group.records[0].pid ?? '');
          expect(pid, `${group.model} fixture pid`).toBeTruthy();
          const directResponse = await other.page.request.get(`/api/dynamic/${group.model}/${pid}`);
          const directBody = (await directResponse.json().catch(() => ({}))) as Record<
            string,
            unknown
          >;
          const directRecord = ((directBody.data as Record<string, unknown> | undefined)?.data ??
            directBody.data ??
            directBody) as Record<string, unknown>;
          expect(
            directResponse.ok() && String(directRecord?.pid ?? directRecord?.id ?? '') === pid,
            `${group.model}/${pid} leaked to another sales employee`,
          ).toBe(false);
          const visible = await queryDynamicRecords(other.page, group.model, [
            { fieldName: 'pid', operator: 'EQ', value: pid },
          ]);
          expect(visible, `${group.model}/${pid} list leaked to another sales employee`).toEqual(
            [],
          );
        }
      } finally {
        await other.context.close();
      }

      // 9. hard gates
      expect(consoleIssues, `console issues:\n${consoleIssues.join('\n')}`).toEqual([]);
      const hits = forbidden.map((h) => `[${h.step}] ${h.status} ${h.url}`);
      expect(hits, `forbidden API hits as qo_sales:\n${hits.join('\n')}`).toEqual([]);
    } finally {
      await context.close();
    }
  });
});
