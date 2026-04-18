/**
 * Mission Control — Memory Promotion review E2E (PR-68 Phase 4, mocked)
 *
 * Validates /aurabot/memory-promotions end-to-end with intercepted
 * /api/memory/promotions/** so the test is independent of whether the
 * extractor has produced live proposals.
 *
 * Mirrors ai-learning-drafts.spec.ts conventions (page.route fulfilment,
 * bilingual regex assertions). Corresponds to plan §11 outline.
 */

import { test, expect } from '../../fixtures';
import type { Page, Route } from '@playwright/test';

// ---------------------------------------------------------------------------
// Canned responses
// ---------------------------------------------------------------------------

function envelope<T>(data: T) {
  return { code: '0', message: 'OK', data };
}

function makePromotion(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    pid: 'MEMPROM000000000000001',
    tenant_id: 1,
    source_scope: 'user',
    source_memory_pid: null,
    source_memory_pids: [{ pid: 'SRC0000000000000000001' }, { pid: 'SRC0000000000000000002' }],
    target_scope: 'tenant',
    category: 'operations',
    proposed_title: 'Month-end close cycle',
    proposed_content: 'Month-end close cycle runs on the 28th of each month.',
    proposed_importance: 8,
    reason_code: 'cross_user_agreement',
    reason_detail: { user_ids: ['101', '102', '103'], agreement_count: 3, min_similarity: 0.87 },
    confidence_score: 0.82,
    similarity_score: 0.87,
    ai_rationale: 'All three members of the finance team independently noted this date.',
    status: 'DRAFT_PENDING_REVIEW',
    reviewer_id: null,
    review_comment: null,
    reject_reason: null,
    promoted_memory_pid: null,
    shadow_started_at: null,
    shadow_ends_at: null,
    activated_at: null,
    created_at: '2026-04-18T12:00:00Z',
    reviewed_at: null,
    ...overrides,
  };
}

function makeShadow(overrides: Partial<Record<string, unknown>> = {}) {
  const endsAt = new Date(Date.now() + 72 * 3_600_000).toISOString();
  return makePromotion({
    pid: 'MEMSHADOW0000000000001',
    status: 'PROMOTED_SHADOW',
    reviewer_id: 99,
    promoted_memory_pid: 'TMEM0000000000000000001',
    shadow_started_at: '2026-04-18T12:00:00Z',
    shadow_ends_at: endsAt,
    ...overrides,
  });
}

function makeActive(overrides: Partial<Record<string, unknown>> = {}) {
  return makePromotion({
    pid: 'MEMACTIVE00000000000001',
    status: 'ACTIVE',
    reviewer_id: 99,
    activated_at: '2026-04-18T12:00:00Z',
    ...overrides,
  });
}

async function interceptPromotionApi(page: Page, opts: {
  pending?: unknown[];
  shadow?: unknown[];
  active?: unknown[];
  rejected?: unknown[];
  retracted?: unknown[];
  reviewResponder?: (pid: string, body: Record<string, unknown>) => unknown;
  retractResponder?: (pid: string, body: Record<string, unknown>) => unknown;
  provenanceResponder?: (pid: string) => unknown;
} = {}) {
  const byStatus: Record<string, unknown[]> = {
    DRAFT_PENDING_REVIEW: opts.pending ?? [makePromotion()],
    PROMOTED_SHADOW: opts.shadow ?? [makeShadow()],
    ACTIVE: opts.active ?? [makeActive()],
    REVIEWED_REJECTED: opts.rejected ?? [],
    RETRACTED: opts.retracted ?? [],
  };

  await page.route(/\/api\/memory\/promotions(\?.*)?$/, async (route: Route) => {
    const url = new URL(route.request().url());
    const status = url.searchParams.get('status') ?? 'DRAFT_PENDING_REVIEW';
    const data = byStatus[status] ?? [];
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(envelope(data)),
    });
  });

  await page.route(/\/api\/memory\/promotions\/[^/]+\/review$/, async (route: Route) => {
    const match = route.request().url().match(/promotions\/([^/]+)\/review/);
    const pid = match?.[1] ?? '';
    const body = JSON.parse(route.request().postData() || '{}');
    const payload = opts.reviewResponder
      ? opts.reviewResponder(pid, body)
      : envelope({
          pid,
          previous_status: 'DRAFT_PENDING_REVIEW',
          status: body.decision === 'approve' ? 'PROMOTED_SHADOW' : 'REVIEWED_REJECTED',
          promoted_memory_pid: body.decision === 'approve' ? 'TMEMNEW0000000000000001' : null,
        });
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(payload),
    });
  });

  await page.route(/\/api\/memory\/promotions\/[^/]+\/retract$/, async (route: Route) => {
    const match = route.request().url().match(/promotions\/([^/]+)\/retract/);
    const pid = match?.[1] ?? '';
    const body = JSON.parse(route.request().postData() || '{}');
    const payload = opts.retractResponder
      ? opts.retractResponder(pid, body)
      : envelope({
          pid,
          previous_status: 'PROMOTED_SHADOW',
          status: 'RETRACTED',
          promoted_memory_pid: 'TMEM0000000000000000001',
        });
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(payload),
    });
  });

  await page.route(/\/api\/memory\/promotions\/[^/]+\/provenance$/, async (route: Route) => {
    const match = route.request().url().match(/promotions\/([^/]+)\/provenance/);
    const pid = match?.[1] ?? '';
    const payload = opts.provenanceResponder
      ? opts.provenanceResponder(pid)
      : envelope({
          promotion: makePromotion({ pid }),
          source_memories: [
            {
              pid: 'SRC0000000000000000001',
              scope: 'user',
              scope_key: '101',
              memory_title: 'close on 28',
              memory_content: 'we close books on the 28th',
              importance: 7,
              created_at: '2026-04-10T12:00:00Z',
              author_email: 'alice@co',
              author_user_name: 'Alice',
            },
            {
              pid: 'SRC0000000000000000002',
              scope: 'user',
              scope_key: '102',
              memory_title: 'month-end 28',
              memory_content: 'month-end = 28',
              importance: 7,
              created_at: '2026-04-12T12:00:00Z',
              author_email: 'bob@co',
              author_user_name: 'Bob',
            },
          ],
          promoted_memory: null,
          upstream_promotions: [],
        });
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(payload),
    });
  });

  await page.route(/\/api\/memory\/promotions\/batch-approve$/, async (route: Route) => {
    const body = JSON.parse(route.request().postData() || '{}');
    const pids = (body?.pids as string[]) ?? [];
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(envelope({ approved: pids, failed: [] })),
    });
  });
}

async function openPage(page: Page) {
  await page.goto('/aurabot/memory-promotions');
  await page.waitForLoadState('domcontentloaded');
  await expect(page.locator('[data-testid="memory-promotions-page"]')).toBeVisible({ timeout: 10000 });
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

test.describe('Mission Control — Memory Promotion review (PR-68)', () => {
  test('MP-01: pending tab renders with confidence bar and category badge', async ({ page }) => {
    await interceptPromotionApi(page, {
      pending: [
        makePromotion({
          pid: 'MEMPROM000000000000001',
          confidence_score: 0.82,
          category: 'operations',
        }),
      ],
    });
    await openPage(page);

    const row = page.locator('[data-testid="promotion-MEMPROM000000000000001"]');
    await expect(row).toBeVisible();
    await expect(row.locator('[data-testid="confidence-bar"]')).toBeVisible();
    await expect(row.locator('[data-testid="confidence-value"]')).toHaveText('0.82');
    await expect(row.locator('[data-testid="category-badge"]')).toHaveText('operations');
    await expect(row.locator('[data-testid="proposed-title"]')).toContainText(/Month-end close|月末/);
    await expect(row.locator('[data-testid="ai-rationale"]')).toContainText(/finance team|财务/);
  });

  test('MP-02: keyboard "a" triggers POST /review with decision=approve', async ({ page }) => {
    const captured: Array<{ pid: string; body: Record<string, unknown> }> = [];
    await interceptPromotionApi(page, {
      reviewResponder: (pid, body) => {
        captured.push({ pid, body });
        return envelope({
          pid,
          previous_status: 'DRAFT_PENDING_REVIEW',
          status: 'PROMOTED_SHADOW',
          promoted_memory_pid: 'TMEMNEW0000000000000001',
        });
      },
    });
    await openPage(page);
    await expect(page.locator('[data-testid="promotion-MEMPROM000000000000001"]')).toBeVisible();

    // Click somewhere on the page body to unfocus any input, then dispatch 'a'
    await page.locator('body').click({ position: { x: 10, y: 10 } });
    await page.keyboard.press('a');

    await expect(page.locator('[data-testid="toast"]')).toContainText(/批准|Approved/);
    expect(captured).toHaveLength(1);
    expect(captured[0].body).toMatchObject({ decision: 'approve' });
    expect(captured[0].pid).toBe('MEMPROM000000000000001');
  });

  test('MP-03: keyboard "r" opens reject modal; selecting reason + submit triggers POST /review', async ({ page }) => {
    const captured: Array<{ pid: string; body: Record<string, unknown> }> = [];
    await interceptPromotionApi(page, {
      reviewResponder: (pid, body) => {
        captured.push({ pid, body });
        return envelope({
          pid,
          previous_status: 'DRAFT_PENDING_REVIEW',
          status: 'REVIEWED_REJECTED',
          promoted_memory_pid: null,
        });
      },
    });
    await openPage(page);
    await expect(page.locator('[data-testid="promotion-MEMPROM000000000000001"]')).toBeVisible();

    await page.locator('body').click({ position: { x: 10, y: 10 } });
    await page.keyboard.press('r');

    const modal = page.locator('[data-testid="reject-modal"]');
    await expect(modal).toBeVisible();
    await modal.locator('[data-testid="reject-reason-select"]').selectOption('contains_pii');
    await modal.locator('[data-testid="reject-comment"]').fill('PII risk');
    await modal.locator('[data-testid="reject-submit"]').click();

    await expect(page.locator('[data-testid="toast"]')).toContainText(/驳回|Rejected/);
    expect(captured).toHaveLength(1);
    expect(captured[0].body).toMatchObject({
      decision: 'reject',
      reject_reason: 'contains_pii',
      comment: 'PII risk',
    });
  });

  test('MP-04: shadow tab shows countdown + retract button; retract submits /retract', async ({ page }) => {
    const captured: Array<{ pid: string; body: Record<string, unknown> }> = [];
    await interceptPromotionApi(page, {
      retractResponder: (pid, body) => {
        captured.push({ pid, body });
        return envelope({
          pid,
          previous_status: 'PROMOTED_SHADOW',
          status: 'RETRACTED',
          promoted_memory_pid: 'TMEM0000000000000000001',
        });
      },
    });
    await openPage(page);
    // Wait for pending data so tab bar is stable before switching.
    await expect(page.locator('[data-testid="promotion-MEMPROM000000000000001"]')).toBeVisible();

    await page.locator('[data-testid="tab-shadow"]').click();
    await expect(page.locator('[data-testid="shadow-tab"]')).toBeVisible();
    const row = page.locator('[data-testid="shadow-MEMSHADOW0000000000001"]');
    await expect(row).toBeVisible();
    await expect(row.locator('[data-testid="shadow-countdown"]')).toContainText(/remaining|还剩/);

    await row.locator('[data-testid="retract-btn"]').click();
    const modal = page.locator('[data-testid="retract-modal"]');
    await expect(modal).toBeVisible();
    await modal.locator('[data-testid="retract-reason"]').fill('turned out to be wrong');
    await modal.locator('[data-testid="retract-submit"]').click();

    await expect(page.locator('[data-testid="toast"]')).toContainText(/撤回|Retracted/);
    expect(captured).toHaveLength(1);
    expect(captured[0].pid).toBe('MEMSHADOW0000000000001');
    expect(captured[0].body).toMatchObject({ reason: 'turned out to be wrong' });
  });

  test('MP-05: audit tab renders completed rows from ACTIVE/REJECTED/RETRACTED', async ({ page }) => {
    await interceptPromotionApi(page, {
      active: [makeActive({ pid: 'MEMACTIVE00000000000001' })],
      rejected: [
        makePromotion({
          pid: 'MEMREJ0000000000000001',
          status: 'REVIEWED_REJECTED',
          reject_reason: 'outdated',
          reviewer_id: 42,
        }),
      ],
      retracted: [
        makePromotion({
          pid: 'MEMRETR000000000000001',
          status: 'RETRACTED',
          reviewer_id: 42,
        }),
      ],
    });
    await openPage(page);
    await expect(page.locator('[data-testid="promotion-MEMPROM000000000000001"]')).toBeVisible();

    await page.locator('[data-testid="tab-audit"]').click();
    await expect(page.locator('[data-testid="audit-tab"]')).toBeVisible();
    const table = page.locator('[data-testid="audit-table"]');
    await expect(table).toBeVisible();
    await expect(page.locator('[data-testid="audit-MEMACTIVE00000000000001"]')).toBeVisible();
    await expect(page.locator('[data-testid="audit-MEMREJ0000000000000001"]')).toContainText('outdated');
    await expect(page.locator('[data-testid="audit-MEMRETR000000000000001"]')).toBeVisible();
  });

  test('MP-06: PII warning banner is visible on pending tab', async ({ page }) => {
    await interceptPromotionApi(page);
    await openPage(page);

    const banner = page.locator('[data-testid="pii-warning"]').first();
    await expect(banner).toBeVisible();
    await expect(banner).toContainText(/PII|个人信息/);
  });

  test('MP-07: provenance link opens modal with timeline', async ({ page }) => {
    await interceptPromotionApi(page);
    await openPage(page);

    await page
      .locator('[data-testid="promotion-MEMPROM000000000000001"] [data-testid="provenance-link"]')
      .click();
    const modal = page.locator('[data-testid="provenance-modal"]');
    await expect(modal).toBeVisible();
    const timeline = page.locator('[data-testid="provenance-timeline"]');
    await expect(timeline).toBeVisible();
    await expect(timeline.locator('[data-testid="timeline-source"]').first()).toBeVisible();
    await expect(timeline.locator('[data-testid="timeline-promotion"]')).toBeVisible();
    await expect(modal).toContainText(/alice|Alice|bob|Bob/);
  });
});
