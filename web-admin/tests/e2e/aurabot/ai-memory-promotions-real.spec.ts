/**
 * Mission Control — Memory Promotion review REAL-backend E2E (PR-69).
 *
 * These tests drive the live Spring Boot backend on :6443. They exercise
 * the full Phase 1–4 stack:
 *
 *   psql seed → sidebar click → React fetch → Spring Controller →
 *   MemoryPromotionApplier → PostgreSQL → assert DB state flipped
 *
 * MP-E2E-01 — approve flow: seed DRAFT → UI Approve → DB PROMOTED_SHADOW
 *                           AND promoted_memory_pid is set
 * MP-E2E-02 — reject with reason: seed DRAFT → UI Reject + reason dropdown
 *                                 → DB REVIEWED_REJECTED + reject_reason='outdated'
 * MP-E2E-03 — retract during shadow: seed PROMOTED_SHADOW (with real
 *                                    ab_agent_memory row) → UI Retract
 *                                    → DB RETRACTED + memory soft-deleted
 * MP-E2E-04 — batch approve: seed 3 DRAFT rows (confidence ≥ 0.80) →
 *                            checkbox each → batch approve →
 *                            all 3 → PROMOTED_SHADOW
 * MP-E2E-05 — provenance modal: seed a chain → click provenance link →
 *                               verify modal shows expected data.
 *
 * Phase 4 UI deliverables the spec depends on:
 *   - data-testid="memory-promotions-page" root
 *   - data-testid="promotion-{pid}" per row
 *   - data-testid="promotion-approve-btn" / "promotion-reject-btn"
 *   - data-testid="promotion-reject-reason" select; options match enum
 *   - data-testid="promotion-retract-btn" (on Shadow tab)
 *   - data-testid="promotion-retract-reason" input
 *   - data-testid="promotion-batch-checkbox-{pid}"
 *   - data-testid="promotion-batch-approve-btn"
 *   - data-testid="promotion-provenance-link" / "promotion-provenance-modal"
 *   - data-testid="promotion-tab-shadow" / "promotion-tab-pending"
 *   - data-testid="toast"
 */

import { test, expect } from '../../fixtures';
import type { Page } from '@playwright/test';
import {
  seedMemoryPromotion,
  seedMemoryPromotionWithPromotedMemory,
  dbPromotionRow,
  dbMemoryIsDeleted,
  cleanupPromotions,
  seedMemoryPromotionsMenu,
  cleanupMemoryPromotionsMenu,
  MEMORY_PROMOTION_PID_PREFIX,
  type SeededPromotionMenu,
} from './_real-backend-helpers';

let seededMenu: SeededPromotionMenu;

test.beforeAll(async () => {
  seededMenu = seedMemoryPromotionsMenu();
});

test.afterAll(async () => {
  if (seededMenu) cleanupMemoryPromotionsMenu(seededMenu);
});

test.afterEach(async () => {
  // Broad cleanup by prefix — each test seeds under MEMORY_PROMOTION_PID_PREFIX.
  cleanupPromotions(MEMORY_PROMOTION_PID_PREFIX);
});

/**
 * Navigate to /aurabot/memory-promotions via the sidebar.
 */
async function navigateViaSidebar(page: Page): Promise<void> {
  await page.goto('/');
  await page.waitForLoadState('domcontentloaded');

  const nav = page.locator('nav').first();
  const aiCenter = nav.getByRole('button', { name: /AI 中心|AI Center/ });
  await aiCenter.waitFor({ state: 'visible', timeout: 10_000 });
  await aiCenter.evaluate((el: HTMLElement) => el.click());

  const leaf = nav.getByRole('link', { name: /记忆提案|Memory Promotions?/ });
  await leaf.waitFor({ state: 'visible', timeout: 5_000 });
  await leaf.evaluate((el: HTMLElement) => el.click());

  await expect(
    page.locator('[data-testid="memory-promotions-page"]'),
  ).toBeVisible({ timeout: 10_000 });
}

async function expandRow(page: Page, pid: string): Promise<void> {
  const row = page.locator(`[data-testid="promotion-${pid}"]`);
  await expect(row).toBeVisible({ timeout: 10_000 });
  await row.locator('button').first().click();
}

test.describe('Mission Control — Memory Promotion review (real backend, PR-69)', () => {
  test('MP-E2E-01: approve flow — DRAFT_PENDING_REVIEW → PROMOTED_SHADOW', async ({
    page,
  }) => {
    const pid = seedMemoryPromotion('DRAFT_PENDING_REVIEW', 0.85, 'cross_user_agreement');

    await navigateViaSidebar(page);
    await expandRow(page, pid);

    await page.locator('[data-testid="promotion-approve-btn"]').click();

    await expect(page.locator('[data-testid="toast"]')).toBeVisible({
      timeout: 5_000,
    });
    await expect(page.locator('[data-testid="toast"]')).toContainText(
      /批准|Approv/,
    );

    const row = dbPromotionRow(pid);
    expect(row.status).toBe('PROMOTED_SHADOW');
    expect(row.promotedMemoryPid).not.toBeNull();
    expect(row.promotedMemoryPid?.length).toBeGreaterThan(0);
  });

  test('MP-E2E-02: reject with reason — DRAFT_PENDING_REVIEW → REVIEWED_REJECTED(outdated)', async ({
    page,
  }) => {
    const pid = seedMemoryPromotion('DRAFT_PENDING_REVIEW', 0.82, 'cross_user_agreement');

    await navigateViaSidebar(page);
    await expandRow(page, pid);

    await page.locator('[data-testid="promotion-reject-btn"]').click();

    const reasonSelect = page.locator('[data-testid="promotion-reject-reason"]');
    await expect(reasonSelect).toBeVisible({ timeout: 5_000 });
    await reasonSelect.selectOption('outdated');

    // Submit confirm button inside the reject modal.
    await page.locator('[data-testid="promotion-reject-submit"]').click();

    await expect(page.locator('[data-testid="toast"]')).toBeVisible({
      timeout: 5_000,
    });
    await expect(page.locator('[data-testid="toast"]')).toContainText(
      /驳回|Reject/,
    );

    const row = dbPromotionRow(pid);
    expect(row.status).toBe('REVIEWED_REJECTED');
    expect(row.rejectReason).toBe('outdated');
  });

  test('MP-E2E-03: retract during shadow — PROMOTED_SHADOW → RETRACTED + memory soft-deleted', async ({
    page,
  }) => {
    const seed = seedMemoryPromotionWithPromotedMemory();

    await navigateViaSidebar(page);

    // Switch to the Shadow tab to see this promotion.
    await page.locator('[data-testid="promotion-tab-shadow"]').click();
    await expandRow(page, seed.pid);

    await page.locator('[data-testid="promotion-retract-btn"]').click();

    const reasonInput = page.locator('[data-testid="promotion-retract-reason"]');
    await expect(reasonInput).toBeVisible({ timeout: 5_000 });
    await reasonInput.fill('e2e retract — proposal was wrong');

    await page.locator('[data-testid="promotion-retract-submit"]').click();

    await expect(page.locator('[data-testid="toast"]')).toBeVisible({
      timeout: 5_000,
    });
    await expect(page.locator('[data-testid="toast"]')).toContainText(
      /撤回|Retract/,
    );

    const row = dbPromotionRow(seed.pid);
    expect(row.status).toBe('RETRACTED');
    expect(dbMemoryIsDeleted(seed.promotedMemoryPid!)).toBe(true);
  });

  test('MP-E2E-04: batch approve — 3 DRAFT rows → all PROMOTED_SHADOW', async ({
    page,
  }) => {
    const pid1 = seedMemoryPromotion('DRAFT_PENDING_REVIEW', 0.86, 'cross_user_agreement');
    const pid2 = seedMemoryPromotion('DRAFT_PENDING_REVIEW', 0.83, 'cross_user_agreement');
    const pid3 = seedMemoryPromotion('DRAFT_PENDING_REVIEW', 0.90, 'implicit_co_sign');

    await navigateViaSidebar(page);

    // Tick the three checkboxes.
    for (const pid of [pid1, pid2, pid3]) {
      const cb = page.locator(`[data-testid="promotion-batch-checkbox-${pid}"]`);
      await expect(cb).toBeVisible({ timeout: 10_000 });
      await cb.check();
    }

    await page.locator('[data-testid="promotion-batch-approve-btn"]').click();

    await expect(page.locator('[data-testid="toast"]')).toBeVisible({
      timeout: 10_000,
    });

    for (const pid of [pid1, pid2, pid3]) {
      const row = dbPromotionRow(pid);
      expect(row.status).toBe('PROMOTED_SHADOW');
      expect(row.promotedMemoryPid).not.toBeNull();
    }
  });

  test('MP-E2E-05: provenance modal — chain renders expected data', async ({
    page,
  }) => {
    const seed = seedMemoryPromotionWithPromotedMemory();

    await navigateViaSidebar(page);
    await page.locator('[data-testid="promotion-tab-shadow"]').click();
    await expandRow(page, seed.pid);

    await page.locator('[data-testid="promotion-provenance-link"]').click();

    const modal = page.locator('[data-testid="promotion-provenance-modal"]');
    await expect(modal).toBeVisible({ timeout: 5_000 });

    // Modal must reveal the promotion pid, the promoted memory, and a
    // timeline with a promotion-created marker.
    await expect(modal).toContainText(seed.pid);
    await expect(modal).toContainText(seed.promotedMemoryPid!);
    await expect(modal).toContainText(/PROMOTED_SHADOW|观察/);
  });
});
