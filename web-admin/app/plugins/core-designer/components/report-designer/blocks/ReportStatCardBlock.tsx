/**
 * ReportStatCardBlock — KPI stat card for report headers
 */

import React from 'react';
import type { StatCardBlock } from '../types';

interface ReportStatCardBlockProps {
  block: StatCardBlock;
  mode: 'design' | 'runtime';
  data?: Record<string, unknown>[];
}

function computeValue(data: Record<string, unknown>[], field: string, agg: string): number {
  const values = data.map((r) => Number(r[field]) || 0);
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
      return 0;
  }
}

function formatValue(value: number, format?: string): string {
  if (!format) return value.toLocaleString();
  if (format === 'currency')
    return `¥${value.toLocaleString('zh-CN', { minimumFractionDigits: 2 })}`;
  if (format === 'percent') return `${(value * 100).toFixed(1)}%`;
  if (format === 'number') return value.toLocaleString();
  return String(value);
}

const COLOR_MAP: Record<string, { bg: string; text: string; border: string }> = {
  blue: { bg: 'bg-blue-50', text: 'text-blue-700', border: 'border-blue-200' },
  green: { bg: 'bg-green-50', text: 'text-green-700', border: 'border-green-200' },
  orange: { bg: 'bg-orange-50', text: 'text-orange-700', border: 'border-orange-200' },
  red: { bg: 'bg-red-50', text: 'text-red-700', border: 'border-red-200' },
  purple: { bg: 'bg-purple-50', text: 'text-purple-700', border: 'border-purple-200' },
  gray: { bg: 'bg-gray-50', text: 'text-gray-700', border: 'border-gray-200' },
};

export const ReportStatCardBlock: React.FC<ReportStatCardBlockProps> = ({
  block,
  mode,
  data = [],
}) => {
  const colors = COLOR_MAP[block.color || 'blue'] || COLOR_MAP.blue;
  const value = mode === 'design' ? 12345 : computeValue(data, block.valueField, block.aggregation);

  return (
    <div
      className={`inline-block min-w-[140px] rounded-lg border p-4 ${colors.bg} ${colors.border}`}
    >
      <div className="mb-1 text-xs tracking-wider text-gray-500 uppercase">
        {block.label || block.title || 'Metric'}
      </div>
      <div className={`text-2xl font-bold ${colors.text}`}>{formatValue(value, block.format)}</div>
      {mode === 'design' && !block.valueField && (
        <div className="mt-1 text-xs text-amber-500">Configure value field</div>
      )}
    </div>
  );
};
