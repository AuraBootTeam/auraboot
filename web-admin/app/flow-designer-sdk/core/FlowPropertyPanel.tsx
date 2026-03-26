// web-admin/app/flow-designer-sdk/core/FlowPropertyPanel.tsx
import React from 'react';
import { useSmartText } from '~/utils/i18n';
import { useFlowStore } from '../store/useFlowStore';
import { nodeRegistry } from '../nodes/NodeRegistry';
import { PropertyField } from './PropertyField';
import { cn } from '~/utils/cn';
import { confirmDialog } from '~/utils/confirmDialog';

export interface FlowPropertyPanelProps {
  readOnly?: boolean;
  className?: string;
}

export function FlowPropertyPanel({ readOnly, className }: FlowPropertyPanelProps) {
  const st = useSmartText();
  const { nodes, selectedNodeId, deleteNode } = useFlowStore();

  const selectedNode = nodes.find((n) => n.id === selectedNodeId);

  if (!selectedNode) {
    return (
      <div className={cn('w-80 border-l border-gray-200 bg-white p-4', className)}>
        <div className="mt-8 text-center text-gray-500">
          {st('$i18n:flow.panel.selectNode') || 'Select a node to configure'}
        </div>
      </div>
    );
  }

  const definition = nodeRegistry.get(selectedNode.type);
  const schema = definition?.configSchema || [];

  const handleDelete = async () => {
    if (
      await confirmDialog({
        content: st('$i18n:flow.panel.deleteConfirm') || 'Delete this node?',
        variant: 'danger',
      })
    ) {
      deleteNode(selectedNode.id);
    }
  };

  return (
    <div className={cn('w-80 overflow-y-auto border-l border-gray-200 bg-white', className)}>
      <div className="p-4">
        {/* Header */}
        <div className="mb-4 flex items-center gap-2 border-b border-gray-200 pb-4">
          <span className="text-xl">
            {typeof definition?.icon === 'string' ? definition.icon : null}
          </span>
          <div className="flex-1">
            <h3 className="font-medium text-gray-900">
              {st(definition?.label || selectedNode.type)}
            </h3>
            {definition?.description && (
              <p className="text-xs text-gray-500">{st(definition.description)}</p>
            )}
          </div>
        </div>

        {/* Properties */}
        {schema.length > 0 ? (
          <div className="space-y-4">
            {schema.map((field) => (
              <PropertyField key={field.key} schema={field} nodeId={selectedNode.id} />
            ))}
          </div>
        ) : (
          <div className="py-4 text-center text-gray-500">
            {st('$i18n:flow.panel.noConfig') || 'No configuration options'}
          </div>
        )}

        {/* Actions */}
        {!readOnly && (
          <div className="mt-6 border-t border-gray-200 pt-4">
            <button
              type="button"
              onClick={handleDelete}
              className="w-full rounded-md bg-red-50 px-4 py-2 text-sm font-medium text-red-600 transition-colors hover:bg-red-100"
            >
              {st('$i18n:flow.panel.deleteNode') || 'Delete Node'}
            </button>
          </div>
        )}
      </div>
    </div>
  );
}

export default FlowPropertyPanel;
