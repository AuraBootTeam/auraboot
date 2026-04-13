// web-admin/app/components/smart/picker/CascadeSelect.tsx
/**
 * CascadeSelect Component
 *
 * Multi-level cascading select for hierarchical data.
 * Uses custom styled dropdowns matching the project's Select UI component.
 */

import React, { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import { cn } from '~/utils/cn';
import { useDictTree } from './useDictTree';

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
  /** Dict code for auto-loading tree options */
  dictCode?: string;
  /** Read-only mode */
  readOnly?: boolean;
}

/**
 * Individual dropdown panel for a single cascade level
 */
interface CascadeDropdownProps {
  name: string;
  level: number;
  levelLabel: string;
  options: CascadeOption[];
  selectedValue: string;
  isDisabled: boolean;
  isLoading: boolean;
  hasError: boolean;
  onChange: (value: string) => void;
}

const CascadeDropdown: React.FC<CascadeDropdownProps> = ({
  name,
  level,
  levelLabel,
  options,
  selectedValue,
  isDisabled,
  isLoading,
  hasError,
  onChange,
}) => {
  const [isOpen, setIsOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);

  // Close on click outside
  useEffect(() => {
    if (!isOpen) return;

    const handleClickOutside = (e: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        setIsOpen(false);
      }
    };

    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, [isOpen]);

  // Close on Escape
  useEffect(() => {
    if (!isOpen) return;

    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setIsOpen(false);
      }
    };

    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [isOpen]);

  const selectedOption = options.find((o) => o.value === selectedValue);

  const handleSelect = (value: string) => {
    onChange(value);
    setIsOpen(false);
  };

  const handleTriggerClick = () => {
    if (!isDisabled && !isLoading) {
      setIsOpen((prev) => !prev);
    }
  };

  return (
    <div ref={containerRef} className="relative min-w-[150px] flex-1">
      <label className="mb-1 block text-xs font-medium text-gray-500 dark:text-gray-400">
        {levelLabel}
      </label>

      {/* Trigger button */}
      <button
        type="button"
        data-testid={`cascade-trigger-${name}-${level}`}
        onClick={handleTriggerClick}
        disabled={isDisabled}
        className={cn(
          'flex h-9 w-full items-center justify-between rounded-md border bg-white px-3 py-2 text-sm shadow-sm',
          'focus:border-blue-500 focus:ring-2 focus:ring-blue-500 focus:outline-none',
          'disabled:cursor-not-allowed disabled:opacity-50',
          'dark:border-gray-600 dark:bg-gray-800 dark:text-gray-100',
          isDisabled
            ? 'cursor-not-allowed border-gray-200 bg-gray-50 dark:bg-gray-900'
            : 'border-gray-300 hover:border-gray-400',
          hasError && 'border-red-500 focus:ring-red-500',
          isOpen && 'border-blue-500 ring-2 ring-blue-500',
        )}
      >
        <span
          className={cn(
            'truncate text-left',
            !selectedOption && 'text-gray-400',
            isDisabled && 'text-gray-400',
          )}
        >
          {isLoading ? (
            <span className="flex items-center gap-1.5">
              <svg
                className="h-3.5 w-3.5 animate-spin text-gray-400"
                fill="none"
                viewBox="0 0 24 24"
              >
                <circle
                  className="opacity-25"
                  cx="12"
                  cy="12"
                  r="10"
                  stroke="currentColor"
                  strokeWidth="4"
                />
                <path
                  className="opacity-75"
                  fill="currentColor"
                  d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"
                />
              </svg>
              <span className="text-gray-400">Loading...</span>
            </span>
          ) : selectedOption ? (
            selectedOption.label
          ) : (
            `Select ${levelLabel}`
          )}
        </span>

        {/* Chevron icon */}
        <svg
          className={cn(
            'ml-2 h-4 w-4 shrink-0 text-gray-400 transition-transform duration-200',
            isOpen && 'rotate-180',
          )}
          fill="none"
          stroke="currentColor"
          viewBox="0 0 24 24"
        >
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
        </svg>
      </button>

      {/* Dropdown panel */}
      {isOpen && (
        <div
          data-testid={`cascade-dropdown-${name}-${level}`}
          className={cn(
            'absolute z-[100] mt-1 max-h-60 w-full min-w-[8rem] overflow-auto rounded-md border border-gray-200 bg-white p-1 shadow-md',
            'animate-in fade-in-0 zoom-in-95',
            'dark:border-gray-700 dark:bg-gray-800',
          )}
        >
          {options.length === 0 ? (
            <div className="px-2 py-4 text-center text-sm text-gray-400">No options</div>
          ) : (
            options.map((option) => {
              const isSelected = option.value === selectedValue;
              return (
                <button
                  key={option.value}
                  type="button"
                  data-testid={`cascade-option-${name}-${level}-${option.value}`}
                  onClick={() => handleSelect(option.value)}
                  className={cn(
                    'relative flex w-full cursor-default items-center rounded-sm py-1.5 pr-2 pl-8 text-left text-sm outline-none select-none',
                    'hover:bg-gray-100 hover:text-gray-900',
                    'dark:hover:bg-gray-700 dark:hover:text-gray-100',
                    isSelected && 'bg-gray-50 dark:bg-gray-700/50',
                  )}
                >
                  {/* Checkmark for selected item */}
                  <span className="absolute left-2 flex h-3.5 w-3.5 items-center justify-center">
                    {isSelected && (
                      <svg
                        className="h-4 w-4 text-blue-600 dark:text-blue-400"
                        fill="none"
                        stroke="currentColor"
                        viewBox="0 0 24 24"
                      >
                        <path
                          strokeLinecap="round"
                          strokeLinejoin="round"
                          strokeWidth={2}
                          d="M5 13l4 4L19 7"
                        />
                      </svg>
                    )}
                  </span>
                  <span className="truncate">{option.label}</span>
                </button>
              );
            })
          )}
        </div>
      )}
    </div>
  );
};

/**
 * CascadeSelect - Multi-level cascading select
 */
const EMPTY_VALUE: string[] = [];

export const CascadeSelect: React.FC<CascadeSelectProps> = ({
  name,
  label,
  value: valueProp,
  options: externalOptions,
  loadChildren,
  levels = 3,
  levelLabels,
  allowPartial = false,
  required = false,
  disabled = false,
  readOnly = false,
  placeholder,
  onChange,
  error,
  className,
  dictCode,
}) => {
  // Stabilize value reference to prevent infinite re-render loop
  const value = useMemo(() => valueProp || EMPTY_VALUE, [valueProp]);

  // Auto-load tree options from dict when dictCode is provided
  const dictTreeOptions = useDictTree(dictCode, !!externalOptions);
  const staticOptions = externalOptions || dictTreeOptions || undefined;

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
        <label className="block text-sm font-medium text-gray-700 dark:text-gray-300">
          {label}
          {required && <span className="ml-1 text-red-500">*</span>}
        </label>
      )}

      {/* Level dropdowns */}
      <div className="flex flex-wrap gap-2">
        {Array.from({ length: levels }).map((_, level) => {
          const options = levelOptions[level] || [];
          const isDisabled = disabled || readOnly || (level > 0 && !value[level - 1]);
          const isLoading = loading[level];

          return (
            <CascadeDropdown
              key={level}
              name={name}
              level={level}
              levelLabel={getLevelLabel(level)}
              options={options}
              selectedValue={value[level] || ''}
              isDisabled={isDisabled}
              isLoading={!!isLoading}
              hasError={!!error && level === levels - 1 && !allowPartial}
              onChange={(val) => handleLevelChange(level, val)}
            />
          );
        })}
      </div>

      {/* Selected path display */}
      {value.length > 0 && value.some(Boolean) && (
        <div className="text-sm text-gray-600 dark:text-gray-400">
          Selected: {getDisplayValue()}
        </div>
      )}

      {/* Error */}
      {error && <p className="text-sm text-red-500">{error}</p>}
    </div>
  );
};

export default CascadeSelect;
