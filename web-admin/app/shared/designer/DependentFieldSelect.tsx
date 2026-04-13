/**
 * DependentFieldSelect - field-select that cascades from modelCode in same node's config.
 */
import React, { useCallback, useEffect, useRef } from 'react';
import { BaseResourceSelect } from '~/components/base-fields';
import { fetchFieldOptions } from '~/services/resourceSelectService';
import { useFlowStore } from '~/flow-designer-sdk/store';
import type { FieldAdapter } from '~/components/field-adapter';

export interface DependentFieldSelectProps {
  adapter: FieldAdapter<unknown>;
  label?: string;
  helpText?: string;
  placeholder?: string;
  /** Filter fields by dataType (e.g., 'enum' for state fields) */
  filterByDataType?: string;
  /** Config key to read model code from (default: 'modelCode') */
  modelCodeKey?: string;
}

export function DependentFieldSelect({
  adapter,
  label,
  helpText,
  placeholder,
  filterByDataType,
  modelCodeKey = 'modelCode',
}: DependentFieldSelectProps) {
  const { nodes, selectedNodeId } = useFlowStore();
  const node = nodes.find((n) => n.id === selectedNodeId);
  const modelCode = (node?.data.config?.[modelCodeKey] as string) || '';
  const prevModelCodeRef = useRef(modelCode);

  // Clear field value when modelCode changes
  useEffect(() => {
    if (prevModelCodeRef.current && prevModelCodeRef.current !== modelCode) {
      adapter.setValue('' as any);
    }
    prevModelCodeRef.current = modelCode;
  }, [modelCode, adapter]);

  const fetchOptions = useCallback(async () => {
    const options = await fetchFieldOptions(modelCode);
    if (filterByDataType) {
      return options.filter(
        (o) => o.description?.toLowerCase() === filterByDataType.toLowerCase(),
      );
    }
    return options;
  }, [modelCode, filterByDataType]);

  if (!modelCode) {
    return (
      <div>
        {label && <label className="mb-1 block text-sm font-medium text-gray-700">{label}</label>}
        <div className="rounded-md border border-gray-200 bg-gray-50 px-3 py-2 text-sm text-gray-400">
          Please select a model first
        </div>
        {helpText && <p className="mt-1 text-xs text-gray-500">{helpText}</p>}
      </div>
    );
  }

  return (
    <div>
      {label && <label className="mb-1 block text-sm font-medium text-gray-700">{label}</label>}
      <BaseResourceSelect
        value={(adapter.value as string) || ''}
        onChange={adapter.setValue as any}
        fetchOptions={fetchOptions}
        placeholder={placeholder || 'Select field...'}
      />
      {adapter.error && <p className="mt-1 text-xs text-red-500">{adapter.error}</p>}
      {helpText && !adapter.error && <p className="mt-1 text-xs text-gray-500">{helpText}</p>}
    </div>
  );
}
