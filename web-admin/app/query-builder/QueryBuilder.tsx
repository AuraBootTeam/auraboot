/**
 * QueryBuilder — Visual query builder for data exploration
 *
 * Two-panel layout: left sidebar (model/field/filter/aggregation config),
 * right main area (result preview as table or chart).
 */

import { useState, useCallback } from 'react';
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

  // Query configuration
  const [modelCode, setModelCode] = useState('');
  const [selectedFields, setSelectedFields] = useState<string[]>([]);
  const [filters, setFilters] = useState<FilterCondition[]>([]);
  const [groupBy, setGroupBy] = useState<string[]>([]);
  const [aggregations, setAggregations] = useState<AggConfig[]>([]);
  const [sortField, setSortField] = useState('');
  const [sortOrder, setSortOrder] = useState<'asc' | 'desc'>('asc');
  const [limit, setLimit] = useState(500);

  // Available fields from selected model
  const [availableFields, setAvailableFields] = useState<FieldInfo[]>([]);

  // Results
  const [results, setResults] = useState<Record<string, unknown>[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>();

  const handleModelChange = useCallback((code: string) => {
    setModelCode(code);
    setSelectedFields([]);
    setFilters([]);
    setGroupBy([]);
    setAggregations([]);
    setResults([]);
    setError(undefined);
  }, []);

  const handleFieldsLoaded = useCallback((fields: FieldInfo[]) => {
    setAvailableFields(fields);
  }, []);

  const handleRun = useCallback(async () => {
    if (!modelCode) {
      showErrorToast('Please select a model first');
      return;
    }
    setLoading(true);
    setError(undefined);
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
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Query failed');
    } finally {
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

  const fieldCodes = availableFields.map((f) => f.code);

  return (
    <div className="flex h-full flex-col" data-testid="query-builder">
      {/* Header */}
      <div className="flex items-center justify-between border-b border-gray-200 bg-white px-6 py-4">
        <div>
          <h1 className="text-xl font-semibold text-gray-900">Query Builder</h1>
          <p className="mt-0.5 text-sm text-gray-500">Build and explore data queries visually</p>
        </div>
        <div className="flex items-center gap-3">
          <div className="flex items-center gap-2">
            <label className="text-sm text-gray-600">Limit:</label>
            <input
              type="number"
              value={limit}
              onChange={(e) =>
                setLimit(Math.min(5000, Math.max(1, parseInt(e.target.value) || 500)))
              }
              className="w-20 rounded-md border border-gray-300 px-2 py-1 text-sm"
              data-testid="qb-limit"
            />
          </div>
          <button
            type="button"
            onClick={handleRun}
            disabled={!modelCode || loading}
            data-testid="qb-run"
            className="inline-flex items-center gap-2 rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {loading ? (
              <span className="h-4 w-4 animate-spin rounded-full border-2 border-white/30 border-t-white" />
            ) : (
              <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M14.752 11.168l-3.197-2.132A1 1 0 0010 9.87v4.263a1 1 0 001.555.832l3.197-2.132a1 1 0 000-1.664z"
                />
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M21 12a9 9 0 11-18 0 9 9 0 0118 0z"
                />
              </svg>
            )}
            Run
          </button>
        </div>
      </div>

      {/* Main content */}
      <div className="flex flex-1 overflow-hidden">
        {/* Left sidebar */}
        <div className="w-72 space-y-4 overflow-y-auto border-r border-gray-200 bg-white p-4">
          <ModelSelector value={modelCode} onChange={handleModelChange} />

          {modelCode && (
            <>
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

              {/* Sort */}
              <div className="space-y-2">
                <h3 className="text-sm font-medium text-gray-700">Sort</h3>
                <div className="flex items-center gap-2">
                  <select
                    value={sortField}
                    onChange={(e) => setSortField(e.target.value)}
                    className="flex-1 rounded-md border border-gray-300 px-2 py-1 text-sm"
                  >
                    <option value="">No sort</option>
                    {fieldCodes.map((f) => (
                      <option key={f} value={f}>
                        {f}
                      </option>
                    ))}
                  </select>
                  <select
                    value={sortOrder}
                    onChange={(e) => setSortOrder(e.target.value as 'asc' | 'desc')}
                    className="w-20 rounded-md border border-gray-300 px-2 py-1 text-sm"
                  >
                    <option value="asc">ASC</option>
                    <option value="desc">DESC</option>
                  </select>
                </div>
              </div>
            </>
          )}
        </div>

        {/* Right content */}
        <div className="flex-1 overflow-auto bg-gray-50 p-6">
          <ResultPreview data={results} loading={loading} error={error} />
        </div>
      </div>
    </div>
  );
};

export default QueryBuilder;
