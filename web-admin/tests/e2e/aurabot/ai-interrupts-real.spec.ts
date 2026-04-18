/**
 * Mission Control — Interrupt audit REAL-backend E2E (PR-64).
 *
 * Drives /aurabot/interrupts against the live backend. Seeds rows
 * directly into ab_agent_interrupt_log via psql and asserts the
 * rendered UI matches both the DB state and the classifier text.
 *
 * IL-E2E-01 — real interrupt log render: seed three rows with distinct
 *             sub_policy values, navigate via sidebar, assert the
 *             policy badges / excerpts / confidences render.
 */

import { test, expect } from '../../fixtures';
import type { Page } from '@playwright/test';
import {
  seedInterrupt,
  cleanupInterrupts,
  seedMissionControlMenus,
  cleanupMissionControlMenus,
  type SeededMenus,
} from './_real-backend-helpers';

let seededMenus: SeededMenus;
// All seeded interrupt rows use session IDs that start with this prefix
// so afterAll can blast them with one DELETE.
const SESSION_PREFIX = 'e2e_il_real_';

test.beforeAll(async () => {
  seededMenus = seedMissionControlMenus();
});

test.afterAll(async () => {
  if (seededMenus) cleanupMissionControlMenus(seededMenus);
  cleanupInterrupts(SESSION_PREFIX);
});

async function navigateViaSidebar(page: Page): Promise<void> {
  await page.goto('/');
  await page.waitForLoadState('domcontentloaded');

  const nav = page.locator('nav').first();
  const aiCenter = nav.getByRole('button', { name: /AI 中心|AI Center/ });
  await aiCenter.waitFor({ state: 'visible', timeout: 10_000 });
  await aiCenter.evaluate((el: HTMLElement) => el.click());

  const leaf = nav.getByRole('link', { name: /中断审计|Interrupts?/ });
  await leaf.waitFor({ state: 'visible', timeout: 5_000 });
  await leaf.evaluate((el: HTMLElement) => el.click());

  await expect(page.locator('[data-testid="interrupts-page"]')).toBeVisible({
    timeout: 10_000,
  });
}

test.describe('Mission Control — Interrupt audit (real backend, PR-64)', () => {
  test('IL-E2E-01: real interrupt log rows render with badge + excerpt + confidence', async ({
    page,
  }) => {
    const sessionId = `${SESSION_PREFIX}${Date.now()}`;

    // Seed 3 rows — one per sub_policy — so we exercise the classifier
    // → badge translation for every enum value.
    const replace = seedInterrupt({
      sessionId,
      subPolicy: 'replace_intent',
      excerpt: '算了,改成看上周数据',
      confidence: 0.92,
    });
    const append = seedInterrupt({
      sessionId,
      subPolicy: 'append_context',
      excerpt: '再加上华南区的数据',
      confidence: 0.81,
    });
    const insert = seedInterrupt({
      sessionId,
      subPolicy: 'insert_subtask',
      excerpt: '顺便帮我导出一下 CSV',
      confidence: 0.74,
    });

    await navigateViaSidebar(page);

    // Backend returns newest-first; wait until all three rows are rendered.
    const table = page.locator('[data-testid="interrupts-table"]');
    await expect(table).toBeVisible({ timeout: 10_000 });

    // Replace-intent row — full triangle: badge label, excerpt, confidence%.
    const replaceRow = page.locator(`[data-testid="interrupt-${replace.pid}"]`);
    await expect(replaceRow).toBeVisible();
    await expect(
      replaceRow.locator('[data-testid="policy-badge"]'),
    ).toContainText(/替换意图|Replace intent/);
    await expect(replaceRow).toContainText('算了,改成看上周数据');
    await expect(replaceRow).toContainText('92%');

    // Append-context row.
    const appendRow = page.locator(`[data-testid="interrupt-${append.pid}"]`);
    await expect(
      appendRow.locator('[data-testid="policy-badge"]'),
    ).toContainText(/追加上下文|Append context/);
    await expect(appendRow).toContainText('再加上华南区的数据');
    await expect(appendRow).toContainText('81%');

    // Insert-subtask row.
    const insertRow = page.locator(`[data-testid="interrupt-${insert.pid}"]`);
    await expect(
      insertRow.locator('[data-testid="policy-badge"]'),
    ).toContainText(/插入子任务|Insert subtask/);
    await expect(insertRow).toContainText('顺便帮我导出一下 CSV');
    await expect(insertRow).toContainText('74%');
  });
});
