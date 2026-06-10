/**
 * Digital-thread trace-graph block — browser golden.
 *
 * Drives the real `trace-graph` DSL block mounted on pe_production_plan_detail
 * (consumption mode, dataSource nq:pe_consumption_trace_by_lot) in a headed
 * browser and asserts the @xyflow/react canvas renders real lineage —
 * something jsdom unit tests cannot do (ReactFlow needs layout geometry).
 *
 * Pids are seeded on the live stack via the command pipeline (see seed.sh):
 *   HAPPY_PLAN — production plan that consumed one lot (WORK_ORDER -> LOT)
 *   HAPPY_LOT  — the consumed inventory lot
 *   EMPTY_PLAN — production plan with no consumption (empty-state)
 */
import { test, expect } from '@playwright/test';

const HAPPY_PLAN = process.env.HAPPY_PLAN!;
const HAPPY_LOT = process.env.HAPPY_LOT!;
const HAPPY_PLAN_CODE = process.env.HAPPY_PLAN_CODE || 'PP-20260610-001';
const HAPPY_LOT_CODE = process.env.HAPPY_LOT_CODE || 'LOT-RES10K-001';
const EMPTY_PLAN = process.env.EMPTY_PLAN!;

const detailUrl = (pid: string) => `/p/pe_production_plan/view/${pid}`;
const BLOCK = '[data-testid="trace-graph-block-block_pp_detail_trace"]';

// Requires a stack seeded with the digital-thread lineage (see
// docs/handover/... g2 trace-graph golden, or scripts that drive
// pe:validate_material_binding). Skips cleanly when the seed pids are absent
// so it is a no-op in unseeded CI rather than a false failure.
test.skip(!HAPPY_PLAN || !HAPPY_LOT || !EMPTY_PLAN,
  'set HAPPY_PLAN / HAPPY_LOT / EMPTY_PLAN to the seeded pids to run');

test.describe('trace-graph block — digital-thread consumption lineage', () => {
  test('happy: renders WORK_ORDER -> LOT nodes + consumes edge from real data', async ({ page }) => {
    await page.goto(detailUrl(HAPPY_PLAN));
    // block mounts
    await expect(page.locator(BLOCK)).toBeVisible({ timeout: 30000 });

    // WORK_ORDER node (id = production plan pid)
    const woNode = page.locator(`[data-testid="trace-node-${HAPPY_PLAN}"]`);
    await expect(woNode).toBeVisible({ timeout: 20000 });
    await expect(woNode).toHaveAttribute('data-node-type', 'WORK_ORDER');
    await expect(woNode).toContainText(HAPPY_PLAN_CODE);

    // LOT node (id = lot pid)
    const lotNode = page.locator(`[data-testid="trace-node-${HAPPY_LOT}"]`);
    await expect(lotNode).toBeVisible();
    await expect(lotNode).toHaveAttribute('data-node-type', 'LOT');
    await expect(lotNode).toContainText(HAPPY_LOT_CODE);

    // exactly the two nodes, distinct
    await expect(page.locator(`${BLOCK} [data-testid^="trace-node-"]`)).toHaveCount(2);

    // edge labelled "consumes <qty>" (ReactFlow edge label)
    await expect(page.locator(`${BLOCK} .react-flow__edge`)).toHaveCount(1);
    await expect(page.locator(`${BLOCK} .react-flow__edge-text`).first()).toContainText(/consumes/i);

    await page.screenshot({ path: 'tests/storage/g2trace-happy.png', fullPage: true });
  });

  test('edge: empty state when a plan has no consumption', async ({ page }) => {
    await page.goto(detailUrl(EMPTY_PLAN));
    await expect(page.locator('[data-testid="trace-graph-empty"]')).toBeVisible({ timeout: 30000 });
    // no nodes rendered
    await expect(page.locator('[data-testid^="trace-node-"]')).toHaveCount(0);
    await page.screenshot({ path: 'tests/storage/g2trace-empty.png', fullPage: true });
  });
});
