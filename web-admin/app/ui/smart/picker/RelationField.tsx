// web-admin/app/components/smart/picker/RelationField.tsx
/**
 * RelationField Component
 *
 * Enhanced reference field with bidirectional relation support.
 * Supports ONE_TO_ONE, ONE_TO_MANY, MANY_TO_ONE, MANY_TO_MANY.
 */

import React, { useState, useEffect, useCallback, useRef } from 'react';
import { cn } from '~/utils/cn';

/**
 * Relation type
 */
export type RelationType = 'one_to_one' | 'one_to_many' | 'many_to_one' | 'many_to_many';

/**
 * Option item for selection
 */
export interface RelationOption {
  value: string;
  label: string;
  [key: string]: unknown;
}

/**
 * Props for RelationField component
 */
export interface RelationFieldProps {
  /** Field name */
  name: string;
  /** Field label */
  label?: string;
  /** Current value (single ID or array of IDs) */
  value?: string | string[];
  /** Relation type */
  relationType: RelationType;
  /** Target model code */
  targetModelCode: string;
  /** Display field on target */
  displayField: string;
  /** Value field on target (default: 'id') */
  valueField?: string;
  /** Whether field is required */
  required?: boolean;
  /** Whether field is disabled */
  disabled?: boolean;
  /** Placeholder text */
  placeholder?: string;
  /** Maximum selections (for MANY relations) */
  maxSelections?: number;
  /** Fetch options function */
  fetchOptions: (search?: string) => Promise<RelationOption[]>;
  /** On change callback */
  onChange?: (value: string | string[] | null) => void;
  /** Error message */
  error?: string;
  /** Custom class name */
  className?: string;
}

export const RelationField: React.FC<RelationFieldProps> = ({
  name,
  label,
  value,
  relationType,
  targetModelCode,
  displayField,
  valueField = 'id',
  required = false,
  disabled = false,
  placeholder,
  maxSelections,
  fetchOptions,
  onChange,
  error,
  className,
}) => {
  const [options, setOptions] = useState<RelationOption[]>([]);
  const [loading, setLoading] = useState(false);
  const [isOpen, setIsOpen] = useState(false);
  const [search, setSearch] = useState('');

  // Determine if multi-select based on relation type
  const isMultiple = relationType === 'one_to_many' || relationType === 'many_to_many';

  // Normalize value to array for internal handling
  const normalizedValue = isMultiple
    ? Array.isArray(value)
      ? value
      : value
        ? [value]
        : []
    : Array.isArray(value)
      ? value[0]
      : value;

  // Load options
  const loadOptions = useCallback(
    async (searchTerm?: string) => {
      setLoading(true);
      try {
        const result = await fetchOptions(searchTerm);
        setOptions(result);
      } catch (err) {
        console.error('Failed to load options:', err);
      } finally {
        setLoading(false);
      }
    },
    [fetchOptions],
  );

  // Debounced search: load options after 300ms of no input
  const debounceTimer = useRef<ReturnType<typeof setTimeout>>(undefined);
  useEffect(() => {
    if (!isOpen) return;
    if (!search) {
      // Load immediately when search is empty (e.g. on open)
      loadOptions('');
      return;
    }
    debounceTimer.current = setTimeout(() => {
      loadOptions(search);
    }, 300);
    return () => clearTimeout(debounceTimer.current);
  }, [isOpen, search, loadOptions]);

  // Handle option selection
  const handleSelect = (optionValue: string) => {
    if (disabled) return;

    if (isMultiple) {
      const currentValues = Array.isArray(normalizedValue) ? normalizedValue : [];
      let newValues: string[];

      if (currentValues.includes(optionValue)) {
        newValues = currentValues.filter((v) => v !== optionValue);
      } else {
        if (maxSelections && currentValues.length >= maxSelections) {
          return;
        }
        newValues = [...currentValues, optionValue];
      }

      onChange?.(newValues.length > 0 ? newValues : null);
    } else {
      onChange?.(optionValue === normalizedValue ? null : optionValue);
      setIsOpen(false);
    }
  };

  // Handle clear
  const handleClear = () => {
    if (disabled) return;
    onChange?.(null);
  };

  // Get display label for a value
  const getDisplayLabel = (val: string): string => {
    const option = options.find((o) => o[valueField] === val);
    return option ? String(option[displayField]) : val;
  };

  // Render selected values
  const renderSelectedValues = () => {
    if (isMultiple) {
      const values = Array.isArray(normalizedValue) ? normalizedValue : [];
      if (values.length === 0) {
        return <span className="text-text-3">{placeholder || 'Select...'}</span>;
      }
      return (
        <div className="flex flex-wrap gap-1">
          {values.map((val) => (
            <span
              key={val}
              className="rounded-pill inline-flex items-center bg-blue-100 px-2 py-0.5 text-xs text-blue-800"
            >
              {getDisplayLabel(val)}
              {!disabled && (
                <button
                  type="button"
                  className="text-accent ml-1 hover:text-blue-800"
                  onClick={(e) => {
                    e.stopPropagation();
                    handleSelect(val);
                  }}
                >
                  ×
                </button>
              )}
            </span>
          ))}
        </div>
      );
    } else {
      if (!normalizedValue) {
        return <span className="text-text-3">{placeholder || 'Select...'}</span>;
      }
      return <span>{getDisplayLabel(normalizedValue as string)}</span>;
    }
  };

  return (
    <div className={cn('space-y-1', className)}>
      {label && (
        <label htmlFor={name} className="text-text-2 block text-sm font-medium">
          {label}
          {required && <span className="text-status-red ml-1">*</span>}
        </label>
      )}

      <div className="relative">
        <button
          type="button"
          id={name}
          className={cn(
            'rounded-control bg-panel w-full border px-3 py-2 text-left',
            'focus-visible:shadow-focus focus:outline-none',
            disabled && 'bg-hover cursor-not-allowed',
            error && 'border-status-red',
          )}
          onClick={() => !disabled && setIsOpen(!isOpen)}
          disabled={disabled}
        >
          {renderSelectedValues()}
        </button>

        {!disabled && (isMultiple ? (normalizedValue as string[]).length > 0 : normalizedValue) && (
          <button
            type="button"
            className="text-text-3 hover:text-text-2 absolute top-1/2 right-8 -translate-y-1/2"
            onClick={(e) => {
              e.stopPropagation();
              handleClear();
            }}
          >
            ×
          </button>
        )}

        <span className="text-text-3 absolute top-1/2 right-3 -translate-y-1/2">
          <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
          </svg>
        </span>

        {isOpen && (
          <div className="rounded-control bg-panel absolute z-50 mt-1 max-h-60 w-full overflow-y-auto border shadow-lg">
            <div className="border-b p-2">
              <input
                type="text"
                className="w-full rounded border px-2 py-1 text-sm"
                placeholder="Search..."
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                onClick={(e) => e.stopPropagation()}
              />
            </div>

            {loading ? (
              <div className="text-text-2 p-4 text-center">Loading...</div>
            ) : options.length === 0 ? (
              <div className="text-text-2 p-4 text-center">No options</div>
            ) : (
              options.map((option) => {
                const optValue = String(option[valueField]);
                const isSelected = isMultiple
                  ? (normalizedValue as string[]).includes(optValue)
                  : normalizedValue === optValue;

                return (
                  <button
                    key={optValue}
                    type="button"
                    className={cn(
                      'hover:bg-hover w-full px-3 py-2 text-left text-sm',
                      isSelected && 'bg-accent-weak text-accent',
                    )}
                    onClick={() => handleSelect(optValue)}
                  >
                    {isMultiple && (
                      <input type="checkbox" checked={isSelected} readOnly className="mr-2" />
                    )}
                    {String(option[displayField])}
                  </button>
                );
              })
            )}
          </div>
        )}
      </div>

      {error && <p className="text-status-red text-sm">{error}</p>}

      <p className="text-text-3 text-xs">
        {relationType.replace('_', ' ')} relation to {targetModelCode}
      </p>
    </div>
  );
};

export default RelationField;
