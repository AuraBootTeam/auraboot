// web-admin/app/flow-designer-sdk/core/FlowCanvas.tsx
import React, { useCallback, useMemo, useRef } from 'react';
import {
  ReactFlow,
  Background,
  Controls,
  MiniMap,
  useReactFlow,
  type Connection,
  type NodeTypes,
  type EdgeTypes,
  type Node,
  type Edge,
  type NodeChange,
  type EdgeChange,
  type XYPosition,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { useFlowStore } from '../store/useFlowStore';
import { nodeRegistry } from '../nodes/NodeRegistry';
import { edgeRegistry } from '../edges/EdgeRegistry';
import { DefaultFlowNode } from './DefaultFlowNode';

export interface FlowCanvasProps {
  readOnly?: boolean;
  showMinimap?: boolean;
  showControls?: boolean;
  className?: string;
}

/**
 * Invisible child component rendered INSIDE <ReactFlow> so that
 * `useReactFlow()` resolves against the correct @xyflow context.
 * Captures `screenToFlowPosition` into a ref shared with the parent.
 *
 * Why a child component?
 * `useReactFlow()` must be called inside the ReactFlow provider tree.
 * `FlowCanvas` renders <ReactFlow> which IS the provider, so `useReactFlow()`
 * cannot be called directly in `FlowCanvas`. Rendering this null component as
 * a child of <ReactFlow> gives it access to the correct store context.
 */
function ScreenToFlowPositionCapture({
  onCapture,
}: {
  onCapture: (fn: (pos: XYPosition) => XYPosition) => void;
}) {
  const { screenToFlowPosition } = useReactFlow();
  // Sync the function into the parent ref on every render so it stays fresh
  // (the store's transform updates on zoom/pan, and screenToFlowPosition
  // reads from the store at call time — so keeping the ref current ensures
  // onDrop always gets the latest conversion function).
  // Writing to a ref during render is intentional: no state mutation, no
  // re-render triggered.
  onCapture(screenToFlowPosition);
  return null;
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
    selectedEdgeId,
    registryVersion,
    updateNode,
    addNode,
    addEdge: storeAddEdge,
    selectNode,
    selectEdge,
    deleteNode,
    deleteEdge,
  } = useFlowStore();

  // Capture whether this canvas mounted with pre-existing nodes (edit mode).
  // fitView should only activate when loading pre-existing graph data so the
  // user sees the whole automation on first open.
  //
  // On empty canvases (new automation), fitView=true causes @xyflow to
  // re-run the fit animation every time a node is added via drag-drop, which
  // zooms to fill the viewport and pushes the freshly-dropped node below the
  // visible viewport area. Disabling fitView on empty canvases keeps the 1:1
  // zoom and places dropped nodes exactly where the user drops them.
  const hadInitialNodesRef = useRef<boolean | null>(null);
  if (hadInitialNodesRef.current === null) {
    // Read once at mount; never updated so it doesn't change after the first
    // node drop.
    hadInitialNodesRef.current = nodes.length > 0;
  }
  const fitViewActive = hadInitialNodesRef.current;

  // Ref to hold the screenToFlowPosition function captured from inside the
  // ReactFlow context by ScreenToFlowPositionCapture below.
  const screenToFlowPositionRef = useRef<((pos: XYPosition) => XYPosition) | null>(null);

  const handleCapture = useCallback((fn: (pos: XYPosition) => XYPosition) => {
    screenToFlowPositionRef.current = fn;
  }, []);

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

  // Build edge types from registry (domains register custom edge components,
  // e.g. a conditional edge). Default @xyflow edge is used when unregistered.
  const edgeTypes: EdgeTypes = useMemo(() => {
    const types: EdgeTypes = {};
    edgeRegistry.getAll().forEach((def) => {
      if (def.component) {
        types[def.type] = def.component;
      }
    });
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

  const rfEdges: Edge[] = useMemo(
    () =>
      edges.map((e) => ({
        ...e,
        selected: e.id === selectedEdgeId,
        selectable: !readOnly,
        interactionWidth: 24,
      })),
    [edges, selectedEdgeId, readOnly],
  );

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

  const onEdgeClick = useCallback(
    (_: React.MouseEvent, edge: Edge) => {
      selectEdge(edge.id);
    },
    [selectEdge],
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

      // Convert viewport (screen) coordinates to flow-graph coordinates.
      //
      // screenToFlowPositionRef is populated by ScreenToFlowPositionCapture,
      // a null child rendered inside <ReactFlow> that has access to the xyflow
      // store. The function reads the current viewport transform (zoom + pan)
      // at call time, so it always reflects the latest state even after the
      // user has panned/zoomed.
      //
      // Fallback: derive the inverse transform from the viewport element's
      // computed CSS matrix (used if the ref isn't populated yet, e.g. if the
      // drop fires before ScreenToFlowPositionCapture has rendered once).
      let position: XYPosition;
      if (screenToFlowPositionRef.current) {
        position = screenToFlowPositionRef.current({
          x: event.clientX,
          y: event.clientY,
        });
      } else {
        const rfEl = event.currentTarget as HTMLElement;
        const rfBounds = rfEl.getBoundingClientRect();
        const vpEl = rfEl.querySelector('.react-flow__viewport') as HTMLElement | null;
        const relX = event.clientX - rfBounds.x;
        const relY = event.clientY - rfBounds.y;
        if (vpEl) {
          const mat = window.getComputedStyle(vpEl).transform;
          const m = mat.match(/matrix\(([^)]+)\)/);
          if (m) {
            const [scale, , , , tx, ty] = m[1].split(',').map(Number);
            position = {
              x: (relX - tx) / scale,
              y: (relY - ty) / scale,
            };
          } else {
            position = { x: relX, y: relY };
          }
        } else {
          position = { x: relX, y: relY };
        }
      }

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
    <div className={`min-h-0 flex-1 ${className || ''}`}>
      <ReactFlow
        nodes={rfNodes}
        edges={rfEdges}
        nodeTypes={nodeTypes}
        edgeTypes={edgeTypes}
        onNodesChange={onNodesChange}
        onEdgesChange={onEdgesChange}
        onConnect={onConnect}
        onNodeClick={onNodeClick}
        onEdgeClick={onEdgeClick}
        onPaneClick={onPaneClick}
        onDrop={onDrop}
        onDragOver={onDragOver}
        nodesDraggable={!readOnly}
        nodesConnectable={!readOnly}
        elementsSelectable={!readOnly}
        edgesFocusable={!readOnly}
        // Only auto-fit when this canvas was mounted with pre-existing nodes
        // (edit mode). On new empty canvases, fitView re-zooms after each
        // drag-drop and pushes newly-added nodes out of the visible viewport.
        fitView={fitViewActive}
      >
        {/* ScreenToFlowPositionCapture must render inside ReactFlow to access
            its context. It stores screenToFlowPosition in a ref so onDrop
            can convert viewport→flow coordinates at drop time. */}
        <ScreenToFlowPositionCapture onCapture={handleCapture} />
        <Background />
        {showControls && <Controls />}
        {showMinimap && <MiniMap />}
      </ReactFlow>
    </div>
  );
}

export default FlowCanvas;
