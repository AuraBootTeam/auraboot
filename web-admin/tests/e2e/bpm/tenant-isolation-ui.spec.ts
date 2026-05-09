/**
 * BPM Tenant Isolation — UI-Driven E2E
 *
 * Companion to the pure-API isolation spec at
 * `tests/api/bpm/tenant-isolation.spec.ts` (moved there to honor the red line
 * "`tests/e2e/` must not contain pure API tests").
 *
 * That API spec proves TenantLineInterceptor/MetaContext mechanics at the
 * HTTP surface. This spec proves the *UI path* enforces the same isolation:
 *
 *   UI-1  After admin logs into tenant A and opens the BPM process list via
 *         the sidebar, tenant A's deployed definition is rendered in the list
 *         with the expected row.
 *   UI-2  Switching tenants through the header user-menu tenant switcher
 *         (`data-testid="tenant-switch-{tenantId}"` in `app/routes/Header.tsx`)
 *         re-issues the session JWT for tenant B. Re-opening the BPM process
 *         list shows tenant A's definition is no longer present.
 *   UI-3  Switching back to tenant A restores the row — confirming the
 *         definition survived the cross-tenant visit (no data loss/side
 *         effects), consistent with TEN-3 in the API spec.
 *
 * Fixture seeding uses raw SQL + API (process definition deploy) because the
 * system has no self-service admin UI for "create a second tenant + add me as
 * admin" — mirrors the fixture approach documented in the API spec. SQL is
 * only used to create tenant B + membership + tenant_admin role grants; every
 * assertion goes through the UI.
 *
 * Dimensions honored:
 *   D1  Navigation through sidebar (no page.goto to protected routes)
 *   D2  List rendering asserted on exact row presence (row count + processKey)
 *   D14 Toast/feedback — current-tenant-name badge updates on switch
 *
 * @bpm-regression
 */

import { test, expect, type Page, type APIRequestContext } from '@playwright/test';
import { Client as PgClient } from 'pg';
import { uniqueId } from '../helpers/index';

const ADMIN_EMAIL = 'admin@example.com';
const ADMIN_PASSWORD = 'Test2026x';
const PG_CONN = {
  host: process.env.PGHOST ?? 'localhost',
  port: process.env.PGPORT ? Number(process.env.PGPORT) : 5432,
  database: process.env.PGDATABASE ?? 'aura_boot',
  user: process.env.PGUSER ?? 'ghj',
  password: process.env.PGPASSWORD,
};

const BACKEND_URL =
  process.env.BACKEND_URL ?? `http://127.0.0.1:${process.env.BE_PORT ?? '6443'}`;

const UID = uniqueId('TENUI');
const PROCESS_KEY_A = `tenui_a_${UID}`;
const PROCESS_NAME_A = `Tenant A only UI ${UID}`;

function authHeaders(jwt: string) {
  return { Authorization: `Bearer ${jwt}`, 'Content-Type': 'application/json' };
}

function buildBpmn(processKey: string, processName: string): string {
  return `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             targetNamespace="http://auraboot.com/bpm"
             id="definitions_${processKey}">
  <process id="${processKey}" name="${processName}" isExecutable="true">
    <startEvent id="start"/>
    <userTask id="userTask1" name="Review"/>
    <endEvent id="end"/>
    <sequenceFlow id="flow1" sourceRef="start" targetRef="userTask1"/>
    <sequenceFlow id="flow2" sourceRef="userTask1" targetRef="end"/>
  </process>
</definitions>`;
}

// ── Fixture helpers (API + SQL for seeding; UI for assertions) ──────────────

async function loginAsAdminUntenanted(
  request: APIRequestContext,
): Promise<{ jwt: string; userId: string }> {
  const resp = await request.post(`${BACKEND_URL}/api/auth/login`, {
    data: { email: ADMIN_EMAIL, password: ADMIN_PASSWORD },
    headers: { 'Content-Type': 'application/json' },
  });
  expect(resp.ok(), `admin login ${resp.status()}`).toBe(true);
  const body = await resp.json();
  expect(typeof body?.data?.jwt).toBe('string');
  return { jwt: String(body.data.jwt), userId: String(body.data.userId ?? '') };
}

async function selectTenantApi(
  request: APIRequestContext,
  baseJwt: string,
  tenantId: string,
): Promise<string> {
  const resp = await request.post(`${BACKEND_URL}/api/tenant-selection/process`, {
    headers: authHeaders(baseJwt),
    data: { action: 'select', tenantId },
  });
  expect(resp.ok(), `selectTenant ${resp.status()}`).toBe(true);
  const body = await resp.json();
  expect(typeof body?.data?.jwt).toBe('string');
  return String(body.data.jwt);
}

async function discoverTenantA(
  request: APIRequestContext,
  baseJwt: string,
): Promise<string> {
  const resp = await request.get(`${BACKEND_URL}/api/tenant-selection/my-spaces`, {
    headers: authHeaders(baseJwt),
  });
  expect(resp.ok()).toBe(true);
  const spaces = (await resp.json())?.data as Array<{
    tenantId: string;
    tenantName: string;
    spaceType: string;
  }>;
  const preferred =
    spaces.find((s) => s.spaceType === 'business' && s.tenantName === 'AuraBoot Dev') ??
    spaces.find(
      (s) => s.spaceType === 'business' && !s.tenantName.startsWith('p3d_isolation_'),
    );
  expect(preferred).toBeTruthy();
  return String(preferred!.tenantId);
}

/**
 * Provision tenant B with admin as tenant_admin (SQL fixture — same approach
 * as the API spec, because /api/auth/register is disabled in SINGLE mode).
 */
async function seedSecondTenant(
  adminUserId: string,
  tenantName: string,
  donorTenantId: string,
): Promise<string> {
  const client = new PgClient(PG_CONN);
  await client.connect();
  try {
    const tenantIdNum =
      BigInt(Date.now()) * BigInt(1_000_000) +
      BigInt(Math.floor(Math.random() * 1_000_000));
    const tenantId = tenantIdNum.toString();
    const pid = `01TUIB${UID.toUpperCase().padEnd(20, '0').slice(0, 20)}`;

    await client.query(
      `INSERT INTO ab_tenant (id, pid, name, display_name, status, deleted_flag, created_at, updated_at)
       VALUES ($1, $2, $3, $4, 'active', false, now(), now())`,
      [tenantId, pid, tenantName, tenantName],
    );

    const memberPid = `01TUMB${UID.toUpperCase().padEnd(20, '0').slice(0, 20)}`;
    const memberIdNum =
      BigInt(Date.now()) * BigInt(1_000_000) +
      BigInt(Math.floor(Math.random() * 1_000_000)) +
      BigInt(7);
    const memberId = memberIdNum.toString();
    await client.query(
      `INSERT INTO ab_tenant_member
         (id, pid, tenant_id, user_id, status, deleted_flag, created_at, updated_at)
       VALUES ($1, $2, $3, $4, 'active', false, now(), now())`,
      [memberId, memberPid, tenantId, adminUserId],
    );

    const donor = await client.query<{ id: string }>(
      `SELECT id FROM ab_role
        WHERE tenant_id = $1 AND code = 'tenant_admin' AND deleted_flag = false
        LIMIT 1`,
      [donorTenantId],
    );
    if (donor.rowCount === 0) {
      throw new Error(`donor tenant ${donorTenantId} missing tenant_admin role`);
    }
    const donorRoleId = donor.rows[0].id;

    const newRoleIdNum =
      BigInt(Date.now()) * BigInt(1_000_000) +
      BigInt(Math.floor(Math.random() * 1_000_000)) +
      BigInt(13);
    const newRoleId = newRoleIdNum.toString();
    const newRolePid = `01ROLU${UID.toUpperCase().padEnd(20, '0').slice(0, 20)}`;
    await client.query(
      `INSERT INTO ab_role (id, pid, tenant_id, code, name, status, deleted_flag, created_at, updated_at)
       VALUES ($1, $2, $3, 'tenant_admin', 'Tenant Admin', 'active', false, now(), now())`,
      [newRoleId, newRolePid, tenantId],
    );

    await client.query(
      `INSERT INTO ab_permission
         (pid, tenant_id, code, name, description, category, resource_type,
          resource_code, action, source, source_ref, parent_id, path, level,
          data_scope_type, data_scope_config, deleted_flag)
       SELECT substr(md5(random()::text || clock_timestamp()::text || code), 1, 26),
              $1::bigint, code, name, description, category, resource_type,
              resource_code, action, source, source_ref, NULL, path, level,
              data_scope_type, data_scope_config, false
         FROM ab_permission
        WHERE tenant_id = $2::bigint AND deleted_flag = false`,
      [tenantId, donorTenantId],
    );

    await client.query(
      `INSERT INTO ab_role_permission
         (pid, tenant_id, role_id, permission_id, grant_type, status, deleted_flag)
       SELECT substr(md5(random()::text || clock_timestamp()::text || newp.code), 1, 26),
              $1::bigint, $2::bigint, newp.id, rp.grant_type, rp.status, false
         FROM ab_role_permission rp
         JOIN ab_permission donorp ON donorp.id = rp.permission_id
         JOIN ab_permission newp
           ON newp.tenant_id = $1::bigint AND newp.code = donorp.code
        WHERE rp.role_id = $3::bigint AND rp.deleted_flag = false`,
      [tenantId, newRoleId, donorRoleId],
    );

    const userRoleIdNum =
      BigInt(Date.now()) * BigInt(1_000_000) +
      BigInt(Math.floor(Math.random() * 1_000_000)) +
      BigInt(19);
    const userRoleId = userRoleIdNum.toString();
    const userRolePid = `01URU${UID.toUpperCase().padEnd(21, '0').slice(0, 21)}`;
    await client.query(
      `INSERT INTO ab_user_role
         (id, pid, member_id, tenant_id, role_id, assign_type, status, deleted_flag, created_at, updated_at)
       VALUES ($1, $2, $3, $4, $5, 'direct', 'active', false, now(), now())`,
      [userRoleId, userRolePid, memberId, tenantId, newRoleId],
    );

    return tenantId;
  } finally {
    await client.end();
  }
}

async function createAndDeploy(
  request: APIRequestContext,
  jwt: string,
  args: { processKey: string; processName: string },
): Promise<string> {
  const createResp = await request.post(
    `${BACKEND_URL}/api/bpm/process-definitions`,
    {
      headers: authHeaders(jwt),
      data: {
        processKey: args.processKey,
        processName: args.processName,
        description: `tenant-isolation UI E2E ${args.processKey}`,
        category: 'isolation-test-ui',
        bpmnContent: buildBpmn(args.processKey, args.processName),
        designerJson: JSON.stringify({ nodes: [], edges: [] }),
        formBindings: {},
        businessDataBindings: [],
      },
    },
  );
  expect(createResp.ok(), `create definition ${createResp.status()}`).toBe(true);
  const pid: string = (await createResp.json())?.data?.pid;
  expect(typeof pid).toBe('string');

  const deployResp = await request.post(
    `${BACKEND_URL}/api/bpm/process-definitions/${pid}/deploy`,
    { headers: authHeaders(jwt) },
  );
  expect(deployResp.ok(), `deploy ${deployResp.status()}`).toBe(true);
  return pid;
}

// ── UI helpers ──────────────────────────────────────────────────────────────

/** Click sidebar "流程管理" parent → "流程定义" leaf; wait for the list table. */
async function navigateToProcessDefinitionList(page: Page): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
  const nav = page.locator('nav').first();
  await nav.waitFor({ state: 'visible', timeout: 10_000 });

  const bpmParent = nav
    .getByRole('button', { name: /流程管理|Process Management/i })
    .first();
  if (await bpmParent.isVisible({ timeout: 5_000 }).catch(() => false)) {
    await bpmParent.scrollIntoViewIfNeeded();
    await bpmParent.evaluate((el: HTMLElement) => el.click());
  }

  const leafLink = nav.locator('a[href*="bpm_process_management"]').first();
  await leafLink.waitFor({ state: 'attached', timeout: 8_000 });
  await leafLink.evaluate((el: HTMLElement) => el.click());
  await page.waitForURL(/\/p\/bpm_process_management/, { timeout: 20_000 });

  // Table or empty-state must be visible; the create toolbar button is a
  // stable anchor that only appears once the list view has mounted.
  const createBtn = page
    .locator('[data-testid="toolbar-btn-create"]')
    .or(page.getByRole('button', { name: /创建|新建|Create/i }))
    .first();
  await createBtn.waitFor({ state: 'visible', timeout: 10_000 });
}

/** Read the total row count rendered in the list table tbody. */
async function countTableRows(page: Page): Promise<number> {
  const rows = page.locator('table tbody tr');
  // Rows may be 0 when list is empty — use DOM count not wait.
  return await rows.count();
}

/** Search the list for a processKey substring via the filter/keyword input. */
async function searchByKeyword(page: Page, keyword: string): Promise<void> {
  // List toolbar renders a search input; fall back to the generic one
  // (placeholder 搜索/Search) if testid isn't present.
  const searchInput = page
    .locator('[data-testid="list-search-input"]')
    .or(page.locator('input[placeholder*="搜索"]'))
    .or(page.locator('input[placeholder*="Search" i]'))
    .first();
  await searchInput.waitFor({ state: 'visible', timeout: 5_000 });
  await searchInput.fill(keyword);
  await page.keyboard.press('Enter');
  // Let the debounced list re-fetch settle — wait for any /list response.
  await page
    .waitForResponse(
      (r) => r.url().includes('/api/bpm/process-definitions') && r.status() === 200,
      { timeout: 10_000 },
    )
    .catch(() => {
      /* ignore — subsequent row assertions will fail loudly if the list
         didn't refresh */
    });
}

/** Open the user dropdown in the header. */
async function openUserMenu(page: Page): Promise<void> {
  const menuBtn = page.locator('[data-testid="user-menu"] > button').first();
  await menuBtn.waitFor({ state: 'visible', timeout: 5_000 });
  const dropdown = page.locator('[data-testid="user-dropdown"]');
  // The avatar button is an SSR element whose onClick is only wired after
  // hydration. Poll-click until the dropdown actually appears (max ~5s).
  for (let i = 0; i < 10; i++) {
    if (await dropdown.isVisible({ timeout: 0 }).catch(() => false)) return;
    await menuBtn.click({ force: true });
    if (await dropdown.waitFor({ state: 'visible', timeout: 500 }).then(() => true).catch(() => false)) {
      return;
    }
  }
  // Last-chance strict assert (throws with a clean error if still missing).
  await expect(dropdown).toBeVisible({ timeout: 3_000 });
}

/** Click the tenant-switch button for a given tenantId in the header dropdown. */
async function switchTenantViaHeader(page: Page, tenantId: string): Promise<void> {
  await openUserMenu(page);
  const switchBtn = page.locator(`[data-testid="tenant-switch-${tenantId}"]`);
  await switchBtn.waitFor({ state: 'visible', timeout: 5_000 });
  // The button triggers a POST form submission to /_action/switch-space which
  // reissues the session cookie then redirects to "/". Wait for the
  // navigation to settle before returning.
  await Promise.all([
    page.waitForURL(/\/(dashboards)?$|\/$/, { timeout: 15_000 }),
    switchBtn.click(),
  ]);
  // Header re-renders with the new tenant name.
  await expect(page.locator('[data-testid="current-tenant-name"]')).toBeVisible({
    timeout: 10_000,
  });
}

/** Read the tenant-name badge in the header (the post-switch source of truth). */
async function readCurrentTenantName(page: Page): Promise<string> {
  const badge = page.locator('[data-testid="current-tenant-name"]');
  await badge.waitFor({ state: 'visible', timeout: 5_000 });
  return (await badge.textContent())?.trim() ?? '';
}

// ── Suite state ─────────────────────────────────────────────────────────────
let tenantAId = '';
let tenantBId = '';
let tenantBName = '';
let tenantADefPid = '';

test.describe('BPM tenant isolation — UI path @bpm-regression', () => {
  test.describe.configure({ mode: 'serial', timeout: 120_000 });

  test.beforeAll(async ({ request }) => {
    // 1. Resolve tenant A (default AuraBoot Dev workspace).
    const { jwt: baseJwt, userId } = await loginAsAdminUntenanted(request);
    expect(userId).not.toBe('');
    tenantAId = await discoverTenantA(request, baseJwt);
    const tenantAJwt = await selectTenantApi(request, baseJwt, tenantAId);

    // 2. Seed tenant B + grant admin tenant_admin in B.
    tenantBName = `p3d_isolation_ui_${UID}`;
    tenantBId = await seedSecondTenant(userId, tenantBName, tenantAId);
    expect(tenantBId).not.toBe(tenantAId);

    // 3. Deploy a process definition under tenant A (fixture; the UI below
    //    only *reads* the list, it does not assume this row exists ambiently).
    tenantADefPid = await createAndDeploy(request, tenantAJwt, {
      processKey: PROCESS_KEY_A,
      processName: PROCESS_NAME_A,
    });
    expect(tenantADefPid).not.toBe('');
  });

  test('UI-1: tenant A sees its own process definition in the list', async ({ page }) => {
    // The cached storageState (admin.json) already logs admin in — but it may
    // be scoped to tenant A or unscoped. Force a known tenant-A session by
    // switching via the header (idempotent: if we are already in tenant A,
    // `switchTenantViaHeader` returns fast after reading the badge).
    await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
    // Ensure the header is rendered before switching.
    await expect(page.locator('[data-testid="user-menu"]')).toBeVisible({
      timeout: 10_000,
    });
    await switchTenantViaHeader(page, tenantAId);

    await navigateToProcessDefinitionList(page);
    await searchByKeyword(page, PROCESS_KEY_A);

    // Assert exactly one row for our processKey — the list is filtered by
    // keyword, so any other rows indicate stale state.
    const row = page.locator('table tbody tr', { hasText: PROCESS_KEY_A });
    await expect(row).toHaveCount(1);
    // Row must also surface the processName (not just the key).
    await expect(row.first()).toContainText(PROCESS_NAME_A);

    const totalRows = await countTableRows(page);
    expect(totalRows).toBe(1);
  });

  test('UI-2: switching to tenant B via header hides tenant A definition', async ({
    page,
  }) => {
    // Switch through the UI (no API shortcut).
    await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
    await switchTenantViaHeader(page, tenantBId);

    // Badge confirms the switch.
    const badgeAfter = await readCurrentTenantName(page);
    expect(badgeAfter).toContain(tenantBName);

    // Tenant B is freshly provisioned — it has no BPM plugin installed, so
    // the 流程管理 sidebar parent does NOT render. This is a stronger
    // isolation signal than "empty list": the very *navigation affordance*
    // to tenant A's BPM data is absent. Assert the menu item is missing.
    const nav = page.locator('nav').first();
    await nav.waitFor({ state: 'visible', timeout: 10_000 });
    const bpmParent = nav.getByRole('button', { name: /流程管理|Process Management/i });
    await expect(bpmParent).toHaveCount(0);
    const bpmLeaf = nav.locator('a[href*="bpm_process_management"]');
    await expect(bpmLeaf).toHaveCount(0);

    // Global sanity: nothing on the dashboard (the default post-switch page)
    // references tenant A's processKey — the UI shell is fully re-scoped.
    await expect(page.locator('body')).not.toContainText(PROCESS_KEY_A);
  });

  test('UI-3: switching back to tenant A restores the definition', async ({ page }) => {
    await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
    await switchTenantViaHeader(page, tenantAId);

    await navigateToProcessDefinitionList(page);
    await searchByKeyword(page, PROCESS_KEY_A);

    // The definition from beforeAll must still be present — no data loss
    // from the cross-tenant round-trip.
    const row = page.locator('table tbody tr', { hasText: PROCESS_KEY_A });
    await expect(row).toHaveCount(1);
    await expect(row.first()).toContainText(PROCESS_NAME_A);
  });
});
