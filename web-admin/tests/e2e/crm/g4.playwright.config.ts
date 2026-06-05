import { defineConfig, devices } from '@playwright/test';
import dns from 'node:dns';
import { loadEnv } from '../../helpers/environments';

dns.setDefaultResultOrder('ipv4first');
delete process.env.http_proxy;
delete process.env.HTTP_PROXY;
delete process.env.https_proxy;
delete process.env.HTTPS_PROXY;
process.env.NO_PROXY = 'localhost,127.0.0.1';
process.env.no_proxy = process.env.NO_PROXY;

// Self-contained config for the isolated crm-gap stack (G4 payout Post-to-GL action).
const baseURL = loadEnv('crm-gap').urls.base;

export default defineConfig({
  testDir: '.',
  testMatch: ['crm-g4-payout-gl.spec.ts'],
  outputDir: '/tmp/g4-e2e/artifacts',
  fullyParallel: false,
  workers: 1,
  retries: 0,
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
  projects: [{ name: 'chromium-m5', use: { ...devices['Desktop Chrome'] } }],
});
