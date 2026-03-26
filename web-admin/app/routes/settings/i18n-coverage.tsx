/**
 * Translation Coverage Dashboard
 *
 * Displays per-locale translation coverage with progress bars and a table of
 * missing keys (capped at 50).  Accessible at /settings/i18n-coverage.
 */

import { useState, useEffect, useCallback } from 'react';
import { useToastContext } from '~/contexts/ToastContext';

type MetaArgs = Record<string, unknown>;

export function meta({}: MetaArgs) {
  return [
    { title: 'Translation Coverage' },
    { name: 'description', content: 'Monitor i18n translation completeness per locale' },
  ];
}

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

interface LocaleCoverage {
  locale: string;
  translated: number;
  missing: number;
  coverage: number;
}

interface MissingKeyEntry {
  key: string;
  missingIn: string[];
}

interface CoverageData {
  baseLocale: string;
  totalKeys: number;
  locales: LocaleCoverage[];
  missingKeys: MissingKeyEntry[];
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

const LOCALE_LABELS: Record<string, string> = {
  'zh-CN': '简体中文',
  'en-US': 'English (US)',
  'ja-JP': '日本語',
  'ko-KR': '한국어',
};

function localeLabel(code: string): string {
  return LOCALE_LABELS[code] ?? code;
}

function coverageColor(pct: number): string {
  if (pct >= 90) return 'bg-emerald-500';
  if (pct >= 60) return 'bg-amber-400';
  return 'bg-red-400';
}

function coverageTextColor(pct: number): string {
  if (pct >= 90) return 'text-emerald-600';
  if (pct >= 60) return 'text-amber-600';
  return 'text-red-600';
}

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export default function I18nCoveragePage() {
  const { showErrorToast } = useToastContext();
  const [data, setData] = useState<CoverageData | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);

  const fetchCoverage = useCallback(async (isRefresh = false) => {
    if (isRefresh) setRefreshing(true);
    else setLoading(true);
    try {
      const res = await fetch('/api/admin/i18n/coverage');
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const json = await res.json();
      setData(json.data);
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err);
      showErrorToast(`Failed to load coverage data: ${msg}`);
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [showErrorToast]);

  useEffect(() => {
    fetchCoverage();
  }, [fetchCoverage]);

  // --------------------------------------------------------------------------
  // Render: loading skeleton
  // --------------------------------------------------------------------------
  if (loading) {
    return (
      <div className="p-8 space-y-6 animate-pulse">
        <div className="h-8 w-64 bg-gray-200 rounded" />
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
          {[1, 2, 3, 4].map((i) => (
            <div key={i} className="h-28 bg-gray-200 rounded-xl" />
          ))}
        </div>
        <div className="h-48 bg-gray-200 rounded-xl" />
      </div>
    );
  }

  if (!data) {
    return (
      <div className="p-8 text-center text-gray-500">
        No coverage data available.
      </div>
    );
  }

  // --------------------------------------------------------------------------
  // Render: main content
  // --------------------------------------------------------------------------
  return (
    <div className="p-6 max-w-5xl mx-auto space-y-8">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold text-gray-900">Translation Coverage</h1>
          <p className="mt-1 text-sm text-gray-500">
            Base locale: <span className="font-medium">{localeLabel(data.baseLocale)}</span>
            &nbsp;·&nbsp;
            <span className="font-medium">{data.totalKeys.toLocaleString()}</span> total keys
          </p>
        </div>
        <button
          onClick={() => fetchCoverage(true)}
          disabled={refreshing}
          className="inline-flex items-center gap-2 px-4 py-2 text-sm font-medium bg-white border border-gray-300 rounded-lg shadow-sm hover:bg-gray-50 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
        >
          <svg
            className={`w-4 h-4 ${refreshing ? 'animate-spin' : ''}`}
            fill="none"
            viewBox="0 0 24 24"
            stroke="currentColor"
          >
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
          </svg>
          {refreshing ? 'Refreshing…' : 'Refresh'}
        </button>
      </div>

      {/* Coverage cards */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        {data.locales.map((loc) => (
          <div
            key={loc.locale}
            className="bg-white border border-gray-200 rounded-xl p-4 shadow-sm"
          >
            <div className="flex items-start justify-between mb-3">
              <div>
                <p className="text-sm font-medium text-gray-900">{localeLabel(loc.locale)}</p>
                <p className="text-xs text-gray-400">{loc.locale}</p>
              </div>
              <span className={`text-xl font-bold ${coverageTextColor(loc.coverage)}`}>
                {loc.coverage.toFixed(1)}%
              </span>
            </div>

            {/* Progress bar */}
            <div className="w-full bg-gray-100 rounded-full h-2 mb-3 overflow-hidden">
              <div
                className={`h-2 rounded-full transition-all duration-500 ${coverageColor(loc.coverage)}`}
                style={{ width: `${Math.min(100, loc.coverage)}%` }}
              />
            </div>

            <div className="flex justify-between text-xs text-gray-500">
              <span>{loc.translated.toLocaleString()} translated</span>
              {loc.missing > 0 && (
                <span className="text-red-500">{loc.missing.toLocaleString()} missing</span>
              )}
            </div>
          </div>
        ))}
      </div>

      {/* Missing keys table */}
      {data.missingKeys.length > 0 ? (
        <div className="bg-white border border-gray-200 rounded-xl shadow-sm overflow-hidden">
          <div className="px-5 py-4 border-b border-gray-100">
            <h2 className="text-base font-semibold text-gray-900">
              Missing Keys
              <span className="ml-2 text-xs font-normal text-gray-400">(showing up to {data.missingKeys.length})</span>
            </h2>
            <p className="mt-0.5 text-xs text-gray-500">
              Keys present in {localeLabel(data.baseLocale)} but absent in other locales
            </p>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="bg-gray-50 text-left text-xs font-medium text-gray-500 uppercase tracking-wide">
                  <th className="px-5 py-3 w-1/2">Key</th>
                  <th className="px-5 py-3">Missing in</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {data.missingKeys.map((entry) => (
                  <tr key={entry.key} className="hover:bg-gray-50 transition-colors">
                    <td className="px-5 py-3 font-mono text-xs text-gray-700 break-all">
                      {entry.key}
                    </td>
                    <td className="px-5 py-3">
                      <div className="flex flex-wrap gap-1">
                        {entry.missingIn.map((locale) => (
                          <span
                            key={locale}
                            className="inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium bg-red-50 text-red-700"
                          >
                            {locale}
                          </span>
                        ))}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      ) : (
        <div className="bg-white border border-gray-200 rounded-xl p-8 text-center text-gray-500 shadow-sm">
          <svg className="mx-auto h-10 w-10 text-emerald-400 mb-3" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M5 13l4 4L19 7" />
          </svg>
          <p className="font-medium text-gray-700">All keys are translated!</p>
          <p className="text-sm mt-1">No missing translations found across all locales.</p>
        </div>
      )}
    </div>
  );
}
