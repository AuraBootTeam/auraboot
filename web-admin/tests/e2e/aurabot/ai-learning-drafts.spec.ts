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
  shadowRuns?: unknown[];
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

  await page.route(/\/api\/learning\/drafts\/[^/]+\/shadow-runs(\?.*)?$/, async (route: Route) => {
    await route.fulfill({
      status: 200, contentType: 'application/json',
      body: JSON.stringify(envelope(opts.shadowRuns ?? [])),
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
    // Single unified handler: captures every /drafts list request and
    // returns [] so the UI stays in the empty state between filter changes.
    await page.route(/\/api\/learning\/drafts(\?|$)/, async (route: Route) => {
      requestedUrls.push(route.request().url());
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(envelope([])),
      });
    });
    await page.goto('/aurabot/learning-drafts');
    await expect(page.locator('[data-testid="learning-drafts-page"]')).toBeVisible({ timeout: 10000 });
    // Wait until the initial fetch captures — ensures React has hydrated
    // and the route handler is live before we interact with the select.
    await expect.poll(() => requestedUrls.length, { timeout: 10000 }).toBeGreaterThanOrEqual(1);

    await page.locator('[data-testid="status-filter"]').selectOption('REVIEWED_OK');
    await expect.poll(
      () => requestedUrls.filter((u) => u.includes('status=REVIEWED_OK')).length,
      { timeout: 10000 },
    ).toBeGreaterThanOrEqual(1);
  });

  test('LD-07: shadow-runs empty state renders when no shadow runs exist (PR-43)', async ({ page }) => {
    await interceptLearningApi(page, { shadowRuns: [] });
    await openPage(page);

    await page.locator('[data-testid="draft-DRAFTPID1234567890ABCD"] button').first().click();

    const section = page.locator('[data-testid="shadow-runs-section"]');
    await expect(section).toBeVisible();
    await expect(section).toContainText('暂无影子运行');
  });

  test('LD-08: shadow-runs table renders duration/cost deltas (PR-43)', async ({ page }) => {
    await interceptLearningApi(page, {
      shadowRuns: [
        {
          pid: 'SR1234567890ABCDEF1234',
          original_run_id: 'ORIG111',
          shadow_status: 'success',
          shadow_duration_ms: 120,
          original_duration_ms: 180,
          shadow_cost_usd: 0.0012,
          original_cost_usd: 0.0015,
          output_match: true,
          fidelity_match: true,
          created_at: '2026-04-18T12:00:00Z',
        },
        {
          pid: 'SR9876543210ZYXWVU0987',
          original_run_id: 'ORIG222',
          shadow_status: 'success',
          shadow_duration_ms: 220,
          original_duration_ms: 150,
          shadow_cost_usd: 0.003,
          original_cost_usd: 0.001,
          output_match: false,
          fidelity_match: true,
          created_at: '2026-04-18T12:05:00Z',
        },
      ],
    });
    await openPage(page);

    await page.locator('[data-testid="draft-DRAFTPID1234567890ABCD"] button').first().click();

    const table = page.locator('[data-testid="shadow-runs-table"]');
    await expect(table).toBeVisible();

    // Row 1: faster & cheaper → negative deltas, green
    const row1 = page.locator('[data-testid="shadow-run-SR1234567890ABCDEF1234"]');
    await expect(row1).toContainText('-60');       // 120 - 180
    await expect(row1).toContainText('-0.0003');    // 0.0012 - 0.0015
    await expect(row1).toContainText('✓');

    // Row 2: slower & more expensive → positive deltas, cross
    const row2 = page.locator('[data-testid="shadow-run-SR9876543210ZYXWVU0987"]');
    await expect(row2).toContainText('+70');        // 220 - 150
    await expect(row2).toContainText('+0.0020');    // 0.003 - 0.001
    await expect(row2).toContainText('✗');
  });

  test('LD-09: output_diff toggle is hidden when output_match=true (PR-52)', async ({ page }) => {
    await interceptLearningApi(page, {
      shadowRuns: [
        {
          pid: 'SRMATCHOK0000000000001',
          original_run_id: 'ORIG333',
          shadow_status: 'success',
          shadow_duration_ms: 100,
          original_duration_ms: 120,
          shadow_cost_usd: 0.001,
          original_cost_usd: 0.001,
          output_match: true,
          fidelity_match: true,
          output_diff: null,
          created_at: '2026-04-18T12:10:00Z',
        },
      ],
    });
    await openPage(page);

    await page.locator('[data-testid="draft-DRAFTPID1234567890ABCD"] button').first().click();
    await expect(page.locator('[data-testid="shadow-runs-table"]')).toBeVisible();
    await expect(
      page.locator('[data-testid="output-diff-toggle-SRMATCHOK0000000000001"]'),
    ).toHaveCount(0);
    await expect(
      page.locator('[data-testid="output-diff-panel-SRMATCHOK0000000000001"]'),
    ).toHaveCount(0);
  });

  test('LD-10: output_diff panel expands and shows JSON when output_match=false (PR-52)', async ({ page }) => {
    await interceptLearningApi(page, {
      shadowRuns: [
        {
          pid: 'SRDIFFMISMATCH00000001',
          original_run_id: 'ORIG444',
          shadow_status: 'success',
          shadow_duration_ms: 200,
          original_duration_ms: 150,
          shadow_cost_usd: 0.002,
          original_cost_usd: 0.001,
          output_match: false,
          fidelity_match: true,
          output_diff: JSON.stringify({
            expected_row_count: 3,
            actual_row_count: 2,
            missing_ids: ['id-7'],
          }),
          created_at: '2026-04-18T12:15:00Z',
        },
      ],
    });
    await openPage(page);

    await page.locator('[data-testid="draft-DRAFTPID1234567890ABCD"] button').first().click();
    const toggle = page.locator('[data-testid="output-diff-toggle-SRDIFFMISMATCH00000001"]');
    await expect(toggle).toBeVisible();

    // Panel hidden before toggle
    await expect(
      page.locator('[data-testid="output-diff-panel-SRDIFFMISMATCH00000001"]'),
    ).toHaveCount(0);

    await toggle.click();
    const panel = page.locator('[data-testid="output-diff-panel-SRDIFFMISMATCH00000001"]');
    await expect(panel).toBeVisible();
    await expect(panel).toContainText('expected_row_count');
    await expect(panel).toContainText('missing_ids');
  });

  test('LD-11: promotion metrics cards render with decision chip (PR-52)', async ({ page }) => {
    await interceptLearningApi(page, {
      list: [makeDraft({ status: 'REVIEWED_OK' })],
      detail: makeDetail({ status: 'REVIEWED_OK' }),
    });
    await page.route(/\/api\/learning\/drafts\/[^/]+\/evaluate-promotion$/, async (route: Route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(
          envelope({
            pid: 'DRAFTPID1234567890ABCD',
            decision: 'PROMOTE',
            shadow_runs: 8,
            output_match_rate: 0.95,
            fidelity_match_rate: 0.98,
            cost_delta: -0.001,
            duration_delta_ms: -40,
          }),
        ),
      });
    });
    await openPage(page);

    await page.locator('[data-testid="draft-DRAFTPID1234567890ABCD"] button').first().click();
    await page.locator('[data-testid="evaluate-promotion-btn"]').click();

    const cards = page.locator('[data-testid="promotion-cards"]');
    await expect(cards).toBeVisible();
    await expect(page.locator('[data-testid="promotion-shadow-runs"]')).toHaveText('8');
    await expect(page.locator('[data-testid="promotion-output-match"]')).toHaveText('95%');
    await expect(page.locator('[data-testid="promotion-fidelity-match"]')).toHaveText('98%');
    const chip = page.locator('[data-testid="promotion-decision-chip"]');
    await expect(chip).toHaveText('PROMOTE');
    await expect(chip).toHaveClass(/bg-green-100/);
  });
});
