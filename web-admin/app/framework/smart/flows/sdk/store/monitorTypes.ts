// web-admin/app/flow-designer-sdk/store/monitorTypes.ts
/**
 * Monitor mode (G8): runtime status surface for FlowDesigner nodes.
 *
 * SDK-internal type, kept independent from BPMN's NodeMonitorStatus
 * ('active' | 'completed' | 'idle') and Automation's A2 runtime status map.
 * Once A2 (status injection from the parent runtime) lands, a bridge PR
 * should align names; for now we keep this superset so editors can be
 * monitor-mode aware without forking the SDK.
 *
 * TODO(B2c/A2-bridge): once A2 lands and finalizes its status enum, map
 * BPMN.NodeMonitorStatus ('active'→'running', 'completed'→'completed',
 * 'idle'→'idle') and Automation runtime states here. Until then, callers
 * normalize at the injection boundary.
 */
export type FlowMonitorStatus =
  | 'pending'
  | 'running'
  | 'completed'
  | 'failed'
  | 'skipped'
  | 'idle';

export interface NodeMonitorStatus {
  status: FlowMonitorStatus;
  /** Optional human-readable message (failure reason, current step, etc.). */
  message?: string;
  /** Optional millis timestamp of last status transition. */
  updatedAt?: number;
  /** Free-form runtime metadata (instanceId, taskAssignee, ...). */
  meta?: Record<string, unknown>;
}

/**
 * Monitor data injected via <FlowDesigner monitorData={...} />.
 * Keyed by FlowNode.id.
 */
export type FlowMonitorData = Record<string, NodeMonitorStatus>;
