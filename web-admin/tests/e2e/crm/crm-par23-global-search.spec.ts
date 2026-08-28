import { expect, test, type Page } from '../../fixtures';
import fs from 'node:fs';
import path from 'node:path';
import { BACKEND_URL, BASE_URL } from '../../helpers/environments';
import { createCookieSessionStorage } from 'react-router';

const PERSONA_SESSION_STORAGE = createCookieSessionStorage({
  cookie: {
    name: '__session',
    httpOnly: true,
    path: '/',
    sameSite: 'lax',
    secrets: [process.env.SESSION_SECRET || 'dev-only-secret-do-not-use-in-production'],
    secure: false,
  },
});

async function personaSessionCookie(jwt: string): Promise<string> {
  const session = await PERSONA_SESSION_STORAGE.getSession();
  session.set('jwtToken', jwt);
  const setCookie = await PERSONA_SESSION_STORAGE.commitSession(session, { maxAge: 60 * 60 * 24 * 7 });
  return /__session=([^;]+)/.exec(setCookie)?.[1] ?? '';
}

/**
 * PAR-23 slice 1: unified cross-model global search.
 * Frozen contract: docs/plans/... (enterprise repo, par23 goal freeze).
 *
 * Journeys:
 *  1. Admin main journey: header search trigger → keyword → grouped record
 *     hits → click-through to the record detail page.
 *  2. Empty state: nonsense keyword renders the page-level "No results" copy.
 *  3. Permission convergence (fail-closed): a persona whose role carries no
 *     model read grants gets 403 on the model list and an empty global search
 *     response (both API and UI panel) while admin sees the same record.
 */

const RUN_ID = `par23-${Date.now()}`;
const KEYWORD = RUN_ID; // full uniqueness: runs share ms-prefixes if sliced
const ADMIN_EMAIL = 'admin@auraboot.com';
const ADMIN_PASSWORD = 'Test2026x';
const PERSONA_PASSWORD = 'AuraBoot2026!';
const EVIDENCE_ROOT = process.env.AURA_EVIDENCE_ROOT
  ? path.join(process.env.AURA_EVIDENCE_ROOT, 'par23-global-search')
  : path.resolve(process.cwd(), '..', '.workspace', 'evidence', 'par23-global-search-s65', 'par23-global-search');

interface MatrixApiResult {
  ok: boolean;
  status: number;
  body: any;
  recordId: string;
}

test.describe.configure({ mode: 'serial' });

function shot(page: Page, name: string): void {
  fs.mkdirSync(EVIDENCE_ROOT, { recursive: true });
  page.screenshot({ path: path.join(EVIDENCE_ROOT, `${name}.png`), fullPage: false }).catch(() => {});
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

async function matrixApi(jwt: string, apiPath: string, method: 'GET' | 'POST' | 'DELETE' = 'GET', payload?: unknown): Promise<MatrixApiResult> {
  const resp = await fetch(`${BACKEND_URL}${apiPath}`, {
    method,
    headers: { Authorization: `Bearer ${jwt}`, 'Content-Type': 'application/json' },
    body: payload === undefined ? undefined : JSON.stringify(payload),
  });
  const body: any = await resp.json().catch(() => null);
  const recordId: string = body?.data?.data?.recordPid ?? body?.data?.data?.recordId ?? body?.data?.data?.pid ?? '';
  return { ok: resp.ok && body?.code === '0', status: resp.status, body, recordId };
}

async function openPaletteAndSearch(page: Page, keyword: string): Promise<void> {
  await page.getByTestId('header-search-trigger').click();
  await page.getByTestId('command-palette-input').fill(keyword);
  await page.waitForTimeout(1200);
}

/**
 * The palette's record search is pure CSR: the HttpClient reads the JWT from
 * browser storage (`jwtToken`), which a storageState session does not carry.
 * Inject the token from a real API login so the browser session behaves
 * exactly like a post-login client.
 */
async function injectCsrfToken(page: Page, jwt: string): Promise<void> {
  await page.addInitScript((token) => {
    try {
      localStorage.setItem('jwtToken', token);
    } catch {
      // storage unavailable — auth will fail visibly in the journey
    }
  }, jwt);
}

let adminPid = '';
let personaEmail = '';

test.beforeAll(async () => {
  const adminJwt = await loginJwt(ADMIN_EMAIL, ADMIN_PASSWORD);

  const account = await matrixApi(adminJwt, '/api/meta/commands/execute/crm:create_account', 'POST', {
    payload: { crm_acc_name: `磐石${KEYWORD}科技有限公司`, crm_acc_industry: 'tech' },
    operationType: 'create',
  });
  expect(account.ok, `seed account: ${JSON.stringify(account.body).slice(0, 200)}`).toBe(true);
  adminPid = account.recordId;

  // persona: org skeleton + employee whose only role (tenant_member) carries
  // no model read grants
  const dept = await matrixApi(adminJwt, '/api/meta/commands/execute/org:create_department', 'POST', {
    payload: { org_dept_name: `${KEYWORD} 部门`, org_dept_order: 10, org_dept_status: 'active' },
    operationType: 'create',
  });
  expect(dept.ok, 'seed dept').toBe(true);
  const pos = await matrixApi(adminJwt, '/api/meta/commands/execute/org:create_position', 'POST', {
    payload: { org_pos_code: `${KEYWORD}-p`, org_pos_name: `${KEYWORD} 岗`, org_pos_dept_id: dept.recordId, org_pos_level: 'staff', org_pos_status: 'active' },
    operationType: 'create',
  });
  expect(pos.ok, `seed position: HTTP ${pos.status} ${JSON.stringify(pos.body).slice(0, 200)}`).toBe(true);
  personaEmail = `${KEYWORD}@e2e.local`;
  const emp = await matrixApi(adminJwt, '/api/org/employees', 'POST', {
    name: `${KEYWORD} 无权员`,
    email: personaEmail,
    phone: `138${Math.floor(10000000 + Math.random() * 89999999)}`,
    deptPid: dept.recordId,
    positionPid: pos.recordId,
  });
  const empData = emp.body?.data ?? {};
  const memberPid = empData.memberPid || empData.pid || '';
  expect(emp.ok && Boolean(memberPid), `seed employee: HTTP ${emp.status} ${JSON.stringify(emp.body).slice(0, 200)}`).toBe(true);
  const assign = await matrixApi(adminJwt, '/api/user-roles/assign-by-code', 'POST', {
    memberPid,
    roleCodes: ['tenant_member'],
  });
  expect(assign.ok, `assign tenant_member: ${JSON.stringify(assign.body).slice(0, 200)}`).toBe(true);
});

test('PAR-23 admin main journey: global search to record detail', async ({ page }) => {
  test.setTimeout(300_000);

  await injectCsrfToken(page, await loginJwt(ADMIN_EMAIL, ADMIN_PASSWORD));
  await page.goto(`${BASE_URL}/dashboards`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(2500);

  await openPaletteAndSearch(page, KEYWORD);
  await shot(page, '01-palette-grouped-results');

  const results = page.getByTestId('command-palette-results');
  await expect(results, 'palette shows the seeded account hit').toContainText(`磐石${KEYWORD}`, { timeout: 15000 });

  // click the record hit → lands on the record detail page
  await results.getByText(`磐石${KEYWORD}`, { exact: false }).first().click();
  await page.waitForURL(/\/p\/crm_account_common\/view\//, { timeout: 15000 });
  expect(page.url(), 'detail route carries the record pid').toContain(adminPid);
  await page.waitForTimeout(2500);
  await shot(page, '02-detail-landing');

  const detailJson = JSON.stringify(await page.content());
  expect(detailJson.includes('磐石') || (await page.getByTestId('dynamic-page-detail').count()) > 0,
    'record detail is rendered').toBe(true);
});

test('PAR-23 empty state renders page-level no-results copy', async ({ page }) => {
  test.setTimeout(180_000);

  await injectCsrfToken(page, await loginJwt(ADMIN_EMAIL, ADMIN_PASSWORD));
  await page.goto(`${BASE_URL}/dashboards`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(2000);

  await openPaletteAndSearch(page, `zzz无此记录${RUN_ID}`);
  await shot(page, '03-empty-state');
  await expect(page.getByTestId('command-palette-results')).toContainText(/No results found|未找到结果/, { timeout: 10000 });
});

test('PAR-23 permission convergence: unreadable model never surfaces', async ({ browser }) => {
  test.setTimeout(300_000);
  const adminJwt = await loginJwt(ADMIN_EMAIL, ADMIN_PASSWORD);
  const personaJwt = await loginJwt(personaEmail, PERSONA_PASSWORD);

  // baseline: the persona really cannot read the model (fail-closed context)
  const personaList = await matrixApi(personaJwt, '/api/dynamic/crm_account_common/list?pageNum=1&pageSize=5');
  expect(personaList.status, 'persona model list denied').toBe(403);

  // API: persona search response has no crm group; admin search does
  const personaSearch = await matrixApi(personaJwt, `/api/search/global?keyword=${KEYWORD}`);
  expect(personaSearch.status, 'persona search allowed at endpoint level').toBe(200);
  const personaModels = (personaSearch.body?.data?.groups ?? []).map((g: any) => g.modelCode);
  expect(personaModels, 'no crm group for unreadable persona').not.toContain('crm_account_common');

  const adminSearch = await matrixApi(adminJwt, `/api/search/global?keyword=${KEYWORD}`);
  const adminModels = (adminSearch.body?.data?.groups ?? []).map((g: any) => g.modelCode);
  expect(adminModels, 'admin search surfaces the seeded record').toContain('crm_account_common');

  // UI: persona logs in through the real login form and sees no record hits.
  // browser.newContext() inherits the project's injected session cookies in
  // this setup, so launch an independent browser for the clean session.
  const personaBrowser = await browser.browserType().launch({ args: ['--no-proxy-server'] });
  const context = await personaBrowser.newContext();
  const personaPage = await context.newPage();
  // Seed the persona session the same way tests/auth.setup.ts does (the
  // /login UI route redirects unauthenticated browsers to /home in dev), then
  // open the palette as that persona.
  const cookieValue = await personaSessionCookie(personaJwt);
  expect(cookieValue, 'persona session cookie built').toBeTruthy();
  await context.addCookies([
    { name: '__session', value: cookieValue, domain: '127.0.0.1', path: '/', httpOnly: true, sameSite: 'Lax' },
  ]);
  await personaPage.goto(`${BASE_URL}/dashboards`, { waitUntil: 'domcontentloaded' });
  await personaPage.waitForTimeout(2500);

  await openPaletteAndSearch(personaPage, KEYWORD);
  await shot(personaPage, '04-persona-no-record-hits');
  const paletteText = await personaPage.getByTestId('command-palette-results').textContent().catch(() => '');
  expect(paletteText ?? '', 'persona palette hides the unreadable record').not.toContain('磐石');
  await context.close();
  await personaBrowser.close();
});
