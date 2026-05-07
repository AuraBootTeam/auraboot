/**
 * QueryBuilder — Studio-density layout with sticky header, models rail,
 * 4 numbered step cards, and docked results panel.
 */

import { useState, useCallback, useEffect, useRef } from 'react';
import { useToastContext } from '~/contexts/ToastContext';
import { ResultHelper } from '~/utils/type';
import {
  queryBuilderService,
  type FilterCondition,
  type AggregationConfig as AggConfig,
  type FieldInfo,
} from './services/queryBuilderService';
import { ModelSelector } from './components/ModelSelector';
import { FieldSelector } from './components/FieldSelector';
import { FilterBuilder } from './components/FilterBuilder';
import { AggregationConfig } from './components/AggregationConfig';
import { ResultPreview } from './components/ResultPreview';

export const QueryBuilder: React.FC = () => {
  const { showErrorToast } = useToastContext();

  const [modelCode, setModelCode] = useState('');
  const [selectedFields, setSelectedFields] = useState<string[]>([]);
  const [filters, setFilters] = useState<FilterCondition[]>([]);
  const [groupBy, setGroupBy] = useState<string[]>([]);
  const [aggregations, setAggregations] = useState<AggConfig[]>([]);
  const [sortField, setSortField] = useState('');
  const [sortOrder, setSortOrder] = useState<'asc' | 'desc'>('asc');
  const [limit, setLimit] = useState(500);

  const [availableFields, setAvailableFields] = useState<FieldInfo[]>([]);
  const fieldCodes = availableFields.map((f) => f.code);

  const [results, setResults] = useState<Record<string, unknown>[]>([]);
  const [latencyMs, setLatencyMs] = useState<number | undefined>();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>();

  const searchInputRef = useRef<HTMLInputElement | null>(null);

  const handleModelChange = useCallback((code: string) => {
    setModelCode(code);
    setSelectedFields([]);
    setFilters([]);
    setGroupBy([]);
    setAggregations([]);
    setSortField('');
    setResults([]);
    setLatencyMs(undefined);
    setError(undefined);
  }, []);

  const handleFieldsLoaded = useCallback((fields: FieldInfo[]) => {
    setAvailableFields(fields);
  }, []);

  const handleReset = useCallback(() => {
    setSelectedFields([]);
    setFilters([]);
    setGroupBy([]);
    setAggregations([]);
    setSortField('');
    setLimit(500);
    setResults([]);
    setLatencyMs(undefined);
    setError(undefined);
  }, []);

  const handleRun = useCallback(async () => {
    if (!modelCode) {
      showErrorToast('Please select a model first');
      return;
    }
    setLoading(true);
    setError(undefined);
    const t0 = performance.now();
    try {
      const resp = await queryBuilderService.execute({
        modelCode,
        fields: selectedFields.length > 0 ? selectedFields : undefined,
        filters: filters.length > 0 ? filters : undefined,
        groupBy: groupBy.length > 0 ? groupBy : undefined,
        aggregations: aggregations.length > 0 ? aggregations : undefined,
        sortField: sortField || undefined,
        sortOrder: sortField ? sortOrder : undefined,
        limit,
      });
      if (ResultHelper.isSuccess(resp) && resp.data) {
        setResults(resp.data);
      } else {
        setError(resp.desc || 'Query failed');
        setResults([]);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Query failed');
      setResults([]);
    } finally {
      setLatencyMs(Math.round(performance.now() - t0));
      setLoading(false);
    }
  }, [
    modelCode,
    selectedFields,
    filters,
    groupBy,
    aggregations,
    sortField,
    sortOrder,
    limit,
    showErrorToast,
  ]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const meta = e.metaKey || e.ctrlKey;
      if (!meta) return;
      if (e.key === 'Enter') {
        e.preventDefault();
        handleRun();
      } else if (e.key === 'k' || e.key === 'K') {
        e.preventDefault();
        searchInputRef.current?.focus();
        searchInputRef.current?.select();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [handleRun]);

  const summaryParts = [
    modelCode || 'no model',
    `${selectedFields.length} fields`,
    `${filters.length} filters`,
  ];
  if (latencyMs != null) summaryParts.push(`${results.length} rows / ${latencyMs} ms`);

  return (
    <div className="flex h-full flex-col bg-slate-50" data-testid="query-builder">
      <header className="sticky top-0 z-10 flex shrink-0 items-center justify-between gap-4 border-b border-slate-200 bg-white px-6 py-3">
        <div className="min-w-0">
          <h1 className="text-lg font-semibold text-slate-900">Query Builder</h1>
          <p className="text-xs text-slate-500">Build and explore data queries visually</p>
        </div>
        <div
          data-testid="qb-summary"
          className="hidden min-w-0 flex-1 items-center justify-center md:flex"
        >
          <div className="inline-flex items-center gap-2 rounded-full border border-slate-200 bg-slate-50 px-3 py-1 text-xs text-slate-600">
            {summaryParts.map((part, i) => (
              <span key={i} className="flex items-center gap-2">
                {i > 0 && <span className="text-slate-300">·</span>}
                <span className={i === 0 && !modelCode ? 'text-slate-400 italic' : ''}>{part}</span>
              </span>
            ))}
          </div>
        </div>
        <div className="flex items-center gap-2">
          <button
            type="button"
            onClick={handleReset}
            disabled={!modelCode}
            className="rounded-lg border border-slate-200 px-3 py-1.5 text-sm text-slate-600 hover:bg-slate-50 disabled:opacity-40"
          >
            Reset
          </button>
          <button
            type="button"
            onClick={handleRun}
            disabled={!modelCode || loading}
            data-testid="qb-run"
            title="⌘ + Enter"
            className="inline-flex items-center gap-2 rounded-lg bg-blue-600 px-4 py-1.5 text-sm font-medium text-white shadow-sm transition-colors hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {loading ? (
              <span className="h-4 w-4 animate-spin rounded-full border-2 border-white/40 border-t-white" />
            ) : (
              <svg className="h-4 w-4" fill="currentColor" viewBox="0 0 20 20">
                <path d="M6.3 4.3a1 1 0 0 1 1.5-.86l9 5.7a1 1 0 0 1 0 1.72l-9 5.7A1 1 0 0 1 6.3 15.7V4.3z" />
              </svg>
            )}
            Run query
          </button>
        </div>
      </header>

      <div className="grid min-h-0 flex-1 grid-cols-[280px_minmax(0,1fr)]">
        <aside className="overflow-hidden border-r border-slate-200 bg-white p-4">
          <ModelSelector value={modelCode} onChange={handleModelChange} searchInputRef={searchInputRef} />
        </aside>

        <div className="grid min-h-0 grid-rows-[minmax(0,1fr)_340px]">
          <div className="overflow-y-auto p-6">
            {!modelCode ? (
              <div
                data-testid="qb-empty-onboarding"
                className="mx-auto mt-10 max-w-xl rounded-2xl border border-dashed border-slate-300 bg-white p-10 text-center shadow-sm"
              >
                <div className="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-full bg-blue-50 text-blue-600">
                  <svg className="h-6 w-6" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2m-6 9l2 2 4-4" />
                  </svg>
                </div>
                <h2 className="text-base font-semibold text-slate-800">Pick a model to start</h2>
                <p className="mt-1 text-sm text-slate-500">
                  Choose a data model on the left, select fields, optionally add filters, and click Run query.
                </p>
                <p className="mt-3 text-xs text-slate-400">
                  Tip: <kbd className="rounded border border-slate-200 bg-slate-50 px-1.5 py-0.5 font-mono">⌘ K</kbd>{' '}
                  to search models · <kbd className="rounded border border-slate-200 bg-slate-50 px-1.5 py-0.5 font-mono">⌘ ↵</kbd> to run
                </p>
              </div>
            ) : (
              <div className="mx-auto flex max-w-5xl flex-col gap-4">
                <FieldSelector
                  modelCode={modelCode}
                  selectedFields={selectedFields}
                  onChange={setSelectedFields}
                  onFieldsLoaded={handleFieldsLoaded}
                />
                <FilterBuilder filters={filters} availableFields={fieldCodes} onChange={setFilters} />
                <AggregationConfig
                  groupBy={groupBy}
                  aggregations={aggregations}
                  availableFields={fieldCodes}
                  onGroupByChange={setGroupBy}
                  onAggregationsChange={setAggregations}
                />

                <section
                  data-testid="qb-step-sort"
                  className="rounded-xl border border-slate-200 bg-white p-5 shadow-sm"
                >
                  <header className="mb-3 flex items-center gap-2">
                    <span className="inline-flex h-6 w-6 items-center justify-center rounded-full bg-blue-50 text-xs font-semibold text-blue-700 ring-1 ring-blue-200">
                      4
                    </span>
                    <h3 className="text-sm font-semibold text-slate-700">Sort &amp; Limit</h3>
                  </header>
                  <div className="flex flex-wrap items-center gap-3">
                    <label className="flex items-center gap-2 text-xs text-slate-600">
                      Sort by
                      <select
                        value={sortField}
                        onChange={(e) => setSortField(e.target.value)}
                        className="rounded-lg border border-slate-200 bg-white px-2.5 py-1.5 text-sm focus:border-blue-500 focus:outline-none"
                      >
                        <option value="">none</option>
                        {fieldCodes.map((f) => (
                          <option key={f} value={f}>
                            {f}
                          </option>
                        ))}
                      </select>
                    </label>
                    <div className="inline-flex rounded-lg border border-slate-200 bg-white p-0.5 text-xs">
                      {(['asc', 'desc'] as const).map((o) => (
                        <button
                          key={o}
                          type="button"
                          onClick={() => setSortOrder(o)}
                          disabled={!sortField}
                          className={`rounded-md px-2.5 py-1 font-medium transition-colors ${
                            sortOrder === o
                              ? 'bg-blue-600 text-white'
                              : 'text-slate-600 hover:bg-slate-50 disabled:opacity-40'
                          }`}
                        >
                          {o.toUpperCase()}
                        </button>
                      ))}
                    </div>
                    <label className="ml-auto flex items-center gap-2 text-xs text-slate-600">
                      Limit
                      <input
                        type="number"
                        min={1}
                        max={5000}
                        value={limit}
                        onChange={(e) =>
                          setLimit(Math.min(5000, Math.max(1, parseInt(e.target.value) || 500)))
                        }
                        className="w-24 rounded-lg border border-slate-200 bg-white px-2.5 py-1.5 text-sm focus:border-blue-500 focus:outline-none"
                        data-testid="qb-limit"
                      />
                      <span className="text-slate-400">rows</span>
                    </label>
                  </div>
                </section>
              </div>
            )}
          </div>

          <section className="min-h-0 border-t border-slate-200 bg-white">
            <ResultPreview
              data={results}
              loading={loading}
              error={error}
              latencyMs={latencyMs}
              fieldsCount={selectedFields.length || availableFields.length}
              modelCode={modelCode}
            />
          </section>
        </div>
      </div>
    </div>
  );
};

export default QueryBuilder;
