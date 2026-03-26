/**
 * Entitlement Lifecycle Tests
 *
 * Tests the entitlement grant/revoke lifecycle via admin API.
 * With entitlement.enabled=false (default), grant/revoke are no-ops
 * but the API should still respond correctly.
 */

import { test, expect } from '../../fixtures';

test.describe('Entitlement Lifecycle Tests', () => {
  test('admin can grant entitlement (no-op when disabled)', async ({ page }) => {
    const resp = await page.request.post('/api/admin/entitlements/grant', {
      data: {
        tenantId: 1,
        pluginId: 'e2e-test-order',
        planCode: 'lifecycle-test',
        expiresAt: new Date(Date.now() + 30 * 86400000).toISOString(),
      },
    });
    // When entitlement.enabled=false, this is a no-op but should return OK
    expect(resp.status()).toBeLessThan(400);
  });

  test('tenant can view entitlements list', async ({ page }) => {
    const resp = await page.request.get('/api/entitlements');
    expect(resp.ok()).toBeTruthy();
    const data = await resp.json();
    const result = data.data || data;
    expect('enabled' in result).toBeTruthy();
    expect('entitlements' in result).toBeTruthy();
  });

  test('tenant can view single plugin entitlement', async ({ page }) => {
    const resp = await page.request.get('/api/entitlements/e2e-test-order');
    expect(resp.ok()).toBeTruthy();
    // When disabled, returns null/empty (no entitlement needed)
  });

  test('tenant can view plugin features', async ({ page }) => {
    const resp = await page.request.get('/api/entitlements/e2e-test-order/features');
    expect(resp.ok()).toBeTruthy();
  });

  test('trial activation returns appropriate response', async ({ page }) => {
    const resp = await page.request.post('/api/entitlements/e2e-test-order/activate', {
      data: {},
    });
    // When disabled, returns OK with message
    expect(resp.status()).toBeLessThan(400);
  });

  test('audit log is accessible and returns array', async ({ page }) => {
    const resp = await page.request.get('/api/admin/entitlements/audit-log?tenantId=1');
    expect(resp.ok()).toBeTruthy();
    const data = await resp.json();
    expect(Array.isArray(data.data)).toBeTruthy();
  });

  test('token import rejects invalid token', async ({ page }) => {
    const resp = await page.request.post('/api/entitlements/import-token', {
      data: { token: 'test-invalid-token' },
    });
    // Invalid token: HTTP 200 with ApiResponse success=false
    expect(resp.ok()).toBeTruthy();
    const data = await resp.json();
    expect(data.code).not.toBe('0');
  });
});
