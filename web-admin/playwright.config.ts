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
const baseURL = process.env.PLAYWRIGHT_BASE_URL || 'http://127.0.0.1:5173';
const runProfile = process.env.PW_PROFILE || 'fast';
const enableRoleProjects = process.env.PW_ROLE_PROJECTS === '1';
const skipWebServer = process.env.PW_SKIP_WEBSERVER === '1';
const storageDir = process.env.PW_STORAGE_DIR;
const storageStatePath = (envName: string, fileName: string, fallback: string) =>
  process.env[envName] || (storageDir ? `${storageDir}/${fileName}` : fallback);
const adminStorageState = storageStatePath(
  'PW_ADMIN_STORAGE_STATE',
  'admin.json',
  './tests/storage/admin.json',
);
const operatorStorageState = storageStatePath(
  'PW_OPERATOR_STORAGE_STATE',
  'operator.json',
  './tests/storage/operator.json',
);
const viewerStorageState = storageStatePath(
  'PW_VIEWER_STORAGE_STATE',
  'viewer.json',
  './tests/storage/viewer.json',
);
const artifactDir = process.env.PW_ARTIFACT_DIR || './test-results/artifacts';
const reportDir = process.env.PW_REPORT_DIR || './test-results/html-report';
const resultsJson = process.env.PW_RESULTS_JSON || './test-results/results.json';
const deepSpecPattern = /.*-deep\.spec\.ts$/;
const quoteOpsCurrentSpecNames = [
  'enterprise-info-profile',
  'quote-bom-focused-menu-and-permissions',
  'bom-workbench-golden',
  'quote-minimal-create-regression',
  'quote-bom-visual-feedback-golden',
  'quote-corrected-bom-upload-golden',
  'quote-nonstd-bom-upload-golden',
  'quote-bom-price-manual-adoption',
  'form-and-overlay-golden',
  'quote-bom-price-yunhan-adoption',
  'quote-bom-price-ladder-moq',
  'quote-bom-price-deepseek-suggestions',
  'quote-process-fee-review',
  'quote-gerber-runtime',
  'quote-excel-download',
  'quote-bulk-import-price-cache-golden',
  // Per-role suites (DDR-2026-06-29 §8). These were listed on the gate script's
  // command line but the `quoteops` project testMatch dropped them silently, so the
  // gate went green WITHOUT running them — they must be in this list to actually run.
  'role-capability-closed-loop',
  'quote-bom-role-menu-smoke',
  'bom-workbench-role-eng-golden',
  'quote-role-sales-golden',
  'quote-role-proc-golden',
  'quote-bom-soft-delete-golden',
  'bom-workbench-self-scope-golden',
  // Manual-intervention path through the import gateway. The automatic path is
  // heavily guarded; this one had no UI coverage at all until now.
  'bom-import-gateway-manual-path',
];
const quoteOpsCurrentGatePattern = new RegExp(
  String.raw`.*\/pcba-solution\/(${quoteOpsCurrentSpecNames.join('|')})\.spec\.ts$`,
);

const enterpriseScopeDirs = [
  'annual-plan',
  'asset-management',
  'construction-process',
  'contract-cost',
  'doc-knowledge',
  'dual-prevention',
  'enterprise',
  'finance',
  'finance-accounting',
  'inventory',
  'license',
  'logistics',
  'maintenance',
  'marketplace',
  'pcba',
  'pcba-solution',
  'payment',
  'procurement',
  'product-catalog',
  'project-management',
  'quality',
  'quarry',
  'sales',
  'sales-templates',
  'tax',
  'templates',
];
const contractScopeDirs = [
  'action-system',
  'activity',
  'agent-control-plane',
  'approval',
  'bpm',
  'command',
  'dashboard',
  'data-tools',
  'e2et-order',
  'integration',
  'model',
  'named-query',
  'plugin',
  'query-builder',
  'scheduler',
  'search',
  'smart-components',
];
const scopeRegex = (dirs: string[]) => new RegExp(`.*\\/(${dirs.join('|')})\\/.*\\.spec\\.ts$`);
const enterpriseScopeFilePatterns = [
  /.*\/aurabot\/pcba-.*\.spec\.ts$/,
  /.*\/crm\/crm-(agent-ui-smoke|calendar-sync|campaign-sla|dashboard|dashboard-enhanced|email-features|inbound-channel|inbound-lifecycle|inbound-smoke|merge-queue|opportunity-currency|quote-complaint|web-form)\.spec\.ts$/,
  /.*\/cs-agent\/cs-agent-email-lifecycle\.spec\.ts$/,
  /.*\/plugin\/asset-.*\.spec\.ts$/,
  /.*\/plugin\/pcba-.*\.spec\.ts$/,
  /.*\/plugin\/pm-.*\.spec\.ts$/,
  /.*\/plugin\/plugin-all-packages-smoke\.spec\.ts$/,
];
const enterpriseScopeRegex = scopeRegex(enterpriseScopeDirs);
const enterpriseProfileRegex = scopeRegex([...enterpriseScopeDirs, 'plugin']);
const enterpriseScopeRegexes = [enterpriseScopeRegex, ...enterpriseScopeFilePatterns];
const enterpriseProfileMatch = [enterpriseProfileRegex, ...enterpriseScopeFilePatterns];
const contractScopeRegex = scopeRegex(contractScopeDirs);

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
    ...(runProfile === 'rbac'
      ? [
          {
            name: 'rbac-setup',
            testDir: './tests/api/setup',
            // RBAC targeted setup intentionally excludes 02-test-pages, which
            // owns system_overview/page_schema dashboard fixtures unrelated to
            // data-scope authorization.
            testMatch: /\/0[013]-.*\.spec\.ts$/,
            timeout: 120_000,
            use: {
              ...devices['Desktop Chrome'],
            },
          },
          {
            name: 'rbac-auth',
            testMatch: /auth\.setup\.ts/,
            timeout: 30000,
            dependencies: ['rbac-setup'],
            use: {
              ...devices['Desktop Chrome'],
              actionTimeout: 15000,
              navigationTimeout: 15000,
            },
          },
        ]
      : [
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
        ]),
    // init-env (legacy bundled spec) is excluded from the setup project's
    // testMatch — it pre-dates the 00/01/02 split and would re-do steps
    ...(runProfile === 'fast' || runProfile === 'full'
      ? [
          {
            name: 'chromium',
            testDir: './tests/e2e',
            // Exclude resource-intensive deep designer tests — they get their own project (chromium-deep)
            // with workers:1 to prevent browser OOM crashes from concurrent heavy DOM operations.
            testIgnore: deepSpecPattern,
            dependencies: ['auth'],
            use: {
              ...devices['Desktop Chrome'],
              storageState: adminStorageState,
            },
          },
          {
            name: 'chromium-deep',
            testDir: './tests/e2e',
            testMatch: deepSpecPattern,
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
    ...(runProfile === 'oss'
      ? [
          {
            name: 'oss',
            testDir: './tests/e2e',
            testIgnore: [deepSpecPattern, ...enterpriseScopeRegexes, contractScopeRegex],
            dependencies: ['auth'],
            use: {
              ...devices['Desktop Chrome'],
              storageState: adminStorageState,
            },
          },
          {
            name: 'oss-deep',
            testDir: './tests/e2e',
            testMatch: deepSpecPattern,
            testIgnore: [...enterpriseScopeRegexes, contractScopeRegex],
            dependencies: ['oss'],
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
    ...(runProfile === 'contract'
      ? [
          {
            name: 'contract',
            testDir: './tests/e2e',
            testMatch: contractScopeRegex,
            testIgnore: deepSpecPattern,
            dependencies: ['auth'],
            use: {
              ...devices['Desktop Chrome'],
              storageState: adminStorageState,
            },
          },
        ]
      : []),
    ...(runProfile === 'enterprise-smoke'
      ? [
          {
            name: 'enterprise-smoke',
            testDir: './tests/e2e',
            testMatch: enterpriseProfileMatch,
            grep: /@smoke/,
            dependencies: ['auth'],
            use: {
              ...devices['Desktop Chrome'],
              storageState: adminStorageState,
            },
          },
        ]
      : []),
    ...(runProfile === 'enterprise-full'
      ? [
          {
            name: 'enterprise-full',
            testDir: './tests/e2e',
            testMatch: enterpriseProfileMatch,
            dependencies: ['auth'],
            use: {
              ...devices['Desktop Chrome'],
              storageState: adminStorageState,
            },
          },
        ]
      : []),
    ...(runProfile === 'quoteops'
      ? [
          {
            name: 'quoteops',
            testDir: './tests/e2e',
            testMatch: quoteOpsCurrentGatePattern,
            dependencies: ['auth'],
            use: {
              ...devices['Desktop Chrome'],
              storageState: adminStorageState,
            },
          },
        ]
      : []),
    ...(runProfile === 'rbac'
      ? [
          {
            name: 'rbac-chromium',
            testDir: './tests/e2e/permission',
            testMatch: /dynamic-data-scope-runtime\.spec\.ts$/,
            dependencies: ['rbac-auth'],
            use: {
              ...devices['Desktop Chrome'],
              storageState: adminStorageState,
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
