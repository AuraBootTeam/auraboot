// web-admin/app/flow-designer-sdk/core/DefaultFlowNode.tsx
import React from 'react';
import { Handle, Position, type NodeProps } from '@xyflow/react';
import { useSmartText } from '~/utils/i18n';
import { nodeRegistry } from '../nodes/NodeRegistry';
import {
  useNodeRuntimeStatus,
  type NodeRuntimeStatus,
} from '../runtime/NodeRuntimeStatusContext';
import { cn } from '~/utils/cn';
import { resolveIcon } from '~/utils/icon-resolver';
import type { FlowNodeAvailabilityMetadata, FlowNodeDefinition } from '../nodes/types';

const categoryColors: Record<string, string> = {
  trigger: 'border-green-500 bg-green-50',
  action: 'border-blue-500 bg-blue-50',
  control: 'border-yellow-500 bg-yellow-50',
  default: 'border-gray-500 bg-gray-50',
};

// G5 — runtime overlay styling. Kept entirely local to this renderer so SDK
// consumers don't need to know about the status taxonomy at the node-definition
// layer; the visual cue is added purely from the React context populated by
// FlowDesigner.nodeStatuses.
const runtimeStatusRing: Record<NodeRuntimeStatus, string> = {
  pending: 'ring-2 ring-gray-300',
  running: 'ring-2 ring-blue-500 animate-pulse',
  completed: 'ring-2 ring-green-500',
  failed: 'ring-2 ring-red-500',
  skipped: 'ring-2 ring-gray-300 opacity-60',
};

const runtimeStatusBadge: Record<NodeRuntimeStatus, { label: string; cls: string }> = {
  pending: { label: '…', cls: 'bg-gray-400 text-white' },
  running: { label: '▶', cls: 'bg-blue-500 text-white' },
  completed: { label: '✓', cls: 'bg-green-500 text-white' },
  failed: { label: '✕', cls: 'bg-red-500 text-white' },
  skipped: { label: '–', cls: 'bg-gray-400 text-white' },
};

function renderNodeIcon(icon: unknown, label: string) {
  if (React.isValidElement(icon)) return icon;
  if (typeof icon === 'string') {
    const trimmed = icon.trim();
    if (trimmed.length > 0 && trimmed.length <= 2 && !/[A-Za-z0-9_-]/.test(trimmed)) {
      return <span aria-hidden="true">{trimmed}</span>;
    }
    return resolveIcon(trimmed || null, label, 22);
  }
  return resolveIcon(null, label, 22);
}

function resolveSmartText(
  st: (key: string) => string,
  key: string,
  fallback: string,
) {
  const value = st(key);
  return value && value !== key ? value : fallback;
}

function nodeAvailability(
  definition: FlowNodeDefinition | undefined,
): FlowNodeAvailabilityMetadata | undefined {
  const availability = definition?.metadata?.availability;
  return availability && typeof availability === 'object' ? availability : undefined;
}

export function DefaultFlowNode({ id, data, selected, type }: NodeProps) {
  const st = useSmartText();
  const definition = nodeRegistry.get(type || (data.type as string));
  const runtimeStatus = useNodeRuntimeStatus(id);
  const availability = nodeAvailability(definition);
  const unavailable = availability?.unavailable === true;

  const categoryColor = categoryColors[definition?.category || 'default'] || categoryColors.default;
  const label = st(definition?.label || (data.label as string) || type || 'Unknown')
    || String((data.label as string | undefined) || type || 'Unknown');

  return (
    <div
      data-testid={`flow-node-${id}`}
      data-runtime-status={runtimeStatus || undefined}
      className={cn(
        'relative min-w-[150px] rounded-lg border-2 px-4 py-3 shadow-sm',
        categoryColor,
        // Selection ring takes priority visually (offset adds spacing); when not
        // selected, the runtime overlay ring renders directly on the node.
        selected && 'ring-2 ring-blue-500 ring-offset-2',
        !selected && runtimeStatus && runtimeStatusRing[runtimeStatus],
      )}
    >
      {/* G5 — runtime status badge (top-right corner). Render only when an
          overlay is active; otherwise the node looks identical to before. */}
      {runtimeStatus && (
        <span
          data-testid={`flow-node-${id}-status-badge`}
          aria-label={`runtime status: ${runtimeStatus}`}
          className={cn(
            'absolute -top-2 -right-2 flex h-5 w-5 items-center justify-center rounded-full text-xs font-bold',
            runtimeStatusBadge[runtimeStatus].cls,
          )}
        >
          {runtimeStatusBadge[runtimeStatus].label}
        </span>
      )}

      {unavailable && (
        <span
          data-testid={`flow-node-${id}-availability-badge`}
          title={availability?.reason}
          className="absolute -top-2 left-3 rounded-full border border-amber-200 bg-amber-50 px-2 py-0.5 text-[10px] font-medium text-amber-700 shadow-sm"
        >
          {resolveSmartText(st, '$i18n:flow.availability.unavailable', '不可用')}
        </span>
      )}

      {/* Input handle - not for triggers */}
      {definition?.category !== 'trigger' && (
        <Handle
          type="target"
          position={Position.Top}
          data-testid={`node-handle-target-${id}`}
          className="h-3 w-3 border-2 border-white bg-gray-400"
        />
      )}

      {/* Node content */}
      <div className="flex items-center gap-2">
        <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-md bg-white/70 text-gray-700 shadow-sm">
          {renderNodeIcon(definition?.icon, label)}
        </span>
        <div>
          <div className="text-sm font-medium text-gray-900">
            {label}
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
          data-testid={`node-handle-source-${id}`}
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
            data-testid={`node-handle-source-${id}-true`}
            className="h-3 w-3 border-2 border-white bg-green-500"
            style={{ left: '30%' }}
          />
          <Handle
            type="source"
            position={Position.Bottom}
            id="false"
            data-testid={`node-handle-source-${id}-false`}
            className="h-3 w-3 border-2 border-white bg-red-500"
            style={{ left: '70%' }}
          />
        </>
      )}
    </div>
  );
}

export default DefaultFlowNode;
