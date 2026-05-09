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

import { test, expect } from '@playwright/test';
import { authHeaders, loginAdmin } from './_helpers';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';

const USERS = [
  { email: 'e2e-operator@test.com', role: 'operator' },
  { email: 'e2e-viewer@test.com', role: 'viewer' },
] as const;

test.describe.configure({ mode: 'serial' });

test('01-multi-role-users: provision e2e-operator + e2e-viewer', async ({ request }) => {
  const jwt = await loginAdmin(request);
  const headers = authHeaders(jwt);

  for (const user of USERS) {
    // Idempotency: probe via login first. /api/admin/users returns a
    // generic "Business error" (HTTP 422) when the email already exists,
    // and inspecting the message string is brittle. A successful login
    // proves both that the user exists AND that the credentials match
    // what auth.setup.ts will use later — strictly stronger guarantee.
    const probe = await request.post('/api/auth/login', {
      data: { email: user.email, password: DEFAULT_TEST_ACCOUNT.password },
    });
    if (probe.ok()) {
      const probeBody = await probe.json().catch(() => ({}));
      if (probeBody?.code === '0' && probeBody?.data?.jwt) {
        // Already provisioned and password matches — nothing to do.
        continue;
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
        roleCodes: [user.role],
        sendInviteEmail: false,
      },
    });
    const body = await resp.json().catch(() => ({}));
    if (body?.data?.email === user.email) continue; // freshly created

    // Re-probe login: maybe the user existed but password was being
    // reset by a parallel run. If the post-create login works, success.
    const recheck = await request.post('/api/auth/login', {
      data: { email: user.email, password: DEFAULT_TEST_ACCOUNT.password },
    });
    if (recheck.ok()) {
      const recheckBody = await recheck.json().catch(() => ({}));
      if (recheckBody?.code === '0') continue;
    }

    throw new Error(
      `[setup-01] provisioning ${user.email} failed: create→ HTTP ${resp.status()} ${JSON.stringify(body)}; login probe→ ${recheck.status()}`,
    );
  }
});
