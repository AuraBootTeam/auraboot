// web-admin/app/flow-designer-sdk/hooks/useNodeNeighbors.ts
/**
 * G7 — useNodeNeighbors
 *
 * Returns the structural neighbors of a node (incoming/outgoing edges plus
 * the resolved source/target nodes). Primary consumer: PropertyEditor panels
 * that need to enumerate sibling edges — e.g. BPMN ExclusiveGatewayEditor
 * choosing a "default flow" from the gateway's outgoing edges.
 *
 * Reactive: re-renders whenever the underlying store mutates nodes or edges
 * (zustand selectors return new array references each time, so consumers
 * should memo downstream derivations if hot).
 */
import { useMemo } from 'react';
import { useFlowStore } from '../store/useFlowStore';
import type { FlowNode, FlowEdge } from '../store/types';

export interface NodeNeighbors {
  /** Edges where target === nodeId. */
  incoming: FlowEdge[];
  /** Edges where source === nodeId. */
  outgoing: FlowEdge[];
  /** Nodes that are the `source` of an incoming edge. May contain duplicates if multiple edges connect the same source. */
  sourceNodes: FlowNode[];
  /** Nodes that are the `target` of an outgoing edge. May contain duplicates if multiple edges connect the same target. */
  targetNodes: FlowNode[];
}

const EMPTY: NodeNeighbors = Object.freeze({
  incoming: [],
  outgoing: [],
  sourceNodes: [],
  targetNodes: [],
});

export function useNodeNeighbors(nodeId: string | null | undefined): NodeNeighbors {
  const nodes = useFlowStore((s) => s.nodes);
  const edges = useFlowStore((s) => s.edges);

  return useMemo<NodeNeighbors>(() => {
    if (!nodeId) return EMPTY;
    const incoming: FlowEdge[] = [];
    const outgoing: FlowEdge[] = [];
    for (const e of edges) {
      if (e.target === nodeId) incoming.push(e);
      if (e.source === nodeId) outgoing.push(e);
    }
    const nodesById = new Map(nodes.map((n) => [n.id, n] as const));
    const sourceNodes = incoming
      .map((e) => nodesById.get(e.source))
      .filter((n): n is FlowNode => Boolean(n));
    const targetNodes = outgoing
      .map((e) => nodesById.get(e.target))
      .filter((n): n is FlowNode => Boolean(n));
    return { incoming, outgoing, sourceNodes, targetNodes };
  }, [nodeId, nodes, edges]);
}
