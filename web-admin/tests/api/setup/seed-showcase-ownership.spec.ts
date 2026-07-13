/**
 * Showcase Seed — CRM Ownership Distribution
 *
 * Every CRM record is created by the admin account, and the create commands
 * auto-set the owner to the current user (`autoSetFields: { crm_opp_owner:
 * { strategy: 'current_username' } }`). The result is that all opportunities
 * and activities end up owned by a single user, which makes every owner-grouped
 * chart degenerate into one bar / one radar line / one heatmap row.
 *
 * This phase reassigns ownership across the seeded sales team so that
 * leaderboards, team-capability radars and activity heatmaps have real
 * cardinality. Assignment is deterministic (records sorted by pid, owners
 * drawn from a weighted slot pool) so repeated seeds produce the same board.
 *
 * Runs AFTER every phase that creates CRM records (data / extended / arsenal /
 * supplement):
 *   node scripts/run-showcase-seed-sequence.mjs ownership
 */

import { test, expect } from '@playwright/test';
import { executeCommandViaApi } from '../../e2e/helpers';

/**
 * The sales team seeded by seed-showcase-data (dept = 销售部).
 * Weight drives how many records each rep owns — a flat split would make the
 * leaderboard a straight line, which is exactly the "doesn't look real" problem
 * this phase exists to fix.
 */
const SALES_TEAM: Array<{ name: string; weight: number }> = [
  { name: '陈志豪', weight: 4 }, // 大客户经理
  { name: '王佳琳', weight: 3 }, // 销售总监
  { name: '张雨晴', weight: 3 }, // 高级销售
  { name: '林伟杰', weight: 3 }, // 高级销售
  { name: '刘思雨', weight: 2 }, // 销售代表
  { name: '周梦琪', weight: 2 }, // 销售代表
  { name: '孙浩然', weight: 2 }, // 销售代表
  { name: '赵小燕', weight: 1 }, // 销售代表
];

/** Expand the weights into a slot pool so `pool[i % pool.length]` is a weighted pick. */
const OWNER_POOL: string[] = SALES_TEAM.flatMap(({ name, weight }) =>
  Array.from({ length: weight }, () => name),
);

interface DynamicRecord {
  pid?: string;
  [key: string]: unknown;
}

/**
 * Fetch every record of a dynamic model, sorted by pid so assignment is stable
 * across runs (ULIDs are creation-ordered).
 */
async function listAll(page: any, modelCode: string): Promise<DynamicRecord[]> {
  const resp = await page.request.get(
    `/api/dynamic/${modelCode}/list?pageNum=1&pageSize=1000`,
  );
  expect(resp.ok(), `list ${modelCode} failed: ${resp.status()}`).toBeTruthy();
  const body = await resp.json();
  const records: DynamicRecord[] = body?.data?.records ?? [];
  return records
    .filter((r) => typeof r.pid === 'string' && r.pid)
    .sort((a, b) => String(a.pid).localeCompare(String(b.pid)));
}

/** Assign owners across records and report the resulting distribution. */
async function distributeOwners(
  page: any,
  modelCode: string,
  commandCode: string,
  ownerField: string,
): Promise<Map<string, number>> {
  const records = await listAll(page, modelCode);
  const distribution = new Map<string, number>();

  for (const [index, record] of records.entries()) {
    const owner = OWNER_POOL[index % OWNER_POOL.length];
    await executeCommandViaApi(
      page,
      commandCode,
      { [ownerField]: owner },
      record.pid,
      'update',
    );
    distribution.set(owner, (distribution.get(owner) ?? 0) + 1);
  }

  return distribution;
}

test.describe('Showcase Seed — CRM ownership distribution', () => {
  test.describe.configure({ mode: 'serial', timeout: 300_000 });

  test('Ownership 1: Opportunities spread across the sales team', async ({ page }) => {
    const distribution = await distributeOwners(
      page,
      'crm_opportunity',
      'crm:update_opportunity',
      'crm_opp_owner',
    );

    for (const [owner, count] of [...distribution].sort((a, b) => b[1] - a[1])) {
      console.log(`  ${owner}: ${count} opportunities`);
    }

    // Every rep must own something, otherwise the leaderboard has holes.
    expect(distribution.size).toBe(SALES_TEAM.length);
    // And the split must actually be uneven — a flat board is the bug we are fixing.
    const counts = [...distribution.values()];
    expect(Math.max(...counts)).toBeGreaterThan(Math.min(...counts));
  });

  test('Ownership 2: Activities spread across the sales team', async ({ page }) => {
    const distribution = await distributeOwners(
      page,
      'crm_activity',
      'crm:update_activity',
      'crm_act_owner',
    );

    for (const [owner, count] of [...distribution].sort((a, b) => b[1] - a[1])) {
      console.log(`  ${owner}: ${count} activities`);
    }

    expect(distribution.size).toBe(SALES_TEAM.length);
  });

  test('Ownership 3: Owner values are readable names, not user ids', async ({ page }) => {
    // The owner column is a plain string field. Before this phase it held the
    // admin user's pid (a ULID), which would surface as the category label on
    // every owner-grouped chart.
    const opportunities = await listAll(page, 'crm_opportunity');
    const owners = new Set(
      opportunities.map((r) => String(r.crm_opp_owner ?? '')).filter(Boolean),
    );

    expect(owners.size).toBeGreaterThan(1);
    for (const owner of owners) {
      expect(owner, `owner "${owner}" looks like a raw id`).not.toMatch(/^[0-9A-HJKMNP-TV-Z]{26}$/);
      expect(SALES_TEAM.map((m) => m.name)).toContain(owner);
    }
  });
});
