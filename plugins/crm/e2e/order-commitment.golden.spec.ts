import { expect, test, type Page, type TestInfo } from '@playwright/test';
import { mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';

const BASE = process.env.PLAYWRIGHT_BASE_URL || 'http://127.0.0.1:5227';
const BE = process.env.BACKEND_URL || 'http://127.0.0.1:6497';
const RUN = process.env.ORDER_COMMITMENT_RUN_ID || '20260809-212741';
const REQUEST_PID = requiredEnv('ORDER_COMMITMENT_CUSTOMER_REQUEST_PID');
const EVIDENCE_DIR = requiredEnv('ORDER_COMMITMENT_EVIDENCE_DIR');
const ACTOR_EMAIL =
  process.env.ORDER_COMMITMENT_ACTOR_EMAIL
  || `qdp-release-manager-${RUN}@example.test`;
const PASSWORD = 'Test2026x';

let actorJwt = '';
let releasedQdpPid = '';
let releasedQdpCode = '';
let quotePid = '';
let quoteCode = '';
let expectedVersion = 0;
const screenshots: string[] = [];
const completedScenarios = new Set<string>();
const EXPECTED_SCENARIOS = [
  'formal-role-ui-commitment',
  'quote-evidence-and-action-gating',
  'qdp-reverse-drilldown',
] as const;

function requiredEnv(name: string): string {
  const value = process.env[name];
  if (!value) throw new Error(`${name} is required`);
  return value;
}

function findValue(value: unknown, keys: string[]): unknown {
  if (Array.isArray(value)) {
    for (const child of value) {
      const found = findValue(child, keys);
      if (found !== undefined && found !== null && found !== '') return found;
    }
  } else if (value && typeof value === 'object') {
    const record = value as Record<string, unknown>;
    for (const key of keys) {
      if (record[key] !== undefined && record[key] !== null && record[key] !== '') {
        return record[key];
      }
    }
    for (const child of Object.values(record)) {
      const found = findValue(child, keys);
      if (found !== undefined && found !== null && found !== '') return found;
    }
  }
  return undefined;
}

async function api(pathname: string, init: RequestInit = {}, jwt = actorJwt): Promise<any> {
  const headers = new Headers(init.headers);
  if (jwt) headers.set('Authorization', `Bearer ${jwt}`);
  if (init.body) headers.set('Content-Type', 'application/json');
  const response = await fetch(`${BE}${pathname}`, { ...init, headers });
  const body = await response.json().catch(() => ({}));
  return { response, body };
}

function assertOk(result: any, label: string): any {
  expect(
    result.response.ok,
    `${label}: HTTP ${result.response.status} ${JSON.stringify(result.body)}`,
  ).toBeTruthy();
  expect(String(result.body?.code), `${label}: ${JSON.stringify(result.body)}`).toBe('0');
  return result.body;
}

async function loginApi(): Promise<string> {
  const result = await api('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify({ email: ACTOR_EMAIL, password: PASSWORD }),
  }, '');
  const body = assertOk(result, `API login ${ACTOR_EMAIL}`);
  const jwt = findValue(body.data, ['jwt']);
  expect(jwt).toBeTruthy();
  return String(jwt);
}

async function getRecord(model: string, pid: string): Promise<Record<string, any>> {
  const result = await api(`/api/dynamic/${model}/${encodeURIComponent(pid)}`);
  return assertOk(result, `read ${model}/${pid}`).data;
}

async function currentReleasedQdp(): Promise<Record<string, any>> {
  const filters = encodeURIComponent(JSON.stringify([
    { fieldName: 'crm_qdp_customer_request_id', operator: 'EQ', value: REQUEST_PID },
    { fieldName: 'crm_qdp_status', operator: 'EQ', value: 'released' },
  ]));
  const result = await api(
    `/api/dynamic/crm_qdp_revision_common/list?pageNum=1&pageSize=100&filters=${filters}`,
  );
  const body = assertOk(result, 'list current released QDP');
  const rows = body.data?.records ?? body.data ?? [];
  expect(rows, JSON.stringify(body)).toHaveLength(1);
  return rows[0];
}

async function createBrowserQuote(): Promise<Record<string, any>> {
  const tag = `${RUN}-browser-${Date.now()}`;
  const result = await api('/api/meta/commands/execute/crm:create_quote_summary', {
    method: 'POST',
    body: JSON.stringify({
      clientRequestId: `order-commitment-${tag}`,
      payload: {
        crm_qs_customer_request_id: REQUEST_PID,
        crm_qs_source_quote_type: 'pcba_quote',
        crm_qs_source_quote_id: `PCBA-${tag}`,
        crm_qs_status: 'accepted',
        crm_qs_quote_amount: '990000.00',
        crm_qs_currency: 'CNY',
        crm_qs_valid_until: '2026-12-31',
        crm_qs_approval_status: 'approved',
        crm_qs_customer_feedback_status: 'accepted',
        crm_qs_won_lost_result: 'won',
        crm_qs_summary: `Browser order commitment ${tag}`,
      },
    }),
  });
  const body = assertOk(result, 'create browser accepted Quote Summary');
  const pid = findValue(body.data, ['recordPid', 'recordId', 'pid']);
  expect(String(pid)).toMatch(/^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/);
  return getRecord('crm_quote_summary_common', String(pid));
}

async function uiLogin(page: Page): Promise<void> {
  const response = await page.request.post(`${BASE}/login`, {
    form: { email: ACTOR_EMAIL, password: PASSWORD, remember: 'on', redirectTo: '/' },
    maxRedirects: 0,
  });
  expect([302, 303], `UI login ${ACTOR_EMAIL}: HTTP ${response.status()}`)
    .toContain(response.status());
  await page.goto(`${BASE}/`, { waitUntil: 'domcontentloaded' });
  if (page.url().includes('tenant-selection')) {
    await page.getByRole('button', { name: /进入|选择|Enter|AuraBoot/ }).first().click();
    await page.waitForURL((url) => !url.pathname.includes('tenant-selection'), {
      timeout: 15_000,
    });
  }
}

async function shot(page: Page, testInfo: TestInfo, name: string): Promise<void> {
  const output = testInfo.outputPath(name);
  mkdirSync(path.dirname(output), { recursive: true });
  await page.screenshot({ path: output, fullPage: true });
  await testInfo.attach(name, { path: output, contentType: 'image/png' });
  screenshots.push(output);
}

test.describe.configure({ mode: 'serial' });

test.beforeAll(async () => {
  mkdirSync(EVIDENCE_DIR, { recursive: true });
  actorJwt = await loginApi();
  const qdp = await currentReleasedQdp();
  releasedQdpPid = String(qdp.pid);
  releasedQdpCode = String(qdp.crm_qdp_code);
  expect(qdp.crm_qdp_customer_request_id).toBe(REQUEST_PID);
  expect(qdp.crm_qdp_status).toBe('released');

  const quote = await createBrowserQuote();
  quotePid = String(quote.pid);
  quoteCode = String(quote.crm_qs_code);
  expectedVersion = Number(quote.row_version ?? quote.rowVersion);
  expect(quote.crm_qs_status).toBe('accepted');
  expect(expectedVersion).toBeGreaterThan(0);
});

test.afterAll(async () => {
  const evidence = {
    schemaVersion: 1,
    runId: RUN,
    baseUrl: BASE,
    backendUrl: BE,
    actorEmail: ACTOR_EMAIL,
    customerRequestPid: REQUEST_PID,
    releasedQdpPid,
    releasedQdpCode,
    quoteSummaryPid: quotePid,
    quoteSummaryCode: quoteCode,
    expectedScenarios: [...EXPECTED_SCENARIOS],
    completedScenarios: [...completedScenarios].sort(),
    screenshots,
    verdict:
      EXPECTED_SCENARIOS.every((scenario) => completedScenarios.has(scenario))
        ? 'pass'
        : 'fail',
  };
  const output = path.join(EVIDENCE_DIR, `order-commitment-browser-${RUN}.json`);
  writeFileSync(output, `${JSON.stringify(evidence, null, 2)}\n`);
});

test('formal release manager commits the accepted quote and drills back from QDP', async ({ page }, testInfo) => {
  await uiLogin(page);
  await page.goto(`${BASE}/p/crm_quote_summary_common/view/${quotePid}`, {
    waitUntil: 'domcontentloaded',
  });
  await expect(page.getByText(quoteCode, { exact: true }).first()).toBeVisible({ timeout: 20_000 });
  const commitButton = page.getByTestId('toolbar-btn-record_order_commitment');
  await expect(commitButton).toBeVisible();
  await expect(page.getByTestId('toolbar-btn-open_committed_qdp')).toHaveCount(0);

  await commitButton.click();
  const dialog = page.getByTestId('form-dialog');
  await expect(dialog).toBeVisible();
  await expect(dialog).toContainText(/选择正式发布的 QDP|Select Released QDP/);
  const qdpSelect = page.getByTestId('form-dialog-field-crm_qdp_revision_id');
  await qdpSelect.selectOption(releasedQdpPid);
  await expect(qdpSelect).toHaveValue(releasedQdpPid);

  const commandResponse = page.waitForResponse(
    (response) =>
      response.url().includes('/api/meta/commands/execute/crm:record_order_commitment')
      && response.request().method() === 'POST',
    { timeout: 30_000 },
  );
  await page.getByTestId('form-dialog-submit').click();
  const response = await commandResponse;
  const responseBody = await response.json().catch(() => ({}));
  expect(response.ok(), JSON.stringify(responseBody)).toBeTruthy();
  expect(String(responseBody.code), JSON.stringify(responseBody)).toBe('0');
  const requestBody = response.request().postDataJSON() as Record<string, any>;
  expect(requestBody.targetRecordPid).toBe(quotePid);
  expect(requestBody.expectedVersion).toBe(expectedVersion);
  expect(requestBody.payload).toMatchObject({
    crm_quote_summary_id: quotePid,
    crm_qdp_revision_id: releasedQdpPid,
  });
  completedScenarios.add('formal-role-ui-commitment');

  await expect.poll(
    async () => (await getRecord('crm_quote_summary_common', quotePid)).crm_qs_status,
    { timeout: 15_000 },
  ).toBe('ordered');
  await page.reload({ waitUntil: 'domcontentloaded' });
  await expect(page.getByText(/订单承诺与发布证据|Order Commitment and Release Evidence/))
    .toBeVisible({ timeout: 15_000 });
  await expect(page.getByText(releasedQdpCode, { exact: true }).first()).toBeVisible();
  await expect(commitButton).toHaveCount(0);
  const openQdp = page.getByTestId('toolbar-btn-open_committed_qdp');
  await expect(openQdp).toBeVisible();
  await shot(page, testInfo, 'order-commitment-quote-detail.png');
  completedScenarios.add('quote-evidence-and-action-gating');

  await openQdp.click();
  await expect(page).toHaveURL(
    new RegExp(`/p/crm_qdp_revision_common/view/${releasedQdpPid}(?:[?#].*)?$`),
    { timeout: 15_000 },
  );
  await expect(page.getByText(releasedQdpCode, { exact: true }).first()).toBeVisible({
    timeout: 15_000,
  });
  await page.getByText(/生命周期审计|Lifecycle Audit/).first().click();
  await expect(page.getByText(quoteCode, { exact: true }).first()).toBeVisible({ timeout: 20_000 });
  await shot(page, testInfo, 'order-commitment-qdp-reverse-drilldown.png');
  completedScenarios.add('qdp-reverse-drilldown');
});
