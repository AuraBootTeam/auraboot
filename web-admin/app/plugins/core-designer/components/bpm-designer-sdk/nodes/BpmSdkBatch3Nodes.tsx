/**
 * B2b batch3 — final BPMN node renderer port: CallActivity.
 *
 * Scope (A3 §4.1 Tier-2):
 *   - callActivity (was bpmn-designer/components/nodes/CallActivityNode.tsx — 56 LOC).
 *     Tier-2 only because its editor depends on ProcessPicker (remote data).
 *     The renderer itself is a straight Tier-1 drop-in: double-border square
 *     with calledProcessKey subtitle.
 *
 * After this batch, all 9 legacy BPMN node types have an SDK equivalent
 * (batch1 = start/end/parallelGw/serviceTask, batch2 = exclusive/inclusive/
 * receiveTask/userTask, batch3 = callActivity).
 */

import { memo } from 'react';
import { Handle, Position, type NodeProps } from '@xyflow/react';
import {
  useNodeMonitorStatus,
  type FlowMonitorStatus,
} from '~/plugins/core-designer/components/flow-designer-sdk';
import { BPMN_NODE_STYLES } from '~/plugins/core-designer/components/bpmn-designer/constants';
import { BPMNNodeType } from '~/plugins/core-designer/components/bpmn-designer/types';

// ---------------------------------------------------------------------------
// Shared helpers (kept byte-identical to BpmSdkBatch2Nodes — extraction to a
// real ./shared/badges.tsx tracked as a small follow-up).
// ---------------------------------------------------------------------------

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

// ---------------------------------------------------------------------------
// callActivity
// ---------------------------------------------------------------------------

export const CallActivityNode = memo(({ id, data, selected }: NodeProps) => {
  const style = BPMN_NODE_STYLES[BPMNNodeType.CALL_ACTIVITY];
  const monitor = useNodeMonitorStatus(id);
  const monitorStatus = monitor?.status;
  const ring = ringClassesForStatus(monitorStatus);
  const label = ((data as any)?.label as string) ?? '';
  const cfg = ((data as any)?.config as Record<string, any> | undefined) ?? {};

  return (
    <div className="relative" data-testid="bpm-sdk-call-activity">
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
          // Double-border visual — outer border + inset ring give the classic
          // BPMN call-activity "thick frame" appearance.
          boxShadow: `inset 0 0 0 3px ${style.backgroundColor}, inset 0 0 0 5px ${style.borderColor}`,
        }}
      >
        <Handle type="target" position={Position.Left} className="!h-3 !w-3 !bg-emerald-600" />
        <div className="mb-0.5 text-2xl">&#10697;</div>
        <div className="w-full truncate px-1 text-center text-xs font-medium">{label}</div>
        {cfg.calledProcessKey && (
          <div className="mt-0.5 w-full truncate px-1 text-center font-mono text-[10px] text-gray-400">
            {cfg.calledProcessKey}
          </div>
        )}
        <Handle type="source" position={Position.Right} className="!h-3 !w-3 !bg-emerald-600" />
      </div>
      {monitorStatus === 'completed' && <CompletedBadge />}
    </div>
  );
});

CallActivityNode.displayName = 'CallActivityNode';
