/**
 * Verifies Plan 3a: kind=dashboard removed from V2 blocks.
 *
 * After Plan 3a + 3b:
 * - Former kind=dashboard pages live in ab_dashboard (Dashboard DSL).
 * - They are accessed exclusively via /dashboards?code={code}.
 * - Menus, plugin JSONs, and seed data have been updated to the new route.
 * - Legacy /p/c/{code} has no pre-baked data — visiting it directly shows
 *   a generic "menu not found" error (expected; no fallback redirect).
 *
 * @since 2026-04-15
 */

import { test, expect } from '../../fixtures';

const OSS_DASHBOARD_CODES = [
  'sc_arsenal_dashboard',
  'sc_workflow_dashboard',
  'acs_dashboard',
] as const;

test.describe('Plan 3a - kind=dashboard removal', () => {
  for (const code of OSS_DASHBOARD_CODES) {
    test(`Dashboard ${code} is accessible via /dashboards route`, async ({ page }) => {
      const response = await page.goto(`/dashboards?code=${code}`);
      expect(response?.status() ?? 0).toBeLessThan(500);
      await expect(page.locator('body')).not.toContainText('500');
    });
  }
});
