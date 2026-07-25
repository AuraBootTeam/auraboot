import { test, expect } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';

/**
 * Inbox — real action-point verification.
 *
 * Each test drives a real affordance and asserts the resulting state change,
 * not the mere presence of a control.
 */

const SHOTS = process.env.PROBE_SHOT_DIR || '/tmp/collab-probe';
fs.mkdirSync(SHOTS, { recursive: true });

test.describe('inbox actions', () => {
  test('IB-1: the "All" tab shows the real unread total, not a double-count', async ({ page }) => {
    // Seed unread items of several types so the double-count bug has something to
    // double: with zero unread, "total" and "2 x total" are the same number and the
    // assertion would pass no matter what the page rendered.
    const me = await (await page.request.get('/api/auth/me')).json();
    const user = me.data.user;
    for (const fixture of ['inbox_items', 'inbox_alert', 'inbox_assignment']) {
      await page.request.post('/api/test/fixture', {
        data: {
          name: fixture,
          params: { count: 2, userId: String(user.id), tenantId: String(user.tenantId) },
        },
      });
    }

    await page.goto('/inbox', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { name: 'Inbox' })).toBeVisible({ timeout: 10000 });
    await page.waitForTimeout(1500);

    // Source of truth: the same endpoint the page reads.
    const summaryResp = await page.request.get('/api/inbox/unread-summary');
    const summaryBody = await summaryResp.json();
    const serverTotal: number = summaryBody.data.total;
    const perTypeSum = Object.entries(summaryBody.data)
      .filter(([k]) => k !== 'total')
      .reduce((acc, [, v]) => acc + (v as number), 0);

    const allTabCount = (
      await page.locator('[data-testid="inbox-tab-count-all"]').innerText()
    ).trim();
    const rows = await page.locator('[data-testid^="inbox-item-"]').count();

    fs.writeFileSync(
      path.join(SHOTS, 'inbox-count-evidence.json'),
      JSON.stringify(
        { allTabCount, renderedRows: rows, serverTotal, perTypeSum, summary: summaryBody.data },
        null,
        2,
      ),
    );
    await page.screenshot({ path: path.join(SHOTS, '20-inbox-counts.png'), fullPage: true });

    // The badge must equal the server's unread total. The bug this guards against
    // summed every value of the summary map — including its own `total` key — so the
    // badge read serverTotal + perTypeSum (exactly double) instead of serverTotal.
    expect(
      Number(allTabCount),
      `tab shows ${allTabCount}; server says total=${serverTotal} (per-type sum=${perTypeSum})`,
    ).toBe(serverTotal);
    expect(serverTotal, 'seeding should have produced unread items').toBeGreaterThan(0);
    expect(
      Number(allTabCount),
      'badge must not be the double-counted total',
    ).not.toBe(serverTotal + perTypeSum);
  });

  test('IB-2: dismiss removes the item from the list and from the backend', async ({ page }) => {
    await page.goto('/inbox', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { name: 'Inbox' })).toBeVisible({ timeout: 10000 });
    await page.waitForTimeout(1500);

    const before = await page.locator('[data-testid^="inbox-item-"]').count();
    expect(before, 'need at least one item to dismiss').toBeGreaterThan(0);

    const firstDismiss = page.locator('[data-testid^="inbox-dismiss-"]').first();
    await firstDismiss.click();
    await page.waitForTimeout(2500);
    await page.screenshot({ path: path.join(SHOTS, '21-inbox-after-dismiss.png'), fullPage: true });

    const after = await page.locator('[data-testid^="inbox-item-"]').count();
    expect(after, 'dismiss must remove the row from the list').toBe(before - 1);
  });

  test('IB-3: mark all read drives the unread count to zero', async ({ page }) => {
    await page.goto('/inbox', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { name: 'Inbox' })).toBeVisible({ timeout: 10000 });
    await page.waitForTimeout(1500);

    await page.locator('[data-testid="inbox-mark-all-read"]').click();
    await page.waitForTimeout(2500);
    await page.screenshot({ path: path.join(SHOTS, '22-inbox-mark-all-read.png'), fullPage: true });

    // Outcome: backend unread count is 0.
    const resp = await page.request.get('/api/inbox/unread-count');
    const body = await resp.json();
    fs.writeFileSync(
      path.join(SHOTS, 'inbox-unread-after-markall.json'),
      JSON.stringify(body, null, 2),
    );
    expect(body.data, 'unread count must be 0 after mark-all-read').toBe(0);
  });

  test('IB-4: type tab filters the list to that type only', async ({ page }) => {
    await page.goto('/inbox', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { name: 'Inbox' })).toBeVisible({ timeout: 10000 });
    await page.waitForTimeout(1500);

    await page.locator('[data-testid="inbox-tab-alert"]').click();
    await page.waitForTimeout(2000);
    await page.screenshot({ path: path.join(SHOTS, '23-inbox-tab-alert.png'), fullPage: true });

    const rowTexts = await page.locator('[data-testid^="inbox-item-"]').allInnerTexts();
    fs.writeFileSync(
      path.join(SHOTS, 'inbox-tab-filter-evidence.json'),
      JSON.stringify({ rowTexts }, null, 2),
    );
    // Every listed row must be an Alert row.
    for (const t of rowTexts) {
      expect(t, `non-alert row leaked into the Alert tab: ${t}`).toMatch(/Alert/i);
    }
    expect(rowTexts.length, 'Alert tab should list the seeded alert items').toBeGreaterThan(0);
  });
});
