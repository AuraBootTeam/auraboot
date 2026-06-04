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

const baseURL = process.env.PLAYWRIGHT_BASE_URL || 'http://localhost:5189';

// Self-contained config for the isolated CRM-M1/M2 stack. No webServer, no auth setup
// project — the spec performs its own UI login in beforeEach.
export default defineConfig({
  testDir: '.',
  testMatch: ['crm-m2-icm.spec.ts'],
  outputDir: '/tmp/m2-e2e/artifacts',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 60_000,
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
      name: 'chromium-m1',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
});
