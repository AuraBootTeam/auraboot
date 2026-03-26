/**
 * Entitlement System Smoke Tests
 *
 * Verifies API availability and default bypass state (entitlement.enabled=false).
 * Uses storageState for authentication (auto-configured in playwright.config).
 */

import { test, expect } from '../../fixtures';

test.describe('Entitlement System Smoke Tests', () => {
  test('entitlement API returns disabled state by default', async ({ page }) => {
    const resp = await page.request.get('/api/entitlements');
    expect(resp.ok()).toBeTruthy();
    const data = await resp.json();
    const result = data.data || data;
    expect(result.enabled).toBe(false);
    expect(result.entitlements).toEqual([]);
  });

  test('entitlement admin list API is accessible', async ({ page }) => {
    const resp = await page.request.get('/api/admin/entitlements?tenantId=1');
    // Should return 200 with empty list (system disabled → returns [])
    expect(resp.ok()).toBeTruthy();
    const data = await resp.json();
    expect(Array.isArray(data.data)).toBeTruthy();
  });

  test('plan admin API returns empty list for unknown plugin', async ({ page }) => {
    const resp = await page.request.get('/api/admin/entitlements/plans?pluginId=nonexistent');
    expect(resp.ok()).toBeTruthy();
    const data = await resp.json();
    expect(Array.isArray(data.data)).toBeTruthy();
    expect(data.data.length).toBe(0);
  });

  test('feature admin API returns empty list for unknown plugin', async ({ page }) => {
    const resp = await page.request.get('/api/admin/entitlements/features?pluginId=nonexistent');
    expect(resp.ok()).toBeTruthy();
    const data = await resp.json();
    expect(Array.isArray(data.data)).toBeTruthy();
    expect(data.data.length).toBe(0);
  });

  test('marketplace plugins load with licenseMode field', async ({ page }) => {
    const resp = await page.request.get('/api/marketplace/plugins');
    expect(resp.ok()).toBeTruthy();
    const data = await resp.json();
    const plugins = data.data?.records || data.data;
    if (Array.isArray(plugins) && plugins.length > 0) {
      const first = plugins[0];
      // Should have licenseMode field (renamed from licenseType)
      expect('licenseMode' in first).toBeTruthy();
    }
  });

  test('entitlement tables exist in database', async ({ page }) => {
    // Verify via admin API that the system is functional
    const resp = await page.request.get('/api/admin/entitlements/audit-log?tenantId=1&limit=1');
    expect(resp.ok()).toBeTruthy();
    const data = await resp.json();
    expect(Array.isArray(data.data)).toBeTruthy();
  });
});
