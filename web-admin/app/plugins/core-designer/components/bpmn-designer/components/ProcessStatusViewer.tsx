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
import { useBpmFlowStore } from '~/plugins/core-designer/components/bpm-designer-sdk/store/useBpmFlowStore';
import { BPMNNodeType, type BPMNNode, type BPMNEdge } from '~/plugins/core-designer/components/bpmn-designer/types';
import {
  getProcessDefinitionByKey,
  getProcessInstanceStatus,
  getProcessInstanceStatusByBusinessKey,
  type ProcessInstanceNodeStatus,
} from '~/plugins/core-designer/components/bpmn-designer/services/bpmnService';
import { BpmRuleTraceSection } from '~/plugins/core-bpm/components/panel/BpmRuleTraceSection';
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
  [BPMNNodeType.RULE_TASK]: ServiceTaskNode,
  [BPMNNodeType.NOTIFICATION_TASK]: ServiceTaskNode,
  [BPMNNodeType.RECORD_UPDATE_TASK]: ServiceTaskNode,
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

  const store = useBpmFlowStore;

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
          setError('缺少流程实例 ID 或业务键');
          setLoading(false);
          return;
        }

        if (cancelled) return;

        if (!ResultHelper.isSuccess(statusResult) || !statusResult.data) {
          setError(statusResult.desc || '流程实例状态加载失败');
          setLoading(false);
          return;
        }

        const status = statusResult.data;
        setInstanceStatus(status);

        // 2. Load process definition to get the visual layout
        const processDefId = status.processDefinitionId;
        if (!processDefId) {
          setError('状态中缺少流程定义 ID');
          setLoading(false);
          return;
        }

        const defResult = await getProcessDefinitionByKey(processDefId);
        if (cancelled) return;

        if (!ResultHelper.isSuccess(defResult) || !defResult.data) {
          setError('流程定义布局加载失败');
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
          setError(err instanceof Error ? err.message : '未知错误');
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
  }, [processInstanceId, processKey, businessKey, store]);

  const handleNodeClick = useCallback<NodeMouseHandler<BPMNNode>>(
    (_event, node) => {
      if (!instanceStatus) return;

      const activeNode = instanceStatus.currentNodes.find((n) => n.nodeId === node.id);
      const completedNode = instanceStatus.completedNodes.find((n) => n.nodeId === node.id);

      if (activeNode) {
        const nodeStatus = activeNode.status === 'failed' ? 'failed' : 'active';
        setSelectedNodeDetail({
          nodeId: node.id,
          name: node.data.label,
          status: nodeStatus,
          assignee: activeNode.assignee,
        });
      } else if (completedNode) {
        const nodeStatus = completedNode.status === 'failed' ? 'failed' : 'completed';
        setSelectedNodeDetail({
          nodeId: node.id,
          name: node.data.label,
          status: nodeStatus,
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
        <div className="text-gray-500">正在加载流程状态...</div>
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

  const _processStatus = instanceStatus?.status?.toLowerCase() as
    | 'draft'
    | 'published'
    | 'suspended'
    | undefined;

  return (
    <div className="flex h-full flex-col">
      {/* Header bar */}
      <div className="flex items-center justify-between border-b border-gray-200 bg-white px-4 py-3">
        <div className="flex items-center gap-3">
          <h2 className="text-lg font-semibold text-gray-800">
            流程状态
            <span className="sr-only">Process Status</span>
          </h2>
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
              <h3 className="text-sm font-semibold text-gray-700">
                节点详情
                <span className="sr-only">Node Detail</span>
              </h3>
              <button
                onClick={() => setSelectedNodeDetail(null)}
                className="text-sm text-gray-400 hover:text-gray-600"
              >
                关闭
              </button>
            </div>

            <div className="space-y-3">
              <DetailRow
                label="名称"
                value={selectedNodeDetail.name || selectedNodeDetail.nodeId}
              />
              <DetailRow label="节点 ID" value={selectedNodeDetail.nodeId} />
              <DetailRow
                label="状态"
                value={<StatusBadge status={selectedNodeDetail.status} />}
              />
              {selectedNodeDetail.assignee && (
                <DetailRow label="当前处理人" value={selectedNodeDetail.assignee} />
              )}
              {selectedNodeDetail.completedAt && (
                <DetailRow
                  label="完成时间"
                  value={formatDateTime(selectedNodeDetail.completedAt)}
                />
              )}
              {selectedNodeDetail.completedBy && (
                <DetailRow label="完成人" value={selectedNodeDetail.completedBy} />
              )}
            </div>
          </div>
        )}
      </div>

      {instanceStatus?.instanceId && (
        <div
          data-testid="bpm-process-status-rule-trace"
          className="max-h-72 overflow-auto border-t border-gray-200 bg-gray-50 p-3"
        >
          <BpmRuleTraceSection processInstanceId={instanceStatus.instanceId} compact />
        </div>
      )}
    </div>
  );
}

function StatusBadge({ status }: { status: string }) {
  const normalized = (status ?? '').toLowerCase();
  const config: Record<string, { label: string; compatLabel: string; bg: string; text: string }> = {
    running: { label: '运行中', compatLabel: 'Running', bg: 'bg-blue-100', text: 'text-blue-700' },
    completed: { label: '已完成', compatLabel: 'Completed', bg: 'bg-green-100', text: 'text-green-700' },
    suspended: { label: '已挂起', compatLabel: 'Suspended', bg: 'bg-yellow-100', text: 'text-yellow-700' },
    terminated: { label: '已终止', compatLabel: 'Terminated', bg: 'bg-red-100', text: 'text-red-700' },
    aborted: { label: '已终止', compatLabel: 'Terminated', bg: 'bg-red-100', text: 'text-red-700' },
    cancelled: { label: '已取消', compatLabel: 'Cancelled', bg: 'bg-red-100', text: 'text-red-700' },
    failed: { label: '失败关闭', compatLabel: 'Failed', bg: 'bg-red-100', text: 'text-red-700' },
    active: { label: '进行中', compatLabel: 'Active', bg: 'bg-blue-100', text: 'text-blue-700' },
    idle: { label: '未到达', compatLabel: 'Not Reached', bg: 'bg-gray-100', text: 'text-gray-500' },
  };
  const c = config[normalized] ?? {
    label: status,
    compatLabel: status,
    bg: 'bg-gray-100',
    text: 'text-gray-700',
  };

  return (
    <span
      className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium ${c.bg} ${c.text}`}
    >
      <span aria-hidden="true">{c.label}</span>
      <span className="sr-only">{c.compatLabel}</span>
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
    return new Date(iso).toLocaleString('zh-CN');
  } catch {
    return iso;
  }
}
