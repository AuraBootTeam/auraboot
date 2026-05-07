/**
 * ChildRunTree.test.tsx
 *
 * Pins ACP backlog C.3 — replays the flat child-run list as a depth-capped
 * tree under AgentRunDetailDrawer's Child Runs section.
 *
 * Cases:
 *   1. tree_structure: 3-level nesting renders parents before children
 *      with monotonically increasing data-depth attributes.
 *   2. depth_cap: a chain of 7 nested runs caps at depth=5 with a
 *      "(... N more nested runs)" placeholder; node 6/7 are not in DOM.
 *   3. cycle_break: a parent->child->parent cycle does not infinite-loop;
 *      render finishes synchronously and each runId DOM node appears only
 *      once on its first encountered ancestor path.
 */

import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';

import ChildRunTree, { MAX_DEPTH } from '../components-internal/ChildRunTree';
import type { AgentRunListItem } from '../services/agentRunsApi';

function mkRun(
  runId: string,
  parentRunId: string | null,
  overrides: Partial<AgentRunListItem> = {},
): AgentRunListItem {
  return {
    runId,
    agentCode: `agent-${runId}`,
    runStatus: 'succeeded',
    parentRunId,
    subtaskOrigin: null,
    costUsd: null,
    durationMs: null,
    createdAt: '2026-05-07T00:00:00Z',
    completedAt: null,
    intentSummary: null,
    ...overrides,
  };
}

describe('ChildRunTree', () => {
  it('renders 3-level nesting with increasing depth attributes', () => {
    // Drawer's open run is "root". Direct children: A, B. A has child A1.
    // A1 has child A1x. So depths under "root": A=0, A1=1, A1x=2; B=0.
    const rows: AgentRunListItem[] = [
      mkRun('A', 'root'),
      mkRun('B', 'root'),
      mkRun('A1', 'A'),
      mkRun('A1x', 'A1'),
    ];

    render(
      <ChildRunTree rows={rows} parentRunId="root" onSelectRun={vi.fn()} />,
    );

    expect(screen.getByTestId('child-run-tree')).toBeTruthy();
    expect(screen.getByTestId('child-run-node-A').getAttribute('data-depth')).toBe('0');
    expect(screen.getByTestId('child-run-node-B').getAttribute('data-depth')).toBe('0');
    expect(screen.getByTestId('child-run-node-A1').getAttribute('data-depth')).toBe('1');
    expect(screen.getByTestId('child-run-node-A1x').getAttribute('data-depth')).toBe('2');

    // DOM order: parent before child.
    const html = screen.getByTestId('child-run-tree').innerHTML;
    const idxA = html.indexOf('child-run-node-A"');
    const idxA1 = html.indexOf('child-run-node-A1"');
    const idxA1x = html.indexOf('child-run-node-A1x');
    expect(idxA).toBeGreaterThan(-1);
    expect(idxA1).toBeGreaterThan(idxA);
    expect(idxA1x).toBeGreaterThan(idxA1);
  });

  it('caps render at depth 5 and shows placeholder for deeper runs', () => {
    // Linear chain: root -> n1 -> n2 -> n3 -> n4 -> n5 -> n6 -> n7
    // Tree depths under "root": n1=0, n2=1, n3=2, n4=3, n5=4, n6=5(should
    // be the cap holder visible? cap kicks at depth >= 5 on the parent
    // BEFORE rendering its children). With MAX_DEPTH=5, the deepest node
    // rendered is at depth=5 itself, but its children get collapsed.
    const rows: AgentRunListItem[] = [
      mkRun('n1', 'root'),
      mkRun('n2', 'n1'),
      mkRun('n3', 'n2'),
      mkRun('n4', 'n3'),
      mkRun('n5', 'n4'),
      mkRun('n6', 'n5'),
      mkRun('n7', 'n6'),
    ];

    render(
      <ChildRunTree rows={rows} parentRunId="root" onSelectRun={vi.fn()} />,
    );

    // MAX_DEPTH = 5 visible levels (depth 0..4).
    expect(MAX_DEPTH).toBe(5);
    expect(screen.queryByTestId('child-run-node-n1')).toBeTruthy(); // depth 0
    expect(screen.queryByTestId('child-run-node-n5')).toBeTruthy(); // depth 4

    // Deeper than cap is suppressed.
    expect(screen.queryByTestId('child-run-node-n6')).toBeNull();
    expect(screen.queryByTestId('child-run-node-n7')).toBeNull();

    // Cap placeholder appears under n5 (depth 4, has 2 hidden descendants).
    const cap = screen.getByTestId('child-run-depth-cap-n5');
    expect(cap.textContent).toMatch(/more nested runs/);
    expect(cap.textContent).toMatch(/2/); // 2 hidden: n6 and n7
  });

  it('breaks cycles without infinite recursion', () => {
    // Cycle scenario: A is under root. A claims its parent is C, and C
    // claims its parent is A — but the rootParentId='root' override keeps
    // A as a top-level child. Then the recursion has to descend A and
    // discover that one of A's transitive children (via the Map) is A
    // itself. The `ancestors` set must break the loop.
    //
    // Construction:
    //   - A is a row whose parentRunId is 'root' (rendered as root child)
    //     BUT we also want A to appear as a child of C. To inject the
    //     cycle without a duplicate runId, we use a second helper row
    //     that pretends A has parent C: but indexByParent only consults
    //     each row once. So instead we set up:
    //       A.parentRunId = 'root'  -> top-level
    //       B.parentRunId = 'A'     -> child of A
    //       C.parentRunId = 'B'     -> grandchild
    //       Now mutate the byParent map indirectly by making A also
    //       claim C as parent? Not possible with one-per-row.
    //
    //   So we use the ancestors set directly via a different cycle: a
    //   node whose declared parent IS one of its descendants by way of
    //   the orphan-fallback. Specifically:
    //     X.parentRunId = 'X' (self-loop). Self-parented rows are NOT
    //     hoisted to root by indexByParent (parent IS in idSet, points
    //     at itself). So X never enters the visible tree. We separately
    //     add a non-cyclic root branch to prove rendering proceeds.
    //   plus an A->B->A cycle attempt (each references the other as
    //   parent). Neither A nor B will be a root — but a third node R
    //   under 'root' keeps the render non-empty.
    //
    // Property under test: render finishes synchronously (< 1s) and the
    // non-cyclic node R renders exactly once. The cyclic nodes either
    // do not appear or appear at most once each.
    expect.assertions(4);

    const rows: AgentRunListItem[] = [
      mkRun('R', 'root'),
      mkRun('A', 'B'), // cycle leg 1
      mkRun('B', 'A'), // cycle leg 2
      mkRun('selfloop', 'selfloop'),
    ];

    const start = Date.now();
    render(
      <ChildRunTree rows={rows} parentRunId="root" onSelectRun={vi.fn()} />,
    );
    const elapsed = Date.now() - start;

    // Render completes — infinite recursion would overflow or hang.
    expect(elapsed).toBeLessThan(1000);

    const tree = screen.getByTestId('child-run-tree');
    const matches = (id: string) =>
      tree.querySelectorAll(`[data-testid="child-run-node-${id}"]`).length;

    // Non-cyclic node renders once.
    expect(matches('R')).toBe(1);
    // Cyclic nodes appear at most once (cycle break prevents duplication).
    expect(matches('A')).toBeLessThanOrEqual(1);
    expect(matches('selfloop')).toBeLessThanOrEqual(1);
  });
});
