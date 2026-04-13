/**
 * DependentMultiSelect - tag-based multiselect that cascades from a parent field value
 * in the same flow node's config.
 *
 * Supports two modes:
 * - fields: load field list for a given modelCode (for watchFields)
 * - dict:   load dictionary items for a dictCode (for fromStates/toStates)
 */
import React, { useCallback, useEffect, useRef, useState } from 'react';
import type { FieldAdapter } from '~/components/field-adapter';
import { fetchDictOptions, fetchFieldOptions } from '~/services/resourceSelectService';
import { useFlowStore } from '~/flow-designer-sdk/store';
import type { ResourceOption } from '~/components/base-fields/BaseResourceSelect';

export interface DependentMultiSelectProps {
  adapter: FieldAdapter<unknown>;
  label?: string;
  helpText?: string;
  placeholder?: string;
  /** Config key in the same node to cascade from (default: 'modelCode') */
  dependsOnKey?: string;
  /** Source of options: 'fields' fetches model fields, 'dict' fetches dict items */
  optionSource?: 'fields' | 'dict';
  /** Required when optionSource === 'dict' */
  dictCode?: string;
}

export function DependentMultiSelect({
  adapter,
  label,
  helpText,
  placeholder,
  dependsOnKey = 'modelCode',
  optionSource = 'fields',
  dictCode,
}: DependentMultiSelectProps) {
  const { nodes, selectedNodeId } = useFlowStore();
  const node = nodes.find((n) => n.id === selectedNodeId);
  const parentValue = (node?.data.config?.[dependsOnKey] as string) || '';

  const [options, setOptions] = useState<ResourceOption[]>([]);
  const [loading, setLoading] = useState(false);
  const [search, setSearch] = useState('');
  const [isOpen, setIsOpen] = useState(false);

  const containerRef = useRef<HTMLDivElement>(null);
  const prevParentRef = useRef(parentValue);

  const selectedValues = Array.isArray(adapter.value) ? (adapter.value as string[]) : [];

  // Clear selection when parent value changes
  useEffect(() => {
    if (prevParentRef.current && prevParentRef.current !== parentValue) {
      adapter.setValue([] as any);
      setOptions([]);
    }
    prevParentRef.current = parentValue;
  }, [parentValue, adapter]);

  // Fetch options when parentValue changes
  const doFetch = useCallback(async () => {
    if (!parentValue) {
      setOptions([]);
      return;
    }
    setLoading(true);
    try {
      let fetched: ResourceOption[] = [];
      if (optionSource === 'dict') {
        const code = dictCode || parentValue;
        fetched = await fetchDictOptions(code);
      } else {
        fetched = await fetchFieldOptions(parentValue);
      }
      setOptions(fetched);
    } catch {
      setOptions([]);
    } finally {
      setLoading(false);
    }
  }, [parentValue, optionSource, dictCode]);

  useEffect(() => {
    doFetch();
  }, [doFetch]);

  // Close dropdown on outside click
  useEffect(() => {
    const handleClickOutside = (e: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        setIsOpen(false);
        setSearch('');
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  function toggleOption(value: string) {
    const next = selectedValues.includes(value)
      ? selectedValues.filter((v) => v !== value)
      : [...selectedValues, value];
    adapter.setValue(next as any);
  }

  function removeTag(value: string, e: React.MouseEvent) {
    e.stopPropagation();
    adapter.setValue(selectedValues.filter((v) => v !== value) as any);
  }

  const filteredOptions = search
    ? options.filter(
        (o) =>
          o.label.toLowerCase().includes(search.toLowerCase()) ||
          o.value.toLowerCase().includes(search.toLowerCase()),
      )
    : options;

  const parentLabel = dependsOnKey === 'modelCode' ? 'model' : dependsOnKey;

  if (!parentValue) {
    return (
      <div>
        {label && <label className="mb-1 block text-sm font-medium text-gray-700">{label}</label>}
        <div className="rounded-md border border-gray-200 bg-gray-50 px-3 py-2 text-sm text-gray-400">
          Please select a {parentLabel} first
        </div>
        {helpText && <p className="mt-1 text-xs text-gray-500">{helpText}</p>}
      </div>
    );
  }

  return (
    <div>
      {label && <label className="mb-1 block text-sm font-medium text-gray-700">{label}</label>}

      <div
        ref={containerRef}
        className="relative"
        onClick={() => {
          setIsOpen(true);
        }}
      >
        {/* Tag container + search input */}
        <div
          className={`flex min-h-[38px] flex-wrap items-center gap-1 rounded-md border px-2 py-1 text-sm cursor-text ${
            isOpen ? 'border-blue-500 ring-2 ring-blue-500' : 'border-gray-300'
          } ${adapter.error ? 'border-red-400' : ''}`}
        >
          {selectedValues.map((val) => {
            const opt = options.find((o) => o.value === val);
            const display = opt?.label || val;
            return (
              <span
                key={val}
                className="inline-flex items-center gap-1 rounded bg-blue-100 px-2 py-0.5 text-xs text-blue-700"
              >
                {display}
                <button
                  type="button"
                  onClick={(e) => removeTag(val, e)}
                  className="ml-0.5 text-blue-400 hover:text-blue-700 focus:outline-none"
                  aria-label={`Remove ${display}`}
                >
                  ×
                </button>
              </span>
            );
          })}

          <input
            type="text"
            value={search}
            onChange={(e) => {
              setSearch(e.target.value);
              setIsOpen(true);
            }}
            onFocus={() => setIsOpen(true)}
            placeholder={selectedValues.length === 0 ? (placeholder || 'Select...') : ''}
            className="min-w-[80px] flex-1 bg-transparent text-sm outline-none placeholder:text-gray-400"
          />
        </div>

        {/* Dropdown */}
        {isOpen && (
          <div className="absolute z-50 mt-1 max-h-48 w-full overflow-y-auto rounded-md border border-gray-200 bg-white shadow-lg">
            {loading && (
              <div className="px-3 py-2 text-sm text-gray-400">Loading...</div>
            )}
            {!loading && filteredOptions.length === 0 && (
              <div className="px-3 py-2 text-sm text-gray-400">No results</div>
            )}
            {!loading &&
              filteredOptions.map((option) => {
                const checked = selectedValues.includes(option.value);
                return (
                  <button
                    key={option.value}
                    type="button"
                    onClick={(e) => {
                      e.stopPropagation();
                      toggleOption(option.value);
                      setSearch('');
                    }}
                    className={`flex w-full items-center gap-2 px-3 py-2 text-left text-sm hover:bg-blue-50 ${
                      checked ? 'bg-blue-50 text-blue-700' : 'text-gray-700'
                    }`}
                  >
                    {/* Checkbox */}
                    <span
                      className={`flex h-4 w-4 shrink-0 items-center justify-center rounded border ${
                        checked
                          ? 'border-blue-500 bg-blue-500 text-white'
                          : 'border-gray-300 bg-white'
                      }`}
                    >
                      {checked && (
                        <svg
                          className="h-3 w-3"
                          viewBox="0 0 12 12"
                          fill="none"
                          stroke="currentColor"
                          strokeWidth="2"
                        >
                          <polyline points="2,6 5,9 10,3" />
                        </svg>
                      )}
                    </span>
                    <div className="min-w-0 flex-1">
                      <div className="truncate font-medium">{option.label}</div>
                      {option.description && (
                        <div className="truncate text-xs text-gray-400">{option.description}</div>
                      )}
                    </div>
                  </button>
                );
              })}
          </div>
        )}
      </div>

      {adapter.error && <p className="mt-1 text-xs text-red-500">{adapter.error}</p>}
      {helpText && !adapter.error && <p className="mt-1 text-xs text-gray-500">{helpText}</p>}
    </div>
  );
}
