/**
 * B2b batch2 — 4 additional BPMN node renderers ported onto flow-designer-sdk.
 *
 * Scope (Tier-1 drop-in nodes per A3 §4.1):
 *   - exclusiveGateway (was bpmn-designer/components/nodes/ExclusiveGatewayNode.tsx — 60 LOC)
 *   - inclusiveGateway (was bpmn-designer/components/nodes/InclusiveGatewayNode.tsx — 60 LOC)
 *   - receiveTask     (was bpmn-designer/components/nodes/ReceiveTaskNode.tsx — 54 LOC)
 *   - userTask        (was bpmn-designer/components/nodes/UserTaskNode.tsx — 116 LOC, ported
 *                      WITHOUT the live useBPMNStore.instanceStatus active-assignee lookup —
 *                      that branch is dropped and gated behind a TODO until B2c migrates
 *                      useBPMNStore. The static data.config.assignee rendering branches are
 *                      kept intact, so JSON state still renders identically when no monitor
 *                      activity is in flight. See B2c hand-off note in batch2 report.)
 *
 * Shared helpers (CompletedBadge / BottomLabel / ringClassesForStatus) are
 * reused from ./BpmSdkNodes via re-export (sibling module).
 *
 * Geometry, handles, colours and monitor-mode overlay are kept identical to
 * the legacy bpmn-designer versions so JSON state from those renderers maps
 * 1:1 onto these. Like batch1, the only behavioural diff is the monitor hook
 * (SDK G8 returns NodeMonitorStatus object; legacy returned a string union).
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
// Shared (duplicated rather than re-exported to avoid widening BpmSdkNodes
// public surface; kept byte-identical so we can extract to a real
// ./shared/badges.tsx file in a small follow-up without touching consumers).
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

function BottomLabel({ label }: { label?: string }) {
  if (!label) return null;
  return (
    <div className="absolute -bottom-5 left-1/2 -translate-x-1/2 text-[10px] whitespace-nowrap text-gray-500">
      {label}
    </div>
  );
}

// ---------------------------------------------------------------------------
// exclusiveGateway
// ---------------------------------------------------------------------------

export const ExclusiveGatewayNode = memo(({ id, data, selected }: NodeProps) => {
  const style = BPMN_NODE_STYLES[BPMNNodeType.EXCLUSIVE_GATEWAY];
  const monitor = useNodeMonitorStatus(id);
  const monitorStatus = monitor?.status;
  const ring = ringClassesForStatus(monitorStatus);
  const label = ((data as any)?.label as string) ?? '';

  return (
    <div className="relative" data-testid="bpm-sdk-exclusive-gateway">
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
          className="!h-3 !w-3 -rotate-45 !bg-yellow-500"
        />
        <div className="-rotate-45 text-3xl font-bold">×</div>
        <Handle
          type="source"
          position={Position.Right}
          className="!h-3 !w-3 -rotate-45 !bg-yellow-500"
        />
      </div>
      {monitorStatus === 'completed' && <CompletedBadge />}
      <BottomLabel label={label} />
    </div>
  );
});
ExclusiveGatewayNode.displayName = 'ExclusiveGatewayNode';

// ---------------------------------------------------------------------------
// inclusiveGateway
// ---------------------------------------------------------------------------

export const InclusiveGatewayNode = memo(({ id, data, selected }: NodeProps) => {
  const style = BPMN_NODE_STYLES[BPMNNodeType.INCLUSIVE_GATEWAY];
  const monitor = useNodeMonitorStatus(id);
  const monitorStatus = monitor?.status;
  const ring = ringClassesForStatus(monitorStatus);
  const label = ((data as any)?.label as string) ?? '';

  return (
    <div className="relative" data-testid="bpm-sdk-inclusive-gateway">
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
          className="!h-3 !w-3 -rotate-45 !bg-purple-500"
        />
        <div className="-rotate-45 text-2xl font-bold">○</div>
        <Handle
          type="source"
          position={Position.Right}
          className="!h-3 !w-3 -rotate-45 !bg-purple-500"
        />
      </div>
      {monitorStatus === 'completed' && <CompletedBadge />}
      <BottomLabel label={label} />
    </div>
  );
});
InclusiveGatewayNode.displayName = 'InclusiveGatewayNode';

// ---------------------------------------------------------------------------
// receiveTask
// ---------------------------------------------------------------------------

export const ReceiveTaskNode = memo(({ id, data, selected }: NodeProps) => {
  const style = BPMN_NODE_STYLES[BPMNNodeType.RECEIVE_TASK];
  const monitor = useNodeMonitorStatus(id);
  const monitorStatus = monitor?.status;
  const ring = ringClassesForStatus(monitorStatus);
  const label = ((data as any)?.label as string) ?? '';
  const cfg = ((data as any)?.config as Record<string, any> | undefined) ?? {};

  return (
    <div className="relative" data-testid="bpm-sdk-receive-task">
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
        <Handle type="target" position={Position.Left} className="!h-3 !w-3 !bg-cyan-500" />
        <div className="mb-0.5 text-2xl">📨</div>
        <div className="w-full truncate px-1 text-center text-xs font-medium">{label}</div>
        {cfg.messageRef && (
          <div className="mt-0.5 w-full truncate px-1 text-center font-mono text-[10px] text-gray-400">
            {cfg.messageRef}
          </div>
        )}
        <Handle type="source" position={Position.Right} className="!h-3 !w-3 !bg-cyan-500" />
      </div>
      {monitorStatus === 'completed' && <CompletedBadge />}
    </div>
  );
});
ReceiveTaskNode.displayName = 'ReceiveTaskNode';

// ---------------------------------------------------------------------------
// userTask
// ---------------------------------------------------------------------------

const ASSIGNEE_LABELS = {
  role: '角色',
  user: '用户',
  dept: '部门',
  starter: '发起人',
  multi: '会签',
  sequential: '顺序',
} as const;

/**
 * NOTE: the legacy UserTaskNode reads `useBPMNStore.instanceStatus` to look
 * up the LIVE assignee currently servicing the task in monitor mode (e.g.
 * "currently assigned to: alice"). That selector is intentionally not
 * ported in batch2 — it depends on the BPMN-specific store contract which
 * batch B2c will migrate onto the SDK. Until then, the SDK port:
 *   - keeps every static `data.config.assignee` rendering branch verbatim
 *   - drops the "monitor active assignee override" branch
 *   - keeps the multi-instance + assigneeMode indicator
 *
 * This matches the legacy renderer for any node NOT currently active in
 * monitor mode (the vast majority of states). The handoff is documented in
 * the batch2 port report so B2c can re-wire via the SDK monitorData payload.
 */
export const UserTaskNode = memo(({ id, data, selected }: NodeProps) => {
  const style = BPMN_NODE_STYLES[BPMNNodeType.USER_TASK];
  const monitor = useNodeMonitorStatus(id);
  const monitorStatus = monitor?.status;
  const ring = ringClassesForStatus(monitorStatus);
  const label = ((data as any)?.label as string) ?? '';
  const cfg = ((data as any)?.config as Record<string, any> | undefined) ?? {};
  const assignee = cfg?.assignee;
  const multiInstance = cfg?.multiInstance;

  let assigneeSubtitle: React.ReactNode = null;
  if (assignee?.type === 'role' && assignee.roleIds?.length) {
    assigneeSubtitle = (
      <div className="mt-0.5 w-full truncate px-1 text-center text-[10px] text-gray-500">
        {ASSIGNEE_LABELS.role}: {assignee.roleIds.join(',')}
      </div>
    );
  } else if (assignee?.type === 'user' && assignee.userIds?.length) {
    assigneeSubtitle = (
      <div className="mt-0.5 w-full truncate px-1 text-center text-[10px] text-gray-500">
        {ASSIGNEE_LABELS.user}: {assignee.userIds.join(',')}
      </div>
    );
  } else if (assignee?.type === 'dept' && assignee.deptIds?.length) {
    assigneeSubtitle = (
      <div className="mt-0.5 w-full truncate px-1 text-center text-[10px] text-gray-500">
        {ASSIGNEE_LABELS.dept}: {assignee.deptIds.join(',')}
      </div>
    );
  } else if (assignee?.type === 'starter') {
    assigneeSubtitle = (
      <div className="mt-0.5 w-full truncate px-1 text-center text-[10px] text-gray-500">
        {ASSIGNEE_LABELS.starter}
      </div>
    );
  } else if (assignee?.type === 'expression' && assignee.expression) {
    assigneeSubtitle = (
      <div className="mt-0.5 w-full truncate px-1 text-center font-mono text-[10px] text-gray-400">
        {assignee.expression}
      </div>
    );
  }

  return (
    <div className="relative" data-testid="bpm-sdk-user-task">
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
        <Handle type="target" position={Position.Left} className="!h-3 !w-3 !bg-blue-500" />
        <div className="mb-0.5 text-2xl">👤</div>
        <div className="w-full truncate px-1 text-center text-xs font-medium">{label}</div>
        {assigneeSubtitle}
        {multiInstance?.enabled && (
          <div
            className="mt-0.5 flex items-center justify-center gap-0.5 text-[10px] text-gray-500"
            data-testid="bpm-sdk-user-task-mi-indicator"
          >
            <span>{multiInstance.sequential ? '≡' : '|||'}</span>
            <span>
              {assignee?.assigneeMode === 'multi'
                ? ASSIGNEE_LABELS.multi
                : assignee?.assigneeMode === 'sequential'
                  ? ASSIGNEE_LABELS.sequential
                  : ''}
            </span>
          </div>
        )}
        <Handle type="source" position={Position.Right} className="!h-3 !w-3 !bg-blue-500" />
      </div>
      {monitorStatus === 'completed' && <CompletedBadge />}
    </div>
  );
});
UserTaskNode.displayName = 'UserTaskNode';
