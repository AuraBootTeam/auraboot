import React, { useState, useEffect, useCallback, useRef } from 'react';
import type { CascadeOption } from './types';

interface CascadeSelectProps {
  value?: string[];
  onChange?: (value: string[], labels: string[]) => void;
  options?: CascadeOption[];
  loadData?: (parentValue: string | null) => Promise<CascadeOption[]>;
  placeholder?: string;
  disabled?: boolean;
  levels?: number;
  levelLabels?: string[];
  separator?: string;
  className?: string;
}

/**
 * CascadeSelect - multi-level cascade selection component.
 * Supports both static options and dynamic loading (province → city → district).
 *
 * @since 3.7.0
 */
export const CascadeSelect: React.FC<CascadeSelectProps> = ({
  value = [],
  onChange,
  options: staticOptions,
  loadData,
  placeholder = '请选择',
  disabled = false,
  levels = 3,
  levelLabels = ['一级', '二级', '三级'],
  separator = ' / ',
  className = '',
}) => {
  const [open, setOpen] = useState(false);
  const [levelOptions, setLevelOptions] = useState<CascadeOption[][]>([]);
  const [selected, setSelected] = useState<string[]>(value);
  const [loading, setLoading] = useState<number | null>(null);
  const containerRef = useRef<HTMLDivElement>(null);

  // Initialize with static options
  useEffect(() => {
    if (staticOptions) {
      setLevelOptions([staticOptions]);
    } else if (loadData) {
      loadLevel(null, 0);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [staticOptions]);

  // Sync external value
  useEffect(() => {
    setSelected(value);
  }, [value]);

  // Close on outside click
  useEffect(() => {
    if (!open) return;
    const handleClick = (e: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener('mousedown', handleClick);
    return () => document.removeEventListener('mousedown', handleClick);
  }, [open]);

  const loadLevel = useCallback(
    async (parentValue: string | null, level: number) => {
      if (!loadData) return;
      setLoading(level);
      try {
        const opts = await loadData(parentValue);
        setLevelOptions((prev) => {
          const next = prev.slice(0, level);
          next[level] = opts;
          return next;
        });
      } finally {
        setLoading(null);
      }
    },
    [loadData],
  );

  const handleSelect = useCallback(
    (level: number, option: CascadeOption) => {
      const newSelected = [...selected.slice(0, level), option.value];
      setSelected(newSelected);

      // Load next level
      if (level < levels - 1 && !option.isLeaf) {
        if (staticOptions) {
          // Find children in static tree
          const children = findChildren(staticOptions, newSelected);
          if (children && children.length > 0) {
            setLevelOptions((prev) => {
              const next = prev.slice(0, level + 1);
              next[level + 1] = children;
              return next;
            });
          } else {
            // Leaf reached
            finishSelection(newSelected);
          }
        } else if (loadData) {
          loadLevel(option.value, level + 1);
        }
      } else {
        // Final level or leaf
        finishSelection(newSelected);
      }
    },
    [selected, levels, staticOptions, loadData, loadLevel],
  );

  const finishSelection = useCallback(
    (values: string[]) => {
      setSelected(values);
      setOpen(false);
      if (onChange) {
        const labels = values.map((v, i) => {
          const opt = levelOptions[i]?.find((o) => o.value === v);
          return opt?.label ?? v;
        });
        onChange(values, labels);
      }
    },
    [onChange, levelOptions],
  );

  const displayValue =
    selected.length > 0
      ? selected
          .map((v, i) => {
            const opt = levelOptions[i]?.find((o) => o.value === v);
            return opt?.label ?? v;
          })
          .join(separator)
      : '';

  const handleClear = (e: React.MouseEvent) => {
    e.stopPropagation();
    setSelected([]);
    setLevelOptions((prev) => prev.slice(0, 1));
    onChange?.([], []);
  };

  return (
    <div ref={containerRef} className={`relative ${className}`}>
      {/* Trigger */}
      <div
        onClick={() => !disabled && setOpen(!open)}
        className={`flex w-full cursor-pointer items-center justify-between rounded-md border px-3 py-2 text-sm ${
          disabled
            ? 'cursor-not-allowed border-gray-200 bg-gray-100 text-gray-400'
            : 'border-gray-300 bg-white hover:border-blue-400'
        }`}
      >
        <span className={displayValue ? 'text-gray-700' : 'text-gray-400'}>
          {displayValue || placeholder}
        </span>
        <div className="flex items-center gap-1">
          {selected.length > 0 && !disabled && (
            <button onClick={handleClear} className="text-gray-400 hover:text-gray-600">
              <svg className="h-3.5 w-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M6 18L18 6M6 6l12 12"
                />
              </svg>
            </button>
          )}
          <svg
            className={`h-4 w-4 text-gray-400 transition-transform ${open ? 'rotate-180' : ''}`}
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
          </svg>
        </div>
      </div>

      {/* Dropdown panels */}
      {open && (
        <div className="absolute top-full left-0 z-50 mt-1 flex rounded-md border border-gray-200 bg-white shadow-lg">
          {levelOptions.map((opts, level) => (
            <div
              key={level}
              className="max-h-60 w-36 overflow-y-auto border-r border-gray-100 last:border-r-0"
            >
              <div className="border-b border-gray-100 px-2 py-1 text-[10px] font-medium text-gray-400">
                {levelLabels[level] ?? `Level ${level + 1}`}
              </div>
              {loading === level ? (
                <div className="flex items-center justify-center py-4">
                  <div className="h-4 w-4 animate-spin rounded-full border-2 border-blue-500 border-t-transparent" />
                </div>
              ) : (
                opts.map((opt) => (
                  <div
                    key={opt.value}
                    onClick={() => handleSelect(level, opt)}
                    className={`flex cursor-pointer items-center justify-between px-3 py-1.5 text-xs ${
                      selected[level] === opt.value
                        ? 'bg-blue-50 text-blue-600'
                        : 'text-gray-700 hover:bg-gray-50'
                    }`}
                  >
                    <span className="truncate">{opt.label}</span>
                    {!opt.isLeaf && level < levels - 1 && (
                      <svg
                        className="h-3 w-3 shrink-0 text-gray-400"
                        fill="none"
                        stroke="currentColor"
                        viewBox="0 0 24 24"
                      >
                        <path
                          strokeLinecap="round"
                          strokeLinejoin="round"
                          strokeWidth={2}
                          d="M9 5l7 7-7 7"
                        />
                      </svg>
                    )}
                  </div>
                ))
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
};

/**
 * Find children options in a static tree by following the selected path.
 */
function findChildren(options: CascadeOption[], path: string[]): CascadeOption[] | null {
  let current = options;
  for (const value of path) {
    const found = current.find((o) => o.value === value);
    if (!found || !found.children) return null;
    current = found.children;
  }
  return current;
}
