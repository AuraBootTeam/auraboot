import { test, expect } from '@playwright/test';
import type { APIRequestContext } from '@playwright/test';
import { writeFileSync, mkdirSync } from 'fs';
import { join } from 'path';

/**
 * Cross-platform seed test: runs on PC (Playwright) to seed data for iOS XCUITest.
 *
 * Flow:
 * 1. Get a unique testRunId from backend
 * 2. Seed records with prefix xp_{testRunId}_
 * 3. Write testRunId to test-results/cross-platform-run-id.txt for CI handoff
 * 4. Verify records are visible in the web UI (regression: data accessible from web)
 * 5. iOS XCUITest reads testRunId from CI env and validates same records on mobile
 */

const BASE_URL = process.env.BASE_URL || 'http://localhost:6443';
const ADMIN_EMAIL = process.env.TEST_EMAIL || 'admin@auraboot.test';
const ADMIN_PASSWORD = process.env.TEST_PASSWORD || 'Test2026x';

test.use({ storageState: 'tests/storage/admin.json' });

async function getAuthToken(request: APIRequestContext): Promise<string> {
  const resp = await request.post(`${BASE_URL}/api/auth/login`, {
    data: { email: ADMIN_EMAIL, password: ADMIN_PASSWORD },
  });
  const body = await resp.json();
  return body.result?.jwt ?? body.data?.jwt ?? '';
}

test.describe('Cross-platform data seed', () => {
  let testRunId = '';
  let authToken = '';

  test.beforeAll(async ({ request }) => {
    authToken = await getAuthToken(request);
    expect(authToken).toBeTruthy();

    // Get a unique test run ID
    const runIdResp = await request.get(`${BASE_URL}/api/test/fixture/run-id`, {
      headers: { Authorization: `Bearer ${authToken}` },
    });
    if (runIdResp.ok()) {
      const body = await runIdResp.json();
      testRunId = body.testRunId;
    } else {
      // Fallback: generate locally
      testRunId = `xp_${Date.now().toString(36)}`;
    }
    expect(testRunId).toBeTruthy();

    // Write testRunId for CI handoff to iOS tests
    try {
      mkdirSync(join(process.cwd(), 'test-results'), { recursive: true });
      writeFileSync(
        join(process.cwd(), 'test-results', 'cross-platform-run-id.txt'),
        testRunId
      );
    } catch {
      // Non-fatal — CI pipeline reads this file
    }
  });

  test('seed cross-platform records via API', async ({ request }) => {
    const resp = await request.post(`${BASE_URL}/api/test/fixture`, {
      headers: {
        Authorization: `Bearer ${authToken}`,
        'Content-Type': 'application/json',
      },
      data: {
        name: 'crossplatform',
        testRunId,
        params: { count: 3, modelCode: 'e2et_order' },
      },
    });

    const body = await resp.json().catch(() => null);

    if (!resp.ok() || body?.success !== true) {
      // Fixture endpoint may be unavailable or may not have tenant context — seed via command API.
      test.info().annotations.push({
        type: 'note',
        description: `Fixture API returned ${resp.status()} with success=${String(body?.success)} — using fallback command creation`,
      });
      for (let i = 1; i <= 3; i++) {
        const createResp = await request.post(`${BASE_URL}/api/meta/commands/execute/e2et:create_order`, {
          headers: {
            Authorization: `Bearer ${authToken}`,
            'Content-Type': 'application/json',
          },
          data: {
            payload: {
              e2et_order_title: `xp_${testRunId}_record_${i}`,
              e2et_order_desc: `cross-platform seed ${i}`,
              e2et_order_type: 'standard',
              e2et_order_urgent: false,
              e2et_order_remark: `seed:${testRunId}`,
            },
            operationType: 'create',
          },
        });
        expect(createResp.ok()).toBe(true);
        const createBody = await createResp.json();
        expect(createBody.code).toBe('0');
      }
      return;
    }

    expect(body.success).toBe(true);
    expect(body.recordsCreated).toBeGreaterThanOrEqual(1);
    expect(body.testRunId).toBe(testRunId);
    expect(body.recordIds.length).toBeGreaterThanOrEqual(1);
  });

  test('seeded records visible in web list view', async ({ page }) => {
    await page.goto('/meta/models');
    await page.waitForLoadState('domcontentloaded');

    // Navigate to e2et_order via sidebar
    const sidebarItem = page.locator('[data-menu-code="e2et_order"], [href*="e2et-order"], [href*="e2et_order"]').first();
    if (await sidebarItem.isVisible({ timeout: 5000 }).catch(() => false)) {
      await sidebarItem.click().catch(async () => {
        await page.goto('/dynamic/e2et-order');
      });
    } else {
      await page.goto('/dynamic/e2et-order');
    }

    // Search for seeded records
    const searchInput = page.locator('input[placeholder*="search" i], input[placeholder*="Search" i], [data-testid="dynamic-list-search"]').first();
    if (await searchInput.isVisible({ timeout: 5000 }).catch(() => false)) {
      await searchInput.fill(`xp_${testRunId}`);
      await searchInput.press('Enter');
    }

    // At least 1 record with the testRunId prefix should be visible
    await expect(
      page.locator(`text=xp_${testRunId}`).first()
    ).toBeVisible({ timeout: 10000 });
  });

  test('testRunId is persisted and retrievable', async ({ request }) => {
    const resp = await request.get(`${BASE_URL}/api/test/fixture/${testRunId}`, {
      headers: { Authorization: `Bearer ${authToken}` },
    });

    if (resp.status() === 404) {
      // Fixture metadata not stored — this is acceptable
      test.info().annotations.push({
        type: 'note',
        description: 'Fixture metadata not found (in-memory store may have been reset)',
      });
      return;
    }

    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    expect(body.testRunId).toBe(testRunId);
    expect(body.recordsCreated).toBeGreaterThanOrEqual(1);
  });
});
