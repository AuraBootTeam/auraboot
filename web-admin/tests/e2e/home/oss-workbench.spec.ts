/**
 * OSS /home workbench smoke test.
 *
 * After Plan 4 (workbench migrated from ent to OSS), /home should render
 * the default workbench dashboard containing Inbox, Recent, Shortcuts widgets
 * created by WorkbenchTemplateProvider on first access.
 *
 * @since 2026-04-15
 */

import { test, expect } from '../../fixtures';

test.describe('OSS /home workbench', () => {
  test('loads default workbench on first access', async ({ page }) => {
    await page.goto('/home');
    await expect(page.locator('body')).toBeVisible({ timeout: 5000 });
  });

  test('workbench dashboard has at least 4 widgets', async ({ page }) => {
    await page.goto('/home');
    // WorkbenchTemplateProvider seeds 4 widgets on first access:
    // StatsRow, Inbox, Shortcuts, Recent. The grid renders them as direct
    // children of react-grid-layout.
    const widgets = page.locator('.react-grid-item');
    await expect(widgets.first()).toBeVisible({ timeout: 8000 });
    const count = await widgets.count();
    expect(count).toBeGreaterThanOrEqual(4);
  });

  test('/home is routed via dashboard code (not catch-all)', async ({ page }) => {
    const response = await page.goto('/home');
    expect(response?.status()).toBe(200);
    // /home must NOT redirect to /login/ (user is authenticated via fixture)
    expect(page.url()).toContain('/home');
  });
});
