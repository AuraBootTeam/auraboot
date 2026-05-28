/**
 * B2b — first batch of BPMN node renderers ported onto flow-designer-sdk.
 *
 * Scope: 4 Tier-1 nodes from A3 feasibility report §4.1
 *   - startEvent  (was bpmn-designer/components/nodes/StartEventNode.tsx — 52 LOC)
 *   - endEvent    (was bpmn-designer/components/nodes/EndEventNode.tsx — 52 LOC)
 *   - parallelGateway (was bpmn-designer/components/nodes/ParallelGatewayNode.tsx — 60 LOC)
 *   - serviceTask (was bpmn-designer/components/nodes/ServiceTaskNode.tsx — 80 LOC)
 *
 * Geometry, handles, colours and monitor-mode overlay are kept identical to
 * the legacy bpmn-designer versions so JSON state from those renderers maps
 * 1:1 onto these. The only behavioural diff is the monitor hook: instead of
 * `bpmn-designer/hooks/useNodeMonitorStatus` (which returned a string union),
 * these consume the SDK's G8 `useNodeMonitorStatus` (returns NodeMonitorStatus
 * object). A small adapter maps the SDK `FlowMonitorStatus` enum to the same
 * ring-classes the legacy renderers used so the visual result is unchanged.
 *
 * Bpmn-designer's renderers are NOT modified or removed — this is a
 * double-write batch. The legacy renderers stay live until batch B2c migrates
 * useBPMNStore and batch B2d cuts the page over.
 */

import { memo } from 'react';
import { Handle, Position, type NodeProps } from '@xyflow/react';
import {
  useNodeMonitorStatus,
  type FlowMonitorStatus,
} from '~/plugins/core-designer/components/flow-designer-sdk';
import { BPMN_NODE_STYLES } from '~/plugins/core-designer/components/bpmn-designer/constants';
import { BPMNNodeType } from '~/plugins/core-designer/components/bpmn-designer/types';

/**
 * Adapter: bpmn-designer's monitor hook returned 'active' | 'completed' |
 * 'idle' | null. SDK G8 returns NodeMonitorStatus | undefined with a wider
 * enum (pending/running/completed/failed/skipped/idle). We collapse the SDK
 * enum down to the legacy ring-classes so visual parity holds, and we
 * intentionally keep the same "show check icon on completed" branch.
 */
function ringClassesForStatus(status: FlowMonitorStatus | undefined): string {
  switch (status) {
    case 'running':
    case 'pending':
      return 'ring-2 ring-blue-500 animate-pulse';
    case 'completed':
      return 'ring-2 ring-green-500';
    case 'failed':
      return 'ring-2 ring-red-500';
    case 'skipped':
      return 'ring-2 ring-gray-400';
    default:
      return '';
  }
}

function CompletedBadge() {
  return (
    <div
      data-testid="bpm-sdk-completed-badge"
      className="absolute -top-1 -right-1 flex h-4 w-4 items-center justify-center rounded-full bg-green-500"
    >
      <svg className="h-3 w-3 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={3} d="M5 13l4 4L19 7" />
      </svg>
    </div>
  );
}

function BottomLabel({ label }: { label?: string }) {
  if (!label) return null;
  return (
    <div className="absolute -bottom-5 left-1/2 -translate-x-1/2 text-[10px] whitespace-nowrap text-gray-500">
      {label}
    </div>
  );
}

// ---------------------------------------------------------------------------
// startEvent
// ---------------------------------------------------------------------------

export const StartEventNode = memo(({ id, data, selected }: NodeProps) => {
  const style = BPMN_NODE_STYLES[BPMNNodeType.START_EVENT];
  const monitor = useNodeMonitorStatus(id);
  const monitorStatus = monitor?.status;
  const ring = ringClassesForStatus(monitorStatus);
  const label = ((data as any)?.label as string) ?? '';

  return (
    <div className="relative" data-testid="bpm-sdk-start-event">
      <div
        className={`flex items-center justify-center ${
          monitorStatus ? ring : selected ? 'ring-2 ring-blue-500' : ''
        }`}
        style={{
          width: style.width,
          height: style.height,
          borderRadius: style.borderRadius,
          border: `${style.borderWidth}px solid ${style.borderColor}`,
          backgroundColor: style.backgroundColor,
        }}
      >
        <div className="text-2xl">▶</div>
        <Handle type="source" position={Position.Right} className="!h-3 !w-3 !bg-green-500" />
      </div>
      {monitorStatus === 'completed' && <CompletedBadge />}
      <BottomLabel label={label} />
    </div>
  );
});
StartEventNode.displayName = 'StartEventNode';

// ---------------------------------------------------------------------------
// endEvent
// ---------------------------------------------------------------------------

export const EndEventNode = memo(({ id, data, selected }: NodeProps) => {
  const style = BPMN_NODE_STYLES[BPMNNodeType.END_EVENT];
  const monitor = useNodeMonitorStatus(id);
  const monitorStatus = monitor?.status;
  const ring = ringClassesForStatus(monitorStatus);
  const label = ((data as any)?.label as string) ?? '';

  return (
    <div className="relative" data-testid="bpm-sdk-end-event">
      <div
        className={`flex items-center justify-center ${
          monitorStatus ? ring : selected ? 'ring-2 ring-blue-500' : ''
        }`}
        style={{
          width: style.width,
          height: style.height,
          borderRadius: style.borderRadius,
          border: `${style.borderWidth}px solid ${style.borderColor}`,
          backgroundColor: style.backgroundColor,
        }}
      >
        <div className="text-2xl">⬛</div>
        <Handle type="target" position={Position.Left} className="!h-3 !w-3 !bg-red-500" />
      </div>
      {monitorStatus === 'completed' && <CompletedBadge />}
      <BottomLabel label={label} />
    </div>
  );
});
EndEventNode.displayName = 'EndEventNode';

// ---------------------------------------------------------------------------
// parallelGateway
// ---------------------------------------------------------------------------

export const ParallelGatewayNode = memo(({ id, data, selected }: NodeProps) => {
  const style = BPMN_NODE_STYLES[BPMNNodeType.PARALLEL_GATEWAY];
  const monitor = useNodeMonitorStatus(id);
  const monitorStatus = monitor?.status;
  const ring = ringClassesForStatus(monitorStatus);
  const label = ((data as any)?.label as string) ?? '';

  return (
    <div className="relative" data-testid="bpm-sdk-parallel-gateway">
      <div
        className={`flex rotate-45 items-center justify-center ${
          monitorStatus ? ring : selected ? 'ring-2 ring-blue-500' : ''
        }`}
        style={{
          width: style.width,
          height: style.height,
          border: `${style.borderWidth}px solid ${style.borderColor}`,
          backgroundColor: style.backgroundColor,
        }}
      >
        <Handle
          type="target"
          position={Position.Left}
          className="!h-3 !w-3 -rotate-45 !bg-blue-500"
        />
        <div className="-rotate-45 text-2xl font-bold">+</div>
        <Handle
          type="source"
          position={Position.Right}
          className="!h-3 !w-3 -rotate-45 !bg-blue-500"
        />
      </div>
      {monitorStatus === 'completed' && <CompletedBadge />}
      <BottomLabel label={label} />
    </div>
  );
});
ParallelGatewayNode.displayName = 'ParallelGatewayNode';

// ---------------------------------------------------------------------------
// serviceTask
// ---------------------------------------------------------------------------

export const ServiceTaskNode = memo(({ id, data, selected }: NodeProps) => {
  const style = BPMN_NODE_STYLES[BPMNNodeType.SERVICE_TASK];
  const monitor = useNodeMonitorStatus(id);
  const monitorStatus = monitor?.status;
  const ring = ringClassesForStatus(monitorStatus);
  const label = ((data as any)?.label as string) ?? '';
  const cfg = ((data as any)?.config as Record<string, any> | undefined) ?? {};

  let subtitle: React.ReactNode = null;
  if (cfg.serviceType === 'http' && cfg.serviceUrl) {
    subtitle = (
      <div className="mt-0.5 w-full truncate px-1 text-center font-mono text-[10px] text-gray-400">
        {cfg.serviceUrl}
      </div>
    );
  } else if (cfg.serviceType === 'java' && typeof cfg.className === 'string') {
    subtitle = (
      <div className="mt-0.5 w-full truncate px-1 text-center font-mono text-[10px] text-gray-400">
        {(cfg.className as string).split('.').pop()}
      </div>
    );
  } else if (cfg.serviceType === 'script') {
    subtitle = (
      <div className="mt-0.5 w-full truncate px-1 text-center text-[10px] text-gray-400">
        {cfg.scriptType || 'script'}
      </div>
    );
  } else if (cfg.serviceType) {
    subtitle = (
      <div className="mt-0.5 text-center text-[10px] text-gray-400">{cfg.serviceType}</div>
    );
  }

  return (
    <div className="relative" data-testid="bpm-sdk-service-task">
      <div
        className={`flex flex-col items-center justify-center p-2 ${
          monitorStatus ? ring : selected ? 'ring-2 ring-blue-500' : ''
        }`}
        style={{
          width: style.width,
          height: style.height,
          borderRadius: style.borderRadius,
          border: `${style.borderWidth}px solid ${style.borderColor}`,
          backgroundColor: style.backgroundColor,
        }}
      >
        <Handle type="target" position={Position.Left} className="!h-3 !w-3 !bg-purple-500" />
        <div className="mb-0.5 text-2xl">⚙</div>
        <div className="w-full truncate px-1 text-center text-xs font-medium">{label}</div>
        {subtitle}
        <Handle type="source" position={Position.Right} className="!h-3 !w-3 !bg-purple-500" />
      </div>
      {monitorStatus === 'completed' && <CompletedBadge />}
    </div>
  );
});
ServiceTaskNode.displayName = 'ServiceTaskNode';
