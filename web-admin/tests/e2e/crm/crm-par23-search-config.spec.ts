import { expect, test, type Page } from '../../fixtures';
import fs from 'node:fs';
import path from 'node:path';
import { BACKEND_URL, BASE_URL } from '../../helpers/environments';

/**
 * PAR-23 slice 2: personal global-search configuration.
 * Contract: enterprise docs/plans/2026-08/2026-08-31-par23-personal-search-config-slice2-goal-freeze.md
 *
 * The journey proves that a saved selection is ordered, intersects the live
 * readable set, changes Chromium palette results immediately, and survives a
 * fresh palette session. Model read permission remains the outer boundary.
 */

const RUN_ID = `par23cfg-${Date.now()}`;
const KEYWORD = RUN_ID;
const ADMIN_EMAIL = 'admin@auraboot.com';
const ADMIN_PASSWORD = 'Test2026x';
const ACCOUNT_MODEL = 'crm_account_common';
const CONTACT_MODEL = 'crm_contact_common';
const EVIDENCE_ROOT = process.env.AURA_EVIDENCE_ROOT
  ? path.join(process.env.AURA_EVIDENCE_ROOT, 'par23-search-config')
  : path.resolve(
      process.cwd(),
      '..',
      '.workspace',
      'evidence',
      'par23-search-config-s71',
      'par23-search-config',
    );

interface ApiResult {
  ok: boolean;
  status: number;
  body: any;
}

test.describe.configure({ mode: 'serial' });

function shot(page: Page, name: string): void {
  fs.mkdirSync(EVIDENCE_ROOT, { recursive: true });
  void page.screenshot({ path: path.join(EVIDENCE_ROOT, `${name}.png`), fullPage: false });
}

async function loginJwt(email: string, password: string): Promise<string> {
  const resp = await fetch(`${BACKEND_URL}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password }),
  });
  const body: any = await resp.json().catch(() => ({}));
  expect(resp.status === 200 && Boolean(body?.data?.jwt), `login ${email}`).toBe(true);
  return body.data.jwt;
}

async function api(
  jwt: string,
  apiPath: string,
  method: 'GET' | 'PUT' | 'POST' = 'GET',
  payload?: unknown,
): Promise<ApiResult> {
  const resp = await fetch(`${BACKEND_URL}${apiPath}`, {
    method,
    headers: { Authorization: `Bearer ${jwt}`, 'Content-Type': 'application/json' },
    body: payload === undefined ? undefined : JSON.stringify(payload),
  });
  const body: any = await resp.json().catch(() => null);
  return { ok: resp.ok && body?.code === '0', status: resp.status, body };
}

async function executeCommand(
  jwt: string,
  command: string,
  payload: Record<string, unknown>,
): Promise<string> {
  const result = await api(jwt, `/api/meta/commands/execute/${command}`, 'POST', {
    payload,
    operationType: 'create',
  });
  const pid = result.body?.data?.data?.recordPid ?? result.body?.data?.data?.pid ?? '';
  expect(
    result.ok && Boolean(pid),
    `${command}: ${JSON.stringify(result.body).slice(0, 240)}`,
  ).toBe(true);
  return pid;
}

async function candidates(jwt: string): Promise<any[]> {
  const result = await api(jwt, '/api/search/global/candidates');
  expect(result.ok, `candidates: ${JSON.stringify(result.body).slice(0, 240)}`).toBe(true);
  return result.body?.data?.models ?? [];
}

async function savePreference(jwt: string, codes: string[]): Promise<void> {
  const result = await api(jwt, '/api/search/global/preferences', 'PUT', { modelCodes: codes });
  expect(result.ok, `save preference: ${JSON.stringify(result.body).slice(0, 240)}`).toBe(true);
}

async function groupCodes(jwt: string): Promise<string[]> {
  const result = await api(jwt, `/api/search/global?keyword=${encodeURIComponent(KEYWORD)}`);
  expect(result.ok, `search: ${JSON.stringify(result.body).slice(0, 240)}`).toBe(true);
  return (result.body?.data?.groups ?? []).map((group: any) => group.modelCode);
}

async function injectJwt(page: Page, jwt: string): Promise<void> {
  await page.addInitScript((token) => {
    try {
      localStorage.setItem('jwtToken', token);
    } catch {
      // Auth failure will be visible in the journey.
    }
  }, jwt);
}

async function openSettings(page: Page): Promise<void> {
  await page.getByTestId('header-search-trigger').click();
  await page.getByTestId('command-palette-settings').click();
  await expect(page.getByTestId('command-palette-settings-panel')).toBeVisible({ timeout: 10000 });
}

let adminJwt = '';
let allCandidateCodes: string[] = [];

test.beforeAll(async () => {
  adminJwt = await loginJwt(ADMIN_EMAIL, ADMIN_PASSWORD);
  const accountPid = await executeCommand(adminJwt, 'crm:create_account', {
    crm_acc_name: `磐石${KEYWORD}科技有限公司`,
    crm_acc_industry: 'tech',
  });
  await executeCommand(adminJwt, 'crm:create_contact', {
    crm_ct_account_id: accountPid,
    crm_ct_name: `联系人${KEYWORD}`,
    crm_ct_email: `${KEYWORD}@example.com`,
  });
  allCandidateCodes = (await candidates(adminJwt)).map((model: any) => model.modelCode);
  expect(allCandidateCodes).toContain(ACCOUNT_MODEL);
  expect(allCandidateCodes).toContain(CONTACT_MODEL);
});

test.afterAll(async () => {
  if (adminJwt && allCandidateCodes.length > 0) {
    await savePreference(adminJwt, allCandidateCodes);
  }
});

test('PAR-23 config saves ordered selection and converges search candidates', async () => {
  test.setTimeout(180_000);

  await savePreference(adminJwt, [CONTACT_MODEL, ACCOUNT_MODEL]);
  const preference = await api(adminJwt, '/api/search/global/preferences');
  expect(preference.body?.data?.configured).toBe(true);
  expect(preference.body?.data?.enabledModelCodes).toEqual([CONTACT_MODEL, ACCOUNT_MODEL]);

  expect(await groupCodes(adminJwt)).toEqual([CONTACT_MODEL, ACCOUNT_MODEL]);

  await savePreference(adminJwt, [CONTACT_MODEL]);
  const disabled = await groupCodes(adminJwt);
  expect(disabled).toContain(CONTACT_MODEL);
  expect(disabled).not.toContain(ACCOUNT_MODEL);
});

test('PAR-23 palette settings persist selection and immediately affect results', async ({
  page,
}) => {
  test.setTimeout(300_000);

  await injectJwt(page, await loginJwt(ADMIN_EMAIL, ADMIN_PASSWORD));
  await page.goto(`${BASE_URL}/dashboards`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(2000);

  await openSettings(page);
  await expect(page.getByTestId(`search-model-${CONTACT_MODEL}`)).toContainText('联系人', {
    timeout: 10000,
  });
  await expect(page.getByTestId(`search-model-checkbox-${CONTACT_MODEL}`)).toBeChecked();
  await expect(page.getByTestId(`search-model-checkbox-${ACCOUNT_MODEL}`)).not.toBeChecked();
  await shot(page, '01-settings-readable-candidates');

  // Reopening the panel is a fresh CSR session and proves persisted selection.
  await page.getByTestId('command-palette-settings-close').click();
  await page.getByTestId('command-palette-settings').click();
  await expect(page.getByTestId(`search-model-checkbox-${ACCOUNT_MODEL}`)).not.toBeChecked();
  await shot(page, '02-config-persisted');

  await page.getByTestId('command-palette-settings-close').click();
  await page.getByTestId('command-palette-input').fill(KEYWORD);
  await page.waitForTimeout(1200);
  const palette = await page.getByTestId('command-palette-results').textContent();
  expect(palette ?? '').not.toContain(`磐石${KEYWORD}`);
  await shot(page, '03-disabled-model-absent');

  await page.getByTestId('command-palette-settings').click();
  await page.getByTestId(`search-model-checkbox-${ACCOUNT_MODEL}`).check();
  await page.getByTestId(`search-model-up-${ACCOUNT_MODEL}`).click();
  await page.getByTestId('command-palette-settings-save').click();
  await expect(page.getByTestId('command-palette-settings-message')).toContainText('saved');

  const preference = await api(adminJwt, '/api/search/global/preferences');
  expect(preference.body?.data?.enabledModelCodes).toEqual([ACCOUNT_MODEL, CONTACT_MODEL]);
  expect(await groupCodes(adminJwt)).toEqual([ACCOUNT_MODEL, CONTACT_MODEL]);
  await shot(page, '04-enabled-model-restored');
});
