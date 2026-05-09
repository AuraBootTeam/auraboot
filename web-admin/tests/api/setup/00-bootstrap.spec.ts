/**
 * Setup Phase 0 — Bootstrap.
 *
 * Drives /api/bootstrap/setup if the backend is not yet initialized.
 * Idempotent: a second call hits the "already initialized" branch and
 * exits clean.
 *
 * Replaces oss-reset-and-init.sh step 4.5 + 7.4. The /api/bootstrap/setup
 * endpoint creates BOTH:
 *   - System Tenant (id=1) with platform_admin role + grant to admin user
 *   - Business Tenant (the configured `companyName`) with tenant_admin
 *     role + grant to admin user
 * So we don't need a separate platform_admin grant step — the endpoint
 * does it via BootstrapEngineService.bootstrapSystemTenant().
 *
 * Why this lives in tests/api/setup/ (Playwright project), not the
 * reset-and-init shell script:
 *   - Test data prep is the test suite's responsibility, not infra.
 *   - Same code runs against host stack OR isolated docker stack — the
 *     setup uses PLAYWRIGHT_BASE_URL so it points at whichever vite/BFF
 *     port the runner was launched against.
 *   - Wired as the first project in playwright.oss.config.ts so all
 *     downstream projects (auth, chromium, chromium-deep) inherit a
 *     ready environment.
 *
 * Pre-conditions:
 *   - Backend is up and `/actuator/health` returns 200.
 *   - For isolated stack: `AURABOOT_BOOTSTRAP_ENABLED=false` so the
 *     auto-runner doesn't race this call (auto-runner creates only a
 *     demo tenant; this endpoint creates the full System+Business
 *     setup).
 */

import { test, expect } from '@playwright/test';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';

const COMPANY_NAME = process.env.AURA_BOOTSTRAP_COMPANY ?? 'AuraBoot Dev';

test.describe.configure({ mode: 'serial' });

test('00-bootstrap: ensure system is initialized via /api/bootstrap/setup', async ({
  request,
}) => {
  // Step 1: probe current state.
  const status = await request.get('/api/bootstrap/status');
  expect(status.ok()).toBeTruthy();
  const statusBody = await status.json();
  const initialized = statusBody?.data?.initialized === true;

  if (initialized) {
    // eslint-disable-next-line no-console
    console.log('[setup-00] system already initialized — skipping bootstrap');
    return;
  }

  // Step 2: drive the setup endpoint.
  const setup = await request.post('/api/bootstrap/setup', {
    data: {
      companyName: COMPANY_NAME,
      adminEmail: DEFAULT_TEST_ACCOUNT.email,
      adminPassword: DEFAULT_TEST_ACCOUNT.password,
      adminDisplayName: 'Admin User',
      systemMode: 'single',
      // We seed the heavyweight demo data via the seed-showcase-* specs
      // so we keep this fast and focused.
      seedDemoData: false,
    },
  });

  // The endpoint always responds 200; success/failure is in body.code.
  expect(setup.status()).toBe(200);
  const body = await setup.json();
  expect(body.code, `bootstrap failed: ${JSON.stringify(body)}`).toBe('0');
  expect(body.data?.tenantId, 'bootstrap should return business tenantId').toBeTruthy();

  // Step 3: cross-check that initialized flag flipped.
  const recheck = await request.get('/api/bootstrap/status');
  const recheckBody = await recheck.json();
  expect(recheckBody?.data?.initialized).toBe(true);
});
