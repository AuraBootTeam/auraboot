/**
 * LineageNodePicker — browse and select a semantic node (model / metric / dimension).
 *
 * Loads the full semantic catalog via GET /api/semantic/meta and renders a
 * searchable, tabbed list.  On selection it calls `onChange(pid, nodeType)`.
 */

import { useEffect, useState, useDeferredValue } from 'react';
import {
  fetchSemanticMeta,
  type ModelMeta,
  type MetricMeta,
  type DimensionMeta,
} from '~/plugins/core-semantic/api/semanticApi';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

type Tab = 'MODEL' | 'METRIC' | 'DIMENSION';

interface FlatEntry {
  pid: string;
  code: string;
  label?: Record<string, string>;
  nodeType: Tab;
  parentCode?: string;
}

export interface LineageNodePickerProps {
  selectedPid?: string;
  onChange: (pid: string, nodeType: string) => void;
  t: (key: string, params?: Record<string, unknown>, fallback?: string) => string;
  /** Current locale — used to resolve localised labels. Default: 'zh-CN' */
  locale?: string;
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function resolveLabel(
  entry: Pick<FlatEntry, 'code' | 'label'>,
  locale: string,
): string {
  if (!entry.label) return entry.code;
  return entry.label[locale] ?? entry.label['en-US'] ?? entry.label['zh-CN'] ?? entry.code;
}

function flattenCatalog(models: ModelMeta[]): FlatEntry[] {
  const entries: FlatEntry[] = [];
  for (const m of models) {
    entries.push({ pid: m.pid, code: m.code, label: m.label, nodeType: 'MODEL' });
    for (const mt of m.metrics ?? []) {
      entries.push({
        pid: mt.pid,
        code: mt.code,
        label: mt.label,
        nodeType: 'METRIC',
        parentCode: m.code,
      });
    }
    for (const d of m.dimensions ?? []) {
      entries.push({
        pid: d.pid,
        code: d.code,
        label: d.label,
        nodeType: 'DIMENSION',
        parentCode: m.code,
      });
    }
  }
  return entries;
}

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

const TAB_LABELS: Record<Tab, { key: string; fallback: string }> = {
  MODEL: { key: 'semantic.lineage.nodeType.model', fallback: 'Models' },
  METRIC: { key: 'semantic.lineage.nodeType.metric', fallback: 'Metrics' },
  DIMENSION: { key: 'semantic.lineage.nodeType.dimension', fallback: 'Dimensions' },
};

const TAB_ORDER: Tab[] = ['MODEL', 'METRIC', 'DIMENSION'];

export function LineageNodePicker({
  selectedPid,
  onChange,
  t,
  locale = 'zh-CN',
}: LineageNodePickerProps) {
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [allEntries, setAllEntries] = useState<FlatEntry[]>([]);
  const [activeTab, setActiveTab] = useState<Tab>('MODEL');
  const [searchRaw, setSearchRaw] = useState('');
  const search = useDeferredValue(searchRaw.trim().toLowerCase());

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);

    fetchSemanticMeta()
      .then((meta) => {
        if (cancelled) return;
        setAllEntries(flattenCatalog(meta.models ?? []));
        setLoading(false);
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setError(err instanceof Error ? err.message : String(err));
        setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, []);

  const filtered = allEntries.filter((e) => {
    if (e.nodeType !== activeTab) return false;
    if (!search) return true;
    const label = resolveLabel(e, locale).toLowerCase();
    return label.includes(search) || e.code.toLowerCase().includes(search);
  });

  return (
    <div
      data-testid="lineage-node-picker"
      className="flex h-full flex-col border-r border-gray-200 bg-gray-50"
    >
      {/* Header */}
      <div className="border-b border-gray-200 p-3">
        <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-gray-500">
          {t('semantic.lineage.picker.label', undefined, 'Semantic Node')}
        </p>
        <input
          data-testid="lineage-picker-search"
          type="text"
          placeholder={t('semantic.lineage.picker.search', undefined, 'Search…')}
          value={searchRaw}
          onChange={(e) => setSearchRaw(e.target.value)}
          className="w-full rounded border border-gray-300 bg-white px-2 py-1.5 text-xs focus:border-blue-400 focus:outline-none"
        />
      </div>

      {/* Tabs */}
      <div className="flex border-b border-gray-200" role="tablist">
        {TAB_ORDER.map((tab) => (
          <button
            key={tab}
            role="tab"
            aria-selected={activeTab === tab}
            data-testid={`lineage-picker-tab-${tab.toLowerCase()}`}
            onClick={() => setActiveTab(tab)}
            className={`flex-1 border-b-2 px-1 py-2 text-xs font-medium transition-colors ${
              activeTab === tab
                ? 'border-blue-500 text-blue-700'
                : 'border-transparent text-gray-500 hover:text-gray-700'
            }`}
          >
            {t(TAB_LABELS[tab].key, undefined, TAB_LABELS[tab].fallback)}
          </button>
        ))}
      </div>

      {/* Content */}
      <div className="flex-1 overflow-y-auto" role="tabpanel">
        {loading && (
          <div
            data-testid="lineage-picker-loading"
            className="p-4 text-center text-xs text-gray-400"
          >
            {t('semantic.lineage.loading', undefined, 'Loading…')}
          </div>
        )}
        {error && (
          <div
            data-testid="lineage-picker-error"
            className="p-3 text-xs text-red-600"
          >
            {t('semantic.lineage.error', undefined, 'Error')}: {error}
          </div>
        )}
        {!loading && !error && filtered.length === 0 && (
          <div className="p-4 text-center text-xs text-gray-400">
            {t('semantic.lineage.picker.empty', undefined, 'No items found')}
          </div>
        )}
        {!loading &&
          !error &&
          filtered.map((entry) => {
            const label = resolveLabel(entry, locale);
            const isSelected = entry.pid === selectedPid;
            return (
              <button
                key={entry.pid}
                data-testid={`lineage-picker-item-${entry.pid}`}
                onClick={() => onChange(entry.pid, entry.nodeType)}
                className={`block w-full px-3 py-2 text-left text-xs transition-colors ${
                  isSelected
                    ? 'bg-blue-100 text-blue-800'
                    : 'text-gray-700 hover:bg-gray-100'
                }`}
              >
                <span className="block truncate font-medium">{label}</span>
                {entry.parentCode && (
                  <span className="block truncate text-gray-400">{entry.parentCode}</span>
                )}
              </button>
            );
          })}
      </div>
    </div>
  );
}
