/**
 * Behavior Quarantine Admin — Golden Spec
 *
 * Proves the operator-facing DSL page for ab_behavior_quarantine:
 *   seed quarantine row → /p/c/behavior_quarantine_list shows reason + raw event
 *   row Replay action → POST replay API → ab_behavior_event row + replay_status=replayed
 */

import { test, expect } from '../../fixtures';
import type { Page } from '@playwright/test';
import { execSync } from 'node:child_process';
import path from 'node:path';
import { PSQL_BASE, PG_ENV, BACKEND_URL } from '../../helpers/environments';

const E2E_PG_ENV = { ...PG_ENV, PGPASSWORD: PG_ENV.PGPASSWORD ?? 'auraboot' };
const ASSERT_TIMEOUT_MS = 5_000;

function psql(sql: string): string {
  return execSync(`${PSQL_BASE} -P pager=off -t -A -c "${sql.replace(/"/g, '\\"')}"`, {
    encoding: 'utf-8',
    env: E2E_PG_ENV,
    timeout: ASSERT_TIMEOUT_MS,
  }).trim();
}

function applyMigration(file: string) {
  const migration = path.resolve(process.cwd(), '../platform/src/main/resources/db/migration/core', file);
  execSync(`${PSQL_BASE} -P pager=off -q -f '${migration}'`, {
    env: E2E_PG_ENV,
    timeout: ASSERT_TIMEOUT_MS,
  });
}

function sqlLit(s: string): string {
  return s.replace(/'/g, "''");
}

function adminTenantId(): string {
  const out = execSync(
    `curl -sf -X POST ${BACKEND_URL}/api/auth/login -H 'Content-Type: application/json' ` +
      `-d '{"email":"admin@auraboot.com","password":"Test2026x"}'`,
    { encoding: 'utf-8', timeout: ASSERT_TIMEOUT_MS },
  );
  const token = JSON.parse(out)?.data?.jwt as string;
  if (!token) throw new Error(`Admin login failed: ${out}`);
  const raw = Buffer.from(
    token.split('.')[1] + '='.repeat((4 - (token.split('.')[1].length % 4)) % 4),
    'base64',
  ).toString('utf-8');
  const m = raw.match(/"tenantId"\s*:\s*(\d+)/);
  if (!m) throw new Error(`No tenantId in JWT: ${raw}`);
  return m[1];
}

async function openQuarantinePageFromSidebar(page: Page) {
  await page.goto('/', { waitUntil: 'domcontentloaded' });
  await page.evaluate(() => {
    localStorage.removeItem('sidebar-collapsed');
  });
  await page.reload({ waitUntil: 'domcontentloaded' });

  const nav = page.locator('nav, aside, [role="navigation"]').first();
  let link = nav.locator('a[href="/p/c/behavior_quarantine_list"]').first();
  if (!(await link.isVisible().catch(() => false))) {
    const analyticsMenu = nav
      .locator('[data-menu-code="analytics_root"]')
      .or(nav.getByRole('button', { name: /Analytics|分析|analytics/i }))
      .first();
    if (await analyticsMenu.isVisible().catch(() => false)) {
      await analyticsMenu.click();
    }
    link = nav
      .locator('a[href="/p/c/behavior_quarantine_list"]')
      .or(nav.getByRole('link', { name: /Behavior Quarantine|行为隔离队列/i }))
      .first();
  }

  await expect(link).toBeVisible({ timeout: ASSERT_TIMEOUT_MS });
  await link.click();
  await expect(page).toHaveURL(/\/p\/c\/behavior_quarantine_list/);
}

test.describe.serial('Behavior Quarantine Admin — Golden', () => {
  test.setTimeout(60_000);
  test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });

  let tenantId: string;
  let eventId: string;
  let decoyEventId: string;

  test.beforeAll(() => {
    applyMigration('V20260620000200__behavior_event_store.sql');
    applyMigration('V20260622000000__behavior_quarantine_sink.sql');
    applyMigration('V20260622001000__behavior_quarantine_replay_state.sql');

    tenantId = adminTenantId();
    eventId = `bqa-${Date.now().toString(36)}`;
    decoyEventId = `bqa-decoy-${Date.now().toString(36)}`;
    const rawEvent = JSON.stringify({
      eventId,
      schemaVersion: '1',
      eventName: 'page_view',
      eventCategory: 'navigation',
      source: 'web',
      anonId: 'bqa-anon',
      props: { routeTemplate: '/p/c/behavior_quarantine_list', browser: 'chromium' },
    });
    const decoyRawEvent = JSON.stringify({
      eventId: decoyEventId,
      schemaVersion: '1',
      eventName: 'page_view',
      eventCategory: 'navigation',
      source: 'web',
      anonId: 'bqa-decoy',
      props: { routeTemplate: '/p/c/behavior_quarantine_list', browser: 'chromium' },
    });

    psql(`DELETE FROM ab_behavior_event WHERE tenant_id=${tenantId} AND event_id='${sqlLit(eventId)}'`);
    psql(`DELETE FROM ab_behavior_event WHERE tenant_id=${tenantId} AND event_id='${sqlLit(decoyEventId)}'`);
    psql(`DELETE FROM ab_behavior_quarantine WHERE tenant_id=${tenantId} AND anon_id IN ('bqa-anon', 'bqa-decoy')`);
    psql(`
      INSERT INTO ab_behavior_quarantine
          (tenant_id, user_id, anon_id, event_id, event_name, reason, detail, raw_event)
      VALUES
          (${tenantId}, NULL, 'bqa-anon', '${sqlLit(eventId)}', 'page_view',
           'constraint_violation', 'Golden raw event exceeded old field limit',
           '${sqlLit(rawEvent)}'::jsonb)
    `);
    psql(`
      INSERT INTO ab_behavior_quarantine
          (tenant_id, user_id, anon_id, event_id, event_name, reason, detail, raw_event)
      VALUES
          (${tenantId}, NULL, 'bqa-decoy', '${sqlLit(decoyEventId)}', 'page_view',
           'malformed_missing_event_name', 'Golden decoy for filter coverage',
           '${sqlLit(decoyRawEvent)}'::jsonb)
    `);
  });

  test('BQA-01 list page shows reason/raw event and replay writes one event row', async ({ page }) => {
    await openQuarantinePageFromSidebar(page);
    await expect(page.getByText('Behavior Quarantine').or(page.getByText('行为隔离队列'))).toBeVisible({
      timeout: ASSERT_TIMEOUT_MS,
    });
    const quarantineRow = page.locator('[data-testid^="table-row-"]').filter({ hasText: eventId }).first();
    await expect(quarantineRow).toBeVisible({ timeout: ASSERT_TIMEOUT_MS });
    await expect(page.locator('[data-testid^="table-row-"]').filter({ hasText: decoyEventId })).toBeVisible({
      timeout: ASSERT_TIMEOUT_MS,
    });
    await expect(quarantineRow.locator('[data-testid$="-reason"]').filter({ hasText: 'constraint_violation' })).toBeVisible();
    await expect(quarantineRow.locator('[data-testid$="-eventId"]').filter({ hasText: eventId })).toBeVisible();
    await expect(
      quarantineRow.locator('[data-testid$="-detail"]').filter({ hasText: 'Golden raw event exceeded old field limit' }),
    ).toBeVisible();
    await expect(quarantineRow.locator('[data-testid$="-rawEvent"]').filter({ hasText: '/p/c/behavior_quarantine_list' })).toBeVisible();

    await page.screenshot({ path: 'test-results/bqa-01-quarantine-list.png', fullPage: true });

    await page.getByTestId('filters-toggle').click();
    await expect(page.getByTestId('search-area')).toBeVisible({ timeout: ASSERT_TIMEOUT_MS });
    await page.getByTestId('field-reason').getByRole('textbox').fill('constraint_violation');
    await page.getByTestId('filter-search').click();
    await expect(page.locator('[data-testid^="table-row-"]').filter({ hasText: eventId })).toBeVisible({
      timeout: ASSERT_TIMEOUT_MS,
    });
    await expect(page.locator('[data-testid^="table-row-"]').filter({ hasText: decoyEventId })).toHaveCount(0);
    await page.getByTestId('filter-reset').click();
    await expect(page.locator('[data-testid^="table-row-"]').filter({ hasText: eventId })).toBeVisible({
      timeout: ASSERT_TIMEOUT_MS,
    });

    await page.locator('[data-testid^="table-row-"]').filter({ hasText: eventId }).first().getByTestId('row-action-replay').click();
    await expect
      .poll(
        () => psql(`SELECT replay_status FROM ab_behavior_quarantine WHERE tenant_id=${tenantId} AND event_id='${sqlLit(eventId)}'`),
        { timeout: ASSERT_TIMEOUT_MS, message: 'quarantine replay_status becomes replayed' },
      )
      .toBe('replayed');
    await expect
      .poll(
        () => psql(`SELECT count(*) FROM ab_behavior_event WHERE tenant_id=${tenantId} AND event_id='${sqlLit(eventId)}'`),
        { timeout: ASSERT_TIMEOUT_MS, message: 'replay writes exactly one behavior event' },
      )
      .toBe('1');
    await page.reload({ waitUntil: 'domcontentloaded' });
    const replayedRow = page.locator('[data-testid^="table-row-"]').filter({ hasText: eventId }).first();
    await expect(replayedRow.locator('[data-testid$="-replayStatus"]').filter({ hasText: 'replayed' })).toBeVisible({
      timeout: ASSERT_TIMEOUT_MS,
    });

    await page.getByTestId('filters-toggle').click();
    await page.getByTestId('field-replayStatus').getByRole('textbox').fill('replayed');
    await page.getByTestId('filter-search').click();
    await expect(page.locator('[data-testid^="table-row-"]').filter({ hasText: eventId })).toBeVisible({
      timeout: ASSERT_TIMEOUT_MS,
    });
    await expect(page.locator('[data-testid^="table-row-"]').filter({ hasText: decoyEventId })).toHaveCount(0);
  });
});
