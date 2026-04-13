import { useMemo, useState } from 'react';
import {
  TIMEZONE_ENTRIES,
  getTimezoneLabel,
  searchTimezones,
  type TimezoneEntry,
} from '../utils/timezoneNames';

/**
 * Searchable timezone selector with city-friendly display names.
 *
 * - Shows "北京/上海 (UTC+8)" instead of raw "Asia/Shanghai"
 * - Supports searching in Chinese and English (e.g. "北京" → Asia/Shanghai)
 * - Falls back gracefully for timezones not in the curated list
 * - The selected value (onChange / value) is always the IANA string
 */

interface TimezoneSelectProps {
  value: string;
  onChange: (tz: string) => void;
  disabled?: boolean;
  'data-testid'?: string;
}

/**
 * Build display options: curated list first, then any remaining system timezones not in the list.
 * The curated list covers ~50 common zones; the full Intl list is 400+ entries.
 */
function buildAllOptions(): Array<{ value: string; label: string; isKnown: boolean }> {
  const curatedIana = new Set(TIMEZONE_ENTRIES.map((e) => e.iana));

  const curated = TIMEZONE_ENTRIES.map((e) => ({
    value: e.iana,
    label: `${e.displayName} (${e.utcOffset})`,
    isKnown: true,
  }));

  // Append system timezones not in the curated list as a fallback tail
  let systemZones: string[] = [];
  try {
    systemZones = Intl.supportedValuesOf('timeZone');
  } catch {
    // Older engines — curated list only
  }

  const fallback = systemZones
    .filter((tz) => !curatedIana.has(tz))
    .map((tz) => ({
      value: tz,
      label: getTimezoneLabel(tz),
      isKnown: false,
    }));

  return [...curated, ...fallback];
}

let cachedOptions: Array<{ value: string; label: string; isKnown: boolean }> | null = null;
function getAllOptions() {
  if (!cachedOptions) cachedOptions = buildAllOptions();
  return cachedOptions;
}

export default function TimezoneSelect({
  value,
  onChange,
  disabled,
  'data-testid': testId,
}: TimezoneSelectProps) {
  const [search, setSearch] = useState('');
  const [isOpen, setIsOpen] = useState(false);

  const allOptions = useMemo(() => getAllOptions(), []);

  const filtered = useMemo(() => {
    if (!search.trim()) return allOptions;
    const q = search.toLowerCase();

    // First: match curated entries (supports Chinese + search terms)
    const curatedMatches = searchTimezones(search).map((e: TimezoneEntry) => ({
      value: e.iana,
      label: `${e.displayName} (${e.utcOffset})`,
      isKnown: true,
    }));
    const curatedIana = new Set(curatedMatches.map((o) => o.value));

    // Then: match non-curated system timezones by IANA string
    const fallbackMatches = allOptions.filter(
      (o) => !o.isKnown && !curatedIana.has(o.value) && o.value.toLowerCase().includes(q),
    );

    return [...curatedMatches, ...fallbackMatches];
  }, [allOptions, search]);

  const selectedLabel = useMemo(() => {
    if (!value) return '';
    return getTimezoneLabel(value);
  }, [value]);

  return (
    <div className="relative" data-testid={testId}>
      {/* Trigger button */}
      <button
        type="button"
        onClick={() => !disabled && setIsOpen(!isOpen)}
        disabled={disabled}
        className="flex w-full items-center justify-between rounded-lg border border-gray-300 bg-white px-3 py-2 text-left focus:border-transparent focus:ring-2 focus:ring-blue-500 disabled:cursor-not-allowed disabled:bg-gray-100"
        data-testid={testId ? `${testId}-trigger` : undefined}
      >
        <span className="truncate text-sm">{selectedLabel || value}</span>
        <svg
          className={`h-4 w-4 flex-shrink-0 text-gray-400 transition-transform ${isOpen ? 'rotate-180' : ''}`}
          fill="none"
          stroke="currentColor"
          viewBox="0 0 24 24"
        >
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
        </svg>
      </button>

      {/* Dropdown */}
      {isOpen && (
        <>
          {/* Backdrop */}
          <div className="fixed inset-0 z-10" onClick={() => setIsOpen(false)} />
          <div className="absolute z-20 mt-1 flex max-h-72 w-full flex-col rounded-lg border border-gray-200 bg-white shadow-lg">
            {/* Search input */}
            <div className="border-b p-2">
              <input
                type="text"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder="搜索时区（北京、Tokyo、UTC...）"
                autoFocus
                className="w-full rounded border border-gray-300 px-3 py-1.5 text-sm focus:border-transparent focus:ring-2 focus:ring-blue-500"
                data-testid={testId ? `${testId}-search` : undefined}
              />
            </div>
            {/* Options */}
            <ul className="flex-1 overflow-y-auto">
              {filtered.length === 0 ? (
                <li className="px-3 py-2 text-sm text-gray-500">未找到匹配的时区</li>
              ) : (
                filtered.map((tz) => (
                  <li key={tz.value}>
                    <button
                      type="button"
                      onClick={() => {
                        onChange(tz.value);
                        setIsOpen(false);
                        setSearch('');
                      }}
                      className={`w-full px-3 py-1.5 text-left text-sm hover:bg-blue-50 ${tz.value === value ? 'bg-blue-50 font-medium text-blue-700' : 'text-gray-700'}`}
                      data-testid={testId ? `${testId}-option-${tz.value}` : undefined}
                    >
                      {tz.label}
                    </button>
                  </li>
                ))
              )}
            </ul>
          </div>
        </>
      )}
    </div>
  );
}
