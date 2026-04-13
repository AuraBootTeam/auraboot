/**
 * User task node with monitor-mode status highlighting.
 */

import { memo } from 'react';
import { Handle, Position, type NodeProps } from '@xyflow/react';
import { BPMNNodeType, type BPMNNode } from '~/plugins/core-designer/components/bpmn-designer/types';
import { BPMN_NODE_STYLES } from '~/plugins/core-designer/components/bpmn-designer/constants';
import {
  useNodeMonitorStatus,
  getMonitorStatusClasses,
} from '~/plugins/core-designer/components/bpmn-designer/hooks/useNodeMonitorStatus';
import { useBPMNStore } from '~/plugins/core-designer/components/bpmn-designer/store/useBPMNStore';

export const UserTaskNode = memo(({ id, data, selected }: NodeProps<BPMNNode>) => {
  const style = BPMN_NODE_STYLES[BPMNNodeType.USER_TASK];
  const monitorStatus = useNodeMonitorStatus(id);
  const monitorClasses = getMonitorStatusClasses(monitorStatus);

  // Resolve the assignee label when the node is active in monitor mode
  const instanceStatus = useBPMNStore((s) => s.instanceStatus);
  const activeEntry =
    monitorStatus === 'active' ? instanceStatus?.currentNodes.find((n) => n.nodeId === id) : null;

  return (
    <div className="relative">
      <div
        className={`flex flex-col items-center justify-center p-2 ${
          monitorStatus ? monitorClasses : selected ? 'ring-2 ring-blue-500' : ''
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
        <div className="w-full truncate px-1 text-center text-xs font-medium">{data.label}</div>
        {/* Assignee info */}
        {(() => {
          const cfg = data.config as any;
          const assignee = cfg?.assignee;
          if (activeEntry?.assignee) {
            return (
              <div className="mt-0.5 w-full truncate text-center text-[10px] text-blue-600">
                {activeEntry.assignee}
              </div>
            );
          }
          if (assignee?.type === 'role' && assignee.roleIds?.length) {
            return (
              <div className="mt-0.5 w-full truncate px-1 text-center text-[10px] text-gray-500">
                Role: {assignee.roleIds.join(',')}
              </div>
            );
          }
          if (assignee?.type === 'user' && assignee.userIds?.length) {
            return (
              <div className="mt-0.5 w-full truncate px-1 text-center text-[10px] text-gray-500">
                User: {assignee.userIds.join(',')}
              </div>
            );
          }
          if (assignee?.type === 'dept' && assignee.deptIds?.length) {
            return (
              <div className="mt-0.5 w-full truncate px-1 text-center text-[10px] text-gray-500">
                Dept: {assignee.deptIds.join(',')}
              </div>
            );
          }
          if (assignee?.type === 'starter') {
            return (
              <div className="mt-0.5 w-full truncate px-1 text-center text-[10px] text-gray-500">
                Starter
              </div>
            );
          }
          if (assignee?.type === 'expression' && assignee.expression) {
            return (
              <div className="mt-0.5 w-full truncate px-1 text-center font-mono text-[10px] text-gray-400">
                {assignee.expression}
              </div>
            );
          }
          return null;
        })()}
        {/* Multi-instance indicator + mode label */}
        {(data.config as any)?.multiInstance?.enabled && (
          <div className="mt-0.5 flex items-center justify-center gap-0.5 text-[10px] text-gray-500">
            <span>{(data.config as any).multiInstance.sequential ? '≡' : '|||'}</span>
            <span>
              {(data.config as any).assignee?.assigneeMode === 'multi'
                ? 'Countersign'
                : (data.config as any).assignee?.assigneeMode === 'sequential'
                  ? 'Sequential'
                  : ''}
            </span>
          </div>
        )}
        <Handle type="source" position={Position.Right} className="!h-3 !w-3 !bg-blue-500" />
      </div>
      {monitorStatus === 'completed' && (
        <div className="absolute -top-1 -right-1 flex h-4 w-4 items-center justify-center rounded-full bg-green-500">
          <svg className="h-3 w-3 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={3} d="M5 13l4 4L19 7" />
          </svg>
        </div>
      )}
    </div>
  );
});

UserTaskNode.displayName = 'UserTaskNode';
