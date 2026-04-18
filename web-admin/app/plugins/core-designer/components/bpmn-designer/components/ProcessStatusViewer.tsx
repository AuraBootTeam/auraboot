/**
 * ProcessStatusViewer — Read-only BPMN diagram with node status highlighting.
 *
 * Displays a process definition's visual layout with runtime status overlay:
 * - Completed nodes: green border + checkmark
 * - Active (current) nodes: blue border + pulse animation
 * - Idle (not reached) nodes: dimmed
 *
 * Uses the same node components as the BPMN designer but in monitor mode
 * by temporarily setting the useBPMNStore state.
 */

import { useEffect, useState, useCallback } from 'react';
import { ReactFlow, Controls, type NodeTypes, type NodeMouseHandler } from '@xyflow/react';
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
} from '~/plugins/core-designer/components/bpmn-designer/components/nodes';
import { useBPMNStore } from '~/plugins/core-designer/components/bpmn-designer/store/useBPMNStore';
import { BPMNNodeType, type BPMNNode, type BPMNEdge } from '~/plugins/core-designer/components/bpmn-designer/types';
import type { ProcessInstanceNodeStatus } from '~/plugins/core-designer/components/bpmn-designer/services/bpmnService';
import {
  getProcessDefinitionByKey,
  getProcessInstanceStatus,
  getProcessInstanceStatusByBusinessKey,
} from '~/plugins/core-designer/components/bpmn-designer/services/bpmnService';
import { ResultHelper } from '~/utils/type';

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

interface ProcessStatusViewerProps {
  processInstanceId?: string;
  processKey?: string;
  businessKey?: string;
}

interface NodeDetail {
  nodeId: string;
  name?: string;
  status: string;
  assignee?: string;
  completedAt?: string;
  completedBy?: string;
}

export function ProcessStatusViewer({
  processInstanceId,
  processKey,
  businessKey,
}: ProcessStatusViewerProps) {
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [nodes, setNodes] = useState<BPMNNode[]>([]);
  const [edges, setEdges] = useState<BPMNEdge[]>([]);
  const [instanceStatus, setInstanceStatus] = useState<ProcessInstanceNodeStatus | null>(null);
  const [selectedNodeDetail, setSelectedNodeDetail] = useState<NodeDetail | null>(null);

  const store = useBPMNStore;

  // Load data on mount
  useEffect(() => {
    let cancelled = false;

    async function loadData() {
      setLoading(true);
      setError(null);

      try {
        // 1. Fetch instance status
        let statusResult;
        if (processInstanceId) {
          statusResult = await getProcessInstanceStatus(processInstanceId);
        } else if (businessKey) {
          statusResult = await getProcessInstanceStatusByBusinessKey(businessKey, processKey);
        } else {
          setError('Missing processInstanceId or businessKey');
          setLoading(false);
          return;
        }

        if (cancelled) return;

        if (!ResultHelper.isSuccess(statusResult) || !statusResult.data) {
          setError(statusResult.desc || 'Failed to load process instance status');
          setLoading(false);
          return;
        }

        const status = statusResult.data;
        setInstanceStatus(status);

        // 2. Load process definition to get the visual layout
        const processDefId = status.processDefinitionId;
        if (!processDefId) {
          setError('Process definition ID not found in status');
          setLoading(false);
          return;
        }

        const defResult = await getProcessDefinitionByKey(processDefId);
        if (cancelled) return;

        if (!ResultHelper.isSuccess(defResult) || !defResult.data) {
          setError('Failed to load process definition layout');
          setLoading(false);
          return;
        }

        const definition = defResult.data;
        setNodes(definition.nodes);
        setEdges(definition.edges);

        // 3. Set the BPMN store to monitor mode so node components render status
        store.setState({
          viewMode: 'monitor',
          instanceStatus: {
            currentNodes: status.currentNodes,
            completedNodes: status.completedNodes,
          },
        });
      } catch (err) {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : 'Unknown error');
        }
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    }

    loadData();

    return () => {
      cancelled = true;
      // Restore store to design mode on unmount
      store.setState({
        viewMode: 'design',
        instanceStatus: null,
      });
    };
  }, [processInstanceId, processKey, businessKey]);

  const handleNodeClick = useCallback<NodeMouseHandler<BPMNNode>>(
    (_event, node) => {
      if (!instanceStatus) return;

      const activeNode = instanceStatus.currentNodes.find((n) => n.nodeId === node.id);
      const completedNode = instanceStatus.completedNodes.find((n) => n.nodeId === node.id);

      if (activeNode) {
        setSelectedNodeDetail({
          nodeId: node.id,
          name: node.data.label,
          status: 'active',
          assignee: activeNode.assignee,
        });
      } else if (completedNode) {
        setSelectedNodeDetail({
          nodeId: node.id,
          name: node.data.label,
          status: 'completed',
          completedAt: completedNode.completedAt,
          completedBy: completedNode.completedBy,
        });
      } else {
        setSelectedNodeDetail({
          nodeId: node.id,
          name: node.data.label,
          status: 'idle',
        });
      }
    },
    [instanceStatus],
  );

  if (loading) {
    return (
      <div className="flex h-full items-center justify-center">
        <div className="text-gray-500">Loading process status...</div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="flex h-full items-center justify-center">
        <div className="text-red-500">{error}</div>
      </div>
    );
  }

  const processStatus = instanceStatus?.status?.toLowerCase() as
    | 'draft'
    | 'published'
    | 'suspended'
    | undefined;

  return (
    <div className="flex h-full flex-col">
      {/* Header bar */}
      <div className="flex items-center justify-between border-b border-gray-200 bg-white px-4 py-3">
        <div className="flex items-center gap-3">
          <h2 className="text-lg font-semibold text-gray-800">Process Status</h2>
          {instanceStatus?.instanceId && (
            <span className="text-sm text-gray-500">{instanceStatus.instanceId}</span>
          )}
        </div>
        <div className="flex items-center gap-3">
          {instanceStatus?.status && <StatusBadge status={instanceStatus.status} />}
        </div>
      </div>

      {/* BPMN canvas + optional detail panel */}
      <div className="flex min-h-0 flex-1">
        <div className="flex-1">
          <ReactFlow<BPMNNode, BPMNEdge>
            nodes={nodes}
            edges={edges.map((e) => ({
              ...e,
              style: { stroke: '#94a3b8', strokeWidth: 2 },
            }))}
            nodeTypes={nodeTypes}
            onNodeClick={handleNodeClick}
            nodesDraggable={false}
            nodesConnectable={false}
            elementsSelectable={false}
            panOnDrag={true}
            zoomOnScroll={true}
            defaultViewport={{ x: 50, y: 50, zoom: 0.9 }}
            fitView
          >
            <Controls showInteractive={false} />
          </ReactFlow>
        </div>

        {/* Node detail panel */}
        {selectedNodeDetail && (
          <div className="w-72 overflow-auto border-l border-gray-200 bg-white p-4">
            <div className="mb-4 flex items-center justify-between">
              <h3 className="text-sm font-semibold text-gray-700">Node Detail</h3>
              <button
                onClick={() => setSelectedNodeDetail(null)}
                className="text-sm text-gray-400 hover:text-gray-600"
              >
                Close
              </button>
            </div>

            <div className="space-y-3">
              <DetailRow
                label="Name"
                value={selectedNodeDetail.name || selectedNodeDetail.nodeId}
              />
              <DetailRow label="Node ID" value={selectedNodeDetail.nodeId} />
              <DetailRow
                label="Status"
                value={<StatusBadge status={selectedNodeDetail.status} />}
              />
              {selectedNodeDetail.assignee && (
                <DetailRow label="Assignee" value={selectedNodeDetail.assignee} />
              )}
              {selectedNodeDetail.completedAt && (
                <DetailRow
                  label="Completed At"
                  value={formatDateTime(selectedNodeDetail.completedAt)}
                />
              )}
              {selectedNodeDetail.completedBy && (
                <DetailRow label="Completed By" value={selectedNodeDetail.completedBy} />
              )}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

function StatusBadge({ status }: { status: string }) {
  const normalized = (status ?? '').toLowerCase();
  const config: Record<string, { label: string; bg: string; text: string }> = {
    running: { label: 'Running', bg: 'bg-blue-100', text: 'text-blue-700' },
    completed: { label: 'Completed', bg: 'bg-green-100', text: 'text-green-700' },
    suspended: { label: 'Suspended', bg: 'bg-yellow-100', text: 'text-yellow-700' },
    terminated: { label: 'Terminated', bg: 'bg-red-100', text: 'text-red-700' },
    aborted: { label: 'Terminated', bg: 'bg-red-100', text: 'text-red-700' },
    cancelled: { label: 'Cancelled', bg: 'bg-red-100', text: 'text-red-700' },
    active: { label: 'Active', bg: 'bg-blue-100', text: 'text-blue-700' },
    idle: { label: 'Not Reached', bg: 'bg-gray-100', text: 'text-gray-500' },
  };
  const c = config[normalized] ?? { label: status, bg: 'bg-gray-100', text: 'text-gray-700' };

  return (
    <span
      className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium ${c.bg} ${c.text}`}
    >
      {c.label}
    </span>
  );
}

function DetailRow({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div>
      <div className="mb-0.5 text-xs text-gray-500">{label}</div>
      <div className="text-sm text-gray-800">{typeof value === 'string' ? value : value}</div>
    </div>
  );
}

function formatDateTime(iso: string): string {
  try {
    return new Date(iso).toLocaleString();
  } catch {
    return iso;
  }
}
