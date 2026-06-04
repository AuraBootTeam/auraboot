import { defineConfig, devices } from '@playwright/test';
import dns from 'node:dns';

// Prefer IPv4 for localhost (BFF listens on IPv4 only).
dns.setDefaultResultOrder('ipv4first');
delete process.env.http_proxy;
delete process.env.HTTP_PROXY;
delete process.env.https_proxy;
delete process.env.HTTPS_PROXY;
process.env.NO_PROXY = 'localhost,127.0.0.1';
process.env.no_proxy = process.env.NO_PROXY;

// Self-contained config for the isolated CRM-gap stack (PCBA M5 / J6 sell-side RFQ).
// No webServer, no auth setup project — the spec performs its own UI login in beforeEach.
// Default base URL targets the crm-gap stack's vite port; override via PLAYWRIGHT_BASE_URL.
const baseURL = process.env.PLAYWRIGHT_BASE_URL || 'http://localhost:5239';

export default defineConfig({
  testDir: '.',
  testMatch: ['crm-m5-pcba.spec.ts'],
  outputDir: '/tmp/m5-e2e/artifacts',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  // The PCBA detail pages render several tabs/sub-tables and the app polls in the
  // background, so a single UI flow (login + nav + open detail + assert) can take ~30s.
  // 120s gives real headroom without masking a hang (the helpers wait on concrete
  // elements, so a genuine failure still surfaces well before this cap).
  timeout: 120_000,
  reporter: [['list']],
  use: {
    baseURL,
    headless: true,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
    actionTimeout: 10_000,
    navigationTimeout: 20_000,
    launchOptions: { args: ['--no-proxy-server'] },
  },
  projects: [
    {
      name: 'chromium-m5',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
});
