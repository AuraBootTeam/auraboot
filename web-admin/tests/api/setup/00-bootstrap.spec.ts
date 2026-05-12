/**
 * Setup Phase 0 — Bootstrap (Phase 2.4 contract test).
 *
 * Drives /api/bootstrap/setup if the backend is not yet initialized, then
 * verifies all 9 bootstrap invariants are present in the database via psql.
 * Idempotent: repeated runs short-circuit at the "already initialized" branch
 * and re-verify the invariants.
 *
 * The 9 invariants (canonical: docs/plans/2026-05/bootstrap-unified.md §2):
 *   1. system_config row + system.initialized=true
 *   2. System Tenant row (name='System')
 *   3. platform_admin role row in System Tenant
 *   4. admin@auraboot.com user
 *   5. admin → System Tenant membership
 *   6. admin → platform_admin role grant
 *   7. Business Tenant row
 *   8. Built-in plugins imported (com.auraboot.org-management,
 *      com.auraboot.platform-admin) for the Business Tenant
 *   9. JWT signing key consistent (system_config.jwt_secret OR
 *      security.jwt.secret — see runtime contract below)
 *
 * Runtime contract for invariant 9:
 *   The OSS BootstrapRepairService.repairJwtSecret() reports PRESENT because
 *   the secret is sourced from `application.yml#security.jwt.secret` (env
 *   override `JWT_SECRET`). There is no `system_config.jwt_secret` row in OSS
 *   today. The contract test asserts EITHER the system_config row OR a
 *   successful login (proves the in-process JwtKeyProvider has a usable key
 *   wired from properties).
 *
 * Replaces oss-reset-and-init.sh step 4.5 + 7.4. Lives in tests/api/setup/
 * because:
 *   - Test data prep is the test suite's responsibility, not infra.
 *   - Same code runs against host stack OR isolated docker stack — the
 *     setup uses BACKEND_URL / PSQL_BASE so it points at whichever
 *     vite/BFF/postgres the runner was launched against.
 *   - Wired as the first project in playwright.oss.config.ts so all
 *     downstream projects (auth, chromium, chromium-deep) inherit a
 *     ready environment.
 *
 * Pre-conditions:
 *   - Backend is up and `/actuator/health` returns 200.
 *   - Postgres reachable via PSQL_BASE.
 */

import { execSync } from 'node:child_process';
import { test, expect } from '@playwright/test';
import { BACKEND_URL, PSQL_BASE } from '../../helpers/environments';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';

const COMPANY_NAME = process.env.AURA_BOOTSTRAP_COMPANY ?? 'AuraBoot Dev';

/** Built-in plugin pluginIds imported by the platform on bootstrap (canonical: BuiltinPluginImportServiceImpl). */
const BUILTIN_PLUGIN_IDS = [
  'com.auraboot.org-management',
  'com.auraboot.platform-admin',
];

/**
 * Run a psql query against the configured stack and return scalar / row.
 *
 * Uses `-tA` (tuples-only, unaligned) so the output is the raw cell value(s).
 */
function psql(query: string): string {
  const cmd = `${PSQL_BASE} -tA -P pager=off -c "${query.replace(/"/g, '\\"')}"`;
  return execSync(cmd, { encoding: 'utf8' }).trim();
}

function psqlInt(query: string): number {
  const out = psql(query);
  const n = Number(out);
  if (!Number.isFinite(n)) {
    throw new Error(`psql query did not return an integer: ${query} → ${out}`);
  }
  return n;
}

test.describe.configure({ mode: 'serial' });

test('00-bootstrap: ensure system is initialized via /api/bootstrap/setup', async ({
  request,
}) => {
  const status = await request.get(`${BACKEND_URL}/api/bootstrap/status`);
  expect(status.ok()).toBeTruthy();
  const statusBody = await status.json();
  const initialized = statusBody?.data?.initialized === true;

  if (!initialized) {
    // Phase 2.4: backend's BootstrapStartupRunner now auto-bootstraps on
    // first start. The wizard endpoint short-circuits when the system is
    // already initialized (back-compat), so a successful response can be
    // either code='0' (we initialized) OR a body whose message contains
    // "already initialized" (auto-runner beat us).
    const setup = await request.post(`${BACKEND_URL}/api/bootstrap/setup`, {
      data: {
        companyName: COMPANY_NAME,
        adminEmail: DEFAULT_TEST_ACCOUNT.email,
        adminPassword: DEFAULT_TEST_ACCOUNT.password,
        adminDisplayName: 'Admin User',
        systemMode: 'single',
        seedDemoData: false,
      },
    });
    expect(setup.status()).toBe(200);
    const body = await setup.json();
    if (body.code !== '0') {
      expect(body.message ?? '').toMatch(/already initialized/i);
    } else {
      expect(body.data?.tenantId, 'bootstrap should return business tenantId').toBeTruthy();
    }

    const recheck = await request.get(`${BACKEND_URL}/api/bootstrap/status`);
    const recheckBody = await recheck.json();
    expect(recheckBody?.data?.initialized).toBe(true);
  }
});

test('00-bootstrap: invariant 1 — system_config row + system.initialized=true', async () => {
  const initRow = psql(
    `SELECT config_value FROM ab_system_config WHERE config_key = 'system.initialized' LIMIT 1`,
  );
  expect(initRow, 'system.initialized row missing').toBe('true');

  const expectedKeys = [
    'system.mode',
    'system.platform_name',
    'system.db_uuid',
    'system.instance_url',
  ];
  for (const key of expectedKeys) {
    const cnt = psqlInt(
      `SELECT COUNT(*) FROM ab_system_config WHERE config_key = '${key}'`,
    );
    expect(cnt, `system_config row missing for key=${key}`).toBeGreaterThan(0);
  }
});

test('00-bootstrap: invariant 2 — System Tenant row exists', async () => {
  const count = psqlInt(
    `SELECT COUNT(*) FROM ab_tenant WHERE name = 'System' AND COALESCE(deleted_flag, false) = false`,
  );
  expect(count, 'System Tenant row missing').toBeGreaterThanOrEqual(1);
});

test('00-bootstrap: invariant 3 — platform_admin role exists in System Tenant', async () => {
  const count = psqlInt(
    `SELECT COUNT(*) FROM ab_role r JOIN ab_tenant t ON r.tenant_id = t.id ` +
      `WHERE t.name = 'System' AND r.code = 'platform_admin' ` +
      `AND COALESCE(r.deleted_flag, false) = false`,
  );
  expect(count, 'platform_admin role missing in System Tenant').toBe(1);
});

test('00-bootstrap: invariant 4 — admin user exists', async () => {
  const count = psqlInt(
    `SELECT COUNT(*) FROM ab_user WHERE email = '${DEFAULT_TEST_ACCOUNT.email}' ` +
      `AND COALESCE(deleted_flag, false) = false`,
  );
  expect(count, `admin user ${DEFAULT_TEST_ACCOUNT.email} missing`).toBe(1);
});

test('00-bootstrap: invariant 5 — admin → System Tenant membership', async () => {
  const count = psqlInt(
    `SELECT COUNT(*) FROM ab_tenant_member tm
     JOIN ab_user u ON tm.user_id = u.id
     JOIN ab_tenant t ON tm.tenant_id = t.id
     WHERE u.email = '${DEFAULT_TEST_ACCOUNT.email}' AND t.name = 'System'`,
  );
  expect(count, 'admin → System Tenant membership missing').toBe(1);
});

test('00-bootstrap: invariant 6 — admin → platform_admin role grant', async () => {
  const count = psqlInt(
    `SELECT COUNT(*) FROM ab_user_role ur
     JOIN ab_tenant_member tm ON ur.member_id = tm.id
     JOIN ab_user u ON tm.user_id = u.id
     JOIN ab_role r ON ur.role_id = r.id
     JOIN ab_tenant t ON r.tenant_id = t.id
     WHERE u.email = '${DEFAULT_TEST_ACCOUNT.email}'
       AND r.code = 'platform_admin'
       AND t.name = 'System'`,
  );
  expect(count, 'admin → platform_admin grant missing').toBeGreaterThanOrEqual(1);
});

test('00-bootstrap: invariant 7 — Business Tenant exists', async () => {
  const count = psqlInt(
    `SELECT COUNT(*) FROM ab_tenant WHERE name = '${COMPANY_NAME}' ` +
      `AND COALESCE(deleted_flag, false) = false`,
  );
  expect(count, `Business Tenant '${COMPANY_NAME}' missing`).toBeGreaterThanOrEqual(1);
});

test('00-bootstrap: invariant 8 — built-in plugins imported (org-management, platform-admin)', async () => {
  // ab_plugin is the plugin registry; ab_plugin_installation is the
  // tenant-binding table. We accept either as proof of import: some test
  // stacks may register without binding (or bind without registering on
  // a fresh re-deploy). Per Phase 2.2 docs, repairBuiltinPlugins is
  // documented as non-fatal — so if BOTH tables come up empty for a
  // pluginId, we surface the gap rather than silently passing.
  const installTblExists =
    psqlInt(
      `SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'ab_plugin_installation'`,
    ) > 0;

  for (const pluginId of BUILTIN_PLUGIN_IDS) {
    let found = 0;
    if (installTblExists) {
      found += psqlInt(
        `SELECT COUNT(*) FROM ab_plugin_installation WHERE plugin_id = '${pluginId}'`,
      );
    }
    if (found === 0) {
      found += psqlInt(
        `SELECT COUNT(*) FROM ab_plugin WHERE plugin_id = '${pluginId}'`,
      );
    }
    expect(
      found,
      `built-in plugin ${pluginId} not registered (neither installation nor plugin row)`,
    ).toBeGreaterThanOrEqual(1);
  }
});

test('00-bootstrap: invariant 9 — JWT signing key usable (login round-trip)', async ({ request }) => {
  // Either the system_config row exists (DB-sourced secret) OR an admin login
  // round-trip succeeds (config-sourced secret) — both prove invariant 9.
  const dbCount = psqlInt(
    `SELECT COUNT(*) FROM ab_system_config WHERE config_key = 'jwt_secret'`,
  );

  const login = await request.post(`${BACKEND_URL}/api/auth/login`, {
    data: {
      email: DEFAULT_TEST_ACCOUNT.email,
      password: DEFAULT_TEST_ACCOUNT.password,
    },
  });
  expect(login.status()).toBe(200);
  const body = await login.json();
  const jwt = body?.data?.jwt as string | undefined;
  expect(
    jwt,
    `invariant 9 violation: no JWT returned (db_jwt_secret_rows=${dbCount}); login body=${JSON.stringify(body)}`,
  ).toBeTruthy();
});
