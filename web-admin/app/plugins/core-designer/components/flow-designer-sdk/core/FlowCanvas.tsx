// web-admin/app/flow-designer-sdk/core/FlowCanvas.tsx
import React, { useCallback, useMemo } from 'react';
import {
  ReactFlow,
  Background,
  Controls,
  MiniMap,
  type Connection,
  type NodeTypes,
  type Node,
  type Edge,
  type NodeChange,
  type EdgeChange,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { useFlowStore } from '../store/useFlowStore';
import { nodeRegistry } from '../nodes/NodeRegistry';
import { DefaultFlowNode } from './DefaultFlowNode';

export interface FlowCanvasProps {
  readOnly?: boolean;
  showMinimap?: boolean;
  showControls?: boolean;
  className?: string;
}

export function FlowCanvas({
  readOnly = false,
  showMinimap = true,
  showControls = true,
  className,
}: FlowCanvasProps) {
  const {
    nodes,
    edges,
    selectedNodeId,
    registryVersion,
    updateNode,
    addNode,
    addEdge: storeAddEdge,
    selectNode,
    deleteNode,
    deleteEdge,
  } = useFlowStore();

  // Build node types from registry.
  // Depends on registryVersion so it recomputes after nodeRegistry.registerAll().
  const nodeTypes: NodeTypes = useMemo(() => {
    const types: NodeTypes = {};
    nodeRegistry.getAll().forEach((def) => {
      types[def.type] = def.component || DefaultFlowNode;
    });
    // Always include default type
    if (!types['default']) {
      types['default'] = DefaultFlowNode;
    }
    return types;
  }, [registryVersion]);

  // Convert store nodes to ReactFlow nodes
  const rfNodes: Node[] = useMemo(
    () =>
      nodes.map((n) => ({
        ...n,
        selected: n.id === selectedNodeId,
      })),
    [nodes, selectedNodeId],
  );

  const rfEdges: Edge[] = useMemo(() => edges, [edges]);

  const onNodesChange = useCallback(
    (changes: NodeChange[]) => {
      changes.forEach((change) => {
        if (change.type === 'position' && change.position && change.id) {
          updateNode(change.id, { position: change.position });
        }
        if (change.type === 'remove' && change.id) {
          deleteNode(change.id);
        }
      });
    },
    [updateNode, deleteNode],
  );

  const onEdgesChange = useCallback(
    (changes: EdgeChange[]) => {
      changes.forEach((change) => {
        if (change.type === 'remove' && change.id) {
          deleteEdge(change.id);
        }
      });
    },
    [deleteEdge],
  );

  const onConnect = useCallback(
    (connection: Connection) => {
      if (connection.source && connection.target) {
        storeAddEdge({
          source: connection.source,
          target: connection.target,
          sourceHandle: connection.sourceHandle || undefined,
          targetHandle: connection.targetHandle || undefined,
        });
      }
    },
    [storeAddEdge],
  );

  const onNodeClick = useCallback(
    (_: React.MouseEvent, node: Node) => {
      selectNode(node.id);
    },
    [selectNode],
  );

  const onPaneClick = useCallback(() => {
    selectNode(null);
  }, [selectNode]);

  const onDrop = useCallback(
    (event: React.DragEvent) => {
      event.preventDefault();

      const type = event.dataTransfer.getData('application/flow-node');
      if (!type) return;

      const definition = nodeRegistry.get(type);
      if (!definition) return;

      // Get canvas bounds
      const target = event.currentTarget as HTMLElement;
      const bounds = target.getBoundingClientRect();

      const position = {
        x: event.clientX - bounds.left - 75,
        y: event.clientY - bounds.top - 30,
      };

      addNode({
        type,
        position,
        data: {
          label: typeof definition.label === 'string' ? definition.label : type,
          config: definition.defaultConfig || {},
        },
      });
    },
    [addNode],
  );

  const onDragOver = useCallback((event: React.DragEvent) => {
    event.preventDefault();
    event.dataTransfer.dropEffect = 'move';
  }, []);

  return (
    <div className={`h-full flex-1 ${className || ''}`}>
      <ReactFlow
        nodes={rfNodes}
        edges={rfEdges}
        nodeTypes={nodeTypes}
        onNodesChange={onNodesChange}
        onEdgesChange={onEdgesChange}
        onConnect={onConnect}
        onNodeClick={onNodeClick}
        onPaneClick={onPaneClick}
        onDrop={onDrop}
        onDragOver={onDragOver}
        nodesDraggable={!readOnly}
        nodesConnectable={!readOnly}
        elementsSelectable={!readOnly}
        fitView
      >
        <Background />
        {showControls && <Controls />}
        {showMinimap && <MiniMap />}
      </ReactFlow>
    </div>
  );
}

export default FlowCanvas;
