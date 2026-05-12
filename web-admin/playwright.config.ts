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
const adminStorageState = process.env.PW_ADMIN_STORAGE_STATE || './tests/storage/admin.json';
const operatorStorageState = process.env.PW_OPERATOR_STORAGE_STATE || './tests/storage/operator.json';
const viewerStorageState = process.env.PW_VIEWER_STORAGE_STATE || './tests/storage/viewer.json';
const artifactDir = process.env.PW_ARTIFACT_DIR || './test-results/artifacts';
const reportDir = process.env.PW_REPORT_DIR || './test-results/html-report';
const resultsJson = process.env.PW_RESULTS_JSON || './test-results/results.json';

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
  // mcp-cursor-demo.spec.ts imports `@modelcontextprotocol/sdk` which is not
  // a project dep — the file itself documents that it requires extra setup
  // (CLI build + AURA_TOKEN) and is intentionally NOT part of the default
  // OSS test grep. Without this ignore, Playwright's spec collection step
  // imports the file and crashes with ERR_MODULE_NOT_FOUND before any test
  // runs, blocking auth.setup and the entire suite.
  testIgnore: ['**/mcp-cursor-demo.spec.ts'],
  outputDir: artifactDir,

  // Global teardown only (login moved to 'auth' setup project)
  globalTeardown: './tests/global-teardown.ts',

  // Test execution settings
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: 0,
  // Default 4: designer-heavy specs crush the dev server HMR under 10+
  // concurrent workers (observed Request context disposed / Execution
  // context destroyed / Target page has been closed). Override with
  // PW_WORKERS env var when running lighter specs.
  workers: Number(process.env.PW_WORKERS || 4),
  timeout: process.env.E2E_COVERAGE === '1' ? 60000 : 15000,

  // Reporter configuration
  reporter: process.env.CI
    ? [
        ['list'],
        ['html', { open: 'never', outputFolder: reportDir }],
        ['json', { outputFile: resultsJson }],
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
    // Setup project — idempotent test-data prep that runs BEFORE auth.
    // Drives /api/bootstrap/setup, provisions multi-role users, creates
    // the page-designer fixture pages + system_overview dashboard. All
    // specs are idempotent so this project is safe to run repeatedly.
    //
    // Replaces the bundled `oss-reset-and-init.sh §6/§7.4` bash logic;
    // tests own test data (cf. fix/oss-suite-r2 commit d2dbab9e).
    //
    // 2026-05-08 reinstates the previously-removed setup project (the
    // "removed" comment below was the artefact of that earlier
    // decision, now reversed).
    {
      name: 'setup',
      testDir: './tests/api/setup',
      // Match the new numbered setup specs (00-bootstrap, 01-multi-role-users,
      // 02-test-pages). Existing seed-showcase-*.spec.ts files run as part
      // of the showcase seed flow, not in this setup project.
      testMatch: /\/\d{2}-.*\.spec\.ts$/,
      timeout: 60_000,
      use: {
        ...devices['Desktop Chrome'],
      },
    },
    // Auth project — logs in all test users and saves storageState (runs after setup)
    {
      name: 'auth',
      testMatch: /auth\.setup\.ts/,
      timeout: 30000,
      dependencies: ['setup'],
      use: {
        ...devices['Desktop Chrome'],
        actionTimeout: 15000,
        navigationTimeout: 15000,
      },
    },
    // init-env (legacy bundled spec) is excluded from the setup project's
    // testMatch — it pre-dates the 00/01/02 split and would re-do steps
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
              storageState: adminStorageState,
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
              storageState: adminStorageState,
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
              storageState: adminStorageState,
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
              storageState: adminStorageState,
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
              storageState: adminStorageState,
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
              storageState: operatorStorageState,
            },
          },
          {
            name: 'viewer',
            testDir: './tests/e2e',
            grep: /@viewer/,
            dependencies: ['auth'],
            use: {
              ...devices['Desktop Chrome'],
              storageState: viewerStorageState,
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
