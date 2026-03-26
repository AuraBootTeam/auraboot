/**
 * ReportChartBlock — SVG-based chart rendering (no external deps)
 * Supports bar, horizontal-bar, pie charts
 * Uses pure SVG for PDF compatibility with openhtmltopdf
 */

import React from 'react';
import type { ChartBlock } from '../types';

interface ReportChartBlockProps {
  block: ChartBlock;
  mode: 'design' | 'runtime';
  data?: Record<string, unknown>[];
}

const DEFAULT_COLORS = [
  '#3b82f6',
  '#ef4444',
  '#22c55e',
  '#f59e0b',
  '#8b5cf6',
  '#ec4899',
  '#06b6d4',
  '#f97316',
];

function computeAgg(values: number[], agg: string): number {
  if (values.length === 0) return 0;
  switch (agg) {
    case 'sum':
      return values.reduce((a, b) => a + b, 0);
    case 'avg':
      return values.reduce((a, b) => a + b, 0) / values.length;
    case 'count':
      return values.length;
    case 'min':
      return Math.min(...values);
    case 'max':
      return Math.max(...values);
    default:
      return values.reduce((a, b) => a + b, 0);
  }
}

function aggregateData(
  data: Record<string, unknown>[],
  categoryField: string,
  valueField: string,
  agg: string = 'sum',
) {
  const groups = new Map<string, number[]>();
  for (const row of data) {
    const cat = String(row[categoryField] ?? 'Other');
    const val = Number(row[valueField]) || 0;
    if (!groups.has(cat)) groups.set(cat, []);
    groups.get(cat)!.push(val);
  }
  return Array.from(groups.entries()).map(([category, values]) => ({
    category,
    value: computeAgg(values, agg),
  }));
}

const SAMPLE_DATA = [
  { category: 'Category A', value: 120 },
  { category: 'Category B', value: 85 },
  { category: 'Category C', value: 200 },
  { category: 'Category D', value: 65 },
];

const BarChart: React.FC<{
  items: { category: string; value: number }[];
  width: number;
  height: number;
  colors: string[];
}> = ({ items, width, height, colors }) => {
  const maxVal = Math.max(...items.map((i) => i.value), 1);
  const barWidth = Math.min(40, (width - 60) / items.length - 8);
  const chartH = height - 40;

  return (
    <svg width={width} height={height} viewBox={`0 0 ${width} ${height}`}>
      {/* Y axis */}
      <line x1="50" y1="10" x2="50" y2={chartH + 10} stroke="#d1d5db" strokeWidth="1" />
      {/* X axis */}
      <line
        x1="50"
        y1={chartH + 10}
        x2={width - 10}
        y2={chartH + 10}
        stroke="#d1d5db"
        strokeWidth="1"
      />
      {/* Bars */}
      {items.map((item, i) => {
        const barH = (item.value / maxVal) * chartH;
        const x = 60 + i * ((width - 70) / items.length);
        const y = chartH + 10 - barH;
        return (
          <g key={i}>
            <rect
              x={x}
              y={y}
              width={barWidth}
              height={barH}
              fill={colors[i % colors.length]}
              rx="2"
            />
            <text
              x={x + barWidth / 2}
              y={chartH + 24}
              textAnchor="middle"
              fontSize="9"
              fill="#6b7280"
            >
              {item.category.length > 8 ? item.category.slice(0, 8) + '…' : item.category}
            </text>
            <text x={x + barWidth / 2} y={y - 4} textAnchor="middle" fontSize="8" fill="#374151">
              {item.value.toLocaleString()}
            </text>
          </g>
        );
      })}
    </svg>
  );
};

const HorizontalBarChart: React.FC<{
  items: { category: string; value: number }[];
  width: number;
  height: number;
  colors: string[];
}> = ({ items, width, height, colors }) => {
  const maxVal = Math.max(...items.map((i) => i.value), 1);
  const barH = Math.min(24, (height - 20) / items.length - 6);
  const chartW = width - 120;

  return (
    <svg width={width} height={height} viewBox={`0 0 ${width} ${height}`}>
      {items.map((item, i) => {
        const barW = (item.value / maxVal) * chartW;
        const y = 10 + i * ((height - 20) / items.length);
        return (
          <g key={i}>
            <text x="5" y={y + barH / 2 + 4} fontSize="9" fill="#374151">
              {item.category.length > 12 ? item.category.slice(0, 12) + '…' : item.category}
            </text>
            <rect
              x="110"
              y={y}
              width={barW}
              height={barH}
              fill={colors[i % colors.length]}
              rx="2"
            />
            <text x={110 + barW + 4} y={y + barH / 2 + 4} fontSize="8" fill="#6b7280">
              {item.value.toLocaleString()}
            </text>
          </g>
        );
      })}
    </svg>
  );
};

const PieChart: React.FC<{
  items: { category: string; value: number }[];
  width: number;
  height: number;
  colors: string[];
}> = ({ items, width, height, colors }) => {
  const total = items.reduce((s, i) => s + i.value, 0) || 1;
  const cx = Math.min(width, height) / 2;
  const cy = height / 2;
  const r = Math.min(cx, cy) - 30;
  let startAngle = -Math.PI / 2;

  const slices = items.map((item, i) => {
    const angle = (item.value / total) * Math.PI * 2;
    const endAngle = startAngle + angle;
    const largeArc = angle > Math.PI ? 1 : 0;
    const x1 = cx + r * Math.cos(startAngle);
    const y1 = cy + r * Math.sin(startAngle);
    const x2 = cx + r * Math.cos(endAngle);
    const y2 = cy + r * Math.sin(endAngle);
    const path = `M ${cx} ${cy} L ${x1} ${y1} A ${r} ${r} 0 ${largeArc} 1 ${x2} ${y2} Z`;
    const result = { path, color: colors[i % colors.length], item };
    startAngle = endAngle;
    return result;
  });

  return (
    <svg width={width} height={height} viewBox={`0 0 ${width} ${height}`}>
      {slices.map((s, i) => (
        <path key={i} d={s.path} fill={s.color} stroke="white" strokeWidth="1" />
      ))}
      {/* Legend */}
      {items.map((item, i) => (
        <g key={i}>
          <rect
            x={cx * 2 + 10}
            y={10 + i * 18}
            width="10"
            height="10"
            fill={colors[i % colors.length]}
            rx="2"
          />
          <text x={cx * 2 + 24} y={10 + i * 18 + 9} fontSize="9" fill="#374151">
            {item.category} ({Math.round((item.value / total) * 100)}%)
          </text>
        </g>
      ))}
    </svg>
  );
};

export const ReportChartBlock: React.FC<ReportChartBlockProps> = ({ block, mode, data = [] }) => {
  const colors = block.colors?.length ? block.colors : DEFAULT_COLORS;
  const w = block.width || 400;
  const h = block.height || 240;

  // Design mode or no config
  if (mode === 'design' && (!block.categoryField || !block.valueField)) {
    return (
      <div className="rounded border border-dashed border-gray-300 p-4 text-center text-sm text-gray-400">
        <div className="mb-1 font-medium">{block.title || 'Chart'}</div>
        <div>Configure category and value fields</div>
      </div>
    );
  }

  const items =
    mode === 'design'
      ? SAMPLE_DATA
      : aggregateData(data, block.categoryField, block.valueField, block.aggregation || 'sum');

  if (items.length === 0) {
    return <div className="py-4 text-center text-sm text-gray-500">No data</div>;
  }

  return (
    <div>
      {block.title && <div className="mb-2 text-sm font-semibold text-gray-800">{block.title}</div>}
      <div className="inline-block">
        {block.chartType === 'bar' && (
          <BarChart items={items} width={w} height={h} colors={colors} />
        )}
        {block.chartType === 'horizontal-bar' && (
          <HorizontalBarChart items={items} width={w} height={h} colors={colors} />
        )}
        {block.chartType === 'pie' && (
          <PieChart items={items} width={w} height={h} colors={colors} />
        )}
      </div>
    </div>
  );
};
