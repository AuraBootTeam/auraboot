import {
  expect,
  test,
  type APIResponse,
  type Browser,
  type BrowserContext,
  type FileChooser,
  type Locator,
  type Page,
  type Response,
  type TestInfo,
} from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { read as xlsxRead, utils as XLSXUtils, write as xlsxWrite } from 'xlsx';
import { ensureSidebarExpanded, waitForFormReady } from '../helpers';

const ADMIN_EMAIL = 'admin@auraboot.com';
const PASSWORD = 'Test2026x';

type CommandResult = Record<string, unknown>;

// The first command after plugin import loads the expanded recycle-rule command/model,
// binding, field-mask, SoD and role-scope metadata once. A fresh slot measured 128 SQL;
// the same real command remains <= 100 after those bounded caches are populated.
const COLD_CLAIM_SQL_BUDGET = 130;
const STEADY_COMMAND_SQL_BUDGET = 100;
// The first successful permanent purge initializes metadata for the pool projection,
// ownership history and both activity relation shapes. The measured cold path is 102 SQL;
// the second item in the same batch is 85, so keep a narrow cold-only ceiling.
const COLD_CUSTOMER_DELETE_SQL_BUDGET = 110;
const REJECT_SQL_BUDGET = 75;
const MEMBER_OPERATIONS_SCENARIO =
  'member scope, quick edit, chart, exports, guarded delete, batch update, claim and assignment persist on the real stack';
const GOVERNANCE_RECYCLE_SCENARIO =
  'administrator governs quick pool policy, capacity, relative and fixed recycle rules, and recent activity blocks recycle';
const IMPORT_MOBILE_SCENARIO =
  'administrator downloads, prechecks and imports XLSX customers while mobile sales reviews customer, activity and ownership tabs';
const CUSTOMER_POOL_SCENARIOS = [
  MEMBER_OPERATIONS_SCENARIO,
  GOVERNANCE_RECYCLE_SCENARIO,
  IMPORT_MOBILE_SCENARIO,
];
const completedScenarios = new Set<string>();
const failedScenarios = new Set<string>();
const runtimeScreenshots = new Set<string>();

async function attachScreenshot(page: Page, testInfo: TestInfo, name: string): Promise<void> {
  const screenshotPath = testInfo.outputPath(`${name}.png`);
  await page.screenshot({ path: screenshotPath });
  runtimeScreenshots.add(screenshotPath);
  await testInfo.attach(name, { path: screenshotPath });
}

async function attachJson(testInfo: TestInfo, name: string, value: unknown): Promise<void> {
  const jsonPath = testInfo.outputPath(name);
  fs.writeFileSync(jsonPath, `${JSON.stringify(value, null, 2)}\n`);
  await testInfo.attach(name, { path: jsonPath, contentType: 'application/json' });
}

async function captureWorkbookExport(
  page: Page,
  testInfo: TestInfo,
  name: string,
  expectedNames: string[],
  unexpectedNames: string[],
  action: () => Promise<void>,
): Promise<Record<string, unknown>> {
  const responsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes('/api/dynamic/crm_customer_pool_item_common/export'),
    { timeout: 20_000 },
  );
  const downloadPromise = page.waitForEvent('download', { timeout: 20_000 });
  await action();
  const [response, download] = await Promise.all([responsePromise, downloadPromise]);
  const responseBody = await response.json().catch(() => ({}));
  expect(
    response.ok() && String(responseBody?.code) === '0',
    `${name} export failed: HTTP ${response.status()} ${JSON.stringify(responseBody).slice(0, 800)}`,
  ).toBe(true);
  expect(download.suggestedFilename(), `${name} filename`).toMatch(/\.xlsx$/i);
  const destination = testInfo.outputPath(`${name}.xlsx`);
  await download.saveAs(destination);
  const workbookBytes = fs.readFileSync(destination);
  expect(workbookBytes.subarray(0, 4).toString('hex'), `${name} must be a real XLSX`).toBe(
    '504b0304',
  );
  const workbook = xlsxRead(workbookBytes, { type: 'buffer' });
  expect(workbook.SheetNames.length, `${name} workbook sheet count`).toBeGreaterThan(0);
  const rows = XLSXUtils.sheet_to_json<unknown[]>(workbook.Sheets[workbook.SheetNames[0]], {
    header: 1,
    defval: '',
  });
  const workbookText = JSON.stringify(rows);
  for (const expected of expectedNames) {
    expect(workbookText, `${name} must contain ${expected}`).toContain(expected);
  }
  for (const unexpected of unexpectedNames) {
    expect(workbookText, `${name} must exclude ${unexpected}`).not.toContain(unexpected);
  }
  await testInfo.attach(name, {
    path: destination,
    contentType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  });
  return (response.request().postDataJSON() ?? {}) as Record<string, unknown>;
}

function writeRuntimeReceipt(): void {
  const receiptPath = process.env.CUSTOMER_POOL_RUNTIME_RECEIPT;
  if (!receiptPath) return;
  const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../../..');
  const manifest = JSON.parse(
    fs.readFileSync(path.join(repoRoot, 'plugins/crm/coverage-manifest.json'), 'utf8'),
  );
  const contract = manifest.runtimeEvidenceContracts.find(
    (candidate: { id?: string }) => candidate.id === 'CRM-CUSTOMER-POOL',
  );
  if (!contract?.expectedCoverage) {
    throw new Error('CRM-CUSTOMER-POOL runtime evidence contract is missing');
  }
  const completed = CUSTOMER_POOL_SCENARIOS.filter((scenario) => completedScenarios.has(scenario));
  const verdict =
    completed.length === CUSTOMER_POOL_SCENARIOS.length && failedScenarios.size === 0
      ? 'pass'
      : 'fail';
  const coverage = Object.fromEntries(
    Object.entries(contract.expectedCoverage).map(([axis, expected]) => [
      axis,
      {
        expected,
        completed: verdict === 'pass' ? expected : [],
      },
    ]),
  );
  const receipt = {
    schemaVersion: 1,
    runId: process.env.AURA_RUNTIME_NAME ?? `crm-customer-pool-${Date.now()}`,
    createdAt: new Date().toISOString(),
    verdict,
    fixtureMode: 'self-seeded',
    dataMigration: 'out-of-scope-development-stage',
    expectedScenarios: CUSTOMER_POOL_SCENARIOS,
    completedScenarios: completed,
    failedScenarios: [...failedScenarios],
    screenshots: [...runtimeScreenshots],
    coverage,
  };
  fs.mkdirSync(path.dirname(receiptPath), { recursive: true });
  fs.writeFileSync(receiptPath, `${JSON.stringify(receipt, null, 2)}\n`);
}

async function navigateToCrmMenu(page: Page, section: RegExp, href: string): Promise<void> {
  await ensureSidebarExpanded(page);
  if ((page.viewportSize()?.width ?? 1280) <= 640) {
    const mobileMenu = page.getByRole('button', {
      name: /打开导航菜单|Open navigation menu/i,
    });
    const mobileSidebar = page.getByTestId('sidebar');
    await expect(mobileMenu).toBeVisible({ timeout: 8_000 });
    for (let attempt = 0; attempt < 2; attempt += 1) {
      await mobileMenu.click();
      await page.waitForTimeout(250);
      const sidebarBox = await mobileSidebar.boundingBox();
      if (sidebarBox && sidebarBox.x >= -1) break;
    }
    await expect
      .poll(async () => (await mobileSidebar.boundingBox())?.x ?? -999, { timeout: 5_000 })
      .toBeGreaterThanOrEqual(-1);
  }
  const nav = page.locator('nav, aside, [role="navigation"]').first();
  const leaf = nav.locator(`a[href="${href}"]`).first();
  if (!(await leaf.isVisible().catch(() => false))) {
    const crmRoot = nav.getByRole('button', { name: /客户关系管理|CRM/i }).first();
    if (await crmRoot.isVisible().catch(() => false)) await crmRoot.click();
  }
  if (!(await leaf.isVisible().catch(() => false))) {
    await nav.getByRole('button', { name: section }).first().click();
  }
  await expect(leaf, `CRM sidebar leaf ${href}`).toBeVisible({ timeout: 8_000 });
  await leaf.click();
  await expect(page).toHaveURL(new RegExp(href.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')));
}

async function navigateToCustomerPoolStrategy(
  page: Page,
  tab: 'pools' | 'capacity' | 'recycle' = 'pools',
): Promise<void> {
  await navigateToCrmMenu(page, /运营与配置|Operations/i, '/p/c/crm_customer_pool_strategy');
  const main = page.locator('main');
  await expect(
    main.getByRole('heading', { name: /客户公海策略|Customer Pool Strategy/, exact: true }),
  ).toBeVisible({ timeout: 20_000 });
  await expect(main.getByTestId('metric-strip-item-enabled_pools')).toBeVisible();
  await expect(main.getByText(/搜索公海策略|Search Pool Policies/, { exact: true })).toBeVisible();
  const tabNames = {
    pools: /公海策略|Pool Policies/,
    capacity: /人员库容|User Capacity/,
    recycle: /回收条件|Recycle Conditions/,
  };
  const targetTab = main.getByRole('tab', { name: tabNames[tab] });
  await expect(targetTab).toBeVisible();
  if ((await targetTab.getAttribute('aria-selected')) !== 'true') {
    await targetTab.click();
  }
  await expect(targetTab).toHaveAttribute('aria-selected', 'true');
}

function assertSqlCount(
  rawCount: string | null | undefined,
  label: string,
  budget: number,
): number {
  expect(rawCount, `${label} must expose X-SQL-Count from the real backend`).toBeTruthy();
  const count = Number(rawCount);
  expect(Number.isInteger(count) && count >= 0, `${label} invalid X-SQL-Count=${rawCount}`).toBe(
    true,
  );
  expect(count, `${label} exceeded SQL budget ${budget}`).toBeLessThanOrEqual(budget);
  return count;
}

function assertApiSqlCount(response: APIResponse, label: string, budget: number): number {
  return assertSqlCount(response.headers()['x-sql-count'], label, budget);
}

async function assertUiCommandResponse(
  response: Response,
  label: string,
  budget: number,
): Promise<number> {
  const body = await response.json().catch(() => ({}));
  expect(
    response.ok(),
    `${label} failed with HTTP ${response.status()}: ${JSON.stringify(body).slice(0, 800)}`,
  ).toBe(true);
  expect(String(body?.code), `${label} failed: ${JSON.stringify(body).slice(0, 800)}`).toBe('0');
  return assertSqlCount(await response.headerValue('x-sql-count'), label, budget);
}

async function captureUiCommandSqlCount(
  page: Page,
  commandCode: string,
  label: string,
  budget: number,
  action: () => Promise<void>,
): Promise<number> {
  const [response] = await Promise.all([
    page.waitForResponse(
      (candidate) =>
        candidate.request().method() === 'POST' &&
        candidate.url().includes(`/api/meta/commands/execute/${commandCode}`),
      { timeout: 20_000 },
    ),
    action(),
  ]);
  return assertUiCommandResponse(response, label, budget);
}

async function captureUiCommandSqlCounts(
  page: Page,
  commandCode: string,
  expectedCount: number,
  label: string,
  budget: number,
  action: () => Promise<void>,
): Promise<number[]> {
  const responses: Response[] = [];
  const listener = (response: Response): void => {
    if (
      response.request().method() === 'POST' &&
      response.url().includes(`/api/meta/commands/execute/${commandCode}`)
    ) {
      responses.push(response);
    }
  };
  page.on('response', listener);
  try {
    await action();
    await expect.poll(() => responses.length, { timeout: 20_000 }).toBe(expectedCount);
    return await Promise.all(
      responses.map((response, index) =>
        assertUiCommandResponse(response, `${label} ${index + 1}`, budget),
      ),
    );
  } finally {
    page.off('response', listener);
  }
}

async function captureNamedQueryResponses(
  page: Page,
  queryCodes: string[],
  action: () => Promise<void>,
): Promise<Record<string, unknown>> {
  const responses = new Map<string, Response>();
  const listener = (response: Response): void => {
    const url = new URL(response.url());
    const datasourceId = url.searchParams.get('datasourceId');
    const code = queryCodes.find((candidate) => datasourceId === `nq:${candidate}`);
    if (code && url.pathname === '/api/datasource/list' && response.request().method() === 'GET') {
      responses.set(code, response);
    }
  };
  page.on('response', listener);
  try {
    await action();
    await expect.poll(() => responses.size, { timeout: 20_000 }).toBe(queryCodes.length);
    const bodies: Record<string, unknown> = {};
    for (const code of queryCodes) {
      const response = responses.get(code);
      expect(
        response,
        `${code} response must be observed from the rendered workbench`,
      ).toBeTruthy();
      expect(response!.ok(), `${code} must return HTTP success`).toBe(true);
      const body = await response!.json().catch(() => ({}));
      expect(String(body?.code), `${code} application response`).toBe('0');
      bodies[code] = body?.data;
    }
    return bodies;
  } finally {
    page.off('response', listener);
  }
}

function queryRows(body: unknown): Record<string, unknown>[] {
  const value = body as Record<string, unknown> | undefined;
  const candidates = [value?.records, value?.rows, value?.content, value?.data, body];
  const rows = candidates.find(Array.isArray);
  return Array.isArray(rows) ? (rows as Record<string, unknown>[]) : [];
}

function customerPoolWorkbook(rows: unknown[][]): Buffer {
  const workbook = XLSXUtils.book_new();
  XLSXUtils.book_append_sheet(
    workbook,
    XLSXUtils.aoa_to_sheet([
      [
        'crm_acc_code',
        'crm_acc_name',
        'crm_acc_industry',
        'crm_acc_website',
        'crm_acc_phone',
        'crm_acc_address',
        'crm_acc_rating',
        'crm_acc_status',
        'crm_acc_remark',
      ],
      ...rows,
    ]),
    '客户公海导入',
  );
  return xlsxWrite(workbook, { type: 'buffer', bookType: 'xlsx' }) as Buffer;
}

async function uploadWorkbook(page: Page, name: string, buffer: Buffer): Promise<string> {
  const response = await page.request.post('/api/file/upload', {
    multipart: {
      file: {
        name,
        mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        buffer,
      },
    },
  });
  const body = await response.json().catch(() => ({}));
  expect(
    response.ok() && String(body?.code) === '0',
    `workbook upload failed: HTTP ${response.status()} ${JSON.stringify(body).slice(0, 800)}`,
  ).toBe(true);
  const fileId = String(body?.data?.fileId ?? body?.fileId ?? '');
  expect(fileId, 'workbook upload must return a public file id').toBeTruthy();
  return fileId;
}

async function dynamicListRows(
  page: Page,
  modelCode: string,
  size = 500,
): Promise<Record<string, unknown>[]> {
  const response = await page.request.get(
    `/api/dynamic/${modelCode}/list?pageNum=1&pageSize=${size}`,
  );
  const body = await response.json().catch(() => ({}));
  expect(
    response.ok() && String(body?.code) === '0',
    `${modelCode} list failed: HTTP ${response.status()} ${JSON.stringify(body).slice(0, 800)}`,
  ).toBe(true);
  return queryRows(body?.data);
}

function companyCell(page: Page, company: string) {
  return page.getByRole('cell', { name: company, exact: true });
}

function formField(page: Page, fieldCode: string): Locator {
  return page.locator(`[data-testid="form-field-${fieldCode}"]`).first();
}

async function fillFormField(page: Page, fieldCode: string, value: string): Promise<void> {
  const control = page
    .locator(
      `[data-testid="form-field-${fieldCode}"] input:not([type="hidden"]), ` +
        `[data-testid="form-field-${fieldCode}"] textarea`,
    )
    .first();
  await expect(control, `${fieldCode} form control`).toBeVisible({ timeout: 10_000 });
  await control.fill(value);
}

async function pickSmartSelect(
  page: Page,
  fieldCode: string,
  optionName: RegExp | string,
): Promise<void> {
  const trigger = page.getByTestId(`select-trigger-${fieldCode}`).first();
  await expect(trigger, `${fieldCode} select trigger`).toBeVisible({ timeout: 10_000 });
  await trigger.click();
  const option = page.getByRole('option', { name: optionName }).first();
  await expect(option, `${fieldCode} option ${String(optionName)}`).toBeVisible({
    timeout: 10_000,
  });
  await option.click();
}

async function pickReference(
  page: Page,
  fieldCode: string,
  recordPid: string,
  expectedLabel: string,
): Promise<void> {
  const trigger = page.getByTestId(`select-trigger-${fieldCode}`).first();
  await expect(trigger, `${fieldCode} reference trigger`).toBeVisible({ timeout: 10_000 });
  await trigger.click();
  const option = page.locator(`[role="option"][data-value="${recordPid}"]`).first();
  await expect(option, `${fieldCode} reference ${recordPid}`).toBeVisible({ timeout: 10_000 });
  await option.click();
  await expect(trigger).toContainText(expectedLabel);
}

async function pickMemberInForm(
  page: Page,
  fieldCode: string,
  userPid: string,
  searchText: string,
): Promise<void> {
  const field = formField(page, fieldCode);
  await field.scrollIntoViewIfNeeded();
  await field.getByTestId('member-picker-add').click();
  const popup = field.getByTestId('member-picker-popup');
  await expect(popup).toBeVisible({ timeout: 10_000 });
  await popup.getByTestId('member-picker-search-input').fill(searchText);
  await popup.getByTestId(`member-picker-option-${userPid}`).click();
  await page.keyboard.press('Escape').catch(() => null);
  await expect(field.getByTestId(`member-picker-selected-${userPid}`)).toBeVisible();
}

async function pickUserInForm(page: Page, fieldCode: string, searchText: string): Promise<void> {
  const trigger = page.getByTestId(`user-select-trigger-${fieldCode}`).first();
  await expect(trigger, `${fieldCode} user trigger`).toBeVisible({ timeout: 10_000 });
  await trigger.click();
  await page.getByTestId(`user-select-search-${fieldCode}`).fill(searchText);
  const option = page.locator(`[data-testid^="user-select-option-${fieldCode}-"]`, {
    hasText: searchText,
  });
  await expect(option.first(), `${fieldCode} user option ${searchText}`).toBeVisible({
    timeout: 10_000,
  });
  await option.first().click();
}

async function setBooleanFormField(page: Page, fieldCode: string, checked: boolean): Promise<void> {
  const field = formField(page, fieldCode);
  const control = field.locator('button[role="switch"], input[type="checkbox"]').first();
  await expect(control, `${fieldCode} boolean control`).toBeVisible({ timeout: 10_000 });
  const current =
    (await control.getAttribute('aria-checked')) === 'true' ||
    (await control.isChecked().catch(() => false));
  if (current !== checked) await control.click();
  await expect
    .poll(async () => {
      return (
        (await control.getAttribute('aria-checked')) === 'true' ||
        (await control.isChecked().catch(() => false))
      );
    })
    .toBe(checked);
}

async function submitFormCommand(page: Page, commandCode: string): Promise<CommandResult> {
  const responsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes(`/api/meta/commands/execute/${commandCode}`),
    { timeout: 20_000 },
  );
  const submit = page.getByTestId('form-btn-submit');
  await expect(submit).toBeEnabled({ timeout: 10_000 });
  await submit.click();
  const response = await responsePromise;
  const body = await response.json().catch(() => ({}));
  expect(
    response.ok() && String(body?.code) === '0',
    `${commandCode} UI form failed: HTTP ${response.status()} ${JSON.stringify(body).slice(0, 1_000)}`,
  ).toBe(true);
  return body?.data?.data ?? body?.data ?? {};
}

async function clickRowAction(
  page: Page,
  row: Locator,
  actionCode: string,
  actionName: RegExp,
): Promise<void> {
  const direct = row.getByRole('button', { name: actionName }).first();
  if (await direct.isVisible().catch(() => false)) {
    await expect(direct, `row action ${actionCode}`).toBeVisible({ timeout: 15_000 });
    await direct.click({ timeout: 15_000 });
    return;
  }
  const directLink = row.getByRole('link', { name: actionName }).first();
  if (await directLink.isVisible().catch(() => false)) {
    await expect(directLink, `row action ${actionCode}`).toBeVisible({ timeout: 15_000 });
    await directLink.click({ timeout: 15_000 });
    return;
  }
  await row.getByTestId('row-action-more').click();
  const menuAction = page.getByTestId(`row-action-${actionCode}`).last();
  await expect(menuAction, `row action ${actionCode}`).toBeVisible({ timeout: 5_000 });
  await menuAction.click();
}

async function runCustomerPoolImportAction(
  page: Page,
  poolRow: Locator,
  actionCode: 'precheck_import' | 'import_customers',
  commandCode: 'crm:precheck_customer_pool_import' | 'crm:import_customer_pool_customers',
  workbookName: string,
  workbook: Buffer,
  skipErrors = false,
): Promise<Response> {
  await clickRowAction(
    page,
    poolRow,
    actionCode,
    actionCode === 'precheck_import'
      ? /预检导入文件|Pre-check Import File/
      : /正式导入客户|Import Customers/,
  );
  const form = page.getByTestId('form-dialog');
  await expect(form).toBeVisible({ timeout: 10_000 });
  await expect(form.getByTestId('form-dialog-field-importType')).toBeVisible();
  if (actionCode === 'import_customers' && skipErrors) {
    await form
      .getByTestId('form-dialog-field-skipErrors')
      .locator('input[type="checkbox"]')
      .check();
  }
  const fileInput = form.getByTestId('form-dialog-field-importFileId');
  await expect(fileInput).toHaveAttribute('type', 'file');
  await fileInput.setInputFiles({
    name: workbookName,
    mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    buffer: workbook,
  });
  await expect(form).toContainText(workbookName);
  let secondPickerOpened = false;
  const secondPickerGuard = async (chooser: FileChooser): Promise<void> => {
    secondPickerOpened = true;
    await chooser.setFiles({
      name: workbookName,
      mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      buffer: workbook,
    });
  };
  page.on('filechooser', secondPickerGuard);
  const responsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      response.url().includes(`/api/meta/commands/execute/${commandCode}`),
    { timeout: 30_000 },
  );
  await form.getByTestId('form-dialog-submit').click();
  const response = await responsePromise.finally(() => {
    page.off('filechooser', secondPickerGuard);
  });
  expect(secondPickerOpened, 'inline import must not open a second OS file picker').toBe(false);
  const body = await response.json().catch(() => ({}));
  expect(
    response.ok() && String(body?.code) === '0',
    `${commandCode} dispatch failed: HTTP ${response.status()} ${JSON.stringify(body).slice(0, 800)}`,
  ).toBe(true);
  return response;
}

async function closeAsyncTaskModal(page: Page): Promise<void> {
  const closeButton = page.getByRole('button', { name: '关闭', exact: true }).last();
  await expect(closeButton).toBeVisible({ timeout: 20_000 });
  await closeButton.click();
}

async function confirmDialog(page: Page): Promise<void> {
  const dialog = page.getByRole('dialog').last();
  await expect(dialog).toBeVisible({ timeout: 5_000 });
  await dialog
    .getByRole('button', { name: /确认|确定|继续|删除|Confirm|Continue|Delete/i })
    .last()
    .click();
}

async function uiLogin(page: Page, email: string): Promise<void> {
  await page.goto('/login', { waitUntil: 'domcontentloaded' });
  // Do not type into the server-rendered form before React hydration; hydration
  // would legitimately replace the node and clear the pre-hydration value.
  await page.waitForLoadState('networkidle', { timeout: 10_000 }).catch(() => {});
  const identifier = page
    .locator('input[placeholder*="用户名"], input[name="identifier"], input[type="email"]')
    .first();
  if (await identifier.isVisible({ timeout: 5_000 }).catch(() => false)) {
    await identifier.fill(email);
    await page.getByRole('textbox', { name: '密码' }).fill(PASSWORD);
    await expect(identifier).toHaveValue(email);
    await page.getByRole('button', { name: '立即登录', exact: true }).click();
    await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 20_000 });
    const loginResponse = await page.request.get('/api/auth/me');
    const loginBody = await loginResponse.json().catch(() => ({}));
    expect(
      loginResponse.ok() && String(loginBody?.code) === '0',
      `authenticated session ${email} failed: HTTP ${loginResponse.status()} ${JSON.stringify(loginBody).slice(0, 800)}`,
    ).toBe(true);
  }
}

async function command(
  page: Page,
  code: string,
  payload: Record<string, unknown> = {},
  targetRecordPid?: string,
  operationType?: string,
): Promise<CommandResult> {
  const response = await page.request.post(`/api/meta/commands/execute/${code}`, {
    data: {
      payload,
      ...(targetRecordPid ? { targetRecordPid } : {}),
      ...(operationType ? { operationType } : {}),
    },
    timeout: 20_000,
  });
  const body = await response.json().catch(() => ({}));
  expect(
    response.ok() && String(body?.code) === '0',
    `${code} failed: HTTP ${response.status()} ${JSON.stringify(body).slice(0, 1_000)}`,
  ).toBe(true);
  return body?.data?.data ?? {};
}

function recordPid(result: CommandResult): string {
  return String(
    result.recordId ?? result.recordPid ?? result.publicRecordId ?? result.pid ?? result.id ?? '',
  );
}

async function provisionUser(
  page: Page,
  stamp: string,
  kind: 'sales' | 'manager' | 'viewer',
  roleCodes: string[],
): Promise<{ email: string; pid: string }> {
  const email = `crm-customer-pool-${kind}-${stamp}@e2e.local`;
  const response = await page.request.post('/api/admin/users', {
    data: {
      email,
      displayName: `Customer Pool ${
        kind === 'sales' ? 'Sales' : kind === 'manager' ? 'Manager' : 'Viewer'
      } ${stamp}`,
      initialPassword: PASSWORD,
      roleCodes,
      sendInviteEmail: false,
    },
  });
  const body = await response.json().catch(() => ({}));
  expect(
    response.ok() && String(body?.code) === '0',
    `provision ${kind} failed: HTTP ${response.status()} ${JSON.stringify(body).slice(0, 800)}`,
  ).toBe(true);
  const pid = String(body?.data?.pid ?? body?.data?.userPid ?? '');
  expect(pid, 'provisioned sales user public pid').toBeTruthy();
  return { email, pid };
}

async function createCustomerAndMove(
  page: Page,
  poolPid: string,
  customerName: string,
  index: number,
): Promise<{ customerPid: string; itemPid: string }> {
  const customer = await command(
    page,
    'crm:create_account',
    {
      crm_acc_name: customerName,
      crm_acc_industry: 'technology',
      crm_acc_phone: `1390000${String(index).padStart(4, '0')}`,
      crm_acc_rating: index % 2 === 0 ? 'A' : 'B',
      crm_acc_status: 'active',
      crm_acc_remark: 'CordysCRM PAR-06 customer-pool acceptance fixture',
    },
    undefined,
    'create',
  );
  const customerPid = recordPid(customer);
  expect(customerPid, `created customer ${customerName}`).toBeTruthy();
  const moved = await command(
    page,
    'crm:move_customer_to_pool',
    { poolId: poolPid, reason: 'CordysCRM W1 automated parity journey' },
    customerPid,
  );
  const itemPid = String(moved.poolItemId ?? '');
  expect(itemPid, `pool item for ${customerName}`).toBeTruthy();
  return { customerPid, itemPid };
}

async function openAs(
  browser: Browser,
  baseURL: string,
  email: string,
): Promise<{
  context: BrowserContext;
  page: Page;
}> {
  const context = await browser.newContext({ baseURL, storageState: { cookies: [], origins: [] } });
  const page = await context.newPage();
  await uiLogin(page, email);
  return { context, page };
}

test.describe('CRM customer-pool Cordys parity W1', () => {
  test.setTimeout(300_000);

  test.beforeAll(() => {
    completedScenarios.clear();
    failedScenarios.clear();
    runtimeScreenshots.clear();
    const receiptPath = process.env.CUSTOMER_POOL_RUNTIME_RECEIPT;
    if (receiptPath) fs.rmSync(receiptPath, { force: true });
  });

  test.afterEach(({}, testInfo) => {
    if (!CUSTOMER_POOL_SCENARIOS.includes(testInfo.title)) return;
    if (testInfo.status === testInfo.expectedStatus) completedScenarios.add(testInfo.title);
    else failedScenarios.add(testInfo.title);
  });

  test.afterAll(() => {
    writeRuntimeReceipt();
  });

  test(MEMBER_OPERATIONS_SCENARIO, async ({ page, browser, baseURL }, testInfo) => {
    const sqlEvidence: Record<string, number> = {};
    await uiLogin(page, ADMIN_EMAIL);
    const stamp = `${Date.now()}`;
    const meResponse = await page.request.get('/api/auth/me');
    const meBody = await meResponse.json();
    const adminPid = String(meBody?.data?.user?.pid ?? '');
    expect(adminPid, 'admin pid from /api/auth/me').toBeTruthy();
    const sales = await provisionUser(page, stamp, 'sales', ['crm_sales']);
    const manager = await provisionUser(page, stamp, 'manager', ['crm_sales_manager']);

    const pool = await command(
      page,
      'crm:create_customer_pool',
      {
        crm_cp_name: `Cordys W1 Pool ${stamp}`,
        crm_cp_member_user_ids: JSON.stringify([sales.pid]),
        crm_cp_admin_user_ids: JSON.stringify([adminPid, manager.pid]),
        crm_cp_daily_pick_limit: 1,
        crm_cp_new_cooldown_days: 0,
        crm_cp_previous_owner_cooldown_days: 2,
        crm_cp_auto_recycle: true,
        crm_cp_recycle_after_days: 30,
        crm_cp_recycle_basis: 'last_activity',
        crm_cp_recycle_match_mode: 'all',
        crm_cp_description: 'CordysCRM parity automated acceptance pool',
      },
      undefined,
      'create',
    );
    const poolPid = recordPid(pool);
    expect(poolPid, 'created customer pool pid').toBeTruthy();

    const capacity = await command(
      page,
      'crm:create_customer_capacity',
      {
        crm_ccap_user_id: sales.pid,
        crm_ccap_capacity: 1,
        crm_ccap_status: 'active',
        crm_ccap_remark: 'W1 capacity boundary',
      },
      undefined,
      'create',
    );
    const capacityPid = recordPid(capacity);
    expect(capacityPid, 'created capacity pid').toBeTruthy();

    const singleA = `W1-Single-A-${stamp}`;
    const singleB = `W1-Single-B-${stamp}`;
    const assignA = `W1-Assign-A-${stamp}`;
    const batchA = `W1-Batch-A-${stamp}`;
    const batchB = `W1-Batch-B-${stamp}`;
    const revokedA = `W1-Revoked-A-${stamp}`;
    const raceA = `W1-Race-A-${stamp}`;
    const quickEditA = `W1-Quick-Edit-${stamp}`;
    const quickEditedA = `${quickEditA}-Updated`;
    const deleteA = `W1-Delete-A-${stamp}`;
    const deleteB = `W1-Delete-B-${stamp}`;
    const deleteBatchName = `W1-Delete-Renamed-${stamp}`;
    const guardedDeleteA = `W1-Guarded-Delete-${stamp}`;
    const a = await createCustomerAndMove(page, poolPid, singleA, 1);
    const b = await createCustomerAndMove(page, poolPid, singleB, 2);
    await createCustomerAndMove(page, poolPid, assignA, 3);
    const batchCustomerA = await createCustomerAndMove(page, poolPid, batchA, 4);
    const batchCustomerB = await createCustomerAndMove(page, poolPid, batchB, 5);
    const revoked = await createCustomerAndMove(page, poolPid, revokedA, 6);
    const race = await createCustomerAndMove(page, poolPid, raceA, 7);
    const quickEdit = await createCustomerAndMove(page, poolPid, quickEditA, 8);
    const deletableA = await createCustomerAndMove(page, poolPid, deleteA, 9);
    const deletableB = await createCustomerAndMove(page, poolPid, deleteB, 10);
    const guardedDelete = await createCustomerAndMove(page, poolPid, guardedDeleteA, 11);
    await command(
      page,
      'crm:create_contact',
      {
        crm_ct_account_id: guardedDelete.customerPid,
        crm_ct_name: `Guarded Contact ${stamp}`,
        crm_ct_title: 'Procurement Director',
        crm_ct_email: `guarded-contact-${stamp}@e2e.local`,
        crm_ct_is_primary: true,
        crm_ct_remark: 'Reference that must block permanent customer deletion',
      },
      undefined,
      'create',
    );

    const salesSession = await openAs(browser, baseURL ?? 'http://localhost:5251', sales.email);
    try {
      const salesPage = salesSession.page;
      const salesMeResponse = await salesPage.request.get('/api/auth/me');
      const salesMeBody = await salesMeResponse.json();
      expect(JSON.stringify(salesMeBody?.data?.permissions)).toContain('crm.customer_pool.read');
      expect(JSON.stringify(salesMeBody?.data?.permissions)).toContain('crm.customer_pool.pick');
      const queryEvidence = await captureNamedQueryResponses(
        salesPage,
        ['crm_customer_pool_ops_stats', 'crm_customer_pool_ops_queue'],
        () =>
          navigateToCrmMenu(
            salesPage,
            /业务档案|Business Records/i,
            '/p/c/crm_customer_pool_item_list',
          ),
      );
      const statsRows = queryRows(queryEvidence.crm_customer_pool_ops_stats);
      const queueRows = queryRows(queryEvidence.crm_customer_pool_ops_queue);
      expect(statsRows, 'customer-pool metrics query must return one aggregate row').toHaveLength(
        1,
      );
      expect(
        Number(statsRows[0]?.ready_count),
        'ready metric must include the seven seeded customers',
      ).toBeGreaterThanOrEqual(7);
      expect(
        queueRows.length,
        'customer-pool queue query must return seeded records',
      ).toBeGreaterThanOrEqual(7);
      await expect(salesPage).toHaveURL(/crm_customer_pool_item/, { timeout: 15_000 });
      await expect(salesPage.getByTestId('metric-strip-item-ready')).toBeVisible({
        timeout: 15_000,
      });
      await expect(
        salesPage.getByRole('heading', {
          name: /客户公海运营台|Customer Pool Operations/,
          exact: true,
        }),
      ).toBeVisible();
      await expect(companyCell(salesPage, singleA)).toBeVisible({ timeout: 15_000 });
      await expect(companyCell(salesPage, singleB)).toBeVisible({ timeout: 15_000 });
      await expect(
        salesPage.getByText(/领取与责任证据|Claim and Ownership Evidence/),
      ).toBeVisible();
      await expect(
        salesPage.getByRole('button', { name: /分配给成员|Assign to Member/, exact: true }),
      ).toHaveCount(0);
      await expect(
        salesPage.getByRole('button', { name: /客户公海策略|Pool Policies/ }),
      ).toHaveCount(0);
      await expect(salesPage.getByRole('button', { name: /人员库容|User Capacity/ })).toHaveCount(
        0,
      );
      await expect(salesPage.getByText(/加载中|Loading/)).toHaveCount(0);
      const searchBox = salesPage.getByRole('textbox', {
        name: /搜索池内客户|Search pooled customers/,
      });
      await expect(searchBox).toBeVisible();
      const compactMetricCards = salesPage.locator('[data-testid^="metric-strip-item-"].h-20');
      await expect(compactMetricCards).toHaveCount(5);
      await expect(
        compactMetricCards.locator('[data-testid^="metric-strip-subtext-"]'),
        'compact metric cards must not render clipped auxiliary copy',
      ).toHaveCount(0);
      const compactMetricHeights = await compactMetricCards.evaluateAll((cards) =>
        cards.map((card) => card.getBoundingClientRect().height),
      );
      expect(
        compactMetricHeights.every((height) => height <= 82),
        `compact metric cards must stay within the 80px visual contract: ${compactMetricHeights.join(',')}`,
      ).toBe(true);
      const compactFilter = salesPage.locator('.filters-block[data-density="compact"]');
      await expect(compactFilter).toBeVisible();
      expect(
        await compactFilter.evaluate((element) => element.getBoundingClientRect().height),
        'compact search and actions should not consume a second toolbar row',
      ).toBeLessThanOrEqual(90);
      await expect(salesPage.locator('.table-block').first()).toHaveCSS('max-height', '360px');
      await attachScreenshot(salesPage, testInfo, 'sales-ready-operations-workbench');

      await searchBox.fill(singleB);
      await salesPage.getByTestId('filter-btn-search').click();
      await expect(companyCell(salesPage, singleB)).toBeVisible();
      await expect(companyCell(salesPage, singleA)).toHaveCount(0);
      await salesPage.getByTestId('filter-btn-reset').click();
      await expect(companyCell(salesPage, singleA)).toBeVisible();

      const detailRow = salesPage.getByRole('row', { name: new RegExp(singleB) });
      await clickRowAction(salesPage, detailRow, 'view_pool_evidence', /池内详情|Pool Detail/);
      await expect(salesPage).toHaveURL(/crm_customer_pool_item.*view/);
      await expect(salesPage.getByText(/客户快照|Customer Snapshot/)).toBeVisible();
      await expect(salesPage.getByText(singleB, { exact: true })).toBeVisible();
      await expect(salesPage.getByText(/入池与领取证据|Pool and Claim Evidence/)).toBeVisible();
      await attachScreenshot(salesPage, testInfo, 'customer-pool-item-detail');
      await salesPage.goBack({ waitUntil: 'domcontentloaded' });
      await expect(companyCell(salesPage, singleA)).toBeVisible({ timeout: 15_000 });

      const rowA = salesPage.getByRole('row', { name: new RegExp(singleA) });
      await rowA.click();
      await expect(
        salesPage.getByText(/该客户现在可以领取或分配|ready to claim or assign/),
      ).toBeVisible();
      sqlEvidence.singleClaim = await captureUiCommandSqlCount(
        salesPage,
        'crm:claim_pool_customer',
        'first successful member claim',
        COLD_CLAIM_SQL_BUDGET,
        async () => {
          await salesPage
            .getByRole('button', { name: /领取此客户|Claim Customer/, exact: true })
            .click();
          await salesPage
            .getByRole('dialog')
            .getByRole('button', { name: /确认|Confirm|继续|Continue/i })
            .click();
        },
      );
      await expect(salesPage.getByText(/领取成功|Customer claimed/)).toBeVisible({
        timeout: 15_000,
      });
      await expect(companyCell(salesPage, singleA)).toBeVisible();
      await expect(salesPage.getByText(/客户已由成员领取|claimed by a member/)).toBeVisible();
      await expect(salesPage.getByText(/加载中|Loading/)).toHaveCount(0);
      await attachScreenshot(salesPage, testInfo, 'sales-claim-operations-workbench');

      const capacityResponse = await salesPage.request.post(
        '/api/meta/commands/execute/crm:claim_pool_customer',
        { data: { targetRecordPid: b.itemPid, operationType: 'update', payload: {} } },
      );
      const capacityBody = await capacityResponse.json().catch(() => ({}));
      expect(JSON.stringify(capacityBody)).toMatch(
        /Customer capacity reached: current \d+ \/ limit 1 for user/,
      );
      sqlEvidence.capacityReject = assertApiSqlCount(
        capacityResponse,
        'capacity rejection',
        REJECT_SQL_BUDGET,
      );

      await salesPage.reload({ waitUntil: 'domcontentloaded' });
      await expect(salesPage.getByTestId('metric-strip-item-blocked')).toContainText(/[1-9]/, {
        timeout: 15_000,
      });
      const capacitySearch = salesPage.getByRole('textbox', {
        name: /搜索池内客户|Search pooled customers/,
      });
      await capacitySearch.fill(singleB);
      await salesPage.getByTestId('filter-btn-search').click();
      const capacityBlockedRow = salesPage.getByRole('row', { name: new RegExp(singleB) });
      await expect(capacityBlockedRow).toBeVisible();
      await expect(capacityBlockedRow).toContainText(/人员库容已满|Customer Capacity Reached/);
      expect(await capacityBlockedRow.innerText()).not.toContain('capacity_blocked');
      await capacityBlockedRow.click();
      const capacityBanner = salesPage.getByTestId('status-banner-crm_customer_pool_status');
      await expect(capacityBanner).toContainText(/当前人员库容已满|customer capacity is full/i);
      await expect(
        capacityBanner
          .locator('dt', { hasText: /名下活跃客户|Active Customers Owned/ })
          .locator('..'),
      ).toContainText('1');
      await expect(
        capacityBanner.locator('dt', { hasText: /人员库容上限|Customer Capacity/ }).locator('..'),
      ).toContainText('1');
      await expect(
        salesPage.getByRole('button', { name: /领取此客户|Claim Customer/, exact: true }),
      ).toHaveCount(0);
      await attachScreenshot(salesPage, testInfo, 'sales-capacity-blocked-eligibility');

      await command(
        page,
        'crm:update_customer_capacity',
        {
          crm_ccap_capacity: 3,
          crm_ccap_status: 'active',
          crm_ccap_remark: 'W1 daily quota boundary',
        },
        capacityPid,
        'update',
      );
      const quotaResponse = await salesPage.request.post(
        '/api/meta/commands/execute/crm:claim_pool_customer',
        { data: { targetRecordPid: b.itemPid, operationType: 'update', payload: {} } },
      );
      const quotaBody = await quotaResponse.json().catch(() => ({}));
      expect(JSON.stringify(quotaBody)).toMatch(
        /Daily customer-pool claim limit reached: current 1 \/ limit 1; next eligible at/,
      );
      sqlEvidence.quotaReject = assertApiSqlCount(
        quotaResponse,
        'daily quota rejection',
        REJECT_SQL_BUDGET,
      );

      await salesPage.reload({ waitUntil: 'domcontentloaded' });
      await expect(salesPage.getByTestId('metric-strip-item-blocked')).toContainText(/[1-9]/, {
        timeout: 15_000,
      });
      const quotaSearch = salesPage.getByRole('textbox', {
        name: /搜索池内客户|Search pooled customers/,
      });
      await quotaSearch.fill(singleB);
      await salesPage.getByTestId('filter-btn-search').click();
      const quotaBlockedRow = salesPage.getByRole('row', { name: new RegExp(singleB) });
      await expect(quotaBlockedRow).toBeVisible();
      await expect(quotaBlockedRow).toContainText(/今日额度已满|Daily Quota Reached/);
      expect(await quotaBlockedRow.innerText()).not.toContain('quota_blocked');
      await quotaBlockedRow.click();
      const quotaBanner = salesPage.getByTestId('status-banner-crm_customer_pool_status');
      await expect(quotaBanner).toContainText(/今日领取额度已用完|claim quota is exhausted/i);
      await expect(
        quotaBanner.locator('dt', { hasText: /今日已领取|Claimed Today/ }).locator('..'),
      ).toContainText('1');
      await expect(
        quotaBanner.locator('dt', { hasText: /每日上限|Daily Limit/ }).locator('..'),
      ).toContainText('1');
      await expect(
        quotaBanner
          .locator('dt', { hasText: /下次可领取|Next Eligible At/ })
          .locator('..')
          .locator('dd'),
      ).not.toHaveText('');
      await expect(
        salesPage.getByRole('button', { name: /领取此客户|Claim Customer/, exact: true }),
      ).toHaveCount(0);
      await attachScreenshot(salesPage, testInfo, 'sales-quota-blocked-eligibility');

      await command(
        page,
        'crm:update_customer_pool',
        {
          crm_cp_name: `Cordys W1 Pool ${stamp}`,
          crm_cp_member_user_ids: JSON.stringify([]),
          crm_cp_admin_user_ids: JSON.stringify([adminPid, manager.pid]),
          crm_cp_daily_pick_limit: 1,
          crm_cp_new_cooldown_days: 0,
          crm_cp_previous_owner_cooldown_days: 2,
          crm_cp_auto_recycle: true,
          crm_cp_recycle_after_days: 30,
          crm_cp_recycle_basis: 'last_activity',
          crm_cp_recycle_match_mode: 'all',
          crm_cp_description: 'CordysCRM parity automated acceptance pool',
        },
        poolPid,
        'update',
      );
      const revokedResponse = await salesPage.request.post(
        '/api/meta/commands/execute/crm:claim_pool_customer',
        { data: { targetRecordPid: revoked.itemPid, operationType: 'update', payload: {} } },
      );
      const revokedBody = await revokedResponse.json().catch(() => ({}));
      expect(JSON.stringify(revokedBody)).toMatch(
        /not a member|command permit scope does not include this record/,
      );
      sqlEvidence.revokedMembershipReject = assertApiSqlCount(
        revokedResponse,
        'revoked membership rejection with an existing session',
        REJECT_SQL_BUDGET,
      );
      const unauthorizedUpdateResponse = await salesPage.request.post(
        '/api/meta/commands/execute/crm:update_pool_customer',
        {
          data: {
            targetRecordPid: quickEdit.itemPid,
            operationType: 'update',
            payload: { crm_acc_rating: 'D' },
          },
        },
      );
      const unauthorizedUpdateBody = await unauthorizedUpdateResponse.json().catch(() => ({}));
      expect(String(unauthorizedUpdateBody?.code)).not.toBe('0');
      expect(JSON.stringify(unauthorizedUpdateBody)).toMatch(
        /permission|forbidden|not permitted|command permit scope|无权|权限/i,
      );
      sqlEvidence.unauthorizedUpdateReject = assertApiSqlCount(
        unauthorizedUpdateResponse,
        'sales role customer update rejection',
        REJECT_SQL_BUDGET,
      );
      await salesPage.reload({ waitUntil: 'domcontentloaded' });
      await expect(companyCell(salesPage, revokedA)).toHaveCount(0);
      await expect(companyCell(salesPage, singleB)).toHaveCount(0);
    } finally {
      await salesSession.context.close();
    }

    await command(
      page,
      'crm:update_customer_pool',
      {
        crm_cp_name: `Cordys W1 Pool ${stamp}`,
        crm_cp_member_user_ids: JSON.stringify([sales.pid]),
        crm_cp_admin_user_ids: JSON.stringify([adminPid, manager.pid]),
        crm_cp_daily_pick_limit: 10,
        crm_cp_new_cooldown_days: 0,
        crm_cp_previous_owner_cooldown_days: 2,
        crm_cp_auto_recycle: true,
        crm_cp_recycle_after_days: 30,
        crm_cp_recycle_basis: 'last_activity',
        crm_cp_recycle_match_mode: 'all',
        crm_cp_description: 'CordysCRM parity automated acceptance pool',
      },
      poolPid,
      'update',
    );

    await navigateToCrmMenu(page, /业务档案|Business Records/i, '/p/c/crm_customer_pool_item_list');
    await expect(
      page.getByRole('button', { name: /分配给成员|Assign to Member/, exact: true }),
    ).toBeVisible();
    await expect(page.getByRole('button', { name: /客户公海策略|Pool Policies/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /人员库容|User Capacity/ })).toHaveCount(0);
    const assignRow = page.getByRole('row', { name: new RegExp(assignA) });
    await assignRow.click();
    await page.getByRole('button', { name: /分配给成员|Assign to Member/, exact: true }).click();
    const assignDialog = page.getByRole('dialog');
    await expect(assignDialog.getByText(/分配池内客户|Assign Pooled Customer/)).toBeVisible();
    await assignDialog.getByTestId('member-picker-add').click();
    await assignDialog.getByTestId('member-picker-search-input').fill(sales.email);
    await assignDialog.getByTestId(`member-picker-option-${sales.pid}`).click();
    sqlEvidence.singleAssign = await captureUiCommandSqlCount(
      page,
      'crm:assign_pool_customer',
      'successful administrator assignment',
      STEADY_COMMAND_SQL_BUDGET,
      () => assignDialog.getByRole('button', { name: /确认|确定|提交|Submit|Confirm/i }).click(),
    );
    await expect(page.getByText(/分配成功|Customer assigned/)).toBeVisible({ timeout: 15_000 });
    const ownedMetric = page.getByTestId('metric-strip-item-owned');
    await expect(ownedMetric).toContainText(/[1-9]/);
    await ownedMetric.click();
    const assignedSearch = page.getByRole('textbox', {
      name: /搜索池内客户|Search pooled customers/,
    });
    await assignedSearch.fill(assignA);
    await page.getByTestId('filter-btn-search').click();
    const assignedRow = page.getByRole('row', { name: new RegExp(assignA) });
    await expect(assignedRow).toBeVisible({ timeout: 15_000 });
    await assignedRow.click();
    await expect(page.getByText(/客户已由管理员分配|assigned by an administrator/)).toBeVisible();
    await expect(page.getByText(/加载中|Loading/)).toHaveCount(0);
    await attachScreenshot(page, testInfo, 'admin-assign-operations-workbench');
    await command(
      page,
      'crm:update_customer_capacity',
      {
        crm_ccap_capacity: 10,
        crm_ccap_status: 'active',
        crm_ccap_remark: 'W1 batch assignment capacity',
      },
      capacityPid,
      'update',
    );

    const managerSession = await openAs(browser, baseURL ?? 'http://localhost:5251', manager.email);
    try {
      const managerPage = managerSession.page;
      await navigateToCrmMenu(
        managerPage,
        /业务档案|Business Records/i,
        '/p/c/crm_customer_pool_item_list',
      );
      await expect(managerPage.getByTestId('metric-strip-item-ready')).toBeVisible();
      await managerPage
        .getByRole('button', { name: /批量处理|Batch Operations/, exact: true })
        .click();
      await expect(
        managerPage.getByText(/批量治理公海客户|Govern pooled customers in bulk/),
      ).toBeVisible({ timeout: 15_000 });
      const batchTabs = managerPage.getByRole('navigation', { name: /Tabs/ });
      await expect(batchTabs.getByRole('button', { name: /待领取|Available/ })).toBeVisible();
      await expect(batchTabs.getByRole('button', { name: /已领取|Claimed/ })).toBeVisible();
      await expect(batchTabs.getByRole('button', { name: /已分配|Assigned/ })).toBeVisible();

      // Cordys chart(): analyze the current filtered pool list and prove the aggregate backend,
      // dictionary labels, chart, and breakdown table all render without an error state.
      await managerPage.getByTestId('view-analysis-open').click();
      const analysisDrawer = managerPage.getByTestId('view-analysis-drawer');
      await expect(analysisDrawer).toBeVisible();
      await expect(analysisDrawer.getByTestId('view-analysis-error')).toHaveCount(0);
      await expect(
        analysisDrawer.locator('[data-testid^="view-analysis-breakdown-"]').first(),
      ).toBeVisible({ timeout: 20_000 });
      await analysisDrawer.getByTestId('view-analysis-chart-donut').click();
      await expect(analysisDrawer.getByTestId('view-analysis-chart-donut')).toHaveAttribute(
        'aria-pressed',
        'true',
      );
      await attachScreenshot(managerPage, testInfo, 'manager-customer-pool-analysis');
      await analysisDrawer.getByTestId('view-analysis-close').click();

      // Cordys quickUpdate: a generated row form must restore current values, render dictionary
      // choices, update the account master, and keep the pool projection synchronized.
      const quickEditRow = managerPage.getByRole('row', { name: new RegExp(quickEditA) });
      await clickRowAction(managerPage, quickEditRow, 'quick_update', /快速编辑|Quick Edit/);
      const quickEditDialog = managerPage.getByTestId('form-dialog');
      await expect(quickEditDialog).toBeVisible();
      await expect(
        quickEditDialog.getByTestId('form-dialog-field-crm_cpi_account_name'),
      ).toHaveValue(quickEditA);
      await expect(quickEditDialog.getByTestId('form-dialog-field-crm_cpi_rating')).toHaveValue(
        'A',
      );
      await quickEditDialog
        .getByTestId('form-dialog-field-crm_cpi_account_name')
        .fill(quickEditedA);
      await quickEditDialog.getByTestId('form-dialog-field-crm_cpi_rating').selectOption('D');
      sqlEvidence.quickCustomerUpdate = await captureUiCommandSqlCount(
        managerPage,
        'crm:update_pool_customer',
        'manager quick customer update',
        STEADY_COMMAND_SQL_BUDGET,
        () => quickEditDialog.getByTestId('form-dialog-submit').click(),
      );
      await expect(companyCell(managerPage, quickEditedA)).toBeVisible({ timeout: 15_000 });
      await expect(companyCell(managerPage, quickEditA)).toHaveCount(0);
      const quickAccountResponse = await managerPage.request.get(
        `/api/dynamic/crm_account_common/${quickEdit.customerPid}`,
      );
      const quickItemResponse = await managerPage.request.get(
        `/api/dynamic/crm_customer_pool_item_common/${quickEdit.itemPid}`,
      );
      const quickAccount = (await quickAccountResponse.json())?.data;
      const quickItem = (await quickItemResponse.json())?.data;
      expect(quickAccount?.crm_acc_name).toBe(quickEditedA);
      expect(quickAccount?.crm_acc_rating).toBe('D');
      expect(quickItem?.crm_cpi_account_name).toBe(quickEditedA);
      expect(quickItem?.crm_cpi_rating).toBe('D');

      // Cordys exportAll: the current Available tab is exported as a real, parseable workbook.
      const exportAllRequest = await captureWorkbookExport(
        managerPage,
        testInfo,
        'customer-pool-export-all-available',
        [quickEditedA, batchA, batchB, guardedDeleteA],
        [assignA],
        async () => {
          await managerPage.getByTestId('toolbar-more-menu').click();
          await managerPage.getByTestId('more-menu-export-excel').click();
        },
      );
      expect(JSON.stringify(exportAllRequest)).toContain('available');

      // Cordys exportSelect: the selected workbook must contain exactly the chosen business rows.
      for (const company of [batchA, batchB]) {
        const row = managerPage.getByRole('row', { name: new RegExp(company) });
        await row.getByRole('checkbox').click();
      }
      const exportSelectedRequest = await captureWorkbookExport(
        managerPage,
        testInfo,
        'customer-pool-export-selected',
        [batchA, batchB],
        [raceA, quickEditedA],
        async () => {
          await managerPage.getByTestId('bulk-more-actions-btn').click();
          await managerPage.getByTestId('bulk-export-selected-btn').click();
        },
      );
      expect(JSON.stringify(exportSelectedRequest)).toContain(batchCustomerA.itemPid);
      expect(JSON.stringify(exportSelectedRequest)).toContain(batchCustomerB.itemPid);

      // Cordys batchUpdate: update two customer masters and their pool snapshots in one governed
      // interaction. The shared bulk result still executes and budgets each command independently.
      await managerPage.getByTestId('bulk-more-actions-btn').click();
      await managerPage.getByTestId('bulk-action-batch_update_rating').click();
      const batchUpdateDialog = managerPage.getByTestId('bulk-field-command-dialog');
      await expect(batchUpdateDialog).toContainText(/2 条记录|2 records/);
      await batchUpdateDialog.getByTestId('select-trigger-crm_acc_rating').click();
      await managerPage.getByRole('option', { name: /C - 一般客户|C - Normal/ }).click();
      const batchUpdateCounts = await captureUiCommandSqlCounts(
        managerPage,
        'crm:update_pool_customer',
        2,
        'successful manager batch customer update',
        STEADY_COMMAND_SQL_BUDGET,
        () => batchUpdateDialog.getByTestId('bulk-field-command-submit').click(),
      );
      sqlEvidence.managerBatchUpdate1 = batchUpdateCounts[0];
      sqlEvidence.managerBatchUpdate2 = batchUpdateCounts[1];
      await expect(
        managerPage.getByText(/批量修改评级已完成.*成功 2 条|Update Ratings.*2/i),
      ).toBeVisible({
        timeout: 20_000,
      });
      for (const record of [batchCustomerA, batchCustomerB]) {
        const accountResponse = await managerPage.request.get(
          `/api/dynamic/crm_account_common/${record.customerPid}`,
        );
        const itemResponse = await managerPage.request.get(
          `/api/dynamic/crm_customer_pool_item_common/${record.itemPid}`,
        );
        expect((await accountResponse.json())?.data?.crm_acc_rating).toBe('C');
        expect((await itemResponse.json())?.data?.crm_cpi_rating).toBe('C');
      }

      for (const company of [batchA, batchB]) {
        await managerPage
          .getByRole('row', { name: new RegExp(company) })
          .getByRole('checkbox')
          .click();
      }
      await managerPage.getByTestId('bulk-more-actions-btn').click();
      await managerPage.getByTestId('bulk-action-batch_update_industry').click();
      const batchIndustryDialog = managerPage.getByTestId('bulk-field-command-dialog');
      await batchIndustryDialog.getByTestId('select-trigger-crm_acc_industry').click();
      await managerPage.getByRole('option', { name: /制造业|Manufacturing/ }).click();
      const batchIndustryCounts = await captureUiCommandSqlCounts(
        managerPage,
        'crm:update_pool_customer',
        2,
        'successful manager batch industry update',
        STEADY_COMMAND_SQL_BUDGET,
        () => batchIndustryDialog.getByTestId('bulk-field-command-submit').click(),
      );
      sqlEvidence.managerBatchIndustry1 = batchIndustryCounts[0];
      sqlEvidence.managerBatchIndustry2 = batchIndustryCounts[1];
      await expect(
        managerPage.getByText(/批量修改行业已完成.*成功 2 条|Update Industries.*2/i),
      ).toBeVisible({ timeout: 20_000 });
      for (const record of [batchCustomerA, batchCustomerB]) {
        const accountResponse = await managerPage.request.get(
          `/api/dynamic/crm_account_common/${record.customerPid}`,
        );
        const itemResponse = await managerPage.request.get(
          `/api/dynamic/crm_customer_pool_item_common/${record.itemPid}`,
        );
        expect((await accountResponse.json())?.data?.crm_acc_industry).toBe('manufacturing');
        expect((await itemResponse.json())?.data?.crm_cpi_industry).toBe('manufacturing');
      }

      for (const company of [deleteA, deleteB]) {
        await managerPage
          .getByRole('row', { name: new RegExp(company) })
          .getByRole('checkbox')
          .click();
      }
      await managerPage.getByTestId('bulk-action-batch_update_name').click();
      const batchNameDialog = managerPage.getByTestId('bulk-field-command-dialog');
      await batchNameDialog.locator('input[name="crm_acc_name"]').fill(deleteBatchName);
      const batchNameCounts = await captureUiCommandSqlCounts(
        managerPage,
        'crm:update_pool_customer',
        2,
        'successful manager batch name update',
        STEADY_COMMAND_SQL_BUDGET,
        () => batchNameDialog.getByTestId('bulk-field-command-submit').click(),
      );
      sqlEvidence.managerBatchName1 = batchNameCounts[0];
      sqlEvidence.managerBatchName2 = batchNameCounts[1];
      await expect(
        managerPage.getByText(/批量修改客户名称已完成.*成功 2 条|Update Customer Names.*2/i),
      ).toBeVisible({ timeout: 20_000 });
      await expect(companyCell(managerPage, deleteBatchName)).toHaveCount(2);
      await attachScreenshot(managerPage, testInfo, 'manager-customer-pool-update-result');

      // Cordys delete(): a referenced customer must be rejected and remain visible.
      const guardedDeleteRow = managerPage.getByRole('row', {
        name: new RegExp(guardedDeleteA),
      });
      const guardedDeleteResponsePromise = managerPage.waitForResponse(
        (response) => response.url().includes('/execute/crm:delete_pool_customer'),
        { timeout: 20_000 },
      );
      await clickRowAction(managerPage, guardedDeleteRow, 'delete', /永久删除|Permanently Delete/);
      await confirmDialog(managerPage);
      const guardedDeleteResponse = await guardedDeleteResponsePromise;
      const guardedDeleteBody = await guardedDeleteResponse.json().catch(() => ({}));
      expect(String(guardedDeleteBody?.code)).not.toBe('0');
      expect(JSON.stringify(guardedDeleteBody)).toMatch(
        /related contacts or opportunities|联系人|商机/i,
      );
      sqlEvidence.guardedCustomerDelete = assertSqlCount(
        await guardedDeleteResponse.headerValue('x-sql-count'),
        'referenced customer delete rejection',
        REJECT_SQL_BUDGET,
      );
      await expect(companyCell(managerPage, guardedDeleteA)).toBeVisible();

      // Cordys batchDelete: unreferenced customers are permanently removed from both the account
      // master and pool projection; the L4 confirmation is exercised through the rendered UI.
      const renamedDeleteRows = await managerPage
        .getByRole('row', { name: new RegExp(deleteBatchName) })
        .all();
      expect(renamedDeleteRows, 'batch name update must leave two deletable rows').toHaveLength(2);
      for (const row of renamedDeleteRows) {
        await row.getByRole('checkbox').click();
      }
      const batchDeleteCounts = await captureUiCommandSqlCounts(
        managerPage,
        'crm:delete_pool_customer',
        2,
        'successful manager batch customer delete',
        COLD_CUSTOMER_DELETE_SQL_BUDGET,
        async () => {
          await managerPage.getByTestId('bulk-more-actions-btn').click();
          await managerPage.getByTestId('bulk-action-batch_delete').click();
          await confirmDialog(managerPage);
        },
      );
      sqlEvidence.managerBatchDelete1 = batchDeleteCounts[0];
      sqlEvidence.managerBatchDelete2 = batchDeleteCounts[1];
      await expect(companyCell(managerPage, deleteBatchName)).toHaveCount(0);
      const remainingAccounts = await dynamicListRows(managerPage, 'crm_account_common');
      const remainingPoolItems = await dynamicListRows(
        managerPage,
        'crm_customer_pool_item_common',
      );
      expect(
        remainingAccounts.filter((record) =>
          [deletableA.customerPid, deletableB.customerPid].includes(String(record.pid)),
        ),
      ).toHaveLength(0);
      expect(
        remainingPoolItems.filter((record) =>
          [deletableA.itemPid, deletableB.itemPid].includes(String(record.pid)),
        ),
      ).toHaveLength(0);

      for (const company of [batchA, batchB]) {
        const row = managerPage.getByRole('row', { name: new RegExp(company) });
        await row.getByRole('checkbox').click();
      }
      await managerPage
        .getByRole('button', { name: /批量领取|Claim Selected/, exact: true })
        .click();
      const batchClaimCounts = await captureUiCommandSqlCounts(
        managerPage,
        'crm:claim_pool_customer',
        2,
        'successful manager batch claim',
        STEADY_COMMAND_SQL_BUDGET,
        () =>
          managerPage
            .getByRole('dialog')
            .getByRole('button', { name: /确认|Confirm/, exact: true })
            .click(),
      );
      sqlEvidence.managerBatchClaim1 = batchClaimCounts[0];
      sqlEvidence.managerBatchClaim2 = batchClaimCounts[1];
      await expect(managerPage.getByText(/批量领取已完成.*成功 2 条|Claim.*2/i)).toBeVisible({
        timeout: 20_000,
      });
      await expect(companyCell(managerPage, batchA)).toHaveCount(0);
      await expect(companyCell(managerPage, batchB)).toHaveCount(0);
      await batchTabs.getByRole('button', { name: /已领取|Claimed/ }).click();
      await expect(companyCell(managerPage, batchA)).toBeVisible({ timeout: 15_000 });
      await expect(companyCell(managerPage, batchB)).toBeVisible();
      await attachScreenshot(managerPage, testInfo, 'manager-batch-claim-result');
      await batchTabs.getByRole('button', { name: /待领取|Available/ }).click();
      await expect(companyCell(managerPage, singleB)).toBeVisible({ timeout: 15_000 });
      await expect(managerPage.getByText(/加载中|Loading/)).toHaveCount(0);

      for (const company of [singleB, revokedA]) {
        const row = managerPage.getByRole('row', { name: new RegExp(company) });
        await row.getByRole('checkbox').click();
      }
      await managerPage
        .getByRole('button', { name: /批量分配|Assign Selected/, exact: true })
        .click();
      const batchAssignDialog = managerPage.getByTestId('bulk-field-command-dialog');
      await expect(batchAssignDialog).toBeVisible();
      await expect(batchAssignDialog).toContainText(/2 条记录|2 records/);
      await batchAssignDialog.getByTestId('member-picker-add').click();
      await batchAssignDialog.getByTestId('member-picker-search-input').fill(sales.email);
      await batchAssignDialog.getByTestId(`member-picker-option-${sales.pid}`).click();
      const batchAssignCounts = await captureUiCommandSqlCounts(
        managerPage,
        'crm:assign_pool_customer',
        2,
        'successful manager batch assignment',
        STEADY_COMMAND_SQL_BUDGET,
        () => batchAssignDialog.getByTestId('bulk-field-command-submit').click(),
      );
      sqlEvidence.managerBatchAssign1 = batchAssignCounts[0];
      sqlEvidence.managerBatchAssign2 = batchAssignCounts[1];
      await expect(
        managerPage.getByText(
          /^(?:批量分配已完成.*成功 2 条|Batch assignment completed.*2 (?:records )?succeeded)$/i,
        ),
      ).toBeVisible({ timeout: 20_000 });
      await expect(companyCell(managerPage, singleB)).toHaveCount(0);
      await expect(companyCell(managerPage, revokedA)).toHaveCount(0);
      await batchTabs.getByRole('button', { name: /已分配|Assigned/ }).click();
      await expect(companyCell(managerPage, singleB)).toBeVisible({ timeout: 15_000 });
      await expect(companyCell(managerPage, revokedA)).toBeVisible();
      await attachScreenshot(managerPage, testInfo, 'manager-batch-assign-result');
      await batchTabs.getByRole('button', { name: /待领取|Available/ }).click();
      await expect(companyCell(managerPage, raceA)).toBeVisible({ timeout: 15_000 });

      const raceResponses = await Promise.all([
        managerPage.request.post('/api/meta/commands/execute/crm:claim_pool_customer', {
          data: { targetRecordPid: race.itemPid, operationType: 'update', payload: {} },
        }),
        managerPage.request.post('/api/meta/commands/execute/crm:claim_pool_customer', {
          data: { targetRecordPid: race.itemPid, operationType: 'update', payload: {} },
        }),
      ]);
      const raceBodies = await Promise.all(
        raceResponses.map((response) => response.json().catch(() => ({}))),
      );
      const raceWinners = raceBodies.filter((body) => String(body?.code) === '0');
      const raceLosers = raceBodies.filter((body) => String(body?.code) !== '0');
      expect(raceWinners, 'exactly one concurrent claimant must win').toHaveLength(1);
      expect(raceLosers, 'the second concurrent claimant must receive a conflict').toHaveLength(1);
      expect(JSON.stringify(raceLosers[0])).toContain('already claimed or assigned');
      sqlEvidence.concurrentClaim1 = assertApiSqlCount(
        raceResponses[0],
        'concurrent claim attempt 1',
        STEADY_COMMAND_SQL_BUDGET,
      );
      sqlEvidence.concurrentClaim2 = assertApiSqlCount(
        raceResponses[1],
        'concurrent claim attempt 2',
        STEADY_COMMAND_SQL_BUDGET,
      );
    } finally {
      await managerSession.context.close();
    }

    await navigateToCustomerPoolStrategy(page);
    await page.getByTestId('workbench-action-open_ownership_history').click();
    await expect(page).toHaveURL(/\/p\/crm_customer_owner_history/);
    await expect(page.getByRole('columnheader', { name: /发生时间|Occurred At/ })).toBeVisible({
      timeout: 15_000,
    });
    await expect(page.getByRole('columnheader', { name: /CRM_COH_/i })).toHaveCount(0);
    await expect(page.getByText(/领取|Claimed/).first()).toBeVisible();
    await expect(page.getByText('CordysCRM W1 automated parity journey').first()).toBeVisible();
    await attachScreenshot(page, testInfo, 'customer-owner-history');
    const historyRow = page
      .getByRole('row', { name: /CordysCRM W1 automated parity journey/ })
      .first();
    await clickRowAction(page, historyRow, 'view', /查看|View/);
    await expect(page.getByText(/不可变归属证据|Immutable Ownership Evidence/)).toBeVisible({
      timeout: 15_000,
    });
    await expect(page.getByText('CordysCRM W1 automated parity journey')).toBeVisible();
    await expect(page.getByRole('link', { name: /编辑|Edit/ })).toHaveCount(0);
    await expect(page.getByRole('button', { name: /分享|Share/ })).toHaveCount(0);
    await attachScreenshot(page, testInfo, 'customer-owner-history-detail');

    const persistedCustomer = await page.request.get(
      `/api/dynamic/crm_account_common/${a.customerPid}`,
    );
    const persistedBody = await persistedCustomer.json();
    expect(String(persistedBody?.data?.crm_acc_owner)).toBe(sales.pid);
    expect(String(persistedBody?.data?.crm_acc_pool_state)).toBe('owned');
    await attachJson(testInfo, 'customer-pool-sql-budget.json', sqlEvidence);
  });

  test(GOVERNANCE_RECYCLE_SCENARIO, async ({ page }, testInfo) => {
    await uiLogin(page, ADMIN_EMAIL);
    const stamp = `${Date.now()}`;
    const meResponse = await page.request.get('/api/auth/me');
    const meBody = await meResponse.json();
    const adminPid = String(meBody?.data?.user?.pid ?? '');
    expect(adminPid, 'admin pid from /api/auth/me').toBeTruthy();
    const sales = await provisionUser(page, stamp, 'sales', ['crm_sales']);
    const poolName = `Cordys Governance Pool ${stamp}`;
    const editedPoolName = `${poolName} Edited`;

    // D1/D4/D5/D14: reach customer-pool settings from the real sidebar and create a full policy
    // through the generated form, including both MemberPicker fields and conditional recycle inputs.
    const strategyQueryEvidence = await captureNamedQueryResponses(
      page,
      ['crm_customer_pool_policy_stats', 'crm_customer_pool_policy_queue'],
      () => navigateToCustomerPoolStrategy(page),
    );
    expect(
      queryRows(strategyQueryEvidence.crm_customer_pool_policy_stats),
      'strategy metrics query must return one aggregate row',
    ).toHaveLength(1);
    await expect(
      page
        .locator('main')
        .getByRole('heading', { name: /客户公海策略|Customer Pool Strategy/, exact: true }),
    ).toBeVisible();
    await expect(page.getByTestId('metric-strip-item-daily_capacity')).toBeVisible();
    await expect(page.getByTestId('metric-strip-item-auto_recycle')).toBeVisible();
    await expect(page.getByTestId('metric-strip-item-active_rules')).toBeVisible();
    await expect(page.getByTestId('workbench-action-open_pool_operations')).toBeVisible();
    await expect(page.getByTestId('workbench-action-open_ownership_history')).toBeVisible();
    await attachScreenshot(page, testInfo, 'customer-pool-strategy-workbench');
    await page.getByTestId('workbench-action-create_pool').click();
    await waitForFormReady(page, 15_000);
    await expect(
      page.getByRole('heading', { name: /客户公海策略|Customer Pool Policy/, exact: true }),
    ).toBeVisible();
    await fillFormField(page, 'crm_cp_name', poolName);
    await pickMemberInForm(page, 'crm_cp_member_user_ids', sales.pid, sales.email);
    await pickMemberInForm(page, 'crm_cp_admin_user_ids', adminPid, ADMIN_EMAIL);
    await fillFormField(page, 'crm_cp_daily_pick_limit', '5');
    await fillFormField(page, 'crm_cp_new_cooldown_days', '0');
    await fillFormField(page, 'crm_cp_previous_owner_cooldown_days', '2');
    await setBooleanFormField(page, 'crm_cp_auto_recycle', true);
    await pickSmartSelect(page, 'crm_cp_recycle_match_mode', /全部满足|All Conditions/);
    await fillFormField(page, 'crm_cp_recycle_after_days', '30');
    await pickSmartSelect(page, 'crm_cp_recycle_basis', /最近跟进|Last Activity/);
    await fillFormField(
      page,
      'crm_cp_description',
      'CordysCRM customer commons governance and recycle acceptance policy',
    );
    const createdPool = await submitFormCommand(page, 'crm:create_customer_pool');
    const poolPid = recordPid(createdPool);
    expect(poolPid, 'UI-created customer pool pid').toBeTruthy();
    await navigateToCustomerPoolStrategy(page);
    await expect(page.getByRole('cell', { name: poolName, exact: true })).toBeVisible({
      timeout: 15_000,
    });
    await attachScreenshot(page, testInfo, 'customer-pool-governance-list');

    // D7/D8: open the persisted detail and edit the same record from its UI action.
    let poolRow = page.getByRole('row', { name: new RegExp(poolName) });
    await clickRowAction(page, poolRow, 'view', /查看|View/);
    await expect(
      page.getByTestId('evidence-panel-section-pool').getByText(poolName, { exact: true }),
    ).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText(/策略总览|Policy Overview/)).toBeVisible();
    await page.getByTestId('toolbar-btn-edit').click();
    await waitForFormReady(page, 15_000);
    await expect(
      formField(page, 'crm_cp_member_user_ids').getByTestId(`member-picker-selected-${sales.pid}`),
      'edit form must restore pool members from the persisted JSON list',
    ).toBeVisible({ timeout: 10_000 });
    await expect(
      formField(page, 'crm_cp_admin_user_ids').getByTestId(`member-picker-selected-${adminPid}`),
      'edit form must restore pool administrators from the persisted JSON list',
    ).toBeVisible();
    await expect(formField(page, 'crm_cp_new_cooldown_days').locator('input')).toHaveValue('0');
    await expect(
      formField(page, 'crm_cp_previous_owner_cooldown_days').locator('input'),
    ).toHaveValue('2');
    await expect(formField(page, 'crm_cp_auto_recycle').locator('[role="switch"]')).toHaveAttribute(
      'aria-checked',
      'true',
    );
    await fillFormField(page, 'crm_cp_name', editedPoolName);
    await fillFormField(page, 'crm_cp_daily_pick_limit', '7');
    await pickSmartSelect(page, 'crm_cp_recycle_match_mode', /任一满足|Any Condition/);
    await submitFormCommand(page, 'crm:update_customer_pool');
    await navigateToCustomerPoolStrategy(page);
    await expect(page.getByRole('cell', { name: editedPoolName, exact: true })).toBeVisible({
      timeout: 15_000,
    });

    // Cordys quickUpdate for pool policy: restore the persisted row into a compact generated form
    // and update only the high-frequency claim-policy fields without reopening the full page.
    poolRow = page.getByRole('row', { name: new RegExp(editedPoolName) });
    await clickRowAction(page, poolRow, 'quick_update', /快速调整策略|Quick Policy Update/);
    const quickPolicyDialog = page.getByTestId('form-dialog');
    await expect(quickPolicyDialog).toBeVisible();
    await expect(quickPolicyDialog.getByTestId('form-dialog-field-crm_cp_name')).toHaveValue(
      editedPoolName,
    );
    await expect(
      quickPolicyDialog.getByTestId('form-dialog-field-crm_cp_daily_pick_limit'),
    ).toHaveValue('7');
    await expect(
      quickPolicyDialog.getByTestId('form-dialog-field-crm_cp_new_cooldown_days'),
    ).toHaveValue('0');
    await quickPolicyDialog.getByTestId('form-dialog-field-crm_cp_daily_pick_limit').fill('9');
    const quickPolicyResponsePromise = page.waitForResponse(
      (response) => response.url().includes('/execute/crm:update_customer_pool'),
      { timeout: 20_000 },
    );
    await quickPolicyDialog.getByTestId('form-dialog-submit').click();
    const quickPolicyResponse = await quickPolicyResponsePromise;
    const quickPolicyBody = await quickPolicyResponse.json().catch(() => ({}));
    expect(String(quickPolicyBody?.code)).toBe('0');
    assertSqlCount(
      await quickPolicyResponse.headerValue('x-sql-count'),
      'quick pool policy update',
      STEADY_COMMAND_SQL_BUDGET,
    );
    const quickPolicyFact = await page.request.get(
      `/api/dynamic/crm_customer_pool_common/${poolPid}`,
    );
    expect(Number((await quickPolicyFact.json())?.data?.crm_cp_daily_pick_limit)).toBe(9);
    await expect(page.getByRole('cell', { name: editedPoolName, exact: true })).toBeVisible({
      timeout: 15_000,
    });
    await attachScreenshot(page, testInfo, 'customer-pool-quick-policy-update');

    // D9: disable and re-enable the same pool from row commands; every state is persisted.
    poolRow = page.getByRole('row', { name: new RegExp(editedPoolName) });
    let toggleResponsePromise = page.waitForResponse(
      (response) => response.url().includes('/execute/crm:toggle_customer_pool'),
      { timeout: 20_000 },
    );
    await clickRowAction(page, poolRow, 'toggle', /启用\/停用|Enable\/Disable/);
    let toggleResponse = await toggleResponsePromise;
    let toggleBody = await toggleResponse.json();
    expect(String(toggleBody?.code)).toBe('0');
    await expect(poolRow).toContainText(/已停用|Disabled/, { timeout: 10_000 });
    toggleResponsePromise = page.waitForResponse(
      (response) => response.url().includes('/execute/crm:toggle_customer_pool'),
      { timeout: 20_000 },
    );
    await clickRowAction(page, poolRow, 'toggle', /启用\/停用|Enable\/Disable/);
    toggleResponse = await toggleResponsePromise;
    toggleBody = await toggleResponse.json();
    expect(String(toggleBody?.code)).toBe('0');
    await expect(poolRow).toContainText(/已启用|Enabled/, { timeout: 10_000 });

    // D4/D7/D8 for user capacity: create through UserSelect, open detail, edit, then verify API fact.
    await captureNamedQueryResponses(page, ['crm_customer_capacity_policy_queue'], () =>
      navigateToCustomerPoolStrategy(page, 'capacity'),
    );
    await page.getByTestId('workbench-action-create_capacity').click();
    await waitForFormReady(page, 15_000);
    await pickUserInForm(page, 'crm_ccap_user_id', sales.email);
    await fillFormField(page, 'crm_ccap_capacity', '5');
    await pickSmartSelect(page, 'crm_ccap_status', /生效|Active/);
    const capacityRemark = `Cordys governance UI capacity ${stamp}`;
    await fillFormField(page, 'crm_ccap_remark', capacityRemark);
    const createdCapacity = await submitFormCommand(page, 'crm:create_customer_capacity');
    const capacityPid = recordPid(createdCapacity);
    expect(capacityPid, 'UI-created customer capacity pid').toBeTruthy();
    await navigateToCustomerPoolStrategy(page, 'capacity');
    let capacityRow = page.getByRole('row', { name: new RegExp(capacityRemark) });
    await expect(capacityRow).toBeVisible({ timeout: 15_000 });
    await clickRowAction(page, capacityRow, 'view', /查看|View/);
    await expect(page.getByText(/用户库容|User Capacity/)).toBeVisible();
    await page.getByTestId('toolbar-btn-edit').click();
    await waitForFormReady(page, 15_000);
    await fillFormField(page, 'crm_ccap_capacity', '6');
    await fillFormField(page, 'crm_ccap_remark', `${capacityRemark} edited`);
    await submitFormCommand(page, 'crm:update_customer_capacity');
    await navigateToCustomerPoolStrategy(page, 'capacity');
    await expect(
      page.getByRole('row', { name: new RegExp(`${capacityRemark} edited`) }),
    ).toBeVisible({ timeout: 15_000 });
    const capacityDetail = await page.request.get(
      `/api/dynamic/crm_customer_capacity_common/${capacityPid}`,
    );
    const capacityBody = await capacityDetail.json();
    expect(Number(capacityBody?.data?.crm_ccap_capacity)).toBe(6);

    // D4/D5/D7/D8: create a relative rule used by the actual recycle, inspect it, and update it.
    await captureNamedQueryResponses(page, ['crm_customer_recycle_policy_queue'], () =>
      navigateToCustomerPoolStrategy(page, 'recycle'),
    );
    await page.getByTestId('workbench-action-create_recycle_rule').click();
    await waitForFormReady(page, 15_000);
    const relativeRuleCode = `CP-ACT-${stamp}`;
    const relativeRuleName = `最近跟进超过 1 天 ${stamp}`;
    await fillFormField(page, 'crm_cprr_code', relativeRuleCode);
    await fillFormField(page, 'crm_cprr_name', relativeRuleName);
    await pickReference(page, 'crm_cprr_pool_id', poolPid, editedPoolName);
    await pickSmartSelect(page, 'crm_cprr_status', /生效|Active/);
    await fillFormField(page, 'crm_cprr_sort_order', '10');
    await pickSmartSelect(page, 'crm_cprr_time_source', /最近跟进时间|Last Activity Time/);
    await pickSmartSelect(page, 'crm_cprr_operator', /早于相对天数|Older Than Relative Days/);
    await fillFormField(page, 'crm_cprr_days', '1');
    await fillFormField(page, 'crm_cprr_description', 'Recycle only genuinely stale customers');
    const relativeRule = await submitFormCommand(page, 'crm:create_customer_pool_recycle_rule');
    const relativeRulePid = recordPid(relativeRule);
    expect(relativeRulePid, 'UI-created relative recycle rule pid').toBeTruthy();
    await navigateToCustomerPoolStrategy(page, 'recycle');
    let relativeRuleRow = page.getByRole('row', { name: new RegExp(relativeRuleName) });
    await expect(relativeRuleRow).toBeVisible({ timeout: 15_000 });
    await clickRowAction(page, relativeRuleRow, 'view', /查看|View/);
    await expect(page.getByText(relativeRuleCode, { exact: true })).toBeVisible();
    await expect(page.getByText(/早于相对天数|Older Than Relative Days/)).toBeVisible();
    await page.getByTestId('toolbar-btn-edit').click();
    await waitForFormReady(page, 15_000);
    await expect(formField(page, 'crm_cprr_code').locator('input')).toHaveValue(relativeRuleCode);
    await expect(formField(page, 'crm_cprr_days').locator('input')).toHaveValue('1');
    await fillFormField(page, 'crm_cprr_sort_order', '11');
    await fillFormField(
      page,
      'crm_cprr_description',
      'Updated relative recycle rule verified through the generated edit form',
    );
    await submitFormCommand(page, 'crm:update_customer_pool_recycle_rule');
    await navigateToCustomerPoolStrategy(page, 'recycle');
    relativeRuleRow = page.getByRole('row', { name: new RegExp(relativeRuleName) });
    await expect(relativeRuleRow).toContainText('11', { timeout: 15_000 });

    // Fixed-window fields are a separate Cordys contract. Create an inactive interval rule so its
    // conditional inputs are exercised without altering the live relative-rule decision below.
    await navigateToCustomerPoolStrategy(page, 'recycle');
    await page.getByTestId('workbench-action-create_recycle_rule').click();
    await waitForFormReady(page, 15_000);
    const fixedRuleCode = `CP-FIX-${stamp}`;
    const fixedRuleName = `固定区间回收 ${stamp}`;
    await fillFormField(page, 'crm_cprr_code', fixedRuleCode);
    await fillFormField(page, 'crm_cprr_name', fixedRuleName);
    await pickReference(page, 'crm_cprr_pool_id', poolPid, editedPoolName);
    await pickSmartSelect(page, 'crm_cprr_status', /停用|Inactive/);
    await fillFormField(page, 'crm_cprr_sort_order', '20');
    await pickSmartSelect(page, 'crm_cprr_time_source', /入池时间|Pool Entry Time/);
    await pickSmartSelect(page, 'crm_cprr_operator', /位于固定区间|Within Fixed Interval/);
    await fillFormField(page, 'crm_cprr_start_at', '2026-08-01T00:00');
    await fillFormField(page, 'crm_cprr_end_at', '2026-08-31T23:59');
    await fillFormField(page, 'crm_cprr_description', 'Inactive fixed interval UI contract');
    const fixedRule = await submitFormCommand(page, 'crm:create_customer_pool_recycle_rule');
    const fixedRulePid = recordPid(fixedRule);
    expect(fixedRulePid, 'UI-created fixed recycle rule pid').toBeTruthy();
    const fixedRuleFact = await page.request.get(
      `/api/dynamic/crm_customer_pool_recycle_rule_common/${fixedRulePid}`,
    );
    const fixedRuleBody = await fixedRuleFact.json();
    expect(String(fixedRuleBody?.data?.crm_cprr_name)).toBe(fixedRuleName);
    await navigateToCustomerPoolStrategy(page, 'recycle');
    let fixedRuleRow = page.getByRole('row', { name: new RegExp(fixedRuleName) });
    await expect(fixedRuleRow).toBeVisible({ timeout: 15_000 });
    await attachScreenshot(page, testInfo, 'customer-pool-recycle-rule-list');
    const deleteFixedResponsePromise = page.waitForResponse(
      (response) => response.url().includes('/execute/crm:delete_customer_pool_recycle_rule'),
      { timeout: 20_000 },
    );
    await clickRowAction(page, fixedRuleRow, 'delete', /删除|Delete/);
    await confirmDialog(page);
    const deleteFixedResponse = await deleteFixedResponsePromise;
    const deleteFixedBody = await deleteFixedResponse.json();
    expect(String(deleteFixedBody?.code)).toBe('0');
    await expect(page.getByRole('row', { name: new RegExp(fixedRuleName) })).toHaveCount(0);

    const staleName = `W2-Stale-${stamp}`;
    const recentName = `W2-Recent-${stamp}`;
    const stale = await createCustomerAndMove(page, poolPid, staleName, 31);
    const recent = await createCustomerAndMove(page, poolPid, recentName, 32);

    // D11/L4 guard: a non-empty customer pool must reject deletion and remain visible.
    await navigateToCustomerPoolStrategy(page);
    poolRow = page.getByRole('row', { name: new RegExp(editedPoolName) });
    const guardedDeleteResponsePromise = page.waitForResponse(
      (response) => response.url().includes('/execute/crm:delete_customer_pool'),
      { timeout: 20_000 },
    );
    await clickRowAction(page, poolRow, 'delete', /删除|Delete/);
    await confirmDialog(page);
    const guardedDeleteResponse = await guardedDeleteResponsePromise;
    const guardedDeleteBody = await guardedDeleteResponse.json().catch(() => ({}));
    expect(JSON.stringify(guardedDeleteBody)).toMatch(
      /contains available customers and cannot be deleted|not empty|non-empty|公海.*非空/i,
    );
    await expect(page.getByRole('row', { name: new RegExp(editedPoolName) })).toBeVisible();

    await command(
      page,
      'crm:assign_pool_customer',
      { crm_cpi_claimed_by: sales.pid },
      stale.itemPid,
      'update',
    );
    await command(
      page,
      'crm:assign_pool_customer',
      { crm_cpi_claimed_by: sales.pid },
      recent.itemPid,
      'update',
    );
    await command(
      page,
      'crm:create_activity',
      {
        crm_act_type: 'call',
        crm_act_subject: `Recent customer follow-up ${stamp}`,
        crm_act_content: 'Real account activity must prevent automatic recycle.',
        crm_act_date: new Date().toISOString(),
        crm_act_source: 'manual',
        crm_act_related_model: 'crm_account_common',
        crm_act_related_id: recent.customerPid,
      },
      undefined,
      'create',
    );

    const tenantPools = await dynamicListRows(page, 'crm_customer_pool_common');
    const tenantPoolItems = await dynamicListRows(page, 'crm_customer_pool_item_common');
    const autoPoolCount = tenantPools.filter(
      (row) => row.crm_cp_status === 'enabled' && row.crm_cp_auto_recycle === true,
    ).length;
    const ownedCandidateCount = tenantPoolItems.filter((row) =>
      ['claimed', 'assigned'].includes(String(row.crm_cpi_status)),
    ).length;
    // The command intentionally scans every enabled automatic pool in the tenant. Keep a bounded
    // linear budget instead of a fixture-only constant: fixed metadata cost + per-pool policy/rule
    // lookup + per-owned-candidate activity/lease evaluation.
    const configuredRecycleBudget = Number(process.env.CUSTOMER_POOL_RECYCLE_SQL_BUDGET);
    const recycleSqlBudget =
      Number.isInteger(configuredRecycleBudget) && configuredRecycleBudget > 0
        ? configuredRecycleBudget
        : Math.max(180, 80 + autoPoolCount * 35 + ownedCandidateCount * 10);

    // D9/D10: run recycle from the toolbar. The stale customer returns to the public pool while
    // the customer with a real recent activity keeps its owner and access state.
    const recycleResponsePromise = page.waitForResponse(
      (response) => response.url().includes('/execute/crm:run_customer_pool_recycle'),
      { timeout: 20_000 },
    );
    await page.getByTestId('workbench-action-run_recycle').click();
    await confirmDialog(page);
    const recycleResponse = await recycleResponsePromise;
    const recycleBody = await recycleResponse.json();
    expect(String(recycleBody?.code)).toBe('0');
    const recycleSqlCount = assertSqlCount(
      await recycleResponse.headerValue('x-sql-count'),
      'manual customer-pool recycle',
      recycleSqlBudget,
    );
    const recycleData = recycleBody?.data?.data ?? recycleBody?.data ?? {};
    expect(
      Number(recycleData?.recycled),
      'at least the stale customer must recycle',
    ).toBeGreaterThanOrEqual(1);
    expect(Number(recycleData?.failed), 'recycle must report no failed candidates').toBe(0);
    await attachJson(testInfo, 'customer-pool-recycle-sql-budget.json', {
      autoPoolCount,
      ownedCandidateCount,
      recycleSqlBudget,
      recycleSqlCount,
    });

    const staleDetailResponse = await page.request.get(
      `/api/dynamic/crm_account_common/${stale.customerPid}`,
    );
    const recentDetailResponse = await page.request.get(
      `/api/dynamic/crm_account_common/${recent.customerPid}`,
    );
    const staleDetail = (await staleDetailResponse.json())?.data;
    const recentDetail = (await recentDetailResponse.json())?.data;
    expect(String(staleDetail?.crm_acc_pool_state)).toBe('in_pool');
    expect(staleDetail?.crm_acc_owner ?? '').toBeFalsy();
    expect(String(recentDetail?.crm_acc_pool_state)).toBe('owned');
    expect(String(recentDetail?.crm_acc_owner)).toBe(sales.pid);

    await navigateToCustomerPoolStrategy(page);
    await page.getByTestId('workbench-action-open_ownership_history').click();
    await expect(page).toHaveURL(/\/p\/crm_customer_owner_history/);
    await expect(page.getByRole('columnheader', { name: /发生时间|Occurred At/ })).toBeVisible({
      timeout: 15_000,
    });
    await expect(page.getByRole('columnheader', { name: /CRM_COH_/i })).toHaveCount(0);
    await expect(page.getByText(/自动回收|Auto Recycled/).first()).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText(/Automatic recycle after 30 days/).first()).toBeVisible();
    await attachScreenshot(page, testInfo, 'customer-pool-recycle-and-ownership-history');

    // Successful empty-pool deletion complements the guarded non-empty branch.
    const emptyPool = await command(
      page,
      'crm:create_customer_pool',
      {
        crm_cp_name: `Empty Pool ${stamp}`,
        crm_cp_member_user_ids: JSON.stringify([sales.pid]),
        crm_cp_admin_user_ids: JSON.stringify([adminPid]),
        crm_cp_daily_pick_limit: 5,
        crm_cp_new_cooldown_days: 0,
        crm_cp_previous_owner_cooldown_days: 0,
        crm_cp_auto_recycle: false,
        crm_cp_recycle_match_mode: 'all',
        crm_cp_recycle_after_days: 30,
        crm_cp_recycle_basis: 'last_activity',
      },
      undefined,
      'create',
    );
    expect(recordPid(emptyPool)).toBeTruthy();
    await navigateToCustomerPoolStrategy(page);
    const emptyPoolRow = page.getByRole('row', { name: new RegExp(`Empty Pool ${stamp}`) });
    await expect(emptyPoolRow).toBeVisible({ timeout: 15_000 });
    const deleteEmptyResponsePromise = page.waitForResponse(
      (response) => response.url().includes('/execute/crm:delete_customer_pool'),
      { timeout: 20_000 },
    );
    await clickRowAction(page, emptyPoolRow, 'delete', /删除|Delete/);
    await confirmDialog(page);
    const deleteEmptyResponse = await deleteEmptyResponsePromise;
    const deleteEmptyBody = await deleteEmptyResponse.json();
    expect(String(deleteEmptyBody?.code)).toBe('0');
    await expect(page.getByRole('row', { name: new RegExp(`Empty Pool ${stamp}`) })).toHaveCount(0);

    // Capacity governance is complete only when administrators can remove an obsolete limit.
    await navigateToCustomerPoolStrategy(page, 'capacity');
    capacityRow = page.getByRole('row', { name: new RegExp(`${capacityRemark} edited`) });
    await expect(capacityRow).toBeVisible({ timeout: 15_000 });
    const deleteCapacityResponsePromise = page.waitForResponse(
      (response) => response.url().includes('/execute/crm:delete_customer_capacity'),
      { timeout: 20_000 },
    );
    await clickRowAction(page, capacityRow, 'delete', /删除|Delete/);
    await confirmDialog(page);
    const deleteCapacityResponse = await deleteCapacityResponsePromise;
    const deleteCapacityBody = await deleteCapacityResponse.json();
    expect(String(deleteCapacityBody?.code)).toBe('0');
    await expect(
      page.getByRole('row', { name: new RegExp(`${capacityRemark} edited`) }),
    ).toHaveCount(0);
  });

  test(IMPORT_MOBILE_SCENARIO, async ({ page, browser, baseURL }, testInfo) => {
    await uiLogin(page, ADMIN_EMAIL);
    const stamp = `${Date.now()}`;
    const meResponse = await page.request.get('/api/auth/me');
    const meBody = await meResponse.json();
    const adminPid = String(meBody?.data?.user?.pid ?? '');
    expect(adminPid, 'admin pid from /api/auth/me').toBeTruthy();
    const sales = await provisionUser(page, stamp, 'sales', ['crm_sales']);
    const viewer = await provisionUser(page, stamp, 'viewer', ['crm_viewer']);
    const pool = await command(
      page,
      'crm:create_customer_pool',
      {
        crm_cp_name: `Cordys Import Mobile ${stamp}`,
        crm_cp_member_user_ids: JSON.stringify([sales.pid]),
        crm_cp_admin_user_ids: JSON.stringify([adminPid]),
        crm_cp_daily_pick_limit: 20,
        crm_cp_new_cooldown_days: 0,
        crm_cp_previous_owner_cooldown_days: 0,
        crm_cp_auto_recycle: false,
        crm_cp_recycle_match_mode: 'all',
        crm_cp_recycle_after_days: 30,
        crm_cp_recycle_basis: 'last_activity',
        crm_cp_description: 'CordysCRM XLSX import and mobile-detail parity fixture',
      },
      undefined,
      'create',
    );
    const poolPid = recordPid(pool);
    expect(poolPid, 'created import customer pool pid').toBeTruthy();
    const poolName = `Cordys Import Mobile ${stamp}`;
    const validCode = `IMP-${stamp}`;
    const validName = `Imported Mobile Customer ${stamp}`;
    const invalidCode = `IMP-BAD-${stamp}`;
    const workbook = customerPoolWorkbook([
      [
        validCode,
        validName,
        'manufacturing',
        'https://imported.example',
        '0755-88886666',
        '深圳市南山区',
        'A',
        'active',
        'Cordys customer-pool import journey',
      ],
      [invalidCode, '', 'software', '', '', '', 'B', 'active', 'Missing name must fail'],
    ]);

    await navigateToCustomerPoolStrategy(page);
    let poolRow = page.getByRole('row', { name: new RegExp(poolName) });
    await expect(poolRow).toBeVisible({ timeout: 15_000 });

    const templateResponsePromise = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        response
          .url()
          .includes('/api/meta/commands/execute/crm:download_customer_pool_import_template'),
      { timeout: 20_000 },
    );
    const templateDownloadPromise = page.waitForEvent('download', { timeout: 20_000 });
    await clickRowAction(
      page,
      poolRow,
      'download_import_template',
      /下载导入模板|Download Import Template/,
    );
    const [templateResponse, templateDownload] = await Promise.all([
      templateResponsePromise,
      templateDownloadPromise,
    ]);
    await assertUiCommandResponse(templateResponse, 'customer-pool import template', 80);
    expect(templateDownload.suggestedFilename()).toBe('crm-customer-pool-import-template.xlsx');
    const templatePath = testInfo.outputPath('customer-pool-import-template.xlsx');
    await templateDownload.saveAs(templatePath);
    const templateWorkbook = xlsxRead(fs.readFileSync(templatePath), { type: 'buffer' });
    const templateRows = XLSXUtils.sheet_to_json<unknown[]>(
      templateWorkbook.Sheets[templateWorkbook.SheetNames[0]],
      { header: 1, defval: '' },
    );
    expect(templateRows[0]).toEqual([
      'crm_acc_code',
      'crm_acc_name',
      'crm_acc_industry',
      'crm_acc_website',
      'crm_acc_phone',
      'crm_acc_address',
      'crm_acc_rating',
      'crm_acc_status',
      'crm_acc_remark',
    ]);
    await testInfo.attach('customer-pool-import-template', {
      path: templatePath,
      contentType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    });

    poolRow = page.getByRole('row', { name: new RegExp(poolName) });
    await runCustomerPoolImportAction(
      page,
      poolRow,
      'precheck_import',
      'crm:precheck_customer_pool_import',
      `customer-pool-precheck-${stamp}.xlsx`,
      workbook,
    );
    await expect(page.getByText(/预检完成，未写入客户数据|Pre-check completed/)).toBeVisible({
      timeout: 30_000,
    });
    await expect(page.getByText(/Customer name is required/)).toBeVisible();
    await attachScreenshot(page, testInfo, 'customer-pool-import-precheck-partial');
    expect(
      (await dynamicListRows(page, 'crm_account_common')).some(
        (record) => record.crm_acc_code === validCode,
      ),
      'precheck must not persist the valid row',
    ).toBe(false);
    await closeAsyncTaskModal(page);

    poolRow = page.getByRole('row', { name: new RegExp(poolName) });
    await runCustomerPoolImportAction(
      page,
      poolRow,
      'import_customers',
      'crm:import_customer_pool_customers',
      `customer-pool-import-blocked-${stamp}.xlsx`,
      workbook,
    );
    await expect(page.getByText(/客户公海导入完成|Customer pool import completed/)).toBeVisible({
      timeout: 30_000,
    });
    await expect(page.getByText(/Customer name is required/)).toBeVisible();
    expect(
      (await dynamicListRows(page, 'crm_account_common')).some(
        (record) => record.crm_acc_code === validCode,
      ),
      'formal import must default to zero writes when any row fails',
    ).toBe(false);
    await closeAsyncTaskModal(page);

    poolRow = page.getByRole('row', { name: new RegExp(poolName) });
    await runCustomerPoolImportAction(
      page,
      poolRow,
      'import_customers',
      'crm:import_customer_pool_customers',
      `customer-pool-import-partial-${stamp}.xlsx`,
      workbook,
      true,
    );
    await expect(page.getByText(/客户公海导入完成|Customer pool import completed/)).toBeVisible({
      timeout: 30_000,
    });
    await expect(page.getByText(/Customer name is required/)).toBeVisible();
    await attachScreenshot(page, testInfo, 'customer-pool-import-partial-success');
    const accounts = await dynamicListRows(page, 'crm_account_common');
    const importedAccount = accounts.find((record) => record.crm_acc_code === validCode);
    expect(
      importedAccount,
      'valid row must persist after explicit partial-success consent',
    ).toBeTruthy();
    expect(importedAccount?.crm_acc_pool_state).toBe('in_pool');
    expect(importedAccount?.crm_acc_owner ?? '').toBeFalsy();
    const importedAccountPid = String(importedAccount?.pid ?? '');
    const poolItems = await dynamicListRows(page, 'crm_customer_pool_item_common');
    const importedItem = poolItems.find(
      (record) => record.crm_cpi_account_id === importedAccountPid,
    );
    const importedItemPid = String(importedItem?.pid ?? '');
    expect(importedItemPid, 'import must create the public-pool projection').toBeTruthy();
    await closeAsyncTaskModal(page);

    await command(
      page,
      'crm:create_activity',
      {
        crm_act_type: 'call',
        crm_act_subject: `Imported customer follow-up ${stamp}`,
        crm_act_content: 'Mobile seller reviewed the imported account and confirmed next action.',
        crm_act_date: new Date().toISOString(),
        crm_act_source: 'manual',
        crm_act_related_model: 'crm_account_common',
        crm_act_related_id: importedAccountPid,
      },
      undefined,
      'create',
    );

    const adminOwnedDeniedFileId = await uploadWorkbook(
      page,
      `viewer-command-denied-${stamp}.xlsx`,
      customerPoolWorkbook([[`VIEWER-${stamp}`, 'Viewer Must Not Import']]),
    );
    const viewerSession = await openAs(browser, baseURL ?? 'http://localhost:5251', viewer.email);
    try {
      const denied = await viewerSession.page.request.post(
        '/api/meta/commands/execute/crm:precheck_customer_pool_import',
        {
          data: {
            targetRecordPid: poolPid,
            operationType: 'UPDATE',
            payload: { importFileId: adminOwnedDeniedFileId, importType: 'ADD' },
          },
        },
      );
      const deniedBody = await denied.json().catch(() => ({}));
      expect(String(deniedBody?.code)).not.toBe('0');
      expect(JSON.stringify(deniedBody)).toMatch(/permission|forbidden|not permitted|无权|权限/i);
    } finally {
      await viewerSession.context.close();
    }

    const adminMobileContext = await browser.newContext({
      baseURL: baseURL ?? 'http://localhost:5251',
      storageState: { cookies: [], origins: [] },
      viewport: { width: 390, height: 844 },
      hasTouch: true,
      isMobile: true,
    });
    const adminMobilePage = await adminMobileContext.newPage();
    try {
      await uiLogin(adminMobilePage, ADMIN_EMAIL);
      await navigateToCrmMenu(
        adminMobilePage,
        /业务档案|Business Records/i,
        '/p/c/crm_customer_pool_item_list',
      );
      const adminCard = adminMobilePage.getByTestId(`table-mobile-card-${importedItemPid}`);
      await expect(adminCard).toContainText(validName, { timeout: 20_000 });
      const adminAssignButton = adminCard.getByRole('button', { name: /分配|Assign/ });
      await expect(adminAssignButton).toBeVisible();
      await adminAssignButton.click();
      const adminMobileAssignDialog = adminMobilePage.getByRole('dialog');
      await expect(
        adminMobileAssignDialog.getByText(/分配池内客户|Assign Pooled Customer/),
      ).toBeVisible();
      await expect(adminMobileAssignDialog.getByTestId('member-picker-add')).toBeVisible();
      await attachScreenshot(adminMobilePage, testInfo, 'mobile-pool-admin-assign-dialog-390');
      await adminMobileAssignDialog.getByRole('button', { name: /取消|Cancel/ }).click();
    } finally {
      await adminMobileContext.close();
    }

    const mobileContext = await browser.newContext({
      baseURL: baseURL ?? 'http://localhost:5251',
      storageState: { cookies: [], origins: [] },
      viewport: { width: 390, height: 844 },
      hasTouch: true,
      isMobile: true,
    });
    const mobilePage = await mobileContext.newPage();
    try {
      await uiLogin(mobilePage, sales.email);
      await navigateToCrmMenu(
        mobilePage,
        /业务档案|Business Records/i,
        '/p/c/crm_customer_pool_item_list',
      );
      await expect(
        mobilePage.getByRole('heading', { name: /客户公海运营台|Customer Pool Operations/ }),
      ).toBeVisible({ timeout: 20_000 });
      const mobileCards = mobilePage.getByTestId('table-mobile-cards');
      await expect(mobileCards).toBeVisible();
      await expect(mobilePage.getByTestId('table-block')).toHaveCount(0);
      const importedCard = mobilePage.getByTestId(`table-mobile-card-${importedItemPid}`);
      await expect(importedCard).toContainText(validName);
      const claimButton = importedCard.getByRole('button', { name: /领取|Claim/ });
      const assignButton = importedCard.getByRole('button', { name: /分配|Assign/ });
      const detailButton = importedCard.getByRole('button', { name: /详情|Details/ });
      await expect(claimButton).toBeVisible();
      await expect(
        assignButton,
        'sales must not receive the pool-administrator assignment action',
      ).toHaveCount(0);
      await expect(detailButton).toBeVisible();
      expect(
        await importedCard
          .getByRole('button')
          .evaluateAll((elements) =>
            elements.every((element) => element.getBoundingClientRect().height >= 44),
          ),
        'mobile public-pool card actions must provide 44px touch targets',
      ).toBe(true);
      expect(
        await mobilePage.evaluate(
          () => document.documentElement.scrollWidth <= window.innerWidth + 1,
        ),
        'mobile public-pool queue must not cause page-level horizontal overflow',
      ).toBe(true);
      await importedCard.scrollIntoViewIfNeeded();
      await attachScreenshot(mobilePage, testInfo, 'mobile-pool-customer-cards-390');

      await detailButton.click();
      await expect(mobilePage).toHaveURL(
        new RegExp(`/p/crm_customer_pool_item/view/${importedItemPid}$`),
      );
      await expect(mobilePage.getByText(validName, { exact: true })).toBeVisible({
        timeout: 20_000,
      });
      const tabs = mobilePage.getByRole('tablist');
      await expect(tabs).toBeVisible();
      const tabButtons = tabs.getByRole('tab');
      expect(await tabButtons.count()).toBeGreaterThanOrEqual(3);
      const customerInfoTab = tabs.getByRole('tab', { name: /客户信息|Customer Info/ });
      const activityHistoryTab = tabs.getByRole('tab', { name: /跟进记录|Activity History/ });
      const ownershipHistoryTab = tabs.getByRole('tab', { name: /归属记录|Ownership History/ });
      await expect(customerInfoTab).toBeVisible();
      await expect(activityHistoryTab).toBeVisible();
      await expect(ownershipHistoryTab).toBeVisible();
      expect(
        await tabButtons.evaluateAll((elements) =>
          elements.every((element) => element.getBoundingClientRect().height >= 44),
        ),
        'mobile tabs must provide 44px touch targets',
      ).toBe(true);
      expect(
        await mobilePage.evaluate(
          () => document.documentElement.scrollWidth <= window.innerWidth + 1,
        ),
        'mobile customer detail must not cause page-level horizontal overflow',
      ).toBe(true);
      await attachScreenshot(mobilePage, testInfo, 'mobile-pool-customer-info-390');

      const timelineResponsePromise = mobilePage.waitForResponse((response) => {
        const url = new URL(response.url());
        return url.searchParams.get('datasourceId') === 'nq:crm_pool_customer_timeline';
      });
      await activityHistoryTab.click();
      const timelineResponse = await timelineResponsePromise;
      const timelineBody = await timelineResponse.json().catch(() => ({}));
      expect(String(timelineBody?.code)).toBe('0');
      expect(JSON.stringify(timelineBody)).toContain(`Imported customer follow-up ${stamp}`);
      await expect(
        mobilePage.getByText(`Imported customer follow-up ${stamp}`, { exact: true }),
      ).toBeVisible({ timeout: 15_000 });
      await attachScreenshot(mobilePage, testInfo, 'mobile-pool-customer-activity-390');

      const historyResponsePromise = mobilePage.waitForResponse((response) => {
        const url = new URL(response.url());
        return url.searchParams.get('datasourceId') === 'nq:crm_pool_customer_owner_history';
      });
      await ownershipHistoryTab.click();
      const historyResponse = await historyResponsePromise;
      const historyBody = await historyResponse.json().catch(() => ({}));
      expect(String(historyBody?.code)).toBe('0');
      expect(JSON.stringify(historyBody)).toContain('imported_to_pool');
      await expect(
        mobilePage.getByText(/导入公海|Imported to Pool|imported_to_pool/i).first(),
      ).toBeVisible({
        timeout: 15_000,
      });
      await attachScreenshot(mobilePage, testInfo, 'mobile-pool-customer-ownership-390');
    } finally {
      await mobileContext.close();
    }
  });
});
