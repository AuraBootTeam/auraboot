/**
 * BPMN Property Panel — dispatcher that delegates to per-node-type editors.
 */

import { useBPMNStore } from '~/bpmn-designer/store/useBPMNStore';
import { BPMNNodeType } from '~/bpmn-designer/types';
import { confirmDialog } from '~/utils/confirmDialog';
import type {
  UserTaskConfig,
  ServiceTaskConfig,
  ReceiveTaskConfig,
  ExclusiveGatewayConfig,
  ParallelGatewayConfig,
  InclusiveGatewayConfig,
  CallActivityConfig,
  StartEventConfig,
  EndEventConfig,
} from '~/bpmn-designer/types';
import {
  UserTaskEditor,
  ServiceTaskEditor,
  ReceiveTaskEditor,
  GatewayEditor,
  StartEventEditor,
  EndEventEditor,
  CallActivityEditor,
  EdgeEditor,
  ProcessMetadataPanel,
} from './property-editors';

export interface ProcessMetadataProps {
  name: string;
  processKey: string;
  description: string;
  category: string;
  isExisting: boolean;
  onNameChange: (value: string) => void;
  onDescriptionChange: (value: string) => void;
  onCategoryChange: (value: string) => void;
}

export function BPMNPropertyPanel({ processMetadata }: { processMetadata?: ProcessMetadataProps }) {
  const {
    selectedNodeId,
    selectedEdgeId,
    getNodeById,
    getEdgeById,
    updateNode,
    updateEdge,
    deleteNode,
    deleteEdge,
  } = useBPMNStore();

  const selectedNode = selectedNodeId ? getNodeById(selectedNodeId) : null;
  const selectedEdge = selectedEdgeId ? getEdgeById(selectedEdgeId) : null;

  const handleDeleteNode = async () => {
    if (
      selectedNodeId &&
      (await confirmDialog({ content: '确定要删除这个节点吗？', variant: 'danger' }))
    ) {
      deleteNode(selectedNodeId);
    }
  };

  const handleDeleteEdge = async () => {
    if (
      selectedEdgeId &&
      (await confirmDialog({ content: '确定要删除这条连线吗？', variant: 'danger' }))
    ) {
      deleteEdge(selectedEdgeId);
    }
  };

  // No selection — show process metadata or placeholder
  if (!selectedNode && !selectedEdge) {
    return (
      <div className="w-80 overflow-y-auto border-l border-gray-200 bg-white p-4">
        <h2 className="mb-4 text-lg font-semibold">流程属性</h2>
        {processMetadata && <ProcessMetadataPanel metadata={processMetadata} />}
        {!processMetadata && (
          <div className="mt-4 text-center text-gray-500">请选择一个节点或连线以配置属性</div>
        )}
      </div>
    );
  }

  // Node selected — dispatch to the right editor
  if (selectedNode) {
    const nodeType = selectedNode.data.type;
    const handleConfigChange = (config: any) => updateNode(selectedNode.id, { config });

    return (
      <div className="w-80 overflow-y-auto border-l border-gray-200 bg-white p-4">
        <div className="mb-4 flex items-center justify-between">
          <h2 className="text-lg font-semibold">节点属性</h2>
          <button
            onClick={handleDeleteNode}
            className="rounded-md px-3 py-1 text-sm text-red-600 transition-colors hover:bg-red-50 hover:text-red-800"
            title="删除节点 (Delete键)"
          >
            删除
          </button>
        </div>

        {/* Common label field */}
        <div className="mb-4">
          <label className="mb-1 block text-sm font-medium text-gray-700">节点标签</label>
          <input
            type="text"
            value={selectedNode.data.label}
            onChange={(e) => updateNode(selectedNode.id, { label: e.target.value })}
            className="w-full rounded-md border border-gray-300 px-3 py-2"
          />
        </div>

        {/* Type-specific editor */}
        {nodeType === BPMNNodeType.START_EVENT && (
          <StartEventEditor
            config={selectedNode.data.config as StartEventConfig}
            onChange={handleConfigChange}
          />
        )}

        {nodeType === BPMNNodeType.END_EVENT && (
          <EndEventEditor
            config={selectedNode.data.config as EndEventConfig}
            onChange={handleConfigChange}
          />
        )}

        {nodeType === BPMNNodeType.USER_TASK && (
          <UserTaskEditor
            config={selectedNode.data.config as UserTaskConfig}
            onChange={handleConfigChange}
          />
        )}

        {nodeType === BPMNNodeType.SERVICE_TASK && (
          <ServiceTaskEditor
            config={selectedNode.data.config as ServiceTaskConfig}
            onChange={handleConfigChange}
          />
        )}

        {nodeType === BPMNNodeType.RECEIVE_TASK && (
          <ReceiveTaskEditor
            config={selectedNode.data.config as ReceiveTaskConfig}
            onChange={handleConfigChange}
          />
        )}

        {nodeType === BPMNNodeType.EXCLUSIVE_GATEWAY && (
          <GatewayEditor
            config={selectedNode.data.config as ExclusiveGatewayConfig}
            onChange={handleConfigChange}
          />
        )}

        {nodeType === BPMNNodeType.PARALLEL_GATEWAY && (
          <GatewayEditor
            config={selectedNode.data.config as ParallelGatewayConfig}
            onChange={handleConfigChange}
          />
        )}

        {nodeType === BPMNNodeType.INCLUSIVE_GATEWAY && (
          <GatewayEditor
            config={selectedNode.data.config as InclusiveGatewayConfig}
            onChange={handleConfigChange}
          />
        )}

        {nodeType === BPMNNodeType.CALL_ACTIVITY && (
          <CallActivityEditor
            config={selectedNode.data.config as CallActivityConfig}
            onChange={handleConfigChange}
          />
        )}
      </div>
    );
  }

  // Edge selected
  if (selectedEdge) {
    return (
      <div className="w-80 overflow-y-auto border-l border-gray-200 bg-white p-4">
        <div className="mb-4 flex items-center justify-between">
          <h2 className="text-lg font-semibold">连线属性</h2>
          <button
            onClick={handleDeleteEdge}
            className="rounded-md px-3 py-1 text-sm text-red-600 transition-colors hover:bg-red-50 hover:text-red-800"
            title="删除连线 (Delete键)"
          >
            删除
          </button>
        </div>

        <EdgeEditor edgeId={selectedEdge.id} data={selectedEdge.data} onUpdate={updateEdge} />
      </div>
    );
  }

  return null;
}
