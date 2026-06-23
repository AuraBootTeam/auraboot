/**
 * Golden-stack warm spec — host-first visual-golden harness pre-warm.
 *
 * Run by scripts/oss-golden-stack.sh's internal `warm` step (after the
 * `setup` + `auth` projects have produced tests/storage/admin.json) to make
 * the FIRST real golden run reliable:
 *
 *   - Drives a real authenticated headless navigation to the heavy lazy
 *     designer routes (/report-designer, /dashboard) so the client lazy
 *     chunk + Vite client-dep graph is hot (compiled + cached) before any
 *     golden spec runs. web-admin/vite.config.ts already pre-bundles the
 *     heavy deps via optimizeDeps.include (#947); this is the
 *     belt-and-suspenders client-side warm for the route chunks themselves.
 *   - Asserts the designer actually mounted (block-palette visible) so the
 *     warm is a real load, not a 302-to-/login (which would mean auth is
 *     broken and the operator should see it now, not mid-golden).
 *
 * It runs in the `chromium` project (storageState = admin.json) and is
 * targeted by the harness with `--grep @golden-warm --no-deps`. It is NOT
 * part of any golden suite's assertions — it only primes the dev server.
 */
import { test, expect } from '@playwright/test';

test.describe('@golden-warm host-first golden stack pre-warm', () => {
  test('warm /report-designer (heavy lazy chunk)', async ({ page }) => {
    await page.goto('/report-designer', { waitUntil: 'domcontentloaded' });
    // A real authenticated mount — block-palette only renders when the
    // designer chunk loaded AND the session was accepted (no /login bounce).
    await expect(page.getByTestId('block-palette')).toBeVisible({ timeout: 30000 });
    await expect(page.getByTestId('report-canvas')).toBeVisible({ timeout: 30000 });
  });

  test('warm /dashboard', async ({ page }) => {
    const resp = await page.goto('/dashboard', { waitUntil: 'domcontentloaded' });
    // Don't over-assert on dashboard structure (it varies by seed); just
    // confirm we did not get bounced to /login and the chunk compiled.
    expect(page.url()).not.toContain('/login');
    expect(resp?.ok() ?? true).toBeTruthy();
  });
});
