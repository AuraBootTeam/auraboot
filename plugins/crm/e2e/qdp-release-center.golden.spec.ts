import { expect, test, type Locator, type Page, type TestInfo } from '@playwright/test';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';

const BASE = process.env.PLAYWRIGHT_BASE_URL || 'http://127.0.0.1:5157';
const BE = process.env.BACKEND_URL || 'http://127.0.0.1:6457';
const RUN = process.env.QDP_RUN_ID || '20260806-170710';
const BROWSER_RUN = process.env.QDP_BROWSER_RUN_ID || `${RUN}-browser`;
const REQUEST_PID = requiredEnv('QDP_CUSTOMER_REQUEST_PID');
const SIDECAR_PID = requiredEnv('QDP_PCBA_RFQ_PID');
const STALE_QDP_PID = requiredEnv('QDP_STALE_QDP_PID');
const RELEASED_QDP_PID = requiredEnv('QDP_RELEASED_QDP_PID');
const RELEASED_PACK_SUMMARY = process.env.QDP_RELEASED_PACK_SUMMARY || 'PCBA-MFG@3.0';
const EVIDENCE_DIR =
  process.env.QDP_EVIDENCE_DIR || mkdtempSync(path.join(tmpdir(), 'qdp-release-center-evidence-'));
const ADMIN_EMAIL = 'admin@auraboot.com';
const PASSWORD = 'Test2026x';
const RELEASE_MANAGER_EMAIL = `qdp-release-manager-${RUN}@example.test`;
const NO_PERMISSION_EMAIL = `qdp-no-permission-${RUN}@example.test`;
const PREPARE_COMMAND = ['crm', 'prepare_qdp_draft'].join(':');
const COMPILE_COMMAND = 'crm:compile_qdp_revision';
const REVIEW_COMMAND = 'crm:submit_qdp_review';
const PUBLISH_COMMAND = 'crm:publish_qdp_revision';
const RELEASE_CENTER_DETAIL_PAGE = 'crm_qdp_revision_common_detail';
const PG = {
  host: process.env.PGHOST || process.env.PG_HOST || '127.0.0.1',
  port: process.env.PGPORT || process.env.PG_PORT || '5432',
  user: process.env.PGUSER || process.env.PG_USER || 'auraboot',
  database: process.env.PGDATABASE || process.env.PG_DB || 'auraboot_57',
  password: process.env.PGPASSWORD || process.env.PG_PASSWORD || 'auraboot',
};

let adminJwt = '';
let staleCode = '';
let releasedCode = '';
let releasedBaselineStatus = '';
let browserQdpPid = '';
let browserQdpCode = '';
let browserFilePid = '';
let compileQdpPid = '';
let compileQdpCode = '';
let compileFilePid = '';
let compileConfirmationPid = '';
let compileConfirmationHash = '';
const screenshots: string[] = [];
const completedScenarios = new Set<string>();
const EXPECTED_SCENARIOS = [
  'async-loading-validation-failed-partial-recovery',
  'empty-state',
  'external-failure-recovery-release',
  'no-permission',
  'release-center-list-and-detail',
  'stale-visible-feedback',
] as const;

function requiredEnv(name: string): string {
  const value = process.env[name];
  if (!value) throw new Error(`${name} is required`);
  return value;
}

function sha256(value: string | Uint8Array): string {
  return createHash('sha256').update(value).digest('hex');
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
      if (record[key] !== undefined && record[key] !== null && record[key] !== '') return record[key];
    }
    for (const child of Object.values(record)) {
      const found = findValue(child, keys);
      if (found !== undefined && found !== null && found !== '') return found;
    }
  }
  return undefined;
}

async function api(pathname: string, init: RequestInit = {}, jwt = adminJwt): Promise<any> {
  const headers = new Headers(init.headers);
  if (jwt) headers.set('Authorization', `Bearer ${jwt}`);
  if (init.body && !(init.body instanceof FormData)) headers.set('Content-Type', 'application/json');
  const response = await fetch(`${BE}${pathname}`, { ...init, headers });
  const body = await response.json().catch(() => ({}));
  return { response, body };
}

function assertOk(result: any, label: string): any {
  expect(result.response.ok, `${label}: HTTP ${result.response.status} ${JSON.stringify(result.body)}`).toBeTruthy();
  expect(String(result.body?.code), `${label}: ${JSON.stringify(result.body)}`).toBe('0');
  return result.body;
}

async function loginApi(email = ADMIN_EMAIL): Promise<string> {
  const result = await api('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify({ email, password: PASSWORD }),
  }, '');
  const body = assertOk(result, `API login ${email}`);
  const jwt = findValue(body?.data, ['jwt']);
  expect(jwt).toBeTruthy();
  return String(jwt);
}

async function getRecord(model: string, pid: string): Promise<Record<string, any>> {
  const result = await api(`/api/dynamic/${model}/${encodeURIComponent(pid)}`);
  const body = assertOk(result, `read ${model}/${pid}`);
  expect(body.data).toBeTruthy();
  return body.data;
}

async function executeCommand(
  code: string,
  payload: Record<string, unknown>,
  targetRecordPid: string,
  expectedVersion: number,
  clientRequestId?: string,
): Promise<any> {
  const envelope: Record<string, unknown> = { payload, targetRecordPid, expectedVersion };
  if (clientRequestId) envelope.clientRequestId = clientRequestId;
  return api(`/api/meta/commands/execute/${code}`, {
    method: 'POST',
    body: JSON.stringify(envelope),
  });
}

async function uploadBrowserFixture(purpose = 'external-failure'): Promise<string> {
  const content = `QDP browser ${purpose} fixture\nrun=${BROWSER_RUN}\n`;
  const form = new FormData();
  form.append(
    'file',
    new Blob([content], { type: 'text/plain' }),
    `qdp-browser-${purpose}-${BROWSER_RUN}.txt`,
  );
  const result = await api('/api/file/upload', { method: 'POST', body: form });
  const body = assertOk(result, 'upload browser fixture');
  const pid = findValue(body?.data, ['fileId', 'pid']);
  expect(pid).toBeTruthy();
  return String(pid);
}

function setConfirmationHash(confirmationPid: string, hash: string): void {
  expect(confirmationPid).toMatch(/^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/);
  expect(hash).toMatch(/^[0-9a-f]{64}$/);
  execFileSync('psql', [
    '-h', PG.host,
    '-p', PG.port,
    '-U', PG.user,
    '-d', PG.database,
    '-v', 'ON_ERROR_STOP=1',
    '-c', `UPDATE mt_crm_customer_confirmation_common SET crm_cc_file_package_hash='${hash}' WHERE pid='${confirmationPid}'`,
  ], { env: { ...process.env, PGPASSWORD: PG.password }, stdio: 'pipe' });
}

function setFileStatus(filePid: string, status: 'success' | 'failed'): void {
  expect(filePid).toMatch(/^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/);
  execFileSync('psql', [
    '-h', PG.host,
    '-p', PG.port,
    '-U', PG.user,
    '-d', PG.database,
    '-v', 'ON_ERROR_STOP=1',
    '-c', `UPDATE ab_file SET status='${status}', updated_time=now() WHERE pid='${filePid}'`,
  ], { env: { ...process.env, PGPASSWORD: PG.password }, stdio: 'pipe' });
}

function handlerData(body: any): Record<string, any> {
  const data = body?.data?.data ?? body?.data ?? {};
  return data?.result && typeof data.result === 'object' ? data.result : data;
}

async function seedBrowserDraftRevision(): Promise<void> {
  browserFilePid = await uploadBrowserFixture('external-failure');
  const request = await getRecord('crm_customer_request_common', REQUEST_PID);
  const expectedVersion = Number(request.row_version ?? request.rowVersion);
  expect(expectedVersion).toBeGreaterThan(0);
  const packHash = sha256(`browser-pack-${BROWSER_RUN}`);
  const payload = {
    crm_qdp_customer_request_id: REQUEST_PID,
    crm_qdp_pcba_rfq_id: SIDECAR_PID,
    crm_qdp_primary_file_id: browserFilePid,
    crm_qdp_file_manifest: [{ filePid: browserFilePid, purpose: 'browser_release' }],
    crm_qdp_requirement_version: `RV-${BROWSER_RUN}`,
    crm_qdp_customer_confirmation_ref: `PORTAL-${BROWSER_RUN}`,
    crm_qdp_customer_confirmed_by: 'browser.customer@example.test',
    crm_qdp_customer_confirmed_at: '2026-08-06T16:00:00+08:00',
    crm_qdp_pack_set: [{ packCode: 'PCBA-MFG', version: 'browser', contentHash: packHash }],
    crm_qdp_downstream_impact: [{
      objectType: 'crm_customer_request_pcba_rfq',
      objectPid: SIDECAR_PID,
      impact: 'browser verified downstream release',
      owner: 'browser-program-owner',
      disposition: 'accepted',
    }],
    crm_qdp_assumptions: ['Browser release uses exact uploaded bytes'],
    crm_qdp_approved_exceptions: [],
    crm_qdp_release_note: 'Browser external-failure recovery',
  };
  const prepared = await executeCommand(
    PREPARE_COMMAND, payload, REQUEST_PID, expectedVersion, `qdp-browser-${BROWSER_RUN}`,
  );
  assertOk(prepared, 'prepare browser QDP');
  browserQdpPid = String(handlerData(prepared.body).qdpRevisionId || '');
  expect(browserQdpPid).toMatch(/^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/);
  const draft = await getRecord('crm_qdp_revision_common', browserQdpPid);
  browserQdpCode = String(draft.crm_qdp_code);
  expect(draft.crm_qdp_status).toBe('draft');
}

async function seedBrowserCompilationRevision(): Promise<void> {
  compileFilePid = await uploadBrowserFixture('async-state');
  const request = await getRecord('crm_customer_request_common', REQUEST_PID);
  const expectedVersion = Number(request.row_version ?? request.rowVersion);
  expect(expectedVersion).toBeGreaterThan(0);
  const packHash = sha256(`browser-compile-pack-${BROWSER_RUN}`);
  const payload = {
    crm_qdp_customer_request_id: REQUEST_PID,
    crm_qdp_pcba_rfq_id: SIDECAR_PID,
    crm_qdp_primary_file_id: compileFilePid,
    crm_qdp_file_manifest: [{ filePid: compileFilePid, purpose: 'browser_async_compile' }],
    crm_qdp_requirement_version: `RV-COMPILE-${BROWSER_RUN}`,
    crm_qdp_customer_confirmation_ref: `PORTAL-COMPILE-${BROWSER_RUN}`,
    crm_qdp_customer_confirmed_by: 'browser.customer@example.test',
    crm_qdp_customer_confirmed_at: '2026-08-06T17:00:00+08:00',
    crm_qdp_pack_set: [{ packCode: 'PCBA-MFG', version: 'async', contentHash: packHash }],
    crm_qdp_downstream_impact: [{
      objectType: 'crm_customer_request_pcba_rfq',
      objectPid: SIDECAR_PID,
      impact: 'browser verified async QDP compilation',
      owner: 'browser-program-owner',
      disposition: 'accepted',
    }],
    crm_qdp_assumptions: ['Browser compile uses exact uploaded bytes'],
    crm_qdp_approved_exceptions: [{
      code: `APPROVED-${BROWSER_RUN}`,
      reason: 'Customer-approved tolerance pending downstream acknowledgement',
    }],
    crm_qdp_release_note: 'Browser async compilation state verification',
  };
  const prepared = await executeCommand(
    PREPARE_COMMAND, payload, REQUEST_PID, expectedVersion, `qdp-compile-${BROWSER_RUN}`,
  );
  assertOk(prepared, 'prepare browser compilation QDP');
  compileQdpPid = String(handlerData(prepared.body).qdpRevisionId || '');
  expect(compileQdpPid).toMatch(/^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/);
  const draft = await getRecord('crm_qdp_revision_common', compileQdpPid);
  compileQdpCode = String(draft.crm_qdp_code);
  compileConfirmationPid = String(draft.crm_qdp_customer_confirmation_id || '');
  compileConfirmationHash = String(draft.crm_qdp_file_package_hash || '');
  expect(compileConfirmationPid).toMatch(/^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/);
  expect(compileConfirmationHash).toMatch(/^[0-9a-f]{64}$/);
  setConfirmationHash(compileConfirmationPid, 'e'.repeat(64));
}

async function uiLogin(page: Page, email = RELEASE_MANAGER_EMAIL): Promise<void> {
  const response = await page.request.post(`${BASE}/login`, {
    form: {
      email,
      password: PASSWORD,
      remember: 'on',
      redirectTo: '/',
    },
    maxRedirects: 0,
  });
  expect([302, 303], `UI session login ${email}: HTTP ${response.status()}`)
    .toContain(response.status());

  await page.goto(`${BASE}/`, { waitUntil: 'domcontentloaded' });
  if (page.url().includes('tenant-selection')) {
    await page.getByRole('button', { name: /进入|选择|Enter|AuraBoot/ }).first().click();
    await page.waitForURL((url) => !url.pathname.includes('tenant-selection'), { timeout: 15_000 });
  }
  await expect(page.locator('input#email')).toHaveCount(0, { timeout: 10_000 });
}

async function gotoReleaseCenter(page: Page) {
  for (let attempt = 0; attempt < 2; attempt += 1) {
    try {
      await page.goto(`${BASE}/dashboards`, { waitUntil: 'domcontentloaded' });
      break;
    } catch (error) {
      if (attempt > 0 || !String(error).includes('ERR_ABORTED')) throw error;
    }
  }
  const nav = page.locator('nav, aside, [role="navigation"]').first();
  await expect(nav).toBeVisible({ timeout: 10_000 });
  const releaseCenterLink = nav.locator('a[href="/p/c/crm_qdp_release_workbench"]').first();
  if (!(await releaseCenterLink.isVisible().catch(() => false))) {
    await nav.getByRole('button', { name: /客户关系管理|CRM/i }).first().click();
  }
  await expect(releaseCenterLink).toBeVisible({ timeout: 10_000 });
  const statsResponsePromise = page.waitForResponse((response) =>
    response.request().method() === 'GET'
      && response.url().includes('/api/datasource/list')
      && new URL(response.url()).searchParams.get('datasourceId')
        === 'nq:crm_qdp_release_stats',
  { timeout: 15_000 });
  await releaseCenterLink.click();
  const statsResponse = await statsResponsePromise;
  expect(statsResponse.ok(), `QDP stats returned HTTP ${statsResponse.status()}`).toBeTruthy();
  await expect(page).toHaveURL(/\/p\/c\/crm_qdp_release_workbench(?:[?#].*)?$/, { timeout: 15_000 });
  await expect(page.getByText(/QDP 发布工作台|QDP Release Workbench/).first())
    .toBeVisible({ timeout: 20_000 });
  return statsResponse;
}

async function searchReleaseWorkbench(page: Page, keyword: string): Promise<void> {
  const search = page.getByTestId('field-crm_qdp_code').locator('input');
  await expect(search).toBeVisible({ timeout: 15_000 });
  const responsePromise = page.waitForResponse((response) =>
    response.url().includes('/api/dynamic/crm_qdp_revision_common')
      && new URL(response.url()).searchParams.get('keyword') === keyword);
  await search.fill(keyword);
  const response = await responsePromise;
  expect(response.ok(), `QDP workbench search returned HTTP ${response.status()}`).toBeTruthy();
  const refreshPromise = page.waitForResponse((candidate) =>
    candidate.request().method() === 'GET'
      && candidate.url().includes('/api/dynamic/crm_qdp_revision_common/list')
      && new URL(candidate.url()).searchParams.get('keyword') === keyword);
  await page.getByTestId('filter-btn-search').click();
  const refresh = await refreshPromise;
  expect(refresh.ok(), `QDP explicit search returned HTTP ${refresh.status()}`).toBeTruthy();
}

async function activateWorkbenchFilter(page: Page, testId: string): Promise<void> {
  const responsePromise = page.waitForResponse((response) =>
    response.request().method() === 'GET'
      && response.url().includes('/api/dynamic/crm_qdp_revision_common/list'));
  await page.getByTestId(testId).click();
  const response = await responsePromise;
  expect(response.ok(), `${testId} returned HTTP ${response.status()}`).toBeTruthy();
  await expect(page.getByTestId(testId)).toHaveClass(/ring-2/);
}

async function openDetail(page: Page, code: string): Promise<void> {
  await gotoReleaseCenter(page);
  await searchReleaseWorkbench(page, code);
  const row = page.locator('tr').filter({ hasText: code }).first();
  await expect(row, `QDP row ${code}`).toBeVisible({ timeout: 20_000 });
  await row.click();
  await expect(page.getByTestId('status-banner-crm_qdp_selected_status')).toBeVisible();
  const openRecord = row.getByTestId('row-action-open_qdp_record');
  await expect(openRecord).toBeVisible();
  await openRecord.click();
  await page.waitForLoadState('networkidle', { timeout: 15_000 }).catch(() => {});
  await expect(
    page.getByText(code, { exact: true }).first(),
    `${RELEASE_CENTER_DETAIL_PAGE} should render the selected revision`,
  ).toBeVisible({ timeout: 15_000 });
}

async function submitRelease(page: Page, note: string): Promise<{ response: any; body: any }> {
  await page.getByRole('button', { name: /发布 QDP|Release QDP/ }).first().click();
  await expect(page.getByRole('heading', { name: /发布确认|Release Confirmation/ }).last())
    .toBeVisible();
  const textarea = page.locator('textarea:visible').last();
  await expect(textarea).toBeVisible();
  await textarea.fill(note);
  const responsePromise = page.waitForResponse((response) =>
    response.url().includes(`/api/meta/commands/execute/${PUBLISH_COMMAND}`)
      && response.request().method() === 'POST');
  await page.getByRole('button', { name: /正式发布|^Release$|^确认$|^Confirm$/ }).last().click();
  const response = await responsePromise;
  return { response, body: await response.json().catch(() => ({})) };
}

async function submitCompilation(page: Page): Promise<{ response: any; body: any }> {
  const responsePromise = page.waitForResponse((response) =>
    response.url().includes(`/api/meta/commands/execute/${COMPILE_COMMAND}`)
      && response.request().method() === 'POST');
  await page.getByRole('button', { name: /编制并校验|Compile and Validate/ }).first().click();
  const response = await responsePromise;
  return { response, body: await response.json().catch(() => ({})) };
}

async function submitReview(page: Page): Promise<{ response: any; body: any }> {
  const responsePromise = page.waitForResponse((response) =>
    response.url().includes(`/api/meta/commands/execute/${REVIEW_COMMAND}`)
      && response.request().method() === 'POST');
  await page.getByRole('button', { name: /提交评审|Submit for Review/ }).first().click();
  const response = await responsePromise;
  return { response, body: await response.json().catch(() => ({})) };
}

async function shot(page: Page, testInfo: TestInfo, name: string): Promise<void> {
  const output = testInfo.outputPath(name);
  mkdirSync(path.dirname(output), { recursive: true });
  await page.screenshot({ path: output, fullPage: true });
  await testInfo.attach(name, { path: output, contentType: 'image/png' });
  screenshots.push(output);
}

async function shotElement(element: Locator, testInfo: TestInfo, name: string): Promise<void> {
  const output = testInfo.outputPath(name);
  mkdirSync(path.dirname(output), { recursive: true });
  await element.screenshot({ path: output });
  await testInfo.attach(name, { path: output, contentType: 'image/png' });
  screenshots.push(output);
}

test.describe.configure({ mode: 'serial' });

test.beforeAll(async () => {
  mkdirSync(EVIDENCE_DIR, { recursive: true });
  adminJwt = await loginApi(RELEASE_MANAGER_EMAIL);
  staleCode = String((await getRecord('crm_qdp_revision_common', STALE_QDP_PID)).crm_qdp_code);
  const releasedBaseline = await getRecord('crm_qdp_revision_common', RELEASED_QDP_PID);
  releasedCode = String(releasedBaseline.crm_qdp_code);
  releasedBaselineStatus = String(releasedBaseline.crm_qdp_status);
  expect(['released', 'superseded']).toContain(releasedBaselineStatus);
  await seedBrowserDraftRevision();
  await seedBrowserCompilationRevision();
});

test.afterAll(async () => {
  if (browserFilePid) setFileStatus(browserFilePid, 'success');
  if (compileConfirmationPid && compileConfirmationHash) {
    setConfirmationHash(compileConfirmationPid, compileConfirmationHash);
  }
  const evidence = {
    schemaVersion: 1,
    runId: RUN,
    browserRunId: BROWSER_RUN,
    baseUrl: BASE,
    backendUrl: BE,
    staleQdpPid: STALE_QDP_PID,
    releasedQdpPid: RELEASED_QDP_PID,
    browserQdpPid,
    browserFilePid,
    compileQdpPid,
    compileFilePid,
    screenshots,
    completedScenarios: [...completedScenarios].sort(),
    expectedScenarios: EXPECTED_SCENARIOS,
    verdict: EXPECTED_SCENARIOS.every((scenario) => completedScenarios.has(scenario))
      ? 'pass'
      : 'incomplete',
  };
  writeFileSync(path.join(EVIDENCE_DIR, `qdp-release-center-browser-${RUN}.json`),
    `${JSON.stringify(evidence, null, 2)}\n`);
});

test('Release Workbench and released record show task hierarchy, evidence and impact', async ({ page }, testInfo) => {
  await uiLogin(page);
  const statsResponse = await gotoReleaseCenter(page);
  const statsBody = await statsResponse.json();
  const visibleQueueTotal = [
    'action_required_count',
    'ready_for_release_count',
    'compiling_count',
    'released_count',
  ].reduce((sum, key) => sum + Number(findValue(statsBody, [key]) || 0), 0);
  expect(visibleQueueTotal, 'the workbench metrics must be backed by retained QDP data')
    .toBeGreaterThan(0);
  for (const key of ['action_required', 'ready_for_release', 'compiling', 'released']) {
    await expect(page.getByTestId(`metric-strip-item-${key}`)).toBeVisible();
  }
  await activateWorkbenchFilter(page, 'metric-strip-item-action_required');
  await expect(page.locator('tr').filter({ hasText: browserQdpCode }).first()).toBeVisible();
  await activateWorkbenchFilter(page, 'metric-strip-item-ready_for_release');
  await expect(page.locator('tr').filter({ hasText: staleCode }).first()).toBeVisible();
  await activateWorkbenchFilter(page, 'metric-strip-item-compiling');
  await expect(page.getByTestId('table-block').first()).toContainText(/暂无数据|No data/);
  await activateWorkbenchFilter(page, 'metric-strip-item-released');
  await activateWorkbenchFilter(page, 'workbench-action-filter_history');
  await expect(page.locator('tr').filter({ hasText: releasedCode }).first()).toBeVisible();
  await activateWorkbenchFilter(page, 'workbench-action-filter_action_required');
  await expect(page.locator('tr').filter({ hasText: browserQdpCode }).first()).toBeVisible();
  await activateWorkbenchFilter(page, 'workbench-action-filter_ready');
  await expect(page.locator('tr').filter({ hasText: staleCode }).first()).toBeVisible();
  await activateWorkbenchFilter(page, 'workbench-action-filter_all');
  await searchReleaseWorkbench(page, releasedCode);
  const releasedBaselineRow = page.locator('tr').filter({ hasText: releasedCode }).first();
  await expect(releasedBaselineRow).toBeVisible();
  await expect(releasedBaselineRow).toContainText(
    releasedBaselineStatus === 'released' ? /已发布|Released/ : /已取代|Superseded/,
  );
  await expect(page.getByTestId('status-banner-crm_qdp_selected_status')).toBeVisible();
  await expect(page.getByTestId('status-banner-crm_qdp_selected_status')).toContainText(
    releasedBaselineStatus === 'released'
      ? /当前版本已发布|Current revision released/
      : /历史版本，已被新版本取代|Historical revision superseded/,
  );
  await expect(page.getByText(/发布证据与影响范围|Release Evidence and Impact/).first())
    .toBeVisible();
  await shot(page, testInfo, 'qdp-release-workbench.png');

  await openDetail(page, releasedCode);
  await expect(page.getByText(
    releasedBaselineStatus === 'released' ? /已发布|Released/ : /已取代|Superseded/,
  ).first()).toBeVisible();
  await expect(page.getByText(/发布决策摘要|Release Decision Summary/).first()).toBeVisible();
  await expect(page.getByText(/制造范围与下游影响|Manufacturing Scope and Downstream Impact/).first())
    .toBeVisible();
  const body = await page.locator('body').innerText();
  expect(body).toContain(RELEASED_PACK_SUMMARY);
  expect(body).toContain('1 downstream object(s), 0 blocked');
  expect(body).not.toMatch(/\bcrm_qdp_[a-z_]+\b/);
  expect(body).not.toMatch(/\b\d{18,20}\b/);
  await shot(page, testInfo, 'qdp-release-center-released-detail.png');
  await page.getByText(/客户确认与文件证据|Customer and File Evidence/).first().click();
  const identitySection = page.getByText(/版本与追溯证据|Revision and Trace Evidence/).first();
  await expect(identitySection).toBeVisible({ timeout: 10_000 });
  const evidenceBody = await page.locator('body').innerText();
  expect(evidenceBody).toMatch(/[0-9a-f]{64}/);
  expect(evidenceBody).not.toMatch(/\b\d{18,20}\b/);
  await shot(page, testInfo, 'qdp-release-center-released-identity.png');
  await page.getByText(/生命周期审计|Lifecycle Audit/).first().click();
  const auditBody = await page.locator('main, [role="main"]').first().innerText();
  expect(auditBody).not.toMatch(/\b\d{18,20}\b/);
  await shot(page, testInfo, 'qdp-release-center-released-audit.png');
  await page.getByText(/发布就绪度|Release Readiness/).first().click();
  const impactSection = page.getByText(/制造范围与下游影响|Manufacturing Scope and Downstream Impact/).first();
  await impactSection.scrollIntoViewIfNeeded();
  await expect(impactSection).toBeVisible();
  await shot(page, testInfo, 'qdp-release-center-released-impact.png');
  const impactBlock = impactSection.locator('..');
  await expect(impactBlock).toContainText(RELEASED_PACK_SUMMARY);
  await expect(impactBlock).toContainText('1 downstream object(s), 0 blocked');
  await shotElement(impactBlock, testInfo, 'qdp-release-center-released-impact-section.png');
  await page.getByTestId('toolbar-btn-back_to_release_workbench').click();
  await expect(page).toHaveURL(/\/p\/c\/crm_qdp_release_workbench(?:[?#].*)?$/);
  await expect(page.getByText(/QDP 发布工作台|QDP Release Workbench/).first()).toBeVisible();
  completedScenarios.add('release-center-list-and-detail');
});

test('Release Center search exposes a real empty state instead of an ambiguous blank table', async ({ page }, testInfo) => {
  await uiLogin(page);
  await gotoReleaseCenter(page);
  const noMatch = `NO-MATCH-${BROWSER_RUN}`;
  await searchReleaseWorkbench(page, noMatch);
  const emptyContent = page.getByTestId('table-block').first();
  await expect(emptyContent).toBeVisible();
  await expect(emptyContent).toContainText(/暂无数据|No data/);
  const emptyBox = await emptyContent.boundingBox();
  const viewport = page.viewportSize();
  expect(emptyBox, 'empty-state content must have a rendered bounding box').toBeTruthy();
  expect(viewport, 'empty-state test requires a fixed browser viewport').toBeTruthy();
  expect(emptyBox!.x).toBeGreaterThanOrEqual(0);
  expect(emptyBox!.x + emptyBox!.width).toBeLessThanOrEqual(viewport!.width + 1);
  await shot(page, testInfo, 'qdp-release-center-empty-state.png');
  const resetResponsePromise = page.waitForResponse((response) =>
    response.request().method() === 'GET'
      && response.url().includes('/api/dynamic/crm_qdp_revision_common/list')
      && !new URL(response.url()).searchParams.get('keyword'));
  await page.getByTestId('filter-btn-reset').click();
  const resetResponse = await resetResponsePromise;
  expect(resetResponse.ok(), `QDP workbench reset returned HTTP ${resetResponse.status()}`).toBeTruthy();
  await expect(page.getByTestId('row-action-open_qdp_record').first()).toBeVisible();
  completedScenarios.add('empty-state');
});

test('browser-driven async compilation shows loading, validation recovery and partial success', async ({ page }, testInfo) => {
  await uiLogin(page);
  await openDetail(page, compileQdpCode);

  const failedDispatch = await submitCompilation(page);
  expect(failedDispatch.response.ok(), JSON.stringify(failedDispatch.body)).toBeTruthy();
  expect(String(failedDispatch.body?.code)).toBe('0');
  await expect(page.getByText(/已提交.*后台处理中|Submitted.*background/i).first())
    .toBeVisible({ timeout: 5_000 });
  await shot(page, testInfo, 'qdp-release-center-compilation-submitted.png');

  await expect(page.getByTestId('async-task-modal-failed')).toBeVisible({ timeout: 20_000 });
  await expect(page.getByTestId('async-task-modal-error'))
    .toContainText(/客户确认.*当前需求版本.*文件包.*重新确认/i);
  await shot(page, testInfo, 'qdp-release-center-validation-failed-modal.png');
  await page.getByRole('button', { name: /关闭/ }).last().click();
  await expect(page.getByText(/校验失败|Validation Failed/).first()).toBeVisible({ timeout: 15_000 });
  await expect(page.getByText(/校验失败与恢复提示|Validation Failure and Recovery/).first())
    .toBeVisible();
  const failedBody = await page.locator('body').innerText();
  expect(failedBody).toMatch(/客户确认.*当前需求版本.*文件包.*重新确认/i);
  expect(failedBody).not.toMatch(/\bcrm_qdp_[a-z_]+\b/);
  expect(failedBody).not.toMatch(/\b\d{18,20}\b/);
  await shot(page, testInfo, 'qdp-release-center-validation-failed-detail.png');

  setConfirmationHash(compileConfirmationPid, compileConfirmationHash);
  await page.reload({ waitUntil: 'domcontentloaded' });
  const recoveredDispatch = await submitCompilation(page);
  expect(recoveredDispatch.response.ok(), JSON.stringify(recoveredDispatch.body)).toBeTruthy();
  expect(String(recoveredDispatch.body?.code)).toBe('0');
  await expect(page.getByText(/QDP 编制完成|QDP compilation completed/).last())
    .toBeVisible({ timeout: 20_000 });
  await expect(page.getByText(/部分成功|Partial Success/).last()).toBeVisible();
  await shot(page, testInfo, 'qdp-release-center-partial-success-modal.png');
  await page.getByRole('button', { name: /关闭/ }).last().click();
  await expect(page.getByText(/部分成功|Partial Success/).first()).toBeVisible({ timeout: 15_000 });
  await expect(page.getByText(/批准例外|approved exception/i).first()).toBeVisible();
  const recoveredBody = await page.locator('body').innerText();
  expect(recoveredBody).not.toContain('partial_success');
  expect(recoveredBody).not.toMatch(/\bcrm_qdp_[a-z_]+\b/);
  await shot(page, testInfo, 'qdp-release-center-partial-success-detail.png');

  const recovered = await getRecord('crm_qdp_revision_common', compileQdpPid);
  expect(recovered.crm_qdp_status).toBe('ready_for_review');
  expect(recovered.crm_qdp_compilation_outcome).toBe('partial_success');
  expect(recovered.crm_qdp_compilation_progress).toBe(100);
  completedScenarios.add('async-loading-validation-failed-partial-recovery');
});

test('stale QDP release produces visible browser feedback and remains in review', async ({ page }, testInfo) => {
  await uiLogin(page);
  await openDetail(page, staleCode);
  const result = await submitRelease(page, 'browser stale-source probe');
  expect(result.response.ok()).toBeFalsy();
  expect(JSON.stringify(result.body)).toMatch(/stale|source|new revision/i);
  await expect(page.getByText(/Bad parameter|失败|错误|stale|过期/i).first()).toBeVisible({ timeout: 10_000 });
  await shot(page, testInfo, 'qdp-release-center-stale-feedback.png');
  expect((await getRecord('crm_qdp_revision_common', STALE_QDP_PID)).crm_qdp_status)
    .toBe('ready_for_review');
  completedScenarios.add('stale-visible-feedback');
});

test('browser review, real file-runtime failure, retry, release and supersede stay consistent', async ({ page }, testInfo) => {
  await uiLogin(page);
  await openDetail(page, browserQdpCode);
  await expect(page.getByRole('button', { name: /提交评审|Submit for Review/ })).toBeVisible();
  const review = await submitReview(page);
  expect(review.response.ok(), JSON.stringify(review.body)).toBeTruthy();
  expect(String(review.body?.code)).toBe('0');
  await page.reload({ waitUntil: 'domcontentloaded' });
  await expect(page.getByText(/待评审|待发布评审|Ready for Review/).first()).toBeVisible({ timeout: 15_000 });
  const reviewed = await getRecord('crm_qdp_revision_common', browserQdpPid);
  expect(reviewed.crm_qdp_status).toBe('ready_for_review');
  expect(reviewed.crm_qdp_review_submitted_at).toBeTruthy();
  await page.getByText(/发布决策摘要|Release Decision Summary/).first().scrollIntoViewIfNeeded();
  await shot(page, testInfo, 'qdp-release-center-review-submitted.png');

  setFileStatus(browserFilePid, 'failed');
  const failed = await submitRelease(page, 'browser file-runtime failure probe');
  expect(failed.response.ok()).toBeFalsy();
  expect(JSON.stringify(failed.body)).toMatch(/file|status|finalized|retention/i);
  await expect(page.getByText(/Bad parameter|失败|错误|file/i).first()).toBeVisible({ timeout: 10_000 });
  await shot(page, testInfo, 'qdp-release-center-external-failure-feedback.png');
  expect((await getRecord('crm_qdp_revision_common', browserQdpPid)).crm_qdp_status)
    .toBe('ready_for_review');

  setFileStatus(browserFilePid, 'success');
  await page.reload({ waitUntil: 'domcontentloaded' });
  const released = await submitRelease(page, 'browser recovery formal release');
  expect(released.response.ok()).toBeTruthy();
  expect(String(released.body?.code)).toBe('0');
  await page.reload({ waitUntil: 'domcontentloaded' });
  await expect(page.getByText(/已发布|Released/).first()).toBeVisible({ timeout: 15_000 });
  await page.getByText(/发布决策摘要|Release Decision Summary/).first().scrollIntoViewIfNeeded();
  await shot(page, testInfo, 'qdp-release-center-browser-released.png');
  expect((await getRecord('crm_qdp_revision_common', browserQdpPid)).crm_qdp_status).toBe('released');
  expect((await getRecord('crm_qdp_revision_common', RELEASED_QDP_PID)).crm_qdp_status).toBe('superseded');
  completedScenarios.add('external-failure-recovery-release');
});

test('no-permission user cannot see the menu, lifecycle actions or QDP data', async ({ browser }, testInfo) => {
  const context = await browser.newContext();
  const page = await context.newPage();
  await uiLogin(page, NO_PERMISSION_EMAIL);
  await page.goto(`${BASE}/dashboards`, { waitUntil: 'domcontentloaded' });
  const nav = page.locator('nav, aside, [role="navigation"]').first();
  await expect(nav).toBeVisible({ timeout: 15_000 });
  await expect(page.getByText(/暂无可用菜单|No available menus/i).first()).toBeVisible();
  await expect(page.getByText(/加载中|Loading/i).first()).toBeHidden();
  await expect(nav.locator('a[href="/p/c/crm_qdp_release_workbench"]')).toHaveCount(0);
  await shot(page, testInfo, 'qdp-release-center-no-permission.png');
  const qdpResponses: number[] = [];
  page.on('response', (response) => {
    if (response.url().includes('crm_qdp_revision_common')) qdpResponses.push(response.status());
  });
  // page-golden-audit allow-direct-page: this is the negative direct-URL authorization probe;
  // the release-manager path above independently proves the real sidebar entry.
  await page.goto(`${BASE}/p/c/crm_qdp_release_workbench`, { waitUntil: 'domcontentloaded' });
  await page.waitForLoadState('networkidle', { timeout: 15_000 }).catch(() => {});
  await expect(page.getByRole('button', { name: /发布 QDP|提交评审|Release QDP|Submit for Review/ }))
    .toHaveCount(0);
  const body = await page.locator('body').innerText();
  expect(body.includes(releasedCode)).toBeFalsy();
  expect(qdpResponses.some((status) => status === 401 || status === 403)
    || /无权限|forbidden|permission|access denied|403/i.test(body))
    .toBeTruthy();
  completedScenarios.add('no-permission');
  await context.close();
});
