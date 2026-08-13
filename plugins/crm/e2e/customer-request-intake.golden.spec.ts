import { expect, test, type Page, type TestInfo } from '@playwright/test';
import { mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';

const BASE = process.env.PLAYWRIGHT_BASE_URL || 'http://127.0.0.1:5231';
const BE = process.env.BACKEND_URL || 'http://127.0.0.1:6501';
const RUN = requiredEnv('CUSTOMER_REQUEST_INTAKE_RUN_ID');
const REQUEST_PID = requiredEnv('CUSTOMER_REQUEST_INTAKE_PID');
const EVIDENCE_DIR = requiredEnv('CUSTOMER_REQUEST_INTAKE_EVIDENCE_DIR');
const ADMIN_EMAIL = process.env.ADMIN_EMAIL || 'admin@auraboot.com';
const PASSWORD = process.env.ADMIN_PW || 'Test2026x';
const EXPECTED_SCENARIOS = [
  'source-evidence-detail-summary',
  'raw-provenance-remains-hidden',
] as const;

let jwt = '';
let requestCode = '';
let contentHash = '';
const screenshots: string[] = [];
const completedScenarios = new Set<string>();

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

async function api(pathname: string, init: RequestInit = {}, token = jwt): Promise<any> {
  const headers = new Headers(init.headers);
  if (token) headers.set('Authorization', `Bearer ${token}`);
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

function jsonValue(value: unknown): unknown {
  return typeof value === 'string' ? JSON.parse(value) : value;
}

async function uiLogin(page: Page): Promise<void> {
  const response = await page.request.post(`${BASE}/login`, {
    form: { email: ADMIN_EMAIL, password: PASSWORD, remember: 'on', redirectTo: '/' },
    maxRedirects: 0,
  });
  expect([302, 303], `UI login ${ADMIN_EMAIL}: HTTP ${response.status()}`)
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
  const login = await api('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify({ email: ADMIN_EMAIL, password: PASSWORD }),
  }, '');
  const loginBody = assertOk(login, 'API login');
  jwt = String(findValue(loginBody.data, ['jwt']) || '');
  expect(jwt).toBeTruthy();
  const result = await api(`/api/dynamic/crm_customer_request_common/${REQUEST_PID}`);
  const record = assertOk(result, 'read governed Customer Request').data;
  requestCode = String(record.crm_cr_code || '');
  contentHash = String(record.crm_cr_source_content_hash || '');
  expect(requestCode).toMatch(/^CR-INT-/);
  expect(contentHash).toMatch(/^[a-f0-9]{64}$/);
  expect(record.crm_cr_intake_snapshot).toBeTruthy();
  expect(record.crm_cr_source_provenance).toBeTruthy();
  expect(jsonValue(record.crm_cr_field_evidence)).toHaveLength(2);
});

test.afterAll(async () => {
  const receipt = {
    schemaVersion: 1,
    runId: RUN,
    baseUrl: BASE,
    backendUrl: BE,
    customerRequestPid: REQUEST_PID,
    requestCode,
    expectedScenarios: [...EXPECTED_SCENARIOS],
    completedScenarios: [...completedScenarios].sort(),
    screenshots,
    verdict: EXPECTED_SCENARIOS.every((scenario) => completedScenarios.has(scenario))
      ? 'pass'
      : 'fail',
  };
  const output = path.join(
    EVIDENCE_DIR,
    `customer-request-intake-browser-${RUN}.json`,
  );
  writeFileSync(output, `${JSON.stringify(receipt, null, 2)}\n`);
});

test('operator inspects the immutable source-evidence summary without raw payload leakage',
  async ({ page }, testInfo) => {
    await uiLogin(page);
    await page.goto(`${BASE}/p/crm_customer_request_common/view/${REQUEST_PID}`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(page.getByText(requestCode, { exact: true }).first())
      .toBeVisible({ timeout: 20_000 });
    const sourceTab = page.getByRole('tab', { name: /来源与证据|Source and Evidence/ });
    await expect(sourceTab).toBeVisible();
    await sourceTab.click();

    await expect(page.getByTestId('form-field-crm_cr_source_channel')).toContainText(/邮件|Email/);
    await expect(page.getByTestId('form-field-crm_cr_source_system')).toContainText('amos.mailbox');
    await expect(page.getByTestId('form-field-crm_cr_source_message_ref'))
      .toContainText(`message-${RUN}`);
    await expect(page.getByTestId('form-field-crm_cr_field_evidence_count')).toContainText('2');
    await expect(page.getByTestId('form-field-crm_cr_source_content_hash'))
      .toContainText(contentHash);
    await shot(page, testInfo, 'customer-request-intake-source-evidence-desktop.png');
    completedScenarios.add('source-evidence-detail-summary');

    for (const hidden of [
      'crm_cr_source_business_key',
      'crm_cr_intake_snapshot',
      'crm_cr_source_provenance',
      'crm_cr_field_evidence',
      'crm_cr_intake_client_request_id',
    ]) {
      await expect(page.getByTestId(`form-field-${hidden}`)).toHaveCount(0);
    }
    await expect(page.locator('body')).not.toContainText('archive://amos-intake/');
    await page.setViewportSize({ width: 390, height: 844 });
    const sidebarToggle = page.getByTestId('header-sidebar-toggle');
    if (await sidebarToggle.getAttribute('aria-expanded') === 'true') {
      await sidebarToggle.click();
    }
    await expect(sidebarToggle).toHaveAttribute('aria-expanded', 'false');
    await page.waitForTimeout(400); // allow the 300ms mobile drawer transition to finish
    await expect(sourceTab).toHaveAttribute('aria-selected', 'true');
    await expect(page.getByTestId('form-field-crm_cr_source_message_ref')).toBeVisible();
    await shot(page, testInfo, 'customer-request-intake-source-evidence-mobile.png');
    completedScenarios.add('raw-provenance-remains-hidden');
  });
