import { test, expect } from '@playwright/test';

/**
 * Permission v2 capability editor — real-browser golden against the live backend.
 *
 * Proves the new Capabilities tab renders real capability data served by
 * GET /api/permission/capabilities (here convention-derived from the role's permission codes,
 * since the minimal-bootstrap stack ships no capabilities.json) and is interactive: toggling a
 * capability enables Save, toggling back disables it. Non-destructive on purpose — it never clicks
 * Save, so it cannot revoke the admin role's own permissions mid-session. The grant/revoke
 * persistence is covered by CapabilityRoleEditor RTL tests + the backend write-endpoint/registry
 * unit + real-stack ITs.
 */
test.describe('Permission v2 capability editor', () => {
  test('renders the capability checklist for a role and toggles Save dirty-state', async ({ page }) => {
    await page.goto('/enterprise/permissions');

    // Roles load and the first one is auto-selected.
    await expect(page.getByTestId('role-table')).toBeVisible();

    // Open the Capabilities tab (the primary v2 surface; the raw matrix stays under Permissions).
    await page.getByTestId('permission-right-tab-capabilities').click();

    // The checklist renders with real backend capability data.
    const checklist = page.getByTestId('capability-checklist');
    await expect(checklist).toBeVisible();
    const checkboxes = checklist.locator('input[type="checkbox"]');
    await expect(checkboxes.first()).toBeVisible();
    expect(await checkboxes.count()).toBeGreaterThan(0);

    // Save is disabled until the selection differs from the granted baseline.
    const save = page.getByTestId('capability-save');
    await expect(save).toBeDisabled();

    // Toggle a capability -> the checkbox flips and Save becomes enabled (dirty).
    const first = checkboxes.first();
    const before = await first.isChecked();
    await first.click();
    expect(await first.isChecked()).toBe(!before);
    await expect(save).toBeEnabled();

    // Toggle back to the baseline -> Save disabled again. (No Save click => non-destructive.)
    await first.click();
    expect(await first.isChecked()).toBe(before);
    await expect(save).toBeDisabled();

    await page.screenshot({ path: 'test-results/permission-capability-golden.png', fullPage: true });
  });
});
