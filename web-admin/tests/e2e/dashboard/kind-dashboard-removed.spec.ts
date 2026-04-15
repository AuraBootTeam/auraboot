/**
 * Verifies Plan 3a: kind=dashboard removed from V2 blocks.
 *
 * After removal:
 * - Former kind=dashboard pages (seeded into ab_dashboard) are accessible via
 *   /dashboards?code={pageKey}.
 * - Legacy /p/c/{pageKey} routes for those keys no longer resolve to a V2 block
 *   dashboard render. They should 404, show empty state, or redirect — anything
 *   except rendering as a V2 blocks page.
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

    test(`Legacy /p/c/${code} does not render as V2 blocks dashboard`, async ({ page }) => {
      const response = await page.goto(`/p/c/${code}`);
      const status = response?.status() ?? 0;
      expect([200, 302, 404]).toContain(status);
      if (status === 200) {
        const url = page.url();
        expect(url).not.toMatch(new RegExp(`/p/c/${code}$`));
      }
    });
  }
});
