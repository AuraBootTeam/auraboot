import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
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
  setYunhanMockScenario,
  QUOTE_ROLE_TEST_PASSWORD,
  yunhanMockControlUrl,
  type CreatedRows,
  type QuoteRoleUser,
} from './quote-e2e-helpers';
import { validateQuoteWorkbook } from './quote-workbook-assertions';

/**
 * Quote full-chain deep golden led by the SALES ROLE (qo_sales), with admin parity.
 *
 * Drives the sales person's real day: create customer + BOM project (own data, self-scope),
 * create a quote from the UI form (customer/project reference dropdowns + corrected-BOM
 * upload), adopt recent-purchase/Yunhan prices, change sets + price factor, edit a
 * material through a non-mutating Yunhan preview, prove cancel leaves the quote/Excel untouched,
 * explicitly confirm the preview, then generate and parse the quote Excel after every business
 * state (3 sheets, no broken formulas, no raw field codes). An administrator repeats the
 * preview/confirm/export path under ALL scope. A zero-401/403 collector runs across the owner's
 * whole session, followed by a second sales employee proving that every confidential child
 * record remains hidden and both reprice commands are denied ("管理员能用 ≠ 系统能用",
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

const SET_COUNT = 200;
const FLAT_SET_COUNT = 2;
const PRICE_FACTOR = 105;
const RECENT_UNIT_PRICE = 0.021;
const YUNHAN_UNIT_PRICE = 0.0163;
const REPRICE_MPN = 'TEST-REPRICE-0603';
const SQL_BUDGET = {
  preview: Number(process.env.QUOTE_SQL_BUDGET_PREVIEW ?? 100),
  confirm: Number(process.env.QUOTE_SQL_BUDGET_CONFIRM ?? 500),
  excel: Number(process.env.QUOTE_SQL_BUDGET_EXCEL ?? 250),
} as const;

type ForbiddenHit = { step: string; url: string; status: number };

async function assertSqlBudget(
  response: Response,
  label: string,
  budget: number,
  testInfo: TestInfo,
): Promise<void> {
  expect(Number.isInteger(budget) && budget > 0, `${label} SQL budget must be positive`).toBe(true);
  const rawCount = response.headers()['x-sql-count'];
  expect(rawCount, `${label} must expose X-SQL-Count from the real backend`).toBeTruthy();
  const count = Number(rawCount);
  expect(Number.isInteger(count) && count >= 0, `${label} invalid X-SQL-Count=${rawCount}`).toBe(
    true,
  );
  await testInfo.attach(`sql-budget-${label}.json`, {
    body: JSON.stringify({ label, count, budget, url: response.url() }, null, 2),
    contentType: 'application/json',
  });
  expect(count, `${label} SQL count ${count} exceeds budget ${budget}`).toBeLessThanOrEqual(budget);
}

async function fillDialogField(page: Page, field: string, value: string): Promise<void> {
  const input = page.getByTestId(`form-dialog-field-${field}`);
  await expect(input).toBeVisible();
  // FormDialog number inputs are controlled and coerce on every change. Send the complete value
  // as one input event: per-character typing can lose the remaining keystrokes when React commits
  // the first numeric value and re-renders the element.
  await input.fill(value);
  await expect(input).toHaveValue(value);
}

function pricingCompletionModal(page: Page) {
  return page
    .locator('div.fixed.inset-0')
    .filter({ hasText: /修改套数\/价格系数已完成|Edit Sets \/ Price Factor.*completed/i })
    .first();
}

async function dismissPricingCompletion(page: Page): Promise<void> {
  const modal = pricingCompletionModal(page);
  if (!(await modal.isVisible().catch(() => false))) return;
  await modal
    .getByRole('button', { name: /^(关闭|Close)$/ })
    .last()
    .click();
  await expect(modal).toBeHidden({ timeout: 15_000 });
}

async function updateQuotePricingInputs(
  page: Page,
  setCount: number,
  priceFactor: number,
): Promise<void> {
  // A prior async recompute may have reached terminal state between the last
  // assertion and this call. Close that exact overlay before interacting with
  // the toolbar behind it; waiting only for FormDialog to hide does not prove
  // the async completion modal has unmounted.
  await dismissPricingCompletion(page);
  await page.getByRole('tab', { name: /资料上传|Materials/ }).click();
  await page.getByRole('button', { name: /修改套数|Edit Sets/ }).click();
  await expect(page.getByTestId('form-dialog')).toBeVisible({ timeout: 15_000 });
  await fillDialogField(page, 'qo_quote_set_count', String(setCount));
  await fillDialogField(page, 'qo_quote_price_factor', String(priceFactor));
  const recomputeResponsePromise = page.waitForResponse(
    (response) =>
      response.url().includes('/api/meta/commands/execute/qo_quote_common:recompute_quantities') &&
      response.request().method() === 'POST',
    { timeout: 30_000 },
  );
  await page.getByTestId('form-dialog-submit').click();
  const recomputeBody = (await (await recomputeResponsePromise).json().catch(() => ({}))) as Record<
    string,
    unknown
  >;
  expect(
    String(recomputeBody.code),
    `recompute_quantities response: ${JSON.stringify(recomputeBody).slice(0, 600)}`,
  ).toBe('0');
  const completionModal = pricingCompletionModal(page);
  await expect(completionModal).toBeVisible({ timeout: 60_000 });
  await completionModal
    .getByRole('button', { name: /^(关闭|Close)$/ })
    .last()
    .click();
  await expect(completionModal).toBeHidden({ timeout: 15_000 });
  await expect(page.getByTestId('form-dialog')).toBeHidden();
}

async function expectEditActionInsideDrawer(page: Page, actionTestId: string): Promise<void> {
  const drawer = page.getByTestId('review-drawer');
  const actionBar = page.getByTestId('review-drawer-edit-actions');
  const action = page.getByTestId(actionTestId);
  await expect(action).toBeVisible();
  await expect(action).toBeInViewport({ ratio: 1 });
  const [drawerBox, actionBarBox, actionBox] = await Promise.all([
    drawer.boundingBox(),
    actionBar.boundingBox(),
    action.boundingBox(),
  ]);
  expect(drawerBox, 'review drawer geometry is available').not.toBeNull();
  expect(actionBarBox, 'edit action bar geometry is available').not.toBeNull();
  expect(actionBox, `${actionTestId} geometry is available`).not.toBeNull();
  expect(actionBarBox!.y).toBeGreaterThanOrEqual(drawerBox!.y);
  expect(actionBarBox!.y + actionBarBox!.height).toBeLessThanOrEqual(
    drawerBox!.y + drawerBox!.height + 1,
  );
  expect(actionBox!.y + actionBox!.height).toBeLessThanOrEqual(
    drawerBox!.y + drawerBox!.height + 1,
  );
}

async function expectLegacyPricingActionsHidden(page: Page): Promise<void> {
  await expect(
    page.getByTestId('review-drawer-candidate-action-source_price'),
    '“重新查价(此行)”已由两阶段“修正物料信息并重新查价”替代',
  ).toHaveCount(0);
  await expect(
    page.getByTestId('review-drawer-candidate-action-record_manual_price'),
    '“录入人工价”入口暂时下线，原 action、command 与用例保留',
  ).toHaveCount(0);
  const editTrigger = page.getByTestId('review-drawer-edit-open');
  await expect(editTrigger).toBeVisible();
  expect(
    await editTrigger.evaluate(
      (element) => element.closest('[data-testid="review-drawer-candidates-header"]') !== null,
    ),
    '物料修正入口应位于查价候选标题栏',
  ).toBe(true);
  await expect(editTrigger).toHaveClass(/border-accent/);
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
  await expectLegacyPricingActionsHidden(page);
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

/** Close a review drawer left open by the previous pricing step and require a
 * stable hidden window — a completed reprice can briefly rebind the same
 * selected line after the first close, reopening the drawer and intercepting
 * the next tab click. Do not force-click through a visible overlay, because
 * that would hide a real user-facing obstruction. */
async function settleReviewDrawer(page: Page): Promise<void> {
  const drawerClose = page.getByRole('button', {
    name: /关闭复核浮层|Close review drawer/,
  });
  const drawer = page.getByTestId('review-drawer');
  const closeDeadline = Date.now() + 10_000;
  let hiddenSince = 0;
  await expect
    .poll(
      async () => {
        if (Date.now() >= closeDeadline) return false;
        if (await drawer.isVisible().catch(() => false)) {
          await expect(drawerClose).toBeVisible({ timeout: 2_000 });
          await drawerClose.click();
          await expect(drawer).toBeHidden({ timeout: 2_000 });
          hiddenSince = 0;
          return false;
        }
        if (hiddenSince === 0) hiddenSince = Date.now();
        return Date.now() - hiddenSince >= 1_000;
      },
      { timeout: 10_000, intervals: [100, 200, 200, 500] },
    )
    .toBe(true);
  await expect(drawer).toBeHidden({ timeout: 2_000 });
}

async function generateAndValidateWorkbook(
  page: Page,
  quoteId: string,
  label: string,
  expectedSetCount: number,
  testInfo: TestInfo,
  expectedLine?: { mpn: string; unitCost: number },
): Promise<string> {
  await settleReviewDrawer(page);
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
  const response = await responsePromise;
  await assertSqlBudget(response, `excel-${label}`, SQL_BUDGET.excel, testInfo);
  const body = (await response.json().catch(() => ({}))) as Record<string, unknown>;
  expect(
    String(body.code),
    `generate_document(${label}): ${JSON.stringify(body).slice(0, 600)}`,
  ).toBe('0');
  const download = await downloadPromise;
  const exportPath = path.join(testInfo.outputDir, `role-sales-${label}-${quoteId}.xlsx`);
  await download.saveAs(exportPath);
  validateQuoteWorkbook(exportPath, {
    expectedSetCount,
    expectedBomLine: expectedLine,
  });
  return exportPath;
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
    const mockControlUrl = yunhanMockControlUrl();
    await setYunhanMockScenario(page, 'release-default');
    const forbidden: ForbiddenHit[] = [];
    let step = 'login';
    // The 报价Excel tab is gated by qo.quote.output.read, which the #426 seed contract
    // deliberately withholds from qo_sales (quote-surface-permission-release pins
    // sales to 资料上传/BOM价格计算/Gerber校验). Sales keeps driving every pricing action
    // below; the workbook checkpoints are exported through an admin-driven page that
    // holds the output surface.
    let exporterContext: BrowserContext | undefined;
    let exporterPage: Page | undefined;
    const exportWorkbook = async (
      quoteId: string,
      label: string,
      expectedSetCount: number,
      expectedLine?: { mpn: string; unitCost: number },
    ): Promise<void> => {
      if (!exporterPage) throw new Error('admin exporter page not initialised');
      // The export no longer runs on the sales page, so the drawer the sales
      // steps leave behind must be settled here to keep the next sales tab
      // click unobstructed.
      await settleReviewDrawer(page);
      await generateAndValidateWorkbook(
        exporterPage,
        quoteId,
        label,
        expectedSetCount,
        testInfo,
        expectedLine,
      );
    };
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
      // corrected_bom_file renders the BomUploadReview component: the file is
      // parsed into the first-10-row preview immediately, but the actual
      // /api/file/upload POST happens at form submit, not on file selection.
      const uploadField = page.getByTestId('form-field-corrected_bom_file');
      await expect(uploadField).toBeVisible({ timeout: 15_000 });
      const reviewInput = page.getByTestId('bom-upload-review-file-corrected_bom_file');
      await expect(reviewInput).toBeAttached({ timeout: 15_000 });
      await reviewInput.setInputFiles(workbookPath);
      await expect(page.getByText('原始 BOM 前 10 行')).toBeVisible({ timeout: 15_000 });
      await expect(page.getByText('已选 7 列')).toBeVisible();

      // Gerber and CPL are mandatory at create (DSL required + server-side check);
      // SmartUpload validates by extension, so write real .zip/.csv fixtures.
      const gerberFixture = path.join(os.tmpdir(), `sales-gerber-${Date.now()}.zip`);
      fs.writeFileSync(gerberFixture, 'PK\x05\x06');
      const cplFixture = path.join(os.tmpdir(), `sales-cpl-${Date.now()}.csv`);
      fs.writeFileSync(cplFixture, 'Designator,Mid X,Mid Y,Layer\nR1,0,0,top\n');
      for (const [fieldTestId, fixture] of [
        ['form-field-gerber_source_file', gerberFixture],
        ['form-field-cpl_source_file', cplFixture],
      ] as const) {
        const slotField = page.getByTestId(fieldTestId);
        await expect(slotField).toBeVisible({ timeout: 15_000 });
        const slotUpload = page.waitForResponse(
          (response) =>
            response.url().includes('/api/file/upload') && response.request().method() === 'POST',
          { timeout: 30_000 },
        );
        const slotInput = slotField.locator('input[type="file"]').first();
        if ((await slotInput.count()) > 0) {
          await slotInput.setInputFiles(fixture);
        } else {
          const chooser = page.waitForEvent('filechooser', { timeout: 10_000 });
          await slotField.locator('button, [role="button"]').first().click();
          await (await chooser).setFiles(fixture);
        }
        expect((await slotUpload).ok(), `${fieldTestId} upload succeeds`).toBe(true);
      }

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
              ladderNums: [5000, 500000, 5000000],
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
        await expect(
          page.locator(
            [
              '[data-testid="ab:detail:qo_quote_common:tab:comments"]',
              '[data-testid="ab:detail:qo_quote_common:tab:activity"]',
              '[data-testid="ab:detail:qo_quote_common:tab:__comments__"]',
              '[data-testid="ab:detail:qo_quote_common:tab:__activity__"]',
            ].join(', '),
          ),
          'quote detail intentionally hides generic comments and activity entries',
        ).toHaveCount(0);

        // 4. A single imported quote walks each price channel. Every accepted decision is read
        // back as the ordinary employee, directly covering the production 403 regression.
        step = 'open admin exporter for output-gated Excel checkpoints';
        exporterContext = await browser.newContext({
          storageState: { cookies: [], origins: [] },
        });
        const exporter = await exporterContext.newPage();
        exporterPage = exporter;
        await loginViaUI(exporter, ADMIN_EMAIL, ADMIN_PASSWORD);
        await openQuoteDetailFromList(exporter, created);

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
        await exportWorkbook(quoteId, 'recent-purchase', 1);

        // A flat price keeps its unit price when the set count changes; only real quantity,
        // line amount and the local factor change. This is the no-ladder half of the release gate.
        step = 'modify set count and factor for flat recent-purchase price';
        await updateQuotePricingInputs(page, FLAT_SET_COUNT, PRICE_FACTOR);
        await expect
          .poll(
            async () => {
              const currentLine = await readDynamicRecord(page, 'qo_quote_line_common', lineId);
              return [
                Number(currentLine.qo_ql_qty),
                Number(currentLine.qo_ql_unit_cost).toFixed(6),
                String(currentLine.qo_ql_price_source ?? ''),
              ].join('|');
            },
            { timeout: 60_000, intervals: [1000, 1500, 2500] },
          )
          .toBe(
            [
              7600 * FLAT_SET_COUNT,
              (RECENT_UNIT_PRICE * (PRICE_FACTOR / 100)).toFixed(6),
              'purchase_analysis_recent_price',
            ].join('|'),
          );
        await exportWorkbook(quoteId, 'recent-purchase-flat-sets-factor', FLAT_SET_COUNT);

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
          .toBeCloseTo(YUNHAN_UNIT_PRICE * (PRICE_FACTOR / 100), 6);
        await executeCommand(page, 'qo_quote_common:rollup_cost', {}, quoteId, 'update');
        await exportWorkbook(quoteId, 'yunhan', FLAT_SET_COUNT);

        // 5. Moving from 15,200 to 1,520,000 pieces crosses from the 5,000 tier to the
        // 500,000 tier. The selected supplier raw price and factor-derived cost must both move.
        step = 'modify set count and price factor';
        await updateQuotePricingInputs(page, SET_COUNT, PRICE_FACTOR);
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
              (0.0148 * (PRICE_FACTOR / 100)).toFixed(6),
              'yunhan',
            ].join('|'),
          );

        // Visual contract: the table uses factored channel prices, while the drawer keeps both
        // raw supplier evidence and factored prices. The ladder repeats the same two-column price
        // semantics and marks the active quantity tier without a full-width solid-blue slab.
        await page.getByRole('tab', { name: /BOM价格|BOM Price/ }).click();
        const factoredRow = page.getByTestId(`table-row-${lineId}`);
        await expect(factoredRow).toBeVisible({ timeout: 15_000 });
        // A remote tab can restore its cached row before the table header finishes
        // hydrating. Bind to this row's exact table and wait for its first header so
        // we neither read an empty header list nor pick headers from a mounted hidden tab.
        const waterfallTable = factoredRow.locator('xpath=ancestor::table[1]');
        const waterfallHeaders = waterfallTable.locator('thead th, thead [role="columnheader"]');
        let headers: string[] = [];
        let yunhanColumn = -1;
        await expect
          .poll(
            async () => {
              headers = (await waterfallHeaders.allInnerTexts()).map((text) =>
                text.replace(/\s+/g, ' ').trim(),
              );
              yunhanColumn = headers.findIndex((text) =>
                /云汉\(系数后\)|Yunhan \(After Factor\)/.test(text),
              );
              return yunhanColumn;
            },
            {
              timeout: 20_000,
              intervals: [250, 500, 1000],
              message: `waterfall headers hydrate: ${JSON.stringify(headers)}`,
            },
          )
          .toBeGreaterThanOrEqual(0);
        expect(
          headers.some((text) => /总用量|Total Qty/i.test(text)),
          `Waterfall should not expose duplicate total quantity: ${JSON.stringify(headers)}`,
        ).toBe(false);
        await expect(factoredRow.locator('td, [role="cell"]').nth(yunhanColumn)).toContainText(
          '0.0155',
        );
        await factoredRow.click();
        const recentCandidateAfterFactor = page.getByTestId(
          `review-drawer-candidate-${recentEvidenceId}`,
        );
        await expect(recentCandidateAfterFactor).toContainText('原始');
        await expect(
          page.getByTestId(`review-drawer-candidate-${recentEvidenceId}-table-field-quote_total`),
        ).toContainText('0.02205');
        expect(
          await recentCandidateAfterFactor.evaluate(
            (element) => window.getComputedStyle(element).userSelect,
          ),
          '候选报价文字应允许双击或拖动选择',
        ).toBe('text');
        const candidateModel = page.getByTestId(
          `review-drawer-candidate-${recentEvidenceId}-table-field-part_no`,
        );
        const candidateModelBox = await candidateModel.boundingBox();
        expect(candidateModelBox).not.toBeNull();
        await page.mouse.move(
          candidateModelBox!.x + 3,
          candidateModelBox!.y + candidateModelBox!.height / 2,
        );
        await page.mouse.down();
        await page.mouse.move(
          candidateModelBox!.x + candidateModelBox!.width - 3,
          candidateModelBox!.y + candidateModelBox!.height / 2,
          { steps: 8 },
        );
        await page.mouse.up();
        await expect
          .poll(() => page.evaluate(() => window.getSelection()?.toString() || ''))
          .not.toBe('');
        // Price display trims insignificant trailing zeroes under the current
        // candidate renderer; both forms represent the same raw unit price.
        await expect(recentCandidateAfterFactor).toContainText(/0\.021(?:0)?/);
        await expect(recentCandidateAfterFactor).toContainText('0.02205');
        await recentCandidateAfterFactor.screenshot({
          path: testInfo.outputPath('ordinary-sales-non-ladder-factor-price.png'),
        });
        const yunhanCandidate = page.getByTestId(`review-drawer-candidate-${yunhanEvidenceId}`);
        await expect(
          yunhanCandidate.getByTestId('review-drawer-price-comparison'),
          'a usable ladder is the single price presentation',
        ).toHaveCount(0);
        await expect(yunhanCandidate).toContainText('500000–4999999');
        await expect(yunhanCandidate).toContainText('0.0148');
        await expect(yunhanCandidate).toContainText('0.01554');
        await expect(yunhanCandidate).toContainText('当前');
        await expect(yunhanCandidate).toContainText('0.0126');
        const yunhanLadder = page.getByTestId(
          `review-drawer-candidate-${yunhanEvidenceId}-compact-ladder`,
        );
        await expect(yunhanLadder).toBeVisible();
        await yunhanLadder.scrollIntoViewIfNeeded();
        await yunhanLadder.screenshot({
          path: testInfo.outputPath('ordinary-sales-yunhan-factor-ladder.png'),
        });
        await page.getByRole('button', { name: /关闭复核浮层|Close review drawer/ }).click();

        await executeCommand(page, 'qo_quote_common:rollup_cost', {}, quoteId, 'update');
        await exportWorkbook(quoteId, 'sets-factor', SET_COUNT);
      } finally {
        await setupContext.close();
      }

      // 6. Two-phase material reprice. Preview is deliberately non-mutating: the old material,
      // accepted decision, evidence and generated Excel remain authoritative until the user
      // explicitly confirms. The browser calls the protocol-level local Yunhan fake so the gate
      // never spends live API quota.
      step = 'preview material reprice through Yunhan mock';
      await setYunhanMockScenario(page, 'reprice-v2');

      await page.getByRole('tab', { name: /BOM价格|BOM Price/ }).click();
      await page.getByTestId(`table-row-${lineId}`).click();
      await expect(page.getByTestId('review-drawer')).toBeVisible({ timeout: 20_000 });
      await expectLegacyPricingActionsHidden(page);
      await page.getByTestId('review-drawer').screenshot({
        path: testInfo.outputPath('quote-drawer-edit-action-placement.png'),
      });
      const lineBeforePreview = await readDynamicRecord(page, 'qo_quote_line_common', lineId);
      const acceptedBeforePreview = await queryDynamicRecords(
        page,
        'qo_quote_line_price_decision_common',
        [
          { fieldName: 'qo_qlpd_quote_line_id', operator: 'EQ', value: lineId },
          { fieldName: 'qo_qlpd_status', operator: 'EQ', value: 'accepted' },
        ],
      );
      const evidenceBeforePreview = await queryDynamicRecords(page, 'qo_price_evidence_common', [
        { fieldName: 'qo_pe_quote_line_id', operator: 'EQ', value: lineId },
      ]);
      expect(acceptedBeforePreview.length, 'one accepted price exists before preview').toBe(1);

      await page.getByTestId('review-drawer-edit-open').click();
      const exactMode = page.getByRole('radio', { name: /精确型号|Exact MPN/ });
      const specMode = page.getByRole('radio', { name: /规格描述|Specification/ });
      await expect(exactMode).toBeChecked();
      await expect(specMode).not.toBeChecked();
      await expect(
        page.getByTestId('review-drawer-edit-form').locator('input:not([type="radio"])'),
        'the UI exposes one search text input only',
      ).toHaveCount(1);
      const searchInput = page
        .getByTestId('review-drawer-edit-field-searchText')
        .locator('input')
        .first();
      await searchInput.fill(REPRICE_MPN);
      await expectEditActionInsideDrawer(page, 'review-drawer-edit-submit');
      const previewResponsePromise = page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          response
            .url()
            .includes('/api/meta/commands/execute/qo_quote_line_common:preview_reprice'),
        { timeout: 60_000 },
      );
      await page.getByTestId('review-drawer-edit-submit').click();
      const previewResponse = await previewResponsePromise;
      await assertSqlBudget(
        previewResponse,
        'reprice-preview-initial',
        SQL_BUDGET.preview,
        testInfo,
      );
      const previewBody = (await previewResponse.json().catch(() => ({}))) as Record<
        string,
        unknown
      >;
      expect(String(previewBody.code), JSON.stringify(previewBody).slice(0, 800)).toBe('0');
      const previewPanel = page.getByTestId('review-drawer-edit-preview');
      await expect(previewPanel).toBeVisible({ timeout: 20_000 });
      await expect(page.getByTestId('review-drawer-edit-preview-notice')).toContainText(
        '当前报价尚未变更',
      );
      await expect(previewPanel).toContainText(REPRICE_MPN);
      await expect(previewPanel).toContainText('YAGEO');
      await expect(previewPanel).toContainText('KOA Speer');
      await expect(previewPanel).toContainText('10kΩ ±1% 62.5mW 0402 chip resistor');
      const previewComparison = previewPanel.getByTestId(
        'review-drawer-edit-preview-candidate-comparison-table',
      );
      const previewCandidates = previewComparison.locator('tbody > tr');
      await expect(previewCandidates).toHaveCount(2);
      const recommendedCandidate = previewCandidates.filter({ hasText: 'YAGEO' });
      const alternateCandidate = previewCandidates.filter({ hasText: 'KOA Speer' });
      await expect(recommendedCandidate).toHaveAttribute('data-selected', 'true');
      await expect(alternateCandidate).toHaveAttribute('data-selected', 'false');
      await alternateCandidate.getByRole('radio').check();
      await expect(alternateCandidate).toHaveAttribute('data-selected', 'true');
      await expect(recommendedCandidate).toHaveAttribute('data-selected', 'false');
      await recommendedCandidate.getByRole('radio').check();
      await expect(recommendedCandidate).toHaveAttribute('data-selected', 'true');
      // Thin compositions keep the page behind the review overlay mounted. Scope
      // this assertion to the active preview so an unrelated background block
      // cannot be mistaken for the supplier ladder rendered in this drawer.
      await expect(previewPanel.getByTestId('review-drawer-price-comparison')).toHaveCount(0);
      await expect(recommendedCandidate.locator('[data-testid$="-compact-ladder"]')).toBeVisible();
      await expect(recommendedCandidate).toContainText('1000+');
      await expect(recommendedCandidate).toContainText('0.01');
      await expect(recommendedCandidate).toContainText('0.0105');
      await expect(recommendedCandidate).toContainText('当前');
      await expectEditActionInsideDrawer(page, 'review-drawer-edit-confirm');
      await expect(
        recommendedCandidate.locator('[data-testid$="-link-detail_url"]'),
      ).toHaveAttribute('href', 'https://www.ickey.cn/detail/mock/reprice-new.html');

      const originalViewport = page.viewportSize();
      await page.setViewportSize({ width: 1920, height: 1080 });
      await page.getByRole('button', { name: /切换最大化|Toggle maximize/ }).click();
      const editScroll = page.getByTestId('review-drawer-edit-scroll');
      await editScroll.evaluate((element) => element.scrollTo({ top: 0 }));
      await expect(previewPanel).toContainText('2 个候选');
      const scrollBox = await editScroll.boundingBox();
      const recommendedBox = await recommendedCandidate.boundingBox();
      const alternateBox = await alternateCandidate.boundingBox();
      expect(scrollBox).not.toBeNull();
      expect(recommendedBox).not.toBeNull();
      expect(alternateBox).not.toBeNull();
      expect(recommendedBox!.y).toBeGreaterThanOrEqual(scrollBox!.y);
      expect(recommendedBox!.y + recommendedBox!.height).toBeLessThanOrEqual(
        scrollBox!.y + scrollBox!.height,
      );
      expect(alternateBox!.y).toBeGreaterThanOrEqual(scrollBox!.y);
      expect(alternateBox!.y + alternateBox!.height).toBeLessThanOrEqual(
        scrollBox!.y + scrollBox!.height,
      );
      await previewPanel.screenshot({
        path: testInfo.outputPath('ordinary-sales-reprice-preview-before-confirm.png'),
      });
      await page.getByTestId('review-drawer').screenshot({
        path: testInfo.outputPath('ordinary-sales-reprice-preview-actions.png'),
      });
      await page.screenshot({
        path: testInfo.outputPath('ordinary-sales-reprice-multiple-candidates-full.png'),
      });
      const comparisonWrap = previewPanel.getByTestId(
        'review-drawer-edit-preview-candidate-comparison-table-wrap',
      );
      expect(
        await comparisonWrap.evaluate((element) => element.scrollWidth <= element.clientWidth + 1),
        '1920px wide-screen view should show the complete candidate row without horizontal scrolling',
      ).toBe(true);
      await page.setViewportSize({ width: 1440, height: 960 });
      expect(
        await comparisonWrap.evaluate((element) => element.scrollWidth <= element.clientWidth + 1),
        '1440px desktop view should still show the complete candidate row without horizontal scrolling',
      ).toBe(true);
      await expect(
        previewPanel.getByRole('columnheader', { name: /报价 \/ 小计|Quote \/ Subtotal/ }),
      ).toBeInViewport();
      await expect(previewPanel.getByRole('button', { name: /已选择|Selected/ })).toBeInViewport();
      await page.screenshot({
        path: testInfo.outputPath('ordinary-sales-reprice-multiple-candidates-1440.png'),
      });
      await page.getByRole('button', { name: /切换最大化|Toggle maximize/ }).click();
      if (originalViewport) {
        await page.setViewportSize(originalViewport);
      }

      const mockRequestsResponse = await page.request.get(`${mockControlUrl}/__control/requests`);
      expect(mockRequestsResponse.ok(), 'Yunhan mock request audit is readable').toBe(true);
      const mockRequestsBody = (await mockRequestsResponse.json()) as {
        requests?: Array<{ path?: string; form?: Record<string, unknown> }>;
      };
      const singleSearchRequests = (mockRequestsBody.requests ?? []).filter((request) =>
        request.path?.endsWith('/get-single-goods-new'),
      );
      expect(
        singleSearchRequests,
        'one preview must issue one Yunhan single-goods call',
      ).toHaveLength(1);
      const singleSearchRequest = singleSearchRequests[0];
      expect(singleSearchRequest, JSON.stringify(mockRequestsBody).slice(0, 1200)).toBeTruthy();
      expect(singleSearchRequest?.form?.keyword).toEqual([REPRICE_MPN]);
      // The interactive MPN edit is exact-first. A broad request is permitted only
      // after the upstream exact response contains no usable identical MPN; this
      // release-default fixture returns the governed part on the first request, so
      // spending a second request or starting with wide recall is a regression.
      expect(singleSearchRequest?.form?.is_exact_match).toEqual(['1']);
      await testInfo.attach('ordinary-sales-reprice-preview-response.json', {
        body: JSON.stringify(previewBody, null, 2),
        contentType: 'application/json',
      });
      await testInfo.attach('ordinary-sales-yunhan-mock-requests.json', {
        body: JSON.stringify(mockRequestsBody, null, 2),
        contentType: 'application/json',
      });

      const lineDuringPreview = await readDynamicRecord(page, 'qo_quote_line_common', lineId);
      expect(String(lineDuringPreview.qo_ql_mpn ?? '')).toBe(
        String(lineBeforePreview.qo_ql_mpn ?? ''),
      );
      expect(Number(lineDuringPreview.qo_ql_unit_cost)).toBeCloseTo(
        Number(lineBeforePreview.qo_ql_unit_cost),
        6,
      );
      const acceptedDuringPreview = await queryDynamicRecords(
        page,
        'qo_quote_line_price_decision_common',
        [
          { fieldName: 'qo_qlpd_quote_line_id', operator: 'EQ', value: lineId },
          { fieldName: 'qo_qlpd_status', operator: 'EQ', value: 'accepted' },
        ],
      );
      expect(acceptedDuringPreview.map((row) => String(row.pid))).toEqual(
        acceptedBeforePreview.map((row) => String(row.pid)),
      );
      const evidenceDuringPreview = await queryDynamicRecords(page, 'qo_price_evidence_common', [
        { fieldName: 'qo_pe_quote_line_id', operator: 'EQ', value: lineId },
      ]);
      expect(evidenceDuringPreview).toHaveLength(evidenceBeforePreview.length);

      // Regression: closing the outer floating drawer while a preview is open used to leave the
      // parent in edit mode. Clicking the same row then reopened a completely blank drawer.
      await page.getByRole('button', { name: /关闭复核浮层|Close review drawer/ }).click();
      await expect(page.getByTestId('review-drawer')).toBeHidden();
      await page.getByTestId(`table-row-${lineId}`).click();
      await expect(page.getByTestId('review-drawer')).toBeVisible({ timeout: 20_000 });
      await expect(page.getByTestId('review-drawer-content-grid')).not.toHaveClass(
        /(^|\s)hidden(\s|$)/,
      );
      await expect(page.getByTestId('review-drawer-edit-open')).toBeVisible();

      // Recreate the preview once so the explicit inner Cancel contract remains covered as well.
      await page.getByTestId('review-drawer-edit-open').click();
      await page
        .getByTestId('review-drawer-edit-field-searchText')
        .locator('input')
        .fill(REPRICE_MPN);
      const cancelPreviewResponsePromise = page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          response
            .url()
            .includes('/api/meta/commands/execute/qo_quote_line_common:preview_reprice'),
        { timeout: 60_000 },
      );
      await page.getByTestId('review-drawer-edit-submit').click();
      await assertSqlBudget(
        await cancelPreviewResponsePromise,
        'reprice-preview-before-cancel',
        SQL_BUDGET.preview,
        testInfo,
      );
      await expect(page.getByTestId('review-drawer-edit-preview')).toBeVisible({
        timeout: 20_000,
      });

      // Cancel means the preview is discarded from the business flow. The technical preview
      // audit may remain server-side, but quote facts and the next exported workbook stay intact.
      await page.getByTestId('review-drawer-edit-cancel').click();
      await expect(page.getByTestId('review-drawer-edit-preview')).toBeHidden();
      await expect(page.getByTestId('review-drawer-edit-open')).toBeVisible();
      const lineAfterCancel = await readDynamicRecord(page, 'qo_quote_line_common', lineId);
      expect(String(lineAfterCancel.qo_ql_mpn ?? '')).toBe(
        String(lineBeforePreview.qo_ql_mpn ?? ''),
      );
      expect(Number(lineAfterCancel.qo_ql_unit_cost)).toBeCloseTo(
        Number(lineBeforePreview.qo_ql_unit_cost),
        6,
      );
      await exportWorkbook(quoteId, 'reprice-preview-cancel', SET_COUNT, {
        mpn: String(lineBeforePreview.qo_ql_mpn ?? ''),
        unitCost: Number(lineBeforePreview.qo_ql_unit_cost),
      });

      // Run the same explicit exact-model preview again, then adopt it. The confirmation request
      // is generated by the shared renderer with previewId only; trusted price/URL/ladder data is
      // loaded by the server from the preview record.
      step = 'confirm material reprice preview';
      await page.getByRole('tab', { name: /BOM价格|BOM Price/ }).click();
      await page.getByTestId(`table-row-${lineId}`).click();
      await page.getByTestId('review-drawer-edit-open').click();
      await page
        .getByTestId('review-drawer-edit-field-searchText')
        .locator('input')
        .fill(REPRICE_MPN);
      const secondPreviewResponsePromise = page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          response
            .url()
            .includes('/api/meta/commands/execute/qo_quote_line_common:preview_reprice'),
        { timeout: 60_000 },
      );
      await page.getByTestId('review-drawer-edit-submit').click();
      const secondPreviewResponse = await secondPreviewResponsePromise;
      await assertSqlBudget(
        secondPreviewResponse,
        'reprice-preview-before-confirm',
        SQL_BUDGET.preview,
        testInfo,
      );
      const secondPreviewBody = (await secondPreviewResponse.json().catch(() => ({}))) as Record<
        string,
        unknown
      >;
      expect(String(secondPreviewBody.code), JSON.stringify(secondPreviewBody).slice(0, 800)).toBe(
        '0',
      );
      await expect(page.getByTestId('review-drawer-edit-confirm')).toBeVisible({
        timeout: 20_000,
      });
      const confirmResponsePromise = page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          response
            .url()
            .includes('/api/meta/commands/execute/qo_quote_line_common:confirm_reprice_preview'),
        { timeout: 60_000 },
      );
      await page.getByTestId('review-drawer-edit-confirm').click();
      const confirmResponse = await confirmResponsePromise;
      await assertSqlBudget(confirmResponse, 'reprice-confirm', SQL_BUDGET.confirm, testInfo);
      const confirmBody = (await confirmResponse.json().catch(() => ({}))) as Record<
        string,
        unknown
      >;
      expect(String(confirmBody.code), JSON.stringify(confirmBody).slice(0, 1000)).toBe('0');
      await testInfo.attach('ordinary-sales-reprice-confirm-response.json', {
        body: JSON.stringify(confirmBody, null, 2),
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
                  String(row.qo_pe_status ?? '') === 'confirmed' &&
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

      const refreshedLine = await readDynamicRecord(page, 'qo_quote_line_common', lineId);
      expect(String(refreshedLine.qo_ql_mpn ?? '')).toBe(REPRICE_MPN);
      expect(String(refreshedLine.qo_ql_package ?? '')).toBe('0402');
      const refreshedUnitCost = Number(refreshedLine.qo_ql_unit_cost);
      expect(refreshedUnitCost).toBeCloseTo(0.01 * (PRICE_FACTOR / 100), 6);
      await exportWorkbook(quoteId, 'reprice', SET_COUNT, {
        mpn: REPRICE_MPN,
        unitCost: refreshedUnitCost,
      });
      await page.getByRole('tab', { name: /BOM价格|BOM Price/ }).click();
      await expect(page.getByTestId(`table-row-${lineId}`)).toBeVisible({ timeout: 20_000 });
      await page.screenshot({
        path: testInfo.outputPath('ordinary-sales-reprice-result.png'),
        fullPage: true,
      });

      // The admin-driven exporter's generate_document legitimately refreshes the
      // derived cost rows as the administrator. Re-roll the cost and regenerate
      // the document as the sales owner (sales holds qo.document.generate) so the
      // SELF-isolation assertion below stays an honest fixture.
      step = 'refresh cost rows as the sales owner';
      await executeCommand(page, 'qo_quote_common:rollup_cost', {}, quoteId, 'update');
      await executeCommand(page, 'qo_quote_common:generate_document', {}, quoteId);

      // 8. A second sales employee has the same capability atoms but cannot read any record
      // owned by the first employee. This explicitly covers quote child records involved in
      // recompute/export/reprice, not just the quote root. Run this before the administrator
      // path: an ALL-scope export legitimately refreshes derived cost rows as the administrator,
      // which would no longer be an honest fixture for a SELF-owner isolation assertion.
      step = 'cross-employee SELF isolation';
      const ownedRepricePreviews = await queryDynamicRecords(page, 'qo_reprice_preview_common', [
        { fieldName: 'qo_rp_quote_line_id', operator: 'EQ', value: lineId },
      ]);
      const ownedRecordGroups: Array<{ model: string; records: Record<string, unknown>[] }> = [
        { model: 'qo_quote_common', records: [{ pid: quoteId }] },
        { model: 'qo_quote_line_common', records: [{ pid: lineId }] },
        { model: 'qo_reprice_preview_common', records: ownedRepricePreviews },
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
        for (const command of [
          'qo_quote_line_common:preview_reprice',
          'qo_quote_line_common:confirm_reprice_preview',
        ]) {
          const denied = await other.page.request.post(`/api/meta/commands/execute/${command}`, {
            data: {
              targetRecordPid: lineId,
              operationType: 'UPDATE',
              payload:
                command === 'qo_quote_line_common:preview_reprice'
                  ? { searchMode: 'exact', searchText: 'MUST-NOT-QUERY' }
                  : { previewId: 'FOREIGN-PREVIEW' },
            },
          });
          const deniedBody = (await denied.json().catch(() => ({}))) as Record<string, unknown>;
          expect(
            denied.ok() && String(deniedBody.code ?? '') === '0',
            `${command} must not execute against another employee's line`,
          ).toBe(false);
        }
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

      // 9. An administrator executes the same preview + confirm path against the sales-owned
      // quote, proving ALL-scope behavior. The resulting workbook must remain consistent.
      step = 'admin preview, confirm and export';
      const adminGoldenContext = await browser.newContext({
        storageState: { cookies: [], origins: [] },
      });
      try {
        const adminPage = await adminGoldenContext.newPage();
        await loginViaUI(adminPage, ADMIN_EMAIL, ADMIN_PASSWORD);
        await setYunhanMockScenario(adminPage, 'reprice-v2');
        await openQuoteDetailFromList(adminPage, created);
        await adminPage.getByRole('tab', { name: /BOM价格|BOM Price/ }).click();
        await adminPage.getByTestId(`table-row-${lineId}`).click();
        await expectLegacyPricingActionsHidden(adminPage);
        await adminPage.getByTestId('review-drawer-edit-open').click();
        await adminPage
          .getByTestId('review-drawer-edit-field-searchText')
          .locator('input')
          .fill(REPRICE_MPN);
        await expectEditActionInsideDrawer(adminPage, 'review-drawer-edit-submit');
        const adminPreviewResponsePromise = adminPage.waitForResponse(
          (response) =>
            response.request().method() === 'POST' &&
            response
              .url()
              .includes('/api/meta/commands/execute/qo_quote_line_common:preview_reprice'),
          { timeout: 60_000 },
        );
        await adminPage.getByTestId('review-drawer-edit-submit').click();
        await assertSqlBudget(
          await adminPreviewResponsePromise,
          'admin-reprice-preview',
          SQL_BUDGET.preview,
          testInfo,
        );
        const adminPreview = adminPage.getByTestId('review-drawer-edit-preview');
        await expect(adminPreview).toBeVisible({ timeout: 20_000 });
        await adminPage
          .getByTestId('review-drawer-edit-scroll')
          .evaluate((element) => element.scrollTo({ top: element.scrollHeight }));
        await expectEditActionInsideDrawer(adminPage, 'review-drawer-edit-confirm');
        await adminPreview.screenshot({
          path: testInfo.outputPath('admin-reprice-preview-before-confirm.png'),
        });
        await adminPage.getByTestId('review-drawer').screenshot({
          path: testInfo.outputPath('admin-reprice-preview-actions.png'),
        });
        const adminConfirmResponsePromise = adminPage.waitForResponse(
          (response) =>
            response.request().method() === 'POST' &&
            response
              .url()
              .includes('/api/meta/commands/execute/qo_quote_line_common:confirm_reprice_preview'),
          { timeout: 60_000 },
        );
        await adminPage.getByTestId('review-drawer-edit-confirm').click();
        const adminConfirmResponse = await adminConfirmResponsePromise;
        await assertSqlBudget(
          adminConfirmResponse,
          'admin-reprice-confirm',
          SQL_BUDGET.confirm,
          testInfo,
        );
        const adminConfirmBody = (await adminConfirmResponse.json().catch(() => ({}))) as Record<
          string,
          unknown
        >;
        expect(String(adminConfirmBody.code), JSON.stringify(adminConfirmBody).slice(0, 1000)).toBe(
          '0',
        );
        const adminLine = await readDynamicRecord(adminPage, 'qo_quote_line_common', lineId);
        await generateAndValidateWorkbook(
          adminPage,
          quoteId,
          'admin-reprice-confirm',
          SET_COUNT,
          testInfo,
          {
            mpn: REPRICE_MPN,
            unitCost: Number(adminLine.qo_ql_unit_cost),
          },
        );
        await adminPage.getByRole('tab', { name: /BOM价格|BOM Price/ }).click();
        await expect(adminPage.getByTestId(`table-row-${lineId}`)).toBeVisible({
          timeout: 20_000,
        });
        await adminPage.screenshot({
          path: testInfo.outputPath('admin-reprice-confirm-result.png'),
          fullPage: true,
        });
      } finally {
        await adminGoldenContext.close();
      }

      // 10. hard gates
      expect(consoleIssues, `console issues:\n${consoleIssues.join('\n')}`).toEqual([]);
      const hits = forbidden.map((h) => `[${h.step}] ${h.status} ${h.url}`);
      expect(hits, `forbidden API hits as qo_sales:\n${hits.join('\n')}`).toEqual([]);
    } finally {
      await exporterContext?.close().catch(() => {});
      await context.close();
    }
  });
});
