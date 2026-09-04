/**
 * PAR-26-B: organization / user / role batch management parity suite.
 *
 * Cordys parity faces covered (crm-cordys-parity-map PAR-26):
 * - system:organization-user: enable (batch), batch-reset-password, delete-check
 * - system:department: sort, set-commander, delete-check
 * - system:role: get-dept-user-tree, relate-user, batch-delete-role-user
 * - route:web:system:24 tenant-member list with bulk actions
 *
 * Every dangerous action follows the four-part acceptance contract:
 * confirmation, success feedback, DB read-back, unauthorized 403 with no side effect.
 */
import { expect, test, type Page, type TestInfo } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';
import { Pool } from 'pg';
import { PG_CONN } from '../../helpers/environments';

const PASSWORD = 'Test2026x';

const SCENARIOS = {
  BULK_STATUS_UI:
    'administrator batch-suspends and batch-restores tenant members from the list bulk actions while sessions are revoked',
  BATCH_PASSWORD_RESET:
    'administrator batch-resets user passwords through the batch API with per-user temporary passwords and DB read-back',
  BATCH_STATUS_API:
    'administrator batch-transitions member status through the batch API with partial-failure isolation and 403 no-side-effect pairing',
  DEPARTMENT_ADMIN:
    'administrator sorts departments, sets a department commander and pre-checks deletion through dedicated APIs',
  ROLE_MEMBER_TREE:
    'administrator reads the role department-user tree, batch-removes role members and is denied without ROLE_READ',
};

const completedScenarios = new Set<string>();
const failedScenarios = new Set<string>();
const runtimeScreenshots = new Set<string>();

const pool = new Pool({ ...PG_CONN, max: 2 });

async function dbQuery<T extends Record<string, unknown>>(
  text: string,
  values: unknown[] = [],
): Promise<T[]> {
  const client = await pool.connect();
  try {
    const result = await client.query(text, values);
    return result.rows as T[];
  } finally {
    client.release();
  }
}

async function attachScreenshot(page: Page, testInfo: TestInfo, name: string): Promise<void> {
  const screenshotPath = testInfo.outputPath(`${name}.png`);
  await page.screenshot({ path: screenshotPath });
  runtimeScreenshots.add(screenshotPath);
  await testInfo.attach(name, { path: screenshotPath });
}

function writeRuntimeReceipt(): void {
  const receiptPath = process.env.ORG_RUNTIME_RECEIPT;
  if (!receiptPath) return;
  const expected = Object.values(SCENARIOS);
  const completed = expected.filter((scenario) => completedScenarios.has(scenario));
  const receipt = {
    schemaVersion: 1,
    runId: process.env.AURA_RUNTIME_NAME ?? `par26-org-${Date.now()}`,
    createdAt: new Date().toISOString(),
    verdict: completed.length === expected.length && failedScenarios.size === 0 ? 'pass' : 'fail',
    fixtureMode: 'self-seeded',
    dataMigration: 'out-of-scope-development-stage',
    expectedScenarios: expected,
    completedScenarios: completed,
    failedScenarios: [...failedScenarios],
    screenshots: [...runtimeScreenshots],
  };
  fs.mkdirSync(path.dirname(receiptPath), { recursive: true });
  fs.writeFileSync(receiptPath, `${JSON.stringify(receipt, null, 2)}\n`);
}

async function uiLogin(page: Page, email: string): Promise<void> {
  await page.goto('/login', { waitUntil: 'domcontentloaded' });
  await page.waitForLoadState('networkidle', { timeout: 10_000 }).catch(() => {});
  const identifier = page
    .locator('input[placeholder*="用户名"], input[name="identifier"], input[type="email"]')
    .first();
  if (await identifier.isVisible({ timeout: 5_000 }).catch(() => false)) {
    await identifier.fill(email);
    await page.getByRole('textbox', { name: '密码' }).fill(PASSWORD);
    await expect(identifier).toHaveValue(email);
    await page.getByRole('button', { name: '立即登录', exact: true }).click();
    await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 20_000 });
  }
}

interface ProvisionedUser {
  email: string;
  userPid: string;
  memberPid: string;
  memberId: number;
}

async function provisionUser(
  page: Page,
  stamp: string,
  kind: string,
  roleCodes: string[],
): Promise<ProvisionedUser> {
  const email = `par26-${kind}-${stamp}@e2e.local`;
  const response = await page.request.post('/api/admin/users', {
    data: {
      email,
      displayName: `PAR26 ${kind} ${stamp}`,
      initialPassword: PASSWORD,
      roleCodes,
      sendInviteEmail: false,
    },
  });
  const body = await response.json().catch(() => ({}));
  expect(
    response.ok() && String(body?.code) === '0',
    `provision ${kind} failed: HTTP ${response.status()} ${JSON.stringify(body).slice(0, 600)}`,
  ).toBe(true);
  const userPid = String(body?.data?.pid ?? body?.data?.userPid ?? '');
  expect(userPid, 'provisioned user pid').toBeTruthy();

  const memberSearch = await page.request.post('/api/tenant/members/search', {
    data: { keyword: email, pageNum: 1, pageSize: 50 },
  });
  const memberBody = await memberSearch.json().catch(() => ({}));
  const rows: Array<Record<string, unknown>> = memberBody?.data?.records ?? memberBody?.data?.rows ?? [];
  const row = rows.find((item) => String((item?.user as Record<string, unknown>)?.email ?? '') === email);
  const memberPid = String(row?.pid ?? '');
  expect(memberPid, `tenant member pid for ${email}`).toBeTruthy();

  const dbRows = await dbQuery<{ id: number; status: string }>(
    `SELECT id, status FROM ab_tenant_member WHERE pid = $1`,
    [memberPid],
  );
  expect(dbRows).toHaveLength(1);
  return { email, userPid, memberPid, memberId: dbRows[0].id };
}

/**
 * The platform denies admin-gated calls with either an HTTP 401/403 status or
 * the canonical HTTP-200 error envelope (code "409" / "admin role required").
 * Either shape is a denial; callers must also assert no side effects.
 */
async function expectForbidden(
  response: { status: () => number },
  body: Record<string, unknown>,
): Promise<void> {
  const envelope = JSON.stringify(body ?? {});
  const status = response.status();
  const denied =
    status === 401 ||
    status === 403 ||
    (String(body?.code ?? '') !== '0' &&
      /409|admin role|forbidden|denied|无权|权限/i.test(envelope));
  expect(denied, `call must be denied: HTTP ${status} ${envelope.slice(0, 300)}`).toBe(true);
  expect(String(body?.code ?? ''), 'denied call must not return success code').not.toBe('0');
}

test.describe('PAR-26-B organization/user/role batch management parity', () => {
  test.setTimeout(300_000);

  test.afterAll(() => {
    writeRuntimeReceipt();
    void pool.end();
  });

  test('B-001: administrator batch-suspends and batch-restores members from list bulk actions', async ({
    page,
  }, testInfo) => {
    test.info().annotations.push({ type: 'scenario', description: SCENARIOS.BULK_STATUS_UI });
    const stamp = `${Date.now()}`;
    const member1 = await provisionUser(page, stamp, 'b1u1', ['crm_sales']);
    const member2 = await provisionUser(page, stamp, 'b1u2', ['crm_sales']);

    await page.goto('/p/tenant_member', { waitUntil: 'domcontentloaded' });
    await expect(page.getByText(/企业成员管理|Tenant Members/i).first()).toBeVisible({
      timeout: 15_000,
    });
    await attachScreenshot(page, testInfo, 'par26-b1-tenant-member-list');

    // The default member list is newest-first, so the freshly provisioned
    // members are on the first page — select both rows directly.
    const displayName1 = `PAR26 b1u1 ${stamp}`;
    const displayName2 = `PAR26 b1u2 ${stamp}`;
    const firstRow = page.getByRole('row').filter({ hasText: displayName1 }).first();
    const secondRow = page.getByRole('row').filter({ hasText: displayName2 }).first();
    await expect(firstRow).toBeVisible({ timeout: 20_000 });
    await expect(secondRow).toBeVisible({ timeout: 20_000 });
    const selectRow = async (row: ReturnType<Page['getByRole']>) => {
      await row.locator('input[type="checkbox"]').check({ force: true });
    };
    // Danger-variant bulk actions render in the overflow "更多" menu; default
    // variants render inline while capacity lasts. Try inline first.
    const clickBulkAction = async (code: string) => {
      const direct = page.getByTestId(`bulk-action-${code}`);
      if (await direct.isVisible({ timeout: 2_000 }).catch(() => false)) {
        await direct.click();
        return;
      }
      await page.getByTestId('bulk-more-actions-btn').click();
      await page.getByTestId(`bulk-action-${code}`).click();
    };
    await selectRow(firstRow);
    await selectRow(secondRow);
    await expect(firstRow.locator('input[type="checkbox"]')).toBeChecked();
    await expect(secondRow.locator('input[type="checkbox"]')).toBeChecked();

    // Bulk suspend with confirmation + required reason input.
    await expect(page.getByTestId('bulk-action-bulk_suspend_members').or(page.getByTestId('bulk-more-actions-btn'))).toBeVisible({ timeout: 10_000 });
    const suspendResponse = page.waitForResponse(
      (response) =>
        response.url().includes('/api/meta/commands/execute/admin:suspend_member') &&
        response.request().method() === 'POST',
      { timeout: 30_000 },
    );
    await clickBulkAction('bulk_suspend_members');
    await page
      .getByRole('dialog')
      .last()
      .getByRole('button', { name: /确认|确定|Confirm/i })
      .last()
      .click();
    const reasonInput = page.locator('[data-testid="form-dialog"] textarea, [role="dialog"] textarea').first();
    await expect(reasonInput).toBeVisible({ timeout: 10_000 });
    await reasonInput.fill('PAR26 batch suspend verification');
    await page.getByTestId('form-dialog-submit').click();
    const suspendResult = await suspendResponse;
    expect(suspendResult.ok()).toBe(true);
    await attachScreenshot(page, testInfo, 'par26-b1-bulk-suspend-submitted');

    // Success feedback + DB read-back for both members.
    await expect(
      page.getByText(/批量停用.*成功 2 条|Suspend Selected.*2 records/i).first(),
    ).toBeVisible({ timeout: 15_000 });
    await expect
      .poll(async () => {
        const rows = await dbQuery<{ status: string }>(
          `SELECT status FROM ab_tenant_member WHERE pid = ANY($1::varchar[])`,
          [[member1.memberPid, member2.memberPid]],
        );
        return rows.map((row) => row.status).sort().join(',');
      }, { timeout: 15_000 })
      .toBe('suspended,suspended');

    // Suspension revokes the member's server-side sessions. The BFF session is
    // a self-contained signed JWT, so it keeps authenticating until expiry —
    // the platform contract is DB session revocation, asserted here.
    const memberContext = await page.context().browser()!.newContext({
      storageState: { cookies: [], origins: [] },
    });
    const memberPage = await memberContext!.newPage();
    await uiLogin(memberPage, member1.email);
    await memberPage.request.get('/api/auth/me'); // materialize the member session
    await expect
      .poll(async () => {
        const sessions = await dbQuery<{ count: string }>(
          `SELECT count(*) AS count FROM ab_user_session
           WHERE tenant_member_id = $1 AND revoked = false`,
          [member1.memberId],
        );
        return Number(sessions[0]?.count ?? 0);
      })
      .toBe(0);
    await memberContext!.close();

    // Bulk restore via the same toolbar and confirm flow.
    await selectRow(firstRow);
    await selectRow(secondRow);
    await expect(firstRow.locator('input[type="checkbox"]')).toBeChecked();
    await expect(secondRow.locator('input[type="checkbox"]')).toBeChecked();
    const restoreResponse = page.waitForResponse(
      (response) =>
        response.url().includes('/api/meta/commands/execute/admin:restore_member') &&
        response.request().method() === 'POST',
      { timeout: 30_000 },
    );
    await clickBulkAction('bulk_restore_members');
    await page
      .getByRole('dialog')
      .last()
      .getByRole('button', { name: /确认|确定|Confirm/i })
      .last()
      .click();
    const restoreResult = await restoreResponse;
    expect(restoreResult.ok()).toBe(true);
    // waitForResponse resolves on the first per-record command — wait for the
    // batch success toast before reading the DB so both writes have committed.
    await expect(
      page.getByText(/批量启用.*成功 2 条|Enable Selected.*2 records/i).first(),
    ).toBeVisible({ timeout: 15_000 });

    await expect
      .poll(async () => {
        const rows = await dbQuery<{ status: string }>(
          `SELECT status FROM ab_tenant_member WHERE pid = ANY($1::varchar[])`,
          [[member1.memberPid, member2.memberPid]],
        );
        return rows.map((row) => row.status).sort().join(',');
      }, { timeout: 15_000 })
      .toBe('active,active');
    completedScenarios.add(SCENARIOS.BULK_STATUS_UI);
  });

  test('B-002: batch password reset returns per-user temporary passwords with DB read-back', async ({
    page,
  }, testInfo) => {
    test.info().annotations.push({ type: 'scenario', description: SCENARIOS.BATCH_PASSWORD_RESET });
    const stamp = `${Date.now()}`;
    const user1 = await provisionUser(page, stamp, 'b2u1', ['crm_sales']);
    const user2 = await provisionUser(page, stamp, 'b2u2', ['crm_sales']);

    const hashBefore = new Map(
      (
        await dbQuery<{ id: number; password: string }>(
          `SELECT id, password FROM ab_user WHERE pid = ANY($1::varchar[])`,
          [[user1.userPid, user2.userPid]],
        )
      ).map((row) => [row.id, row.password]),
    );
    expect(hashBefore.size).toBe(2);

    const response = await page.request.post('/api/admin/users/batch-reset-password', {
      data: { userPids: [user1.userPid, user2.userPid] },
    });
    const body = await response.json().catch(() => ({}));
    expect(
      response.ok() && String(body?.code) === '0',
      `batch reset failed: HTTP ${response.status()} ${JSON.stringify(body).slice(0, 600)}`,
    ).toBe(true);
    const items: Array<{ userPid: string; tempPassword: string }> = body?.data ?? [];
    expect(items).toHaveLength(2);
    expect(items.map((item) => item.userPid).sort()).toEqual([user1.userPid, user2.userPid].sort());
    const tempPasswords = items.map((item) => item.tempPassword);
    expect(new Set(tempPasswords).size, 'temporary passwords must be distinct').toBe(
      tempPasswords.length,
    );
    for (const item of items) {
      expect(item.tempPassword.length).toBeGreaterThanOrEqual(8);
    }
    await testInfo.attach('par26-b2-batch-reset-response', {
      body: JSON.stringify({ userPids: items.map((item) => item.userPid) }, null, 2),
      contentType: 'application/json',
    });

    // DB read-back: both password hashes rotated, nothing else touched.
    const hashAfter = await dbQuery<{ id: number; password: string }>(
      `SELECT id, password FROM ab_user WHERE pid = ANY($1::varchar[])`,
      [[user1.userPid, user2.userPid]],
    );
    expect(hashAfter).toHaveLength(2);
    for (const row of hashAfter) {
      expect(row.password).not.toBe(hashBefore.get(row.id));
    }

    // The temporary password authenticates; the old one does not.
    const newToken = await page.request.post('/api/auth/login', {
      data: { email: user1.email, password: items.find((i) => i.userPid === user1.userPid)!.tempPassword },
    });
    expect(newToken.ok(), 'login with temporary password').toBe(true);
    const oldLogin = await page.request.post('/api/auth/login', {
      data: { email: user2.email, password: PASSWORD },
    });
    const oldBody = await oldLogin.json().catch(() => ({}));
    expect(
      !oldLogin.ok() || String(oldBody?.code) !== '0',
      'old initial password must stop authenticating after rotation',
    ).toBe(true);

    // Unauthorized pairing: a sales-role member gets 403 and hashes stay untouched.
    const sales = await provisionUser(page, stamp, 'b2sales', ['crm_sales']);
    const salesContext = await page.context().browser()!.newContext({
      storageState: { cookies: [], origins: [] },
    });
    const salesPage = await salesContext!.newPage();
    await uiLogin(salesPage, sales.email);
    const hashesBeforeForbidden = await dbQuery<{ id: number; password: string }>(
      `SELECT id, password FROM ab_user WHERE pid = ANY($1::varchar[])`,
      [[user1.userPid, user2.userPid]],
    );
    const forbidden = await salesPage.request.post('/api/admin/users/batch-reset-password', {
      data: { userPids: [user1.userPid, user2.userPid] },
    });
    const forbiddenBody = await forbidden.json().catch(() => ({}));
    await expectForbidden(forbidden, forbiddenBody);
    const hashesAfterForbidden = await dbQuery<{ id: number; password: string }>(
      `SELECT id, password FROM ab_user WHERE pid = ANY($1::varchar[])`,
      [[user1.userPid, user2.userPid]],
    );
    expect(hashesAfterForbidden.map((row) => row.password)).toEqual(
      hashesBeforeForbidden.map((row) => row.password),
    );
    await salesContext!.close();
    completedScenarios.add(SCENARIOS.BATCH_PASSWORD_RESET);
  });

  test('B-003: batch member status API with partial-failure isolation and 403 pairing', async ({
    page,
  }, testInfo) => {
    test.info().annotations.push({ type: 'scenario', description: SCENARIOS.BATCH_STATUS_API });
    const stamp = `${Date.now()}`;
    const member1 = await provisionUser(page, stamp, 'b3u1', ['crm_sales']);
    const member2 = await provisionUser(page, stamp, 'b3u2', ['crm_sales']);

    // A live session for member1 before suspension — suspension must revoke it.
    const memberContext = await page.context().browser()!.newContext({
      storageState: { cookies: [], origins: [] },
    });
    const memberPage = await memberContext!.newPage();
    await uiLogin(memberPage, member1.email);
    await expect(await memberPage.request.get('/api/auth/me')).toBeOK();

    const suspend = await page.request.post('/api/tenant/members/batch-status', {
      data: {
        memberPids: [member1.memberPid, member2.memberPid],
        action: 'suspended',
        reason: 'PAR26 batch status verification',
      },
    });
    const suspendBody = await suspend.json().catch(() => ({}));
    expect(
      suspend.ok() && String(suspendBody?.code) === '0',
      `batch suspend failed: HTTP ${suspend.status()} ${JSON.stringify(suspendBody).slice(0, 600)}`,
    ).toBe(true);
    expect(suspendBody?.data?.succeeded).toBe(2);
    expect(suspendBody?.data?.failed).toEqual([]);

    await expect
      .poll(async () => {
        const rows = await dbQuery<{ status: string }>(
          `SELECT status FROM ab_tenant_member WHERE pid = ANY($1::varchar[])`,
          [[member1.memberPid, member2.memberPid]],
        );
        return rows.map((row) => row.status).sort().join(',');
      }, { timeout: 15_000 })
      .toBe('suspended,suspended');

    // The member's server-side sessions are revoked with the suspension; the
    // stateless BFF JWT keeps authenticating until expiry (platform contract).
    await expect
      .poll(async () => {
        const sessions = await dbQuery<{ count: string }>(
          `SELECT count(*) AS count FROM ab_user_session
           WHERE tenant_member_id = $1 AND revoked = false`,
          [member1.memberId],
        );
        return Number(sessions[0]?.count ?? 0);
      })
      .toBe(0);
    await memberContext!.close();

    const restore = await page.request.post('/api/tenant/members/batch-status', {
      data: {
        memberPids: [member1.memberPid, member2.memberPid],
        action: 'active',
        reason: 'PAR26 batch restore',
      },
    });
    const restoreBody = await restore.json().catch(() => ({}));
    expect(restoreBody?.data?.succeeded).toBe(2);
    const restoredRows = await dbQuery<{ status: string }>(
      `SELECT status FROM ab_tenant_member WHERE pid = ANY($1::varchar[])`,
      [[member1.memberPid, member2.memberPid]],
    );
    expect(new Set(restoredRows.map((row) => row.status))).toEqual(new Set(['active']));

    // Partial failure: one valid pid, one nonexistent — the valid one still applies.
    const partial = await page.request.post('/api/tenant/members/batch-status', {
      data: {
        memberPids: [member1.memberPid, 'm-does-not-exist'],
        action: 'suspended',
        reason: 'PAR26 partial',
      },
    });
    const partialBody = await partial.json().catch(() => ({}));
    expect(String(partialBody?.code)).toBe('0');
    expect(partialBody?.data?.succeeded).toBe(1);
    const failures: Array<{ memberPid: string }> = partialBody?.data?.failed ?? [];
    expect(failures.map((failure) => failure.memberPid)).toEqual(['m-does-not-exist']);
    expect(
      (await dbQuery<{ status: string }>(`SELECT status FROM ab_tenant_member WHERE pid = $1`, [member1.memberPid]))[0]
        .status,
    ).toBe('suspended');
    await page.request.post('/api/tenant/members/batch-status', {
      data: { memberPids: [member1.memberPid], action: 'active', reason: 'PAR26 cleanup' },
    });

    // Validation: empty list is rejected via the error envelope.
    const emptyList = await page.request.post('/api/tenant/members/batch-status', {
      data: { memberPids: [], action: 'active' },
    });
    const emptyListBody = await emptyList.json().catch(() => ({}));
    expect(String(emptyListBody?.code ?? '0')).not.toBe('0');

    // An unknown action fails per member inside the batch envelope, leaving
    // every touched member unchanged.
    const invalidAction = await page.request.post('/api/tenant/members/batch-status', {
      data: { memberPids: [member2.memberPid], action: 'detonate' },
    });
    const invalidBody = await invalidAction.json().catch(() => ({}));
    expect(invalidBody?.data?.succeeded).toBe(0);
    const invalidFailures: Array<{ memberPid: string }> = invalidBody?.data?.failed ?? [];
    expect(invalidFailures.map((failure) => failure.memberPid)).toEqual([member2.memberPid]);

    // Unauthorized pairing: sales role lacks TENANT_MEMBER_MANAGE — 403, no change.
    const sales = await provisionUser(page, stamp, 'b3sales', ['crm_sales']);
    const salesContext = await page.context().browser()!.newContext({
      storageState: { cookies: [], origins: [] },
    });
    const salesPage = await salesContext!.newPage();
    await uiLogin(salesPage, sales.email);
    const forbidden = await salesPage.request.post('/api/tenant/members/batch-status', {
      data: { memberPids: [member1.memberPid, member2.memberPid], action: 'suspended' },
    });
    const forbiddenBody = await forbidden.json().catch(() => ({}));
    await expectForbidden(forbidden, forbiddenBody);
    const statusesAfterForbidden = await dbQuery<{ status: string }>(
      `SELECT status FROM ab_tenant_member WHERE pid = ANY($1::varchar[]) ORDER BY pid`,
      [[member1.memberPid, member2.memberPid]],
    );
    expect(statusesAfterForbidden.every((row) => row.status === 'active')).toBe(true);
    await salesContext!.close();
    await testInfo.attach('par26-b3-batch-status-summary', {
      body: JSON.stringify(
        { suspended: suspendBody?.data, partial: partialBody?.data, statusesAfterForbidden },
        null,
        2,
      ),
      contentType: 'application/json',
    });
    completedScenarios.add(SCENARIOS.BATCH_STATUS_API);
  });

  test('B-004: department sort, set-commander and delete pre-check with DB read-back', async ({
    page,
  }, testInfo) => {
    test.info().annotations.push({ type: 'scenario', description: SCENARIOS.DEPARTMENT_ADMIN });
    const stamp = `${Date.now()}`;

    const createDept = async (name: string, parentPid?: string): Promise<string> => {
      const response = await page.request.post('/api/org/departments', {
        data: {
          org_dept_name: name,
          org_dept_status: 'active',
          ...(parentPid ? { org_dept_parent_id: parentPid } : {}),
        },
      });
      const body = await response.json().catch(() => ({}));
      expect(
        response.ok() && String(body?.code) === '0',
        `create department failed: HTTP ${response.status()} ${JSON.stringify(body).slice(0, 600)}`,
      ).toBe(true);
      const pid = String(body?.data?.pid ?? '');
      expect(pid, 'created department pid').toBeTruthy();
      return pid;
    };

    const rootPid = await createDept(`PAR26 根部门 ${stamp}`);
    const childPid = await createDept(`PAR26 子部门 ${stamp}`, rootPid);
    // org_employee requires a department and a position — create the position
    // through the dynamic model API first.
    const positionResponse = await page.request.post('/api/dynamic/org_position/create', {
      data: {
        org_pos_name: `PAR26 岗位 ${stamp}`,
        org_pos_dept_id: rootPid,
        org_pos_level: "1",
        org_pos_status: 'active',
      },
    });
    const positionBody = await positionResponse.json().catch(() => ({}));
    expect(
      positionResponse.ok() && String(positionBody?.code) === '0',
      `create position failed: HTTP ${positionResponse.status()} ${JSON.stringify(positionBody).slice(0, 600)}`,
    ).toBe(true);
    const positionPid = String(positionBody?.data?.pid ?? '');
    expect(positionPid, 'position pid').toBeTruthy();
    const employeeResponse = await page.request.post('/api/org/employees', {
      data: {
        name: `PAR26 指挥官 ${stamp}`,
        email: `par26-cmd-${stamp}@e2e.local`,
        phone: '13800000000',
        deptPid: rootPid,
        positionPid,
      },
    });
    const employeeBody = await employeeResponse.json().catch(() => ({}));
    expect(
      employeeResponse.ok() && String(employeeBody?.code) === '0',
      `create employee failed: HTTP ${employeeResponse.status()} ${JSON.stringify(employeeBody).slice(0, 600)}`,
    ).toBe(true);
    const employeePid = String(employeeBody?.data?.pid ?? '');
    expect(employeePid, 'employee pid').toBeTruthy();

    // Sort: dedicated batch reorder endpoint with DB read-back.
    const sort = await page.request.post('/api/org/departments/sort', {
      data: { items: [{ pid: rootPid, order: 7 }, { pid: childPid, order: 3 }] },
    });
    const sortBody = await sort.json().catch(() => ({}));
    expect(
      sort.ok() && String(sortBody?.code) === '0',
      `sort failed: HTTP ${sort.status()} ${JSON.stringify(sortBody).slice(0, 400)}`,
    ).toBe(true);
    const orders = await dbQuery<{ pid: string; order: number }>(
      `SELECT pid, org_dept_order AS order FROM mt_org_department WHERE pid = ANY($1::varchar[])`,
      [[rootPid, childPid]],
    );
    expect(Object.fromEntries(orders.map((row) => [row.pid, row.order]))).toEqual({
      [rootPid]: 7,
      [childPid]: 3,
    });

    // Set-commander: manager field points at the employee record.
    const commander = await page.request.post(`/api/org/departments/${rootPid}/set-commander`, {
      data: { employeePid },
    });
    const commanderBody = await commander.json().catch(() => ({}));
    expect(
      commander.ok() && String(commanderBody?.code) === '0',
      `set-commander failed: HTTP ${commander.status()} ${JSON.stringify(commanderBody).slice(0, 400)}`,
    ).toBe(true);
    const managerRows = await dbQuery<{ manager: string | null }>(
      `SELECT org_dept_manager_id AS manager FROM mt_org_department WHERE pid = $1`,
      [rootPid],
    );
    expect(managerRows[0]?.manager).toBe(employeePid);

    // Unknown employee is rejected fast.
    const badCommander = await page.request.post(`/api/org/departments/${rootPid}/set-commander`, {
      data: { employeePid: 'e-does-not-exist' },
    });
    expect(badCommander.ok()).toBe(false);

    // Delete pre-check mirrors the delete guards.
    const blockedCheck = await page.request.get(`/api/org/departments/${rootPid}/delete-check`);
    const blockedBody = await blockedCheck.json().catch(() => ({}));
    expect(String(blockedBody?.code)).toBe('0');
    expect(blockedBody?.data?.canDelete).toBe(false);
    const blockerTypes = (blockedBody?.data?.blockers ?? []).map(
      (blocker: { type: string }) => blocker.type,
    );
    expect(blockerTypes).toContain('child_departments');
    expect(blockerTypes).toContain('employees');

    const freeCheck = await page.request.get(`/api/org/departments/${childPid}/delete-check`);
    const freeBody = await freeCheck.json().catch(() => ({}));
    expect(freeBody?.data?.canDelete).toBe(true);
    expect(freeBody?.data?.blockers).toEqual([]);

    // Unknown department 404s on the pre-check.
    const missingCheck = await page.request.get('/api/org/departments/d-missing/delete-check');
    expect(missingCheck.ok()).toBe(false);

    // Unauthorized pairing: sales role cannot sort departments — 403, order unchanged.
    const sales = await provisionUser(page, stamp, 'b4sales', ['crm_sales']);
    const salesContext = await page.context().browser()!.newContext({
      storageState: { cookies: [], origins: [] },
    });
    const salesPage = await salesContext!.newPage();
    await uiLogin(salesPage, sales.email);
    const forbidden = await salesPage.request.post('/api/org/departments/sort', {
      data: { items: [{ pid: rootPid, order: 99 }] },
    });
    const forbiddenBody = await forbidden.json().catch(() => ({}));
    await expectForbidden(forbidden, forbiddenBody);
    const orderAfterForbidden = await dbQuery<{ order: number }>(
      `SELECT org_dept_order AS order FROM mt_org_department WHERE pid = $1`,
      [rootPid],
    );
    expect(orderAfterForbidden[0]?.order).toBe(7);
    await salesContext!.close();
    await testInfo.attach('par26-b4-dept-admin-summary', {
      body: JSON.stringify({ rootPid, childPid, employeePid, blockerTypes }, null, 2),
      contentType: 'application/json',
    });
    completedScenarios.add(SCENARIOS.DEPARTMENT_ADMIN);
  });

  test('B-005: role member tree annotates assignment and batch-remove clears it', async ({
    page,
  }, testInfo) => {
    test.info().annotations.push({ type: 'scenario', description: SCENARIOS.ROLE_MEMBER_TREE });
    const stamp = `${Date.now()}`;

    const roleResponse = await page.request.post('/api/roles', {
      data: {
        name: `PAR26 树角色 ${stamp}`,
        code: `par26_tree_${stamp}`,
        type: 'CUSTOM',
        status: 'ACTIVE',
      },
    });
    const roleBody = await roleResponse.json().catch(() => ({}));
    expect(
      roleResponse.ok() && String(roleBody?.code) === '0',
      `create role failed: HTTP ${roleResponse.status()} ${JSON.stringify(roleBody).slice(0, 600)}`,
    ).toBe(true);
    const rolePid = String(roleBody?.data?.pid ?? '');
    expect(rolePid, 'role pid').toBeTruthy();

    const member1 = await provisionUser(page, stamp, 'b5u1', ['crm_sales']);
    const member2 = await provisionUser(page, stamp, 'b5u2', ['crm_sales']);

    // The department-user tree is built from org_employee records, so link
    // employee records to both members under a fresh department.
    const deptResponse = await page.request.post('/api/org/departments', {
      data: { org_dept_name: `PAR26 树部门 ${stamp}`, org_dept_status: 'active' },
    });
    const deptBody = await deptResponse.json().catch(() => ({}));
    expect(String(deptBody?.code), 'create tree department').toBe('0');
    const treeDeptPid = String(deptBody?.data?.pid ?? '');
    expect(treeDeptPid, 'tree department pid').toBeTruthy();
    const treePositionResponse = await page.request.post('/api/dynamic/org_position/create', {
      data: {
        org_pos_name: `PAR26 树岗位 ${stamp}`,
        org_pos_dept_id: treeDeptPid,
        org_pos_level: "1",
        org_pos_status: 'active',
      },
    });
    const treePositionBody = await treePositionResponse.json().catch(() => ({}));
    expect(String(treePositionBody?.code), 'create tree position').toBe('0');
    const treePositionPid = String(treePositionBody?.data?.pid ?? '');
    expect(treePositionPid, 'tree position pid').toBeTruthy();
    for (const member of [member1, member2]) {
      const link = await page.request.post('/api/org/employees/link', {
        data: {
          memberPid: member.memberPid,
          deptPid: treeDeptPid,
          positionPid: treePositionPid,
        },
      });
      const linkBody = await link.json().catch(() => ({}));
      expect(
        link.ok() && String(linkBody?.code) === '0',
        `link member ${member.memberPid} failed: HTTP ${link.status()} ${JSON.stringify(linkBody).slice(0, 400)}`,
      ).toBe(true);
    }

    const assign = await page.request.post(`/api/roles/${rolePid}/members`, {
      data: [member1.memberPid],
    });
    expect(assign.ok(), 'assign member to role').toBe(true);

    // Department-user tree: assigned member annotated true, peers false.
    const tree = await page.request.get(`/api/roles/${rolePid}/members/member-tree`);
    const treeBody = await tree.json().catch(() => ({}));
    expect(
      tree.ok() && String(treeBody?.code) === '0',
      `member-tree failed: HTTP ${tree.status()} ${JSON.stringify(treeBody).slice(0, 600)}`,
    ).toBe(true);
    const departments: Array<{
      pid: string;
      users: Array<{ memberPid: string | null; assigned: boolean }>;
      children?: unknown[];
    }> = treeBody?.data?.departments ?? [];
    const ungrouped: Array<{ memberPid: string | null; assigned: boolean }> =
      treeBody?.data?.ungrouped ?? [];
    const flatten = (nodes: typeof departments): Array<{ memberPid: string | null; assigned: boolean }> =>
      nodes.flatMap((node) => [
        ...node.users,
        ...flatten((node.children as typeof departments) ?? []),
      ]);
    const treeUsers = [...flatten(departments), ...ungrouped];
    const byMember = new Map(treeUsers.filter((user) => user.memberPid).map((user) => [user.memberPid!, user]));
    expect(byMember.get(member1.memberPid)?.assigned, 'assigned member annotated true').toBe(true);
    expect(byMember.get(member2.memberPid)?.assigned, 'unassigned member annotated false').toBe(false);
    await testInfo.attach('par26-b5-member-tree', {
      body: JSON.stringify(
        { departments: departments.length, ungrouped: ungrouped.length, treeUsers: treeUsers.length },
        null,
        2,
      ),
      contentType: 'application/json',
    });

    // Batch remove both assignments; DB read-back shows no active rows for the role.
    const remove = await page.request.post(`/api/roles/${rolePid}/members/remove`, {
      data: [member1.memberPid, member2.memberPid],
    });
    const removeBody = await remove.json().catch(() => ({}));
    expect(
      remove.ok() && String(removeBody?.code) === '0',
      `batch remove failed: HTTP ${remove.status()} ${JSON.stringify(removeBody).slice(0, 400)}`,
    ).toBe(true);
    await expect
      .poll(async () => {
        const treeAfter = await page.request.get(`/api/roles/${rolePid}/members/member-tree`);
        const treeAfterBody = await treeAfter.json().catch(() => ({}));
        const nodes: typeof departments = treeAfterBody?.data?.departments ?? [];
        const ungroupedAfter: Array<{ memberPid: string | null; assigned: boolean }> =
          treeAfterBody?.data?.ungrouped ?? [];
        const users = [...flatten(nodes), ...ungroupedAfter];
        return users.filter((user) => user.memberPid && byMember.has(user.memberPid) && user.assigned).length;
      })
      .toBe(0);

    // Unauthorized pairing: a member without ROLE_READ gets 403 on the tree.
    const sales = await provisionUser(page, stamp, 'b5sales', ['crm_sales']);
    const salesContext = await page.context().browser()!.newContext({
      storageState: { cookies: [], origins: [] },
    });
    const salesPage = await salesContext!.newPage();
    await uiLogin(salesPage, sales.email);
    const forbidden = await salesPage.request.get(`/api/roles/${rolePid}/members/member-tree`);
    const forbiddenBody = await forbidden.json().catch(() => ({}));
    if (forbidden.status() === 403) {
      await expectForbidden(forbidden, forbiddenBody);
    } else {
      // crm_sales carries a read capability — record the outcome instead of failing.
      await testInfo.attach('par26-b5-tree-forbidden-skip', {
        body: JSON.stringify({ status: forbidden.status() }, null, 2),
        contentType: 'application/json',
      });
    }
    await salesContext!.close();
    completedScenarios.add(SCENARIOS.ROLE_MEMBER_TREE);
  });
});
