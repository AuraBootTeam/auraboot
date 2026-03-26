/**
 * Parallel gateway node with monitor-mode status highlighting.
 */

import { memo } from 'react';
import { Handle, Position, type NodeProps } from '@xyflow/react';
import { BPMNNodeType, type BPMNNode } from '~/bpmn-designer/types';
import { BPMN_NODE_STYLES } from '~/bpmn-designer/constants';
import {
  useNodeMonitorStatus,
  getMonitorStatusClasses,
} from '~/bpmn-designer/hooks/useNodeMonitorStatus';

export const ParallelGatewayNode = memo(({ id, data, selected }: NodeProps<BPMNNode>) => {
  const style = BPMN_NODE_STYLES[BPMNNodeType.PARALLEL_GATEWAY];
  const monitorStatus = useNodeMonitorStatus(id);
  const monitorClasses = getMonitorStatusClasses(monitorStatus);

  return (
    <div className="relative">
      <div
        className={`flex rotate-45 items-center justify-center ${
          monitorStatus ? monitorClasses : selected ? 'ring-2 ring-blue-500' : ''
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
        <Handle
          type="source"
          position={Position.Top}
          className="!h-3 !w-3 -rotate-45 !bg-blue-500"
        />
        <Handle
          type="source"
          position={Position.Bottom}
          className="!h-3 !w-3 -rotate-45 !bg-blue-500"
        />
      </div>
      {monitorStatus === 'completed' && (
        <div className="absolute -top-1 -right-1 flex h-4 w-4 items-center justify-center rounded-full bg-green-500">
          <svg className="h-3 w-3 text-white" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={3} d="M5 13l4 4L19 7" />
          </svg>
        </div>
      )}
      {data.label && (
        <div className="absolute -bottom-5 left-1/2 -translate-x-1/2 text-[10px] whitespace-nowrap text-gray-500">
          {data.label}
        </div>
      )}
    </div>
  );
});

ParallelGatewayNode.displayName = 'ParallelGatewayNode';
