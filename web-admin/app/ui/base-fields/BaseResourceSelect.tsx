/**
 * BaseResourceSelect - Generic async resource select component.
 *
 * Fetches options from an API endpoint and renders a searchable dropdown.
 * Used for cross-designer references (page, dashboard, process, automation, command).
 */

import React, { useEffect, useState, useRef, useCallback } from 'react';
import { useSmartText } from '~/utils/i18n';

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
  const st = useSmartText();
  // Stable ref so doFetch does not list st as a dependency (avoids re-fetch
  // every render when the i18n context re-creates the st function reference).
  const stRef = useRef(st);
  stRef.current = st;

  const [options, setOptions] = useState<ResourceOption[]>([]);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [search, setSearch] = useState<string | null>(null);
  const [isOpen, setIsOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);

  const stableFetch = useCallback(fetchOptions, [fetchOptions]);

  const doFetch = useCallback(() => {
    setLoading(true);
    setLoadError(null);
    stableFetch()
      .then((opts) => {
        setOptions(opts);
      })
      .catch((err) => {
        console.error('[BaseResourceSelect] failed to load options', err);
        setLoadError(stRef.current('$i18n:common.options_load_failed', 'Failed to load options'));
      })
      .finally(() => setLoading(false));
  }, [stableFetch]);

  useEffect(() => {
    doFetch();
  }, [doFetch]);

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

  if (loadError) {
    return (
      <div className={`flex items-center gap-2 rounded-md border border-red-300 bg-red-50 px-3 py-2 text-sm text-red-600 ${className || ''}`}>
        <span className="flex-1">{loadError}</span>
        <button
          type="button"
          onClick={doFetch}
          className="shrink-0 rounded px-2 py-0.5 text-xs font-medium text-red-600 hover:bg-red-100 focus:outline-none"
        >
          {st('$i18n:common.retry', 'Retry')}
        </button>
      </div>
    );
  }

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
