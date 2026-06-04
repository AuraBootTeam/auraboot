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

// Self-contained config for the isolated crm-gap stack (G2 Team Commission manager
// dashboard). No webServer, no auth setup project — the spec performs its own UI login.
const baseURL = process.env.PLAYWRIGHT_BASE_URL || 'http://localhost:5239';

export default defineConfig({
  testDir: '.',
  testMatch: ['crm-g2-team-dashboard.spec.ts'],
  outputDir: '/tmp/g2-e2e/artifacts',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  // The dashboard renders several widgets (KPI cards + bar chart + two tables), each
  // backed by a named query, and the app polls in the background. 120s gives real
  // headroom; the helpers wait on concrete elements so a genuine hang still surfaces.
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
