/**
 * BaseResourceSelect - Generic async resource select component.
 *
 * Fetches options from an API endpoint and renders a searchable dropdown.
 * Used for cross-designer references (page, dashboard, process, automation, command).
 */

import React, { useEffect, useState, useRef, useCallback } from 'react';

export interface ResourceOption {
  label: string;
  value: string;
  description?: string;
}

export interface BaseResourceSelectProps {
  value: string;
  onChange: (value: string) => void;
  fetchOptions: () => Promise<ResourceOption[]>;
  placeholder?: string;
  disabled?: boolean;
  className?: string;
}

export function BaseResourceSelect({
  value,
  onChange,
  fetchOptions,
  placeholder,
  disabled,
  className,
}: BaseResourceSelectProps) {
  const [options, setOptions] = useState<ResourceOption[]>([]);
  const [loading, setLoading] = useState(false);
  const [search, setSearch] = useState<string | null>(null);
  const [isOpen, setIsOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);

  const stableFetch = useCallback(fetchOptions, [fetchOptions]);

  useEffect(() => {
    setLoading(true);
    stableFetch()
      .then(setOptions)
      .catch(() => setOptions([]))
      .finally(() => setLoading(false));
  }, [stableFetch]);

  // Close dropdown on outside click
  useEffect(() => {
    const handleClickOutside = (e: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        setIsOpen(false);
        setSearch(null);
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  const displayValue =
    search !== null ? search : options.find((o) => o.value === value)?.label || value || '';

  const filtered = search
    ? options.filter(
        (o) =>
          o.label.toLowerCase().includes(search.toLowerCase()) ||
          o.value.toLowerCase().includes(search.toLowerCase()),
      )
    : options;

  return (
    <div ref={containerRef} className={`relative ${className || ''}`}>
      <input
        type="text"
        value={displayValue}
        onChange={(e) => {
          setSearch(e.target.value);
          setIsOpen(true);
        }}
        onFocus={() => {
          setSearch('');
          setIsOpen(true);
        }}
        placeholder={placeholder || 'Select...'}
        disabled={disabled}
        className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:ring-2 focus:ring-blue-500 disabled:bg-gray-100"
      />
      {isOpen && (
        <div className="absolute z-50 mt-1 max-h-48 w-full overflow-y-auto rounded-md border border-gray-200 bg-white shadow-lg">
          {loading && <div className="px-3 py-2 text-sm text-gray-400">Loading...</div>}
          {!loading && filtered.length === 0 && (
            <div className="px-3 py-2 text-sm text-gray-400">No results</div>
          )}
          {filtered.map((option) => (
            <button
              key={option.value}
              type="button"
              onClick={() => {
                onChange(option.value);
                setSearch(null);
                setIsOpen(false);
              }}
              className={`w-full px-3 py-2 text-left text-sm hover:bg-blue-50 ${
                option.value === value ? 'bg-blue-50 text-blue-700' : 'text-gray-700'
              }`}
            >
              <div className="font-medium">{option.label}</div>
              {option.description && (
                <div className="text-xs text-gray-400">{option.description}</div>
              )}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

export default BaseResourceSelect;
