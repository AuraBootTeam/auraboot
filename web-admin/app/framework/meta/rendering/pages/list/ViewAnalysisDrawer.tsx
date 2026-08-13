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
        className="fixed inset-0 z-[1100] bg-black/30 backdrop-blur-[1px]"
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
        className="bg-subtle fixed inset-y-0 right-0 z-[1110] flex w-[min(96vw,62rem)] flex-col shadow-2xl outline-none"
      >
        <header className="border-border bg-panel border-b px-5 py-4 sm:px-7">
          <div className="flex items-start justify-between gap-4">
            <div>
              <div className="mb-1 flex items-center gap-2">
                <span className="bg-accent-weak text-accent rounded-full px-2.5 py-1 text-[11px] font-semibold tracking-wide">
                  {tx('common.view_analysis_scope', 'CURRENT VIEW')}
                </span>
                <span className="text-text-3 truncate text-xs">
                  {viewName || tx('common.saved_view_default_view', 'Default view')}
                </span>
              </div>
              <h2 id="view-analysis-title" className="text-text text-xl font-semibold">
                {tx('common.view_analysis', 'View analysis')}
              </h2>
              <p className="text-text-3 mt-1 text-sm">
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
              className="text-text-3 hover:bg-hover hover:text-text-2 rounded-lg p-2"
            >
              ✕
            </button>
          </div>
          <div className="mt-4 flex flex-wrap gap-2 text-xs">
            <span className="border-border bg-subtle text-text-2 rounded-full border px-3 py-1.5">
              {tx('common.view_analysis_filters', 'Filters')}: {filters.length}
            </span>
            {keyword.trim() && (
              <span className="border-accent bg-accent-weak text-accent max-w-full truncate rounded-full border px-3 py-1.5">
                {tx('common.search', 'Search')}: {keyword.trim()}
              </span>
            )}
          </div>
        </header>
        <div className="grid min-h-0 flex-1 grid-cols-1 overflow-y-auto lg:grid-cols-[17rem_minmax(0,1fr)] lg:overflow-hidden">
          <aside className="border-border bg-panel border-b p-5 lg:overflow-y-auto lg:border-r lg:border-b-0">
            <div className="space-y-5">
              <label className="text-text-2 block text-sm font-medium">
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
                        className={`rounded-lg border px-3 py-2 text-left text-xs font-medium ${chartType === type ? 'border-accent bg-accent-weak text-accent' : 'border-border bg-panel text-text-2 hover:bg-hover'}`}
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
              <label className="text-text-2 block text-sm font-medium">
                {tx('common.view_analysis_group', 'Group by')}
                <select
                  value={groupField}
                  onChange={(event) => setGroupField(event.target.value)}
                  data-testid="view-analysis-group-field"
                  className="border-border-strong bg-panel text-text mt-2 h-10 w-full rounded-lg border px-3 text-sm"
                >
                  {groupFields.map((field) => (
                    <option key={field.fieldCode} value={field.fieldCode}>
                      {field.label}
                    </option>
                  ))}
                </select>
              </label>
              <label className="text-text-2 block text-sm font-medium">
                {tx('common.view_analysis_metric', 'Measure')}
                <select
                  value={aggregation}
                  onChange={(event) => setAggregation(event.target.value as AnalysisAggregation)}
                  data-testid="view-analysis-aggregation"
                  className="border-border-strong bg-panel text-text mt-2 h-10 w-full rounded-lg border px-3 text-sm"
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
                <label className="text-text-2 block text-sm font-medium">
                  {tx('common.view_analysis_numeric_field', 'Numeric field')}
                  <select
                    value={metricField}
                    onChange={(event) => setMetricField(event.target.value)}
                    data-testid="view-analysis-metric-field"
                    className="border-border-strong bg-panel text-text mt-2 h-10 w-full rounded-lg border px-3 text-sm"
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
            <section className="border-border bg-panel rounded-2xl border p-4 shadow-sm sm:p-6">
              <div className="mb-4 flex items-center justify-between gap-3">
                <div>
                  <h3 className="text-text font-semibold">
                    {selectedGroup?.label ?? groupField} · {metricLabel}
                  </h3>
                  <p className="text-text-3 mt-1 text-xs">
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
                  className="border-border text-text-2 hover:bg-hover rounded-lg border px-3 py-2 text-xs font-medium"
                >
                  {tx('common.view_analysis_refresh', 'Refresh')}
                </button>
              </div>
              {loading ? (
                <div
                  data-testid="view-analysis-loading"
                  className="text-text-3 flex h-[340px] items-center justify-center text-sm"
                >
                  {tx('common.loading', 'Loading...')}
                </div>
              ) : error ? (
                <div
                  role="alert"
                  data-testid="view-analysis-error"
                  className="border-status-red bg-status-red-bg flex h-[340px] flex-col items-center justify-center rounded-xl border text-center"
                >
                  <p className="text-status-red font-medium">
                    {tx('common.view_analysis_failed', 'Analysis failed')}
                  </p>
                  <p className="text-status-red mt-1 max-w-md text-xs">{error.message}</p>
                  <button
                    type="button"
                    onClick={() => refetch()}
                    className="bg-status-red mt-4 rounded-lg px-3 py-2 text-xs font-medium text-white"
                  >
                    {tx('common.retry', 'Retry')}
                  </button>
                </div>
              ) : !data?.rows?.length ? (
                <div
                  data-testid="view-analysis-empty"
                  className="border-border bg-subtle flex h-[340px] flex-col items-center justify-center rounded-xl border border-dashed text-center"
                >
                  <span className="text-3xl">⌁</span>
                  <p className="text-text-2 mt-3 font-medium">
                    {tx('common.view_analysis_empty', 'No matching data')}
                  </p>
                  <p className="text-text-3 mt-1 text-xs">
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
              <section className="border-border bg-panel mt-4 overflow-hidden rounded-2xl border shadow-sm">
                <div className="border-border text-text-2 border-b px-5 py-3 text-sm font-semibold">
                  {tx('common.view_analysis_breakdown', 'Breakdown')}
                </div>
                <div className="max-h-64 overflow-auto">
                  <table className="w-full text-sm">
                    <thead className="bg-subtle text-text-3 sticky top-0 text-xs">
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
                          className="border-border hover:bg-hover group border-t"
                        >
                          <td className="text-text-2 p-0">
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
                              className="text-text-2 hover:text-accent focus-visible:ring-accent flex w-full items-center gap-2 px-5 py-2.5 text-left font-medium outline-none focus-visible:ring-2 focus-visible:ring-inset"
                              aria-label={`${tx('common.view_analysis_open_records', 'Open matching records')}: ${dimensionLabel(data.meta, groupField, row[groupField]) || tx('common.empty', 'Empty')}`}
                            >
                              <span>
                                {dimensionLabel(data.meta, groupField, row[groupField]) ||
                                  tx('common.empty', 'Empty')}
                              </span>
                              <span
                                aria-hidden
                                className="text-text-3 group-hover:text-accent transition-transform group-hover:translate-x-0.5"
                              >
                                →
                              </span>
                            </button>
                          </td>
                          <td
                            className="text-text px-5 py-2.5 text-right font-semibold tabular-nums"
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
