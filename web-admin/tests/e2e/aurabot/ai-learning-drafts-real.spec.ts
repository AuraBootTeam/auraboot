/**
 * Mission Control — SkillDraft review REAL-backend E2E (PR-64).
 *
 * Unlike `ai-learning-drafts.spec.ts`, which fulfills every API call
 * with Playwright `page.route(...)` stubs, these tests drive the live
 * Spring Boot backend on :6443. They exercise the full stack:
 *
 *   psql seed → sidebar click → React fetch → Spring Controller →
 *   Service → Mapper → PostgreSQL → assert DB state flipped
 *
 * Why this matters: the stubbed specs cannot catch a regression where
 * (a) `/api/learning/drafts/{pid}/review` forgets to flip the status,
 * (b) tenant scoping breaks, or (c) the status enum diverges between
 * Java and SQL. These ones can.
 *
 * LD-E2E-01 — approve flow: seed DRAFT_PENDING_REVIEW → Approve → DB shows REVIEWED_OK
 * LD-E2E-02 — reject flow:  seed DRAFT_PENDING_REVIEW → Reject  → DB shows REVIEWED_REJECTED
 */

import { test, expect } from '../../fixtures';
import type { Page } from '@playwright/test';
import {
  seedDraft,
  dbDraftRow,
  cleanupDrafts,
  seedMissionControlMenus,
  cleanupMissionControlMenus,
  type SeededMenus,
  type SeededDraft,
} from './_real-backend-helpers';

let seededMenus: SeededMenus;
let seededPids: string[] = [];

test.beforeAll(async () => {
  seededMenus = seedMissionControlMenus();
});

test.afterAll(async () => {
  if (seededMenus) cleanupMissionControlMenus(seededMenus);
});

test.afterEach(async () => {
  if (seededPids.length > 0) {
    cleanupDrafts(seededPids);
    seededPids = [];
  }
});

/**
 * Navigate to the Skill Drafts page via the sidebar (not page.goto).
 *
 * The page sits under the "AI 中心" submenu. We expand that group, then
 * click the "技能草稿" leaf. The menu rows are seeded by beforeAll.
 */
async function navigateViaSidebar(page: Page): Promise<void> {
  await page.goto('/');
  await page.waitForLoadState('domcontentloaded');

  const nav = page.locator('nav').first();
  // Expand the "AI 中心" collapsible group.
  const aiCenter = nav.getByRole('button', { name: /AI 中心|AI Center/ });
  await aiCenter.waitFor({ state: 'visible', timeout: 10_000 });
  await aiCenter.evaluate((el: HTMLElement) => el.click());

  // Click the leaf — matches Chinese label seeded into ab_menu.
  const leaf = nav.getByRole('link', { name: /技能草稿|Skill Drafts?/ });
  await leaf.waitFor({ state: 'visible', timeout: 5_000 });
  await leaf.evaluate((el: HTMLElement) => el.click());

  await expect(
    page.locator('[data-testid="learning-drafts-page"]'),
  ).toBeVisible({ timeout: 10_000 });
}

async function expandAndReview(
  page: Page,
  draft: SeededDraft,
  decision: 'approve' | 'reject',
  comment: string,
): Promise<void> {
  const row = page.locator(`[data-testid="draft-${draft.pid}"]`);
  await expect(row).toBeVisible({ timeout: 10_000 });

  // Expand — the outer <button> toggles the row.
  await row.locator('button').first().click();
  await expect(page.locator('[data-testid="draft-detail"]')).toBeVisible();

  await page.locator('[data-testid="review-comment"]').fill(comment);

  const btnTestId = decision === 'approve' ? 'approve-btn' : 'reject-btn';
  await page.locator(`[data-testid="${btnTestId}"]`).click();

  // Toast appears when the backend POST /review returns success.
  await expect(page.locator('[data-testid="toast"]')).toBeVisible({
    timeout: 5_000,
  });
}

test.describe('Mission Control — SkillDraft review (real backend, PR-64)', () => {
  test('LD-E2E-01: approve flow — DB flips DRAFT_PENDING_REVIEW → REVIEWED_OK', async ({
    page,
  }) => {
    // D3 — seed: DB row in pending state, reachable via GET /api/learning/drafts/{pid}.
    const draft = seedDraft('DRAFT_PENDING_REVIEW', {
      draftSkillCode: `auto.ld_e2e_01.${Date.now()}`,
    });
    seededPids.push(draft.pid);

    // D1 — navigation via sidebar.
    await navigateViaSidebar(page);

    // D4 — UI action: expand, comment, approve.
    const comment = 'e2e approve smoke';
    await expandAndReview(page, draft, 'approve', comment);

    // D5 — UI assertion: toast text matches either locale.
    await expect(page.locator('[data-testid="toast"]')).toContainText(
      /批准成功|Approval succeeded/,
    );
    // Toast body embeds the new status — double-check.
    await expect(page.locator('[data-testid="toast"]')).toContainText(
      'REVIEWED_OK',
    );

    // D6 — DB assertion: cross-check persistent state.
    const row = dbDraftRow(draft.pid);
    expect(row.status).toBe('REVIEWED_OK');
    expect(row.reviewComment).toBe(comment);
  });

  test('LD-E2E-02: reject flow — DB flips DRAFT_PENDING_REVIEW → REVIEWED_REJECTED', async ({
    page,
  }) => {
    const draft = seedDraft('DRAFT_PENDING_REVIEW', {
      draftSkillCode: `auto.ld_e2e_02.${Date.now()}`,
    });
    seededPids.push(draft.pid);

    await navigateViaSidebar(page);

    const comment = 'e2e reject smoke';
    await expandAndReview(page, draft, 'reject', comment);

    await expect(page.locator('[data-testid="toast"]')).toContainText(
      /驳回成功|Rejection succeeded/,
    );
    await expect(page.locator('[data-testid="toast"]')).toContainText(
      'REVIEWED_REJECTED',
    );

    const row = dbDraftRow(draft.pid);
    expect(row.status).toBe('REVIEWED_REJECTED');
    expect(row.reviewComment).toBe(comment);
  });
});
