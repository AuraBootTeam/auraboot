/**
 * Mission Control — SkillDraft Review E2E (PR-27 + PR-31)
 *
 * Validates that the /aurabot/learning-drafts page correctly lists
 * drafts from /api/learning/drafts, lets the operator expand rows,
 * and round-trips approve / reject / auto-rename through the PR-26
 * REST API.
 *
 * We intercept the API endpoints with Playwright so the test doesn't
 * need the Learning Loop end-to-end data pipeline to be active — each
 * test fulfills a canned JSON response then drives the UI.
 */

import { test, expect } from '../../fixtures';
import type { Page, Route } from '@playwright/test';

// ---------------------------------------------------------------------------
// Canned API responses
// ---------------------------------------------------------------------------

function envelope<T>(data: T) {
  return { code: '0', message: 'OK', data };
}

function makeDraft(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    pid: 'DRAFTPID1234567890ABCD',
    draft_skill_code: 'auto.crm_lead_update.abc123',
    source_pattern_hash: 'hash_source_1',
    status: 'DRAFT_PENDING_REVIEW',
    reviewer_id: null,
    review_comment: null,
    created_at: '2026-04-18T12:00:00Z',
    reviewed_at: null,
    shadow_started_at: null,
    promoted_at: null,
    shadow_metrics_json: null,
    ...overrides,
  };
}

function makeDetail(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    ...makeDraft(),
    contract_yaml:
      '# Auto-generated Skill draft\n' +
      'skill_code: auto.crm_lead_update.abc123\n' +
      'substrate: dsl\n' +
      'target_model: crm_lead\n' +
      'action_type: update\n',
    derived_from_runs_json: '[{"run_id":"01RUN1"},{"run_id":"01RUN2"}]',
    source_pattern: {
      pattern_hash: 'hash_source_1',
      invocation_count: 12,
      success_rate: 1.0,
      status: 'DRAFT_GENERATED',
    },
    recent_shadow_runs: [
      {
        pid: '01SR1',
        shadow_status: 'success',
        output_match: true,
        fidelity_match: true,
        shadow_duration_ms: 120,
        original_duration_ms: 180,
      },
    ],
    ...overrides,
  };
}

async function interceptLearningApi(page: Page, opts: {
  list?: unknown[];
  detail?: unknown;
  reviewResponder?: (body: Record<string, unknown>) => unknown;
  renameResponder?: () => unknown;
}) {
  // Default handlers
  const listData = opts.list ?? [makeDraft()];
  const detailData = opts.detail ?? makeDetail();

  await page.route(/\/api\/learning\/drafts\?.*/, async (route: Route) => {
    await route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify(envelope(listData)),
    });
  });

  await page.route(/\/api\/learning\/drafts\/[^/]+$/, async (route: Route) => {
    if (route.request().method() !== 'GET') return route.fallback();
    await route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify(envelope(detailData)),
    });
  });

  await page.route(/\/api\/learning\/drafts\/[^/]+\/review$/, async (route: Route) => {
    const body = JSON.parse(route.request().postData() || '{}');
    const payload = opts.reviewResponder
      ? opts.reviewResponder(body)
      : envelope({
          pid: (detailData as { pid: string }).pid,
          previous_status: 'DRAFT_PENDING_REVIEW',
          status: body.decision === 'approve' ? 'REVIEWED_OK' : 'REVIEWED_REJECTED',
        });
    await route.fulfill({
      status: 200, contentType: 'application/json', body: JSON.stringify(payload),
    });
  });

  await page.route(/\/api\/learning\/drafts\/[^/]+\/auto-rename$/, async (route: Route) => {
    const payload = opts.renameResponder
      ? opts.renameResponder()
      : envelope({ pid: (detailData as { pid: string }).pid, renamed: false });
    await route.fulfill({
      status: 200, contentType: 'application/json', body: JSON.stringify(payload),
    });
  });
}

async function openPage(page: Page) {
  await page.goto('/aurabot/learning-drafts');
  await page.waitForLoadState('domcontentloaded');
  await expect(page.locator('[data-testid="learning-drafts-page"]')).toBeVisible({ timeout: 10000 });
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

test.describe('Mission Control — SkillDraft review (PR-31)', () => {
  test('LD-01: list renders with status badge and draft_skill_code', async ({ page }) => {
    await interceptLearningApi(page, {
      list: [
        makeDraft({
          pid: 'DRAFTPID1234567890ABCD',
          draft_skill_code: 'auto.crm_lead_update.abc123',
          status: 'DRAFT_PENDING_REVIEW',
        }),
      ],
    });
    await openPage(page);

    await expect(page.locator('[data-testid="drafts-list"]')).toBeVisible();
    const row = page.locator('[data-testid="draft-DRAFTPID1234567890ABCD"]');
    await expect(row).toBeVisible();
    await expect(row.locator('[data-testid="draft-code"]'))
      .toHaveText('auto.crm_lead_update.abc123');
    await expect(row.locator('text=待审核')).toBeVisible();
  });

  test('LD-02: expanding a row loads detail and shows contract_yaml', async ({ page }) => {
    await interceptLearningApi(page, {});
    await openPage(page);

    const row = page.locator('[data-testid="draft-DRAFTPID1234567890ABCD"]');
    await row.locator('button').first().click();

    const detail = page.locator('[data-testid="draft-detail"]');
    await expect(detail).toBeVisible();
    await expect(detail.locator('[data-testid="contract-yaml"]')).toBeVisible();
    await expect(detail.locator('[data-testid="contract-yaml"]')).toContainText(
      'skill_code: auto.crm_lead_update.abc123',
    );
    await expect(detail).toContainText('invocation_count' in {} ? '' : '12'); // source pattern visible
  });

  test('LD-03: approve button triggers POST /review with decision=approve', async ({ page }) => {
    const capturedBodies: Record<string, unknown>[] = [];
    await interceptLearningApi(page, {
      reviewResponder: (body) => {
        capturedBodies.push(body);
        return envelope({
          pid: 'DRAFTPID1234567890ABCD',
          previous_status: 'DRAFT_PENDING_REVIEW',
          status: 'REVIEWED_OK',
        });
      },
    });
    await openPage(page);

    await page.locator('[data-testid="draft-DRAFTPID1234567890ABCD"] button').first().click();
    await page.locator('[data-testid="review-comment"]').fill('looks good');
    await page.locator('[data-testid="approve-btn"]').click();

    await expect(page.locator('[data-testid="toast"]')).toContainText('批准成功');
    expect(capturedBodies).toHaveLength(1);
    expect(capturedBodies[0]).toEqual({ decision: 'approve', comment: 'looks good' });
  });

  test('LD-04: reject button triggers POST /review with decision=reject', async ({ page }) => {
    const capturedBodies: Record<string, unknown>[] = [];
    await interceptLearningApi(page, {
      reviewResponder: (body) => {
        capturedBodies.push(body);
        return envelope({
          pid: 'DRAFTPID1234567890ABCD',
          previous_status: 'DRAFT_PENDING_REVIEW',
          status: 'REVIEWED_REJECTED',
        });
      },
    });
    await openPage(page);

    await page.locator('[data-testid="draft-DRAFTPID1234567890ABCD"] button').first().click();
    await page.locator('[data-testid="reject-btn"]').click();

    await expect(page.locator('[data-testid="toast"]')).toContainText('驳回成功');
    expect(capturedBodies[0]).toHaveProperty('decision', 'reject');
  });

  test('LD-05: auto-rename button shown only for auto.* drafts', async ({ page }) => {
    await interceptLearningApi(page, {
      list: [
        makeDraft({ draft_skill_code: 'auto.crm_lead_update.x' }),
        makeDraft({ pid: 'HUMANNAMEDPID12345678AB', draft_skill_code: 'crm.lead.batch_update' }),
      ],
      detail: makeDetail({ draft_skill_code: 'auto.crm_lead_update.x' }),
    });
    await openPage(page);

    // Open the auto.* draft — rename button visible
    await page.locator('[data-testid="draft-DRAFTPID1234567890ABCD"] button').first().click();
    await expect(page.locator('[data-testid="rename-btn"]')).toBeVisible();

    // Re-fetch detail for the other draft so expansion works
    await page.locator('[data-testid="draft-DRAFTPID1234567890ABCD"] button').first().click(); // collapse
    await page.route(/\/api\/learning\/drafts\/HUMAN.+$/, async (route: Route) => {
      if (route.request().method() !== 'GET') return route.fallback();
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify(envelope(makeDetail({
          pid: 'HUMANNAMEDPID12345678AB',
          draft_skill_code: 'crm.lead.batch_update',
        }))),
      });
    });
    await page.locator('[data-testid="draft-HUMANNAMEDPID12345678AB"] button').first().click();
    await expect(page.locator('[data-testid="rename-btn"]')).toHaveCount(0);
  });

  test('LD-06: status filter dropdown changes the list query', async ({ page }) => {
    const requestedUrls: string[] = [];
    await page.route(/\/api\/learning\/drafts\?.*/, async (route: Route) => {
      requestedUrls.push(route.request().url());
      await route.fulfill({
        status: 200, contentType: 'application/json',
        body: JSON.stringify(envelope([])),
      });
    });
    await interceptLearningApi(page, { list: [] });  // plus catch-all
    await openPage(page);

    await page.locator('[data-testid="status-filter"]').selectOption('REVIEWED_OK');
    // wait for the re-fetch
    await page.waitForFunction(
      () => document.querySelectorAll('[data-testid^="draft-"]').length === 0,
      { timeout: 5000 },
    ).catch(() => null);

    const reviewedOkRequests = requestedUrls.filter((u) => u.includes('status=REVIEWED_OK'));
    expect(reviewedOkRequests.length).toBeGreaterThanOrEqual(1);
  });
});
