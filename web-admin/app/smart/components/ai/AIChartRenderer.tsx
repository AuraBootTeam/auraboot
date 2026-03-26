/**
 * AIChartRenderer — renders data as a simple chart or table.
 *
 * Supports: "table" | "bar" | "pie" | "line"
 * Uses pure CSS/SVG — no external chart library.
 */

import React from 'react';

interface AIChartRendererProps {
  chartType: string;
  data: Record<string, unknown>[];
  columns: string[];
  chartConfig?: Record<string, unknown>;
}

export function AIChartRenderer({ chartType, data, columns, chartConfig }: AIChartRendererProps) {
  if (!data || data.length === 0) {
    return <div className="py-8 text-center text-sm text-gray-400">No data to display</div>;
  }

  switch (chartType) {
    case 'bar':
      return <BarChart data={data} columns={columns} chartConfig={chartConfig} />;
    case 'pie':
      return <PieChart data={data} columns={columns} chartConfig={chartConfig} />;
    case 'line':
      return <LineChart data={data} columns={columns} chartConfig={chartConfig} />;
    default:
      return <DataTable data={data} columns={columns} />;
  }
}

// ============================================================
// Data Table
// ============================================================

function DataTable({ data, columns }: { data: Record<string, unknown>[]; columns: string[] }) {
  const displayColumns = columns.length > 0 ? columns : Object.keys(data[0] || {});

  return (
    <div className="overflow-x-auto rounded border border-gray-700">
      <table className="w-full text-left text-sm">
        <thead className="bg-gray-800 text-xs text-gray-300 uppercase">
          <tr>
            {displayColumns.map((col) => (
              <th key={col} className="px-4 py-2 font-medium whitespace-nowrap">
                {col}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {data.map((row, i) => (
            <tr
              key={i}
              className={`border-t border-gray-700 ${i % 2 === 0 ? 'bg-gray-900' : 'bg-gray-850'} transition-colors hover:bg-gray-700`}
            >
              {displayColumns.map((col) => (
                <td key={col} className="px-4 py-2 whitespace-nowrap text-gray-200">
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

// ============================================================
// Bar Chart (CSS-based horizontal bars)
// ============================================================

function BarChart({
  data,
  columns,
  chartConfig,
}: {
  data: Record<string, unknown>[];
  columns: string[];
  chartConfig?: Record<string, unknown>;
}) {
  const labelField = (chartConfig?.labelField as string) ?? columns[0] ?? '';
  const valueField = (chartConfig?.valueField as string) ?? columns[1] ?? '';

  const values = data.map((row) => Number(row[valueField] ?? 0));
  const maxValue = Math.max(...values, 1);

  const COLORS = [
    '#6366f1',
    '#8b5cf6',
    '#ec4899',
    '#f59e0b',
    '#10b981',
    '#3b82f6',
    '#ef4444',
    '#14b8a6',
    '#f97316',
    '#84cc16',
  ];

  return (
    <div className="space-y-2 py-2">
      <div className="mb-3 text-xs text-gray-400">
        {labelField} vs {valueField}
      </div>
      {data.map((row, i) => {
        const pct = (Number(row[valueField] ?? 0) / maxValue) * 100;
        const color = COLORS[i % COLORS.length];
        return (
          <div key={i} className="flex items-center gap-3">
            <div
              className="w-28 truncate text-right text-xs text-gray-300"
              title={String(row[labelField] ?? '')}
            >
              {String(row[labelField] ?? '')}
            </div>
            <div className="h-6 flex-1 overflow-hidden rounded bg-gray-800">
              <div
                className="flex h-full items-center rounded px-2 transition-all duration-500"
                style={{ width: `${Math.max(pct, 2)}%`, backgroundColor: color }}
              >
                <span className="text-xs font-medium whitespace-nowrap text-white">
                  {String(row[valueField] ?? '')}
                </span>
              </div>
            </div>
          </div>
        );
      })}
    </div>
  );
}

// ============================================================
// Pie Chart (SVG donut)
// ============================================================

function PieChart({
  data,
  columns,
  chartConfig,
}: {
  data: Record<string, unknown>[];
  columns: string[];
  chartConfig?: Record<string, unknown>;
}) {
  const labelField = (chartConfig?.labelField as string) ?? columns[0] ?? '';
  const valueField = (chartConfig?.valueField as string) ?? columns[1] ?? '';

  const values = data.map((row) => Math.max(Number(row[valueField] ?? 0), 0));
  const total = values.reduce((a, b) => a + b, 0) || 1;

  const COLORS = [
    '#6366f1',
    '#8b5cf6',
    '#ec4899',
    '#f59e0b',
    '#10b981',
    '#3b82f6',
    '#ef4444',
    '#14b8a6',
    '#f97316',
    '#84cc16',
  ];

  const SIZE = 180;
  const CX = SIZE / 2;
  const CY = SIZE / 2;
  const R_OUTER = 80;
  const R_INNER = 48; // donut hole

  // Build SVG arcs
  type Slice = { path: string; color: string; label: string; pct: number };
  const slices: Slice[] = [];
  let startAngle = -Math.PI / 2;

  values.forEach((val, i) => {
    const sliceAngle = (val / total) * 2 * Math.PI;
    const endAngle = startAngle + sliceAngle;

    const x1o = CX + R_OUTER * Math.cos(startAngle);
    const y1o = CY + R_OUTER * Math.sin(startAngle);
    const x2o = CX + R_OUTER * Math.cos(endAngle);
    const y2o = CY + R_OUTER * Math.sin(endAngle);

    const x1i = CX + R_INNER * Math.cos(endAngle);
    const y1i = CY + R_INNER * Math.sin(endAngle);
    const x2i = CX + R_INNER * Math.cos(startAngle);
    const y2i = CY + R_INNER * Math.sin(startAngle);

    const largeArc = sliceAngle > Math.PI ? 1 : 0;

    const path = `M ${x1o} ${y1o} A ${R_OUTER} ${R_OUTER} 0 ${largeArc} 1 ${x2o} ${y2o} L ${x1i} ${y1i} A ${R_INNER} ${R_INNER} 0 ${largeArc} 0 ${x2i} ${y2i} Z`;

    slices.push({
      path,
      color: COLORS[i % COLORS.length],
      label: String(data[i][labelField] ?? ''),
      pct: Math.round((val / total) * 100),
    });

    startAngle = endAngle;
  });

  return (
    <div className="flex flex-col items-center gap-6 sm:flex-row">
      <svg width={SIZE} height={SIZE} className="shrink-0">
        {slices.map((s, i) => (
          <path key={i} d={s.path} fill={s.color} opacity={0.9}>
            <title>
              {s.label}: {s.pct}%
            </title>
          </path>
        ))}
        <text x={CX} y={CY - 6} textAnchor="middle" className="fill-gray-300" fontSize={11}>
          Total
        </text>
        <text x={CX} y={CY + 10} textAnchor="middle" className="fill-white font-bold" fontSize={13}>
          {total}
        </text>
      </svg>
      <div className="flex min-w-0 flex-1 flex-col gap-2">
        {slices.map((s, i) => (
          <div key={i} className="flex items-center gap-2 text-sm">
            <div className="h-3 w-3 shrink-0 rounded-sm" style={{ backgroundColor: s.color }} />
            <span className="flex-1 truncate text-gray-300">{s.label}</span>
            <span className="text-xs text-gray-400">{s.pct}%</span>
          </div>
        ))}
      </div>
    </div>
  );
}

// ============================================================
// Line Chart (SVG polyline)
// ============================================================

function LineChart({
  data,
  columns,
  chartConfig,
}: {
  data: Record<string, unknown>[];
  columns: string[];
  chartConfig?: Record<string, unknown>;
}) {
  const xField = (chartConfig?.xField as string) ?? columns[0] ?? '';
  const yField = (chartConfig?.yField as string) ?? columns[1] ?? '';

  const yValues = data.map((row) => Number(row[yField] ?? 0));
  const maxY = Math.max(...yValues, 1);
  const minY = Math.min(...yValues, 0);
  const range = maxY - minY || 1;

  const WIDTH = 400;
  const HEIGHT = 160;
  const PADDING = { top: 16, right: 16, bottom: 28, left: 40 };

  const chartW = WIDTH - PADDING.left - PADDING.right;
  const chartH = HEIGHT - PADDING.top - PADDING.bottom;

  const points = data.map((row, i) => {
    const x = PADDING.left + (i / Math.max(data.length - 1, 1)) * chartW;
    const y = PADDING.top + chartH - ((Number(row[yField] ?? 0) - minY) / range) * chartH;
    return { x, y, label: String(row[xField] ?? ''), value: Number(row[yField] ?? 0) };
  });

  const polyline = points.map((p) => `${p.x},${p.y}`).join(' ');

  // Y-axis labels (3 ticks)
  const yTicks = [minY, minY + range / 2, maxY].map((v) => Math.round(v));

  return (
    <div className="overflow-x-auto">
      <svg width={WIDTH} height={HEIGHT} className="min-w-full">
        {/* Grid lines */}
        {yTicks.map((tick, i) => {
          const y = PADDING.top + chartH - ((tick - minY) / range) * chartH;
          return (
            <g key={i}>
              <line
                x1={PADDING.left}
                y1={y}
                x2={WIDTH - PADDING.right}
                y2={y}
                stroke="#374151"
                strokeWidth={1}
                strokeDasharray="4 4"
              />
              <text x={PADDING.left - 4} y={y + 4} textAnchor="end" fontSize={10} fill="#9CA3AF">
                {tick}
              </text>
            </g>
          );
        })}

        {/* Area fill */}
        {points.length > 1 && (
          <polygon
            points={`${PADDING.left},${PADDING.top + chartH} ${polyline} ${WIDTH - PADDING.right},${PADDING.top + chartH}`}
            fill="#6366f1"
            opacity={0.1}
          />
        )}

        {/* Line */}
        {points.length > 1 && (
          <polyline
            points={polyline}
            fill="none"
            stroke="#6366f1"
            strokeWidth={2}
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        )}

        {/* Data points */}
        {points.map((p, i) => (
          <circle key={i} cx={p.x} cy={p.y} r={4} fill="#6366f1" stroke="#1f2937" strokeWidth={2}>
            <title>
              {p.label}: {p.value}
            </title>
          </circle>
        ))}

        {/* X-axis labels (every N-th to avoid overlap) */}
        {points
          .filter((_, i) => data.length <= 8 || i % Math.ceil(data.length / 8) === 0)
          .map((p, i) => (
            <text key={i} x={p.x} y={HEIGHT - 6} textAnchor="middle" fontSize={9} fill="#9CA3AF">
              {p.label.length > 8 ? p.label.slice(0, 8) + '…' : p.label}
            </text>
          ))}
      </svg>
    </div>
  );
}
