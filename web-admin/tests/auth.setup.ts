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
import { createCookieSessionStorage } from 'react-router';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import { DEFAULT_TEST_ACCOUNT } from './helpers/test-accounts';
import { BASE_URL as DEFAULT_BASE_URL } from './helpers/playwright-env';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const STORAGE_DIR = process.env.PW_STORAGE_DIR
  ? path.resolve(process.env.PW_STORAGE_DIR)
  : path.join(__dirname, 'storage');
const ENABLE_ROLE_AUTH = process.env.PW_ROLE_PROJECTS === '1';
const NODE_ENV = process.env.NODE_ENV ?? 'development';
const JWT_TOKEN_KEY = 'jwtToken';
const authSessionStorage = createCookieSessionStorage({
  cookie: {
    name: '__session',
    httpOnly: true,
    path: '/',
    sameSite: 'lax',
    secrets: [process.env.SESSION_SECRET || 'dev-only-secret-do-not-use-in-production'],
    secure: NODE_ENV === 'production',
  },
});

async function createSessionCookieValue(jwt: string): Promise<string | null> {
  const session = await authSessionStorage.getSession();
  session.set(JWT_TOKEN_KEY, jwt);
  const setCookie = await authSessionStorage.commitSession(session, {
    maxAge: 60 * 60 * 24 * 7,
  });
  const match = setCookie.match(/__session=([^;]+)/);
  return match?.[1] ?? null;
}

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

/**
 * Login via Remix action POST with maxRedirects:0 to capture the 302 Set-Cookie,
 * then manually add the cookie to context.
 *
 * NOTE: Do NOT convert localhost to 127.0.0.1 — the BFF returns 502 on 127.0.0.1.
 * Retries once on failure since the BFF can occasionally return 200 instead of 302.
 */

/**
 * Auto-select the first business space when login redirects to /tenant-selection.
 * Uses the backend API directly since we have the session cookie (= JWT).
 */
async function autoSelectSpace(
  page: import('@playwright/test').Page,
  baseURL: string,
): Promise<boolean> {
  try {
    const cookies = await page.context().cookies();
    const sessionCookie = cookies.find((c) => c.name === '__session');
    if (!sessionCookie) return false;

    // Call my-spaces to find the business tenant
    const spacesResp = await page.request.get(`${baseURL}/api/tenant-selection/my-spaces`);
    if (!spacesResp.ok()) return false;
    const spacesBody = await spacesResp.json();
    const spaces = spacesBody?.data || [];
    // Prefer the canonical "AuraBoot Demo" tenant — bpm/tenant-isolation-ui
    // and similar specs add admin to additional p3d_isolation_ui_* tenants
    // without cleanup, and the backend's "primary" tenant on login can flip
    // to whichever was created last. Pinning auth.setup to AuraBoot Demo
    // gives every downstream spec a deterministic admin session.
    const bizSpace =
      spaces.find((s: any) => s.spaceType === 'business' && s.tenantName === 'AuraBoot Demo') ||
      spaces.find((s: any) => s.spaceType === 'business');
    if (!bizSpace?.tenantId) return false;

    // Select the business space to get a JWT with tenantId
    const selectResp = await page.request.post(`${baseURL}/api/tenant-selection/process`, {
      headers: { 'Content-Type': 'application/json' },
      data: { action: 'select', tenantId: bizSpace.tenantId },
    });
    if (!selectResp.ok()) return false;
    const selectBody = await selectResp.json();
    const newJwt = selectBody?.data?.jwt;
    if (!newJwt) return false;

    // Set the new JWT (with tenantId) as session cookie
    const cookieValue = await createSessionCookieValue(newJwt);
    if (!cookieValue) return false;

    const newCookieBase = {
      name: '__session',
      value: cookieValue,
      httpOnly: true,
      sameSite: 'Lax' as const,
      expires: Math.floor(Date.now() / 1000) + 604800,
    };
    await page.context().addCookies([
      { ...newCookieBase, url: baseURL },
      { ...newCookieBase, domain: 'localhost', path: '/' },
      { ...newCookieBase, domain: '127.0.0.1', path: '/' },
    ]);
    console.log(`   Auto-selected business space: tenantId=${bizSpace.tenantId}`);
    return true;
  } catch (e) {
    console.log('   autoSelectSpace error:', e);
    return false;
  }
}

async function loginViaApi(
  page: import('@playwright/test').Page,
  baseURL: string,
  user: TestUser,
): Promise<boolean> {
  const persistSessionCookie = async (jwt: string): Promise<boolean> => {
    if (!jwt) return false;

    const cookieValue = await createSessionCookieValue(jwt);
    if (!cookieValue) return false;

    const cookieBase = {
      name: '__session',
      value: cookieValue,
      httpOnly: true,
      sameSite: 'Lax' as const,
      expires: Math.floor(Date.now() / 1000) + 604800,
    };
    await page.context().addCookies([
      { ...cookieBase, url: baseURL },
      { ...cookieBase, domain: 'localhost', path: '/' },
      { ...cookieBase, domain: '127.0.0.1', path: '/' },
    ]);

    return true;
  };

  const extractSessionCookieFromHeaders = (setCookieHeader?: string): string | null => {
    const match = setCookieHeader?.match(/__session=([^;]+)/);
    return match?.[1] ?? null;
  };

  const extractSessionCookieFromContext = async (): Promise<string | null> => {
    const cookies = await page.context().cookies();
    return cookies.find((c) => c.name === '__session')?.value ?? null;
  };

  try {
    const resp = await page.request.post(`${baseURL}/api/auth/login`, {
      data: { email: user.email, password: user.password },
      headers: { 'Content-Type': 'application/json' },
    });

    if (resp.ok()) {
      const body = await resp.json();
      const jwt = body?.data?.jwt;
      if (typeof jwt === 'string' && (await persistSessionCookie(jwt))) {
        if (user.email === DEFAULT_TEST_ACCOUNT.email) {
          const resolved = await autoSelectSpace(page, baseURL);
          if (!resolved) {
            console.log(`   [${user.email}] Warning: admin API login auto-select failed`);
          }
        } else if (!body?.data?.tenantId) {
          const resolved = await autoSelectSpace(page, baseURL);
          if (!resolved) {
            console.log(
              `   [${user.email}] Warning: API login returned no tenantId and auto-select failed`,
            );
          }
        }
        return true;
      }
    }
  } catch {
    // Fall back to the legacy form action path below.
  }

  // Retry up to 3 times with increasing delays to handle intermittent
  // BFF cold-start issues where it returns 200 instead of 302.
  const maxAttempts = 3;
  const delays = [500, 1500, 0]; // delay after each failed attempt

  for (let attempt = 1; attempt <= maxAttempts; attempt++) {
    try {
      const resp = await page.request.post(`${baseURL}/login`, {
        form: {
          email: user.email,
          password: user.password,
          remember: 'on',
          redirectTo: '/',
        },
        maxRedirects: 0,
      });

      if (resp.ok() || resp.status() === 302) {
        const sessionCookie =
          extractSessionCookieFromHeaders(resp.headers()['set-cookie']) ||
          (await extractSessionCookieFromContext());
        if (await persistSessionCookie(sessionCookie || '')) {
          // Always pin to the canonical "AuraBoot Demo" business space.
          // We previously only ran autoSelectSpace when the login response
          // included a /tenant-selection redirect — but on stacks where
          // bpm/tenant-isolation-ui (or similar) has added admin to extra
          // p3d_isolation_ui_* tenants, login lands directly with a JWT
          // for whichever tenant the backend picks as primary, which is
          // non-deterministic. Calling autoSelectSpace unconditionally
          // overrides that JWT with one explicitly bound to AuraBoot Demo.
          // Errors are non-fatal — the original cookie remains usable as
          // a fallback.
          if (user.email === 'admin@auraboot.com') {
            await autoSelectSpace(page, baseURL).catch(() => false);
          } else {
            const location = resp.headers()['location'] || '';
            if (location.includes('tenant-selection')) {
              await autoSelectSpace(page, baseURL).catch(() => false);
            }
          }

          return true;
        }
      }
    } catch {
      // Network error — fall through to retry
    }

    // Retry after delay
    if (attempt < maxAttempts) {
      await new Promise((r) => setTimeout(r, delays[attempt - 1]));
    }
  }
  return false;
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
        'input#email, input[name="email"], input[type="email"], input[placeholder*="邮箱"], input[placeholder*="Email"]',
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

  // Skip re-login only if the session is not expired AND still works against backend.
  // After reset-and-init.sh the user PID changes — must validate against live API.
  if (
    !isStorageExpired(storagePath) &&
    (await verifyStorageStateWorks(page, storagePath, baseURL))
  ) {
    return;
  }

  let ok = await loginViaApi(page, baseURL, user);
  if (!ok) {
    ok = await loginViaUI(page, baseURL, user);
  }

  expect(ok, 'Admin login must succeed').toBe(true);

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
