import { test, expect, type Browser, type Page } from '@playwright/test';
import { createCookieSessionStorage } from 'react-router';
import { uniqueId, ensureSidebarExpanded } from '../helpers';

/**
 * X03-04 team data-scope runtime (browser leg).
 *
 * Owner-approved minimal口径 (quote/bom fresh-runtime handover §4.4, OSS #2102):
 * TEAM = explicit member group layered on the DEPT system; team scope makes
 * records associated with the caller's teams visible; no cross-team implicit
 * merged views; removing a member takes effect immediately (cache eviction).
 *
 * Fixture is self-contained (the quoteops stack does not import the
 * test-fixtures plugin): a dedicated published dynamic model declaring
 * extension.dataScope.teamField, two teams, two provisioned users, and records
 * created by the admin on behalf of each team.
 */
const PASSWORD = 'Test2026x';
const MODEL_CODE = 'e2et_team_note';
const TABLE_NAME = 'e2et_team_note';
const TEAM_FIELD = 'e2et_team_pid';
const TITLE_FIELD = 'e2et_note_title';
const DEFAULT_BASE_URL = 'http://127.0.0.1:5173';
const JWT_TOKEN_KEY = 'jwtToken';

const authSessionStorage = createCookieSessionStorage({
  cookie: {
    name: '__session',
    httpOnly: true,
    path: '/',
    sameSite: 'lax',
    secrets: [process.env.SESSION_SECRET || 'dev-only-secret-do-not-use-in-production'],
    secure: process.env.NODE_ENV === 'production',
  },
});

type TestUser = {
  email: string;
  displayName: string;
  password: string;
};

type TenantSpace = {
  id?: string | number;
  tenantId?: string | number;
  type?: string;
  spaceType?: string;
};

test.describe('Team data scope runtime (X03-04)', () => {
  test.setTimeout(120_000);

  test('team member sees own-team records; non-member sees none; removal hides immediately', async ({
    page,
    browser,
    baseURL,
  }, info) => {
    const resolvedBaseURL = baseURL ?? DEFAULT_BASE_URL;
    const uid = uniqueId('team_scope');
    const roleCode = `e2e_team_${uid.replace(/[^a-zA-Z0-9_]/g, '_')}`.slice(0, 60);

    // The rbac profile's project block carries no Referer header; cookie-
    // authenticated writes must still originate from this application.
    await page.context().setExtraHTTPHeaders({ Referer: `${resolvedBaseURL}/` });

    await ensureTeamScopeModel(page);

    // Two teams per run; records are tagged with their ab_team pid.
    const teamOnePid = await createTeam(page, `Team One ${uid}`);
    const teamTwoPid = await createTeam(page, `Team Two ${uid}`);
    expect(teamOnePid).toBeTruthy();
    expect(teamTwoPid).toBeTruthy();

    const recordOne = `R1 ${uid}`;
    const recordTwo = `R2 ${uid}`;
    await createTeamRecord(page, recordOne, teamOnePid);
    await createTeamRecord(page, recordTwo, teamTwoPid);

    const rolePid = await createTeamScopedRole(page, roleCode);

    // Grant only the declared query entry capability; preserve fixture model grants and TEAM scope.
    await page.goto('/home');
    await ensureSidebarExpanded(page);
    await page.getByTestId('sidebar').locator('a[href="/enterprise/permissions"]').click();
    await page.getByTestId('role-search-input').fill(roleCode);
    await page.getByTestId(`role-item-${roleCode}`).click();
    await expect(page.getByTestId('capability-role-editor')).toHaveAttribute('data-role-pid', rolePid);
    await page.getByTestId('capability-checkbox-sys.cap.query_builder').check();
    await page.getByTestId('capability-save').click();
    await expect(page.getByTestId('confirm-dialog')).toBeVisible();
    const capabilitiesSaved = page.waitForResponse(response =>
      response.request().method() === 'PUT' && response.url().includes('/api/permission/capabilities?'));
    await page.getByTestId('confirm-ok').click();
    const saved = await capabilitiesSaved;
    expect(saved.status()).toBe(200);
    expect(String((await saved.json()).code)).toBe('0');
    expect(saved.request().postDataJSON()).toEqual(['sys.cap.query_builder']);
    await expect(page.getByTestId('capability-save')).toBeDisabled();

    const member: TestUser = {
      email: `${roleCode}_a@e2e.local`,
      displayName: `Team Member ${uid}`,
      password: PASSWORD,
    };
    const outsider: TestUser = {
      email: `${roleCode}_b@e2e.local`,
      displayName: `Team Outsider ${uid}`,
      password: PASSWORD,
    };
    await provisionUser(page, member, roleCode);
    await provisionUser(page, outsider, roleCode);

    // Member joins Team One only; outsider joins nothing (SELF fallback).
    await addTeamMember(page, teamOnePid, member.email);

    const memberContext = await newAuthenticatedContext(browser, resolvedBaseURL, member);
    const outsiderContext = await newAuthenticatedContext(browser, resolvedBaseURL, outsider);
    const memberPage = await memberContext.newPage();
    const outsiderPage = await outsiderContext.newPage();

    try {
      const queryEvidence: Array<Record<string, unknown>> = [];
      async function expectScopedQuery(actor: Page, label: string, expectedTitles: string[]) {
        const filters = [{ fieldName: TITLE_FIELD, operator: 'IN', value: [recordOne, recordTwo] }];
        async function execute(extra: Record<string, unknown>) {
          const payload = { modelCode: MODEL_CODE, filters, ...extra };
          const response = await actor.request.post('/api/query-builder/execute', { data: payload });
          const body = await response.json();
          queryEvidence.push({ label, payload, status: response.status(), body });
          expect(response.status(), `${label} query must execute with entry and model read`).toBe(200);
          expect(String(body.code)).toBe('0');
          expect(Array.isArray(body.data)).toBe(true);
          return body.data as Array<Record<string, unknown>>;
        }
        const rows = await execute({ fields: [TITLE_FIELD, TEAM_FIELD], sortField: TITLE_FIELD, sortOrder: 'ASC' });
        expect(rows.map(row => row[TITLE_FIELD]).sort()).toEqual([...expectedTitles].sort());
        const counts = await execute({ aggregations: [{ fieldCode: TITLE_FIELD, function: 'COUNT', alias: 'record_count' }] });
        expect(counts).toHaveLength(1);
        expect(Number(counts[0].record_count)).toBe(expectedTitles.length);
        const groups = await execute({ groupBy: [TEAM_FIELD],
          aggregations: [{ fieldCode: TITLE_FIELD, function: 'COUNT', alias: 'record_count' }] });
        const expectedTeams = expectedTitles.map(title => title === recordOne ? teamOnePid : teamTwoPid).sort();
        expect(groups.map(row => row[TEAM_FIELD]).sort()).toEqual(expectedTeams);
        for (const group of groups) expect(Number(group.record_count)).toBe(1);
      }

      await expectScopedQuery(memberPage, 'member-before-removal', [recordOne]);
      await expectScopedQuery(outsiderPage, 'outsider', []);
      await expectScopedQuery(page, 'tenant-admin', [recordOne, recordTwo]);
      // Query entry access must not grant read access to an unrelated model.
      for (const endpoint of ['/api/query-builder/execute', '/api/query-builder/models/api_connector/fields']) {
        const response = endpoint.endsWith('/execute')
          ? await memberPage.request.post(endpoint, { data: { modelCode: 'api_connector', fields: ['name'] } })
          : await memberPage.request.get(endpoint);
        const body = await response.json();
        queryEvidence.push({ label: 'unrelated-model-denied', endpoint, status: response.status(), body });
        expect(response.status()).toBe(403);
        expect(String(body.code)).toBe('403');
        expect(body.context).toBe('Query builder model read access denied');
      }

      // API legs first: deterministic row visibility through the scoped list query.
      await expectListRows(memberPage, [recordOne], [recordTwo]);
      await expectListRows(outsiderPage, [], [recordOne, recordTwo]);

      // Browser leg: the dynamic list page renders the same scope verdict.
      await openDynamicList(memberPage, resolvedBaseURL);
      await expectRowVisible(memberPage, recordOne);
      await expectRowAbsent(memberPage, recordTwo);
      await memberPage.screenshot({ path: 'test-results/x03-member-sees-own-team.png', fullPage: true });

      await openDynamicList(outsiderPage, resolvedBaseURL);
      await expectRowAbsent(outsiderPage, recordOne);
      await expectRowAbsent(outsiderPage, recordTwo);
      await outsiderPage.screenshot({ path: 'test-results/x03-outsider-sees-none.png', fullPage: true });

      // Removing the member from the team must hide the rows immediately —
      // the team-member service evicts the dataScopeCondition cache (#2102).
      await removeTeamMember(page, teamOnePid, member.email);
      await expectListRows(memberPage, [], [recordOne, recordTwo]);
      await expectScopedQuery(memberPage, 'same-session-after-removal', []);
      await info.attach('query-builder-team-scope', {
        body: JSON.stringify({ rolePid, modelCode: MODEL_CODE, teamOnePid, teamTwoPid, queryEvidence }),
        contentType: 'application/json',
      });

      await openDynamicList(memberPage, resolvedBaseURL);
      await expectRowAbsent(memberPage, recordOne);
      await memberPage.screenshot({ path: 'test-results/x03-removed-member-sees-none.png', fullPage: true });
    } finally {
      await memberContext.close();
      await outsiderContext.close();
    }
  });
});

async function expectOk(resp: { ok: () => boolean; status: () => number }, what: string) {
  expect(resp.ok(), `${what} -> HTTP ${resp.status()}`).toBe(true);
}

async function ensureTeamScopeModel(page: Page): Promise<void> {
  // Idempotent: a fixed-code model is created once per runtime and reused.
  const createResp = await page.request.post('/api/meta/models', {
    data: {
      code: MODEL_CODE,
      displayName: 'E2E Team Note',
      tableName: TABLE_NAME,
      description: 'X03-04 team data-scope fixture model',
    },
  });
  if (!createResp.ok()) {
    const body = await createResp.json().catch(() => ({}));
    const message = String(body?.message ?? '');
    expect(
      message.includes('已存在') || message.includes('exist'),
      `create model -> HTTP ${createResp.status()}: ${message}`,
    ).toBe(true);
  }
  const modelResp = await page.request.get(`/api/meta/models/code/${MODEL_CODE}`);
  await expectOk(modelResp, 'fetch fixture model');
  const modelBody = await modelResp.json();
  const modelPid = String(modelBody?.data?.pid ?? '');
  expect(modelPid, 'fixture model pid').toBeTruthy();

  for (const field of [
    { code: TITLE_FIELD, dataType: 'string' },
    { code: TEAM_FIELD, dataType: 'string' },
  ]) {
    const fieldResp = await page.request.post('/api/meta/fields', {
      data: { ...field, modelPid, autoPublish: false },
    });
    if (!fieldResp.ok()) {
      const body = await fieldResp.json().catch(() => ({}));
      const message = String(body?.message ?? '');
      expect(
        message.includes('已存在') || message.includes('exist'),
        `create field ${field.code} -> HTTP ${fieldResp.status()}: ${message}`,
      ).toBe(true);
    }
  }

  // The team-scope contract is opt-in per model: the model must declare
  // extension.dataScope.teamField or the scope fails closed.
  const updateResp = await page.request.put(`/api/meta/models/${modelPid}`, {
    data: {
      displayName: 'E2E Team Note',
      extension: { dataScope: { teamField: TEAM_FIELD } },
    },
  });
  await expectOk(updateResp, 'declare model teamField');

  // Publish is idempotent: a previous run may have published already, and
  // re-publishing a published model is rejected as a business error.
  const currentStatus = String(modelBody?.data?.status ?? '');
  if (!currentStatus.toLowerCase().includes('publish')) {
    const publishResp = await page.request.post(`/api/meta/models/${modelPid}/publish`);
    await expectOk(publishResp, 'publish fixture model');
  }

  // Publishing auto-creates a content-less stub list page that renders the
  // "尚未配置内容" placeholder instead of querying records — fill it with a
  // minimal list DSL (mirrors auth.setup's "ensure e2et test pages dsl").
  const listPageKey = `${MODEL_CODE}_list`;
  const lookup = await page.request.get(`/api/pages/key/${listPageKey}`);
  await expectOk(lookup, 'look up auto-created list page');
  const cur = (await lookup.json())?.data;
  const blocks = Array.isArray(cur?.blocks) ? cur.blocks : [];
  const isStub =
    blocks.length === 0 ||
    blocks.every((b: any) => !b?.id && !b?.columns && !b?.buttons && !b?.fields && !b?.tabs);
  if (isStub) {
    const dslResp = await page.request.put(`/api/pages/${cur.pid}`, {
      data: {
        pageKey: listPageKey,
        modelCode: MODEL_CODE,
        kind: 'list',
        name: listPageKey,
        layout: { type: 'stack' },
        // The stub marker lives in page extension.auto_created — the renderer
        // shows the placeholder while it is set, regardless of blocks.
        extension: { ...((cur?.extension as Record<string, any>) ?? {}), auto_created: false },
        blocks: [
          {
            id: 'block_team_note_toolbar',
            blockType: 'toolbar',
            buttons: [
              {
                code: 'create',
                primary: true,
                icon: 'Plus',
                permissionCode: `model.${MODEL_CODE}.create`,
              },
            ],
          },
          {
            id: 'block_team_note_table',
            blockType: 'table',
            columns: [
              { field: TITLE_FIELD, width: 240 },
              { field: TEAM_FIELD, width: 240 },
            ],
          },
        ],
      },
    });
    await expectOk(dslResp, 'fill stub list page DSL');
  }
}

async function createTeam(page: Page, name: string): Promise<string> {
  const code = `team_${name.replace(/[^a-zA-Z0-9]/g, '_').slice(0, 40)}`.toLowerCase();
  const resp = await page.request.post('/api/org/teams', {
    data: { code, name },
  });
  await expectOk(resp, `create team ${name}`);
  const body = await resp.json();
  const pid = String(body?.data?.pid ?? body?.data?.teamPid ?? '');
  expect(pid, 'team pid').toBeTruthy();
  return pid;
}

async function addTeamMember(page: Page, teamPid: string, userEmail: string): Promise<void> {
  const resp = await page.request.post(`/api/org/teams/${teamPid}/members`, {
    data: { userPid: await userPidByEmail(page, userEmail) },
  });
  await expectOk(resp, `add member ${userEmail} to ${teamPid}`);
}

async function removeTeamMember(page: Page, teamPid: string, userEmail: string): Promise<void> {
  const userPid = await userPidByEmail(page, userEmail);
  const listResp = await page.request.get(`/api/org/teams/${teamPid}/members`);
  await expectOk(listResp, 'list team members');
  const listBody = await listResp.json();
  const members = Array.isArray(listBody?.data) ? listBody.data : [];
  const found = members.find((m: any) => String(m?.userPid ?? m?.pid ?? '') === userPid);
  expect(found, `member ${userEmail} currently in team`).toBeTruthy();
  const memberPid = String(found?.pid ?? '');
  const resp = await page.request.delete(`/api/org/teams/${teamPid}/members/${memberPid}`);
  await expectOk(resp, `remove member ${userEmail} from ${teamPid}`);
}

async function userPidByEmail(page: Page, email: string): Promise<string> {
  const resp = await page.request.get(
    `/api/admin/users/search?keyword=${encodeURIComponent(email)}`,
  );
  await expectOk(resp, `search user ${email}`);
  const body = await resp.json();
  const list = Array.isArray(body?.data) ? body.data : [];
  const found = list.find((u: any) => String(u?.email ?? '') === email);
  expect(found, `user ${email} found`).toBeTruthy();
  return String(found?.pid ?? '');
}

async function createTeamRecord(page: Page, title: string, teamPid: string): Promise<void> {
  const resp = await page.request.post(`/api/dynamic/${MODEL_CODE}`, {
    data: {
      [TITLE_FIELD]: title,
      [TEAM_FIELD]: teamPid,
    },
  });
  await expectOk(resp, `create record ${title}`);
}

async function listRecordTitles(page: Page): Promise<string[]> {
  const resp = await page.request.get(`/api/dynamic/${MODEL_CODE}/list?pageNum=1&pageSize=100`);
  await expectOk(resp, 'list fixture records');
  const body = await resp.json();
  const records = body?.data?.records ?? body?.data?.list ?? [];
  return (Array.isArray(records) ? records : []).map((r: any) => String(r?.[TITLE_FIELD] ?? ''));
}

async function expectListRows(page: Page, visible: string[], absent: string[]): Promise<void> {
  const titles = await listRecordTitles(page);
  for (const t of visible) {
    expect(titles, `expected visible: ${t}`).toContain(t);
  }
  for (const t of absent) {
    expect(titles, `expected hidden: ${t}`).not.toContain(t);
  }
}

async function createTeamScopedRole(page: Page, roleCode: string): Promise<string> {
  const createResp = await page.request.post('/api/roles', {
    data: {
      code: roleCode,
      name: `Team Scope ${roleCode.slice(-16)}`,
      description: 'X03-04 team scope fixture role',
      type: 'custom',
      status: 'active',
      scopeType: 'tenant',
      defaultDataScopeType: 'team',
    },
  });
  await expectOk(createResp, 'create role');
  const createBody = await createResp.json();
  const rolePid = String(createBody?.data?.pid ?? '');
  expect(rolePid, 'role pid').toBeTruthy();

  // Find the model read/create permission rows for the fixture model.
  const modelPermResp = await page.request.get(`/api/permissions/model/${MODEL_CODE}`);
  await expectOk(modelPermResp, 'list model permissions');
  const modelPermBody = await modelPermResp.json();
  const permissions = Array.isArray(modelPermBody?.data) ? modelPermBody.data : [];
  const permissionIds = permissions
    .filter((p: any) =>
      [`model.${MODEL_CODE}.read`, `model.${MODEL_CODE}.create`].includes(String(p?.code ?? '')),
    )
    .map((p: any) => Number(p.id));
  expect(permissionIds.length, 'model read+create permission ids').toBeGreaterThanOrEqual(1);

  const batchResp = await page.request.put(`/api/permissions/matrix/${rolePid}/batch`, {
    data: permissionIds.map((id: number) => ({ permissionId: id, granted: true })),
  });
  await expectOk(batchResp, 'grant role permissions');

  const scopeResp = await page.request.put(`/api/permissions/matrix/${rolePid}/default-scope`, {
    data: { scopeType: 'team' },
  });
  await expectOk(scopeResp, 'set role default team scope');

  return rolePid;
}

async function provisionUser(page: Page, user: TestUser, roleCode: string): Promise<void> {
  const resp = await page.request.post('/api/admin/users', {
    data: {
      email: user.email,
      displayName: user.displayName,
      initialPassword: user.password,
      roleCodes: [roleCode],
      sendInviteEmail: false,
    },
  });
  await expectOk(resp, `provision user ${user.email}`);
}

async function newAuthenticatedContext(browser: Browser, baseURL: string, user: TestUser) {
  const loginContext = await browser.newContext({ baseURL, storageState: { cookies: [], origins: [] } });
  const loginPage = await loginContext.newPage();
  let jwt: string;
  try {
    jwt = await loginAndResolveJwt(loginPage, baseURL, user);
  } finally {
    await loginContext.close();
  }

  const cookieValue = await createSessionCookieValue(jwt);
  expect(cookieValue, `session cookie for ${user.email}`).toBeTruthy();

  const context = await browser.newContext({ baseURL });
  await context.addCookies([
    {
      name: '__session',
      value: cookieValue!,
      httpOnly: true,
      sameSite: 'Lax' as const,
      expires: Math.floor(Date.now() / 1000) + 604800,
      url: baseURL,
    },
  ]);
  return context;
}

async function loginAndResolveJwt(page: Page, baseURL: string, user: TestUser): Promise<string> {
  const loginResp = await page.request.post(`${baseURL}/api/auth/login`, {
    data: { email: user.email, password: user.password },
    headers: { 'Content-Type': 'application/json' },
  });
  await expectOk(loginResp, `login ${user.email}`);
  const loginBody = await loginResp.json();
  const loginJwt = loginBody?.data?.jwt;
  expect(typeof loginJwt === 'string' && loginJwt.length > 0, JSON.stringify(loginBody)).toBe(true);
  if (loginBody?.data?.tenantId) return loginJwt;

  const spacesResp = await page.request.get(`${baseURL}/api/tenant-selection/my-spaces`, {
    headers: { Authorization: `Bearer ${loginJwt}` },
  });
  if (!spacesResp.ok()) return loginJwt;
  const spacesBody = await spacesResp.json().catch(() => ({}));
  const spaces: TenantSpace[] = Array.isArray(spacesBody?.data) ? spacesBody.data : [];
  const selectedSpace =
    spaces.find((space) => String(space?.spaceType ?? space?.type ?? '').toLowerCase() === 'business') ??
    spaces.find((space) => space?.tenantId ?? space?.id);
  const tenantId = selectedSpace?.tenantId ?? selectedSpace?.id;
  if (!tenantId) return loginJwt;

  const selectResp = await page.request.post(`${baseURL}/api/tenant-selection/process`, {
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${loginJwt}` },
    data: { action: 'select', tenantId },
  });
  if (!selectResp.ok()) return loginJwt;
  const selectBody = await selectResp.json().catch(() => ({}));
  return String(selectBody?.data?.jwt || loginJwt);
}

async function createSessionCookieValue(jwt: string | null): Promise<string | null> {
  const session = await authSessionStorage.getSession();
  session.set(JWT_TOKEN_KEY, jwt);
  const setCookie = await authSessionStorage.commitSession(session, { maxAge: 60 * 60 * 24 * 7 });
  const match = setCookie.match(/__session=([^;]+)/);
  return match?.[1] ?? null;
}

async function openDynamicList(page: Page, baseURL: string): Promise<void> {
  // /p/{model_code} — the URL segment is the model code; the loader derives
  // the stub list page key ({model_code}_list) that publishing auto-created.
  await page.goto(`/p/${MODEL_CODE}`, { waitUntil: 'domcontentloaded' });
  await expect(page.locator('body')).not.toContainText('raw code', { ignoreCase: true });
  // The scoped list call is the render-completion signal (SPA: no networkidle).
  await page.waitForResponse(
    (resp) => resp.url().includes(`/api/dynamic/${MODEL_CODE}/list`) && resp.ok(),
    { timeout: 20_000 },
  );
}

async function expectRowVisible(page: Page, title: string): Promise<void> {
  await expect(page.getByText(title).first()).toBeVisible({ timeout: 15_000 });
}

async function expectRowAbsent(page: Page, title: string): Promise<void> {
  await expect(page.getByText(title).first()).toBeHidden({ timeout: 15_000 });
}
