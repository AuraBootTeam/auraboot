/**
 * Permission - Negative Access E2E Tests
 *
 * Tests E-N01 ~ E-N04: Unauthorized and error-handling access scenarios
 * - Unauthenticated URL access redirects to login
 * - API call without auth returns 401
 * - Non-existent resource shows proper error page
 * - Non-existent API resource returns proper error status
 *
 * Uses real database and API, NO MOCKING.
 *
 * @since 4.0.0
 */

import { test, expect } from '../../fixtures';
import { test as baseTest } from '@playwright/test';


/**
 * Unauthenticated access tests.
 * Uses a fresh browser context WITHOUT storageState to simulate a visitor
 * who has never logged in.
 */
baseTest.describe('Permission - Unauthenticated Access', () => {
  baseTest.use({ storageState: { cookies: [], origins: [] } });

  /**
   * E-N01: Unauthenticated URL access redirects to login
   * Navigate to a protected page without any auth state.
   * The app should redirect to /login or display the login form.
   */
  baseTest('E-N01: Unauthenticated URL access redirects to login', async ({ page }) => {
    // Attempt to access a protected page directly
    await page.goto(`/meta/models`);
    await page.waitForLoadState('domcontentloaded');

    // The app should redirect to login or show the login form
    const loginForm = page.locator('input#email, input[type="email"]');
    const hasLoginForm = await loginForm.isVisible({ timeout: 5000 }).catch(() => false);

    const url = page.url();
    const isOnLoginPage = url.includes('login') || url.endsWith('/');

    // Either the login form is displayed or the URL indicates a login redirect
    expect(hasLoginForm || isOnLoginPage).toBe(true);

    // The protected content should NOT be visible
    const modelTable = page.locator('table, [class*="model-list"], [data-testid="model-list"]');
    const hasModelContent = await modelTable.isVisible({ timeout: 2000 }).catch(() => false);
    expect(hasModelContent).toBe(false);
  });

  /**
   * E-N02: API call without auth returns 401
   * Send a direct API request without any authentication cookies or tokens.
   * The backend should reject the request with 401 Unauthorized.
   */
  baseTest('E-N02: API call without auth returns 401', async ({ request }) => {
    // Call a protected API endpoint without any auth
    const response = await request.get(`/api/meta/models`);
    const status = response.status();

    // Backend should return 401 (Unauthorized) or 403 (Forbidden)
    expect([401, 403]).toContain(status);

    // Verify the response body is not the actual model data
    const body = await response.text();
    let parsed: any = null;
    try {
      parsed = JSON.parse(body);
    } catch {
      // Non-JSON response is acceptable for an error
    }

    if (parsed && parsed.data) {
      // If there is a data field, it should not be an array of models
      expect(Array.isArray(parsed.data) && parsed.data.length > 0).toBe(false);
    }
  });
});

/**
 * Authenticated tests for resource-not-found scenarios.
 * Uses the default authenticated storageState (admin user).
 */
test.describe('Permission - Resource Not Found', () => {
  /**
   * E-N03: Non-existent resource returns proper error page
   * Navigate to a model detail page with a fabricated PID.
   * The app should show a 404 message or "not found" indicator, not a crash.
   */
  test('E-N03: Non-existent resource shows proper error page', async ({ page }) => {
    // Navigate to a model detail page with a non-existent PID
    const fakePid = 'nonexistent-pid-12345';
    await page.goto(`/meta/models/${fakePid}`);
    await page.waitForLoadState('domcontentloaded');

    // The model detail page loader throws a 404 Response when model is not found.
    // React Router's root ErrorBoundary renders:
    //   <h1>404</h1>
    //   <p>The requested page could not be found.</p>
    //   <button>返回首页</button>
    //
    // Check for the error boundary's 404 heading or error text
    const errorBoundary404 = page.locator('h1:has-text("404")');
    const errorMessage = page.locator('text=The requested page could not be found');
    const returnButton = page.locator('text=返回首页');

    const has404 = await errorBoundary404.isVisible({ timeout: 5000 }).catch(() => false);
    const hasErrorMsg = await errorMessage.isVisible({ timeout: 2000 }).catch(() => false);
    const hasReturnBtn = await returnButton.isVisible({ timeout: 2000 }).catch(() => false);

    // Check if the page redirected back to model list (also acceptable behavior)
    const url = page.url();
    const redirectedToList = url.endsWith('/meta/models') || url.includes('/meta/models?');

    // The page should NOT crash with an unhandled error
    const crashIndicator = page.locator('text=Something went wrong, text=Unhandled Runtime Error, text=Cannot read properties');
    const hasCrash = await crashIndicator.first().isVisible({ timeout: 1000 }).catch(() => false);
    expect(hasCrash).toBe(false);

    // At least one graceful behavior should be present
    expect(has404 || hasErrorMsg || hasReturnBtn || redirectedToList).toBe(true);
  });

  /**
   * E-N04: Non-existent API resource returns proper error status
   * Call the model detail API with a fabricated PID.
   * The backend should return an error with a proper status code (404 or error body).
   */
  test('E-N04: Non-existent API resource returns proper error', async ({ page }) => {
    const fakePid = 'nonexistent-pid-12345';

    // Call the API directly for a non-existent model
    const response = await page.request.get(`/api/meta/models/${fakePid}`);
    const status = response.status();

    // The API should return 404 (Not Found) or 400/500 with an error body
    // Some APIs return 200 with an error payload instead of HTTP error codes
    if (status === 200) {
      // If 200, the response body should indicate failure (e.g., success=false, data=null)
      const body = await response.json();
      // At least one of these conditions should indicate a failed/empty response
      const isSuccessWithData =
        body.success === true &&
        body.data !== null &&
        body.data !== undefined &&
        (body.code === 200 || body.code === 0);
      expect(isSuccessWithData).toBe(false);
    } else {
      // HTTP error status: 400, 404, or 500 are all acceptable for non-existent resource
      expect(status).toBeGreaterThanOrEqual(400);
    }

    // Also verify a completely random API path returns an error
    const randomResponse = await page.request.get(
      `/api/meta/models/this-definitely-does-not-exist-99999`
    );
    const randomStatus = randomResponse.status();

    if (randomStatus === 200) {
      const randomBody = await randomResponse.json();
      // Should not return real model data
      const hasRealData = randomBody.data && randomBody.data.pid && randomBody.data.code;
      expect(hasRealData).toBeFalsy();
    } else {
      expect(randomStatus).toBeGreaterThanOrEqual(400);
    }
  });
});
