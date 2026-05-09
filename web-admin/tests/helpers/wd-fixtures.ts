/**
 * wd-fixtures.ts — Playwright helper for workflow-demo runtime E2E tests.
 *
 * Provides deterministic setup helpers for:
 *  - Role-based login (admin / wd_manager / wd_hr)
 *  - Ad-hoc applicant creation via admin API
 *  - Leave balance seeding via the wd:create_leave_balance command
 *  - Leave request submission via sidebar → form UI
 *  - Task processing (approve/reject) via Task Center UI
 *
 * Red lines enforced here:
 *  - No page.goto() to non-login business URLs
 *  - No multi-path response fallback — field names are exact
 *  - No waitForTimeout()
 *  - Throws with a descriptive error when expected data is absent
 */

import { expect } from '@playwright/test';
import type { APIRequestContext, Page } from '@playwright/test';

const BASE_URL = process.env.PLAYWRIGHT_BASE_URL ?? process.env.BASE_URL ?? 'http://localhost:5173';
const BACKEND_URL = process.env.BACKEND_URL ?? `http://localhost:${process.env.BE_PORT ?? '6443'}`;

// ---------------------------------------------------------------------------
// loginAs
// ---------------------------------------------------------------------------

/**
 * POST /api/auth/login and return the JWT string.
 * Throws immediately if the response does not contain a jwt field.
 */
export async function loginAs(
  api: APIRequestContext,
  email: string,
  password: string,
): Promise<string> {
  const resp = await api.post(`${BACKEND_URL}/api/auth/login`, {
    data: { email, password },
    headers: { 'Content-Type': 'application/json' },
  });

  if (!resp.ok()) {
    throw new Error(
      `loginAs(${email}): HTTP ${resp.status()} — login endpoint returned non-2xx`,
    );
  }

  const body = await resp.json();
  const jwt: unknown = body?.data?.jwt;
  if (typeof jwt !== 'string' || jwt.trim() === '') {
    throw new Error(
      `loginAs(${email}): response.data.jwt is absent or empty. ` +
        `Full response: ${JSON.stringify(body)}`,
    );
  }

  return jwt;
}

// ---------------------------------------------------------------------------
// ensureRoleUsers
// ---------------------------------------------------------------------------

/**
 * Attempt login as wd_manager@example.com and wd_hr@example.com.
 * If either login fails (first run after reset), create the user via admin API
 * (POST /api/admin/users) with role code wd_manager / wd_hr respectively,
 * then login again. The helper is idempotent: subsequent calls find the user
 * already present and skip creation.
 */
export async function ensureRoleUsers(
  api: APIRequestContext,
): Promise<{ managerToken: string; hrToken: string }> {
  // Manager and HR need tenant_admin in addition to their domain role so that
  // they can access the BPM Task Center (requires system.process.execute permission).
  // The wd_manager / wd_hr roles alone only grant leave-domain permissions.
  const managerToken = await ensureUser(api, {
    email: 'wd_manager@example.com',
    displayName: 'WD Manager',
    password: 'Test2026x',
    roleCode: 'wd_manager',
    extraRoleCodes: ['tenant_admin'],
  });

  const hrToken = await ensureUser(api, {
    email: 'wd_hr@example.com',
    displayName: 'WD HR',
    password: 'Test2026x',
    roleCode: 'wd_hr',
    extraRoleCodes: ['tenant_admin'],
  });

  return { managerToken, hrToken };
}

async function ensureUser(
  api: APIRequestContext,
  opts: { email: string; displayName: string; password: string; roleCode: string; extraRoleCodes?: string[] },
): Promise<string> {
  // Try login first
  const loginResp = await api.post(`${BACKEND_URL}/api/auth/login`, {
    data: { email: opts.email, password: opts.password },
    headers: { 'Content-Type': 'application/json' },
  });

  const loginBody = await loginResp.json();
  const existingJwt: unknown = loginBody?.data?.jwt;
  if (typeof existingJwt === 'string' && existingJwt.trim() !== '') {
    // User exists — ensure extra roles are assigned (idempotent: re-adding is safe).
    // This handles the case where the user was created in a previous test run without
    // extraRoleCodes. For example, wd_manager needs tenant_admin to access the BPM
    // Task Center (system.process.execute permission is in tenant_admin only).
    if (opts.extraRoleCodes && opts.extraRoleCodes.length > 0) {
      const adminToken = await loginAs(api, 'admin@example.com', 'Test2026x');
      await ensureExtraRoles(api, adminToken, opts.email, opts.roleCode, opts.extraRoleCodes);
    }
    // Re-login to get a fresh JWT that includes the newly-assigned role permissions.
    return loginAs(api, opts.email, opts.password);
  }

  // User missing — create via admin API
  const adminToken = await loginAs(api, 'admin@example.com', 'Test2026x');

  const createResp = await api.post(`${BACKEND_URL}/api/admin/users`, {
    data: {
      email: opts.email,
      displayName: opts.displayName,
      initialPassword: opts.password,
      roleCodes: [opts.roleCode, ...(opts.extraRoleCodes ?? [])],
      sendInviteEmail: false,
    },
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${adminToken}`,
    },
  });

  if (!createResp.ok()) {
    // Concurrency: another worker may have just created this user between our
    // login attempt and this create. Retry login once before giving up.
    const retryLoginResp = await api.post(`${BACKEND_URL}/api/auth/login`, {
      data: { email: opts.email, password: opts.password },
      headers: { 'Content-Type': 'application/json' },
    });
    if (retryLoginResp.ok()) {
      const retryBody = await retryLoginResp.json();
      const retryJwt: unknown = retryBody?.data?.jwt;
      if (typeof retryJwt === 'string' && retryJwt.trim() !== '') {
        return retryJwt;
      }
    }
    const errBody = await createResp.text();
    throw new Error(
      `ensureUser(${opts.email}): admin create failed with HTTP ${createResp.status()}: ${errBody}`,
    );
  }

  // Login with newly-created user
  return loginAs(api, opts.email, opts.password);
}

/**
 * Ensure a user (identified by their existing domain roleCode) also has every role in
 * extraRoleCodes. Idempotent: re-adding is safe (server ignores duplicates).
 *
 * Strategy:
 *   1. GET /api/roles?keyword={roleCode} → find domain role PID
 *   2. GET /api/roles/{domainRolePid}/members → find the user's memberPid by email
 *   3. For each extraRoleCode:
 *       GET /api/roles?keyword={extraRoleCode} → find extra role PID
 *       POST /api/roles/{extraRolePid}/members with [memberPid]
 */
async function ensureExtraRoles(
  api: APIRequestContext,
  adminToken: string,
  userEmail: string,
  domainRoleCode: string,
  extraRoleCodes: string[],
): Promise<void> {
  const headers = { 'Content-Type': 'application/json', Authorization: `Bearer ${adminToken}` };

  // Step 1: find the domain role PID
  const rolesResp = await api.get(
    `${BACKEND_URL}/api/roles?keyword=${encodeURIComponent(domainRoleCode)}&pageNum=1&pageSize=50`,
    { headers },
  );
  const rolesBody = await rolesResp.json();
  const domainRole = (rolesBody?.data?.records ?? []).find(
    (r: Record<string, unknown>) => r.code === domainRoleCode,
  ) as Record<string, unknown> | undefined;
  if (!domainRole) {
    throw new Error(`ensureExtraRoles: domain role "${domainRoleCode}" not found`);
  }
  const domainRolePid = domainRole.pid as string;

  // Step 2: get the user's memberPid from the domain role's member list
  const membersResp = await api.get(
    `${BACKEND_URL}/api/roles/${domainRolePid}/members?pageNum=1&pageSize=200`,
    { headers },
  );
  const membersBody = await membersResp.json();
  const memberRecord = (membersBody?.data?.records ?? []).find(
    (m: Record<string, unknown>) => m.email === userEmail,
  ) as Record<string, unknown> | undefined;
  if (!memberRecord) {
    throw new Error(
      `ensureExtraRoles: user "${userEmail}" not found in members of role "${domainRoleCode}"`,
    );
  }
  const memberPid = memberRecord.memberPid as string;

  // Step 3: add member to each extra role
  for (const extraRoleCode of extraRoleCodes) {
    const extraRolesResp = await api.get(
      `${BACKEND_URL}/api/roles?keyword=${encodeURIComponent(extraRoleCode)}&pageNum=1&pageSize=50`,
      { headers },
    );
    const extraRolesBody = await extraRolesResp.json();
    const extraRole = (extraRolesBody?.data?.records ?? []).find(
      (r: Record<string, unknown>) => r.code === extraRoleCode,
    ) as Record<string, unknown> | undefined;
    if (!extraRole) {
      throw new Error(`ensureExtraRoles: extra role "${extraRoleCode}" not found`);
    }
    const extraRolePid = extraRole.pid as string;

    // idempotent: adding an already-assigned member is a no-op
    await api.post(`${BACKEND_URL}/api/roles/${extraRolePid}/members`, {
      data: [memberPid],
      headers,
    });
  }
}

// ---------------------------------------------------------------------------
// createLeaveApplicant
// ---------------------------------------------------------------------------

/**
 * Create a new user with role wd_employee and return their userId, email, and token.
 * The email is unique across runs via a timestamp suffix.
 */
export async function createLeaveApplicant(
  api: APIRequestContext,
  adminToken: string,
  prefix: string,
): Promise<{ userId: string; email: string; displayName: string; token: string }> {
  const ts = Date.now();
  const email = `${prefix}_${ts}@e2e.local`;
  const password = 'Test2026x';
  const displayName = `${prefix} ${ts}`;

  const createResp = await api.post(`${BACKEND_URL}/api/admin/users`, {
    data: {
      email,
      displayName,
      initialPassword: password,
      // Use tenant_admin so the applicant has all model + page permissions
      // needed to submit a leave via UI. Drools routing depends on {days,type},
      // not the applicant's role, so this doesn't affect routing behavior.
      roleCodes: ['tenant_admin'],
      sendInviteEmail: false,
    },
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${adminToken}`,
    },
  });

  if (!createResp.ok()) {
    const errBody = await createResp.text();
    throw new Error(
      `createLeaveApplicant(${email}): HTTP ${createResp.status()}: ${errBody}`,
    );
  }

  const createBody = await createResp.json();
  const userId: unknown = createBody?.data?.userPid;
  if (typeof userId !== 'string' || userId.trim() === '') {
    throw new Error(
      `createLeaveApplicant(${email}): response.data.userPid absent. ` +
        `Full response: ${JSON.stringify(createBody)}`,
    );
  }

  const token = await loginAs(api, email, password);

  return { userId, email, displayName, token };
}

// ---------------------------------------------------------------------------
// setLeaveBalance
// ---------------------------------------------------------------------------

/**
 * Seed a leave balance record for a user by executing the wd:create_leave_balance
 * command via POST /api/meta/commands/execute/wd:create_leave_balance.
 *
 * The command requires wd.leave_balance.manage permission — use adminToken.
 *
 * @param userId  The user's PID (userPid from createLeaveApplicant)
 * @param days    Annual remaining leave days to seed
 */
export async function setLeaveBalance(
  api: APIRequestContext,
  adminToken: string,
  userId: string,
  days: number,
): Promise<void> {
  const currentYear = new Date().getFullYear();

  const resp = await api.post(
    `${BACKEND_URL}/api/meta/commands/execute/wd:create_leave_balance`,
    {
      data: {
        payload: {
          wd_bal_employee: userId,
          wd_bal_year: currentYear,
          wd_bal_annual_remaining: days,
          wd_bal_sick_used: 0,
        },
      },
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${adminToken}`,
      },
    },
  );

  if (!resp.ok()) {
    const errBody = await resp.text();
    throw new Error(
      `setLeaveBalance(userId=${userId}, days=${days}): HTTP ${resp.status()}: ${errBody}`,
    );
  }

  const body = await resp.json();
  // Command execute ApiResponse convention: code === "0" means success.
  const code: unknown = body?.code;
  if (code !== '0') {
    throw new Error(
      `setLeaveBalance command failed: ${JSON.stringify(body)}`,
    );
  }
}

// ---------------------------------------------------------------------------
// submitLeaveRequest
// ---------------------------------------------------------------------------

/**
 * Create a leave request via the wd:create_leave_request command API.
 *
 * This is a test-fixture setup helper — it does NOT navigate the UI form.
 * The specs that test the approval workflow lifecycle (R1/R2/R4/R5) only need
 * a leave request to exist; the form-fill is pure fixture noise.
 *
 * API contract confirmed via manual curl against http://localhost:16443:
 *   Step 1 — create draft:
 *     POST /api/meta/commands/execute/wd:create_leave_request
 *     Body: { payload: { wd_req_applicant, wd_req_type, wd_req_start_date,
 *                         wd_req_start_slot, wd_req_end_date, wd_req_end_slot,
 *                         wd_req_days, wd_req_reason } }
 *     Response: { code: "0", data: { data: { recordId: string } } }
 *   Step 2 — submit to start BPM process:
 *     POST /api/meta/commands/execute/wd:submit_leave_request
 *     Body: { targetRecordId: "<recordId>", payload: { wd_req_days, wd_req_type,
 *              wd_req_applicant, wd_req_start_slot, wd_req_end_slot } }
 *     Response: { code: "0", data: { data: { recordId: string } } }
 *   Both recordId paths are at body.data.data.recordId — no fallback chain.
 *
 * @param userId     The applicant's userPid (from createLeaveApplicant.userId)
 * @param token      The applicant's JWT (from createLeaveApplicant.token). Required because
 *                   page.request in a fresh browser context carries cookies, not a JWT Bearer
 *                   header. The backend's command endpoint requires Authorization: Bearer <jwt>.
 * @param days       Number of leave days requested
 * @param type       Dict value from wd_leave_type (e.g. "sick", "annual")
 * @param reason     Free-text reason string
 * @param startDate  ISO date string (YYYY-MM-DD); defaults to today
 * @param endDate    ISO date string (YYYY-MM-DD); defaults to startDate + (days-1) days
 * @param startSlot  Session code from wd_leave_day_slot (defaults to "AM")
 * @param endSlot    Session code from wd_leave_day_slot (defaults to "PM")
 */
export async function submitLeaveRequest(
  page: Page,
  input: {
    userId: string;
    token: string;
    days: number;
    type: string;
    reason: string;
    startDate?: string;
    endDate?: string;
    startSlot?: string;
    endSlot?: string;
  },
): Promise<{ recordId: string }> {
  const today = new Date();
  const fmt = (d: Date) => d.toISOString().slice(0, 10);
  const start = input.startDate ?? fmt(today);
  const end =
    input.endDate ?? fmt(new Date(today.getTime() + (input.days - 1) * 86_400_000));
  const startSlot = input.startSlot ?? 'AM';
  const endSlot = input.endSlot ?? 'PM';

  const resp = await page.request.post(
    `${BACKEND_URL}/api/meta/commands/execute/wd:create_leave_request`,
    {
      data: {
        payload: {
          wd_req_applicant: input.userId,
          wd_req_type: input.type,
          wd_req_start_date: start,
          wd_req_start_slot: startSlot,
          wd_req_end_date: end,
          wd_req_end_slot: endSlot,
          wd_req_days: input.days,
          wd_req_reason: input.reason,
        },
      },
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${input.token}`,
      },
    },
  );

  if (!resp.ok()) {
    const err = await resp.text();
    throw new Error(`submitLeaveRequest: HTTP ${resp.status()}: ${err}`);
  }

  const body = await resp.json();
  if (body?.code !== '0') {
    throw new Error(`submitLeaveRequest (create): command failed: ${JSON.stringify(body)}`);
  }

  // Exact path confirmed: body.data.data.recordId
  const recordId: unknown = body.data.data.recordId;
  if (typeof recordId !== 'string' || recordId.trim() === '') {
    throw new Error(
      `submitLeaveRequest: expected body.data.data.recordId (string) but got: ` +
        `${JSON.stringify(body.data)}. Full response: ${JSON.stringify(body)}`,
    );
  }

  // Step 2: submit the draft to start the BPM approval process.
  // wd:submit_leave_request is a state_transition command — it requires `targetRecordId`
  // (not inside payload) to identify the record to update, plus workflow variables in payload.
  // Contract: { targetRecordId: "<pid>", payload: { wd_req_days, wd_req_type,
  //   wd_req_applicant, wd_req_start_slot, wd_req_end_slot } }
  const submitResp = await page.request.post(
    `${BACKEND_URL}/api/meta/commands/execute/wd:submit_leave_request`,
    {
      data: {
        targetRecordId: recordId,
        payload: {
          wd_req_applicant: input.userId,
          wd_req_type: input.type,
          wd_req_days: input.days,
          wd_req_start_slot: startSlot,
          wd_req_end_slot: endSlot,
        },
      },
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${input.token}`,
      },
    },
  );

  if (!submitResp.ok()) {
    const err = await submitResp.text();
    throw new Error(`submitLeaveRequest (submit): HTTP ${submitResp.status()}: ${err}`);
  }

  const submitBody = await submitResp.json();
  if (submitBody?.code !== '0') {
    throw new Error(`submitLeaveRequest (submit): command failed: ${JSON.stringify(submitBody)}`);
  }

  return { recordId };
}

// ---------------------------------------------------------------------------
// processTask
// ---------------------------------------------------------------------------

/**
 * Navigate to the BPM Task Center via the sidebar, find the task row matching
 * taskKey (matched against data-testid="task-business-key" or task name), open
 * the action menu, click approve/reject, fill the optional comment, and confirm.
 *
 * taskKey is matched against the business key column (data-testid="task-business-key").
 */
export async function processTask(
  page: Page,
  taskKey: string,
  action: 'approve' | 'reject',
  comment?: string,
): Promise<void> {
  // The task center page loads data from /api/bpm/workbench (not /api/bpm/tasks/todo).
  // Wait for the workbench response so the table is populated before we look for the task row.
  const workbenchRespPromise = page.waitForResponse(
    (r) => r.url().includes('/api/bpm/workbench') && r.request().method() === 'GET',
    { timeout: 15000 },
  ).catch(() => null);

  await page.goto('/bpm/task-center', { waitUntil: 'domcontentloaded' });
  await expect(page).toHaveURL(/\/bpm\/task-center$/);
  await workbenchRespPromise;

  // Wait for the task list table to render
  await expect(page.locator('table').first()).toBeVisible({ timeout: 15000 });

  // Find the row whose business-key cell matches taskKey
  const businessKeyCell = page
    .locator(`[data-testid="task-business-key"]`)
    .filter({ hasText: taskKey })
    .first();

  await expect(businessKeyCell).toBeVisible({ timeout: 5000 });

  // Get the table row for this task
  const taskRow = businessKeyCell.locator('xpath=ancestor::tr').first();

  // Intercept the approve/reject API call before clicking to avoid race
  const actionPath = action === 'approve' ? 'approve' : 'reject';
  const apiRespPromise = page.waitForResponse(
    (r) =>
      r.url().includes(`/api/bpm/tasks/`) &&
      r.url().includes(`/${actionPath}`) &&
      r.request().method() === 'POST',
    { timeout: 15000 },
  );

  const actionMenuButton = taskRow.getByRole('button').last();
  await expect(actionMenuButton).toBeVisible({ timeout: 5000 });
  await actionMenuButton.click();

  const actionLabel = action === 'approve' ? /^通过$/ : /^驳回$/;
  const actionButton = page.getByRole('button', { name: actionLabel }).first();
  await expect(actionButton).toBeVisible({ timeout: 5000 });
  await actionButton.click();

  const dialogTitle = action === 'approve' ? /通过审批/ : /驳回审批/;
  const dialog = page.locator('[role="dialog"]').filter({ hasText: dialogTitle }).first();
  await expect(dialog).toBeVisible({ timeout: 5000 });

  if (comment) {
    const commentTextarea = dialog.locator('textarea').first();
    await expect(commentTextarea).toBeVisible({ timeout: 3000 });
    await commentTextarea.fill(comment);
  }

  const confirmLabel = action === 'approve' ? /确认通过/ : /确认驳回/;
  const confirmBtn = dialog.getByRole('button', { name: confirmLabel }).first();
  await expect(confirmBtn).toBeVisible({ timeout: 3000 });
  await confirmBtn.click();

  const apiResp = await apiRespPromise;
  if (!apiResp.ok()) {
    const errBody = await apiResp.text();
    throw new Error(
      `processTask(${taskKey}, ${action}): API returned HTTP ${apiResp.status()}: ${errBody}`,
    );
  }
}

// ---------------------------------------------------------------------------
// loginViaUI
// ---------------------------------------------------------------------------

/**
 * Login as a specific user via the UI (for tests that need multiple user contexts).
 * Requires the page to be on or about to navigate to /login.
 *
 * Goes through the real login form — so downstream pages land with proper
 * session cookies for that user (not the admin storageState default).
 */
export async function loginViaUI(page: Page, email: string, password: string): Promise<void> {
  try {
    const loginResp = await page.request.post(`${BASE_URL}/login`, {
      form: {
        email,
        password,
        remember: 'on',
        redirectTo: '/',
      },
      maxRedirects: 0,
    });
    const setCookie = loginResp.headers()['set-cookie'];
    const match = setCookie?.match(/__session=([^;]+)/);
    if (!match?.[1]) {
      throw new Error('login action did not return __session cookie');
    }
    const cookieBase = {
      name: '__session',
      value: match[1],
      path: '/',
      httpOnly: true,
      sameSite: 'Lax' as const,
      expires: Math.floor(Date.now() / 1000) + 604800,
    };
    await page.context().addCookies([
      { ...cookieBase, domain: 'localhost' },
      { ...cookieBase, domain: '127.0.0.1' },
    ]);

    await page.goto('/home', { waitUntil: 'domcontentloaded' });
    if (/\/tenant-selection(?:$|\?)/.test(page.url())) {
      const spacesResp = await page.request.get(`${BASE_URL}/api/tenant-selection/my-spaces`);
      if (spacesResp.ok()) {
        const spacesBody = await spacesResp.json();
        const spaces = Array.isArray(spacesBody?.data) ? spacesBody.data : [];
        const bizSpace = spaces.find((space: any) => space?.spaceType === 'business');
        if (bizSpace?.tenantId) {
          const selectResp = await page.request.post(`${BASE_URL}/api/tenant-selection/process`, {
            headers: { 'Content-Type': 'application/json' },
            data: { action: 'select', tenantId: bizSpace.tenantId },
          });
          if (selectResp.ok()) {
            const selectBody = await selectResp.json();
            const tenantJwt = String(selectBody?.data?.jwt ?? '');
            if (tenantJwt) {
              const tenantCookieBase = { ...cookieBase, value: tenantJwt };
              await page.context().addCookies([
                { ...tenantCookieBase, domain: 'localhost' },
                { ...tenantCookieBase, domain: '127.0.0.1' },
              ]);
              await page.goto('/home', { waitUntil: 'domcontentloaded' });
            }
          }
        }
      }
    }

    if (!/\/login(?:$|\?)/.test(page.url())) {
      return;
    }
  } catch {
    // Fall back to real UI form login below when direct session bootstrap fails.
  }

  await page.goto('/login', { waitUntil: 'domcontentloaded' });
  const emailInput = page.locator('input#email').first();
  await emailInput.waitFor({ state: 'visible', timeout: 10_000 });
  // Click to focus before fill — prevents React hydration from discarding the value.
  await emailInput.click();
  await emailInput.fill(email);

  const passwordInput = page.locator('input#password').first();
  await passwordInput.click();
  await passwordInput.fill(password);

  const submitBtn = page.getByRole('button', { name: /立即登录|login|登录|sign in/i });
  await submitBtn.click();

  await page.waitForFunction(() => !window.location.pathname.endsWith('/login'), undefined, {
    timeout: 15_000,
  });
}
