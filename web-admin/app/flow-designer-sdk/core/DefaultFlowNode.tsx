// web-admin/app/flow-designer-sdk/core/DefaultFlowNode.tsx
import React from 'react';
import { Handle, Position, type NodeProps } from '@xyflow/react';
import { useSmartText } from '~/utils/i18n';
import { nodeRegistry } from '../nodes/NodeRegistry';
import { cn } from '~/utils/cn';

const categoryColors: Record<string, string> = {
  trigger: 'border-green-500 bg-green-50',
  action: 'border-blue-500 bg-blue-50',
  control: 'border-yellow-500 bg-yellow-50',
  default: 'border-gray-500 bg-gray-50',
};

export function DefaultFlowNode({ id, data, selected, type }: NodeProps) {
  const st = useSmartText();
  const definition = nodeRegistry.get(type || (data.type as string));

  const categoryColor = categoryColors[definition?.category || 'default'] || categoryColors.default;

  return (
    <div
      className={cn(
        'min-w-[150px] rounded-lg border-2 px-4 py-3 shadow-sm',
        categoryColor,
        selected && 'ring-2 ring-blue-500 ring-offset-2',
      )}
    >
      {/* Input handle - not for triggers */}
      {definition?.category !== 'trigger' && (
        <Handle
          type="target"
          position={Position.Top}
          className="h-3 w-3 border-2 border-white bg-gray-400"
        />
      )}

      {/* Node content */}
      <div className="flex items-center gap-2">
        <span className="text-xl">{definition?.icon || '📦'}</span>
        <div>
          <div className="text-sm font-medium text-gray-900">
            {st(definition?.label || (data.label as string) || type || 'Unknown')}
          </div>
          {definition?.description && (
            <div className="text-xs text-gray-500">{st(definition.description)}</div>
          )}
        </div>
      </div>

      {/* Output handle - standard */}
      {!(definition?.category === 'control' && definition.type.includes('condition')) && (
        <Handle
          type="source"
          position={Position.Bottom}
          className="h-3 w-3 border-2 border-white bg-gray-400"
        />
      )}

      {/* Condition node: two output handles (true/false) */}
      {definition?.category === 'control' && definition.type.includes('condition') && (
        <>
          <Handle
            type="source"
            position={Position.Bottom}
            id="true"
            className="h-3 w-3 border-2 border-white bg-green-500"
            style={{ left: '30%' }}
          />
          <Handle
            type="source"
            position={Position.Bottom}
            id="false"
            className="h-3 w-3 border-2 border-white bg-red-500"
            style={{ left: '70%' }}
          />
        </>
      )}
    </div>
  );
}

export default DefaultFlowNode;
