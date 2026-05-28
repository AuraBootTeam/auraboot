/**
 * Minimal BPMN-shaped node renderers for the bpm-smoke PoC.
 *
 * Each renderer is intentionally tiny — just enough geometry/handles to prove
 * the SDK can host a BPMN-shaped flow. We do not port the full bpmn-designer
 * styles, monitor overlay, or i18n machinery.
 */

import { memo } from 'react';
import { Handle, Position, type NodeProps } from '@xyflow/react';

const wrap = (children: React.ReactNode, label: string, selected?: boolean) => (
  <div className="relative">
    {children}
    {label && (
      <div className="absolute -bottom-5 left-1/2 -translate-x-1/2 text-[10px] whitespace-nowrap text-gray-500">
        {label}
      </div>
    )}
    {selected && (
      <div className="pointer-events-none absolute inset-0 rounded ring-2 ring-blue-500" />
    )}
  </div>
);

export const StartEventNode = memo(({ data, selected }: NodeProps) => {
  const label = ((data as any)?.label as string) ?? '';
  return wrap(
    <div
      data-testid="bpm-smoke-start"
      className="flex h-10 w-10 items-center justify-center rounded-full border-2 border-green-600 bg-green-50 text-base"
    >
      <span>▶</span>
      <Handle type="source" position={Position.Right} className="!h-3 !w-3 !bg-green-500" />
    </div>,
    label,
    selected,
  );
});
StartEventNode.displayName = 'StartEventNode';

export const ExclusiveGatewayNode = memo(({ data, selected }: NodeProps) => {
  const label = ((data as any)?.label as string) ?? '';
  return wrap(
    <div
      data-testid="bpm-smoke-gateway"
      className="flex h-10 w-10 rotate-45 items-center justify-center border-2 border-yellow-500 bg-yellow-50"
    >
      <Handle
        type="target"
        position={Position.Left}
        className="!h-3 !w-3 -rotate-45 !bg-yellow-500"
      />
      <span className="-rotate-45 text-lg font-bold">×</span>
      <Handle
        type="source"
        position={Position.Right}
        className="!h-3 !w-3 -rotate-45 !bg-yellow-500"
      />
    </div>,
    label,
    selected,
  );
});
ExclusiveGatewayNode.displayName = 'ExclusiveGatewayNode';

export const ServiceTaskNode = memo(({ data, selected }: NodeProps) => {
  const label = ((data as any)?.label as string) ?? '';
  return wrap(
    <div
      data-testid="bpm-smoke-service"
      className="flex h-12 min-w-[120px] items-center justify-center rounded border-2 border-blue-500 bg-blue-50 px-3 text-sm"
    >
      <Handle type="target" position={Position.Left} className="!h-3 !w-3 !bg-blue-500" />
      <span>⚙ {label || 'serviceTask'}</span>
      <Handle type="source" position={Position.Right} className="!h-3 !w-3 !bg-blue-500" />
    </div>,
    label,
    selected,
  );
});
ServiceTaskNode.displayName = 'ServiceTaskNode';

export const EndEventNode = memo(({ data, selected }: NodeProps) => {
  const label = ((data as any)?.label as string) ?? '';
  return wrap(
    <div
      data-testid="bpm-smoke-end"
      className="flex h-10 w-10 items-center justify-center rounded-full border-4 border-red-600 bg-red-50 text-base"
    >
      <Handle type="target" position={Position.Left} className="!h-3 !w-3 !bg-red-500" />
      <span>■</span>
    </div>,
    label,
    selected,
  );
});
EndEventNode.displayName = 'EndEventNode';
