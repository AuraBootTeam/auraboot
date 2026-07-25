import { test, expect, type Page } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';

/**
 * Notification Centre — real action-point verification.
 *
 * Every assertion checks an OUTCOME (what the list contains, what the API returned,
 * what the toast says), never merely that a control exists — a "delete" button that
 * is visible but 404s on click used to pass a presence-only check.
 *
 * Each test provisions its own rows through the `notifications` fixture and keys
 * every assertion on that run's id. Sharing one pre-seeded set meant the delete test
 * silently broke whatever ran next.
 */

const SHOTS = process.env.PROBE_SHOT_DIR || '/tmp/collab-probe';
fs.mkdirSync(SHOTS, { recursive: true });

type Failure = { url: string; status: number; method: string };

function watchFailures(page: Page) {
  const failures: Failure[] = [];
  page.on('response', (r) => {
    if (r.status() >= 400 && r.url().includes('/api/')) {
      failures.push({ url: r.url(), status: r.status(), method: r.request().method() });
    }
  });
  return failures;
}

/** Seed 4 notifications (approval/system/alert unread + business read) owned by the caller. */
async function seedNotifications(page: Page): Promise<string> {
  const me = await (await page.request.get('/api/auth/me')).json();
  const user = me.data.user;
  const res = await page.request.post('/api/test/fixture', {
    data: {
      name: 'notifications',
      params: { userId: String(user.id), tenantId: String(user.tenantId) },
    },
  });
  const body = await res.json();
  expect(body.success, `fixture failed: ${JSON.stringify(body)}`).toBe(true);
  return body.testRunId as string;
}

async function openNotificationCentre(page: Page) {
  await page.goto('/notifications', { waitUntil: 'domcontentloaded' });
  await expect(page.getByRole('heading', { name: '通知中心' })).toBeVisible({ timeout: 15000 });
  await page.waitForTimeout(1200);
}

test.describe('notification centre actions', () => {
  test('NC-1: seeded notifications render with their categories', async ({ page }) => {
    const failures = watchFailures(page);
    const runId = await seedNotifications(page);
    await openNotificationCentre(page);

    for (const category of ['approval', 'system', 'alert', 'business']) {
      await expect(
        page.getByText(`E2E ${category} notification [${runId}]`, { exact: false }).first(),
      ).toBeVisible({ timeout: 10000 });
    }
    await page.screenshot({ path: path.join(SHOTS, '30-notif-list.png'), fullPage: true });
    expect(failures, `unexpected API failures: ${JSON.stringify(failures)}`).toEqual([]);
  });

  test('NC-2: category tab actually filters (was: tab highlighted, list unchanged)', async ({
    page,
  }) => {
    const runId = await seedNotifications(page);
    await openNotificationCentre(page);

    await page.getByRole('button', { name: '审批', exact: true }).click();
    await page.waitForTimeout(1500);
    await page.screenshot({ path: path.join(SHOTS, '31-notif-tab-approval.png'), fullPage: true });

    // The approval row stays; rows of every other category must be gone.
    await expect(page.getByText(`E2E approval notification [${runId}]`).first()).toBeVisible();
    for (const other of ['system', 'alert', 'business']) {
      await expect(page.getByText(`E2E ${other} notification [${runId}]`)).toHaveCount(0);
    }
  });

  test('NC-3: unread filter excludes the already-read row', async ({ page }) => {
    const runId = await seedNotifications(page);
    await openNotificationCentre(page);

    await page.getByRole('button', { name: '未读', exact: true }).click();
    await page.waitForTimeout(1500);
    await page.screenshot({ path: path.join(SHOTS, '32-notif-unread.png'), fullPage: true });

    // 'business' was seeded read → must not show under 未读; 'system' was unread → must.
    await expect(page.getByText(`E2E business notification [${runId}]`)).toHaveCount(0);
    await expect(page.getByText(`E2E system notification [${runId}]`).first()).toBeVisible();
  });

  test('NC-4: read filter shows only read rows (was: returned everything)', async ({ page }) => {
    const runId = await seedNotifications(page);
    await openNotificationCentre(page);

    // "已读" also appears as a per-row state label, so scope to the filter bar's
    // first match (全部 / 未读 / 已读 sits above the list).
    await page.getByRole('button', { name: '已读', exact: true }).first().click();
    await page.waitForTimeout(1500);
    await page.screenshot({ path: path.join(SHOTS, '33-notif-read.png'), fullPage: true });

    await expect(page.getByText(`E2E business notification [${runId}]`).first()).toBeVisible();
    for (const unread of ['approval', 'system', 'alert']) {
      await expect(page.getByText(`E2E ${unread} notification [${runId}]`)).toHaveCount(0);
    }
  });

  test('NC-5: delete selected removes the row for real (was: DELETE 404)', async ({ page }) => {
    const failures = watchFailures(page);
    const runId = await seedNotifications(page);
    await openNotificationCentre(page);

    const target = page
      .locator('div')
      .filter({ hasText: `E2E approval notification [${runId}]` })
      .last();
    await expect(target).toBeVisible({ timeout: 10000 });

    // Select that row, then delete it.
    const row = page
      .locator('.group.relative, [class*="rounded"]')
      .filter({ hasText: `E2E approval notification [${runId}]` })
      .first();
    const checkbox = row.locator('input[type="checkbox"]').first();
    await checkbox.check();
    await page.waitForTimeout(500);

    await page.getByRole('button', { name: /删除/ }).first().click();
    await page.waitForTimeout(2500);
    await page.screenshot({ path: path.join(SHOTS, '34-notif-after-delete.png'), fullPage: true });

    fs.writeFileSync(
      path.join(SHOTS, 'delete-evidence-fixed.json'),
      JSON.stringify({ runId, failures }, null, 2),
    );

    // Outcome 1: no failing API call (the endpoint must exist).
    expect(
      failures.filter((f) => f.method === 'DELETE'),
      `delete call failed: ${JSON.stringify(failures)}`,
    ).toEqual([]);
    // Outcome 2: the row is actually gone from the list.
    await expect(page.getByText(`E2E approval notification [${runId}]`)).toHaveCount(0);
  });

  test('NC-6: header exposes a notification bell that opens the dropdown', async ({ page }) => {
    await seedNotifications(page);
    await page.goto('/', { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(2500);

    const bell = page.locator('[data-testid="notification-bell"]').first();
    await expect(bell, 'header must expose a notification bell').toBeVisible({ timeout: 10000 });
    await bell.click();
    await page.waitForTimeout(1200);
    await page.screenshot({ path: path.join(SHOTS, '35-notif-bell.png'), fullPage: false });

    await expect(page.locator('[data-testid="notification-dropdown-panel"]')).toBeVisible({
      timeout: 5000,
    });
  });

  test('NC-7: the header actually opens the notification SSE stream', async ({ page }) => {
    // Mounting the bell is only half the fix. The backend has always pushed
    // `unread-count` frames and InAppChannel skips the push when no connection is
    // registered — so a bell that renders but never subscribes leaves "real-time
    // notifications" exactly as dead as before, and NC-6 alone would not notice.
    const streamRequests: string[] = [];
    page.on('request', (r) => {
      if (r.url().includes('/api/notifications/stream')) streamRequests.push(r.url());
    });

    await page.goto('/', { waitUntil: 'domcontentloaded' });
    await expect(page.locator('[data-testid="notification-bell"]').first()).toBeVisible({
      timeout: 10000,
    });
    // EventSource opens on mount; give it a moment to leave the browser.
    await page.waitForTimeout(3000);

    fs.writeFileSync(
      path.join(SHOTS, 'sse-evidence.json'),
      JSON.stringify({ streamRequests }, null, 2),
    );
    expect(
      streamRequests.length,
      'the header must subscribe to /api/notifications/stream',
    ).toBeGreaterThan(0);
  });
});
