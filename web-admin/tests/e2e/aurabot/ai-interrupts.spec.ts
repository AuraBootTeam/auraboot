/**
 * Mission Control — Interrupt audit E2E (PR-38)
 *
 * Drives /aurabot/interrupts, the tenant-wide audit view backed by
 * GET /api/aurabot/sessions/interrupts. Intercepts the API so the test
 * is independent of whether the Interrupt classifier has seen live
 * traffic.
 */

import { test, expect } from '../../fixtures';
import type { Page, Route } from '@playwright/test';

function envelope<T>(data: T) {
  return { code: '0', message: 'OK', data };
}

function makeRow(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    pid: 'INTERRUPTPID1234567890AB',
    session_id: 'sess_abc1234567',
    active_run_id: 'run_xyz99',
    new_message_excerpt: '算了,改成看上周数据',
    sub_policy: 'replace_intent',
    classifier_tier: 'keyword',
    confidence: 0.92,
    reason: 'matched keyword: 算了',
    action_taken: 'active_run_cancelled',
    created_at: '2026-04-18T12:00:00Z',
    ...overrides,
  };
}

async function interceptList(page: Page, rows: unknown[]) {
  await page.route(/\/api\/aurabot\/sessions\/interrupts(\?.*)?$/, async (route: Route) => {
    const url = new URL(route.request().url());
    const sub = url.searchParams.get('subPolicy');
    const filtered = sub
      ? (rows as Array<{ sub_policy: string }>).filter((r) => r.sub_policy === sub)
      : rows;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(envelope(filtered)),
    });
  });
}

async function openPage(page: Page) {
  await page.goto('/aurabot/interrupts');
  await page.waitForLoadState('domcontentloaded');
  await expect(page.locator('[data-testid="interrupts-page"]')).toBeVisible({ timeout: 10000 });
}

test.describe('Mission Control — Interrupt audit (PR-38)', () => {
  test('IL-01: empty state renders when no interrupts exist', async ({ page }) => {
    await interceptList(page, []);
    await openPage(page);
    await expect(page.locator('[data-testid="empty-state"]')).toBeVisible();
  });

  test('IL-02: rows render with policy badge, excerpt, confidence', async ({ page }) => {
    await interceptList(page, [
      makeRow({
        pid: 'INTERRUPTPID1234567890AB',
        sub_policy: 'replace_intent',
        new_message_excerpt: '算了,改成看上周数据',
        confidence: 0.92,
      }),
    ]);
    await openPage(page);

    const row = page.locator('[data-testid="interrupt-INTERRUPTPID1234567890AB"]');
    await expect(row).toBeVisible();
    await expect(row.locator('[data-testid="policy-badge"]')).toContainText(/替换意图|Replace intent/);
    await expect(row).toContainText('算了,改成看上周数据');
    await expect(row).toContainText('92%');
  });

  test('IL-03: policy filter re-queries with subPolicy param', async ({ page }) => {
    const requestedUrls: string[] = [];
    await page.route(/\/api\/aurabot\/sessions\/interrupts(\?.*)?$/, async (route: Route) => {
      requestedUrls.push(route.request().url());
      const url = new URL(route.request().url());
      const sub = url.searchParams.get('subPolicy');
      const all = [
        makeRow({ pid: 'IA1234567890123456789012', sub_policy: 'replace_intent' }),
        makeRow({ pid: 'IB1234567890123456789012', sub_policy: 'append_context' }),
        makeRow({ pid: 'IC1234567890123456789012', sub_policy: 'insert_subtask' }),
      ];
      const filtered = sub ? all.filter((r) => r.sub_policy === sub) : all;
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(envelope(filtered)),
      });
    });
    await openPage(page);
    // Initial fetch — 3 rows
    await expect(page.locator('[data-testid^="interrupt-"]')).toHaveCount(3);

    await page.locator('[data-testid="policy-filter"]').selectOption('replace_intent');
    await expect(page.locator('[data-testid^="interrupt-"]')).toHaveCount(1);

    const subReqs = requestedUrls.filter((u) => u.includes('subPolicy=replace_intent'));
    expect(subReqs.length).toBeGreaterThanOrEqual(1);
  });

  test('IL-04: three policy types render with distinct badge labels', async ({ page }) => {
    await interceptList(page, [
      makeRow({ pid: 'IX1234567890123456789012', sub_policy: 'replace_intent' }),
      makeRow({ pid: 'IY1234567890123456789012', sub_policy: 'append_context' }),
      makeRow({ pid: 'IZ1234567890123456789012', sub_policy: 'insert_subtask' }),
    ]);
    await openPage(page);

    await expect(
      page.locator('[data-testid="interrupt-IX1234567890123456789012"] [data-testid="policy-badge"]'),
    ).toContainText(/替换意图|Replace intent/);
    await expect(
      page.locator('[data-testid="interrupt-IY1234567890123456789012"] [data-testid="policy-badge"]'),
    ).toContainText(/追加上下文|Append context/);
    await expect(
      page.locator('[data-testid="interrupt-IZ1234567890123456789012"] [data-testid="policy-badge"]'),
    ).toContainText(/插入子任务|Insert subtask/);
  });
});
