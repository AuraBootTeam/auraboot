import { defineConfig, devices } from '@playwright/test';
import dns from 'node:dns';

// Force Node.js to prefer IPv4 when resolving localhost.
// macOS /etc/hosts has both 127.0.0.1 and ::1 for localhost.
// The BFF only listens on IPv4, so ::1 connections fail with ECONNREFUSED.
// This affects page.request (API context) which uses Node.js DNS resolution.
dns.setDefaultResultOrder('ipv4first');

// Bypass system HTTP proxy for localhost connections.
// The system proxy (http_proxy env var) interferes with BFF connections.
// Playwright's APIRequestContext (page.request) routes through http_proxy and
// may resolve localhost to ::1 (IPv6), causing ECONNREFUSED since Vite only
// listens on IPv4. Delete proxy vars entirely for test processes.
delete process.env.http_proxy;
delete process.env.HTTP_PROXY;
delete process.env.https_proxy;
delete process.env.HTTPS_PROXY;
process.env.NO_PROXY =
  (process.env.NO_PROXY ? process.env.NO_PROXY + ',' : '') + 'localhost,127.0.0.1';
process.env.no_proxy = process.env.NO_PROXY;
if (process.env.FORCE_COLOR && process.env.NO_COLOR) {
  delete process.env.NO_COLOR;
  delete process.env.no_color;
}

// Must use 'localhost' not '127.0.0.1' — the BFF returns 502 on direct IP.
const baseURL = process.env.PLAYWRIGHT_BASE_URL || 'http://localhost:5173';
const runProfile = process.env.PW_PROFILE || 'fast';
const enableRoleProjects = process.env.PW_ROLE_PROJECTS === '1';
const skipWebServer = process.env.PW_SKIP_WEBSERVER === '1';

/**
 * Playwright E2E Test Configuration
 *
 * Key features:
 * - 'auth' setup project: logs in and caches storageState (runs once, no race condition)
 * - 'setup' project: imports plugins and creates test fixtures (depends on auth)
 * - storageState: All tests use cached login state (no repeated logins)
 * - testDir: Tests organized in tests/e2e/
 *
 * @see https://playwright.dev/docs/test-configuration
 */
export default defineConfig({
  // Test directory structure
  testDir: './tests',
  testMatch: ['**/*.spec.ts'],
  outputDir: './test-results/artifacts',

  // Global teardown only (login moved to 'auth' setup project)
  globalTeardown: './tests/global-teardown.ts',

  // Test execution settings
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: 0,
  workers: Number(process.env.PW_WORKERS || 10),
  timeout: process.env.E2E_COVERAGE === '1' ? 60000 : 15000,

  // Reporter configuration
  reporter: process.env.CI
    ? [
        ['list'],
        ['html', { open: 'never', outputFolder: './test-results/html-report' }],
        ['json', { outputFile: './test-results/results.json' }],
      ]
    : [['line']],

  // Shared settings for all projects
  use: {
    baseURL,
    headless: true,
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: process.env.CI ? 'on-first-retry' : 'off',
    actionTimeout: 5000,
    navigationTimeout: 15000,
    // Bypass system HTTP proxy for localhost connections to avoid 502 errors
    launchOptions: {
      args: ['--no-proxy-server'],
    },
  },

  // Browser projects
  projects: [
    // Auth project — logs in all test users and saves storageState (runs first)
    {
      name: 'auth',
      testMatch: /auth\.setup\.ts/,
      timeout: 30000,
      use: {
        ...devices['Desktop Chrome'],
        actionTimeout: 15000,
        navigationTimeout: 15000,
      },
    },
    // init-env and setup projects removed — bootstrap handles plugin import + seed data automatically
    ...(runProfile === 'fast' || runProfile === 'full'
      ? [
          {
            name: 'chromium',
            testDir: './tests/e2e',
            // Exclude resource-intensive deep designer tests — they get their own project (chromium-deep)
            // with workers:1 to prevent browser OOM crashes from concurrent heavy DOM operations.
            testIgnore: /.*-deep\.spec\.ts$/,
            dependencies: ['auth'],
            use: {
              ...devices['Desktop Chrome'],
              storageState: './tests/storage/admin.json',
            },
          },
          {
            name: 'chromium-deep',
            testDir: './tests/e2e',
            testMatch: /.*-deep\.spec\.ts$/,
            // Run AFTER chromium completes — ensures deep designer tests don't compete
            // for browser resources with the main test suite (prevents OOM crashes).
            dependencies: ['chromium'],
            timeout: 120_000,
            use: {
              ...devices['Desktop Chrome'],
              storageState: './tests/storage/admin.json',
              actionTimeout: 30_000,
              navigationTimeout: 60_000,
            },
          },
        ]
      : []),
    ...(runProfile === 'smoke'
      ? [
          {
            name: 'smoke',
            testDir: './tests/e2e',
            grep: /@smoke/,
            dependencies: ['auth'],
            use: {
              ...devices['Desktop Chrome'],
              storageState: './tests/storage/admin.json',
            },
          },
        ]
      : []),
    ...(runProfile === 'critical'
      ? [
          {
            name: 'critical',
            testDir: './tests/e2e',
            grep: /@critical|@smoke/,
            dependencies: ['auth'],
            use: {
              ...devices['Desktop Chrome'],
              storageState: './tests/storage/admin.json',
            },
          },
        ]
      : []),
    ...(runProfile === 'full'
      ? [
          {
            // API tests (excludes setup/seed — those run via reset-and-init.sh or playwright.seed.config.ts)
            name: 'api',
            testDir: './tests/api',
            testIgnore: /setup\//,
            dependencies: ['chromium'],
            use: {
              ...devices['Desktop Chrome'],
              storageState: './tests/storage/admin.json',
            },
          },
        ]
      : []),
    ...(enableRoleProjects
      ? [
          {
            name: 'operator',
            testDir: './tests/e2e',
            grep: /@operator/,
            dependencies: ['auth'],
            use: {
              ...devices['Desktop Chrome'],
              storageState: './tests/storage/operator.json',
            },
          },
          {
            name: 'viewer',
            testDir: './tests/e2e',
            grep: /@viewer/,
            dependencies: ['auth'],
            use: {
              ...devices['Desktop Chrome'],
              storageState: './tests/storage/viewer.json',
            },
          },
        ]
      : []),
  ],

  // Development server
  ...(skipWebServer
    ? {}
    : {
        webServer: {
          command: 'pnpm dev:full',
          url: baseURL,
          reuseExistingServer: process.env.E2E_COVERAGE === '1' ? false : !process.env.CI,
          timeout: process.env.E2E_COVERAGE === '1' ? 30000 : 10000,
        },
      }),
});
