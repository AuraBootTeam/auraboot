/**
 * Digital-thread M:N trace-graph — browser golden.
 *
 * Drives the mo_trace_graph page (two trace-graph blocks in mn-trace mode over
 * /api/ext/mo/trace/graph forward/backward) in a headed browser and asserts the
 * @xyflow/react canvas renders the real M:N lineage graphically — replacing the
 * generic command receipt arrays called out by the T10 evidence.
 *
 * Pids are seeded on the live stack via mo:append_trace_unit / mo:append_trace_edge.
 * Skips cleanly when the seed pids are absent so it is a no-op in unseeded CI.
 */
import { test, expect } from '@playwright/test';

const SEED_UNIT = process.env.MO_TRACE_UNIT_PID ?? '';

const pageUrl = (pid: string) => `/p/mo_trace_graph/view/${pid}`;
const FORWARD_BLOCK = '[data-testid="trace-graph-block-mo_trace_forward_graph"]';
const BACKWARD_BLOCK = '[data-testid="trace-graph-block-mo_trace_backward_graph"]';

test.skip(!SEED_UNIT, 'set MO_TRACE_UNIT_PID to a seeded trace unit pid to run');

test.describe('mo trace graph page — forward and backward graphical exploration', () => {
  test('happy: forward and backward blocks render canvases from real traversal data', async ({ page }) => {
    await page.goto(pageUrl(SEED_UNIT));

    await expect(page.locator(FORWARD_BLOCK)).toBeVisible({ timeout: 30000 });
    await expect(page.locator(`${FORWARD_BLOCK} [data-testid="trace-node-${SEED_UNIT}"]`)).toBeVisible({
      timeout: 20000,
    });
    // The traversal start unit is always present; at least one edge must render
    // for a seeded graph with lineage.
    const forwardEdges = page.locator(`${FORWARD_BLOCK} .react-flow__edge`);
    await expect(forwardEdges.first()).toBeVisible();

    await expect(page.locator(BACKWARD_BLOCK)).toBeVisible({ timeout: 30000 });
    await expect(page.locator(`${BACKWARD_BLOCK} [data-testid="trace-node-${SEED_UNIT}"]`)).toBeVisible();
  });

  test('edge: unknown unit pid renders the block error state, not raw arrays', async ({ page }) => {
    await page.goto(pageUrl('tu_does_not_exist'));
    await expect(page.locator(`${FORWARD_BLOCK} [data-testid="trace-graph-error"]`)).toBeVisible({
      timeout: 20000,
    });
  });
});
