/**
 * Hook for node components to resolve their validation-mode status (G-U1).
 *
 * After the user clicks "Validate", the store holds a `validationResult` whose
 * `errors[]` entries carry an optional `nodeId`. Previously these were surfaced
 * only as a red banner + toast, leaving the user to hunt the offending node on
 * the canvas. This hook lets each node component highlight itself (red for
 * error, amber for warning) so validation feedback is visible *on the diagram*.
 *
 * Mutually exclusive with monitor mode: returns `null` while in monitor mode so
 * runtime highlighting (active/completed/idle) is never mixed with validation.
 */

import { useBpmFlowStore } from '~/plugins/core-designer/components/bpm-designer-sdk/store/useBpmFlowStore';
import type {
  NodeMonitorStatus,
  NodeValidationStatus,
  ValidationResult,
} from '~/plugins/core-designer/components/bpmn-designer/types';

/**
 * Pure resolver — given a validation result and a node id, return the most
 * severe status attached to that node (error wins over warning), or null.
 * Exported for unit testing without rendering.
 */
export function resolveNodeValidationStatus(
  validationResult: ValidationResult | null,
  nodeId: string,
): NodeValidationStatus {
  if (!validationResult || validationResult.errors.length === 0) {
    return null;
  }
  let hasWarning = false;
  for (const err of validationResult.errors) {
    if (err.nodeId !== nodeId) continue;
    if (err.type === 'error') {
      return 'error';
    }
    if (err.type === 'warning') {
      hasWarning = true;
    }
  }
  return hasWarning ? 'warning' : null;
}

export function useNodeValidationStatus(nodeId: string): NodeValidationStatus {
  const viewMode = useBpmFlowStore((s) => s.viewMode);
  const validationResult = useBpmFlowStore((s) => s.validationResult);

  // Validation highlighting only applies in design mode.
  if (viewMode === 'monitor') {
    return null;
  }
  return resolveNodeValidationStatus(validationResult, nodeId);
}

/**
 * Tailwind classes for the validation ring. Kept distinct from the selected /
 * monitor rings so the three states are visually unambiguous.
 */
export function getValidationStatusClasses(status: NodeValidationStatus): string {
  switch (status) {
    case 'error':
      return 'ring-2 ring-red-500';
    case 'warning':
      return 'ring-2 ring-amber-500';
    default:
      return '';
  }
}

/**
 * Single source of truth for a node's outline classes, encoding precedence:
 *   monitor (runtime view) > validation error > validation warning > selected.
 * Each node component calls this so the precedence is consistent everywhere.
 */
export function resolveNodeStateClasses(args: {
  monitorStatus: NodeMonitorStatus | null;
  monitorClasses: string;
  validationStatus: NodeValidationStatus;
  selected: boolean;
}): string {
  const { monitorStatus, monitorClasses, validationStatus, selected } = args;
  if (monitorStatus) {
    return monitorClasses;
  }
  if (validationStatus) {
    return getValidationStatusClasses(validationStatus);
  }
  return selected ? 'ring-2 ring-blue-500' : '';
}
