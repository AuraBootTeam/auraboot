/**
 * Setup Phase 0 — Bootstrap (Phase 2.4 contract test).
 *
 * Drives /api/bootstrap/setup if the backend is not yet initialized, then
 * verifies all 9 bootstrap invariants are present in the database via
 * node-postgres.
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
 *   8. Business Tenant template roles exist
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
 *     setup uses BACKEND_URL / PG* env so it points at whichever
 *     vite/BFF/postgres the runner was launched against.
 *   - Wired as the first project in playwright.oss.config.ts so all
 *     downstream projects (auth, chromium, chromium-deep) inherit a
 *     ready environment.
 *
 * Pre-conditions:
 *   - Backend is up and `/actuator/health` returns 200.
 *   - Postgres reachable via PGHOST/PGPORT/PGUSER/PGDATABASE.
 */

import { execFileSync } from 'node:child_process';
import { test, expect, type APIRequestContext } from '@playwright/test';
import { BACKEND_URL, PG_ENV } from '../../helpers/environments';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';

const COMPANY_NAME = process.env.AURA_BOOTSTRAP_COMPANY ?? 'AuraBoot Dev';
const AUTO_BOOTSTRAP_WAIT_MS = Number(process.env.AURA_AUTO_BOOTSTRAP_WAIT_MS ?? '0');
const BOOTSTRAP_POLL_MS = 2000;

/**
 * Run a SQL query against the configured stack and return scalar / row.
 * Uses node-postgres so the isolated frontend E2E image does not need a shell
 * database client installed.
 */
function psql(query: string): string {
  const runner = `
const { Client } = require('pg');

let sql = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', chunk => { sql += chunk; });
process.stdin.on('end', async () => {
  const client = new Client({
    host: process.env.PGHOST || 'localhost',
    port: Number(process.env.PGPORT || '5432'),
    user: process.env.PGUSER || process.env.USER || 'ghj',
    database: process.env.PGDATABASE || 'aura_boot',
    password: process.env['PGPASSWORD'] || undefined,
  });
  try {
    await client.connect();
    const result = await client.query(sql);
    const rows = Array.isArray(result)
      ? result.flatMap(item => item.rows || [])
      : (result.rows || []);
    const out = rows
      .map(row => Object.values(row).map(value => value == null ? '' : String(value)).join('|'))
      .join('\\n');
    process.stdout.write(out);
  } catch (error) {
    console.error(error && error.stack ? error.stack : String(error));
    process.exitCode = 1;
  } finally {
    await client.end().catch(() => {});
  }
});
`;
  return execFileSync(process.execPath, ['-e', runner], {
    input: query,
    encoding: 'utf8',
    env: PG_ENV,
  }).trim();
}

function psqlInt(query: string): number {
  const out = psql(query);
  const n = Number(out);
  if (!Number.isFinite(n)) {
    throw new Error(`psql query did not return an integer: ${query} → ${out}`);
  }
  return n;
}

async function readBootstrapStatus(request: APIRequestContext): Promise<{
  initialized: boolean;
  inProgress: boolean;
}> {
  const status = await request.get(`${BACKEND_URL}/api/bootstrap/status`);
  expect(status.ok()).toBeTruthy();
  const body = await status.json();
  return {
    initialized: body?.data?.initialized === true,
    inProgress: body?.data?.inProgress === true,
  };
}

async function waitForAutoBootstrap(request: APIRequestContext): Promise<boolean> {
  if (AUTO_BOOTSTRAP_WAIT_MS <= 0) return false;
  const deadline = Date.now() + AUTO_BOOTSTRAP_WAIT_MS;
  do {
    const status = await readBootstrapStatus(request);
    if (status.initialized) return true;
    await new Promise((resolve) => setTimeout(resolve, BOOTSTRAP_POLL_MS));
  } while (Date.now() < deadline);
  return false;
}

test.describe.configure({ mode: 'serial' });

test('00-bootstrap: ensure system is initialized via /api/bootstrap/setup', async ({
  request,
}) => {
  test.setTimeout(120_000);

  let { initialized } = await readBootstrapStatus(request);
  if (!initialized) {
    initialized = await waitForAutoBootstrap(request);
  }

  if (!initialized) {
    // Startup-time repair/bootstrap writes are intentionally disabled. Use the
    // explicit setup API as the single initialization owner; AUTO_BOOTSTRAP_WAIT_MS
    // remains available only for externally pre-bootstrapped stacks.
    const setup = await request.post(`${BACKEND_URL}/api/bootstrap/setup`, {
      timeout: 90_000,
      data: {
        companyName: COMPANY_NAME,
        adminEmail: DEFAULT_TEST_ACCOUNT.email,
        adminPassword: DEFAULT_TEST_ACCOUNT.password,
        adminDisplayName: 'Admin User',
        systemMode: 'single',
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

test('00-bootstrap: invariant 8 — Business Tenant template roles exist', async () => {
  const roles = psql(
    `SELECT r.code
     FROM ab_role r
     JOIN ab_tenant t ON r.tenant_id = t.id
     WHERE t.name = '${COMPANY_NAME}'
       AND r.code IN ('tenant_admin', 'operator', 'viewer')
       AND COALESCE(r.deleted_flag, false) = false
     ORDER BY r.code`,
  )
    .split('\n')
    .filter(Boolean);

  expect(roles).toEqual(['operator', 'tenant_admin', 'viewer']);
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
