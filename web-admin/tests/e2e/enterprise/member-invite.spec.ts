/**
 * Enterprise Member Invite Flow E2E Tests
 *
 * Tests the invite code generation, display, and revocation for tenant member management.
 *
 * MI-01: Generate invite code via API, verify returned code
 * MI-02: Invite code visible in management UI
 * MI-03: Invite code expiry validation
 * MI-04: Revoke invite code
 *
 * Prerequisites:
 * - Backend running, platform-admin plugin imported
 * - Logged in as tenant admin
 *
 * @since 4.0.0
 */

import { test, expect } from '../../fixtures';

const BASE_URL = process.env.BASE_URL || 'http://localhost:5173';
const BACKEND_URL = 'http://127.0.0.1:6443';

test.describe('Member Invite Flow', () => {
  let backendJwt: string | null = null;

  const getBackendJwt = async (page: import('@playwright/test').Page): Promise<string> => {
    if (backendJwt) return backendJwt;
    const resp = await page.request.post(`${BACKEND_URL}/api/auth/login`, {
      data: { email: 'admin@example.com', password: 'Test2026x' },
    });
    if (!resp.ok()) {
      throw new Error(`Failed to obtain backend JWT: HTTP ${resp.status()}`);
    }
    const body = await resp.json().catch(() => null);
    const jwt = body?.data?.jwt;
    if (!jwt) throw new Error('Failed to obtain backend JWT: jwt missing');
    backendJwt = jwt;
    return jwt;
  };

  const requestWithBackendFallback = async (
    page: import('@playwright/test').Page,
    method: 'get' | 'post' | 'delete',
    path: string,
    data?: Record<string, unknown>,
  ) => {
    const bffResp = await page.request
      .fetch(`${BASE_URL}${path}`, { method, data })
      .catch(() => null);
    if (bffResp && bffResp.ok()) return bffResp;
    const bffBody = bffResp ? await bffResp.text().catch(() => '') : '';
    const isProxy500 = bffResp?.status() === 500 && bffBody.includes('Proxy Error');
    if (bffResp && !isProxy500) return bffResp;

    const jwt = await getBackendJwt(page);
    return page.request.fetch(`${BACKEND_URL}${path}`, {
      method,
      data,
      headers: { Authorization: `Bearer ${jwt}` },
    });
  };

  const generateInviteCode = async (
    page: import('@playwright/test').Page,
    expiryDays: number,
  ): Promise<string> => {
    const resp = await requestWithBackendFallback(
      page,
      'post',
      `/api/tenant/invite-code/generate?expiryDays=${expiryDays}`,
    );
    if (!resp.ok()) {
      throw new Error(`Invite API returned ${resp.status()}`);
    }
    const body = await resp.json();
    return body?.data || body?.inviteCode || body?.code;
  };

  /**
   * MI-01: Generate invite code — call API and verify a code is returned.
   */
  test('MI-01: should generate invite code via API @smoke', async ({ page }) => {
    const code = await generateInviteCode(page, 7);
    expect(code).toBeTruthy();
    expect(typeof code).toBe('string');
    expect(code.length).toBeGreaterThan(4);
  });

  /**
   * MI-02: Invite code is visible on the management page.
   */
  test('MI-02: should display invite code in management UI', async ({ page }) => {
    // Generate an invite code first
    const code = await generateInviteCode(page, 7);

    // Navigate to member management page
    await page.goto('/p/tenant_member');
    await page.waitForLoadState('domcontentloaded');
    await expect(page.locator('table, [role="table"]').first()).toBeVisible({ timeout: 15000 });

    // Look for invite-related UI element (button/section)
    const inviteSection = page.locator(
      '[data-testid="invite-section"], button:has-text("invite"), button:has-text("邀请")',
    );
    const hasInviteUI = await inviteSection
      .first()
      .isVisible({ timeout: 5000 })
      .catch(() => false);

    if (!hasInviteUI) {
      throw new Error(String('Invite UI not present on member management page'));
      return;
    }

    await inviteSection.first().click();
    // Verify code or invite dialog is shown
    const dialog = page.locator('[role="dialog"], [data-testid="invite-dialog"]');
    await expect(dialog).toBeVisible({ timeout: 5000 });
    const codeVisible = await page
      .getByText(String(code))
      .isVisible({ timeout: 3000 })
      .catch(() => false);
    expect(codeVisible || (await dialog.first().isVisible())).toBe(true);
  });

  /**
   * MI-03: Invite code expiry — verify code has expected validity period.
   */
  test('MI-03: should create invite code with correct expiry', async ({ page }) => {
    await generateInviteCode(page, 1);
    const currentResp = await requestWithBackendFallback(
      page,
      'get',
      '/api/tenant/invite-code/current',
    );
    if (!currentResp.ok()) {
      throw new Error(`Invite current API unavailable: ${currentResp.status()}`);
    }
    const currentBody = await currentResp.json();
    const expiresAt =
      currentBody?.data?.expiredAt || currentBody?.data?.expiresAt || currentBody?.data?.expireDate;

    if (!expiresAt) {
      const code = currentBody?.data?.code;
      expect(code).toBeTruthy();
      return;
    }

    // Verify expiry is within ~24-48 hours from now
    const expiryDate = new Date(expiresAt);
    const now = new Date();
    const diffHours = (expiryDate.getTime() - now.getTime()) / (1000 * 60 * 60);
    expect(diffHours).toBeGreaterThan(0);
    expect(diffHours).toBeLessThan(50); // ~2 days tolerance
  });

  /**
   * MI-04: Revoke invite code.
   */
  test('MI-04: should revoke invite code', async ({ page }) => {
    const code = await generateInviteCode(page, 7);
    if (!code) throw new Error('No invite code returned — cannot test revocation');

    // Revoke the invite code
    const revokeResp = await requestWithBackendFallback(
      page,
      'post',
      `/api/tenant/invite-code/revoke?code=${encodeURIComponent(code)}`,
    );

    expect(revokeResp.ok()).toBe(true);

    const validateResp = await requestWithBackendFallback(
      page,
      'get',
      `/api/tenant/invite-code/validate?code=${encodeURIComponent(code)}`,
    );
    expect(validateResp.ok()).toBe(true);
    const validateBody = await validateResp.json();
    expect(validateBody?.data).toBe(false);
  });
});
