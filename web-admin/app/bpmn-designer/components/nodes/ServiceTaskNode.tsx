/**
 * Service task node with monitor-mode status highlighting.
 */

import { memo } from 'react';
import { Handle, Position, type NodeProps } from '@xyflow/react';
import { BPMNNodeType, type BPMNNode } from '~/bpmn-designer/types';
import { BPMN_NODE_STYLES } from '~/bpmn-designer/constants';
import {
  useNodeMonitorStatus,
  getMonitorStatusClasses,
} from '~/bpmn-designer/hooks/useNodeMonitorStatus';

export const ServiceTaskNode = memo(({ id, data, selected }: NodeProps<BPMNNode>) => {
  const style = BPMN_NODE_STYLES[BPMNNodeType.SERVICE_TASK];
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
        }}
      >
        <Handle type="target" position={Position.Left} className="!h-3 !w-3 !bg-purple-500" />
        <div className="mb-0.5 text-2xl">⚙</div>
        <div className="w-full truncate px-1 text-center text-xs font-medium">{data.label}</div>
        {/* Service type subtitle */}
        {(() => {
          const cfg = data.config as any;
          if (cfg?.serviceType === 'http' && cfg.serviceUrl) {
            return (
              <div className="mt-0.5 w-full truncate px-1 text-center font-mono text-[10px] text-gray-400">
                {cfg.serviceUrl}
              </div>
            );
          }
          if (cfg?.serviceType === 'java' && cfg.className) {
            return (
              <div className="mt-0.5 w-full truncate px-1 text-center font-mono text-[10px] text-gray-400">
                {cfg.className.split('.').pop()}
              </div>
            );
          }
          if (cfg?.serviceType === 'script') {
            return (
              <div className="mt-0.5 w-full truncate px-1 text-center text-[10px] text-gray-400">
                {cfg.scriptType || 'script'}
              </div>
            );
          }
          if (cfg?.serviceType) {
            return (
              <div className="mt-0.5 text-center text-[10px] text-gray-400">{cfg.serviceType}</div>
            );
          }
          return null;
        })()}
        <Handle type="source" position={Position.Right} className="!h-3 !w-3 !bg-purple-500" />
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

ServiceTaskNode.displayName = 'ServiceTaskNode';
