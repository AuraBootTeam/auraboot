/**
 * Unit tests for buildTraceGraph (pure mapping fn) and a render smoke test for
 * TraceGraphBlockRenderer.
 *
 * Test plan:
 *   buildTraceGraph
 *     happy-consumption: rows → deduped WORK_ORDER + LOT nodes + edges
 *     happy-genealogy:   rows → SN + COMPONENT nodes + edges
 *     edge-empty:        empty rows → empty graph (no crash)
 *     corner-dedup:      multiple rows sharing work_order → node deduped, multiple edges
 *     corner-null-ids:   rows with null ids → skipped
 *     corner-infer-mode: mode inferred from row shape when omitted
 *
 *   TraceGraphBlockRenderer (render smoke)
 *     smoke-loading:  renders loading state
 *     smoke-empty:    renders empty state (no rows)
 *     smoke-rows:     renders canvas section when rows present
 *     smoke-error:    renders error alert
 */

import React from 'react';
import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import type { BlockConfig } from '~/framework/meta/schemas/types';
import { buildTraceGraph } from '../TraceGraphBlockRenderer';
import { TraceGraphBlockRenderer } from '../TraceGraphBlockRenderer';

// ---------------------------------------------------------------------------
// @xyflow/react mock — jsdom has no layout engine; mock the provider + ReactFlow
// so render smoke tests work without geometry errors.
// ---------------------------------------------------------------------------
vi.mock('@xyflow/react', () => ({
  ReactFlowProvider: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  ReactFlow: ({ nodes, edges }: { nodes: any[]; edges: any[] }) => (
    <div data-testid="mock-react-flow" data-node-count={nodes.length} data-edge-count={edges.length} />
  ),
  Controls: () => null,
  Background: () => null,
  Handle: () => null,
  Position: { Left: 'left', Right: 'right' },
  useNodesInitialized: () => false,
  useReactFlow: () => ({ fitView: vi.fn() }),
}));

// ---------------------------------------------------------------------------
// Helper: minimal SchemaRuntime stub
// ---------------------------------------------------------------------------

function makeRuntime(overrides: {
  rows?: unknown[];
  loading?: boolean;
  error?: Error | null;
} = {}): SchemaRuntime {
  const { rows = [], loading = false, error = null } = overrides;
  const data: Record<string, unknown> = rows.length > 0 ? { traceDs: rows } : {};

  const stub = {
    getContext: () => ({
      locale: 'en-US',
      t: (k: string) => k,
      state: {},
      form: {},
      global: {},
    }),
    getDataSourceManager: () => ({
      getData: (id: string) => data[id] ?? null,
      getState: (_id: string) => ({
        data: data[_id] ?? null,
        loading,
        error,
      }),
      subscribe: vi.fn(() => () => {}),
      reload: vi.fn(),
    }),
    getStateManager: () => ({
      getStore: () => ({ subscribe: vi.fn(() => () => {}) }),
      updateState: vi.fn(),
    }),
    getScopeId: () => 'scope-1',
    getEvaluator: () => ({
      evaluateCondition: () => true,
      evaluateTemplate: (t: string) => t,
      evaluateObject: (o: unknown) => o,
    }),
    getSchema: () => ({ id: 'test_schema', modelCode: 'test_model' }),
  };
  return stub as unknown as SchemaRuntime;
}

const baseBlock: BlockConfig = {
  id: 'trace1',
  blockType: 'trace-graph',
  dataSource: 'traceDs',
} as any;

// ---------------------------------------------------------------------------
// buildTraceGraph — pure function tests
// ---------------------------------------------------------------------------

describe('buildTraceGraph — consumption mode', () => {
  it('happy: maps rows to correct WORK_ORDER + LOT nodes and edges', () => {
    const rows = [
      {
        work_order_id: 'WO-1',
        work_order_code: 'WO-2024-001',
        lot_id: 'LOT-A',
        lot_code: 'LOT-A-CODE',
        qty_consumed: 50,
      },
    ];

    const { nodes, edges } = buildTraceGraph(rows, 'consumption');

    expect(nodes).toHaveLength(2);
    const woNode = nodes.find((n) => n.nodeType === 'WORK_ORDER');
    expect(woNode).toBeDefined();
    expect(woNode?.id).toBe('WO-1');
    expect(woNode?.label).toBe('WO-2024-001');

    const lotNode = nodes.find((n) => n.nodeType === 'LOT');
    expect(lotNode).toBeDefined();
    expect(lotNode?.id).toBe('LOT-A');
    expect(lotNode?.label).toBe('LOT-A-CODE');

    expect(edges).toHaveLength(1);
    expect(edges[0].source).toBe('WO-1');
    expect(edges[0].target).toBe('LOT-A');
    expect(edges[0].label).toContain('50');
  });

  it('happy: works without optional qty_consumed (edge label still set)', () => {
    const rows = [
      { work_order_id: 'WO-2', work_order_code: 'WO-2', lot_id: 'LOT-B', lot_code: 'LOT-B' },
    ];

    const { edges } = buildTraceGraph(rows, 'consumption');

    expect(edges).toHaveLength(1);
    expect(edges[0].label).toBe('consumes');
  });
});

describe('buildTraceGraph — genealogy mode', () => {
  it('happy: maps rows to SN + COMPONENT nodes and contains edges', () => {
    const rows = [
      {
        finished_sn: 'FIN-SN-001',
        component_sn: 'COMP-SN-A',
        component_material_name: 'Capacitor 100uF',
      },
      {
        finished_sn: 'FIN-SN-001',
        component_sn: 'COMP-SN-B',
        component_material_name: 'Resistor 10K',
      },
    ];

    const { nodes, edges } = buildTraceGraph(rows, 'genealogy');

    expect(nodes).toHaveLength(3); // 1 finished + 2 components
    const finNode = nodes.find((n) => n.id === 'FIN-SN-001');
    expect(finNode?.nodeType).toBe('SN');

    const compA = nodes.find((n) => n.id === 'COMP-SN-A');
    expect(compA?.nodeType).toBe('COMPONENT');
    expect(compA?.label).toContain('Capacitor');

    expect(edges).toHaveLength(2);
    edges.forEach((e) => {
      expect(e.source).toBe('FIN-SN-001');
      expect(e.label).toBe('contains');
    });
  });
});

describe('buildTraceGraph — edge and corner cases', () => {
  it('edge: empty rows → empty graph, no crash', () => {
    const { nodes, edges } = buildTraceGraph([], 'consumption');
    expect(nodes).toHaveLength(0);
    expect(edges).toHaveLength(0);
  });

  it('edge: empty rows genealogy → empty graph, no crash', () => {
    const { nodes, edges } = buildTraceGraph([], 'genealogy');
    expect(nodes).toHaveLength(0);
    expect(edges).toHaveLength(0);
  });

  it('corner: multiple rows sharing same work_order → node deduped, multiple edges', () => {
    const rows = [
      { work_order_id: 'WO-1', work_order_code: 'WO-X', lot_id: 'LOT-A', lot_code: 'LA', qty_consumed: 10 },
      { work_order_id: 'WO-1', work_order_code: 'WO-X', lot_id: 'LOT-B', lot_code: 'LB', qty_consumed: 20 },
      { work_order_id: 'WO-1', work_order_code: 'WO-X', lot_id: 'LOT-A', lot_code: 'LA', qty_consumed: 5 },
    ];

    const { nodes, edges } = buildTraceGraph(rows, 'consumption');

    // WO-1 deduped → 1; LOT-A deduped → 1; LOT-B → 1 = 3 nodes
    expect(nodes).toHaveLength(3);
    const woNodes = nodes.filter((n) => n.nodeType === 'WORK_ORDER');
    expect(woNodes).toHaveLength(1);

    // Edges: WO-1→LOT-A (row 0), WO-1→LOT-B (row 1), WO-1→LOT-A again (row 2)
    // Note: edges are NOT deduped (each row generates its own edge — callers can dedup if needed).
    expect(edges).toHaveLength(3);
    const edgeTargets = edges.map((e) => e.target);
    expect(edgeTargets.filter((t) => t === 'LOT-A')).toHaveLength(2);
    expect(edgeTargets.filter((t) => t === 'LOT-B')).toHaveLength(1);
  });

  it('corner: rows with null work_order_id → skipped', () => {
    const rows = [
      { work_order_id: null, lot_id: 'LOT-A', lot_code: 'LA' },
      { work_order_id: 'WO-1', lot_id: null, lot_code: 'LX' },
      { work_order_id: 'WO-2', work_order_code: 'WO-2', lot_id: 'LOT-B', lot_code: 'LB' },
    ];

    const { nodes, edges } = buildTraceGraph(rows, 'consumption');

    expect(edges).toHaveLength(1); // only row 3 valid
    expect(nodes.some((n) => n.id === 'LOT-A')).toBe(false); // skipped
    expect(nodes.some((n) => n.id === 'WO-2')).toBe(true);
  });

  it('corner: rows with null finished_sn or component_sn → skipped in genealogy', () => {
    const rows = [
      { finished_sn: null, component_sn: 'COMP-1' },
      { finished_sn: 'FIN-1', component_sn: null },
      { finished_sn: 'FIN-2', component_sn: 'COMP-2' },
    ];

    const { nodes, edges } = buildTraceGraph(rows, 'genealogy');

    expect(edges).toHaveLength(1);
    expect(nodes.some((n) => n.id === 'FIN-2')).toBe(true);
    expect(nodes.some((n) => n.id === 'COMP-2')).toBe(true);
    // Null-id nodes must not appear
    expect(nodes.every((n) => n.id !== null && n.id !== 'null')).toBe(true);
  });

  it('corner: mode inferred from genealogy row shape when mode omitted', () => {
    const rows = [{ finished_sn: 'FIN-1', component_sn: 'COMP-1' }];
    const { nodes } = buildTraceGraph(rows); // no mode arg
    expect(nodes.some((n) => n.nodeType === 'SN')).toBe(true);
    expect(nodes.some((n) => n.nodeType === 'COMPONENT')).toBe(true);
  });

  it('corner: mode inferred as consumption when no genealogy fields present', () => {
    const rows = [
      { work_order_id: 'WO-1', work_order_code: 'WO-X', lot_id: 'LOT-A', lot_code: 'LA' },
    ];
    const { nodes } = buildTraceGraph(rows); // no mode arg
    expect(nodes.some((n) => n.nodeType === 'WORK_ORDER')).toBe(true);
  });
});

// ---------------------------------------------------------------------------
// TraceGraphBlockRenderer — render smoke tests
// ---------------------------------------------------------------------------

describe('TraceGraphBlockRenderer — render smoke', () => {
  it('smoke-loading: renders loading state when datasource is loading', () => {
    const runtime = makeRuntime({ loading: true });
    render(<TraceGraphBlockRenderer block={baseBlock} runtime={runtime} />);
    expect(screen.getByTestId('trace-graph-loading')).toBeInTheDocument();
  });

  it('smoke-empty: renders empty state when no rows', () => {
    const runtime = makeRuntime({ rows: [] });
    render(<TraceGraphBlockRenderer block={baseBlock} runtime={runtime} />);
    expect(screen.getByTestId('trace-graph-empty')).toBeInTheDocument();
  });

  it('smoke-rows: renders canvas section when rows are present', () => {
    const runtime = makeRuntime({
      rows: [
        { work_order_id: 'WO-1', work_order_code: 'WO-X', lot_id: 'LOT-A', lot_code: 'LA', qty_consumed: 10 },
      ],
    });
    const block = { ...baseBlock, mode: 'consumption' } as any;
    render(<TraceGraphBlockRenderer block={block} runtime={runtime} />);
    expect(screen.getByTestId('trace-graph-block-trace1')).toBeInTheDocument();
    expect(screen.getByTestId('trace-graph-canvas')).toBeInTheDocument();
    // Mock ReactFlow should appear with correct node count
    const flowEl = screen.getByTestId('mock-react-flow');
    expect(Number(flowEl.getAttribute('data-node-count'))).toBe(2);
    expect(Number(flowEl.getAttribute('data-edge-count'))).toBe(1);
  });

  it('smoke-error: renders error alert from datasource', () => {
    const runtime = makeRuntime({ error: new Error('DB connection failed') });
    render(<TraceGraphBlockRenderer block={baseBlock} runtime={runtime} />);
    expect(screen.getByTestId('trace-graph-error')).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('DB connection failed');
  });
});
