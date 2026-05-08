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
    if (body?.data?.email === user.email) {
      // Created.
      continue;
    }
    // Treat "already exists" as success — re-running this spec must not break.
    const message = String(body?.message ?? '');
    if (/already exists/i.test(message)) {
      continue;
    }
    throw new Error(
      `[setup-01] provisioning ${user.email} failed: HTTP ${resp.status()} — ${
        message || JSON.stringify(body)
      }`,
    );
  }

  // Cross-check: log in as each user to confirm credentials were set.
  for (const user of USERS) {
    const probe = await request.post('/api/auth/login', {
      data: { email: user.email, password: DEFAULT_TEST_ACCOUNT.password },
    });
    expect(
      probe.ok(),
      `[setup-01] ${user.email} login probe failed (${probe.status()})`,
    ).toBeTruthy();
    const probeBody = await probe.json();
    expect(
      probeBody.code,
      `[setup-01] ${user.email} login returned non-zero code: ${JSON.stringify(probeBody)}`,
    ).toBe('0');
  }
});
