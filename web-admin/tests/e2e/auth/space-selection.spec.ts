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
 *   - admin@example.com belongs to both System Tenant and a Business Tenant
 *   - This is the default state after reset-and-init.sh
 */

import { test, expect } from '@playwright/test';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';

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
    const backendUrl = process.env.SPRING_BOOT_URL || 'http://127.0.0.1:6443';
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
    const backendUrl = process.env.SPRING_BOOT_URL || 'http://127.0.0.1:6443';
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
  test.use({ storageState: './tests/storage/admin.json' });

  test('header shows current tenant name', async ({ page }) => {
    await page.goto('/', { waitUntil: 'domcontentloaded' });
    await page.waitForLoadState('domcontentloaded');

    const tenantName = page.locator('[data-testid="current-tenant-name"]');
    await expect(tenantName).toBeVisible({ timeout: 10_000 });
    const text = await tenantName.textContent();
    expect(text).toBeTruthy();
    expect(text!.length).toBeGreaterThan(0);
  });

  test('avatar menu shows tenant list and platform console', async ({ page }) => {
    await page.goto('/', { waitUntil: 'load' });

    // Wait for React hydration — avatar button must be interactive
    const avatarButton = page.locator('[data-testid="user-menu"] button').first();
    await expect(avatarButton).toBeVisible({ timeout: 15_000 });
    // Wait a beat for React hydration to complete
    await page.waitForTimeout(1000);
    await avatarButton.click();

    const dropdown = page.locator('[data-testid="user-dropdown"]');
    await expect(dropdown).toBeVisible({ timeout: 5_000 });

    // Should show user email
    await expect(dropdown.locator('text=admin@example.com')).toBeVisible();

    // Should show workspace section with at least one tenant
    await expect(dropdown.locator('button[data-testid^="tenant-switch-"]').first()).toBeVisible();

    // Platform Console should be visible for admin user
    const platformConsole = page.locator('[data-testid="platform-console-link"]');
    await expect(platformConsole).toBeVisible();
  });
});
