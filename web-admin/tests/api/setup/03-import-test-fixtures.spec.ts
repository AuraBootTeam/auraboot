/**
 * Setup Phase 0.5 — Import the internal `test-fixtures` plugin.
 *
 * Plugin: `plugins/test-fixtures` (`com.auraboot.test-fixtures`).
 * Provides `e2et_order` / `e2et_customer` / `e2et_payment` schemas that
 * many E2E specs depend on but are intentionally excluded from default
 * bootstrap (it is marked `internal: true` in plugin.json).
 *
 * Gate: runs when `AURA_ENV=test`, `IMPORT_TEST_FIXTURES=true`, or
 * `PW_PROFILE=full`.
 * Otherwise the test body is skipped — matching the backend-side
 * `BuiltinPluginImportService` behaviour described in
 * `auraboot-enterprise/AGENTS.md` §「系统目录」.
 *
 * Idempotent: the import endpoint accepts `conflictStrategy=OVERWRITE`,
 * so re-running against an already-fixtured database is a no-op.
 *
 * Filename prefix is `03-` because `01-multi-role-users` and
 * `02-test-pages` already occupy the early slots; this import is
 * independent of those (multi-role users + test-pages don't depend on
 * the e2et schemas) and only needs to land before any spec that DOES
 * read the e2et tables. Setup-project ordering is alphabetical:
 *   00-bootstrap → 01-multi-role-users → 02-test-pages → 03-import-test-fixtures
 */

import { test, expect } from '@playwright/test';
import { readFileSync, existsSync } from 'fs';
import { resolve, dirname } from 'path';
import { fileURLToPath } from 'url';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';
import { BACKEND_URL } from '../../helpers/environments';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const PLUGIN_DIR = resolve(__dirname, '../../../../plugins/test-fixtures');
const PLUGIN_MANIFEST = resolve(PLUGIN_DIR, 'plugin.json');

const AURA_ENV = process.env.AURA_ENV ?? '';
const IMPORT_TEST_FIXTURES = process.env.IMPORT_TEST_FIXTURES ?? '';
const PW_PROFILE = process.env.PW_PROFILE ?? '';
const BACKEND_PLUGIN_ROOT =
  process.env.OSS_PLUGIN_ROOT ??
  process.env.BACKEND_PLUGIN_ROOT ??
  (PW_PROFILE === 'full' || process.env.CI === '1'
    ? '/app/plugins'
    : resolve(__dirname, '../../../../plugins'));
const BACKEND_PLUGIN_DIR = resolve(BACKEND_PLUGIN_ROOT, 'test-fixtures');
const SHOULD_IMPORT =
  AURA_ENV === 'test' || IMPORT_TEST_FIXTURES.toLowerCase() === 'true' || PW_PROFILE === 'full';

test.describe.configure({ mode: 'serial' });

test('import test-fixtures plugin (gated)', async ({ request }) => {
  test.skip(
    !SHOULD_IMPORT,
    'AURA_ENV=test, IMPORT_TEST_FIXTURES=true, or PW_PROFILE=full not set — skipping internal test-fixtures import',
  );

  // Sanity: refuse to run if the manifest is missing (tells the operator
  // the plugin tree is incomplete instead of silently passing).
  expect(existsSync(PLUGIN_MANIFEST), `plugin manifest missing: ${PLUGIN_MANIFEST}`).toBe(true);

  const manifest = JSON.parse(readFileSync(PLUGIN_MANIFEST, 'utf-8'));
  expect(manifest?.pluginId, 'unexpected test-fixtures plugin manifest').toBe(
    'com.auraboot.test-fixtures',
  );

  // Acquire a JWT against the live backend.
  const loginRes = await request.post(`${BACKEND_URL}/api/auth/login`, {
    data: {
      email: DEFAULT_TEST_ACCOUNT.email,
      password: DEFAULT_TEST_ACCOUNT.password,
    },
  });
  expect(loginRes.ok(), `login failed: ${loginRes.status()}`).toBe(true);
  const loginBody = (await loginRes.json()) as { data?: { jwt?: string } };
  const token = loginBody?.data?.jwt;
  expect(token, 'login response missing jwt').toBeTruthy();

  const importRes = await request.post(
    `${BACKEND_URL}/api/plugins/import/import-directory-sync`,
    {
      data: {
        path: BACKEND_PLUGIN_DIR,
        conflictStrategy: 'OVERWRITE',
        validateReferences: true,
        autoDeployProcesses: true,
        autoPublishModels: true,
        autoPublishFields: true,
        autoPublishCommands: true,
        autoPublishPages: true,
        createResourcePermissions: true,
      },
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
      },
      timeout: 120_000,
    },
  );

  expect(importRes.ok(), `import returned HTTP ${importRes.status()}`).toBe(true);
  const body = (await importRes.json()) as {
    data?: { success?: boolean; status?: string; errorMessage?: string };
    success?: boolean;
    status?: string;
    errorMessage?: string;
  };
  const result = body?.data && typeof body.data === 'object' ? body.data : body;
  expect(
    result?.success,
    `import did not succeed from ${BACKEND_PLUGIN_DIR} (status=${result?.status ?? '?'}, msg=${
      result?.errorMessage ?? '?'
    })`,
  ).toBe(true);
});
