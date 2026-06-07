/**
 * TraceGraphCanvas — generic node/edge graph for manufacturing digital-thread traces.
 *
 * Accepts pre-built { nodes, edges } shaped by the block renderer's mapping layer
 * (buildTraceGraph). Wraps @xyflow/react with Controls + Background + fitView.
 *
 * Color-coding by nodeType:
 *   WORK_ORDER → blue
 *   LOT        → green
 *   COMPONENT  → amber
 *   SN         → purple
 */

import { useEffect } from 'react';
import {
  ReactFlow,
  ReactFlowProvider,
  Controls,
  Background,
  Handle,
  Position,
  useNodesInitialized,
  useReactFlow,
  type Node,
  type Edge,
  type NodeTypes,
  type NodeProps,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export interface TraceNode {
  id: string;
  label: string;
  nodeType: 'WORK_ORDER' | 'LOT' | 'COMPONENT' | 'SN' | string;
}

export interface TraceEdge {
  id: string;
  source: string;
  target: string;
  label?: string;
}

export interface TraceGraphCanvasProps {
  nodes: TraceNode[];
  edges: TraceEdge[];
}

// ---------------------------------------------------------------------------
// Color map
// ---------------------------------------------------------------------------

const NODE_TYPE_COLORS: Record<string, { bg: string; border: string; text: string }> = {
  WORK_ORDER: { bg: 'bg-blue-50', border: 'border-blue-500', text: 'text-blue-900' },
  LOT: { bg: 'bg-green-50', border: 'border-green-500', text: 'text-green-900' },
  COMPONENT: { bg: 'bg-amber-50', border: 'border-amber-500', text: 'text-amber-900' },
  SN: { bg: 'bg-purple-50', border: 'border-purple-500', text: 'text-purple-900' },
};

const DEFAULT_NODE_COLOR = { bg: 'bg-gray-50', border: 'border-gray-400', text: 'text-gray-800' };

function getNodeColors(nodeType: string) {
  return NODE_TYPE_COLORS[nodeType.toUpperCase()] ?? DEFAULT_NODE_COLOR;
}

// ---------------------------------------------------------------------------
// Custom node renderer
// ---------------------------------------------------------------------------

interface TraceNodeData extends Record<string, unknown> {
  label: string;
  nodeType: string;
}

function TraceNodeRenderer({ id, data }: NodeProps) {
  const nodeData = data as TraceNodeData;
  const { bg, border, text } = getNodeColors(nodeData.nodeType);

  return (
    <div
      data-testid={`trace-node-${id}`}
      data-node-type={nodeData.nodeType}
      className={`flex min-w-36 max-w-52 flex-col items-start justify-center rounded border-2 px-3 py-2 text-xs ${bg} ${border} ${text}`}
    >
      <Handle type="target" position={Position.Left} className="!h-2 !w-2 !bg-gray-400" />
      <span
        className={`mb-0.5 rounded px-1 py-0 text-[10px] font-semibold uppercase opacity-70 ${bg} ${border} ${text}`}
      >
        {nodeData.nodeType.replace('_', ' ')}
      </span>
      <span className="truncate font-medium leading-tight">{nodeData.label}</span>
      <Handle type="source" position={Position.Right} className="!h-2 !w-2 !bg-gray-400" />
    </div>
  );
}

const nodeTypes: NodeTypes = {
  trace: TraceNodeRenderer,
};

// ---------------------------------------------------------------------------
// Layout builder — simple left→right layered layout by insertion order.
// Nodes are positioned in a single column; future improvement could compute
// proper DAG depths but is unnecessary for small trace graphs (<50 nodes).
// ---------------------------------------------------------------------------

const NODE_WIDTH = 208; // px — matches max-w-52
const NODE_HEIGHT = 56;
const COL_GAP = 180;
const ROW_GAP = 80;

interface LayoutNode extends Node {
  data: TraceNodeData;
}

function buildFlowLayout(
  traceNodes: TraceNode[],
  traceEdges: TraceEdge[],
): { nodes: LayoutNode[]; edges: Edge[] } {
  // Assign a depth (column) to each node via BFS from sources.
  const childrenOf = new Map<string, string[]>();
  const parentCount = new Map<string, number>();

  for (const n of traceNodes) {
    childrenOf.set(n.id, []);
    parentCount.set(n.id, 0);
  }
  for (const e of traceEdges) {
    childrenOf.get(e.source)?.push(e.target);
    parentCount.set(e.target, (parentCount.get(e.target) ?? 0) + 1);
  }

  const depth = new Map<string, number>();
  const queue: string[] = [];

  // Start BFS from nodes with no incoming edges
  for (const n of traceNodes) {
    if ((parentCount.get(n.id) ?? 0) === 0) {
      depth.set(n.id, 0);
      queue.push(n.id);
    }
  }

  while (queue.length > 0) {
    const current = queue.shift()!;
    const currentDepth = depth.get(current) ?? 0;
    for (const child of childrenOf.get(current) ?? []) {
      if (!depth.has(child)) {
        depth.set(child, currentDepth + 1);
        queue.push(child);
      }
    }
  }

  // Fallback: any node not reached (disconnected)
  for (const n of traceNodes) {
    if (!depth.has(n.id)) depth.set(n.id, 0);
  }

  // Count rows per column
  const columnRows = new Map<number, number>();
  const nodePositions: { id: string; col: number; row: number }[] = [];
  for (const n of traceNodes) {
    const col = depth.get(n.id) ?? 0;
    const row = columnRows.get(col) ?? 0;
    columnRows.set(col, row + 1);
    nodePositions.push({ id: n.id, col, row });
  }

  const nodeById = new Map(traceNodes.map((n) => [n.id, n]));

  const flowNodes: LayoutNode[] = nodePositions.map(({ id, col, row }) => {
    const n = nodeById.get(id)!;
    return {
      id,
      type: 'trace',
      position: {
        x: col * (NODE_WIDTH + COL_GAP),
        y: row * (NODE_HEIGHT + ROW_GAP),
      },
      data: { label: n.label, nodeType: n.nodeType } satisfies TraceNodeData,
    };
  });

  const flowEdges: Edge[] = traceEdges.map((e) => ({
    id: e.id,
    source: e.source,
    target: e.target,
    label: e.label,
    style: { stroke: '#94a3b8', strokeWidth: 1.5 },
    labelStyle: { fontSize: 10, fill: '#64748b' },
  }));

  return { nodes: flowNodes, edges: flowEdges };
}

// ---------------------------------------------------------------------------
// Viewport sync (mirrors LineageGraph pattern)
// ---------------------------------------------------------------------------

function ViewportSync({ nodeCount }: { nodeCount: number }) {
  const nodesInitialized = useNodesInitialized();
  const { fitView } = useReactFlow();

  useEffect(() => {
    if (!nodesInitialized || nodeCount === 0) return;
    const frame = window.requestAnimationFrame(() => {
      void fitView({ padding: 0.2, duration: 0 });
    });
    return () => window.cancelAnimationFrame(frame);
  }, [fitView, nodeCount, nodesInitialized]);

  return null;
}

// ---------------------------------------------------------------------------
// Inner graph (needs ReactFlowProvider context)
// ---------------------------------------------------------------------------

function TraceGraphInner({ nodes: traceNodes, edges: traceEdges }: TraceGraphCanvasProps) {
  const { nodes: flowNodes, edges: flowEdges } = buildFlowLayout(traceNodes, traceEdges);

  return (
    <ReactFlow
      nodes={flowNodes}
      edges={flowEdges}
      nodeTypes={nodeTypes}
      nodesDraggable={false}
      nodesConnectable={false}
      elementsSelectable={false}
      panOnDrag={true}
      zoomOnScroll={true}
      minZoom={0.2}
      maxZoom={2}
      fitView
      fitViewOptions={{ padding: 0.2 }}
    >
      <ViewportSync nodeCount={flowNodes.length} />
      <Controls showInteractive={false} />
      <Background />
    </ReactFlow>
  );
}

// ---------------------------------------------------------------------------
// Public component
// ---------------------------------------------------------------------------

/**
 * TraceGraphCanvas renders a manufacturing digital-thread trace as a directed
 * node/edge graph.  ReactFlowProvider is included so the component can be used
 * standalone (no parent provider required).
 */
export function TraceGraphCanvas({ nodes, edges }: TraceGraphCanvasProps) {
  return (
    <div
      data-testid="trace-graph-canvas"
      className="min-h-[420px] w-full overflow-hidden rounded border border-gray-200 bg-white"
    >
      <ReactFlowProvider>
        <TraceGraphInner nodes={nodes} edges={edges} />
      </ReactFlowProvider>
    </div>
  );
}

export default TraceGraphCanvas;
