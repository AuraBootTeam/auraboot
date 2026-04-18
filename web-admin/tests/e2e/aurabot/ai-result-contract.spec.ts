/**
 * AuraBot ResultContract rendering E2E
 *
 * Pins the end-to-end ResultContract pipeline (PRs 09 / 11) — when the
 * backend chat stream emits a `result_contract` SSE event, the AuraBot
 * panel must render the structured output via ResultContractView (with
 * the correct renderHint dispatch) instead of raw JSON text.
 *
 * We don't depend on a real LLM making deterministic tool calls. Instead
 * we intercept /api/ai/aurabot/chat/stream with Playwright and return a
 * canned SSE body carrying the event shape that ResultContractEmitter
 * produces on the backend.
 */

import { test, expect } from '../../fixtures';
import type { Page, Route } from '@playwright/test';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

async function gotoAppAndOpenPanel(page: Page) {
  // Auth inherited via storageState (see playwright.config.ts auth project).
  await page.goto('/meta/models');
  await page.waitForLoadState('domcontentloaded');

  const toggle = page.locator('[data-testid="ai-panel-toggle"]');
  await expect(toggle).toBeVisible({ timeout: 10000 });
  await expect(toggle).toBeEnabled({ timeout: 5000 });

  const panel = page.locator('[data-testid="aurabot-panel"]');
  const alreadyOpen = await panel.isVisible().catch(() => false);
  if (!alreadyOpen) {
    for (let attempt = 0; attempt < 3; attempt += 1) {
      await toggle.click();
      const opened = await panel.isVisible({ timeout: 2000 }).catch(() => false);
      if (opened) break;
    }
  }
  await expect(panel).toBeVisible({ timeout: 10000 });
  return panel;
}

/**
 * Build a canned SSE response body containing a single result_contract event
 * plus a terminating done event so the stream loop exits cleanly.
 */
function buildCannedStream(contract: Record<string, unknown>): string {
  const payload = JSON.stringify(contract);
  return (
    `event: result_contract\ndata: ${payload}\n\n` +
    `event: done\ndata: {"content":""}\n\n`
  );
}

/**
 * Intercept the streamChat endpoint and reply with the canned SSE body.
 * Other endpoints (conversations create, persist-user-msg) are allowed to
 * pass through so the UI state transitions work normally.
 */
async function interceptChatStream(page: Page, contract: Record<string, unknown>) {
  let hitCount = 0;
  await page.route('**/api/ai/aurabot/chat/stream', async (route: Route) => {
    hitCount += 1;
    // eslint-disable-next-line no-console
    console.log('[E2E] intercepted chat/stream, hit #' + hitCount);
    await route.fulfill({
      status: 200,
      contentType: 'text/event-stream; charset=utf-8',
      headers: {
        'Cache-Control': 'no-cache',
        Connection: 'keep-alive',
      },
      body: buildCannedStream(contract),
    });
  });
  // Log any 4xx/5xx response that isn't our canned stream so we can see real failures.
  page.on('response', (res) => {
    if (res.url().includes('/api/ai/aurabot') && res.status() >= 400) {
      // eslint-disable-next-line no-console
      console.log('[E2E] aurabot API failure', res.status(), res.url());
    }
  });
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

test.describe('AuraBot — ResultContract rendering', () => {
  test('RC-01: table renderHint produces a rendered <table> in the chat bubble', async ({ page }) => {
    const contract = {
      outputType: 'structured_result',
      renderHint: 'table',
      actionability: 'read_only',
      status: 'success',
      skillCode: 'nq_customer_list',
      durationMs: 142,
      textSummary: '2 total, 2 shown',
      table: [
        { pid: '01ACME', name: 'Acme', total: 100 },
        { pid: '01GLOBEX', name: 'Globex', total: 250 },
      ],
    };

    await interceptChatStream(page, contract);
    const panel = await gotoAppAndOpenPanel(page);

    const input = panel.locator('textarea');
    await expect(input).toBeVisible();
    await input.fill('show me the top customers');
    await input.press('Enter');

    const rc = panel.locator('[data-testid="result-contract"]');
    await expect(rc).toBeVisible({ timeout: 10000 });

    // Status + skill header
    await expect(rc.locator('text=success').first()).toBeVisible();
    await expect(rc.locator('text=nq_customer_list')).toBeVisible();
    await expect(rc.locator('text=142ms')).toBeVisible();

    // Table body
    const table = rc.locator('[data-testid="rc-table"]');
    await expect(table).toBeVisible();
    await expect(table.locator('text=Acme')).toBeVisible();
    await expect(table.locator('text=Globex')).toBeVisible();
    await expect(table.locator('th', { hasText: /^pid$/ })).toBeVisible();
    await expect(table.locator('th', { hasText: /^name$/ })).toBeVisible();
  });

  test('RC-02: card renderHint from dsl_command emits a card with data entries', async ({ page }) => {
    const contract = {
      outputType: 'action_proposal',
      renderHint: 'card',
      actionability: 'execute',
      status: 'success',
      skillCode: 'cmd_create_lead',
      durationMs: 215,
      textSummary: 'Lead created',
      data: { pid: '01NEW', crm_lead_company: 'TestCo', crm_lead_status: 'new' },
    };

    await interceptChatStream(page, contract);
    const panel = await gotoAppAndOpenPanel(page);

    const input = panel.locator('textarea');
    await input.fill('create a lead for TestCo');
    await input.press('Enter');

    const rc = panel.locator('[data-testid="result-contract"]');
    await expect(rc).toBeVisible({ timeout: 10000 });

    const card = rc.locator('[data-testid="rc-card"]');
    await expect(card).toBeVisible();
    await expect(card.locator('text=Lead created')).toBeVisible();
    await expect(card.locator('text=TestCo')).toBeVisible();
    await expect(card.locator('text=crm_lead_status')).toBeVisible();
  });

  test('RC-03: failed status renders red styling + error summary', async ({ page }) => {
    const contract = {
      outputType: 'text',
      renderHint: 'summary',
      actionability: 'read_only',
      status: 'failed',
      skillCode: 'nq_broken',
      durationMs: 8,
      textSummary: 'Query failed: column not found',
    };

    await interceptChatStream(page, contract);
    const panel = await gotoAppAndOpenPanel(page);

    const input = panel.locator('textarea');
    await input.fill('query a nonexistent thing');
    await input.press('Enter');

    const rc = panel.locator('[data-testid="result-contract"]');
    await expect(rc).toBeVisible({ timeout: 10000 });

    const statusBadge = rc.locator('text=failed').first();
    await expect(statusBadge).toBeVisible();
    await expect(statusBadge).toHaveClass(/text-red-600/);

    await expect(rc.locator('[data-testid="rc-summary"]')).toBeVisible();
    await expect(rc.locator('text=/column not found/')).toBeVisible();
  });
});
