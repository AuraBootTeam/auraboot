/**
 * The 返回 link in a form header must land somewhere that exists.
 *
 * Reported from production (2026-07-22): clicking 返回 on `/p/bom_start_conversion/new`
 * rendered an error page. The link was hard-coded to `/p/{urlPrefix}`, which the router
 * resolves as the pageKey `{urlPrefix}_list` — a page that only exists when the form is a
 * CRUD form reached from its own list. Every other kind of form (command-entry, custom
 * route, singleton settings) linked at a page nobody created.
 *
 * Unit tests cover the resolution rules; this spec covers what they cannot: that the
 * declared target survives plugin import → DB → page-schema API, and that clicking the
 * link actually lands on a rendered page instead of an error.
 *
 * Uses the two OSS platform-admin pages that carry the fix, because they need no business
 * plugin: enterprise_info_form declares a target, system_preferences_form declares "none".
 */
import { test, expect, type Page } from '../fixtures';

test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });

const SHOTS = process.env.BACK_LINK_SHOTS || 'test-results/form-back-link';
const BACK_LINK = '[data-testid="form-back-link"]';

/** An error state renders these; a healthy page must show none of them. */
async function assertNoErrorState(page: Page, label: string) {
  const body = (await page.locator('main, body').first().innerText()).slice(0, 4000);
  expect(body, `${label} rendered an error`).not.toMatch(
    /Page not found|No schema found|Invalid pageType|Failed to load|Application Error/i,
  );
}

test.describe('form header back link', () => {
  test('a declared target is served by the API and lands on a real page', async ({ page }) => {
    await page.goto('/p/c/enterprise_info_form');
    // Not networkidle: the dev server holds an HMR socket open, so it never settles.
    await expect(page.locator('[data-testid="ab:form:enterprise_info_form:container"]')).toBeVisible();
    await assertNoErrorState(page, 'enterprise_info_form');

    // The link renders here — which is what gives the "absent" assertion in the next
    // test its power. Without this, "no back link" would pass on any broken page.
    const link = page.locator(BACK_LINK);
    await expect(link).toBeVisible();
    await expect(link).toHaveAttribute('href', '/p/c/enterprise_info_detail');
    await page.screenshot({ path: `${SHOTS}/01-form-with-back-link.png`, fullPage: true });

    await link.click();
    await expect(page).toHaveURL(/\/p\/c\/enterprise_info_detail$/);
    // Landing on the route is not landing on a page: `main` is already visible while the
    // skeleton renders, so wait for the detail page's own container and a real field.
    await expect(
      page.locator('[data-testid="ab:detail:enterprise_info_detail:container"]'),
    ).toBeVisible();
    await expect(page.locator('[data-testid="form-field-displayName"]')).toBeVisible();
    await assertNoErrorState(page, 'enterprise_info_detail (after clicking 返回)');
    await page.screenshot({ path: `${SHOTS}/02-landed-on-detail.png`, fullPage: true });
  });

  test('a page declaring "none" renders no back link at all', async ({ page }) => {
    await page.goto('/p/c/system_preferences_form');
    // Prove the page really rendered as a form before asserting something is missing from it.
    await expect(
      page.locator('[data-testid="ab:form:system_preferences_form:container"]'),
    ).toBeVisible();
    await expect(page.locator('[data-testid="dynamic-form"]')).toBeVisible();
    await assertNoErrorState(page, 'system_preferences_form');
    await expect(page.locator(BACK_LINK)).toHaveCount(0);
    await page.screenshot({ path: `${SHOTS}/03-no-back-link.png`, fullPage: true });
  });
});
