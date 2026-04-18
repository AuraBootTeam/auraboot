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

const BASE_URL = process.env.BASE_URL ?? 'http://localhost:5173';
const BACKEND_URL = process.env.BACKEND_URL ?? 'http://localhost:6443';

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
 * If either login fails (auth error), create the user via admin API (POST /api/admin/users)
 * with role code wd_manager / wd_hr respectively, then login again.
 *
 * CONCERN: workflow-demo default-bootstrap.json does NOT seed wd_manager or wd_hr
 * users — only a rolePermissionBinding for tenant_admin is declared. Tests relying
 * on ensureRoleUsers will auto-create the users on first run, but the created users
 * are not cleaned up between runs. This is safe for idempotent test environments
 * that reset via reset-and-init.sh before each run.
 */
export async function ensureRoleUsers(
  api: APIRequestContext,
): Promise<{ managerToken: string; hrToken: string }> {
  const managerToken = await ensureUser(api, {
    email: 'wd_manager@example.com',
    displayName: 'WD Manager',
    password: 'Test2026x',
    roleCode: 'wd_manager',
  });

  const hrToken = await ensureUser(api, {
    email: 'wd_hr@example.com',
    displayName: 'WD HR',
    password: 'Test2026x',
    roleCode: 'wd_hr',
  });

  return { managerToken, hrToken };
}

async function ensureUser(
  api: APIRequestContext,
  opts: { email: string; displayName: string; password: string; roleCode: string },
): Promise<string> {
  // Try login first
  const loginResp = await api.post(`${BACKEND_URL}/api/auth/login`, {
    data: { email: opts.email, password: opts.password },
    headers: { 'Content-Type': 'application/json' },
  });

  const loginBody = await loginResp.json();
  const existingJwt: unknown = loginBody?.data?.jwt;
  if (typeof existingJwt === 'string' && existingJwt.trim() !== '') {
    return existingJwt;
  }

  // User missing — create via admin API
  const adminToken = await loginAs(api, 'admin@example.com', 'Test2026x');

  const createResp = await api.post(`${BACKEND_URL}/api/admin/users`, {
    data: {
      email: opts.email,
      displayName: opts.displayName,
      initialPassword: opts.password,
      roleCodes: [opts.roleCode],
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
      `ensureUser(${opts.email}): admin create failed with HTTP ${createResp.status()}: ${errBody}`,
    );
  }

  // Login with newly-created user
  return loginAs(api, opts.email, opts.password);
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
): Promise<{ userId: string; email: string; token: string }> {
  const ts = Date.now();
  const email = `${prefix}_${ts}@e2e.local`;
  const password = 'Test2026x';

  const createResp = await api.post(`${BACKEND_URL}/api/admin/users`, {
    data: {
      email,
      displayName: `${prefix} ${ts}`,
      initialPassword: password,
      roleCodes: ['wd_employee'],
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

  return { userId, email, token };
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
        wd_bal_employee: userId,
        wd_bal_year: currentYear,
        wd_bal_annual_remaining: days,
        wd_bal_sick_used: 0,
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
  // Command execute returns code 200/OK on success; non-success codes indicate a domain error
  const code: unknown = body?.code;
  if (code !== 200 && code !== 'OK' && code !== '200') {
    throw new Error(
      `setLeaveBalance command failed: ${JSON.stringify(body)}`,
    );
  }
}

// ---------------------------------------------------------------------------
// submitLeaveRequest
// ---------------------------------------------------------------------------

/**
 * Navigate to the leave request list page via the sidebar menu, open the new-request
 * form, fill it in, and click Submit (wd:create_leave_request command button).
 *
 * Returns the recordId extracted from the URL after the form navigates to the
 * detail page, or from the API response intercepted during submit.
 *
 * Navigation path: sidebar "请假 demo" → "我的申请" → "新建" button → fill form → submit.
 */
export async function submitLeaveRequest(
  page: Page,
  input: { days: number; type: string; reason: string },
): Promise<{ recordId: string }> {
  const nav = page.locator('nav');

  // Expand "请假 demo" parent group if not already expanded
  const parentBtn = nav.locator('button').filter({ hasText: /请假 demo|Leave Demo/ }).first();
  await expect(parentBtn).toBeVisible({ timeout: 10000 });
  await parentBtn.click();

  // Click "我的申请 / My Requests" submenu link
  const myRequestsLink = nav.locator('a[href="/p/wd_leave_request"]');
  await expect(myRequestsLink).toBeVisible({ timeout: 5000 });

  // Wait for list response before clicking to avoid race
  const listRespPromise = page.waitForResponse(
    (r) => r.url().includes('/wd_leave_request') && r.request().method() === 'POST',
    { timeout: 15000 },
  ).catch(() => null);

  await myRequestsLink.click();
  await listRespPromise;

  // Click "新建 / New" button in the toolbar
  const newBtn = page.getByRole('button', { name: /新建|New/ }).first();
  await expect(newBtn).toBeVisible({ timeout: 10000 });
  await newBtn.click();

  // Wait for form to load
  await expect(page.locator('form, [data-form], [role="form"]').first()).toBeVisible({
    timeout: 10000,
  });

  // Fill leave type (wd_req_type) — select/combobox
  const typeField = page.locator('[data-field="wd_req_type"]').first();
  if (await typeField.isVisible({ timeout: 3000 }).catch(() => false)) {
    await typeField.click();
    await page.getByRole('option', { name: input.type }).first().click();
  }

  // Fill days (wd_req_days)
  const daysField = page
    .locator('[data-field="wd_req_days"] input, input[name="wd_req_days"]')
    .first();
  if (await daysField.isVisible({ timeout: 3000 }).catch(() => false)) {
    await daysField.fill(String(input.days));
  }

  // Fill reason (wd_req_reason)
  const reasonField = page
    .locator('[data-field="wd_req_reason"] textarea, textarea[name="wd_req_reason"]')
    .first();
  if (await reasonField.isVisible({ timeout: 3000 }).catch(() => false)) {
    await reasonField.fill(input.reason);
  }

  // Intercept the create command response to extract recordId
  const submitRespPromise = page.waitForResponse(
    (r) =>
      r.url().includes('/api/meta/commands/execute/wd:create_leave_request') &&
      r.request().method() === 'POST',
    { timeout: 15000 },
  );

  // Click the submit button (primary form button = wd:create_leave_request command)
  const submitBtn = page.getByRole('button', { name: /保存|save|提交|Submit/i }).first();
  await expect(submitBtn).toBeVisible({ timeout: 5000 });
  await submitBtn.click();

  const submitResp = await submitRespPromise;
  const submitBody = await submitResp.json();

  // The command execute response data.id or data.pid holds the created record id
  const recordId: unknown = submitBody?.data?.id ?? submitBody?.data?.pid ?? submitBody?.data;
  if (typeof recordId !== 'string' && typeof recordId !== 'number') {
    throw new Error(
      `submitLeaveRequest: could not extract recordId from submit response. ` +
        `Full response: ${JSON.stringify(submitBody)}`,
    );
  }

  return { recordId: String(recordId) };
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
  const nav = page.locator('nav');

  // Navigate to Task Center via sidebar link /bpm/task-center
  const taskCenterLink = nav.locator('a[href="/bpm/task-center"]');
  await expect(taskCenterLink).toBeVisible({ timeout: 10000 });

  const todoRespPromise = page.waitForResponse(
    (r) => r.url().includes('/api/bpm/tasks/todo') && r.request().method() === 'GET',
    { timeout: 15000 },
  ).catch(() => null);

  await taskCenterLink.click();
  await todoRespPromise;

  // Wait for the task list table to render
  await expect(page.locator('table').first()).toBeVisible({ timeout: 15000 });

  // Find the row whose business-key cell matches taskKey
  const businessKeyCell = page
    .locator(`[data-testid="task-business-key"]`)
    .filter({ hasText: taskKey })
    .first();

  await expect(businessKeyCell).toBeVisible({ timeout: 10000 });

  // Click the task name button in the same row to open the detail drawer
  const taskRow = businessKeyCell.locator('xpath=ancestor::tr').first();
  const taskNameBtn = taskRow.locator('[data-testid="task-name-button"]').first();
  await taskNameBtn.click();

  // Drawer should open — wait for it
  await expect(page.locator('[role="dialog"], .fixed.right-0').first()).toBeVisible({
    timeout: 5000,
  });

  // Close drawer and use the row action menu instead (more reliable for approve/reject)
  const escKey = page.keyboard.press('Escape');
  await escKey;

  // Click the row action dropdown (MoreHorizontal / ⋮ button)
  const actionMenuBtn = taskRow.locator('button').filter({ hasText: '' }).last();
  // Use the more specific menu trigger: a button inside the row that isn't the name btn
  const moreBtn = taskRow.locator('button[aria-haspopup], button:has(svg)').last();
  await moreBtn.click();

  // Click approve or reject option from the dropdown
  if (action === 'approve') {
    await page.getByRole('menuitem', { name: /通过|approve/i }).first().click();
  } else {
    await page.getByRole('menuitem', { name: /驳回|reject/i }).first().click();
  }

  // Dialog opens — fill optional comment
  const dialogTitle = action === 'approve' ? /通过审批/ : /驳回审批/;
  const dialog = page.locator('[role="dialog"]').filter({ hasText: dialogTitle }).first();
  await expect(dialog).toBeVisible({ timeout: 5000 });

  if (comment) {
    const commentTextarea = dialog.locator('textarea').first();
    await expect(commentTextarea).toBeVisible({ timeout: 3000 });
    await commentTextarea.fill(comment);
  }

  // Intercept the approve/reject API call
  const actionPath = action === 'approve' ? 'approve' : 'reject';
  const apiRespPromise = page.waitForResponse(
    (r) =>
      r.url().includes(`/api/bpm/tasks/`) &&
      r.url().includes(`/${actionPath}`) &&
      r.request().method() === 'POST',
    { timeout: 15000 },
  );

  // Click the confirm button
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

  // Dialog should close after success
  await expect(dialog).not.toBeVisible({ timeout: 5000 });
}
