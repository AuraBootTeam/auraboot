/**
 * Site Key Registry — DSL Admin Page Golden Spec (SP1)
 *
 * Proves the site-key registry management UI works end-to-end in a real browser,
 * driving every action point and asserting state changes + backend persistence:
 *
 *   Step 1  List page renders from the DSL: localized headers, no raw field-code leak.
 *   Step 2  Create: toolbar → form (name only) → submit behavior_site_key:create.
 *   Step 3  List shows the new row with a SERVER-GENERATED abk_ key + status 启用 (active).
 *   Step 4  DB proof: the row exists with a non-null abk_ site_key the user never typed.
 *   Step 5  Disable: row action → confirm → status flips to 已禁用 (disabled).
 *   Step 6  DB proof: status='disabled' for that row.
 *   Step 7  Zero product-level console errors across the whole flow.
 *
 * Prereqs: a host-first stack is UP with the core-site-key plugin imported, and the
 * Playwright env points at it (PLAYWRIGHT_BASE_URL / BACKEND_URL / PG*). Run with:
 *   PW_SKIP_WEBSERVER=1 npx playwright test -c playwright.config.ts \
 *     --project chromium tests/e2e/behavior/site-key-registry.golden.spec.ts
 */

import { test, expect } from '../../fixtures';
import type { Page } from '@playwright/test';
import { execSync } from 'node:child_process';
import { PSQL_BASE, PG_ENV } from '../../helpers/environments';

function psql(sql: string): string {
  return execSync(`${PSQL_BASE} -P pager=off -t -A -c "${sql.replace(/"/g, '\\"')}"`, {
    encoding: 'utf-8',
    env: PG_ENV,
    timeout: 10_000,
  }).trim();
}

function isDevNoise(text: string): boolean {
  return /Outdated Optimize Dep|Failed to fetch dynamically imported module|504 |Loading chunk|entry\.client|Importing a module script failed|HMR|[Vv]ite|websocket|favicon/i.test(text);
}
function isProductError(text: string): boolean {
  if (isDevNoise(text)) return false;
  return /exprError|Maximum update depth|Invalid hook call|is not a function|Internal system error|Application Error|TypeError|ReferenceError|AWAITING DATA|Cannot read prop|Bad parameter/i.test(text);
}

const LIST_URL = '/p/behavior_site_key';

test.describe('Site Key Registry — Admin DSL Page Golden', () => {
  test.setTimeout(120_000);
  test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });

  let consoleErrors: string[] = [];
  test.beforeEach(async ({ page }) => {
    consoleErrors = [];
    page.on('console', (msg) => { if (msg.type() === 'error') consoleErrors.push(msg.text()); });
    page.on('pageerror', (err) => consoleErrors.push(`PAGEERROR: ${err.message}`));
  });

  test('SK-01 create → list shows generated abk_ key → disable → disabled', async ({ page }) => {
    const unique = `Golden Landing ${Date.now()}`;

    // ── STEP 1: list renders, localized headers, no raw code leak ──────────────
    await page.goto(LIST_URL, { waitUntil: 'domcontentloaded' });
    await page.waitForLoadState('networkidle').catch(() => null);
    const main = page.locator('main');
    await expect(main.getByText('站点密钥', { exact: false }).first()).toBeVisible({ timeout: 15_000 });
    // Localized column headers present; raw field code must NOT leak as a header.
    await expect(main).toContainText('名称');
    await expect(main).toContainText('状态');
    const mainText = (await main.textContent()) || '';
    expect(mainText, 'list must not leak raw field code "name" as a header label').not.toMatch(/\bsc_name\b|\bBOM_/);
    await page.screenshot({ path: 'test-results/sitekey-01-list.png', fullPage: true });

    // ── STEP 2: create via toolbar → form (name only) → submit ─────────────────
    await page.getByTestId('toolbar-btn-create').click();
    await expect(page.getByTestId('dynamic-form')).toBeVisible({ timeout: 15_000 });
    const nameInput = page.locator('[data-testid="form-field-name"] input, [data-testid="form-field-name"] textarea').first();
    await nameInput.click();
    await nameInput.pressSequentially(unique, { delay: 10 }); // controlled input → real onChange
    await page.screenshot({ path: 'test-results/sitekey-02-form.png', fullPage: true });
    await page.getByTestId('form-btn-submit').click();

    // Form redirects back to the list on success; navigate explicitly to be safe.
    await page.waitForTimeout(1500);
    await page.goto(LIST_URL, { waitUntil: 'domcontentloaded' });
    await page.waitForLoadState('networkidle').catch(() => null);

    // ── STEP 3: new row shows the SERVER-GENERATED abk_ key + active status ─────
    const row = page.getByRole('row').filter({ hasText: unique });
    await expect(row).toBeVisible({ timeout: 15_000 });
    await expect(row, 'row must show a server-generated abk_ site key').toContainText(/abk_[0-9A-Za-z]{20,}/);
    await expect(row, 'new key status is active (启用)').toContainText(/启用|Active/);
    await page.screenshot({ path: 'test-results/sitekey-03-created.png', fullPage: true });

    // ── STEP 4: DB proof — row persisted with a non-null abk_ key ──────────────
    const dbKey = psql(`SELECT site_key FROM mt_behavior_site_key WHERE name='${unique.replace(/'/g, "''")}'`);
    expect(dbKey, 'DB row has a server-generated abk_ key the user never typed').toMatch(/^abk_[0-9A-Za-z]{20,}$/);
    const dbStatus1 = psql(`SELECT status FROM mt_behavior_site_key WHERE name='${unique.replace(/'/g, "''")}'`);
    expect(dbStatus1).toBe('active');

    // ── STEP 5: disable via row action → confirm → status flips ────────────────
    await disableRow(page, row);
    await page.waitForTimeout(1000);
    await page.goto(LIST_URL, { waitUntil: 'domcontentloaded' });
    await page.waitForLoadState('networkidle').catch(() => null);
    const rowAfter = page.getByRole('row').filter({ hasText: unique });
    await expect(rowAfter).toBeVisible({ timeout: 15_000 });
    await expect(rowAfter, 'status flips to disabled (已禁用)').toContainText(/已禁用|Disabled/);
    await page.screenshot({ path: 'test-results/sitekey-04-disabled.png', fullPage: true });

    // ── STEP 6: DB proof — status disabled ─────────────────────────────────────
    const dbStatus2 = psql(`SELECT status FROM mt_behavior_site_key WHERE name='${unique.replace(/'/g, "''")}'`);
    expect(dbStatus2).toBe('disabled');

    // ── STEP 7: zero product console errors ────────────────────────────────────
    const real = consoleErrors.filter(isProductError);
    expect(real, `product console errors:\n${real.join('\n')}`).toHaveLength(0);
  });
});

/**
 * Click the row's Disable action + confirm. The list renders the first action
 * (view) inline and the rest behind a "more" trigger whose dropdown is portaled
 * to the page body (not a row descendant). Row actions are hover-gated.
 */
async function disableRow(page: Page, row: ReturnType<Page['getByRole']>): Promise<void> {
  await row.hover(); // reveal hover-gated row actions
  const inlineDisable = row.getByTestId('row-action-disable');
  if (await inlineDisable.count() > 0) {
    await inlineDisable.first().click();
  } else {
    // Disable is in the overflow menu — open it; the dropdown is portaled to body.
    await row.getByTestId('row-action-more').click();
    await page.getByTestId('row-action-disable').first().click();
  }
  // Confirmation dialog (button declares confirm: behavior_site_key.disable.confirm).
  const ok = page.getByTestId('confirm-ok');
  await expect(ok).toBeVisible({ timeout: 10_000 });
  await ok.click();
}
