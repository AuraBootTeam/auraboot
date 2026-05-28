// web-admin/app/plugins/core-designer/components/flow-designer-sdk/runtime/NodeRuntimeStatusContext.tsx
//
// G5 — runtime status overlay.
//
// A small React Context that lets `FlowDesigner` push a `Record<nodeId, NodeRuntimeStatus>`
// down to `DefaultFlowNode` without changing the (currently store-driven) data flow for
// design-time edits.
//
// Design intent (DDR-2026-05-23 / T3 plan §G5):
//   • Pure render-time signal — does NOT participate in undo/redo, dirty tracking,
//     or any persistence path. A runtime overlay is intentionally distinct from the
//     authoring schema.
//   • Prop is OPTIONAL on `FlowDesigner` — when absent the context value is `null`
//     and the node renderer behaves exactly as before (no badge, no highlight).
//     This is the back-compat contract for every existing call site.
//   • Vocabulary mirrors `StatusConstants` on the backend, so the API contract is
//     1:1 with the `ab_automation_node_execution.status` column.

import React, { createContext, useContext, useMemo } from 'react';

export type NodeRuntimeStatus =
  | 'pending'
  | 'running'
  | 'completed'
  | 'failed'
  | 'skipped';

export type NodeStatusMap = Record<string, NodeRuntimeStatus | undefined>;

const NodeRuntimeStatusContext = createContext<NodeStatusMap | null>(null);

export interface NodeRuntimeStatusProviderProps {
  /** When undefined / null, the context value is null (overlay disabled). */
  statuses?: NodeStatusMap | null;
  children: React.ReactNode;
}

export function NodeRuntimeStatusProvider({
  statuses,
  children,
}: NodeRuntimeStatusProviderProps) {
  // Always provide the context but with a stable identity per `statuses` ref —
  // avoids re-rendering the whole canvas when an unrelated parent re-renders.
  const value = useMemo<NodeStatusMap | null>(
    () => (statuses && Object.keys(statuses).length > 0 ? statuses : null),
    [statuses],
  );
  return (
    <NodeRuntimeStatusContext.Provider value={value}>
      {children}
    </NodeRuntimeStatusContext.Provider>
  );
}

/** Returns the status for a single node id, or undefined when no overlay is active. */
export function useNodeRuntimeStatus(nodeId: string | undefined): NodeRuntimeStatus | undefined {
  const map = useContext(NodeRuntimeStatusContext);
  if (!map || !nodeId) return undefined;
  return map[nodeId];
}

/** Returns the whole map (or null when no overlay is active). Mainly for tests. */
export function useNodeRuntimeStatusMap(): NodeStatusMap | null {
  return useContext(NodeRuntimeStatusContext);
}
