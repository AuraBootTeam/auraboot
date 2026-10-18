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
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import { establishBrowserBusinessSession } from './helpers/browser-business-session';
import { DEFAULT_TEST_ACCOUNT } from './helpers/test-accounts';
import { BASE_URL as DEFAULT_BASE_URL } from './helpers/playwright-env';
import { ensureTenantAdminModelPermissions, QUOTE_BOM_FIXTURE_MODELS } from './e2e/pcba-solution/quote-e2e-helpers';

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

/** Establish a real BFF session; test code never signs application cookies. */
async function loginViaApi(
  page: import('@playwright/test').Page,
  baseURL: string,
  user: TestUser,
): Promise<boolean> {
  await establishBrowserBusinessSession(page.request, baseURL, {
    identifier: user.email,
    password: user.password,
    tenantId: user.role === 'admin' ? process.env.AURA_ADMIN_TENANT_ID : undefined,
  });
  return true;
}

/**
 * Fallback: login through the browser UI form.
 */
async function loginViaUI(
  page: import('@playwright/test').Page,
  baseURL: string,
  user: TestUser,
): Promise<boolean> {
  try {
    await page.goto(`${baseURL}/login`, { waitUntil: 'domcontentloaded' });

    const emailInput = page
      .locator(
        'input#identifier, input[name="identifier"], input#email, input[name="email"], input[type="email"], input[placeholder*="用户名"], input[placeholder*="账号"], input[placeholder*="邮箱"], input[placeholder*="Email"]',
      )
      .first();
    const visible = await emailInput.isVisible({ timeout: 3000 }).catch(() => false);
    if (!visible) return false;

    await emailInput.fill(user.email);
    const passwordInput = page
      .locator(
        'input#password, input[name="password"], input[type="password"], input[placeholder*="密码"], input[placeholder*="Password"]',
      )
      .first();
    await passwordInput.fill(user.password);

    const loginButton = page
      .locator(
        'button:has-text("立即登录"), button:has-text("Login"), button:has-text("Sign in"), button[type="submit"]',
      )
      .first();
    await loginButton.click();

    await expect(page).not.toHaveURL(/login/, { timeout: 5000 });
    return true;
  } catch {
    return false;
  }
}

// ── Check if existing storage state is still valid ──────────────────
// NOTE: Only checks cookie expiry — does NOT validate against the backend.
// After reset-and-init.sh the user PID changes, making the JWT inside invalid.
// Always use verifyStorageStateWorks() before skipping re-login.
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
    return resp.ok();
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

  let ok = await loginViaApi(page, baseURL, user);
  if (!ok) {
    ok = await loginViaUI(page, baseURL, user);
  }

  expect(ok, 'Admin login must succeed').toBe(true);

  if (process.env.PW_QUOTE_BOM_FIXTURE_PERMISSIONS === '1') {
    // APIRequestContext must satisfy the same application-origin contract as browser writes.
    await page.context().setExtraHTTPHeaders({ Referer: `${baseURL}/` });
    await ensureTenantAdminModelPermissions(page, QUOTE_BOM_FIXTURE_MODELS, ['read', 'create', 'update', 'delete'], true);
    console.log('Quote/BOM fixture administrator CRUD initialized explicitly; independent business roles are unchanged.');
  }

  await page.context().storageState({ path: storagePath });
  patchStorageStateCookies(storagePath);
});

// ── Operator login (optional) ───────────────────────────────────────
// Skip slow UI fallback for optional roles — API login is sufficient.
// If the user doesn't exist, write empty storage immediately.
// Use a 5s race timeout to avoid consuming the full 15s test timeout.
setup('authenticate as operator', async ({ page, baseURL: configURL }) => {
  const baseURL = configURL || DEFAULT_BASE_URL;
  const user = TEST_USERS[1];
  const storagePath = path.join(STORAGE_DIR, user.storageFile);
  if (!ENABLE_ROLE_AUTH) {
    fs.writeFileSync(storagePath, JSON.stringify({ cookies: [], origins: [] }, null, 2));
    return;
  }

  const ok = await Promise.race([
    loginViaApi(page, baseURL, user),
    new Promise<boolean>((resolve) => setTimeout(() => resolve(false), 5000)),
  ]);

  if (ok) {
    await page.context().storageState({ path: storagePath });
    patchStorageStateCookies(storagePath);
  } else {
    fs.writeFileSync(storagePath, JSON.stringify({ cookies: [], origins: [] }, null, 2));
  }
});

// ── Ensure e2et test pages have full DSL (saved-view E2E suite depends on this) ──
// Background: when a model is published, MetaModelServiceImpl.autoCreateDefaultPages
// inserts a stub page schema with `[{toolbar},{filters},{table}]`. Plugin imports
// can fail to overwrite it (e.g. when schema-sync rolls back the import). This
// leaves `e2et_order_list` (and similar) without column/button defs, breaking
// every saved-view spec that navigates to /p/e2et_order. We re-PUT the canonical
// DSL from the test-fixtures plugin sources so the suite is robust to drift.
setup('ensure e2et test pages dsl', async ({ page, baseURL: configURL }) => {
  const baseURL = configURL || DEFAULT_BASE_URL;
  // Use admin storage state for API access
  const adminStoragePath = path.join(STORAGE_DIR, 'admin.json');
  if (!fs.existsSync(adminStoragePath)) return;

  // Resolve fixture pages.json relative to repo root
  const fixturesPagesPath = path.resolve(
    __dirname,
    '../../plugins/test-fixtures/config/pages.json',
  );
  if (!fs.existsSync(fixturesPagesPath)) {
    console.warn('[e2et-pages] fixtures pages.json missing, skipping');
    return;
  }
  const fixtures: any[] = JSON.parse(fs.readFileSync(fixturesPagesPath, 'utf-8'));
  // Load admin token via stored cookie + a header request through the BFF
  const ctx = await page.context();
  await ctx.addCookies(
    JSON.parse(fs.readFileSync(adminStoragePath, 'utf-8')).cookies || [],
  );

  for (const fx of fixtures) {
    const pageKey = fx.pageKey;
    if (!pageKey) continue;
    // Look up current page by key
    const lookup = await page.request.get(`${baseURL}/api/pages/key/${pageKey}`);
    if (!lookup.ok()) continue;
    const cur = (await lookup.json())?.data;
    if (!cur?.pid) continue;
    const blocks = Array.isArray(cur.blocks) ? cur.blocks : [];
    // Heuristic: stub blocks have no `id` and no `columns`/`buttons`/`fields`.
    const isStub = blocks.length === 0 || blocks.every(
      (b: any) => !b?.id && !b?.columns && !b?.buttons && !b?.fields && !b?.tabs,
    );
    if (!isStub) continue;

    const payload = {
      pageKey,
      modelCode: fx.modelCode,
      kind: fx.kind || 'list',
      blocks: fx.blocks,
      layout: fx.layout || { type: 'stack' },
      name: fx.name || pageKey,
    };
    const resp = await page.request.put(`${baseURL}/api/pages/${cur.pid}`, {
      data: payload,
    });
    if (resp.ok()) {
      console.log(`[e2et-pages] restored DSL for ${pageKey}`);
    } else {
      console.warn(
        `[e2et-pages] PUT ${pageKey} failed: ${resp.status()} ${await resp.text()}`,
      );
    }
  }
});

// ── Viewer login (optional) ─────────────────────────────────────────
setup('authenticate as viewer', async ({ page, baseURL: configURL }) => {
  const baseURL = configURL || DEFAULT_BASE_URL;
  const user = TEST_USERS[2];
  const storagePath = path.join(STORAGE_DIR, user.storageFile);
  if (!ENABLE_ROLE_AUTH) {
    fs.writeFileSync(storagePath, JSON.stringify({ cookies: [], origins: [] }, null, 2));
    return;
  }

  const ok = await Promise.race([
    loginViaApi(page, baseURL, user),
    new Promise<boolean>((resolve) => setTimeout(() => resolve(false), 5000)),
  ]);

  if (ok) {
    await page.context().storageState({ path: storagePath });
    patchStorageStateCookies(storagePath);
  } else {
    fs.writeFileSync(storagePath, JSON.stringify({ cookies: [], origins: [] }, null, 2));
  }
});
