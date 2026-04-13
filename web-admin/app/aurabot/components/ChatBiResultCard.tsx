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

import { useState, useMemo, lazy, Suspense } from 'react';

const ReactECharts = lazy(() => import('echarts-for-react'));

interface ChatBiResult {
  interpretation?: string;
  modelCode?: string;
  chartType?: string;
  chartConfig?: Record<string, unknown>;
  columns?: string[];
  records?: Record<string, unknown>[];
  total?: number;
  sql?: string;
  truncated?: boolean;
}

interface ChatBiResultCardProps {
  result: ChatBiResult;
}

// ECharts color palette matching dashboard theme
const CHART_COLORS = [
  '#5B8FF9', '#5AD8A6', '#F6BD16', '#E86452', '#6DC8EC',
  '#945FB9', '#FF9845', '#1E9493', '#FF99C3', '#269A99',
];

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

function EChartsChart({
  chartType,
  records,
  columns,
  chartConfig,
}: {
  chartType: string;
  records: Record<string, unknown>[];
  columns: string[];
  chartConfig?: Record<string, unknown>;
}) {
  const option = useMemo(() => {
    const { labelField, valueField } = inferFields(records, columns, chartConfig);
    const labels = records.map((r) => String(r[labelField] ?? ''));
    const values = records.map((r) => Number(r[valueField] ?? 0));

    const bg = 'transparent';

    if (chartType === 'pie') {
      return {
        backgroundColor: bg,
        tooltip: {
          trigger: 'item',
          formatter: '{b}: {c} ({d}%)',
          backgroundColor: 'rgba(50,50,50,0.9)',
          borderColor: '#555',
          textStyle: { color: '#fff', fontSize: 12 },
        },
        legend: {
          type: 'scroll',
          bottom: 0,
          textStyle: { color: '#e5e7eb', fontSize: 11 },
          pageTextStyle: { color: '#9ca3af' },
          pageIconColor: '#9ca3af',
          pageIconInactiveColor: '#4b5563',
        },
        series: [
          {
            type: 'pie',
            radius: ['30%', '60%'],
            center: ['50%', '42%'],
            data: records.map((r, i) => ({
              name: String(r[labelField] ?? ''),
              value: Number(r[valueField] ?? 0),
              itemStyle: {
                color: CHART_COLORS[i % CHART_COLORS.length],
                borderColor: '#1f2937',
                borderWidth: 2,
              },
            })),
            label: {
              show: records.length <= 10,
              formatter: '{b}\n{d}%',
              fontSize: 11,
              color: '#e5e7eb',
            },
            labelLine: {
              lineStyle: { color: '#6b7280' },
            },
            emphasis: {
              itemStyle: { shadowBlur: 12, shadowColor: 'rgba(0,0,0,0.4)' },
              label: { fontSize: 13, fontWeight: 'bold' },
            },
          },
        ],
      };
    }

    if (chartType === 'line') {
      return {
        backgroundColor: bg,
        tooltip: {
          trigger: 'axis',
          backgroundColor: 'rgba(50,50,50,0.9)',
          borderColor: '#555',
          textStyle: { color: '#fff' },
        },
        grid: { left: '10%', right: '4%', bottom: '14%', top: '10%' },
        xAxis: {
          type: 'category',
          data: labels,
          axisLabel: { color: '#d1d5db', fontSize: 10, rotate: labels.length > 6 ? 30 : 0 },
          axisLine: { lineStyle: { color: '#4b5563' } },
        },
        yAxis: {
          type: 'value',
          axisLabel: { color: '#d1d5db', fontSize: 10 },
          splitLine: { lineStyle: { color: '#374151', type: 'dashed' } },
        },
        series: [
          {
            type: 'line',
            data: values,
            smooth: true,
            areaStyle: { opacity: 0.2, color: CHART_COLORS[0] },
            lineStyle: { color: CHART_COLORS[0], width: 2 },
            itemStyle: { color: CHART_COLORS[0] },
          },
        ],
      };
    }

    // Default: bar chart
    return {
      backgroundColor: bg,
      tooltip: {
        trigger: 'axis',
        backgroundColor: 'rgba(50,50,50,0.9)',
        borderColor: '#555',
        textStyle: { color: '#fff' },
      },
      grid: { left: '10%', right: '4%', bottom: '14%', top: '10%' },
      xAxis: {
        type: 'category',
        data: labels,
        axisLabel: { color: '#d1d5db', fontSize: 10, rotate: labels.length > 6 ? 30 : 0 },
        axisLine: { lineStyle: { color: '#4b5563' } },
      },
      yAxis: {
        type: 'value',
        axisLabel: { color: '#d1d5db', fontSize: 10 },
        splitLine: { lineStyle: { color: '#374151', type: 'dashed' } },
      },
      series: [
        {
          type: 'bar',
          data: values.map((v, i) => ({
            value: v,
            itemStyle: {
              color: CHART_COLORS[i % CHART_COLORS.length],
              borderRadius: [3, 3, 0, 0],
            },
          })),
          barMaxWidth: 40,
        },
      ],
    };
  }, [chartType, records, columns, chartConfig]);

  return (
    <Suspense fallback={<div className="flex h-[280px] items-center justify-center text-gray-400 text-sm">Loading chart...</div>}>
      <ReactECharts
        option={option}
        style={{ height: 280, width: '100%' }}
        opts={{ renderer: 'svg' }}
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
      <table className="w-full text-xs text-gray-300">
        <thead>
          <tr className="border-b border-gray-700">
            {columns.map((col) => (
              <th
                key={col}
                className="px-2 py-1.5 text-left font-medium text-gray-400"
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
              className="border-b border-gray-800 hover:bg-gray-800/50"
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

  // Use explicit chartType if provided, otherwise auto-infer from data shape
  const chartType = result.chartType || inferChartType(records, columns);

  const showChart = chartType !== 'table' && records.length > 0;

  return (
    <div className="mb-3 flex justify-start">
      <div className="w-full max-w-[95%] overflow-hidden rounded-xl border border-indigo-200 bg-white shadow-sm dark:border-indigo-700 dark:bg-gray-800">
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
        <div className="bg-gray-900 p-3">
          {showChart ? (
            <EChartsChart
              chartType={chartType}
              records={records}
              columns={columns}
              chartConfig={chartConfig}
            />
          ) : (
            <DataTable records={records} columns={columns} />
          )}
        </div>

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
