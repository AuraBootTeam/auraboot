// web-admin/app/components/smart/picker/CascadeSelect.tsx
/**
 * CascadeSelect Component
 *
 * Multi-level cascading select for hierarchical data.
 */

import React, { useState, useEffect, useCallback } from 'react';
import { cn } from '~/utils/cn';

/**
 * Cascade option
 */
export interface CascadeOption {
  value: string;
  label: string;
  children?: CascadeOption[];
  isLeaf?: boolean;
}

/**
 * Props for CascadeSelect component
 */
export interface CascadeSelectProps {
  /** Field name */
  name: string;
  /** Field label */
  label?: string;
  /** Current value (array of values for each level) */
  value?: string[];
  /** Available options (tree structure) */
  options?: CascadeOption[];
  /** Async load children */
  loadChildren?: (parentValue: string | null, level: number) => Promise<CascadeOption[]>;
  /** Number of levels */
  levels?: number;
  /** Level labels */
  levelLabels?: string[];
  /** Whether to allow partial selection (not selecting all levels) */
  allowPartial?: boolean;
  /** Whether field is required */
  required?: boolean;
  /** Whether field is disabled */
  disabled?: boolean;
  /** Placeholder text */
  placeholder?: string;
  /** On change callback */
  onChange?: (value: string[]) => void;
  /** Error message */
  error?: string;
  /** Custom class name */
  className?: string;
}

/**
 * CascadeSelect - Multi-level cascading select
 */
export const CascadeSelect: React.FC<CascadeSelectProps> = ({
  name,
  label,
  value = [],
  options: staticOptions,
  loadChildren,
  levels = 3,
  levelLabels,
  allowPartial = false,
  required = false,
  disabled = false,
  placeholder,
  onChange,
  error,
  className,
}) => {
  // Options for each level
  const [levelOptions, setLevelOptions] = useState<CascadeOption[][]>([]);
  const [loading, setLoading] = useState<boolean[]>([]);

  /**
   * Load options for a level
   */
  const loadLevelOptions = useCallback(
    async (parentValue: string | null, level: number) => {
      if (loadChildren) {
        // Async loading
        setLoading((prev) => {
          const next = [...prev];
          next[level] = true;
          return next;
        });

        try {
          const options = await loadChildren(parentValue, level);
          setLevelOptions((prev) => {
            const next = [...prev];
            next[level] = options;
            // Clear subsequent levels
            for (let i = level + 1; i < levels; i++) {
              next[i] = [];
            }
            return next;
          });
        } finally {
          setLoading((prev) => {
            const next = [...prev];
            next[level] = false;
            return next;
          });
        }
      } else if (staticOptions) {
        // Static options - traverse tree
        let currentOptions = staticOptions;

        for (let i = 0; i <= level; i++) {
          if (i === level) {
            setLevelOptions((prev) => {
              const next = [...prev];
              next[level] = currentOptions;
              return next;
            });
          } else {
            const selectedValue = value[i];
            const selected = currentOptions.find((o) => o.value === selectedValue);
            if (selected?.children) {
              currentOptions = selected.children;
            } else {
              break;
            }
          }
        }
      }
    },
    [loadChildren, staticOptions, levels, value],
  );

  // Initialize first level
  useEffect(() => {
    loadLevelOptions(null, 0);
  }, [loadLevelOptions]);

  // Load subsequent levels when value changes
  useEffect(() => {
    for (let i = 1; i < levels; i++) {
      if (value[i - 1]) {
        loadLevelOptions(value[i - 1], i);
      }
    }
  }, [value, levels, loadLevelOptions]);

  /**
   * Handle level selection change
   */
  const handleLevelChange = (level: number, selectedValue: string) => {
    const newValue = [...value];

    // Set value at this level
    newValue[level] = selectedValue;

    // Clear subsequent levels
    for (let i = level + 1; i < levels; i++) {
      newValue[i] = '';
    }

    // Remove trailing empty values if not allowing partial
    if (!allowPartial) {
      while (newValue.length > 0 && !newValue[newValue.length - 1]) {
        newValue.pop();
      }
    }

    onChange?.(newValue);

    // Load next level options
    if (level < levels - 1 && selectedValue) {
      loadLevelOptions(selectedValue, level + 1);
    }
  };

  /**
   * Get label for a level
   */
  const getLevelLabel = (level: number): string => {
    if (levelLabels && levelLabels[level]) {
      return levelLabels[level];
    }
    return `Level ${level + 1}`;
  };

  /**
   * Get display value
   */
  const getDisplayValue = (): string => {
    const labels: string[] = [];

    for (let i = 0; i < value.length; i++) {
      if (!value[i]) break;
      const option = levelOptions[i]?.find((o) => o.value === value[i]);
      if (option) {
        labels.push(option.label);
      }
    }

    return labels.join(' / ') || placeholder || 'Select...';
  };

  return (
    <div className={cn('space-y-2', className)}>
      {/* Label */}
      {label && (
        <label className="block text-sm font-medium text-gray-700">
          {label}
          {required && <span className="ml-1 text-red-500">*</span>}
        </label>
      )}

      {/* Level selects */}
      <div className="flex flex-wrap gap-2">
        {Array.from({ length: levels }).map((_, level) => {
          const options = levelOptions[level] || [];
          const isDisabled = disabled || (level > 0 && !value[level - 1]);
          const isLoading = loading[level];

          return (
            <div key={level} className="min-w-[150px] flex-1">
              <label className="mb-1 block text-xs text-gray-500">{getLevelLabel(level)}</label>
              <select
                name={`${name}_level_${level}`}
                className={cn(
                  'w-full rounded-md border px-3 py-2 text-sm',
                  'focus:ring-2 focus:ring-blue-500 focus:outline-none',
                  isDisabled && 'cursor-not-allowed bg-gray-100',
                  error && level === levels - 1 && !allowPartial && 'border-red-500',
                )}
                value={value[level] || ''}
                onChange={(e) => handleLevelChange(level, e.target.value)}
                disabled={isDisabled}
              >
                <option value="">
                  {isLoading ? 'Loading...' : `Select ${getLevelLabel(level)}`}
                </option>
                {options.map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
              </select>
            </div>
          );
        })}
      </div>

      {/* Selected path display */}
      {value.length > 0 && value.some(Boolean) && (
        <div className="text-sm text-gray-600">Selected: {getDisplayValue()}</div>
      )}

      {/* Error */}
      {error && <p className="text-sm text-red-500">{error}</p>}
    </div>
  );
};

export default CascadeSelect;
