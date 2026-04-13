// web-admin/app/flow-designer-sdk/core/FlowPropertyPanel.tsx
import React, { useState } from 'react';
import { useSmartText } from '~/utils/i18n';
import { useFlowStore } from '../store/useFlowStore';
import { nodeRegistry } from '../nodes/NodeRegistry';
import { PropertyField } from './PropertyField';
import { cn } from '~/utils/cn';
import { confirmDialog } from '~/utils/confirmDialog';
import type { PropertySchema } from '../nodes/types';

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
  const allSchema = definition?.configSchema || [];

  // Evaluate dependsOn visibility
  const visibleSchema = allSchema.filter((field) => {
    if (!field.dependsOn) return true;
    const depValue = selectedNode.data.config?.[field.dependsOn.field];
    if (field.dependsOn.value !== undefined) {
      if (Array.isArray(field.dependsOn.value)) {
        return field.dependsOn.value.includes(depValue);
      }
      return depValue === field.dependsOn.value;
    }
    return !!depValue;
  });

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

        {/* Properties - grouped */}
        {visibleSchema.length > 0 ? (
          <GroupedFields schema={visibleSchema} nodeId={selectedNode.id} />
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

/** Render fields grouped by their `group` property */
function GroupedFields({ schema, nodeId }: { schema: PropertySchema[]; nodeId: string }) {
  const st = useSmartText();
  const [collapsedGroups, setCollapsedGroups] = useState<Set<string>>(
    () => new Set(['advanced']),
  );

  const groupOrder: string[] = [];
  const groupMap = new Map<string, PropertySchema[]>();
  for (const field of schema) {
    const g = field.group || '_default';
    if (!groupMap.has(g)) {
      groupOrder.push(g);
      groupMap.set(g, []);
    }
    groupMap.get(g)!.push(field);
  }

  // If only one group (_default), render flat
  if (groupOrder.length === 1 && groupOrder[0] === '_default') {
    return (
      <div className="space-y-4">
        {schema.map((field) => (
          <PropertyField key={field.key} schema={field} nodeId={nodeId} />
        ))}
      </div>
    );
  }

  const toggleGroup = (g: string) => {
    setCollapsedGroups((prev) => {
      const next = new Set(prev);
      if (next.has(g)) next.delete(g);
      else next.add(g);
      return next;
    });
  };

  const GROUP_LABELS: Record<string, string> = {
    trigger_source: '$i18n:automation.group.triggerSource',
    filter: '$i18n:automation.group.filter',
    advanced: '$i18n:automation.group.advanced',
    target: '$i18n:automation.group.target',
    fields_mapping: '$i18n:automation.group.fieldsMapping',
    notification: '$i18n:automation.group.notification',
    request: '$i18n:automation.group.request',
    process: '$i18n:automation.group.process',
    _default: '',
  };

  return (
    <div className="space-y-2">
      {groupOrder.map((g) => {
        const fields = groupMap.get(g)!;
        const isDefault = g === '_default';
        const isCollapsed = collapsedGroups.has(g);
        const groupLabel = st(GROUP_LABELS[g] || g) || g;

        return (
          <div key={g}>
            {!isDefault && (
              <button
                type="button"
                onClick={() => toggleGroup(g)}
                className="mb-2 mt-3 flex w-full items-center justify-between text-left"
              >
                <span className="text-xs font-semibold uppercase tracking-wider text-gray-500">
                  {groupLabel}
                </span>
                <span className="text-xs text-gray-400">{isCollapsed ? '▶' : '▼'}</span>
              </button>
            )}
            {!isCollapsed && (
              <div className="space-y-4">
                {fields.map((field) => (
                  <PropertyField key={field.key} schema={field} nodeId={nodeId} />
                ))}
              </div>
            )}
            {!isDefault && !isCollapsed && (
              <div className="mb-1 mt-3 border-b border-gray-100" />
            )}
          </div>
        );
      })}
    </div>
  );
}

export default FlowPropertyPanel;
