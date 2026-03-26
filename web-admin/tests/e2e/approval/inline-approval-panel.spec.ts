/**
 * InlineApprovalPanel E2E Smoke Tests
 *
 * Tests IAP-001 ~ IAP-002:
 *   IAP-001: BPM process-instances API endpoint is accessible
 *   IAP-002: A DSL detail page loads successfully without errors (even without an
 *            associated approval process — InlineApprovalPanel renders null gracefully)
 *
 * Prerequisites:
 *   - Backend running at localhost:6443
 *   - Frontend dev server running at localhost:5173
 *   - Any DSL model with at least one record (uses e2et_record from the test-order plugin,
 *     falling back to any available dynamic model)
 *
 * Uses real APIs. NO MOCKING.
 *
 * @since 7.1.0
 */

import { test, expect } from '../../fixtures';

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const BPM_BY_BUSINESS_KEY_ENDPOINT = '/api/bpm/process-instances/by-business-key/status';

// ---------------------------------------------------------------------------
// IAP-001: BPM endpoint is reachable and returns expected shape
// ---------------------------------------------------------------------------

test.describe('InlineApprovalPanel', () => {
  test.describe.configure({ mode: 'serial', timeout: 30000 });

  /**
   * IAP-001
   * Verify the by-business-key/status endpoint is accessible.
   * When called with a non-existent businessKey, the backend should return a
   * structured error response (400/404) — NOT a 500 or connection error.
   * This confirms the BPM module is wired up correctly.
   */
  test('IAP-001: BPM process-instances by-business-key endpoint is accessible', async ({ page }) => {
    const nonExistentKey = `no-such-record-${Date.now()}`;

    const resp = await page.request.get(BPM_BY_BUSINESS_KEY_ENDPOINT, {
      params: { businessKey: nonExistentKey },
    });

    // The API must respond (not hang / 502 / 503).
    // A 400 or 404 is expected for a missing business key — that is the correct behavior.
    // A 200 with a success code would mean a ghost record exists, which is also acceptable.
    // What is NOT acceptable: 500-range errors or missing JSON body.
    expect(resp.status()).toBeLessThan(400);

    const body = await resp.json().catch(() => null);
    expect(body).not.toBeNull();
    // The response must always have a `code` field (ApiResponse envelope)
    expect(body).toHaveProperty('code');
  });

  /**
   * IAP-002
   * Navigate to a DSL detail page and confirm:
   *   1. The page loads without a JS error / crash
   *   2. The InlineApprovalPanel gracefully renders nothing when no approval
   *      process is associated with the record
   *   3. No "Approval History" heading appears (because there is no BPM process)
   *
   * Uses the e2et_record model from the e2e-test-order plugin.
   * If that model is unavailable, the test is skipped.
   */
  test('IAP-002: Detail page loads without errors when no approval process exists', async ({ page }) => {
    // 1. Verify the e2et_record model is available
    const modelResp = await page.request.get('/api/meta/models/code/e2et_record');
    expect(modelResp.ok()).toBe(true);

    // 2. Reuse setup fixture data instead of depending on a non-existent create command.
    const listResp = await page.request.get('/api/dynamic/e2et-record/list?pageNum=1&pageSize=1');
    expect(listResp.ok()).toBe(true);
    const listBody = await listResp.json();
    const records = listBody?.data?.records || listBody?.data?.data || listBody?.data || [];
    const recordPid: string = Array.isArray(records) && records.length > 0
      ? (records[0]?.pid || records[0]?.recordId || '')
      : '';
    expect(recordPid).toBeTruthy();

    // 3. Navigate to the detail page for this record via the dynamic route
    //    The URL pattern is /dynamic/e2et-record/{pid}/view
    await page.goto(`/dynamic/e2et-record/${recordPid}/view`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(page.locator('body')).toBeVisible();

    // 4. The page should not show a global error boundary or "Access forbidden"
    await expect(page.locator('body')).not.toContainText('Access forbidden');
    await expect(page.locator('body')).not.toContainText('Page not found');

    // 5. Wait for the approval-status lookup to settle when it is triggered.
    await page.waitForResponse(
      (response) => response.url().includes(BPM_BY_BUSINESS_KEY_ENDPOINT),
      { timeout: 5000 }
    ).catch(() => null);

    // 6. Because this record has NO BPM process, "Approval History" must NOT appear
    await expect(page.locator('text=Approval History')).toHaveCount(0);
  });
});
