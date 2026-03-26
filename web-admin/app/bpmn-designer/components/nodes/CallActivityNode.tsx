/**
 * Call activity (sub-process) node with double border.
 */

import { memo } from 'react';
import { Handle, Position, type NodeProps } from '@xyflow/react';
import { BPMNNodeType, type BPMNNode } from '~/bpmn-designer/types';
import { BPMN_NODE_STYLES } from '~/bpmn-designer/constants';
import {
  useNodeMonitorStatus,
  getMonitorStatusClasses,
} from '~/bpmn-designer/hooks/useNodeMonitorStatus';

export const CallActivityNode = memo(({ id, data, selected }: NodeProps<BPMNNode>) => {
  const style = BPMN_NODE_STYLES[BPMNNodeType.CALL_ACTIVITY];
  const monitorStatus = useNodeMonitorStatus(id);
  const monitorClasses = getMonitorStatusClasses(monitorStatus);

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
          boxShadow: `inset 0 0 0 3px ${style.backgroundColor}, inset 0 0 0 5px ${style.borderColor}`,
        }}
      >
        <Handle type="target" position={Position.Left} className="!h-3 !w-3 !bg-emerald-600" />
        <div className="mb-0.5 text-2xl">&#10697;</div>
        <div className="w-full truncate px-1 text-center text-xs font-medium">{data.label}</div>
        {(data.config as any)?.calledProcessKey && (
          <div className="mt-0.5 w-full truncate px-1 text-center font-mono text-[10px] text-gray-400">
            {(data.config as any).calledProcessKey}
          </div>
        )}
        <Handle type="source" position={Position.Right} className="!h-3 !w-3 !bg-emerald-600" />
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

CallActivityNode.displayName = 'CallActivityNode';
