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
import { toast } from 'sonner';
import { dashboardService } from '~/plugins/core-dashboard/services/dashboardService';
import { getChartComponent, normalizeChartType } from '~/framework/smart/charts/SharedChartFactory';
import type { ChartDataSource } from '~/framework/smart/types/chart';

interface ChatBiMetric {
  field: string;
  aggregation: string;
  alias?: string;
}

interface ChatBiResult {
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
  title,
}: {
  chartType: string;
  records: Record<string, unknown>[];
  columns: string[];
  dimensions?: string[];
  metrics?: ChatBiMetric[];
  chartConfig?: Record<string, unknown>;
  title?: string;
}) {
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

  const widgetType = normalizeChartType(CHART_TYPE_TO_WIDGET[chartType] || 'smart-bar-chart');
  const ChartComponent = getChartComponent(widgetType);
  if (!ChartComponent) {
    return <DataTable records={records} columns={columns} />;
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
        title={title}
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
}: {
  records: Record<string, unknown>[];
  columns: string[];
}) {
  return (
    <div className="max-h-[300px] overflow-auto">
      <table className="w-full text-xs text-gray-700 dark:text-gray-300">
        <thead>
          <tr className="border-b border-gray-200 dark:border-gray-700">
            {columns.map((col) => (
              <th
                key={col}
                className="px-2 py-1.5 text-left font-medium text-gray-500 dark:text-gray-400"
              >
                {col}
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
                <td key={col} className="px-2 py-1.5">
                  {String(row[col] ?? '')}
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

export function ChatBiResultCard({ result }: ChatBiResultCardProps) {
  const [showSql, setShowSql] = useState(false);

  const {
    interpretation,
    chartConfig,
    columns = [],
    records = [],
    total,
    sql,
    truncated,
  } = result;

  const effectiveColumns =
    columns.length > 0 ? columns : records.length > 0 ? Object.keys(records[0]) : [];

  // Use explicit chartType if provided. If a raw tool result has records but no
  // column metadata, render as a table so all returned evidence fields remain visible.
  const chartType =
    result.chartType || (columns.length > 0 ? inferChartType(records, effectiveColumns) : 'table');

  const showChart = chartType !== 'table' && records.length > 0;

  // ── Ad-hoc → persisted bridge: save this chart as a dashboard widget ──
  const { modelCode, dimensions = [], metrics = [] } = result;
  const [saving, setSaving] = useState(false);
  const [savedPid, setSavedPid] = useState<string | null>(null);
  const canSave = !!modelCode && metrics.length > 0 && records.length > 0 && !savedPid;

  const handleSaveDashboard = async () => {
    if (!canSave || saving) return;
    setSaving(true);
    try {
      const title = String(interpretation || modelCode || 'Chart').slice(0, 200);
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
            dataSource: { type: 'aggregate', modelCode, dimensions, metrics },
          },
        },
      ];
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const dash: any = await dashboardService.create({ title, scope: 'personal', widgets } as any);
      setSavedPid(dash?.pid || dash?.code || 'saved');
      toast.success('已存为看板');
    } catch {
      toast.error('存为看板失败');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="mb-3 flex justify-start">
      <div
        data-testid="chatbi-result-card"
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
            Data Query
          </span>
          {total != null && (
            <span className="ml-auto text-xs text-indigo-400 dark:text-indigo-500">
              {total} record{total !== 1 ? 's' : ''}
              {truncated && ' (truncated)'}
            </span>
          )}
        </div>

        {/* Interpretation */}
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
              title={interpretation}
            />
          ) : (
            <DataTable records={records} columns={effectiveColumns} />
          )}
        </div>

        {/* Actions: ad-hoc → persisted bridge (save this chart as a dashboard widget) */}
        {(canSave || savedPid) && (
          <div className="flex items-center justify-end gap-2 border-t border-gray-100 px-3 py-1.5 dark:border-gray-700">
            {savedPid ? (
              <span data-testid="chatbi-saved-dashboard" className="text-xs font-medium text-green-600 dark:text-green-400">
                已存为看板 ✓
              </span>
            ) : (
              <button
                data-testid="chatbi-save-dashboard"
                onClick={handleSaveDashboard}
                disabled={saving}
                className="flex items-center gap-1 rounded-md px-2 py-1 text-xs text-indigo-600 transition-colors hover:bg-indigo-50 disabled:opacity-50 dark:text-indigo-400 dark:hover:bg-indigo-900/20"
              >
                <svg className="h-3 w-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <rect x="3" y="3" width="7" height="7" />
                  <rect x="14" y="3" width="7" height="7" />
                  <rect x="14" y="14" width="7" height="7" />
                  <rect x="3" y="14" width="7" height="7" />
                </svg>
                {saving ? '保存中…' : '存为看板'}
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
    </div>
  );
}

export default ChatBiResultCard;
