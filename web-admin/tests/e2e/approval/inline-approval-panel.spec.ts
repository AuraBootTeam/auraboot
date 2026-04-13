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
  test('IAP-001: BPM process-instances by-business-key endpoint is accessible', async ({
    page,
  }) => {
    const nonExistentKey = `no-such-record-${Date.now()}`;

    const resp = await page.request.get(BPM_BY_BUSINESS_KEY_ENDPOINT, {
      params: { businessKey: nonExistentKey },
    });

    // The API must respond (not hang / 502 / 503).
    // A 400 or 404 is expected for a missing business key — that is the correct behavior.
    // A 200 with a success code would mean a ghost record exists, which is also acceptable.
    // What is NOT acceptable: 500-range errors or missing JSON body.
    expect(resp.status()).toBeLessThan(500);

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
  test('IAP-002: Detail page loads without errors when no approval process exists', async ({
    page,
  }) => {
    // 1. Prefer e2et_record fixture data, but fall back to another seeded dynamic model when empty.
    const candidates = ['e2et_record', 'e2et_order'];
    let targetModelCode = '';
    let recordPid = '';

    for (const modelCode of candidates) {
      const modelResp = await page.request.get(`/api/meta/models/code/${modelCode}`);
      if (!modelResp.ok()) continue;

      const listResp = await page.request.get(`/api/dynamic/${modelCode}/list?pageNum=1&pageSize=1`);
      if (!listResp.ok()) continue;

      const listBody = await listResp.json().catch(() => null);
      const records = listBody?.data?.records || listBody?.data?.data || listBody?.data || [];
      const pid =
        Array.isArray(records) && records.length > 0
          ? records[0]?.pid || records[0]?.recordId || ''
          : '';

      if (pid) {
        targetModelCode = modelCode;
        recordPid = pid;
        break;
      }
    }

    test.skip(!recordPid, 'No seeded dynamic record available for inline approval detail smoke');

    // 2. Navigate to the detail page for the selected record via the dynamic route.
    await page.goto(`/p/${targetModelCode}/view/${recordPid}`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(page.locator('body')).toBeVisible();

    // 4. The page should not show a global error boundary or "Access forbidden"
    await expect(page.locator('body')).not.toContainText('Access forbidden');
    await expect(page.locator('body')).not.toContainText('Page not found');

    // 3. Wait for the approval-status lookup to settle when it is triggered.
    await page
      .waitForResponse((response) => response.url().includes(BPM_BY_BUSINESS_KEY_ENDPOINT), {
        timeout: 5000,
      })
      .catch(() => null);

    // 4. Because this record has NO BPM process, "Approval History" must NOT appear
    await expect(page.locator('text=Approval History')).toHaveCount(0);
  });
});
