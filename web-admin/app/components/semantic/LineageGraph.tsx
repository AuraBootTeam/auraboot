/**
 * LineageGraph — data-lineage visualisation component (IDA TRUST layer, PRD 16 §12).
 *
 * Renders the incoming/outgoing edge graph for a single semantic node using
 * @xyflow/react.  Layout is a simple left-to-right (LR) grid: incoming nodes on
 * the left, the focal node in the centre, outgoing nodes on the right.
 *
 * Intentionally avoids a full dagre dependency: the lineage graph is small
 * (typically <20 nodes) and a fixed-grid placement is sufficient for readability
 * at this stage.  A proper dagre layout can be wired in later by replacing
 * {@link buildLayout} without touching the rendering logic.
 *
 * Colour-coding follows the design spec:
 *   MODEL      → blue
 *   METRIC     → green
 *   DIMENSION  → orange
 *   EXPOSURE   → purple
 */

import { useEffect, useRef, useState } from 'react';
import {
  ReactFlow,
  Controls,
  Background,
  useNodesInitialized,
  useReactFlow,
  type Node,
  type Edge,
  type NodeTypes,
  Handle,
  Position,
  type NodeProps,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';

import {
  fetchLineage,
  type LineageResponse,
  type LineageEdge,
} from '~/plugins/core-semantic/api/semanticApi';

// ---------------------------------------------------------------------------
// Node-type colour map
// ---------------------------------------------------------------------------

type SemanticNodeType = 'MODEL' | 'METRIC' | 'DIMENSION' | 'EXPOSURE';

const TYPE_COLORS: Record<string, { bg: string; border: string; text: string }> = {
  MODEL: { bg: 'bg-blue-50', border: 'border-blue-500', text: 'text-blue-900' },
  METRIC: { bg: 'bg-green-50', border: 'border-green-500', text: 'text-green-900' },
  DIMENSION: { bg: 'bg-orange-50', border: 'border-orange-500', text: 'text-orange-900' },
  EXPOSURE: { bg: 'bg-purple-50', border: 'border-purple-500', text: 'text-purple-900' },
};

const DEFAULT_COLOR = { bg: 'bg-gray-50', border: 'border-gray-400', text: 'text-gray-800' };

function getTypeColors(nodeType: string) {
  return TYPE_COLORS[nodeType.toUpperCase()] ?? DEFAULT_COLOR;
}

// ---------------------------------------------------------------------------
// Custom node renderer
// ---------------------------------------------------------------------------

interface LineageNodeData extends Record<string, unknown> {
  label: string;
  nodeType: string;
  isFocal?: boolean;
}

function LineageNodeRenderer({ id, data }: NodeProps) {
  const nodeData = data as LineageNodeData;
  const { bg, border, text } = getTypeColors(nodeData.nodeType);
  const focalRing = nodeData.isFocal ? 'ring-2 ring-offset-1 ring-indigo-400' : '';

  return (
    <div
      data-testid={`lineage-node-${id}`}
      data-node-type={nodeData.nodeType}
      className={`flex min-w-32 max-w-48 flex-col items-start justify-center rounded border-2 px-3 py-2 text-xs ${bg} ${border} ${text} ${focalRing}`}
    >
      <Handle type="target" position={Position.Left} className="!h-2 !w-2 !bg-gray-400" />
      <span
        className={`mb-0.5 rounded px-1 py-0 text-[10px] font-semibold uppercase ${bg} ${border} ${text} opacity-70`}
      >
        {nodeData.nodeType}
      </span>
      <span className="truncate font-medium leading-tight">{nodeData.label}</span>
      <Handle type="source" position={Position.Right} className="!h-2 !w-2 !bg-gray-400" />
    </div>
  );
}

const nodeTypes: NodeTypes = {
  lineage: LineageNodeRenderer,
};

// ---------------------------------------------------------------------------
// Layout builder (simple LR grid, no dagre dep)
// ---------------------------------------------------------------------------

const NODE_WIDTH = 192; // px — matches max-w-48
const NODE_HEIGHT = 60;
const COL_GAP = 200;
const ROW_GAP = 80;

function buildLayout(
  response: LineageResponse,
): { nodes: Node[]; edges: Edge[] } {
  const { nodePid, nodeType, incoming, outgoing } = response;

  // Collect unique peer nodes
  const incomingPeers = deduplicatePeers(
    incoming.map((e) => ({ pid: e.srcPid, type: e.srcType })),
  );
  const outgoingPeers = deduplicatePeers(
    outgoing.map((e) => ({ pid: e.dstPid, type: e.dstType })),
  );

  const totalRows = Math.max(incomingPeers.length, outgoingPeers.length, 1);
  const centerY = ((totalRows - 1) * ROW_GAP) / 2;

  const nodes: Node[] = [];
  const edges: Edge[] = [];

  // Focal node — centre column
  const focalX = incomingPeers.length > 0 ? COL_GAP + NODE_WIDTH / 2 : 0;
  nodes.push({
    id: nodePid,
    type: 'lineage',
    position: { x: focalX, y: centerY - NODE_HEIGHT / 2 },
    data: { label: shortLabel(nodePid), nodeType, isFocal: true } satisfies LineageNodeData,
  });

  // Incoming nodes — left column
  incomingPeers.forEach(({ pid, type }, i) => {
    const y = i * ROW_GAP - NODE_HEIGHT / 2;
    nodes.push({
      id: pid,
      type: 'lineage',
      position: { x: 0, y },
      data: { label: shortLabel(pid), nodeType: type } satisfies LineageNodeData,
    });
  });

  // Outgoing nodes — right column
  outgoingPeers.forEach(({ pid, type }, i) => {
    const y = i * ROW_GAP - NODE_HEIGHT / 2;
    nodes.push({
      id: pid,
      type: 'lineage',
      position: { x: focalX + NODE_WIDTH + COL_GAP, y },
      data: { label: shortLabel(pid), nodeType: type } satisfies LineageNodeData,
    });
  });

  // Edges
  let edgeIdx = 0;
  incoming.forEach((e: LineageEdge) => {
    edges.push({
      id: `e-in-${edgeIdx++}`,
      source: e.srcPid,
      target: e.dstPid,
      label: e.refType,
      style: { stroke: '#94a3b8', strokeWidth: 1.5 },
      labelStyle: { fontSize: 10, fill: '#64748b' },
    });
  });
  outgoing.forEach((e: LineageEdge) => {
    edges.push({
      id: `e-out-${edgeIdx++}`,
      source: e.srcPid,
      target: e.dstPid,
      label: e.refType,
      style: { stroke: '#94a3b8', strokeWidth: 1.5 },
      labelStyle: { fontSize: 10, fill: '#64748b' },
    });
  });

  return { nodes, edges };
}

function deduplicatePeers(peers: { pid: string; type: string }[]) {
  const seen = new Set<string>();
  return peers.filter(({ pid }) => {
    if (seen.has(pid)) return false;
    seen.add(pid);
    return true;
  });
}

/** Extract a readable short label from a PID or code string. */
function shortLabel(pid: string): string {
  // PIDs are often ULIDs or UUIDs — show last 8 chars as fallback
  if (pid.length > 20) return `…${pid.slice(-8)}`;
  return pid;
}

// ---------------------------------------------------------------------------
// Viewport sync helper (mirrors BpmDiagramSection pattern)
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
// Public component
// ---------------------------------------------------------------------------

export interface LineageGraphProps {
  nodePid: string;
  nodeType: string;
  /** Injected translator (same pattern as BpmDiagramSection). */
  t: (key: string, params?: Record<string, unknown>, fallback?: string) => string;
}

export function LineageGraph({ nodePid, nodeType, t }: LineageGraphProps) {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [layout, setLayout] = useState<{ nodes: Node[]; edges: Edge[] } | null>(null);
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!nodePid) {
      setLayout(null);
      setError(null);
      setLoading(false);
      return;
    }

    let cancelled = false;
    setLoading(true);
    setError(null);
    setLayout(null);

    fetchLineage(nodePid)
      .then((response) => {
        if (cancelled) return;
        setLayout(buildLayout(response));
        setLoading(false);
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setError(err instanceof Error ? err.message : String(err));
        setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [nodePid]);

  if (!nodePid) {
    return (
      <div
        data-testid="lineage-graph-empty"
        className="flex h-full flex-col items-center justify-center text-sm text-gray-400"
      >
        <span className="mb-2 text-3xl">⬡</span>
        <span>{t('semantic.lineage.empty', undefined, 'Select a node to view its lineage')}</span>
      </div>
    );
  }

  if (loading) {
    return (
      <div
        data-testid="lineage-graph-loading"
        className="flex h-full items-center justify-center text-sm text-gray-500"
      >
        {t('semantic.lineage.loading', undefined, 'Loading lineage…')}
      </div>
    );
  }

  if (error) {
    return (
      <div
        data-testid="lineage-graph-error"
        className="flex h-full items-center justify-center rounded border border-red-200 bg-red-50 p-4 text-sm text-red-700"
      >
        {t('semantic.lineage.error', undefined, 'Failed to load lineage')}: {error}
      </div>
    );
  }

  if (!layout) return null;

  return (
    <div
      ref={containerRef}
      data-testid="lineage-graph-container"
      data-node-pid={nodePid}
      data-node-type={nodeType}
      className="h-full w-full overflow-hidden rounded border border-gray-200 bg-white"
    >
      <ReactFlow
        nodes={layout.nodes}
        edges={layout.edges}
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
        <ViewportSync nodeCount={layout.nodes.length} />
        <Controls showInteractive={false} />
        <Background />
      </ReactFlow>
    </div>
  );
}
