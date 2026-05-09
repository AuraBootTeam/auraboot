/**
 * Global Setup for Playwright E2E Tests
 *
 * This script runs once before all tests to:
 * 1. Perform real login with test credentials for admin, operator, viewer
 * 2. Save authentication state (cookies, localStorage) to storage/*.json
 * 3. All subsequent tests use cached state via storageState config
 *
 * @since 5.0.0
 */

import { chromium } from '@playwright/test';
import type { FullConfig } from '@playwright/test';
import path from 'path';
import fs from 'fs';
import { fileURLToPath } from 'url';
import { DEFAULT_TEST_ACCOUNT } from './helpers/test-accounts';
import { BASE_URL as DEFAULT_BASE_URL } from './helpers/playwright-env';

// ES module compatibility for __dirname
const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

// Test account credentials
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

const STORAGE_DIR = path.join(__dirname, 'storage');

/**
 * Login a single user via Remix action and save storageState
 */
async function loginAndSave(baseURL: string, user: TestUser): Promise<boolean> {
  const storagePath = path.join(STORAGE_DIR, user.storageFile);
  const browser = await chromium.launch({
    args: ['--no-proxy-server'],
  });
  const context = await browser.newContext();

  try {
    // Strategy A: POST to Remix login action
    let loginSuccess = false;

    try {
      console.log(`   [${user.role}] Attempting Remix action login...`);
      const formResp = await context.request.post(`${baseURL}/login`, {
        form: {
          email: user.email,
          password: user.password,
          remember: 'on',
          redirectTo: '/',
        },
        maxRedirects: 0,
      });

      const status = formResp.status();
      console.log(`   [${user.role}] Login response: status=${status}`);

      // Extract Set-Cookie from 302 response and apply to context
      if (status === 302) {
        const setCookieHeader = formResp.headers()['set-cookie'];
        if (setCookieHeader) {
          // Parse cookie from Set-Cookie header
          const cookieMatch = setCookieHeader.match(/__session=([^;]+)/);
          if (cookieMatch) {
            const cookieValue = cookieMatch[1];
            await context.addCookies([
              {
                name: '__session',
                value: cookieValue,
                domain: 'localhost',
                path: '/',
                httpOnly: true,
                sameSite: 'Lax',
                expires: Math.floor(Date.now() / 1000) + 604800,
              },
            ]);
            loginSuccess = true;
            console.log(`   [${user.role}] __session cookie created from 302 redirect`);
          }
        }
      }

      // Fallback: check context cookies (in case Playwright auto-applied them)
      if (!loginSuccess) {
        const cookies = await context.cookies();
        const sessionCookie = cookies.find((c) => c.name === '__session');
        if (sessionCookie) {
          loginSuccess = true;
          console.log(`   [${user.role}] __session cookie created`);
        } else {
          console.log(`   [${user.role}] Warning: No __session cookie (status=${status})`);
        }
      }
    } catch (e) {
      console.log(`   [${user.role}] Remix login failed: ${e}`);
    }

    // Strategy B: Form-based login via browser UI
    if (!loginSuccess) {
      console.log(`   [${user.role}] Falling back to form-based login...`);
      const page = await context.newPage();
      await page.goto(`${baseURL}/`, { waitUntil: 'domcontentloaded' });
      await page.waitForLoadState('domcontentloaded', { timeout: 30000 }).catch(() => {});

      const emailInput = page.locator('input#email');
      const hasLoginForm = await emailInput.isVisible({ timeout: 10000 }).catch(() => false);

      if (hasLoginForm) {
        for (let attempt = 0; attempt < 3; attempt++) {
          await emailInput.click();
          await emailInput.fill('');
          await emailInput.type(user.email, { delay: 50 });
          const ev = await emailInput.inputValue();
          if (ev === user.email) break;
        }

        const passwordInput = page.locator('input#password');
        await passwordInput.click();
        await passwordInput.fill('');
        await passwordInput.type(user.password, { delay: 50 });

        const loginButton = page.locator('button:has-text("立即登录")');
        await loginButton.click();

        try {
          await page.waitForURL((url) => !url.pathname.includes('login'), { timeout: 15000 });
          loginSuccess = true;
        } catch {
          await page.screenshot({ path: path.join(STORAGE_DIR, `${user.role}-login-failed.png`) });
        }
      } else {
        loginSuccess = true;
      }
      await page.close();
    }

    // Save auth state
    if (loginSuccess) {
      await context.storageState({ path: storagePath });
      console.log(`   [${user.role}] Auth state saved to: ${storagePath}`);

      // Verify saved state has cookies
      const savedState = JSON.parse(fs.readFileSync(storagePath, 'utf8'));
      if (!savedState.cookies?.length) {
        console.log(`   [${user.role}] Warning: Saved state has no cookies`);
      }
    }

    return loginSuccess;
  } finally {
    await browser.close();
  }
}

async function globalSetup(config: FullConfig): Promise<void> {
  console.log('🔧 Running global setup...');

  // Ensure storage directory exists
  if (!fs.existsSync(STORAGE_DIR)) {
    fs.mkdirSync(STORAGE_DIR, { recursive: true });
    console.log('   Created storage directory');
  }

  // Get baseURL from config
  const project = config.projects.find((p) => p.name === 'chromium') || config.projects[0];
  const baseURL = project?.use?.baseURL || DEFAULT_BASE_URL;
  console.log(`   Base URL: ${baseURL}`);

  // Login admin (required)
  const adminUser = TEST_USERS[0];
  const adminSuccess = await loginAndSave(baseURL, adminUser);
  if (!adminSuccess) {
    throw new Error('Admin login failed — cannot proceed with E2E tests');
  }

  // Login operator and viewer (optional — skip if users not yet registered)
  for (const user of TEST_USERS.slice(1)) {
    try {
      const success = await loginAndSave(baseURL, user);
      if (!success) {
        console.log(
          `   [${user.role}] Login failed — user may not be registered yet. Run init-env to set up.`,
        );
        // Write empty storageState so config doesn't fail
        const emptyState = { cookies: [], origins: [] };
        fs.writeFileSync(
          path.join(STORAGE_DIR, user.storageFile),
          JSON.stringify(emptyState, null, 2),
        );
      }
    } catch (e) {
      console.log(`   [${user.role}] Login error (non-fatal): ${e}`);
      const emptyState = { cookies: [], origins: [] };
      fs.writeFileSync(
        path.join(STORAGE_DIR, user.storageFile),
        JSON.stringify(emptyState, null, 2),
      );
    }
  }

  console.log('✅ Global setup complete');
}

export default globalSetup;
