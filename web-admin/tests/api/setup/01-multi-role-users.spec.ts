/**
 * Setup Phase 1 — Multi-role test users.
 *
 * Provisions e2e-operator@test.com and e2e-viewer@test.com via
 * /api/admin/users so auth.setup.ts can log them in afterwards. Both
 * use the platform default password (DEFAULT_TEST_ACCOUNT.password)
 * and are tagged with the `operator` / `viewer` role respectively.
 *
 * Replaces oss-reset-and-init.sh §6c (provision_user). Idempotent —
 * re-running detects "already exists" and treats it as success.
 *
 * Why API calls (not psql): tests own test data, not infra. Same code
 * runs on host stack and isolated docker stack via PLAYWRIGHT_BASE_URL.
 */

import { test, expect, type APIRequestContext } from '@playwright/test';
import { authHeaders, loginAdmin } from './_helpers';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';

// `roleCode` is the actual RBAC role code granted by the test-fixtures plugin
// (plugins/test-fixtures/config/roles.json). The bare 'operator'/'viewer' codes
// do NOT exist, so a create with them silently grants nothing — the role must be
// the fixture's `e2et_*` code.
const USERS = [
  { email: 'e2e-operator@test.com', roleCode: 'e2et_operator' },
  { email: 'e2e-viewer@test.com', roleCode: 'e2et_viewer' },
] as const;

test.describe.configure({ mode: 'serial' });

test('01-multi-role-users: provision e2e-operator + e2e-viewer', async ({ request }) => {
  const jwt = await loginAdmin(request);
  const headers = authHeaders(jwt);

  for (const user of USERS) {
    await ensureUserExists(request, headers, user);
    // Ensuring the account exists (via login probe) does NOT guarantee an ACTIVE role
    // assignment: a prior run can leave the ab_user_role row soft-deleted
    // (deleted_flag=true), which MyBatis logical-delete hides from permission
    // resolution → the member silently drops to baseline perms → the permission-driven
    // sidebar menu omits the fixture models (saved-view viewer flow can't navigate).
    // Re-assert the role every run so it is idempotently active.
    await ensureActiveRole(request, headers, user);
  }
});

async function ensureUserExists(
  request: APIRequestContext,
  headers: Record<string, string>,
  user: { email: string; roleCode: string },
): Promise<void> {
  // Idempotency: probe via login first. /api/admin/users returns a generic
  // "Business error" (HTTP 422) when the email already exists, and inspecting the
  // message string is brittle. A successful login proves both that the user exists
  // AND that the credentials match what auth.setup.ts will use later.
  const probe = await request.post('/api/auth/login', {
    data: { email: user.email, password: DEFAULT_TEST_ACCOUNT.password },
  });
  if (probe.ok()) {
    const probeBody = await probe.json().catch(() => ({}));
    if (probeBody?.code === '0' && probeBody?.data?.jwt) {
      return; // already provisioned + password matches
    }
  }

  // Either user doesn't exist, or password mismatched. Try to create.
  const displayName = user.email.split('@')[0];
  const resp = await request.post('/api/admin/users', {
    headers,
    data: {
      email: user.email,
      displayName,
      initialPassword: DEFAULT_TEST_ACCOUNT.password,
      roleCodes: [user.roleCode],
      sendInviteEmail: false,
    },
  });
  const body = await resp.json().catch(() => ({}));
  if (body?.data?.email === user.email) return; // freshly created

  // Re-probe login: maybe the user existed but password was being reset by a
  // parallel run. If the post-create login works, success.
  const recheck = await request.post('/api/auth/login', {
    data: { email: user.email, password: DEFAULT_TEST_ACCOUNT.password },
  });
  if (recheck.ok()) {
    const recheckBody = await recheck.json().catch(() => ({}));
    if (recheckBody?.code === '0') return;
  }

  throw new Error(
    `[setup-01] provisioning ${user.email} failed: create→ HTTP ${resp.status()} ${JSON.stringify(body)}; login probe→ ${recheck.status()}`,
  );
}

async function ensureActiveRole(
  request: APIRequestContext,
  headers: Record<string, string>,
  user: { email: string; roleCode: string },
): Promise<void> {
  // Resolve the member pid for this active, employee-unlinked test user in the
  // admin's current tenant, then (re-)assign the fixture role. assign-by-code
  // re-activates a soft-deleted assignment, so this is idempotent.
  const membersResp = await request.get(
    `/api/org/members/unlinked?keyword=${encodeURIComponent(user.email)}`,
    { headers },
  );
  const membersBody = await membersResp.json().catch(() => ({}));
  const members: Array<{ email?: string; memberPid?: string }> = Array.isArray(membersBody?.data)
    ? membersBody.data
    : [];
  const member = members.find((m) => m.email === user.email);
  expect(
    member?.memberPid,
    `[setup-01] could not resolve memberPid for ${user.email} (unlinked members: ${JSON.stringify(members.map((m) => m.email))})`,
  ).toBeTruthy();

  const assignResp = await request.post('/api/user-roles/assign-by-code', {
    headers,
    data: { memberPid: member!.memberPid, roleCodes: [user.roleCode] },
  });
  expect(
    assignResp.ok(),
    `[setup-01] assign ${user.roleCode} to ${user.email} failed: HTTP ${assignResp.status()}`,
  ).toBeTruthy();
}
