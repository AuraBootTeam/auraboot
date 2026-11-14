// web-admin/app/flow-designer-sdk/hooks/useNodeMonitorStatus.ts
/**
 * G8 — useNodeMonitorStatus (monitor mode first-class citizen)
 *
 * Returns the monitor status for the given nodeId, or undefined when:
 *   - monitorMode is not enabled on the store, or
 *   - no status entry exists for this node in monitorData.
 *
 * Node renderers and PropertyEditor extensions use this to apply
 * runtime ring/badge effects, surface failure reasons, etc.
 *
 * Bridging note: the bpmn-designer ships its own legacy
 * `useNodeMonitorStatus` that reads from useBPMNStore.instanceStatus and
 * returns a 3-state string ('active' | 'completed' | 'idle'). T4 BPMN→SDK
 * migration should drop that hook and feed monitorData via
 * <FlowDesigner monitorMode monitorData={...} />. Automation A2 will inject
 * its runtime status map through the same prop once it lands.
 */
import { useFlowStore } from '../store/useFlowStore';
import type { NodeMonitorStatus } from '../store/monitorTypes';

export function useNodeMonitorStatus(
  nodeId: string | null | undefined,
): NodeMonitorStatus | undefined {
  const monitorMode = useFlowStore((s) => s.monitorMode);
  const monitorData = useFlowStore((s) => s.monitorData);
  if (!monitorMode || !nodeId) return undefined;
  return monitorData[nodeId];
}
