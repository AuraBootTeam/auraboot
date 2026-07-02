/**
 * Space Selection & SpaceSwitcher E2E Tests
 *
 * Tests the multi-tenant space selection infrastructure:
 *   - my-spaces API returns correct platform + business spaces
 *   - Selecting a space returns a new JWT with tenantId
 *   - SpaceSwitcher component visibility
 *   - /_action/switch-space route properly switches tenants
 *
 * NOTE: Tests for login → /tenant-selection redirect require MULTI mode.
 * In SINGLE mode, getTenantIdByUserId auto-selects the default tenant
 * so users never see the space selection page.
 *
 * Prerequisites:
 *   - admin@auraboot.com belongs to both System Tenant and a Business Tenant
 *   - This is the default state after reset-and-init.sh
 */

import { test, expect } from '@playwright/test';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';
import { BACKEND_URL } from '../../helpers/environments';

const ADMIN = DEFAULT_TEST_ACCOUNT;

test.describe('Space Selection API', () => {

  test('my-spaces API returns platform + business spaces', async ({ page }) => {
    // Login to get token
    const loginRes = await page.request.post('/api/auth/login', {
      data: { email: ADMIN.email, password: ADMIN.password },
    });
    expect(loginRes.ok()).toBeTruthy();
    const token = (await loginRes.json()).data.jwt;

    // Fetch spaces
    const spacesRes = await page.request.get('/api/tenant-selection/my-spaces', {
      headers: { Authorization: `Bearer ${token}` },
    });
    expect(spacesRes.ok()).toBeTruthy();

    const spaces = (await spacesRes.json()).data;
    expect(spaces.length).toBeGreaterThanOrEqual(2);

    // Should have both platform and business spaces
    const platformSpaces = spaces.filter((s: any) => s.spaceType === 'platform');
    const businessSpaces = spaces.filter((s: any) => s.spaceType === 'business');
    expect(platformSpaces.length).toBeGreaterThanOrEqual(1);
    expect(businessSpaces.length).toBeGreaterThanOrEqual(1);

    // Verify space data structure — tenantId is string (safe from JS precision loss)
    for (const space of spaces) {
      expect(space.tenantId).toBeTruthy();
      expect(typeof space.tenantId).toBe('string');
      expect(space.tenantName).toBeTruthy();
      expect(space.spaceType).toMatch(/^(platform|business)$/);
      expect(space.roleCodes).toBeDefined();
    }
  });

  test('selecting a space returns new JWT with tenantId', async ({ page }) => {
    // Login
    const loginRes = await page.request.post('/api/auth/login', {
      data: { email: ADMIN.email, password: ADMIN.password },
    });
    const token = (await loginRes.json()).data.jwt;

    // Get business space
    const spacesRes = await page.request.get('/api/tenant-selection/my-spaces', {
      headers: { Authorization: `Bearer ${token}` },
    });
    const spaces = (await spacesRes.json()).data;
    const businessSpace = spaces.find((s: any) => s.spaceType === 'business');
    expect(businessSpace).toBeTruthy();

    // Select the business space — call backend directly to avoid BFF precision issues
    const backendUrl = BACKEND_URL;
    const selectRes = await page.request.fetch(`${backendUrl}/api/tenant-selection/process`, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json',
      },
      data: `{"action":"select","tenantId":${businessSpace.tenantId}}`,
    });
    expect(selectRes.ok()).toBeTruthy();

    const selectBody = await selectRes.json();
    expect(selectBody.data.status).toBe('success');
    expect(selectBody.data.jwt).toBeTruthy();
    // tenantId is now serialized as string from backend
    expect(String(selectBody.data.tenantId)).toBe(String(businessSpace.tenantId));

    // JWT should be valid (may be same as original in SINGLE mode where login already has tenantId)
    expect(selectBody.data.jwt.length).toBeGreaterThan(50);
  });

  test('selecting platform space returns JWT with system tenant', async ({ page }) => {
    // Login
    const loginRes = await page.request.post('/api/auth/login', {
      data: { email: ADMIN.email, password: ADMIN.password },
    });
    const token = (await loginRes.json()).data.jwt;

    // Get platform space
    const spacesRes = await page.request.get('/api/tenant-selection/my-spaces', {
      headers: { Authorization: `Bearer ${token}` },
    });
    const spaces = (await spacesRes.json()).data;
    const platformSpace = spaces.find((s: any) => s.spaceType === 'platform');
    expect(platformSpace).toBeTruthy();

    // Select the platform space — call backend directly to avoid BFF precision issues
    const backendUrl = BACKEND_URL;
    const selectRes = await page.request.fetch(`${backendUrl}/api/tenant-selection/process`, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json',
      },
      data: `{"action":"select","tenantId":${platformSpace.tenantId}}`,
    });
    expect(selectRes.ok()).toBeTruthy();

    const selectBody = await selectRes.json();
    expect(selectBody.data.status).toBe('success');
    expect(selectBody.data.jwt).toBeTruthy();
    expect(String(selectBody.data.tenantId)).toBe(String(platformSpace.tenantId));
  });
});

test.describe('Tenant Switch in Avatar Menu', () => {
  test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });

  test('header shows current tenant name', async ({ page }) => {
    await page.goto('/', { waitUntil: 'domcontentloaded' });
    await page.waitForLoadState('domcontentloaded');

    // The header surfaces the current tenant either as an explicit name span OR,
    // when the tenant name duplicates the env chip (e.g. "AuraBoot Dev" ending
    // with a "Dev" chip), it is deduped into brand + env chip on purpose
    // (Header.tsx tenantDuplicatesChip). Accept whichever rendering applies.
    const tenantName = page.locator('[data-testid="current-tenant-name"]');
    const envChip = page.locator('[data-testid="header-env-chip"]');
    // Settle the avatar (proxy for header hydration) before deciding the branch.
    await expect(page.locator('[data-testid="user-menu"] button').first()).toBeVisible({
      timeout: 15_000,
    });

    if (await tenantName.isVisible({ timeout: 5_000 }).catch(() => false)) {
      const text = await tenantName.textContent();
      expect(text?.trim().length ?? 0).toBeGreaterThan(0);
    } else {
      // Deduped path: the tenant is encoded by the brand + env chip.
      await expect(envChip).toBeVisible({ timeout: 5_000 });
      const chip = (await envChip.textContent())?.trim() ?? '';
      expect(chip.length).toBeGreaterThan(0);
    }
  });

  test('avatar menu shows tenant list and platform console', async ({ page }) => {
    await page.goto('/', { waitUntil: 'load' });

    // Wait for React hydration — avatar button must be interactive, and the
    // header marks data-hydrated once its click handlers are attached (slow CI
    // containers render the SSR avatar many seconds before hydration finishes).
    const avatarButton = page.locator('[data-testid="user-menu"] button').first();
    await expect(avatarButton).toBeVisible({ timeout: 15_000 });
    await page
      .locator('header[data-hydrated="true"]')
      .waitFor({ state: 'attached', timeout: 15_000 })
      .catch(() => null);

    const dropdown = page.locator('[data-testid="user-dropdown"]');
    await expect
      .poll(
        async () => {
          if (await dropdown.isVisible({ timeout: 250 }).catch(() => false)) {
            return true;
          }
          await avatarButton.click().catch(() => null);
          return dropdown.isVisible({ timeout: 500 }).catch(() => false);
        },
        { timeout: 5_000, intervals: [100, 250, 500, 1000] },
      )
      .toBe(true);
    await expect(dropdown).toBeVisible({ timeout: 5_000 });

    // Should show user email
    await expect(dropdown.locator('text=admin@auraboot.com')).toBeVisible();

    // Should show workspace section with at least one tenant
    await expect(dropdown.locator('button[data-testid^="tenant-switch-"]').first()).toBeVisible();

    // Platform Console should be visible for admin user
    const platformConsole = page.locator('[data-testid="platform-console-link"]');
    await expect(platformConsole).toBeVisible();
  });
});
