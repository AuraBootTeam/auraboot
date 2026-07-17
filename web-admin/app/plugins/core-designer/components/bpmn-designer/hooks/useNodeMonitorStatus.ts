/**
 * Hook for node components to resolve their monitor-mode status.
 *
 * Returns 'active' | 'completed' | 'failed' | 'idle' based on the global
 * instanceStatus in the store, or null when not in monitor mode.
 */

import { useBpmFlowStore } from '~/plugins/core-designer/components/bpm-designer-sdk/store/useBpmFlowStore';
import type { NodeMonitorStatus } from '~/plugins/core-designer/components/bpmn-designer/types';

export function useNodeMonitorStatus(nodeId: string): NodeMonitorStatus | null {
  const viewMode = useBpmFlowStore((s) => s.viewMode);
  const instanceStatus = useBpmFlowStore((s) => s.instanceStatus);

  if (viewMode !== 'monitor' || !instanceStatus) {
    return null;
  }

  const isFailed =
    instanceStatus.currentNodes.some((n) => n.nodeId === nodeId && n.status === 'failed') ||
    instanceStatus.completedNodes.some((n) => n.nodeId === nodeId && n.status === 'failed');
  if (isFailed) return 'failed';

  const isActive = instanceStatus.currentNodes.some((n) => n.nodeId === nodeId);
  if (isActive) return 'active';

  const isCompleted = instanceStatus.completedNodes.some((n) => n.nodeId === nodeId);
  if (isCompleted) return 'completed';

  return 'idle';
}

/**
 * Returns a Tailwind CSS class string for the monitor-mode ring/border effect.
 */
export function getMonitorStatusClasses(status: NodeMonitorStatus | null): string {
  switch (status) {
    case 'active':
      return 'ring-2 ring-blue-500 animate-pulse';
    case 'completed':
      return 'ring-2 ring-green-500';
    case 'failed':
      return 'ring-2 ring-red-500';
    case 'idle':
      return 'opacity-50';
    default:
      return '';
  }
}
