import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  SmartBarChart,
  SmartFunnelChart,
  SmartLineChart,
  SmartPieChart,
} from '~/framework/smart/components/charts';
import { useChartData } from '~/framework/smart/hooks/useChartData';
import type { ChartDataSource, FilterConfig } from '~/framework/smart/types/chart';
import type { ViewFilterConfig } from '~/framework/smart/types/savedView';
import { dimensionLabel } from '~/framework/smart/utils/chartLabels';
import { useI18n } from '~/contexts/I18nContext';
import type { ListFilterFieldMetadata } from '../ListPageContent';
import {
  analysisGroupFields,
  analysisMetricFields,
  viewFiltersToChartFilters,
} from './viewAnalysis';

type AnalysisChartType = 'bar' | 'line' | 'pie' | 'donut' | 'funnel';
type AnalysisAggregation = 'count' | 'sum' | 'avg';

export interface ViewAnalysisDrawerProps {
  open: boolean;
  onClose: () => void;
  modelCode: string;
  viewName?: string;
  keyword: string;
  filters: ViewFilterConfig[];
  fields: ListFilterFieldMetadata[];
  onDrillDown: (filters: FilterConfig[]) => void;
}

export function ViewAnalysisDrawer({
  open,
  onClose,
  modelCode,
  viewName,
  keyword,
  filters,
  fields,
  onDrillDown,
}: ViewAnalysisDrawerProps) {
  const { t } = useI18n();
  const tx = (key: string, fallback: string) => t(key, undefined, fallback);
  const panelRef = useRef<HTMLDivElement>(null);
  const groupFields = useMemo(() => analysisGroupFields(fields), [fields]);
  const metricFields = useMemo(() => analysisMetricFields(fields), [fields]);
  const [chartType, setChartType] = useState<AnalysisChartType>('bar');
  const [groupField, setGroupField] = useState('');
  const [aggregation, setAggregation] = useState<AnalysisAggregation>('count');
  const [metricField, setMetricField] = useState('');

  useEffect(() => {
    if (!groupField && groupFields.length) {
      setGroupField(
        groupFields.find((field) => field.dictCode)?.fieldCode ?? groupFields[0].fieldCode,
      );
    }
  }, [groupField, groupFields]);
  useEffect(() => {
    if (!metricField && metricFields.length) setMetricField(metricFields[0].fieldCode);
  }, [metricField, metricFields]);
  useEffect(() => {
    if (!open) return;
    panelRef.current?.focus();
    const handler = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [open, onClose]);

  const metricAlias = 'analysis_value';
  const chosenMetric = aggregation === 'count' ? 'pid' : metricField;
  const source = useMemo<ChartDataSource>(
    () => ({
      type: 'aggregate',
      modelCode,
      dimensions: groupField ? [groupField] : [],
      metrics: chosenMetric ? [{ field: chosenMetric, aggregation, alias: metricAlias }] : [],
      filters: viewFiltersToChartFilters(filters),
      keyword: keyword.trim() || undefined,
      orderBy: [{ field: metricAlias, direction: 'desc' }],
      limit: 50,
      dimensionDicts: Object.fromEntries(
        fields.filter((field) => field.dictCode).map((field) => [field.fieldCode, field.dictCode!]),
      ),
    }),
    [aggregation, chosenMetric, fields, filters, groupField, keyword, modelCode],
  );
  const enabled = open && Boolean(groupField && chosenMetric);
  const { data, loading, error, refetch } = useChartData({ dataSource: source, enabled });
  const staticSource = useMemo<ChartDataSource>(
    () => ({
      ...source,
      type: 'static',
      staticData: data?.rows ?? [],
      dimensions: data?.meta?.dimensions ?? [groupField],
    }),
    [data, groupField, source],
  );
  const selectedGroup = fields.find((field) => field.fieldCode === groupField);
  const selectedMetric = fields.find((field) => field.fieldCode === metricField);
  const metricLabel =
    aggregation === 'count'
      ? tx('common.view_analysis_count', 'Record count')
      : `${selectedMetric?.label ?? metricField} · ${aggregation === 'sum' ? tx('common.view_analysis_sum', 'Sum') : tx('common.view_analysis_avg', 'Average')}`;
  const chartProps = {
    dataSource: staticSource,
    metricLabels: { [metricAlias]: metricLabel },
    showLabel: true,
    chartOptions: { animation: false },
    drillDown: { enabled: true, action: 'filter' as const },
    onDrillDown,
    className: 'h-[340px] !border-0 !bg-transparent !p-0',
  };

  if (!open) return null;
  return (
    <>
      <div
        className="fixed inset-0 z-[1100] bg-slate-950/30 backdrop-blur-[1px]"
        onClick={onClose}
        aria-hidden
      />
      <div
        ref={panelRef}
        tabIndex={-1}
        role="dialog"
        aria-modal="true"
        aria-labelledby="view-analysis-title"
        data-testid="view-analysis-drawer"
        className="fixed inset-y-0 right-0 z-[1110] flex w-[min(96vw,62rem)] flex-col bg-slate-50 shadow-2xl outline-none"
      >
        <header className="border-b border-slate-200 bg-white px-5 py-4 sm:px-7">
          <div className="flex items-start justify-between gap-4">
            <div>
              <div className="mb-1 flex items-center gap-2">
                <span className="rounded-full bg-blue-50 px-2.5 py-1 text-[11px] font-semibold tracking-wide text-blue-700">
                  {tx('common.view_analysis_scope', 'CURRENT VIEW')}
                </span>
                <span className="truncate text-xs text-slate-500">
                  {viewName || tx('common.saved_view_default_view', 'Default view')}
                </span>
              </div>
              <h2 id="view-analysis-title" className="text-xl font-semibold text-slate-950">
                {tx('common.view_analysis', 'View analysis')}
              </h2>
              <p className="mt-1 text-sm text-slate-500">
                {tx(
                  'common.view_analysis_help',
                  'Explore all matching records and click a chart segment to return to the exact list.',
                )}
              </p>
            </div>
            <button
              type="button"
              onClick={onClose}
              aria-label={tx('common.close', 'Close')}
              data-testid="view-analysis-close"
              className="rounded-lg p-2 text-slate-400 hover:bg-slate-100 hover:text-slate-700"
            >
              ✕
            </button>
          </div>
          <div className="mt-4 flex flex-wrap gap-2 text-xs">
            <span className="rounded-full border border-slate-200 bg-slate-50 px-3 py-1.5 text-slate-600">
              {tx('common.view_analysis_filters', 'Filters')}: {filters.length}
            </span>
            {keyword.trim() && (
              <span className="max-w-full truncate rounded-full border border-blue-100 bg-blue-50 px-3 py-1.5 text-blue-700">
                {tx('common.search', 'Search')}: {keyword.trim()}
              </span>
            )}
          </div>
        </header>
        <div className="grid min-h-0 flex-1 grid-cols-1 overflow-y-auto lg:grid-cols-[17rem_minmax(0,1fr)] lg:overflow-hidden">
          <aside className="border-b border-slate-200 bg-white p-5 lg:overflow-y-auto lg:border-r lg:border-b-0">
            <div className="space-y-5">
              <label className="block text-sm font-medium text-slate-700">
                {tx('common.view_analysis_chart_type', 'Chart type')}
                <div className="mt-2 grid grid-cols-2 gap-2">
                  {(['bar', 'line', 'pie', 'donut', 'funnel'] as AnalysisChartType[]).map(
                    (type) => (
                      <button
                        key={type}
                        type="button"
                        onClick={() => setChartType(type)}
                        data-testid={`view-analysis-chart-${type}`}
                        aria-pressed={chartType === type}
                        className={`rounded-lg border px-3 py-2 text-left text-xs font-medium ${chartType === type ? 'border-blue-500 bg-blue-50 text-blue-700' : 'border-slate-200 bg-white text-slate-600 hover:bg-slate-50'}`}
                      >
                        {tx(
                          `common.view_analysis_chart_${type}`,
                          (
                            {
                              bar: 'Bar',
                              line: 'Line',
                              pie: 'Pie',
                              donut: 'Donut',
                              funnel: 'Funnel',
                            } as Record<string, string>
                          )[type],
                        )}
                      </button>
                    ),
                  )}
                </div>
              </label>
              <label className="block text-sm font-medium text-slate-700">
                {tx('common.view_analysis_group', 'Group by')}
                <select
                  value={groupField}
                  onChange={(event) => setGroupField(event.target.value)}
                  data-testid="view-analysis-group-field"
                  className="mt-2 h-10 w-full rounded-lg border border-slate-300 bg-white px-3 text-sm"
                >
                  {groupFields.map((field) => (
                    <option key={field.fieldCode} value={field.fieldCode}>
                      {field.label}
                    </option>
                  ))}
                </select>
              </label>
              <label className="block text-sm font-medium text-slate-700">
                {tx('common.view_analysis_metric', 'Measure')}
                <select
                  value={aggregation}
                  onChange={(event) => setAggregation(event.target.value as AnalysisAggregation)}
                  data-testid="view-analysis-aggregation"
                  className="mt-2 h-10 w-full rounded-lg border border-slate-300 bg-white px-3 text-sm"
                >
                  <option value="count">{tx('common.view_analysis_count', 'Record count')}</option>
                  <option value="sum" disabled={!metricFields.length}>
                    {tx('common.view_analysis_sum', 'Sum')}
                  </option>
                  <option value="avg" disabled={!metricFields.length}>
                    {tx('common.view_analysis_avg', 'Average')}
                  </option>
                </select>
              </label>
              {aggregation !== 'count' && (
                <label className="block text-sm font-medium text-slate-700">
                  {tx('common.view_analysis_numeric_field', 'Numeric field')}
                  <select
                    value={metricField}
                    onChange={(event) => setMetricField(event.target.value)}
                    data-testid="view-analysis-metric-field"
                    className="mt-2 h-10 w-full rounded-lg border border-slate-300 bg-white px-3 text-sm"
                  >
                    {metricFields.map((field) => (
                      <option key={field.fieldCode} value={field.fieldCode}>
                        {field.label}
                      </option>
                    ))}
                  </select>
                </label>
              )}
            </div>
          </aside>
          <main className="min-w-0 p-4 sm:p-7 lg:overflow-y-auto">
            <section className="rounded-2xl border border-slate-200 bg-white p-4 shadow-sm sm:p-6">
              <div className="mb-4 flex items-center justify-between gap-3">
                <div>
                  <h3 className="font-semibold text-slate-900">
                    {selectedGroup?.label ?? groupField} · {metricLabel}
                  </h3>
                  <p className="mt-1 text-xs text-slate-500">
                    {tx(
                      'common.view_analysis_drill_hint',
                      'Click any data point to filter the list.',
                    )}
                  </p>
                </div>
                <button
                  type="button"
                  onClick={() => refetch()}
                  data-testid="view-analysis-refresh"
                  className="rounded-lg border border-slate-200 px-3 py-2 text-xs font-medium text-slate-600 hover:bg-slate-50"
                >
                  {tx('common.view_analysis_refresh', 'Refresh')}
                </button>
              </div>
              {loading ? (
                <div
                  data-testid="view-analysis-loading"
                  className="flex h-[340px] items-center justify-center text-sm text-slate-500"
                >
                  {tx('common.loading', 'Loading...')}
                </div>
              ) : error ? (
                <div
                  role="alert"
                  data-testid="view-analysis-error"
                  className="flex h-[340px] flex-col items-center justify-center rounded-xl border border-red-100 bg-red-50 text-center"
                >
                  <p className="font-medium text-red-700">
                    {tx('common.view_analysis_failed', 'Analysis failed')}
                  </p>
                  <p className="mt-1 max-w-md text-xs text-red-500">{error.message}</p>
                  <button
                    type="button"
                    onClick={() => refetch()}
                    className="mt-4 rounded-lg bg-red-600 px-3 py-2 text-xs font-medium text-white"
                  >
                    {tx('common.retry', 'Retry')}
                  </button>
                </div>
              ) : !data?.rows?.length ? (
                <div
                  data-testid="view-analysis-empty"
                  className="flex h-[340px] flex-col items-center justify-center rounded-xl border border-dashed border-slate-200 bg-slate-50 text-center"
                >
                  <span className="text-3xl">⌁</span>
                  <p className="mt-3 font-medium text-slate-800">
                    {tx('common.view_analysis_empty', 'No matching data')}
                  </p>
                  <p className="mt-1 text-xs text-slate-500">
                    {tx(
                      'common.view_analysis_empty_help',
                      'Adjust the current list filters and try again.',
                    )}
                  </p>
                </div>
              ) : (
                <>
                  {chartType === 'bar' && <SmartBarChart {...chartProps} />}
                  {chartType === 'line' && <SmartLineChart {...chartProps} smooth />}
                  {chartType === 'pie' && <SmartPieChart {...chartProps} />}
                  {chartType === 'donut' && <SmartPieChart {...chartProps} ring />}
                  {chartType === 'funnel' && <SmartFunnelChart {...chartProps} />}
                </>
              )}
            </section>
            {data?.rows?.length ? (
              <section className="mt-4 overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm">
                <div className="border-b border-slate-100 px-5 py-3 text-sm font-semibold text-slate-800">
                  {tx('common.view_analysis_breakdown', 'Breakdown')}
                </div>
                <div className="max-h-64 overflow-auto">
                  <table className="w-full text-sm">
                    <thead className="sticky top-0 bg-slate-50 text-xs text-slate-500">
                      <tr>
                        <th className="px-5 py-2 text-left">
                          {selectedGroup?.label ?? groupField}
                        </th>
                        <th className="px-5 py-2 text-right">{metricLabel}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {data.rows.map((row, index) => (
                        <tr
                          key={`${String(row[groupField])}-${index}`}
                          className="group border-t border-slate-100 hover:bg-blue-50/50"
                        >
                          <td className="p-0 text-slate-700">
                            <button
                              type="button"
                              data-testid={`view-analysis-breakdown-${index}`}
                              onClick={() =>
                                onDrillDown([
                                  {
                                    field: groupField,
                                    operator: 'eq',
                                    value: row[groupField],
                                  },
                                ])
                              }
                              className="flex w-full items-center gap-2 px-5 py-2.5 text-left font-medium text-slate-700 outline-none hover:text-blue-700 focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-blue-500"
                              aria-label={`${tx('common.view_analysis_open_records', 'Open matching records')}: ${dimensionLabel(data.meta, groupField, row[groupField]) || tx('common.empty', 'Empty')}`}
                            >
                              <span>
                                {dimensionLabel(data.meta, groupField, row[groupField]) ||
                                  tx('common.empty', 'Empty')}
                              </span>
                              <span
                                aria-hidden
                                className="text-slate-300 transition-transform group-hover:translate-x-0.5 group-hover:text-blue-500"
                              >
                                →
                              </span>
                            </button>
                          </td>
                          <td
                            className="px-5 py-2.5 text-right font-semibold text-slate-900 tabular-nums"
                            data-testid={`view-analysis-value-${index}`}
                          >
                            {Number(row[metricAlias] ?? 0).toLocaleString()}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </section>
            ) : null}
          </main>
        </div>
      </div>
    </>
  );
}
