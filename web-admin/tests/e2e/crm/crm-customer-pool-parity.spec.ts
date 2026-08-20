import {
  expect,
  test,
  type APIResponse,
  type Browser,
  type BrowserContext,
  type Locator,
  type Page,
  type Response,
  type TestInfo,
} from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { ensureSidebarExpanded, waitForFormReady } from '../helpers';

const ADMIN_EMAIL = 'admin@auraboot.com';
const PASSWORD = 'Test2026x';

type CommandResult = Record<string, unknown>;

// The first command after plugin import loads the expanded recycle-rule command/model,
// binding, field-mask, SoD and role-scope metadata once. A fresh slot measured 128 SQL;
// the same real command remains <= 100 after those bounded caches are populated.
const COLD_CLAIM_SQL_BUDGET = 130;
const STEADY_COMMAND_SQL_BUDGET = 100;
const REJECT_SQL_BUDGET = 75;
const MEMBER_OPERATIONS_SCENARIO =
  'member scope, single claim, capacity, quota, batch claim and assignment, details and audit persist on the real stack';
const GOVERNANCE_RECYCLE_SCENARIO =
  'administrator governs pools, capacity, relative and fixed recycle rules, and recent activity blocks recycle';
const CUSTOMER_POOL_SCENARIOS = [MEMBER_OPERATIONS_SCENARIO, GOVERNANCE_RECYCLE_SCENARIO];
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
  return assertSqlCount(await response.headerValue('x-sql-count'), label, budget);
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
      responses.map(async (response, index) =>
        assertSqlCount(await response.headerValue('x-sql-count'), `${label} ${index + 1}`, budget),
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

async function dynamicListRows(
  page: Page,
  modelCode: string,
  size = 500,
): Promise<Record<string, unknown>[]> {
  const response = await page.request.get(`/api/dynamic/${modelCode}/list?page=0&size=${size}`);
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
  const field = formField(page, fieldCode);
  await field.scrollIntoViewIfNeeded();
  const control = field.locator('input:not([type="hidden"]), textarea').first();
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
    await direct.click();
    return;
  }
  const directLink = row.getByRole('link', { name: actionName }).first();
  if (await directLink.isVisible().catch(() => false)) {
    await directLink.click();
    return;
  }
  await row.getByTestId('row-action-more').click();
  const menuAction = page.getByTestId(`row-action-${actionCode}`).last();
  await expect(menuAction, `row action ${actionCode}`).toBeVisible({ timeout: 5_000 });
  await menuAction.click();
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
  kind: 'sales' | 'manager',
  roleCodes: string[],
): Promise<{ email: string; pid: string }> {
  const email = `crm-customer-pool-${kind}-${stamp}@e2e.local`;
  const response = await page.request.post('/api/admin/users', {
    data: {
      email,
      displayName: `Customer Pool ${kind === 'sales' ? 'Sales' : 'Manager'} ${stamp}`,
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
  test.setTimeout(180_000);

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
    const a = await createCustomerAndMove(page, poolPid, singleA, 1);
    const b = await createCustomerAndMove(page, poolPid, singleB, 2);
    await createCustomerAndMove(page, poolPid, assignA, 3);
    await createCustomerAndMove(page, poolPid, batchA, 4);
    await createCustomerAndMove(page, poolPid, batchB, 5);
    const revoked = await createCustomerAndMove(page, poolPid, revokedA, 6);
    const race = await createCustomerAndMove(page, poolPid, raceA, 7);

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
      expect(JSON.stringify(capacityBody)).toContain('Customer capacity reached');
      sqlEvidence.capacityReject = assertApiSqlCount(
        capacityResponse,
        'capacity rejection',
        REJECT_SQL_BUDGET,
      );

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
      expect(JSON.stringify(quotaBody)).toContain('Daily customer-pool claim limit reached');
      sqlEvidence.quotaReject = assertApiSqlCount(
        quotaResponse,
        'daily quota rejection',
        REJECT_SQL_BUDGET,
      );

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
    await expect(page.getByRole('button', { name: /人员库容|User Capacity/ })).toBeVisible();
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
    await expect(companyCell(page, assignA)).toBeVisible();
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
      await expect(managerPage.getByText(/批量领取与分配|Batch claim and assignment/)).toBeVisible({
        timeout: 15_000,
      });
      const batchTabs = managerPage.getByRole('navigation', { name: /Tabs/ });
      await expect(batchTabs.getByRole('button', { name: /待领取|Available/ })).toBeVisible();
      await expect(batchTabs.getByRole('button', { name: /已领取|Claimed/ })).toBeVisible();
      await expect(batchTabs.getByRole('button', { name: /已分配|Assigned/ })).toBeVisible();
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
      await expect(managerPage.getByText(/批量分配已完成.*成功 2 条|Assign.*2/i)).toBeVisible({
        timeout: 20_000,
      });
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

    await navigateToCrmMenu(page, /运营与配置|Operations/i, '/p/crm_customer_owner_history');
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
    await navigateToCrmMenu(page, /运营与配置|Operations/i, '/p/crm_customer_pool');
    await expect(
      page.getByRole('heading', { name: /客户公海设置|Customer Pool Settings/, exact: true }),
    ).toBeVisible();
    await page.getByTestId('toolbar-btn-create').click();
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
    await expect(page.getByRole('cell', { name: poolName, exact: true })).toBeVisible({
      timeout: 15_000,
    });
    await attachScreenshot(page, testInfo, 'customer-pool-governance-list');

    // D7/D8: open the persisted detail and edit the same record from its UI action.
    let poolRow = page.getByRole('row', { name: new RegExp(poolName) });
    await clickRowAction(page, poolRow, 'view', /查看|View/);
    await expect(page.getByText(poolName, { exact: true })).toBeVisible({ timeout: 10_000 });
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
    await expect(page.getByRole('cell', { name: editedPoolName, exact: true })).toBeVisible({
      timeout: 15_000,
    });

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
    await navigateToCrmMenu(page, /运营与配置|Operations/i, '/p/crm_customer_capacity');
    await page.getByTestId('toolbar-btn-create').click();
    await waitForFormReady(page, 15_000);
    await pickUserInForm(page, 'crm_ccap_user_id', sales.email);
    await fillFormField(page, 'crm_ccap_capacity', '5');
    await pickSmartSelect(page, 'crm_ccap_status', /生效|Active/);
    const capacityRemark = `Cordys governance UI capacity ${stamp}`;
    await fillFormField(page, 'crm_ccap_remark', capacityRemark);
    const createdCapacity = await submitFormCommand(page, 'crm:create_customer_capacity');
    const capacityPid = recordPid(createdCapacity);
    expect(capacityPid, 'UI-created customer capacity pid').toBeTruthy();
    let capacityRow = page.getByRole('row', { name: new RegExp(capacityRemark) });
    await expect(capacityRow).toBeVisible({ timeout: 15_000 });
    await clickRowAction(page, capacityRow, 'view', /查看|View/);
    await expect(page.getByText(/用户库容|User Capacity/)).toBeVisible();
    await page.getByTestId('toolbar-btn-edit').click();
    await waitForFormReady(page, 15_000);
    await fillFormField(page, 'crm_ccap_capacity', '6');
    await fillFormField(page, 'crm_ccap_remark', `${capacityRemark} edited`);
    await submitFormCommand(page, 'crm:update_customer_capacity');
    const capacityDetail = await page.request.get(
      `/api/dynamic/crm_customer_capacity_common/${capacityPid}`,
    );
    const capacityBody = await capacityDetail.json();
    expect(Number(capacityBody?.data?.crm_ccap_capacity)).toBe(6);

    // D4/D5/D7/D8: create a relative rule used by the actual recycle, inspect it, and update it.
    await navigateToCrmMenu(page, /运营与配置|Operations/i, '/p/crm_customer_pool_recycle_rule');
    await page.getByTestId('toolbar-btn-create').click();
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
    relativeRuleRow = page.getByRole('row', { name: new RegExp(relativeRuleName) });
    await expect(relativeRuleRow).toContainText('11', { timeout: 15_000 });

    // Fixed-window fields are a separate Cordys contract. Create an inactive interval rule so its
    // conditional inputs are exercised without altering the live relative-rule decision below.
    await navigateToCrmMenu(page, /运营与配置|Operations/i, '/p/crm_customer_pool_recycle_rule');
    await page.getByTestId('toolbar-btn-create').click();
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
    await navigateToCrmMenu(page, /运营与配置|Operations/i, '/p/crm_customer_pool');
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
    await page.getByTestId('toolbar-btn-run_recycle').click();
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

    await navigateToCrmMenu(page, /运营与配置|Operations/i, '/p/crm_customer_owner_history');
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
    await navigateToCrmMenu(page, /运营与配置|Operations/i, '/p/crm_customer_pool');
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
    await navigateToCrmMenu(page, /运营与配置|Operations/i, '/p/crm_customer_capacity');
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
});
