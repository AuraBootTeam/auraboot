/**
 * BPMN画布组件 - 基于React Flow
 */

import React, { useCallback, useRef, useState } from 'react';
import {
  ReactFlow,
  Controls,
  useNodesState,
  useEdgesState,
  addEdge,
  type Connection,
  type NodeTypes,
  type EdgeTypes,
  type OnNodesChange,
  type OnEdgesChange,
  type OnConnect,
  type NodeMouseHandler,
  type EdgeMouseHandler,
  type ReactFlowInstance,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';

import {
  StartEventNode,
  EndEventNode,
  UserTaskNode,
  ServiceTaskNode,
  ReceiveTaskNode,
  ExclusiveGatewayNode,
  ParallelGatewayNode,
  InclusiveGatewayNode,
  CallActivityNode,
} from '~/bpmn-designer/components/nodes';
import { ConditionalEdge } from '~/bpmn-designer/components/edges/ConditionalEdge';
import { useBPMNStore } from '~/bpmn-designer/store/useBPMNStore';
import {
  BPMNNodeType,
  type BPMNNode,
  type BPMNEdge,
  type BPMNPaletteItem,
} from '~/bpmn-designer/types';
import { DEFAULT_NODE_CONFIGS, GRID_CONFIG } from '~/bpmn-designer/constants';

const nodeTypes: NodeTypes = {
  [BPMNNodeType.START_EVENT]: StartEventNode,
  [BPMNNodeType.END_EVENT]: EndEventNode,
  [BPMNNodeType.USER_TASK]: UserTaskNode,
  [BPMNNodeType.SERVICE_TASK]: ServiceTaskNode,
  [BPMNNodeType.RECEIVE_TASK]: ReceiveTaskNode,
  [BPMNNodeType.EXCLUSIVE_GATEWAY]: ExclusiveGatewayNode,
  [BPMNNodeType.PARALLEL_GATEWAY]: ParallelGatewayNode,
  [BPMNNodeType.INCLUSIVE_GATEWAY]: InclusiveGatewayNode,
  [BPMNNodeType.CALL_ACTIVITY]: CallActivityNode,
};

const edgeTypes: EdgeTypes = {
  conditional: ConditionalEdge,
};

interface BPMNCanvasProps {
  onNodeClick?: (nodeId: string) => void;
  onEdgeClick?: (edgeId: string) => void;
}

export function BPMNCanvas({ onNodeClick, onEdgeClick }: BPMNCanvasProps) {
  const reactFlowWrapper = useRef<HTMLDivElement>(null);
  const [isDragOver, setIsDragOver] = useState(false);
  const [reactFlowInstance, setReactFlowInstance] = React.useState<ReactFlowInstance<
    BPMNNode,
    BPMNEdge
  > | null>(null);

  const {
    nodes: storeNodes,
    edges: storeEdges,
    selectedNodeId,
    selectedEdgeId,
    setNodes: setStoreNodes,
    setEdges: setStoreEdges,
    addNode,
    addEdge: addStoreEdge,
    deleteNode,
    deleteEdge,
    setSelectedNode,
    setSelectedEdge,
  } = useBPMNStore();

  const [nodes, setNodes, onNodesChange] = useNodesState<BPMNNode>(storeNodes);
  const [edges, setEdges, onEdgesChange] = useEdgesState<BPMNEdge>(storeEdges);

  const isSyncingFromStore = React.useRef(false);
  const isAddingNode = React.useRef(false);

  // 从store同步到本地（单向）- 避免在添加节点时同步
  React.useEffect(() => {
    // 如果正在添加节点，不要同步（避免位置被重置）
    if (isAddingNode.current) {
      return;
    }

    // 同步节点：检查数量变化或内容变化
    const hasChanges =
      storeNodes.length !== nodes.length ||
      storeNodes.some((storeNode, index) => {
        const localNode = nodes[index];
        if (!localNode || storeNode.id !== localNode.id) return true;
        // 检查 label 或其他 data 属性是否变化
        return JSON.stringify(storeNode.data) !== JSON.stringify(localNode.data);
      });

    if (hasChanges) {
      isSyncingFromStore.current = true;
      setNodes(storeNodes);
      setTimeout(() => {
        isSyncingFromStore.current = false;
      }, 100);
    }
  }, [storeNodes, nodes, setNodes]);

  // 同步边并确保使用自定义边类型
  React.useEffect(() => {
    isSyncingFromStore.current = true;
    const updatedEdges = storeEdges.map((edge: any) => ({
      ...edge,
      type: edge.type === 'smoothstep' || !edge.type ? 'conditional' : edge.type,
      selected: edge.id === selectedEdgeId,
    }));
    setEdges(updatedEdges);
    setTimeout(() => {
      isSyncingFromStore.current = false;
    }, 100);
  }, [storeEdges, selectedEdgeId, setEdges]);

  // 节点变化处理 - 同步回store
  const handleNodesChange = React.useCallback<OnNodesChange<BPMNNode>>(
    (changes) => {
      onNodesChange(changes);
    },
    [onNodesChange],
  );

  // 边变化处理 - 同步回store
  const handleEdgesChange = React.useCallback<OnEdgesChange<BPMNEdge>>(
    (changes) => {
      onEdgesChange(changes);
    },
    [onEdgesChange],
  );

  // 同步节点到store（防抖 + 防止循环）
  React.useEffect(() => {
    if (!isSyncingFromStore.current && nodes.length > 0) {
      const timer = setTimeout(() => {
        setStoreNodes(nodes);
      }, 300);
      return () => clearTimeout(timer);
    }
  }, [nodes, setStoreNodes]);

  // 同步边到store（防抖 + 防止循环）
  React.useEffect(() => {
    if (!isSyncingFromStore.current) {
      const timer = setTimeout(() => {
        setStoreEdges(edges);
      }, 300);
      return () => clearTimeout(timer);
    }
  }, [edges, setStoreEdges]);

  const onConnect: OnConnect = useCallback(
    (connection: Connection) => {
      const newEdge: BPMNEdge = {
        id: `edge-${Date.now()}`,
        source: connection.source!,
        target: connection.target!,
        type: 'conditional',
        animated: false,
        data: {
          label: '',
        },
      };
      setEdges((eds) => addEdge(newEdge, eds));
      addStoreEdge(newEdge);
    },
    [setEdges, addStoreEdge],
  );

  const onDragOver = useCallback((event: React.DragEvent) => {
    event.preventDefault();
    event.dataTransfer.dropEffect = 'move';
    setIsDragOver(true);
  }, []);

  const handleDragLeave = useCallback((event: React.DragEvent) => {
    if (event.currentTarget === event.target) {
      setIsDragOver(false);
    }
  }, []);

  const onDrop = useCallback(
    (event: React.DragEvent) => {
      event.preventDefault();
      setIsDragOver(false);

      if (!reactFlowInstance) {
        return;
      }

      const type = event.dataTransfer.getData('application/reactflow');
      if (!type) {
        return;
      }

      const paletteItem: BPMNPaletteItem = JSON.parse(type);

      // screenToFlowPosition expects screen coordinates (event.clientX/Y),
      // NOT wrapper-relative coordinates. It internally subtracts the wrapper offset.
      const position = reactFlowInstance.screenToFlowPosition({
        x: event.clientX,
        y: event.clientY,
      });

      const newNode: BPMNNode = {
        id: `${paletteItem.type}-${Date.now()}`,
        type: paletteItem.type,
        position,
        data: {
          type: paletteItem.type,
          label: paletteItem.label,
          config: DEFAULT_NODE_CONFIGS[paletteItem.type],
        },
      };

      // 标记正在添加节点
      isAddingNode.current = true;

      // 直接添加到本地state
      setNodes((nds) => [...nds, newNode]);

      // 添加到store
      addNode(newNode);

      // 延迟后允许同步
      setTimeout(() => {
        isAddingNode.current = false;
      }, 100);
    },
    [reactFlowInstance, addNode, setNodes],
  );

  const handleNodeClick = useCallback<NodeMouseHandler<BPMNNode>>(
    (_event, node) => {
      setSelectedNode(node.id);
      onNodeClick?.(node.id);
    },
    [setSelectedNode, onNodeClick],
  );

  const handleEdgeClick = useCallback<EdgeMouseHandler<BPMNEdge>>(
    (_event, edge) => {
      setSelectedEdge(edge.id);
      onEdgeClick?.(edge.id);
    },
    [setSelectedEdge, onEdgeClick],
  );

  // 处理键盘删除事件
  React.useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      // Delete 或 Backspace 键
      if (event.key === 'Delete' || event.key === 'Backspace') {
        // 防止在输入框中删除
        const target = event.target as HTMLElement;
        if (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.tagName === 'SELECT') {
          return;
        }

        event.preventDefault();

        // 删除选中的节点
        if (selectedNodeId) {
          deleteNode(selectedNodeId);
          // 从本地state中删除
          setNodes((nds) => nds.filter((n) => n.id !== selectedNodeId));
        }

        // 删除选中的边
        if (selectedEdgeId) {
          deleteEdge(selectedEdgeId);
          // 从本地state中删除
          setEdges((eds) => eds.filter((e) => e.id !== selectedEdgeId));
        }
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => {
      window.removeEventListener('keydown', handleKeyDown);
    };
  }, [selectedNodeId, selectedEdgeId, deleteNode, deleteEdge, setNodes, setEdges]);

  return (
    <div
      ref={reactFlowWrapper}
      className={`flex-1 transition-all ${isDragOver ? 'ring-2 ring-blue-300 ring-inset' : ''}`}
      style={{ height: '100%', minHeight: 0 }}
    >
      <ReactFlow<BPMNNode, BPMNEdge>
        nodes={nodes}
        edges={edges}
        onNodesChange={handleNodesChange}
        onEdgesChange={handleEdgesChange}
        onConnect={onConnect}
        onInit={setReactFlowInstance}
        onDrop={onDrop}
        onDragOver={onDragOver}
        onDragLeave={handleDragLeave}
        onNodeClick={handleNodeClick}
        onEdgeClick={handleEdgeClick}
        nodeTypes={nodeTypes}
        edgeTypes={edgeTypes}
        defaultEdgeOptions={{ type: 'conditional' }}
        nodesDraggable={true}
        nodesConnectable={true}
        elementsSelectable={true}
        deleteKeyCode="Delete"
        snapToGrid={true}
        snapGrid={[GRID_CONFIG.size, GRID_CONFIG.size]}
        defaultViewport={{ x: 0, y: 0, zoom: 1 }}
      >
        <Controls />
        {/* <Panel position="top-left" className="bg-white p-2 rounded shadow">
        </Panel> */}
      </ReactFlow>
    </div>
  );
}
