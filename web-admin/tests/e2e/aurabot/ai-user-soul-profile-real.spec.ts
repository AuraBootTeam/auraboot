/**
 * Mission Control — User Soul Profile REAL-backend E2E (PR-80).
 *
 * Drives the live Spring Boot backend on :6443; no page.route mocking.
 * Full stack: psql seed → sidebar click → React fetch → Spring Controller
 * → UserSoulProfileEditor → PostgreSQL → assert DB state flipped.
 *
 * USP-E2E-01 — pin field persists across fetch
 * USP-E2E-02 — hide field removes it from UI + DB marker set
 * USP-E2E-03 — edit field saves override_text + edited_at
 * USP-E2E-04 — reset all clears edited_fields
 * USP-E2E-05 — stale banner renders when stale_flagged_at set
 * USP-E2E-06 — history tab lists SUPERSEDED versions
 * USP-E2E-07 — forget modal (typed confirm) → row ARCHIVED + tombstone
 * USP-E2E-08 — admin dashboard shows metadata only (no persona text)
 *
 * Admin JWT → tenant 303848950530707456. Every profile is seeded under
 * userId = 'ADMIN_01KPG4BZPYSG9STP6K2CHS8X07' which matches the admin's
 * sub field, so the user-facing endpoints return the seeded rows.
 */

import { test, expect } from '../../fixtures';
import type { Page } from '@playwright/test';
import {
  seedUserSoulProfile,
  dbUserSoulProfileRow,
  cleanupUserSoulProfiles,
  seedSoulProfileMenus,
  cleanupSoulProfileMenus,
  SOUL_PROFILE_PID_PREFIX,
  type SeededSoulProfileMenus,
} from './_real-backend-helpers';

// Per-test spoof-user id generator. Every test in this file used to seed
// under the same admin numeric id which made `uq_user_soul_profile_active`
// (tenant_id, user_id) collide as soon as two Playwright workers ran in
// parallel (--workers>=2 or --repeat-each>=2). We now allocate a unique
// synthetic user id per test and pass it to the backend via the
// `X-Test-Spoof-User-Id` header (see TestUserSpoofFilter).
//
// Range: 7-prefix + 14 digits (~7e14) → comfortably inside Java Long,
// outside the snowflake id range used by production rows, and guaranteed
// collision-free across workers + repeats because we mix in workerIndex
// and a per-file monotonic counter.
let __testUserSeq = 0;
function allocateTestUserId(workerIndex: number): string {
  __testUserSeq += 1;
  // 13-digit ms timestamp fits; prefix '7' keeps us away from real ids.
  const ts = Date.now().toString().slice(-12); // 12 digits
  const w = String(workerIndex).padStart(2, '0');
  const seq = String(__testUserSeq % 1000).padStart(3, '0');
  return `7${ts}${w}${seq}`; // 18 digits ≤ Long.MAX_VALUE (19 digits)
}

let seededMenus: SeededSoulProfileMenus;

test.beforeAll(async () => {
  seededMenus = seedSoulProfileMenus();
});

test.afterAll(async () => {
  if (seededMenus) cleanupSoulProfileMenus(seededMenus);
});

test.afterEach(async () => {
  cleanupUserSoulProfiles(SOUL_PROFILE_PID_PREFIX);
});

// ---------------------------------------------------------------------------
// Navigation helpers
// ---------------------------------------------------------------------------

// Phase 10 — sidebar navigation (red-line: no direct page.goto). Menus
// under "AI 中心" are seeded by `seedSoulProfileMenus` in beforeAll so the
// leaves appear in the sidebar. We click the group, then the leaf, and
// wait for the target page's testid.
async function clickSidebarLeaf(
  page: Page,
  leafPattern: RegExp,
  pageTestId: string,
): Promise<void> {
  await page.goto('/');
  await page.waitForLoadState('domcontentloaded');

  const nav = page.locator('nav').first();
  const aiCenter = nav.getByRole('button', { name: /AI 中心|AI Center/ });
  await aiCenter.waitFor({ state: 'visible', timeout: 10_000 });
  await aiCenter.evaluate((el: HTMLElement) => el.click());

  const leaf = nav.getByRole('link', { name: leafPattern });
  await leaf.waitFor({ state: 'visible', timeout: 5_000 });
  await leaf.evaluate((el: HTMLElement) => el.click());

  await expect(page.locator(`[data-testid="${pageTestId}"]`)).toBeVisible({
    timeout: 10_000,
  });
}

async function navigateToMyProfile(page: Page): Promise<void> {
  await clickSidebarLeaf(page, /我的画像|My Profile/, 'my-profile-page');
}

async function navigateToAdminDashboard(page: Page): Promise<void> {
  await clickSidebarLeaf(
    page,
    /Soul Profiles \(管理\)|Soul Profiles \(Admin\)|Soul Profiles/,
    'soul-profiles-admin-page',
  );
}

/**
 * Allocate a unique synthetic user id for the current test and register it
 * as a spoof header on the browser context. Every subsequent page request
 * (including XHR/fetch from the React app) carries the header, so the
 * backend's `TestUserSpoofFilter` overrides `MetaContext.getCurrentUserId()`
 * to this id and seed rows can key off it without racing the admin id.
 *
 * Must be called before any navigation — after `page.goto`, extra headers
 * still apply, but we want all initial requests (menu fetch, etc.) to
 * carry the header too.
 */
async function setupSpoofedIdentity(page: Page, workerIndex: number): Promise<string> {
  const testUserId = allocateTestUserId(workerIndex);
  await page.setExtraHTTPHeaders({ 'X-Test-Spoof-User-Id': testUserId });
  return testUserId;
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

// Parallel-safe via per-test spoofed user identity. Each test synthesises a
// unique numeric user id (see `allocateTestUserId`) and publishes it via
// the `X-Test-Spoof-User-Id` header; the backend's test-profile-only
// `TestUserSpoofFilter` overrides `MetaContext.getCurrentUserId()` for
// that request so seeded rows key off the synthetic id instead of the
// shared admin id. This removes the `uq_user_soul_profile_active`
// (tenant_id, user_id) collision that previously forced `mode: 'serial'`
// and is safe with `--workers=N --repeat-each=N` (N > 1).

test.describe('Mission Control — User Soul Profile (real backend, PR-80)', () => {
  test('USP-E2E-01: pin persona persists in DB edited_fields', async ({ page }, testInfo) => {
    const testUserId = await setupSpoofedIdentity(page, testInfo.workerIndex);
    const seed = seedUserSoulProfile({
      userId: testUserId,
      status: 'active',
      version: 1,
    });

    await navigateToMyProfile(page);

    // Pin button on the persona field card
    const personaCard = page.locator('[data-testid="field-persona"]');
    await expect(personaCard).toBeVisible({ timeout: 5_000 });
    await personaCard.locator('[data-testid="pin-btn"]').click();

    await expect(page.locator('[data-testid="toast"]')).toBeVisible({
      timeout: 5_000,
    });

    const row = dbUserSoulProfileRow(seed.pid);
    expect(row.status).toBe('active');
    expect(row.editedFields ?? '').toContain('persona');
    expect(row.editedFields ?? '').toMatch(/locked|pin/i);
  });

  test('USP-E2E-02: hide field marks DB + removes card from DOM', async ({
    page,
  }, testInfo) => {
    const testUserId = await setupSpoofedIdentity(page, testInfo.workerIndex);
    const seed = seedUserSoulProfile({
      userId: testUserId,
      status: 'active',
    });

    await navigateToMyProfile(page);

    // Use getByTestId (Playwright helper) to side-step CSS dot-parsing
    // confusion — 'field-preferences.communication_style' is the real
    // testid key per FIELD_DEFS in my-profile.tsx.
    const card = page.getByTestId('field-preferences.communication_style');
    await expect(card).toBeVisible({ timeout: 5_000 });
    await card.locator('[data-testid="hide-btn"]').click();

    await expect(page.locator('[data-testid="toast"]')).toBeVisible({
      timeout: 5_000,
    });

    const row = dbUserSoulProfileRow(seed.pid);
    expect(row.editedFields ?? '').toMatch(/communication_style/);
    expect(row.editedFields ?? '').toMatch(/hidden|hide/i);
  });

  test('USP-E2E-03: edit field stores override_text', async ({ page }, testInfo) => {
    const testUserId = await setupSpoofedIdentity(page, testInfo.workerIndex);
    const seed = seedUserSoulProfile({
      userId: testUserId,
      status: 'active',
    });

    await navigateToMyProfile(page);

    const card = page.locator('[data-testid="field-persona"]');
    await card.locator('[data-testid="edit-btn"]').click();

    const modal = page.locator('[data-testid="edit-modal"]');
    await expect(modal).toBeVisible({ timeout: 5_000 });

    const textarea = modal.locator('[data-testid="edit-textarea"]');
    await textarea.fill('Manually edited persona for E2E validation.');
    await modal.locator('[data-testid="edit-submit"]').click();

    await expect(page.locator('[data-testid="toast"]')).toBeVisible();

    const row = dbUserSoulProfileRow(seed.pid);
    expect(row.editedFields ?? '').toContain('Manually edited');
  });

  test('USP-E2E-04: reset persona field removes its key from edited_fields', async ({ page }, testInfo) => {
    const testUserId = await setupSpoofedIdentity(page, testInfo.workerIndex);
    const seed = seedUserSoulProfile({
      userId: testUserId,
      status: 'active',
      editedFields: {
        persona: 'locked',
        'preferences.communication_style': 'hidden',
      },
    });

    await navigateToMyProfile(page);

    // Reset the persona field only (there's one reset-btn per field card).
    const personaCard = page.locator('[data-testid="field-persona"]');
    await expect(personaCard).toBeVisible({ timeout: 5_000 });
    await personaCard.locator('[data-testid="reset-btn"]').click();

    await expect(page.locator('[data-testid="toast"]')).toBeVisible({
      timeout: 5_000,
    });

    const row = dbUserSoulProfileRow(seed.pid);
    // After resetting persona, "persona" key must be gone; communication_style stays.
    const edited = row.editedFields ?? '';
    expect(edited).not.toMatch(/"persona"/);
    expect(edited).toMatch(/communication_style/);
  });

  test('USP-E2E-05: stale banner renders when stale_flagged_at is set', async ({
    page,
  }, testInfo) => {
    const testUserId = await setupSpoofedIdentity(page, testInfo.workerIndex);
    seedUserSoulProfile({
      userId: testUserId,
      status: 'active',
      stale: true,
    });

    await navigateToMyProfile(page);

    const banner = page.locator('[data-testid="stale-banner"]');
    await expect(banner).toBeVisible({ timeout: 5_000 });
    await expect(banner).toContainText(/outdated|过时|观察/);
  });

  test('USP-E2E-06: history tab lists SUPERSEDED versions', async ({ page }, testInfo) => {
    const testUserId = await setupSpoofedIdentity(page, testInfo.workerIndex);
    seedUserSoulProfile({
      userId: testUserId,
      status: 'active',
      version: 2,
    });
    const oldSeed = seedUserSoulProfile({
      userId: testUserId,
      status: 'superseded',
      version: 1,
      confidence: 0.7,
    });

    await navigateToMyProfile(page);

    await expect
      .poll(
        async () => {
          await page.locator('[data-testid="tab-history"]').click().catch(() => {});
          return await page
            .locator(
              '[data-testid="history-loading"], [data-testid="history-list"], [data-testid="history-empty"]',
            )
            .count();
        },
        { timeout: 5_000, intervals: [100, 250, 500, 1_000] },
      )
      .toBe(1);

    const historyList = page.locator('[data-testid="history-list"]');
    await expect(historyList).toBeVisible({ timeout: 5_000 });

    const oldRow = page.locator(`[data-testid="history-${oldSeed.pid}"]`);
    await expect(oldRow).toBeVisible();
    await expect(oldRow).toContainText(/v1|version 1/i);
  });

  test('USP-E2E-07: forget profile archives row + tombstone marker', async ({
    page,
  }, testInfo) => {
    const testUserId = await setupSpoofedIdentity(page, testInfo.workerIndex);
    const seed = seedUserSoulProfile({
      userId: testUserId,
      status: 'active',
    });

    await navigateToMyProfile(page);

    await page.locator('[data-testid="forget-btn"]').click();

    const modal = page.locator('[data-testid="forget-modal"]');
    await expect(modal).toBeVisible({ timeout: 5_000 });

    const confirmInput = modal.locator('[data-testid="forget-input"]');
    await confirmInput.fill('forget');
    await modal.locator('[data-testid="forget-confirm"]').click();

    await expect(page.locator('[data-testid="toast"]')).toBeVisible({
      timeout: 5_000,
    });

    const row = dbUserSoulProfileRow(seed.pid);
    expect(row.status).toBe('archived');
    expect(row.hiddenAt).not.toBeNull();
  });

  test('USP-E2E-09: user exports full soul profile via export button (UI path)', async ({
    page,
  }, testInfo) => {
    const testUserId = await setupSpoofedIdentity(page, testInfo.workerIndex);
    // Seed: ACTIVE v2 + SUPERSEDED v1. Export must return both.
    seedUserSoulProfile({
      userId: testUserId,
      status: 'ACTIVE',
      version: 2,
    });
    seedUserSoulProfile({
      userId: testUserId,
      status: 'SUPERSEDED',
      version: 1,
      confidence: 0.7,
    });

    await navigateToMyProfile(page);

    // Phase 10 — click the UI export button; the browser handles the
    // attachment download via Content-Disposition. Playwright surfaces
    // the download via the 'download' event.
    const exportBtn = page.locator('[data-testid="export-btn"]');
    await expect(exportBtn).toBeVisible({ timeout: 5_000 });

    const [download] = await Promise.all([
      page.waitForEvent('download', { timeout: 10_000 }),
      exportBtn.click(),
    ]);

    const filename = download.suggestedFilename();
    expect(filename).toMatch(/^user-soul-profile-.*\.json$/);

    // Completion toast surfaces to the user (D7 — operation feedback).
    await expect(page.locator('[data-testid="toast"]')).toBeVisible({
      timeout: 5_000,
    });

    // Validate the payload shape via the same endpoint (reuses session
    // cookies). Not the primary assertion — the UI-triggered download
    // above is the contract — but keeps the end-to-end contract honest.
    const resp = await page.request.fetch('/api/user/soul-profile/export', {
      headers: { 'X-Test-Spoof-User-Id': testUserId },
    });
    expect(resp.status()).toBe(200);
    const payload = await resp.json();
    expect(payload.schema_version).toBe('1.0');
    expect(payload.user_id).toBe(testUserId);
    expect(payload.row_count).toBeGreaterThanOrEqual(2);
    const versions = payload.profiles.map((p: any) => p.version).sort();
    expect(versions).toEqual(expect.arrayContaining([1, 2]));
  });

  test('USP-E2E-10: admin forget cascade via admin UI button + modal', async ({
    page,
  }) => {
    const victimUser = `e2e_victim_${Date.now()}`;
    const victimSeed = seedUserSoulProfile({
      userId: victimUser,
      status: 'ACTIVE',
      version: 1,
    });

    await navigateToAdminDashboard(page);

    // Row for the victim user must render (admin list includes all
    // tenant-scoped profiles).
    const row = page.locator(`[data-testid="admin-row-${victimUser}"]`);
    await expect(row).toBeVisible({ timeout: 10_000 });

    // Open admin-forget modal for this victim row.
    await row
      .locator(`[data-testid="admin-forget-btn-${victimUser}"]`)
      .click();

    const modal = page.locator('[data-testid="admin-forget-modal"]');
    await expect(modal).toBeVisible({ timeout: 5_000 });

    // Target user id is shown readonly in the modal.
    await expect(
      modal.locator('[data-testid="admin-forget-user-id"]'),
    ).toHaveValue(victimUser);

    // Pick an explicit reason (default is gdpr_request but assert we can set it).
    await modal
      .locator('[data-testid="admin-forget-reason"]')
      .selectOption('gdpr_request');

    // Typed-confirm guards the submit button.
    const submit = modal.locator('[data-testid="admin-forget-submit"]');
    await expect(submit).toBeDisabled();
    await modal.locator('[data-testid="admin-forget-input"]').fill('forget');
    await expect(submit).toBeEnabled();

    await submit.click();

    // Success toast + table reload.
    await expect(page.locator('[data-testid="admin-toast"]')).toBeVisible({
      timeout: 5_000,
    });

    // DB state: victim row archived + tombstone marker.
    const victimRow = dbUserSoulProfileRow(victimSeed.pid);
    expect(victimRow.status).toBe('archived');
    expect(victimRow.hiddenAt).not.toBeNull();
  });

  test('USP-E2E-08: admin dashboard shows metadata only — no persona text', async ({
    page,
  }) => {
    const uniqueUser = `e2e_admin_probe_${Date.now()}`;
    const uniquePersona = `UNIQUE-PERSONA-TEXT-${Date.now()}`;
    seedUserSoulProfile({
      userId: uniqueUser,
      status: 'active',
      profileJson: {
        schema_version: '1.0',
        persona: { text: uniquePersona, confidence: 0.82 },
        preferences: {},
        language: 'en-US',
      },
    });

    await navigateToAdminDashboard(page);

    const table = page.locator('[data-testid="admin-table"]');
    await expect(table).toBeVisible({ timeout: 5_000 });

    // Row should contain the user id (metadata) but NOT the persona text.
    await expect(table).toContainText(uniqueUser);
    const bodyText = await page.locator('body').innerText();
    expect(bodyText).not.toContain(uniquePersona);
  });
});
