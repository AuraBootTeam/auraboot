/**
 * Auth Setup Project — replaces globalSetup for login.
 *
 * Playwright runs this once before any dependent project.
 * No more concurrent admin.json writes when multiple
 * `npx playwright test` commands share the same config.
 *
 * @since 8.0.0
 *
 * BACKLOG-AUTH-001 (closed WONT_DO 2026-04-25):
 *   Do NOT add a 401 self-heal that resets the admin password via SQL.
 *   The "禁止自愈" red line in AGENTS.md applies even to test infrastructure;
 *   masking environment drift with implicit recovery breaks the contract
 *   that `oss-reset-and-init.sh` is the single source of truth for env state.
 *   When auth.setup hits 401 it MUST fail fast with a clear message that
 *   the operator should run reset-and-init.sh, not silently retry.
 */

import { test as setup, expect } from '@playwright/test';
import { authenticateBffSession } from './helpers/bff-auth-session';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import { DEFAULT_TEST_ACCOUNT } from './helpers/test-accounts';
import { BASE_URL as DEFAULT_BASE_URL } from './helpers/playwright-env';
import {
  ensureTenantAdminModelPermissions,
  QUOTE_BOM_FIXTURE_MODELS,
} from './e2e/pcba-solution/quote-e2e-helpers';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const STORAGE_DIR = process.env.PW_STORAGE_DIR
  ? path.resolve(process.env.PW_STORAGE_DIR)
  : path.join(__dirname, 'storage');
const ENABLE_ROLE_AUTH = process.env.PW_ROLE_PROJECTS === '1';
/**
 * Patch saved storage state to ensure all cookies have `secure` field.
 * Playwright on localhost omits `secure`, but `newContext({ storageState })` requires it.
 */
function patchStorageStateCookies(filePath: string): void {
  try {
    const raw = fs.readFileSync(filePath, 'utf-8');
    const state = JSON.parse(raw);
    if (Array.isArray(state.cookies)) {
      let patched = false;
      for (const cookie of state.cookies) {
        if (cookie.secure === undefined) {
          cookie.secure = false;
          patched = true;
        }
      }
      if (patched) {
        fs.writeFileSync(filePath, JSON.stringify(state, null, 2));
      }
    }
  } catch {
    // ignore — file may not exist yet
  }
}

interface TestUser {
  email: string;
  password: string;
  storageFile: string;
  role: string;
}

const TEST_USERS: TestUser[] = [
  {
    email: DEFAULT_TEST_ACCOUNT.email,
    password: DEFAULT_TEST_ACCOUNT.password,
    storageFile: 'admin.json',
    role: 'admin',
  },
  {
    email: 'e2e-operator@test.com',
    password: DEFAULT_TEST_ACCOUNT.password,
    storageFile: 'operator.json',
    role: 'operator',
  },
  {
    email: 'e2e-viewer@test.com',
    password: DEFAULT_TEST_ACCOUNT.password,
    storageFile: 'viewer.json',
    role: 'viewer',
  },
];

function isStorageExpired(storagePath: string): boolean {
  try {
    if (!fs.existsSync(storagePath)) return true;
    const raw = fs.readFileSync(storagePath, 'utf-8');
    const state = JSON.parse(raw);
    const cookies = state.cookies || [];
    const session = cookies.find((c: any) => c.name === '__session');
    if (!session?.value) return true;
    // Check expiry (with 1h buffer)
    const now = Math.floor(Date.now() / 1000);
    return session.expires <= now + 3600;
  } catch {
    return true;
  }
}

/**
 * Verify the stored session actually works against the backend.
 * Calls /api/auth/me — returns true only if the JWT is accepted.
 * This handles the case where reset-and-init.sh changed user PIDs.
 */
async function verifyStorageStateWorks(
  page: import('@playwright/test').Page,
  storagePath: string,
  baseURL: string,
): Promise<boolean> {
  try {
    const raw = fs.readFileSync(storagePath, 'utf-8');
    const state = JSON.parse(raw);
    const cookies: Array<{ name: string; value: string; domain: string; path: string }> =
      state.cookies || [];
    if (cookies.length === 0) return false;
    // Make a request using the stored cookies to verify the session works
    const resp = await page.request.get(`${baseURL}/api/auth/me`, {
      headers: {
        Cookie: cookies
          .filter((c) => c.name === '__session')
          .map((c) => `${c.name}=${c.value}`)
          .join('; '),
      },
      timeout: 5000,
    });
    if (!resp.ok()) return false;
    const identity = await resp.json();
    return identity.code === '0' && Boolean(identity.data?.user?.tenantId);
  } catch {
    return false;
  }
}

// ── Admin login (required) ──────────────────────────────────────────
setup('authenticate as admin', async ({ page, baseURL: configURL }) => {
  const baseURL = configURL || DEFAULT_BASE_URL;

  if (!fs.existsSync(STORAGE_DIR)) {
    fs.mkdirSync(STORAGE_DIR, { recursive: true });
  }

  const user = TEST_USERS[0];
  const storagePath = path.join(STORAGE_DIR, user.storageFile);

  // Full local suites can run for hours. Reusing a still-valid cookie whose
  // embedded JWT is close to expiry causes mid-run 401 cascades, so admin
  // storage is refreshed by default at the start of every run.
  const allowReuse = process.env.PW_REUSE_AUTH_STATE === '1';
  if (
    allowReuse &&
    !isStorageExpired(storagePath) &&
    (await verifyStorageStateWorks(page, storagePath, baseURL))
  ) {
    patchStorageStateCookies(storagePath);
    return;
  }

  await authenticateBffSession(page.request, baseURL, user.email, user.password);

  if (process.env.PW_QUOTE_BOM_FIXTURE_PERMISSIONS === '1') {
    // APIRequestContext must satisfy the same application-origin contract as browser writes.
    await page.context().setExtraHTTPHeaders({ Referer: `${baseURL}/` });
    await ensureTenantAdminModelPermissions(
      page,
      QUOTE_BOM_FIXTURE_MODELS,
      ['read', 'create', 'update', 'delete'],
      true,
    );
    console.log(
      'Quote/BOM fixture administrator CRUD initialized explicitly; independent business roles are unchanged.',
    );
  }

  await page.context().storageState({ path: storagePath });
  patchStorageStateCookies(storagePath);
});

// Configured role authentication is mandatory; a failed login must not create empty green state.
setup('authenticate as operator', async ({ page, baseURL: configURL }) => {
  const baseURL = configURL || DEFAULT_BASE_URL;
  const user = TEST_USERS[1];
  const storagePath = path.join(STORAGE_DIR, user.storageFile);
  setup.skip(!ENABLE_ROLE_AUTH, 'Role authentication is explicitly disabled for this profile');
  await authenticateBffSession(page.request, baseURL, user.email, user.password);
  await page.context().storageState({ path: storagePath });
});

// Fixture import/publish belongs to setup/03, never an implicit authoring repair here.
setup('verify e2et published fixture blocks', async ({ page, baseURL: configURL }) => {
  setup.skip(!ENABLE_ROLE_AUTH, 'Role fixtures are not part of this auth profile');
  const baseURL = configURL || DEFAULT_BASE_URL;
  const adminStoragePath = path.join(STORAGE_DIR, 'admin.json');
  expect(fs.existsSync(adminStoragePath), 'Verified administrator state is required').toBe(true);
  const fixturesPagesPath = path.resolve(
    __dirname,
    '../../plugins/test-fixtures/config/pages.json',
  );
  expect(fs.existsSync(fixturesPagesPath), 'Declared test fixture pages are required').toBe(true);
  const fixtures = JSON.parse(fs.readFileSync(fixturesPagesPath, 'utf-8'));
  expect(Array.isArray(fixtures)).toBe(true);
  expect(fixtures.length).toBeGreaterThan(0);
  await page.context().addCookies(JSON.parse(fs.readFileSync(adminStoragePath, 'utf-8')).cookies);
  for (const fixture of fixtures) {
    expect(fixture.pageKey).toEqual(expect.any(String));
    const response = await page.request.get(`${baseURL}/api/pages/key/${fixture.pageKey}`);
    expect(response.status(), `Published fixture ${fixture.pageKey} must be readable`).toBe(200);
    const payload = await response.json();
    expect(payload.code).toBe('0');
    const blocks = payload.data?.blocks;
    expect(Array.isArray(blocks)).toBe(true);
    expect(blocks.length).toBeGreaterThan(0);
    for (const block of fixture.blocks) {
      expect(
        blocks,
        `Fixture ${fixture.pageKey} must retain its declared block identities`,
      ).toEqual(
        expect.arrayContaining([
          expect.objectContaining({ id: block.id, blockType: block.blockType }),
        ]),
      );
    }
  }
});

setup('authenticate as viewer', async ({ page, baseURL: configURL }) => {
  const baseURL = configURL || DEFAULT_BASE_URL;
  const user = TEST_USERS[2];
  const storagePath = path.join(STORAGE_DIR, user.storageFile);
  setup.skip(!ENABLE_ROLE_AUTH, 'Role authentication is explicitly disabled for this profile');
  await authenticateBffSession(page.request, baseURL, user.email, user.password);
  await page.context().storageState({ path: storagePath });
});
