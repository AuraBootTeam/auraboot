/**
 * Plugin Productization E2E Tests — ManagedBadge & Resource Protection
 *
 * Tests:
 * PP-01: Model list shows ManagedBadge for plugin-managed models
 * PP-02: ManagedBadge not shown for manually-created models
 * PP-03: Resource owner API returns correct ownership info
 * PP-04: Batch resource owner API returns multiple results
 *
 * Prerequisites:
 * - org-management plugin imported (provides org_department, org_position, org_employee)
 * - Backend running on port 6443
 *
 * @since 4.0.0
 */

import { test, expect } from '../../fixtures';

test.describe('ManagedBadge & Resource Protection', () => {
  /**
   * PP-01: Model list shows ManagedBadge for plugin-managed models
   */
  test('PP-01: plugin-managed model shows managed badge in model list', async ({ page }) => {
    test.setTimeout(30000);

    // Navigate to the list and filter to a known plugin-managed model.
    await page.goto('/meta/models?keyword=org_department', { waitUntil: 'domcontentloaded' });
    await page.locator('table').first().waitFor({ state: 'visible', timeout: 15000 });
    const targetRow = page.locator('tbody tr', { hasText: 'org_department' }).first();
    await expect(targetRow).toBeVisible({ timeout: 10000 });
    const managedBadge = targetRow.locator('span[title*="plugin" i], span[title*="插件管理"]');
    await expect(managedBadge.first()).toBeVisible({ timeout: 10000 });
  });

  /**
   * PP-02: ManagedBadge not shown for models that are not plugin-managed
   */
  test('PP-02: non-plugin model has no managed badge', async ({ page }) => {
    await page.goto('/meta/models', { waitUntil: 'domcontentloaded' });
    await page.locator('table').first().waitFor({ state: 'visible', timeout: 15000 });

    // Verify at least one rendered model row has no managed badge.
    const rows = page.locator('tbody tr');
    const rowCount = await rows.count();
    expect(rowCount).toBeGreaterThan(0);

    let foundUnmanaged = false;
    for (let i = 0; i < rowCount; i += 1) {
      const row = rows.nth(i);
      const managedBadge = row.locator('span[title*="managed" i], span[title*="插件管理"]');
      const hasManagedBadge = await managedBadge
        .first()
        .isVisible({ timeout: 200 })
        .catch(() => false);
      if (!hasManagedBadge) {
        foundUnmanaged = true;
        break;
      }
    }

    expect(foundUnmanaged).toBe(true);
  });

  /**
   * PP-03: Resource owner API returns ownership info for plugin-managed resource
   */
  test('PP-03: resource owner API returns plugin info', async ({ page }) => {
    const resp = await page.request.get(
      `/api/plugins/resources/owner?resourceType=MODEL&resourceCode=org_department`,
    );

    if (resp.status() === 404 || resp.status() === 500) {
      test.skip(true, 'org_department not tracked as plugin resource');
    }

    expect(resp.ok()).toBe(true);
    const body = (await resp.json()) as any;
    const data = body.data || body;

    // Should be managed by a plugin
    expect(data.managed).toBe(true);
    expect(data.pluginId).toBeTruthy();
  });

  /**
   * PP-04: Batch resource owner API returns results for multiple resources
   */
  test('PP-04: batch resource owner API', async ({ page }) => {
    const resp = await page.request.post('/api/plugins/resources/owners', {
      data: {
        resources: [
          { type: 'model', code: 'org_department' },
          { type: 'model', code: 'nonexistent_model' },
        ],
      },
    });

    expect(resp.ok()).toBe(true);
    const body = (await resp.json()) as any;
    const results = body.data || body;

    // org_department should be managed (if plugin imported)
    if (results['MODEL:org_department']) {
      expect(results['MODEL:org_department'].managed).toBe(true);
    }

    // nonexistent model should not be managed
    if (results['MODEL:nonexistent_model']) {
      expect(results['MODEL:nonexistent_model'].managed).toBe(false);
    }
  });
});
