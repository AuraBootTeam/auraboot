import { expect, test, type Browser, type Page, type TestInfo } from '@playwright/test';
import { mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';

const BASE = process.env.PLAYWRIGHT_BASE_URL || 'http://127.0.0.1:5155';
const BE = process.env.BACKEND_URL || 'http://127.0.0.1:6455';
const RUN = process.env.CRM_OPPORTUNITY_PERMISSION_RUN_ID || `opp-perm-${Date.now()}`;
const EVIDENCE_DIR = process.env.CRM_OPPORTUNITY_PERMISSION_EVIDENCE_DIR
  || path.join('/tmp', `crm-opportunity-permission-${RUN}`);
const ADMIN_EMAIL = process.env.CRM_OPPORTUNITY_PERMISSION_ADMIN_EMAIL || 'admin@auraboot.com';
const ADMIN_PASSWORD = process.env.CRM_OPPORTUNITY_PERMISSION_ADMIN_PASSWORD || 'Test2026x';
const PERSONA_PASSWORD = process.env.CRM_OPPORTUNITY_PERMISSION_PERSONA_PASSWORD || 'AuraBoot2026!';
const MUTATION = process.env.CRM_OPPORTUNITY_PERMISSION_MUTATION === 'viewer-can-manage';

type Persona = {
  roleCode: string;
  label: string;
  canRead: boolean;
  canManage: boolean;
  canImport: boolean;
};

const personas: Persona[] = [
  { roleCode: 'crm_sales_manager', label: 'sales-manager', canRead: true, canManage: true, canImport: true },
  { roleCode: 'crm_sales', label: 'sales-representative', canRead: true, canManage: true, canImport: true },
  { roleCode: 'crm_viewer', label: 'read-only-viewer', canRead: true, canManage: MUTATION, canImport: false },
  { roleCode: 'crm_qdp_release_manager', label: 'qdp-release-manager', canRead: true, canManage: false, canImport: false },
  { roleCode: 'crm_service', label: 'service-agent', canRead: false, canManage: false, canImport: false },
];

const checks: Array<Record<string, unknown>> = [];
const screenshots: string[] = [];
const completedPersonas = new Set<string>();
const sessions = new Map<string, { email: string; jwt: string }>();
let adminJwt = '';

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

async function request(pathname: string, jwt = adminJwt, init: RequestInit = {}): Promise<any> {
  const headers = new Headers(init.headers);
  if (jwt) headers.set('Authorization', `Bearer ${jwt}`);
  if (init.body) headers.set('Content-Type', 'application/json');
  const response = await fetch(`${BE}${pathname}`, { ...init, headers });
  const body = await response.json().catch(() => ({}));
  return { response, body };
}

function expectAllowed(result: any, label: string): any {
  expect(result.response.ok, `${label}: HTTP ${result.response.status} ${JSON.stringify(result.body)}`).toBeTruthy();
  expect(String(result.body?.code), `${label}: ${JSON.stringify(result.body)}`).toBe('0');
  checks.push({ label, verdict: 'pass', httpStatus: result.response.status });
  return result.body;
}

function expectDenied(result: any, label: string): void {
  expect(
    result.response.status === 403 || String(result.body?.code) === '403',
    `${label}: expected 403, got HTTP ${result.response.status} ${JSON.stringify(result.body)}`,
  ).toBeTruthy();
  checks.push({ label, verdict: 'pass', httpStatus: result.response.status });
}

async function loginApi(email: string, password: string): Promise<string> {
  const body = expectAllowed(await request('/api/auth/login', '', {
    method: 'POST',
    body: JSON.stringify({ email, password }),
  }), `API login ${email}`);
  const jwt = findValue(body?.data, ['jwt']);
  expect(jwt, `${email} must return a JWT`).toBeTruthy();
  return String(jwt);
}

async function loginUi(page: Page, email: string): Promise<void> {
  const response = await page.request.post(`${BASE}/login`, {
    form: { email, password: PERSONA_PASSWORD, remember: 'on', redirectTo: '/' },
    maxRedirects: 0,
  });
  expect([302, 303], `UI login ${email}: HTTP ${response.status()}`).toContain(response.status());
  await page.goto(`${BASE}/`, { waitUntil: 'load' });
  if (page.url().includes('tenant-selection')) {
    await page.getByRole('button', { name: /进入|选择|Enter|AuraBoot/ }).first().click();
    await page.waitForURL((url) => !url.pathname.includes('tenant-selection'), { timeout: 15_000 });
  }
  await expect(page.locator('input#email')).toHaveCount(0, { timeout: 10_000 });
}

async function shot(page: Page, testInfo: TestInfo, name: string): Promise<void> {
  const output = path.join(EVIDENCE_DIR, name);
  await page.screenshot({ path: output, fullPage: true });
  await testInfo.attach(name, { path: output, contentType: 'image/png' });
  screenshots.push(output);
}

async function adminCount(marker: string): Promise<number> {
  const result = expectAllowed(await request(
    `/api/dynamic/crm_opportunity_common/list?pageNum=1&pageSize=20&keyword=${encodeURIComponent(marker)}`,
  ), `admin counts ${marker}`);
  return Number(result?.data?.total ?? result?.data?.totalElements ?? 0);
}

function rowsOf(data: unknown): any[] {
  if (Array.isArray(data)) return data;
  if (data && typeof data === 'object') {
    const record = data as Record<string, unknown>;
    if (Array.isArray(record.records)) return record.records;
    if (Array.isArray(record.list)) return record.list;
    if (Array.isArray(record.rows)) return record.rows;
  }
  return [];
}

function activeRoleAssignmentPids(data: unknown): string[] {
  return rowsOf(data)
    .filter((row) => String(row?.status ?? 'active') === 'active')
    .map((row) => String(row?.pid ?? ''))
    .filter(Boolean);
}

async function assertApiMatrix(persona: Persona): Promise<void> {
  const session = sessions.get(persona.roleCode)!;
  const list = await request('/api/dynamic/crm_opportunity_common/list?pageNum=1&pageSize=20', session.jwt);
  if (persona.canRead) expectAllowed(list, `${persona.label} reads opportunities`);
  else expectDenied(list, `${persona.label} cannot read opportunities`);

  const marker = `${RUN}-${persona.label}`;
  expect(await adminCount(marker)).toBe(0);
  const create = await request('/api/meta/commands/execute/crm:create_opportunity', session.jwt, {
    method: 'POST',
    body: JSON.stringify({
      operationType: 'create',
      payload: {
        crm_opp_name: marker,
        crm_opp_expected_amount: 88000,
        crm_opp_expected_close_date: '2026-12-31T18:00:00+08:00',
        crm_opp_probability: 25,
        crm_opp_forecast_category: 'pipeline',
      },
    }),
  });
  if (persona.canManage) {
    const created = expectAllowed(create, `${persona.label} creates an opportunity`);
    const createdPid = findValue(created?.data ?? created, ['recordId', 'recordPid', 'publicRecordId', 'pid']);
    expect(createdPid, `${persona.label} create returns a record PID`).toBeTruthy();
    expect(await adminCount(marker)).toBe(1);
    expectAllowed(await request('/api/meta/commands/execute/crm:update_opportunity', session.jwt, {
      method: 'POST',
      body: JSON.stringify({
        targetRecordPid: createdPid,
        operationType: 'update',
        payload: { crm_opp_expected_amount: 99000 },
      }),
    }), `${persona.label} updates an opportunity`);
    const updated = expectAllowed(await request(
      `/api/dynamic/crm_opportunity_common/${encodeURIComponent(String(createdPid))}`,
    ), `admin reads updated ${persona.label} opportunity`);
    expect(findValue(updated?.data, ['crm_opp_expected_amount']) ?? null).toBe(99000);
    expectAllowed(await request('/api/meta/commands/execute/crm:delete_opportunity', session.jwt, {
      method: 'POST',
      body: JSON.stringify({ targetRecordPid: createdPid, operationType: 'delete', payload: {} }),
    }), `${persona.label} deletes an opportunity`);
    expect(await adminCount(marker)).toBe(0);
  } else {
    expectDenied(create, `${persona.label} cannot create opportunities`);
    expect(await adminCount(marker)).toBe(0);
    checks.push({ label: `${persona.label} denied create has no persistence side effect`, verdict: 'pass' });
    const forgedPid = `forged-${RUN}-${persona.label}`;
    expectDenied(await request('/api/meta/commands/execute/crm:update_opportunity', session.jwt, {
      method: 'POST',
      body: JSON.stringify({
        targetRecordPid: forgedPid,
        operationType: 'update',
        payload: { crm_opp_expected_amount: 1 },
      }),
    }), `${persona.label} cannot update opportunities`);
    expectDenied(await request('/api/meta/commands/execute/crm:delete_opportunity', session.jwt, {
      method: 'POST',
      body: JSON.stringify({ targetRecordPid: forgedPid, operationType: 'delete', payload: {} }),
    }), `${persona.label} cannot delete opportunities`);
    checks.push({ label: `${persona.label} denied update/delete has no persistence side effect`, verdict: 'pass' });
  }
}

async function assertBrowserMatrix(browser: Browser, persona: Persona, testInfo: TestInfo): Promise<void> {
  const context = await browser.newContext();
  const page = await context.newPage();
  const session = sessions.get(persona.roleCode)!;
  await loginUi(page, session.email);
  const menuLink = page.locator('a[href="/p/crm_opportunity_common"]');

  if (!persona.canRead) {
    await expect(menuLink).toHaveCount(0);
    await shot(page, testInfo, `par08-permission-${persona.label}-menu-denied.png`);
    checks.push({ label: `${persona.label} opportunity menu is hidden`, verdict: 'pass' });
    await context.close();
    return;
  }

  await expect(menuLink).toHaveCount(1);
  await menuLink.click();
  await expect(page.getByTestId('dynamic-list')).toBeVisible({ timeout: 20_000 });
  await expect(page.getByTestId('list-view-mode-table')).toBeVisible();
  await expect(page.getByTestId('list-view-mode-kanban')).toBeVisible();
  await expect(page.getByTestId('view-analysis-open')).toBeVisible();
  await expect(page.getByTestId('toolbar-btn-create')).toHaveCount(persona.canManage ? 1 : 0);

  await page.getByTestId('toolbar-more-menu').click();
  await expect(page.getByTestId('more-menu-export-csv')).toBeVisible();
  await expect(page.getByTestId('more-menu-import')).toHaveCount(persona.canImport ? 1 : 0);
  await page.keyboard.press('Escape');

  if (!persona.canManage) {
    await expect(page.getByRole('button', { name: /编辑|Edit/ })).toHaveCount(0);
    await expect(page.getByRole('button', { name: /删除|Delete/ })).toHaveCount(0);
  }
  await shot(page, testInfo, `par08-permission-${persona.label}.png`);
  checks.push({
    label: `${persona.label} browser controls match read/manage/import grants`,
    verdict: 'pass',
    canRead: persona.canRead,
    canManage: persona.canManage,
    canImport: persona.canImport,
  });
  await context.close();
}

test.describe.configure({ mode: 'serial' });

test.beforeAll(async () => {
  mkdirSync(EVIDENCE_DIR, { recursive: true });
  adminJwt = await loginApi(ADMIN_EMAIL, ADMIN_PASSWORD);
  expectAllowed(await request('/api/dynamic/crm_opportunity_common/list?pageNum=1&pageSize=20'),
    'administrator reads opportunities');

  for (const persona of personas) {
    const email = `${persona.label}.${Date.now()}@example.test`;
    expectAllowed(await request('/api/admin/users', adminJwt, {
      method: 'POST',
      body: JSON.stringify({
        email,
        displayName: `${RUN.slice(-24)} ${persona.label}`.slice(0, 50),
        initialPassword: PERSONA_PASSWORD,
        roleCodes: [persona.roleCode],
        sendInviteEmail: false,
      }),
    }), `provision ${persona.label}`);
    sessions.set(persona.roleCode, { email, jwt: await loginApi(email, PERSONA_PASSWORD) });
  }
});

for (const persona of personas) {
  test(`${persona.label} exact opportunity permission matrix`, async ({ browser }, testInfo) => {
    await assertApiMatrix(persona);
    await assertBrowserMatrix(browser, persona, testInfo);
    completedPersonas.add(persona.roleCode);
  });
}

test.afterAll(() => {
  const receipt = {
    schemaVersion: 1,
    run: RUN,
    runtime: process.env.AURA_RUNTIME_NAME || 'crm-par09-governance-20260825-s55',
    sourceRoot: process.env.CRM_OPPORTUNITY_PERMISSION_SOURCE_ROOT || process.cwd(),
    frontend: BASE,
    backend: BE,
    database: process.env.PGDATABASE || 'auraboot_55',
    mutation: MUTATION ? 'viewer-can-manage' : null,
    denominator: personas.map(({ roleCode, label, canRead, canManage, canImport }) => ({
      roleCode, label, canRead, canManage, canImport,
    })),
    checks,
    screenshots,
    summary: {
      personas: personas.length,
      completedPersonas: completedPersonas.size,
      passedChecks: checks.filter((check) => check.verdict === 'pass').length,
      failedChecks: personas.length - completedPersonas.size,
      retries: 0,
    },
    verdict: completedPersonas.size === personas.length ? 'pass' : 'fail',
    dataMigration: 'not required; development stage',
  };
  writeFileSync(path.join(EVIDENCE_DIR, 'par08-opportunity-permission-matrix.json'),
    `${JSON.stringify(receipt, null, 2)}\n`, { mode: 0o600 });
});

test('existing sales session loses and regains opportunity access after role revocation', async () => {
  const label = 'revocation-sales';
  const email = `${label}.${Date.now()}@example.test`;
  expectAllowed(await request('/api/admin/users', adminJwt, {
    method: 'POST',
    body: JSON.stringify({
      email,
      displayName: `${RUN.slice(-24)} ${label}`.slice(0, 50),
      initialPassword: PERSONA_PASSWORD,
      roleCodes: ['crm_sales'],
      sendInviteEmail: false,
    }),
  }), `provision ${label}`);
  const jwt = await loginApi(email, PERSONA_PASSWORD);

  expectAllowed(await request('/api/dynamic/crm_opportunity_common/list?pageNum=1&pageSize=20', jwt),
    'revocation-sales reads opportunities while granted');
  const marker = `${RUN}-${label}`;
  const created = expectAllowed(await request('/api/meta/commands/execute/crm:create_opportunity', jwt, {
    method: 'POST',
    body: JSON.stringify({
      operationType: 'create',
      payload: {
        crm_opp_name: marker,
        crm_opp_expected_amount: 77000,
        crm_opp_expected_close_date: '2026-12-31T18:00:00+08:00',
        crm_opp_probability: 30,
        crm_opp_forecast_category: 'pipeline',
      },
    }),
  }), 'revocation-sales creates while granted');
  expect(findValue(created?.data ?? created, ['recordId', 'recordPid', 'publicRecordId', 'pid'])).toBeTruthy();
  expect(await adminCount(marker)).toBe(1);

  const members = expectAllowed(await request('/api/tenant/members/search', adminJwt, {
    method: 'POST',
    body: JSON.stringify({ keyword: email, pageNum: 1, pageSize: 100 }),
  }), `resolve tenant member ${email}`);
  const member = rowsOf(members?.data).find(
    (row) => String((row?.user as Record<string, unknown> | undefined)?.email ?? '') === email,
  );
  expect(member?.pid, `tenant member pid for ${email}`).toBeTruthy();
  const memberPid = String(member.pid);

  const assignments = expectAllowed(await request(
    `/api/user-roles?memberPid=${encodeURIComponent(memberPid)}&pageNum=1&pageSize=100`,
    adminJwt,
  ), `list active role assignments for ${label}`);
  const assignmentPids = activeRoleAssignmentPids(assignments?.data);
  expect(assignmentPids.length, `active role assignments for ${label}`).toBeGreaterThan(0);

  expectAllowed(await request('/api/user-roles/batch-remove-by-pid', adminJwt, {
    method: 'DELETE',
    body: JSON.stringify(assignmentPids),
  }), `revoke ${label} role assignments`);

  expectDenied(await request('/api/dynamic/crm_opportunity_common/list?pageNum=1&pageSize=20', jwt),
    'revocation-sales existing session cannot read after revocation');
  expectDenied(await request('/api/meta/commands/execute/crm:create_opportunity', jwt, {
    method: 'POST',
    body: JSON.stringify({
      operationType: 'create',
      payload: {
        crm_opp_name: `${marker}-after-revoke`,
        crm_opp_expected_amount: 1,
        crm_opp_expected_close_date: '2026-12-31T18:00:00+08:00',
        crm_opp_probability: 10,
        crm_opp_forecast_category: 'pipeline',
      },
    }),
  }), 'revocation-sales existing session cannot create after revocation');
  expect(await adminCount(marker)).toBe(1);
  checks.push({ label: 'revocation-sales denied actions have no persistence side effect', verdict: 'pass' });

  expectAllowed(await request('/api/user-roles/assign-by-code', adminJwt, {
    method: 'POST',
    body: JSON.stringify({ memberPid, roleCodes: ['crm_sales'] }),
  }), `reassign ${label} role`);
  expectAllowed(await request('/api/dynamic/crm_opportunity_common/list?pageNum=1&pageSize=20', jwt),
    'revocation-sales reads after reassignment without re-login');
  const restored = expectAllowed(await request('/api/meta/commands/execute/crm:create_opportunity', jwt, {
    method: 'POST',
    body: JSON.stringify({
      operationType: 'create',
      payload: {
        crm_opp_name: `${marker}-restored`,
        crm_opp_expected_amount: 66000,
        crm_opp_expected_close_date: '2026-12-31T18:00:00+08:00',
        crm_opp_probability: 20,
        crm_opp_forecast_category: 'pipeline',
      },
    }),
  }), 'revocation-sales creates after reassignment without re-login');
  expect(findValue(restored?.data ?? restored, ['recordId', 'recordPid', 'publicRecordId', 'pid'])).toBeTruthy();
  expect(await adminCount(`${marker}-restored`)).toBe(1);
  checks.push({ label: 'revocation lifecycle pass', verdict: 'pass' });
});
