// web-admin/app/flow-designer-sdk/validation/validateFlow.ts
import type { FlowNode, ValidationError, ValidationResult } from '../store/types';
import type { FlowNodeDefinition, PropertySchema } from '../nodes/types';

export interface ValidateFlowOptions {
  /** Message used for empty required fields (resolve i18n at the call site). */
  requiredMessage?: string;
}

/** Replicates FlowPropertyPanel's dependsOn visibility so a field that is not
 *  shown to the user is not validated as required. */
function isVisible(field: PropertySchema, config: Record<string, unknown>): boolean {
  if (!field.dependsOn) return true;
  const depValue = config?.[field.dependsOn.field];
  if (field.dependsOn.value !== undefined) {
    if (Array.isArray(field.dependsOn.value)) {
      return field.dependsOn.value.includes(depValue);
    }
    return depValue === field.dependsOn.value;
  }
  return !!depValue;
}

function isEmpty(value: unknown): boolean {
  if (value === undefined || value === null) return true;
  if (typeof value === 'string') return value.trim() === '';
  if (Array.isArray(value)) return value.length === 0;
  return false;
}

/**
 * Validate a flow's node configuration against each node definition's
 * {@code configSchema}. Currently enforces required fields (respecting
 * {@code dependsOn} visibility); the result feeds {@code FlowFieldAdapter.error}
 * for field-level error states and gates save (P0-4).
 *
 * Pure and i18n-agnostic so it can be unit-tested without React: the caller
 * passes a node-definition resolver and the (already-resolved) requiredMessage.
 */
export function validateFlow(
  nodes: FlowNode[],
  getDefinition: (type: string) => FlowNodeDefinition | undefined,
  opts: ValidateFlowOptions = {},
): ValidationResult {
  const requiredMessage = opts.requiredMessage ?? 'This field is required';
  const errors: ValidationError[] = [];

  for (const node of nodes) {
    const schema = getDefinition(node.type)?.configSchema;
    if (!schema) continue;
    const config = node.data?.config ?? {};
    for (const field of schema) {
      if (!field.required) continue;
      if (!isVisible(field, config)) continue;
      if (isEmpty(config[field.key])) {
        errors.push({
          nodeId: node.id,
          fieldKey: field.key,
          message: requiredMessage,
          type: 'error',
        });
      }
    }
  }

  return { valid: errors.length === 0, errors };
}

export default validateFlow;
