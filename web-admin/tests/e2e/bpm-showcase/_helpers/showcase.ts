/**
 * BPM showcase E2E helpers (S1-S8 scenario suite, tag `@bpm-showcase`).
 *
 * Scenario plan SOT: workspace docs/plans/2026-08-28-oss-bpm-showcase-e2e-verification-plan.md
 *
 * Hard rules encoded here (do not weaken):
 * - Core BPM actions are driven through the REAL UI; API calls are allowed
 *   only for preparation (process deployment, user provisioning) and are
 *   marked API-backed in the coverage matrix.
 * - Multi-persona sessions are created through the real /login form via
 *   `loginViaUI` (same mechanism as rbac-helpers), never by forging cookies.
 * - Every evidence screenshot must first scroll its target into view; a
 *   screenshot that does not show the reviewed element is not evidence.
 *
 * Engine/API helpers are reused from tests/e2e/bpm/_helpers/bpm-lifecycle.ts
 * (single canonical `json.data?.field` parsing — no silent fallback).
 */

import { expect, type APIRequestContext, type Browser, type BrowserContext, type Page } from '@playwright/test';
import { loginViaUI } from '../../../helpers/wd-fixtures';

export const SHOWCASE_PASSWORD = 'Test2026x';

/** Five personas cover initiator / approver / reassign target / cc / no-permission roles. */
export interface ShowcaseUser {
  key: string;
  email: string;
  displayName: string;
}

export const SHOWCASE_USERS: Record<'alice' | 'bob' | 'carol' | 'dave' | 'eve', ShowcaseUser> = {
  alice: { key: 'alice', email: 'bpm-showcase-alice@test.com', displayName: 'BPM Showcase Alice' },
  bob: { key: 'bob', email: 'bpm-showcase-bob@test.com', displayName: 'BPM Showcase Bob' },
  carol: { key: 'carol', email: 'bpm-showcase-carol@test.com', displayName: 'BPM Showcase Carol' },
  dave: { key: 'dave', email: 'bpm-showcase-dave@test.com', displayName: 'BPM Showcase Dave' },
  eve: { key: 'eve', email: 'bpm-showcase-eve@test.com', displayName: 'BPM Showcase Eve' },
};

/** Role carrying the baseline BPM permissions every persona needs. */
export const SHOWCASE_ROLE_CODE = 'bpm_showcase_member';
const SHOWCASE_PERMISSION_CODES = [
  'bpm.process.execute',
  'bpm.process.read',
  'bpm.task.read',
  'bpm.task.update',
  // approve/reject dialogs load GET /api/bpm/forms/task/{id} (gated by
  // bpm.form.update) to resolve taskActions variables before completing.
  'bpm.form.update',
  // add-sign / remove-sign / rollback are gated by WORKFLOW_ADMIN.
  'bpm.process.admin',
  'bpm_management',
  'bpm_process_management',
  'bpm_task_center',
];

/**
 * Idempotently create the showcase role and grant it the BPM permission codes.
 * Permission pids resolved from /api/permissions/resource-type/* (same recipe
 * as e2et-reference-inline-create.spec.ts).
 */
export async function ensureShowcaseRole(request: APIRequestContext): Promise<void> {
  const existing = await request.get('/api/roles/all', { timeout: 15_000 });
  const existingBody = await existing.json().catch(() => ({}) as Record<string, unknown>);
  const roles = ((existingBody?.data ?? []) as Array<Record<string, unknown>>);
  const mine = roles.find((r) => r.code === SHOWCASE_ROLE_CODE);

  let rolePid = mine ? String(mine.pid) : '';
  if (!rolePid) {
    const resp = await request.post('/api/roles', {
      data: {
        code: SHOWCASE_ROLE_CODE,
        name: 'BPM Showcase Member',
        description: 'Baseline BPM permissions for bpm-showcase E2E personas',
        type: 'custom',
      },
      timeout: 20_000,
    });
    const body = await resp.json().catch(() => ({}) as Record<string, unknown>);
    expect(resp.ok(), `create showcase role HTTP ${resp.status()}: ${JSON.stringify(body).slice(0, 300)}`).toBe(true);
    rolePid = String((body as { data?: Record<string, unknown> })?.data?.pid ?? '');
    expect(rolePid, 'role create must return pid').toBeTruthy();
  }

  const permissionPids: string[] = [];
  for (const resourceType of ['function', 'operation', 'data', 'model', 'menu']) {
    const resp = await request.get(`/api/permissions/resource-type/${resourceType}`, { timeout: 15_000 });
    if (!resp.ok()) continue;
    const body = await resp.json().catch(() => ({}) as Record<string, unknown>);
    const list = (body?.data ?? []) as Array<Record<string, unknown>>;
    for (const code of SHOWCASE_PERMISSION_CODES) {
      const hit = list.find((p) => p.code === code);
      if (hit?.pid) permissionPids.push(String(hit.pid));
    }
  }
  expect(
    new Set(permissionPids).size,
    `all showcase permissions must resolve: ${SHOWCASE_PERMISSION_CODES.join(',')}`,
  ).toBe(SHOWCASE_PERMISSION_CODES.length);

  const assign = await request.post(`/api/roles/${rolePid}/permissions`, {
    data: permissionPids,
    timeout: 20_000,
  });
  expect(assign.ok(), `assign permissions to showcase role: ${assign.status()} ${await assign.text()}`).toBe(true);
}

/**
 * Idempotent provisioning of one showcase persona via POST /api/admin/users.
 * Probes login first so warm-stack re-runs don't fail on "already exists";
 * pre-existing users created before the showcase role existed get the role
 * assigned via POST /api/user-roles/assign-by-code.
 */
export async function ensureShowcaseUser(
  request: APIRequestContext,
  user: ShowcaseUser,
  adminToken: string,
): Promise<void> {
  const probe = await request.post('/api/auth/login', {
    data: { email: user.email, password: SHOWCASE_PASSWORD },
    timeout: 20_000,
  });
  if (probe.ok()) {
    // Resolve the TENANT MEMBER pid via /api/tenant/members/search — the
    // assign-by-code endpoint resolves members, not ab_user rows (the user
    // pid from /api/auth/me or /api/admin/users/search is rejected).
    const search = await request.post('/api/tenant/members/search', {
      data: { keyword: user.email },
      headers: { Authorization: `Bearer ${adminToken}`, 'Content-Type': 'application/json' },
      timeout: 15_000,
    });
    const searchBody = await search.json().catch(() => ({}) as Record<string, unknown>);
    const records = ((searchBody?.data?.records ?? []) as Array<Record<string, unknown>>)
      .filter((m) => (m.user as Record<string, unknown> | null)?.email === user.email);
    expect(records.length, `tenant member search must find ${user.email}`).toBe(1);
    const memberPid = String(records[0].pid ?? '');
    expect(memberPid, `member search must expose pid for ${user.email}`).toBeTruthy();
    const assign = await request.post('/api/user-roles/assign-by-code', {
      data: { memberPid, roleCodes: [SHOWCASE_ROLE_CODE] },
      timeout: 20_000,
    });
    expect(
      assign.ok(),
      `assign showcase role to ${user.key}: ${assign.status()} ${await assign.text()}`,
    ).toBe(true);
    return;
  }
  const resp = await request.post('/api/admin/users', {
    data: {
      email: user.email,
      displayName: user.displayName,
      initialPassword: SHOWCASE_PASSWORD,
      roleCodes: [SHOWCASE_ROLE_CODE],
      sendInviteEmail: false,
    },
    timeout: 20_000,
  });
  const body = await resp.json().catch(() => ({}) as Record<string, unknown>);
  expect(
    resp.ok(),
    `provision ${user.key} (${user.email}) HTTP ${resp.status()}: ${JSON.stringify(body).slice(0, 400)}`,
  ).toBe(true);
}

/** API login returning the raw JWT (for backend-evidence queries). */
export async function loginJwt(
  request: APIRequestContext,
  email: string,
  password: string = SHOWCASE_PASSWORD,
): Promise<string> {
  const resp = await request.post('/api/auth/login', {
    data: { email, password },
    headers: { 'Content-Type': 'application/json' },
    timeout: 20_000,
  });
  if (!resp.ok()) {
    throw new Error(`login ${email} failed: ${resp.status()} ${await resp.text()}`);
  }
  const body = await resp.json();
  const jwt = body?.data?.jwt;
  if (typeof jwt !== 'string' || jwt.length === 0) {
    throw new Error(`login ${email} returned no jwt: ${JSON.stringify(body)}`);
  }
  return jwt;
}

/**
 * Open a fresh browser context authenticated through the REAL /login form.
 * Returns context + page so callers manage lifecycle and screenshots.
 */
export async function openUserSession(
  browser: Browser,
  user: ShowcaseUser,
): Promise<{ context: BrowserContext; page: Page }> {
  const context = await browser.newContext({ storageState: { cookies: [], origins: [] } });
  const page = await context.newPage();
  await loginViaUI(page, user.email, SHOWCASE_PASSWORD);
  return { context, page };
}

export interface DeployProcessArgs {
  processKey: string;
  processName: string;
  description?: string;
  /** Designer graph: `{ nodes, edges }` (same shape the designer UI saves). */
  designerJson: { nodes: unknown[]; edges: unknown[] };
}

/**
 * Create a draft process definition with designerJson and deploy it.
 * API-backed by design: the designer canvas itself is covered by the
 * bpm-designer suites; showcase scenarios assert runtime + task-center UX.
 * Returns the processKey (startProcessInstance resolves the current definition).
 */
export async function deployProcess(
  request: APIRequestContext,
  token: string,
  args: DeployProcessArgs,
): Promise<string> {
  const headers = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };
  const createDraft = async () =>
    request.post('/api/bpm/process-definitions', {
      headers,
      data: {
        processKey: args.processKey,
        processName: args.processName,
        description: args.description ?? 'BPM showcase E2E',
        category: 'e2e-test',
        designerJson: JSON.stringify(args.designerJson),
      },
      timeout: 20_000,
    });

  let createResp = await createDraft();

  // Idempotent re-run: a leftover definition with the same key (draft from a
  // failed earlier run, or deployed from a completed one) blocks creation.
  // Clean it up and retry once.
  if (!createResp.ok()) {
    const errText = await createResp.text().catch(() => '');
    if (/already exists/i.test(errText)) {
      const listResp = await request.get('/api/bpm/process-definitions', { headers, timeout: 15_000 });
      if (listResp.ok()) {
        const listBody = await listResp.json().catch(() => ({}) as Record<string, unknown>);
        const defs = ((listBody?.data?.records ?? listBody?.data ?? []) as Array<Record<string, unknown>>)
          .filter((d) => d.processKey === args.processKey);
        for (const def of defs) {
          const pid = String(def.pid ?? '');
          if (!pid) continue;
          if (def.status === 'deployed' || def.isDeployed === true) {
            await request.post(`/api/bpm/process-definitions/${pid}/undeploy`, { headers, timeout: 15_000 });
          }
          await request.delete(`/api/bpm/process-definitions/${pid}`, { headers, timeout: 15_000 });
        }
      }
      createResp = await createDraft();
    }
  }
  const createBody = await createResp.json().catch(() => ({}) as Record<string, unknown>);
  expect(
    createResp.ok(),
    `draft create ${args.processKey} HTTP ${createResp.status()}: ${JSON.stringify(createBody).slice(0, 400)}`,
  ).toBe(true);
  const pid = String((createBody as { data?: Record<string, unknown> })?.data?.pid
    ?? (createBody as { data?: Record<string, unknown> })?.data?.id ?? '');
  expect(pid, 'draft create must return pid').toBeTruthy();

  const deployResp = await request.post(`/api/bpm/process-definitions/${pid}/deploy`, {
    headers,
    timeout: 30_000,
  });
  const deployBody = await deployResp.json().catch(() => ({}) as Record<string, unknown>);
  expect(
    deployResp.ok(),
    `deploy ${args.processKey} HTTP ${deployResp.status()}: ${JSON.stringify(deployBody).slice(0, 400)}`,
  ).toBe(true);
  expect(
    (deployBody as { data?: { status?: string } })?.data?.status,
    'deploy must end status=deployed',
  ).toBe('deployed');
  return args.processKey;
}

/**
 * Evidence screenshot: scroll the target into view first, then capture.
 * A fullPage screenshot of an inner-scrolling container misses everything
 * below the fold — element-scoped or scrolled captures are mandatory.
 */
export async function evidenceShot(
  page: Page,
  testInfo: { outputPath: (...parts: string[]) => string },
  name: string,
  target?: { scrollIntoViewIfNeeded(): Promise<void>; screenshot(opts: { path: string }): Promise<void> },
): Promise<string> {
  const path = testInfo.outputPath('screenshots', `${name}.png`);
  if (target) {
    await target.scrollIntoViewIfNeeded();
    await target.screenshot({ path });
  } else {
    await page.screenshot({ path, fullPage: true });
  }
  return path;
}

/**
 * Assert the main content area (never `body` — the sidebar menu text would
 * mask a "Page not found" content area) shows the expected text and no
 * load-failure marker. Every navigation assertion goes through this.
 */
export async function expectContentReady(page: Page, expectedText?: RegExp): Promise<void> {
  const main = page.locator('main, [role="main"]').first();
  await main.waitFor({ state: 'visible', timeout: 15_000 });
  const content = await main.innerText();
  expect(content, 'content area must not show a load failure').not.toMatch(/加载失败|Page not found/);
  if (expectedText) {
    expect(content, `content area must match ${expectedText}`).toMatch(expectedText);
  }
}
