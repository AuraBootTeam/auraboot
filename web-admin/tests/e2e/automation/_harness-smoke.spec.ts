/**
 * Phase-0 Harness Smoke Test — flow-designer-sdk drag gate
 *
 * Proves that `dragNodeToCanvas` in flow-designer-harness.ts can actually
 * add a node to the @xyflow canvas AND that the node is visually visible
 * in the viewport. This is the PREREQUISITE for every Layer A / golden test.
 *
 * Gate: trigger-record-create drag → node count 0→1, node is visible.
 * HSM-2: drag action node → count 1→2.
 *
 * HTML5-drag mechanism:
 *   FlowCanvas.onDrop reads event.dataTransfer.getData('application/flow-node').
 *   We dispatch a DragEvent carrying that payload via in-page evaluate(),
 *   sharing a single DataTransfer so the key survives across dragstart→drop.
 *   FlowCanvas converts screen coords to flow-space via screenToFlowPosition
 *   (from a ScreenToFlowPositionCapture child inside <ReactFlow>).
 *   fitView is disabled on empty canvases to prevent re-zoom after each drop.
 *
 * §20 guard: "a single-step drag that doesn't work may hide a real bug" —
 * we debug the real mechanism, we do NOT fake-pass or skip.
 */

import { test, expect } from '../../fixtures';
import {
  dragNodeToCanvas,
  currentNodeIds,
} from '../_helpers/flow-designer-harness';

const DESIGNER_ROUTE = '/automation/new';

test.describe('Phase-0 — harness smoke: drag to canvas', () => {
  test.beforeEach(async ({ page }) => {
    // Navigate to the new automation designer. Wait for the palette to appear
    // (ensures the canvas + nodes are registered and ready).
    await page.goto(DESIGNER_ROUTE);
    // Wait for the palette root — signals the flow-designer-sdk is mounted and
    // node definitions are registered.
    await page.locator('[data-testid="flow-palette"]').waitFor({ state: 'visible', timeout: 15000 });
    // Wait for the canvas pane to be present.
    await page.locator('.react-flow__pane').waitFor({ state: 'visible', timeout: 10000 });
  });

  test(
    'HSM-1: drag trigger-record-create onto canvas → node appears in viewport (count 0→1)',
    { tag: ['@smoke', '@harness'] },
    async ({ page }) => {
      // Confirm empty canvas before drag.
      const before = await currentNodeIds(page);
      expect(before.length, 'Canvas should start empty on /automation/new').toBe(0);

      // Confirm palette item is present.
      await expect(
        page.locator('[data-testid="palette-node-trigger-record-create"]'),
      ).toBeVisible();

      // Perform the drag at offset (200, 150) — well within the canvas area.
      const newNodeId = await dragNodeToCanvas(page, 'trigger-record-create', { x: 200, y: 150 });
      expect(typeof newNodeId, 'dragNodeToCanvas must return a string id').toBe('string');
      expect(newNodeId.length, 'node id must be non-empty').toBeGreaterThan(0);

      // Assert count 0→1.
      const after = await currentNodeIds(page);
      expect(after.length, 'Canvas should have exactly 1 node after drag').toBe(1);
      expect(after[0]).toBe(newNodeId);

      // Assert the node is VISIBLE in the viewport (not just in the DOM).
      // With fitView=false on empty canvases, the node renders at 1x zoom at
      // the exact drop coordinates, keeping it inside the visible viewport.
      const nodeLocator = page.locator(`[data-testid="flow-node-${newNodeId}"]`);
      await expect(nodeLocator).toBeVisible();

      // Take a screenshot as evidence.
      await page.screenshot({
        path: 'test-results/artifacts/harness-smoke-HSM1-canvas-with-node.png',
        fullPage: false,
      });
    },
  );

  test(
    'HSM-2: drag action node after trigger → count 1→2 (both nodes visible)',
    { tag: ['@smoke', '@harness'] },
    async ({ page }) => {
      // First drag a trigger at the left part of the canvas.
      const triggerId = await dragNodeToCanvas(page, 'trigger-record-create', { x: 100, y: 150 });

      // Now drag an action node slightly to the right.
      const actionId = await dragNodeToCanvas(page, 'action-update-record', { x: 320, y: 150 });

      const after = await currentNodeIds(page);
      expect(after.length, 'Canvas should have 2 nodes after two drags').toBe(2);
      expect(after).toContain(triggerId);
      expect(after).toContain(actionId);

      // Both nodes should be visible in the viewport.
      await expect(page.locator(`[data-testid="flow-node-${triggerId}"]`)).toBeVisible();
      await expect(page.locator(`[data-testid="flow-node-${actionId}"]`)).toBeVisible();

      await page.screenshot({
        path: 'test-results/artifacts/harness-smoke-HSM2-two-nodes.png',
        fullPage: false,
      });
    },
  );
});
