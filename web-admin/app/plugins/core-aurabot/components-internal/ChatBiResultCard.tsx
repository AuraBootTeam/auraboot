/**
 * ChatBiResultCard
 *
 * Renders AuraBot SQL/query results inline in chat —
 * interpretation text + ECharts visualization + optional SQL toggle.
 *
 * Uses ECharts (same library as Dashboard) for bar, pie, line charts.
 * Falls back to a styled table for "table" type or unknown types.
 *
 * @since 3.2.0
 */

import { useState, useMemo, Suspense } from 'react';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
} from '~/ui/ui/dialog';
import { narrativeWithoutTables } from './analysisReportDraft';
import { toast } from 'sonner';
import { useSmartText } from '~/utils/i18n';
import { resolveFieldLabel } from '~/framework/meta/utils/i18nResolver';
import { reportDesignerService } from '~/plugins/core-designer/components/report-designer/services/reportDesignerService';
import {
  createEmptyReport,
  type ReportColumn,
} from '~/plugins/core-designer/components/report-designer/types';
import { useAnalyticsResultView } from './useAnalyticsResultView';
import { AnalyticsSuggestions } from './AnalyticsSuggestions';
import { useI18n } from '~/contexts/I18nContext';
import { dashboardService } from '~/plugins/core-dashboard/services/dashboardService';
import { getChartComponent, normalizeChartType } from '~/framework/smart/charts/SharedChartFactory';
import type { ChartDataSource } from '~/framework/smart/types/chart';

interface ChatBiMetric {
  field: string;
  aggregation: string;
  alias?: string;
}

export interface ChatBiResult {
  analysisId?: string;
  historyRefreshed?: boolean;
  dataSource?: ChartDataSource;
  interpretation?: string;
  modelCode?: string;
  chartType?: string;
  chartConfig?: Record<string, unknown>;
  columns?: string[];
  records?: Record<string, unknown>[];
  // Aggregate spec carried by the chat-bi tool — lets an ad-hoc chart be persisted as a
  // dashboard widget ("save as dashboard" bridge) without re-deriving it.
  dimensions?: string[];
  metrics?: ChatBiMetric[];
  total?: number;
  sql?: string;
  truncated?: boolean;
}

// Map a chat-bi chartType to the matching dashboard widget type.
const CHART_TYPE_TO_WIDGET: Record<string, string> = {
  bar: 'smart-bar-chart',
  line: 'smart-line-chart',
  pie: 'smart-pie-chart',
  number: 'smart-number-card',
  table: 'smart-table-chart',
};

interface ChatBiResultCardProps {
  result: ChatBiResult;
  reportNarrative?: string;
  narrativePending?: boolean;
}

/**
 * Auto-detect label and value fields from data columns.
 * String columns → label (category axis), numeric columns → value.
 */
function inferFields(
  records: Record<string, unknown>[],
  columns: string[],
  chartConfig?: Record<string, unknown>,
): { labelField: string; valueField: string } {
  if (chartConfig?.labelField && chartConfig?.valueField) {
    return {
      labelField: chartConfig.labelField as string,
      valueField: chartConfig.valueField as string,
    };
  }

  if (records.length === 0 || columns.length === 0) {
    return { labelField: columns[0] || 'name', valueField: columns[1] || 'value' };
  }

  // Classify columns by the type of their first-row value
  const firstRow = records[0];
  const stringCols: string[] = [];
  const numericCols: string[] = [];

  for (const col of columns) {
    const val = firstRow[col];
    if (typeof val === 'number') {
      numericCols.push(col);
    } else {
      stringCols.push(col);
    }
  }

  // Label = first string column, Value = first numeric column
  const labelField = stringCols[0] || columns[0];
  const valueField = numericCols[0] || columns[1] || columns[0];

  return { labelField, valueField };
}

/**
 * Render a chat-bi result via the shared dashboard chart components (SharedChartFactory),
 * fed a {type:'static'} ChartDataSource — the chat card no longer carries its own ECharts
 * config (Slice D renderer convergence). The aggregate spec (dimensions/metrics) is carried
 * by the chat-bi tool; for a raw tool result it is inferred from the column types.
 * (useChartData resolves static sources synchronously, so the chart mounts with data.)
 */
function ChatBiChart({
  chartType,
  records,
  columns,
  dimensions,
  metrics,
  chartConfig,
  labels,
}: {
  chartType: string;
  records: Record<string, unknown>[];
  columns: string[];
  dimensions?: string[];
  metrics?: ChatBiMetric[];
  chartConfig?: Record<string, unknown>;
  labels: Record<string, string>;
}) {
  const { locale } = useI18n();
  const compactAxis = useMemo(
    () => ({
      type: 'value' as const,
      splitNumber: 2,
      axisLabel: {
        hideOverlap: true,
        formatter: (value: number) =>
          new Intl.NumberFormat(locale, {
            notation: 'compact',
            maximumSignificantDigits: 3,
          }).format(value),
      },
    }),
    [locale],
  );
  const dataSource = useMemo<ChartDataSource>(() => {
    const { labelField, valueField } = inferFields(records, columns, chartConfig);
    const effectiveDimensions =
      dimensions && dimensions.length > 0 ? dimensions : chartType === 'number' ? [] : [labelField];
    const effectiveMetrics =
      metrics && metrics.length > 0
        ? metrics
        : [{ field: valueField, aggregation: 'sum', alias: valueField }];
    return {
      type: 'static',
      staticData: records,
      dimensions: effectiveDimensions,
      metrics: effectiveMetrics,
    } as ChartDataSource;
  }, [chartType, records, columns, dimensions, metrics, chartConfig]);

  // Keep long business labels from consuming the narrow chat plot. Preserve the
  // original category data so the shared tooltip and persisted analysis stay exact.
  const categoryField =
    dataSource.dimensions?.[0] || inferFields(records, columns, chartConfig).labelField;
  const categoryAxis = {
    type: 'category' as const,
    data: records.map((record) => record[categoryField]),
    axisLabel: { width: 96, overflow: 'truncate' as const, hideOverlap: true },
  };
  const chartOptions =
    chartType === 'bar'
      ? chartConfig?.orientation === 'vertical'
        ? { yAxis: compactAxis, xAxis: categoryAxis }
        : { xAxis: compactAxis, yAxis: categoryAxis }
      : undefined;

  const widgetType = normalizeChartType(CHART_TYPE_TO_WIDGET[chartType] || 'smart-bar-chart');
  const ChartComponent = getChartComponent(widgetType);
  if (!ChartComponent) {
    return <DataTable records={records} columns={columns} labels={labels} />;
  }

  return (
    <Suspense
      fallback={
        <div className="flex h-[280px] items-center justify-center text-sm text-gray-400">
          Loading chart...
        </div>
      }
    >
      <ChartComponent
        orientation={
          chartType === 'bar'
            ? chartConfig?.orientation === 'vertical'
              ? 'vertical'
              : 'horizontal'
            : undefined
        }
        chartOptions={chartOptions}
        metricLabels={labels}
        dataSource={dataSource}
        className="h-[280px] w-full"
        style={{ height: 280 }}
      />
    </Suspense>
  );
}

function DataTable({
  records,
  columns,
  labels = {},
}: {
  records: Record<string, unknown>[];
  columns: string[];
  labels?: Record<string, string>;
}) {
  const { locale } = useI18n();
  const numeric = new Set(
    columns.filter(
      (column) =>
        records.some((row) => typeof row[column] === 'number') &&
        records.every((row) => row[column] == null || typeof row[column] === 'number'),
    ),
  );
  const formats = Object.fromEntries(
    [...numeric].map((column) => [
      column,
      new Intl.NumberFormat(locale, {
        minimumFractionDigits: records.some(
          (row) => typeof row[column] === 'number' && !Number.isInteger(row[column]),
        )
          ? 2
          : 0,
        maximumFractionDigits: 20,
      }),
    ]),
  );
  return (
    <div className="max-h-[300px] overflow-auto">
      <table className="w-full text-xs text-gray-700 dark:text-gray-300">
        <thead>
          <tr className="border-b border-gray-200 dark:border-gray-700">
            {columns.map((col) => (
              <th
                key={col}
                className={`px-2 py-1.5 font-medium text-gray-500 dark:text-gray-400 ${numeric.has(col) ? 'text-right' : 'text-left'}`}
              >
                {labels[col] || col}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {records.map((row, i) => (
            <tr
              key={i}
              className="border-b border-gray-100 hover:bg-gray-50 dark:border-gray-800 dark:hover:bg-gray-800/50"
            >
              {columns.map((col) => (
                <td
                  key={col}
                  className={`px-2 py-1.5 ${numeric.has(col) ? 'text-right tabular-nums' : ''}`}
                >
                  {typeof row[col] === 'number' && formats[col]
                    ? formats[col].format(row[col])
                    : String(row[col] ?? '')}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/**
 * Auto-infer chart type when not explicitly set.
 * - 1 string + 1 numeric column with ≤20 rows → bar chart (good for comparison)
 * - otherwise → table
 */
function inferChartType(records: Record<string, unknown>[], columns: string[]): string {
  if (records.length === 0 || columns.length < 2) return 'table';
  if (records.length > 20) return 'table';

  const firstRow = records[0];
  let hasString = false;
  let hasNumber = false;
  for (const col of columns) {
    if (typeof firstRow[col] === 'string') hasString = true;
    if (typeof firstRow[col] === 'number') hasNumber = true;
  }

  return hasString && hasNumber ? 'bar' : 'table';
}

export function ChatBiResultCard({
  result,
  reportNarrative,
  narrativePending = false,
}: ChatBiResultCardProps) {
  const { t } = useI18n();
  const [showSql, setShowSql] = useState(false);
  const resultViewRef = useAnalyticsResultView(result.analysisId);

  const { interpretation, chartConfig, columns = [], records = [], total, sql, truncated } = result;

  const effectiveColumns =
    columns.length > 0 ? columns : records.length > 0 ? Object.keys(records[0]) : [];

  // Use explicit chartType if provided. If a raw tool result has records but no
  // column metadata, render as a table so all returned evidence fields remain visible.
  const chartType =
    result.chartType || (columns.length > 0 ? inferChartType(records, effectiveColumns) : 'table');

  const showChart = chartType !== 'table' && records.length > 0;
  const recordLabel =
    total === 1
      ? t('aurabot.chatbi.record', undefined, '条记录')
      : t('aurabot.chatbi.records', undefined, '条记录');

  // ── Ad-hoc → persisted bridge: save this chart as a dashboard widget ──
  const { dimensions = [], metrics = [] } = result;
  const [saving, setSaving] = useState(false);
  const [savedPid, setSavedPid] = useState<string | null>(null);
  const [savedCode, setSavedCode] = useState<string | null>(null);
  const text = useSmartText();
  const columnLabels: Record<string, string> = {};
  const aggregations: Record<string, string> = {
    count: text({ 'zh-CN': '数量', 'en-US': 'Count' }),
    sum: text({ 'zh-CN': '合计', 'en-US': 'Sum' }),
    avg: text({ 'zh-CN': '平均值', 'en-US': 'Average' }),
    min: text({ 'zh-CN': '最小值', 'en-US': 'Minimum' }),
    max: text({ 'zh-CN': '最大值', 'en-US': 'Maximum' }),
    count_distinct: text({ 'zh-CN': '去重数量', 'en-US': 'Distinct count' }),
  };
  for (const column of effectiveColumns) {
    const metric = (result.metrics || result.dataSource?.metrics || []).find(
      (candidate) => [candidate.alias, candidate.field, `${candidate.field}_${candidate.aggregation}`].includes(column),
    );
    const field = metric?.field || column;
    const fieldLabel = resolveFieldLabel(
      field,
      result.modelCode || result.dataSource?.modelCode || '',
      t,
    );
    columnLabels[column] =
      metric && aggregations[metric.aggregation]
        ? metric.aggregation === 'count' && field === 'pid'
          ? aggregations.count
          : `${fieldLabel} · ${aggregations[metric.aggregation]}`
        : fieldLabel;
  }
  const [reviewOpen, setReviewOpen] = useState(false);
  const [reportTitle, setReportTitle] = useState('');
  const [reviewedNarrative, setReviewedNarrative] = useState('');
  const [reviewedColumns, setReviewedColumns] = useState<ReportColumn[]>([]);
  const [reviewConfirmed, setReviewConfirmed] = useState(false);
  const [savingReport, setSavingReport] = useState(false);
  const [savedReportPid, setSavedReportPid] = useState<string | null>(null);
  const dataSource = result.dataSource;
  const canSaveReport = dataSource?.type === 'aggregate' && records.length > 0 && !savedReportPid;
  const openReportReview = () => {
    setReportTitle(text({ 'zh-CN': '数据分析报告', 'en-US': 'Data analysis report' }));
    setReviewedNarrative(narrativeWithoutTables(reportNarrative || interpretation || ''));
    setReviewedColumns(
      effectiveColumns.map((field) => {
        const numeric =
          records.some((row) => typeof row[field] === 'number') &&
          records.every((row) => row[field] == null || typeof row[field] === 'number');
        return {
          field,
          label: columnLabels[field],
          width: numeric ? 150 : 300,
          align: numeric ? 'right' : 'left',
          format: numeric ? 'number' : '',
        };
      }),
    );
    setReviewConfirmed(false);
    setReviewOpen(true);
  };
  const handleSaveReport = async () => {
    if (
      !canSaveReport ||
      savingReport ||
      !dataSource ||
      !reviewConfirmed ||
      !reportTitle.trim() ||
      !reviewedColumns.length
    )
      return;
    setSavingReport(true);
    try {
      const report = createEmptyReport(reportTitle.trim());
      report.header = {
        height: 12,
        elements: [
          {
            type: 'text',
            content: report.title,
            align: 'center',
            style: { fontSize: 16, fontWeight: 'bold' },
          },
        ],
      };
      report.footer = {
        height: 8,
        elements: [
          { type: 'page-number', align: 'right', style: { fontSize: 9 } },
          { type: 'date', align: 'left', style: { fontSize: 9 } },
        ],
      };
      report.dataSources = {
        analysis: { type: 'aggregate', aggregateQuery: { ...dataSource, type: 'aggregate' } },
      };
      report.body = [
        {
          id: 'analysis',
          blockType: 'table',
          dataSource: 'analysis',
          showHeader: true,
          stripe: true,
          border: true,
          columns: reviewedColumns,
        },
      ];
      if (reviewedNarrative.trim()) {
        report.body.push({
          id: 'analysis-summary',
          blockType: 'rich-text',
          title: text({ 'zh-CN': '分析说明', 'en-US': 'Analysis summary' }),
          content:
            text({
              'zh-CN': 'AI 分析 · 经人工核对采用；建议不代替业务审批。',
              'en-US':
                'AI analysis · reviewed and adopted; recommendations do not replace business approval.',
            }) +
            '\n' +
            reviewedNarrative.trim(),
        });
      }
      const pid = await reportDesignerService.save(report, undefined, result.analysisId);
      if (!pid) throw new Error('Saved report identity is missing');
      setSavedReportPid(pid);
      setReviewOpen(false);
      toast.success(text({ 'zh-CN': '已存为报表', 'en-US': 'Saved as report' }));
    } catch {
      toast.error(
        text({ 'zh-CN': '存为报表失败，请重试', 'en-US': 'Could not save report. Please retry.' }),
      );
    } finally {
      setSavingReport(false);
    }
  };
  const canSave = !!dataSource && records.length > 0 && !savedPid;

  const handleSaveDashboard = async () => {
    if (!canSave || saving) return;
    setSaving(true);
    try {
      const title = String(
        interpretation || text({ 'zh-CN': '分析看板', 'en-US': 'Analysis dashboard' }),
      ).slice(0, 200);
      const widgetType = CHART_TYPE_TO_WIDGET[chartType] || 'smart-bar-chart';
      const widgets = [
        {
          id: 'chatbi_chart',
          type: widgetType,
          x: 0,
          y: 0,
          w: 6,
          h: 4,
          title,
          config: {
            title,
            dataSource,
            metricLabels: columnLabels,
            ...(chartType === 'bar'
              ? {
                  orientation:
                    result.chartConfig?.orientation === 'vertical' ? 'vertical' : 'horizontal',
                }
              : {}),
          },
        },
      ];
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const dash: any = await dashboardService.create({
        title,
        scope: 'personal',
        widgets,
        sourceAnalysisId: result.analysisId,
      } as any);
      if (!dash?.pid || !dash?.code) throw new Error('Saved dashboard identity is missing');
      setSavedPid(dash.pid);
      setSavedCode(dash.code);
      toast.success(t('aurabot.chatbi.saved_as_dashboard', undefined, '已存为看板'));
    } catch {
      toast.error(t('aurabot.chatbi.save_failed', undefined, '存为看板失败'));
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="mb-3 flex justify-start">
      <div
        ref={resultViewRef}
        data-testid="chatbi-result-card"
        data-analysis-id={result.analysisId}
        data-model-code={result.modelCode || result.dataSource?.modelCode}
        data-dimensions={(result.dimensions || result.dataSource?.dimensions || []).join(',')}
        data-chart-type={chartType}
        data-row-count={records.length}
        className="w-full max-w-[95%] overflow-hidden rounded-xl border border-indigo-200 bg-white shadow-sm dark:border-indigo-700 dark:bg-gray-800"
      >
        {/* Header */}
        <div className="flex items-center gap-2 border-b border-indigo-100 bg-indigo-50 px-3 py-2 dark:border-indigo-800 dark:bg-indigo-900/20">
          <svg
            className="h-4 w-4 flex-shrink-0 text-indigo-500"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
          >
            <path d="M3 3v18h18" />
            <path d="M7 16l4-8 4 4 4-6" />
          </svg>
          <span className="text-sm font-medium text-indigo-700 dark:text-indigo-300">
            {t('aurabot.chatbi.data_query', undefined, '数据查询')}
          </span>
          {total != null && (
            <span className="ml-auto text-xs text-indigo-400 dark:text-indigo-500">
              {total} {recordLabel}
              {truncated && ` (${t('aurabot.chatbi.truncated', undefined, '已截断')})`}
            </span>
          )}
        </div>

        {/* Interpretation */}
        {result.historyRefreshed && (
          <p className="mb-2 text-xs text-gray-500">
            {text({
              'zh-CN': '已按当前权限重新查询；建议仍关联原分析。',
              'en-US':
                'Data refreshed with current permissions; suggestions remain linked to the original analysis.',
            })}
          </p>
        )}
        {interpretation && (
          <div className="border-b border-gray-100 px-3 py-2 text-sm text-gray-600 dark:border-gray-700 dark:text-gray-300">
            {interpretation}
          </div>
        )}

        {/* Chart or Table */}
        <div data-testid="chatbi-chart-area" className="bg-white p-3 dark:bg-gray-800">
          {showChart ? (
            <ChatBiChart
              chartType={chartType}
              records={records}
              columns={effectiveColumns}
              dimensions={dimensions}
              metrics={metrics}
              chartConfig={chartConfig}
              labels={columnLabels}
            />
          ) : (
            <DataTable records={records} columns={effectiveColumns} labels={columnLabels} />
          )}
        </div>

        {result.analysisId && dataSource?.type === 'aggregate' && (
          <AnalyticsSuggestions analysisId={result.analysisId} query={dataSource} />
        )}
        {/* Actions: ad-hoc → persisted bridge (save this chart as a dashboard widget) */}
        {(canSave || savedPid || canSaveReport || savedReportPid) && (
          <div className="flex items-center justify-end gap-2 border-t border-gray-100 px-3 py-1.5 dark:border-gray-700">
            {savedReportPid ? (
              <a
                data-testid="chatbi-saved-report"
                href={`/report-designer/${encodeURIComponent(savedReportPid)}`}
                className="text-xs font-medium text-green-600 underline dark:text-green-400"
              >
                {text({ 'zh-CN': '打开报表', 'en-US': 'Open report' })}
              </a>
            ) : canSaveReport ? (
              <button
                data-testid="chatbi-save-report"
                onClick={openReportReview}
                disabled={savingReport || narrativePending}
                className="rounded-md px-2 py-1 text-xs text-indigo-600 hover:bg-indigo-50 disabled:opacity-50 dark:text-indigo-400 dark:hover:bg-indigo-900/20"
              >
                {savingReport
                  ? text({ 'zh-CN': '保存中…', 'en-US': 'Saving…' })
                  : text({ 'zh-CN': '存为报表', 'en-US': 'Save as report' })}
              </button>
            ) : null}
            {savedPid ? (
              <a
                data-testid="chatbi-saved-dashboard"
                href={`/dashboards/view/${encodeURIComponent(savedCode!)}`}
                className="text-xs font-medium text-green-600 underline dark:text-green-400"
              >
                {t('aurabot.chatbi.saved_as_dashboard', undefined, '已存为看板')} ✓
              </a>
            ) : (
              <button
                data-testid="chatbi-save-dashboard"
                onClick={handleSaveDashboard}
                disabled={saving}
                className="flex items-center gap-1 rounded-md px-2 py-1 text-xs text-indigo-600 transition-colors hover:bg-indigo-50 disabled:opacity-50 dark:text-indigo-400 dark:hover:bg-indigo-900/20"
              >
                <svg
                  className="h-3 w-3"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="2"
                >
                  <rect x="3" y="3" width="7" height="7" />
                  <rect x="14" y="3" width="7" height="7" />
                  <rect x="14" y="14" width="7" height="7" />
                  <rect x="3" y="14" width="7" height="7" />
                </svg>
                {saving
                  ? t('aurabot.chatbi.saving', undefined, '保存中…')
                  : t('aurabot.chatbi.save_as_dashboard', undefined, '存为看板')}
              </button>
            )}
          </div>
        )}

        {/* SQL toggle */}
        {sql && (
          <div className="border-t border-gray-100 dark:border-gray-700">
            <button
              onClick={() => setShowSql(!showSql)}
              className="flex w-full items-center gap-1.5 px-3 py-1.5 text-xs text-gray-400 transition-colors hover:text-gray-600 dark:hover:text-gray-300"
            >
              <svg
                className="h-3 w-3"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="2"
              >
                <polyline points={showSql ? '18 15 12 9 6 15' : '6 9 12 15 18 9'} />
              </svg>
              {showSql ? 'Hide SQL' : 'Show SQL'}
            </button>
            {showSql && (
              <pre className="overflow-x-auto px-3 pb-2 text-xs whitespace-pre-wrap text-gray-500 dark:text-gray-400">
                {sql}
              </pre>
            )}
          </div>
        )}
      </div>
      <Dialog
        open={reviewOpen}
        onOpenChange={(open) => {
          if (!savingReport) setReviewOpen(open);
        }}
      >
        <DialogContent
          className="max-h-[85vh] overflow-y-auto sm:max-w-2xl"
          data-testid="analysis-report-review"
        >
          <DialogHeader>
            <DialogTitle>
              {text({ 'zh-CN': '核对并存为报表', 'en-US': 'Review and save report' })}
            </DialogTitle>
            <DialogDescription>
              {text({
                'zh-CN':
                  '保留当前查询范围，表格随数据刷新。核对分析内容和列格式后再采用；原始 AI 对话与 Trace 保持不变。',
                'en-US':
                  'Keep the current query scope and refreshable table. Review the narrative and column formats before adoption. The original AI conversation and trace remain unchanged.',
              })}
            </DialogDescription>
          </DialogHeader>
          <label className="grid gap-2 text-sm">
            {text({ 'zh-CN': '报表名称', 'en-US': 'Report name' })}
            <input
              className="rounded-md border px-3 py-2"
              value={reportTitle}
              maxLength={200}
              onChange={(e) => setReportTitle(e.target.value)}
            />
          </label>
          <div className="rounded-lg border bg-slate-50 p-3 text-sm">
            {text({ 'zh-CN': '实时数据表', 'en-US': 'Live data table' })} · {records.length}{' '}
            {text({ 'zh-CN': '条记录', 'en-US': 'records' })}
            <div className="mt-2 grid gap-2">
              {reviewedColumns.map((column, index) => (
                <div
                  key={column.field}
                  data-testid="analysis-report-column"
                  data-field-code={column.field}
                  className="grid grid-cols-[1fr_120px_32px] gap-2"
                >
                  <input
                    aria-label={text({ 'zh-CN': '列显示名称', 'en-US': 'Column label' })}
                    className="min-w-0 rounded border bg-white px-2 py-1.5"
                    value={column.label || ''}
                    onChange={(e) =>
                      setReviewedColumns((rows) =>
                        rows.map((row, i) =>
                          i === index ? { ...row, label: e.target.value } : row,
                        ),
                      )
                    }
                  />
                  <select
                    aria-label={text({ 'zh-CN': '列格式', 'en-US': 'Column format' })}
                    className="rounded border bg-white px-2"
                    value={column.format || ''}
                    onChange={(e) =>
                      setReviewedColumns((rows) =>
                        rows.map((row, i) =>
                          i === index
                            ? {
                                ...row,
                                format: e.target.value,
                                align: e.target.value ? 'right' : 'left',
                              }
                            : row,
                        ),
                      )
                    }
                  >
                    <option value="">{text({ 'zh-CN': '原值', 'en-US': 'Original' })}</option>
                    <option value="number">{text({ 'zh-CN': '数字', 'en-US': 'Number' })}</option>
                    <option value="currency">
                      {text({ 'zh-CN': '金额', 'en-US': 'Currency' })}
                    </option>
                    <option value="percent">
                      {text({ 'zh-CN': '百分比', 'en-US': 'Percentage' })}
                    </option>
                  </select>
                  <button
                    aria-label={text({ 'zh-CN': '移除列', 'en-US': 'Remove column' })}
                    className="rounded border text-slate-500"
                    onClick={() => setReviewedColumns((rows) => rows.filter((_, i) => i !== index))}
                  >
                    ×
                  </button>
                </div>
              ))}
            </div>
          </div>
          <label className="grid gap-2 text-sm">
            {text({ 'zh-CN': '采用的分析说明', 'en-US': 'Adopted analysis' })}
            <textarea
              aria-label={text({ 'zh-CN': '采用的分析说明', 'en-US': 'Adopted analysis' })}
              rows={8}
              className="rounded-md border px-3 py-2 leading-6"
              value={reviewedNarrative}
              onChange={(e) => setReviewedNarrative(e.target.value)}
            />
            <span className="text-xs text-slate-500">
              {text({
                'zh-CN':
                  '已从草稿移除 Markdown 表格，避免重复静态数据；原始输出可在对话中核对。预算、收入和利润应分别判断。',
                'en-US':
                  'Markdown tables are omitted from the draft to avoid duplicate static data. Review the original in the conversation. Distinguish budget, revenue and profit.',
              })}
            </span>
          </label>
          <label className="flex items-start gap-2 text-sm">
            <input
              type="checkbox"
              className="mt-1"
              checked={reviewConfirmed}
              onChange={(e) => setReviewConfirmed(e.target.checked)}
            />
            {text({
              'zh-CN': '我已核对查询范围、事实和采用内容',
              'en-US': 'I reviewed the query scope, facts and adopted content',
            })}
          </label>
          <div className="flex justify-end gap-3">
            <button
              className="rounded-md border px-4 py-2 text-sm"
              disabled={savingReport}
              onClick={() => setReviewOpen(false)}
            >
              {text({ 'zh-CN': '取消', 'en-US': 'Cancel' })}
            </button>
            <button
              className="rounded-md bg-blue-600 px-4 py-2 text-sm text-white disabled:opacity-50"
              data-testid="analysis-report-confirm"
              disabled={
                savingReport || !reviewConfirmed || !reportTitle.trim() || !reviewedColumns.length
              }
              onClick={handleSaveReport}
            >
              {text({ 'zh-CN': '采用并保存报表', 'en-US': 'Adopt and save report' })}
            </button>
          </div>
        </DialogContent>
      </Dialog>
    </div>
  );
}

export default ChatBiResultCard;
