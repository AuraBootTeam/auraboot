/**
 * Payment System Smoke Tests
 *
 * Verifies core payment API endpoints and UI integration points.
 * Payment is disabled by default (no Stripe keys configured),
 * so tests validate the "disabled mode" behavior where all features
 * are unlocked and checkout is rejected.
 *
 * API base: /api/payment
 * Controllers: CheckoutController, BillingController
 *
 * @since 4.0.0
 */

import { test, expect } from '../../fixtures';

test.describe('Payment System Smoke Tests', () => {
  test('payment config API returns enabled status', async ({ page }) => {
    const res = await page.request.get('/api/payment/config');
    // If payment module not deployed, endpoint returns 500 (NoResourceFoundException)
    // If deployed, should return 200 with config data
    if (res.status() === 500) {
      const body = await res.json();
      expect(body.context?.exception).toBe('NoResourceFoundException');
      test.skip(true, 'Payment module not deployed on running backend');
      return;
    }
    expect(res.ok()).toBeTruthy();
    const body = await res.json();
    expect(body.code).toBe('0');
    expect(body.data).toHaveProperty('enabled');
    expect(typeof body.data.enabled).toBe('boolean');
    expect(body.data).toHaveProperty('publishableKey');
  });

  test('billing history API returns list for current tenant', async ({ page }) => {
    const res = await page.request.get('/api/payment/billing/history');
    if (res.status() === 500) {
      const body = await res.json();
      expect(body.context?.exception).toBe('NoResourceFoundException');
      test.skip(true, 'Payment module not deployed on running backend');
      return;
    }
    expect(res.ok()).toBeTruthy();
    const body = await res.json();
    expect(body.code).toBe('0');
    expect(body.data).toBeInstanceOf(Array);
  });

  test('edition API returns all features enabled when payment disabled', async ({ page }) => {
    const res = await page.request.get('/api/payment/billing/edition');
    if (res.status() === 500) {
      const body = await res.json();
      expect(body.context?.exception).toBe('NoResourceFoundException');
      test.skip(true, 'Payment module not deployed on running backend');
      return;
    }
    expect(res.ok()).toBeTruthy();
    const body = await res.json();
    expect(body.code).toBe('0');
    // When payment is disabled, PlatformFeatureGate grants all features
    expect(body.data.branding).toBe(true);
    expect(body.data.multiTenant).toBe(true);
    expect(body.data.sso).toBe(true);
    expect(body.data.auditLog).toBe(true);
    expect(body.data.advancedRbac).toBe(true);
  });

  test('checkout API rejects when payment disabled', async ({ page }) => {
    const res = await page.request.post('/api/payment/checkout', {
      data: { pluginId: 'test-plugin', planCode: 'pro', billingType: 'one_time' },
    });
    if (res.status() === 500) {
      const body = await res.json();
      if (body.context?.exception === 'NoResourceFoundException') {
        test.skip(true, 'Payment module not deployed on running backend');
        return;
      }
    }
    const body = await res.json();
    // Payment system is disabled — should return error
    expect(body.code).not.toBe('0');
  });

  test('order status API returns error for non-existent order', async ({ page }) => {
    const res = await page.request.get('/api/payment/orders/non-existent-order-pid');
    if (res.status() === 500) {
      const body = await res.json();
      if (body.context?.exception === 'NoResourceFoundException') {
        test.skip(true, 'Payment module not deployed on running backend');
        return;
      }
    }
    const body = await res.json();
    // Order not found — error response
    expect(body.code).not.toBe('0');
  });

  test('billing page loads and shows heading', async ({ page }) => {
    await page.goto('/settings/billing');
    // Dismiss Vite HMR error overlay if present (shadow DOM element)
    await page.evaluate(() => {
      const overlay = document.querySelector('vite-error-overlay');
      if (overlay) overlay.remove();
    });
    const heading = page.locator('h1');
    await expect(heading).toBeVisible({ timeout: 10000 });
    const text = await heading.textContent();
    expect(text).toMatch(/账单|Billing/);
  });

  test('licenses page has billing link', async ({ page }) => {
    await page.goto('/settings/licenses');
    // Wait for page heading to confirm render
    await page.waitForSelector('h1', { timeout: 10000 });
    // Verify billing link exists
    const hasBillingLink = await page.evaluate(() => {
      const links = document.querySelectorAll('a');
      return Array.from(links).some((a) => a.href.includes('/settings/billing'));
    });
    expect(hasBillingLink).toBe(true);
  });
});
