import {
  test,
  expect,
  type APIResponse,
  type Browser,
  type BrowserContext,
  type Page,
} from '@playwright/test';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';
import { loginViaUI } from '../../helpers/wd-fixtures';

const RUNTIME_ROUTE = '/production-exception-list-v4';
const DEFAULT_PASSWORD = DEFAULT_TEST_ACCOUNT.password;

const PERSONAS = {
  secondAdmin: {
    roleCode: 'e2e_contextual_authoring_second_admin',
    roleName: 'Contextual authoring second admin',
    email: 'e2e-contextual-authoring-second-admin@test.com',
    permissions: [
      'model.production_exception.read',
      'page.page.read',
      'meta.designer.read',
      'meta.designer.update',
      'meta.designer.admin',
    ],
  },
  reviewer: {
    roleCode: 'e2e_contextual_authoring_reviewer',
    roleName: 'Contextual authoring reviewer',
    email: 'e2e-contextual-authoring-reviewer@test.com',
    permissions: ['page.page.read', 'meta.publish.read', 'meta.publish.update'],
  },
  author: {
    roleCode: 'e2e_contextual_authoring_author',
    roleName: 'Contextual authoring author',
    email: 'e2e-contextual-authoring-author@test.com',
    permissions: [
      'model.production_exception.read',
      'page.page.read',
      'meta.designer.read',
      'meta.designer.update',
    ],
  },
} as const;

type Persona = (typeof PERSONAS)[keyof typeof PERSONAS];

type ApiEnvelope<T> = {
  code?: number | string;
  data?: T;
  message?: string;
};

type PermissionRecord = {
  pid: string;
  code: string;
};

type RoleRecord = {
  pid: string;
  code: string;
};

type WriterLease = {
  status: 'OWNED' | 'HELD_BY_OTHER' | 'HELD_BY_OTHER_SESSION' | 'EXPIRED';
  revision: number;
};

type AuthoringSession = {
  sessionPid: string;
  changeSetPid: string;
  state: string;
  workspaceMode: 'AUTHORING' | 'OBSERVER' | 'REVIEW';
  revision: number;
  riskLevel: string;
  publishPolicy: string;
  validationState: string;
  impactState: string;
  changeSetStatus: string;
  approvalState: string;
  publishState: string;
  snapshot: Record<string, unknown>;
  writerLease?: WriterLease;
};

type CapabilityRegistry = {
  manifests: Array<{ blockType: string; checksum: string }>;
};

type PatchResult = {
  session: AuthoringSession;
};

type ReviewWorkspace = {
  session: AuthoringSession;
  capabilities: CapabilityRegistry;
};

type OpenedActor = {
  context: BrowserContext;
  page: Page;
  close: () => Promise<void>;
};

test.use({ storageState: { cookies: [], origins: [] } });

test.describe('Contextual authoring PC collaboration golden', () => {
  test.describe.configure({ mode: 'serial', timeout: 180_000 });

  test.beforeEach(async ({ page }) => {
    await login(page, DEFAULT_TEST_ACCOUNT.email);
  });

  test('PC-AUTH-005 @critical — second admin observes read-only and takes over the single writer lease', async ({
    page,
    browser,
  }) => {
    await ensurePersona(page, PERSONAS.secondAdmin);
    await page.setViewportSize({ width: 1440, height: 900 });
    const ownerSession = await enterAuthoringFromRuntime(page);
    const tablePatch = await buildTablePatch(page, ownerSession, '/props/density', 'compact');

    const secondAdmin = await openAsPersona(browser, PERSONAS.secondAdmin);
    try {
      await secondAdmin.page.setViewportSize({ width: 1440, height: 900 });
      const observerResponse = secondAdmin.page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          apiPath(response.url()) ===
            `/api/authoring/change-sets/${ownerSession.changeSetPid}/sessions`,
      );
      await secondAdmin.page.goto(
        `/unified-designer?changeSetId=${encodeURIComponent(ownerSession.changeSetPid)}`,
        { waitUntil: 'domcontentloaded' },
      );
      const observer = await expectApiData<AuthoringSession>(
        await observerResponse,
        'open second-admin observer workspace',
      );

      expect(observer.workspaceMode).toBe('OBSERVER');
      expect(observer.state).toBe('READ_ONLY');
      expect(observer.writerLease?.status).toBe('HELD_BY_OTHER');
      await expect(secondAdmin.page).toHaveURL(/authoringSession=/);
      await expect(secondAdmin.page.getByTestId('unified-designer-workbench')).toBeVisible();
      await expect(secondAdmin.page.getByTestId('authoring-writer-lease-notice')).toContainText(
        '另一位管理员正在编辑此 ChangeSet',
      );
      await expect(secondAdmin.page.getByTestId('designer-save')).toBeDisabled();

      await secondAdmin.page
        .getByPlaceholder('填写接管原因（必填，将写入审计）')
        .fill('PC 门禁：经值班负责人确认接管');
      const takeoverResponse = secondAdmin.page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          apiPath(response.url()) ===
            `/api/authoring/sessions/${observer.sessionPid}/writer-lease/takeover`,
      );
      await secondAdmin.page.getByTestId('authoring-writer-lease-takeover').click();
      const taken = await expectApiData<AuthoringSession>(
        await takeoverResponse,
        'take over writer lease',
      );
      expect(taken.state).toBe('ACTIVE');
      expect(taken.writerLease?.status).toBe('OWNED');
      await expect(secondAdmin.page.getByTestId('authoring-writer-lease-notice')).toHaveCount(0);

      await expect(page.getByTestId('authoring-writer-lease-notice')).toContainText(
        '另一位管理员正在编辑此 ChangeSet',
        { timeout: 20_000 },
      );
      await expect(page.getByTestId('contextual-authoring-surface')).toHaveAttribute(
        'data-read-only',
        'true',
      );

      const rejectedWrite = await page.request.patch(
        `/api/authoring/sessions/${ownerSession.sessionPid}/patches`,
        { data: { expectedRevision: ownerSession.revision, ...tablePatch } },
      );
      expect(rejectedWrite.status(), '失去 writer lease 的原 owner 不能继续写入').toBe(409);
      const rejectedBody = await rejectedWrite.text();
      expect(rejectedBody).toContain('authoring.writer-lease.lost');

      const unchanged = await expectApiData<AuthoringSession>(
        await page.request.get(`/api/authoring/sessions/${ownerSession.sessionPid}`),
        'reload former owner session',
      );
      expect(unchanged.revision).toBe(ownerSession.revision);
      expect(unchanged.writerLease?.status).toBe('HELD_BY_OTHER');
    } finally {
      await secondAdmin.close();
    }
  });

  test('PC-AUTH-006 @critical — independent reviewer approves the frozen revision but cannot publish', async ({
    page,
    browser,
  }) => {
    await ensurePersona(page, PERSONAS.reviewer);
    const ownerSession = await enterAuthoringFromRuntime(page);
    const l2Patch = await buildTablePatch(
      page,
      ownerSession,
      '/props/defaultFilter',
      { status: 'OPEN' },
    );
    const patched = await expectApiData<PatchResult>(
      await page.request.patch(`/api/authoring/sessions/${ownerSession.sessionPid}/patches`, {
        data: { expectedRevision: ownerSession.revision, ...l2Patch },
      }),
      'apply L2 review-required patch',
    );
    expect(patched.session.riskLevel).toBe('L2');
    expect(patched.session.publishPolicy).toBe('REQUIRED_REVIEW');

    const prepared = await expectApiData<AuthoringSession>(
      await page.request.post(`/api/authoring/sessions/${ownerSession.sessionPid}/prepare`, {
        data: { expectedRevision: patched.session.revision },
      }),
      'prepare exact revision',
    );
    expect(prepared.validationState).toBe('VALID');
    expect(prepared.impactState).toBe('KNOWN');
    await expectApiSuccess(
      await page.request.post(`/api/authoring/sessions/${ownerSession.sessionPid}/submit`, {
        data: { expectedRevision: prepared.revision },
      }),
      'submit exact revision for review',
    );

    const reviewer = await openAsPersona(browser, PERSONAS.reviewer);
    try {
      await reviewer.page.setViewportSize({ width: 1440, height: 900 });
      const reviewWorkspaceResponse = reviewer.page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          apiPath(response.url()) ===
            `/api/authoring/change-sets/${ownerSession.changeSetPid}/review-workspaces`,
      );
      await reviewer.page.goto(
        `/unified-designer?reviewChangeSetId=${encodeURIComponent(ownerSession.changeSetPid)}`,
        { waitUntil: 'domcontentloaded' },
      );
      const workspace = await expectApiData<ReviewWorkspace>(
        await reviewWorkspaceResponse,
        'open bounded review workspace',
      );

      expect(workspace.session.workspaceMode).toBe('REVIEW');
      expect(workspace.session.state).toBe('READ_ONLY');
      expect(workspace.session.changeSetStatus).toBe('IN_REVIEW');
      expect(workspace.session.revision).toBe(prepared.revision);
      await expect(reviewer.page).toHaveURL(/reviewSession=/);
      await expect(reviewer.page.getByTestId('unified-designer-workbench')).toBeVisible();
      await expect(reviewer.page.getByTestId('designer-save')).toBeDisabled();
      await expect(reviewer.page.getByTestId('authoring-writer-lease-notice')).toHaveCount(0);
      await expect(reviewer.page.getByTestId('authoring-governance-notice')).toContainText(
        `revision r${prepared.revision} 已冻结`,
      );
      await expect(reviewer.page.getByTestId('authoring-governance-approve')).toBeVisible();
      await expect(reviewer.page.getByTestId('authoring-governance-publish')).toHaveCount(0);

      await reviewer.page
        .getByTestId('authoring-governance-reason')
        .fill('PC 门禁：冻结 revision 的独立审核通过');
      const approveResponse = reviewer.page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          apiPath(response.url()) ===
            `/api/authoring/change-sets/${ownerSession.changeSetPid}/approve`,
      );
      await reviewer.page.getByTestId('authoring-governance-approve').click();
      await expectApiSuccess(await approveResponse, 'approve exact frozen revision');
      await expect(reviewer.page.getByTestId('authoring-governance-notice')).toContainText(
        `revision r${prepared.revision} 已批准`,
      );
      await expect(reviewer.page.getByTestId('authoring-governance-publish')).toHaveCount(0);

      const forbiddenPublish = await reviewer.page.request.post(
        `/api/authoring/change-sets/${ownerSession.changeSetPid}/publish`,
        { data: { expectedRevision: prepared.revision } },
      );
      expect(forbiddenPublish.status(), 'reviewer 不得越权发布').toBe(403);

      const approved = await expectApiData<AuthoringSession>(
        await page.request.get(`/api/authoring/sessions/${ownerSession.sessionPid}`),
        'reload approved owner session',
      );
      expect(approved.changeSetStatus).toBe('APPROVED');
      expect(approved.approvalState).toBe('APPROVED');
      expect(approved.publishState).toBe('READY');
      expect(approved.revision).toBe(prepared.revision);
    } finally {
      await reviewer.close();
    }
  });

  test('PC-AUTH-007 @critical — revoking designer update turns a live session read-only and blocks the backend', async ({
    page,
    browser,
  }) => {
    const role = await ensurePersona(page, PERSONAS.author);
    const author = await openAsPersona(browser, PERSONAS.author);
    let permissionsRestored = false;
    try {
      await author.page.setViewportSize({ width: 1280, height: 720 });
      const session = await enterAuthoringFromRuntime(author.page);
      const tablePatch = await buildTablePatch(
        author.page,
        session,
        '/props/density',
        'compact',
      );
      const { editor: densityEditor, value: stagedDensity } = await stageLocalDensityEdit(
        author.page,
        session,
      );
      await expect(author.page.getByTestId('contextual-authoring-surface')).toHaveAttribute(
        'data-read-only',
        'false',
      );
      await expect(author.page.getByText('1 项未保存')).toBeVisible();

      const retainedPermissions = PERSONAS.author.permissions.filter(
        (permission) => permission !== 'meta.designer.update',
      );
      await assignPermissions(page, role.pid, retainedPermissions);

      const forbiddenWrite = await author.page.request.patch(
        `/api/authoring/sessions/${session.sessionPid}/patches`,
        { data: { expectedRevision: session.revision, ...tablePatch } },
      );
      expect(forbiddenWrite.status(), '后端权限必须在 UI 刷新前独立生效').toBe(403);
      const forbiddenBody = await forbiddenWrite.text();
      expect(forbiddenBody).not.toContain('snapshot');

      await author.page.waitForResponse(
        (response) =>
          response.request().method() === 'GET' &&
          apiPath(response.url()) === `/api/authoring/sessions/${session.sessionPid}`,
        { timeout: 25_000 },
      );
      await expect(author.page.getByTestId('authoring-permission-revoked')).toBeVisible({
        timeout: 65_000,
      });
      await expect(author.page.getByTestId('authoring-permission-revoked')).toContainText(
        '配置权限已收回，当前会话已即时转为只读',
      );
      await expect(author.page.getByTestId('contextual-authoring-surface')).toHaveAttribute(
        'data-read-only',
        'true',
      );
      await expect(author.page.getByText('1 项未保存')).toBeVisible();
      await expect(densityEditor).toBeDisabled();
      await expect(author.page.getByRole('button', { name: '保存', exact: true })).toBeDisabled();

      await assignPermissions(page, role.pid, [...PERSONAS.author.permissions]);
      permissionsRestored = true;
      await expect(author.page.getByTestId('authoring-permission-revoked')).toHaveCount(0, {
        timeout: 65_000,
      });
      await expect(author.page.getByTestId('contextual-authoring-surface')).toHaveAttribute(
        'data-read-only',
        'false',
      );
      await expect(densityEditor).toBeEnabled();
      await expect(densityEditor).toHaveValue(stagedDensity);
      await expect(author.page.getByText('1 项未保存')).toBeVisible();
      await expect(author.page.getByRole('button', { name: '保存', exact: true })).toBeEnabled();
      const unchanged = await expectApiData<AuthoringSession>(
        await author.page.request.get(`/api/authoring/sessions/${session.sessionPid}`),
        'author verifies revision after permission restoration',
      );
      expect(unchanged.revision).toBe(session.revision);
    } finally {
      if (!permissionsRestored) {
        await assignPermissions(page, role.pid, [...PERSONAS.author.permissions]).catch(() => {});
      }
      await author.close();
    }
  });
});

async function login(page: Page, email: string): Promise<void> {
  await loginViaUI(page, email, DEFAULT_PASSWORD);
  await expect(page.locator('nav')).toBeVisible({ timeout: 15_000 });
}

async function openRuntimeFromMenu(page: Page): Promise<void> {
  await page.goto('/', { waitUntil: 'domcontentloaded' });
  const link = page.locator('nav').locator(`a[href="${RUNTIME_ROUTE}"]`).first();
  await expect(link).toBeVisible({ timeout: 10_000 });
  await link.click();
  await expect(page).toHaveURL(new RegExp(`${RUNTIME_ROUTE}$`));
  await expect(page.getByRole('main').first().getByText('EXC-V4-REAL-001')).toBeVisible({
    timeout: 15_000,
  });
}

async function enterAuthoringFromRuntime(page: Page): Promise<AuthoringSession> {
  await openRuntimeFromMenu(page);
  const sessionResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' && apiPath(response.url()) === '/api/authoring/sessions',
  );
  await page.getByRole('main').first().getByRole('button', { name: '配置此页' }).click();
  const session = await expectApiData<AuthoringSession>(
    await sessionResponse,
    'enter contextual authoring',
  );
  await expect(page.getByTestId('contextual-authoring-surface')).toBeVisible();
  return session;
}

async function expectApiData<T>(response: APIResponse, label: string): Promise<T> {
  const body = await expectApiSuccess<T>(response, label);
  return body.data as T;
}

async function expectApiSuccess<T = unknown>(
  response: APIResponse,
  label: string,
): Promise<ApiEnvelope<T>> {
  const text = await response.text();
  let body: ApiEnvelope<T>;
  try {
    body = JSON.parse(text) as ApiEnvelope<T>;
  } catch {
    throw new Error(`${label}: non-JSON response HTTP ${response.status()}: ${text}`);
  }
  expect(response.ok(), `${label}: HTTP ${response.status()}: ${text}`).toBe(true);
  expect(String(body.code ?? '0'), `${label}: API envelope ${text}`).toBe('0');
  return body;
}

async function ensurePersona(adminPage: Page, persona: Persona): Promise<RoleRecord> {
  const roles = await expectApiData<RoleRecord[]>(
    await adminPage.request.get('/api/roles/all'),
    `load roles for ${persona.roleCode}`,
  );
  let role = roles.find((candidate) => candidate.code === persona.roleCode);
  if (!role) {
    role = await expectApiData<RoleRecord>(
      await adminPage.request.post('/api/roles', {
        data: {
          code: persona.roleCode,
          name: persona.roleName,
          description: `PC collaboration golden persona: ${persona.roleName}`,
          type: 'custom',
        },
      }),
      `create role ${persona.roleCode}`,
    );
  }
  await assignPermissions(adminPage, role.pid, [...persona.permissions]);

  const loginProbe = await adminPage.request.post('/api/auth/login', {
    data: { email: persona.email, password: DEFAULT_PASSWORD },
  });
  const loginBody = (await loginProbe.json().catch(() => ({}))) as ApiEnvelope<unknown>;
  if (!loginProbe.ok() || String(loginBody.code ?? '1') !== '0') {
    const created = await expectApiData<{ assignedRoles?: string[] }>(
      await adminPage.request.post('/api/admin/users', {
        data: {
          email: persona.email,
          displayName: persona.roleName,
          initialPassword: DEFAULT_PASSWORD,
          roleCodes: [persona.roleCode],
          sendInviteEmail: false,
        },
      }),
      `create user ${persona.email}`,
    );
    expect(created.assignedRoles ?? []).toContain(persona.roleCode);
  }

  const members = await expectApiData<Array<{ email?: string; memberPid?: string }>>(
    await adminPage.request.get(
      `/api/org/members/unlinked?keyword=${encodeURIComponent(persona.email)}`,
    ),
    `resolve member ${persona.email}`,
  );
  const member = members.find((candidate) => candidate.email === persona.email);
  expect(member?.memberPid, `member pid for ${persona.email}`).toBeTruthy();
  await expectApiData<boolean>(
    await adminPage.request.post('/api/user-roles/assign-by-code', {
      data: { memberPid: member!.memberPid, roleCodes: [persona.roleCode] },
    }),
    `activate role ${persona.roleCode}`,
  );
  return role;
}

async function assignPermissions(
  adminPage: Page,
  rolePid: string,
  permissionCodes: string[],
): Promise<void> {
  const permissionPids = await resolvePermissionPids(adminPage, permissionCodes);
  await expectApiData<boolean>(
    await adminPage.request.post(`/api/roles/${rolePid}/permissions`, {
      data: permissionPids,
    }),
    `assign permissions to ${rolePid}`,
  );
}

async function resolvePermissionPids(page: Page, permissionCodes: string[]): Promise<string[]> {
  const resourceTypes = ['function', 'operation', 'data', 'model'];
  const permissions = (
    await Promise.all(
      resourceTypes.map(async (resourceType) =>
        expectApiData<PermissionRecord[]>(
          await page.request.get(`/api/permissions/resource-type/${resourceType}`),
          `load ${resourceType} permissions`,
        ),
      ),
    )
  ).flat();
  const byCode = new Map(permissions.map((permission) => [permission.code, permission.pid]));
  const missing = permissionCodes.filter((code) => !byCode.has(code));
  expect(missing, `missing permissions: ${missing.join(', ')}`).toEqual([]);
  return permissionCodes.map((code) => byCode.get(code)!);
}

async function openAsPersona(browser: Browser, persona: Persona): Promise<OpenedActor> {
  const context = await browser.newContext({ storageState: { cookies: [], origins: [] } });
  const page = await context.newPage();
  await login(page, persona.email);
  return { context, page, close: () => context.close() };
}

async function buildTablePatch(
  page: Page,
  session: AuthoringSession,
  propertyPath: '/props/density' | '/props/defaultFilter',
  value: unknown,
): Promise<{
  blockId: string;
  propertyPath: string;
  operation: 'ADD' | 'REPLACE';
  value: unknown;
  manifestChecksum: string;
}> {
  const table = findTableBlock(session.snapshot);
  expect(table?.id, 'table block id in authoring snapshot').toBeTruthy();
  const capabilities = await expectApiData<CapabilityRegistry>(
    await page.request.get('/api/authoring/capabilities'),
    'load authoring capabilities',
  );
  const tableManifest = capabilities.manifests.find((manifest) => manifest.blockType === 'table');
  expect(tableManifest?.checksum, 'table manifest checksum').toBeTruthy();
  const current = readObjectPath(table!, propertyPath);
  return {
    blockId: String(table!.id),
    propertyPath,
    operation: current === undefined ? 'ADD' : 'REPLACE',
    value,
    manifestChecksum: tableManifest!.checksum,
  };
}

function findTableBlock(value: unknown): Record<string, unknown> | null {
  if (Array.isArray(value)) {
    for (const item of value) {
      const found = findTableBlock(item);
      if (found) return found;
    }
    return null;
  }
  if (!value || typeof value !== 'object') return null;
  const record = value as Record<string, unknown>;
  if (record.type === 'table' || record.blockType === 'table') return record;
  for (const child of Object.values(record)) {
    const found = findTableBlock(child);
    if (found) return found;
  }
  return null;
}

function readObjectPath(root: Record<string, unknown>, path: string): unknown {
  return path
    .split('/')
    .filter(Boolean)
    .reduce<unknown>((value, segment) => {
      if (!value || typeof value !== 'object' || Array.isArray(value)) return undefined;
      return (value as Record<string, unknown>)[segment];
    }, root);
}

async function stageLocalDensityEdit(page: Page, session: AuthoringSession) {
  const table = findTableBlock(session.snapshot);
  expect(table?.id, 'table block id for local edit').toBeTruthy();
  const value = readObjectPath(table!, '/props/density') === 'compact' ? 'comfortable' : 'compact';
  await page.getByTestId('authoring-outline-open').click();
  await page.getByTestId(`authoring-outline-${String(table!.id)}`).click();
  await page.getByRole('button', { name: '关闭页面大纲' }).click();
  await page.getByTestId('authoring-inspector-open').click();
  const editor = page.getByTestId('authoring-property-/props/density').locator('input');
  await expect(editor).toBeVisible();
  await editor.fill(value);
  return { editor, value };
}

function apiPath(url: string): string {
  return new URL(url).pathname;
}
