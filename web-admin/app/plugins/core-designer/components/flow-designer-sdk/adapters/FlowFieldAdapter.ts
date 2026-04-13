// web-admin/app/flow-designer-sdk/adapters/FlowFieldAdapter.ts
import { useCallback, useMemo } from 'react';
import type { FieldAdapter } from '~/components/field-adapter';
import { useFlowStore } from '../store/useFlowStore';

export interface FlowFieldAdapterProps<T = unknown> {
  /** Field key (in node.data.config) */
  fieldKey: string;
  /** Node ID (defaults to selected node) */
  nodeId?: string;
  /** Whether the field is required */
  required?: boolean;
  /** Whether the field is disabled */
  disabled?: boolean;
  /** Whether the field is read-only */
  readOnly?: boolean;
}

/**
 * useFlowFieldAdapter - Create a FieldAdapter for FlowDesigner context
 *
 * This hook bridges the FlowDesigner's Zustand store with the unified FieldAdapter interface,
 * enabling field components to read/write node configuration data and display validation errors.
 *
 * @example
 * ```tsx
 * const adapter = useFlowFieldAdapter<string>({ fieldKey: 'name', required: true });
 * return <TextField adapter={adapter} label="Node Name" />;
 * ```
 */
export function useFlowFieldAdapter<T>(props: FlowFieldAdapterProps<T>): FieldAdapter<T> {
  const { fieldKey, nodeId, required, disabled, readOnly } = props;
  const { nodes, selectedNodeId, updateNodeConfig, validationResult } = useFlowStore();

  const targetNodeId = nodeId || selectedNodeId;
  const node = nodes.find((n) => n.id === targetNodeId);

  const value = node?.data.config?.[fieldKey] as T;

  const setValue = useCallback(
    (newValue: T) => {
      if (!targetNodeId) return;
      updateNodeConfig(targetNodeId, { [fieldKey]: newValue });
    },
    [targetNodeId, fieldKey, updateNodeConfig],
  );

  // Find field validation error
  const fieldError = validationResult?.errors.find(
    (e) => e.nodeId === targetNodeId && e.fieldKey === fieldKey,
  );

  const adapter: FieldAdapter<T> = useMemo(
    () => ({
      value,
      setValue,
      error: fieldError?.message,
      disabled,
      required,
      readOnly,
    }),
    [value, setValue, fieldError?.message, disabled, required, readOnly],
  );

  return adapter;
}
