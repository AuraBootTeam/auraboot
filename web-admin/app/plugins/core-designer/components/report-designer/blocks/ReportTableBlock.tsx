/**
 * ReportTableBlock — renders a table block in design or runtime mode
 */

import React from 'react';
import { useSmartText } from '~/utils/i18n';
import type { DataTableBlock } from '../types';

interface ReportTableBlockProps {
  block: DataTableBlock;
  mode: 'design' | 'runtime';
  data?: Record<string, unknown>[];
}

const SAMPLE_ROWS = 3;

export const ReportTableBlock: React.FC<ReportTableBlockProps> = ({ block, mode, data = [] }) => {
  const text = useSmartText();
  const columns = block.columns;
  const hasColumns = columns.length > 0;

  // Design mode: show placeholder/sample data
  if (mode === 'design') {
    if (!hasColumns) {
      return (
        <div className="rounded border border-dashed border-gray-300 p-4 text-center text-sm text-gray-400">
          <div className="mb-1 font-medium">
            {block.title || text({ zh: '数据表格', en: 'Data Table' })}
          </div>
          <div>
            {text({ zh: '请在属性面板中配置列', en: 'Configure columns in the property panel' })}
          </div>
          {!block.dataSource && (
            <div className="mt-1 text-xs text-amber-500">
              {text({ zh: '尚未选择数据源', en: 'No data source selected' })}
            </div>
          )}
        </div>
      );
    }

    const sampleRows = Array.from({ length: SAMPLE_ROWS }, (_, i) =>
      Object.fromEntries(
        columns.map((col) => [col.field, text({ zh: `示例 ${i + 1}`, en: `Sample ${i + 1}` })]),
      ),
    );

    return (
      <div>
        {block.title && (
          <div className="mb-2 text-sm font-semibold text-gray-800">{block.title}</div>
        )}
        <table className="w-full border-collapse text-xs">
          {block.showHeader !== false && (
            <thead>
              <tr>
                {columns.map((col, i) => (
                  <th
                    key={i}
                    className={`bg-gray-100 px-2 py-1.5 font-semibold text-gray-700 ${
                      block.border !== false ? 'border border-gray-300' : ''
                    }`}
                    style={{
                      textAlign: col.align || 'left',
                      width: col.width ? `${col.width}px` : undefined,
                    }}
                  >
                    {col.label || col.field}
                  </th>
                ))}
              </tr>
            </thead>
          )}
          <tbody>
            {sampleRows.map((row, rowIdx) => (
              <tr
                key={rowIdx}
                className={block.stripe !== false && rowIdx % 2 === 1 ? 'bg-gray-50' : ''}
              >
                {columns.map((col, colIdx) => (
                  <td
                    key={colIdx}
                    className={`px-2 py-1 text-gray-500 ${
                      block.border !== false ? 'border border-gray-300' : ''
                    }`}
                    style={{ textAlign: col.align || 'left' }}
                  >
                    {String(row[col.field] || '')}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
        {!block.dataSource && (
          <div className="mt-1 text-xs text-amber-500">
            {text({ zh: '尚未选择数据源', en: 'No data source selected' })}
          </div>
        )}
      </div>
    );
  }

  // Runtime mode: render actual data
  if (!hasColumns || data.length === 0) {
    return (
      <div className="py-4 text-center text-sm text-gray-500">
        {!hasColumns
          ? text({ zh: '尚未配置列', en: 'No columns configured' })
          : text({ zh: '暂无数据', en: 'No data available' })}
      </div>
    );
  }

  return (
    <div>
      {block.title && <div className="mb-2 text-sm font-semibold text-gray-800">{block.title}</div>}
      <table className="w-full border-collapse text-sm">
        {block.showHeader !== false && (
          <thead>
            <tr>
              {columns.map((col, i) => (
                <th
                  key={i}
                  className={`bg-gray-100 px-3 py-2 font-semibold text-gray-700 ${
                    block.border !== false ? 'border border-gray-300' : ''
                  }`}
                  style={{
                    textAlign: col.align || 'left',
                    width: col.width ? `${col.width}px` : undefined,
                  }}
                >
                  {col.label || col.field}
                </th>
              ))}
            </tr>
          </thead>
        )}
        <tbody>
          {data.map((row, rowIdx) => (
            <tr
              key={rowIdx}
              className={block.stripe !== false && rowIdx % 2 === 1 ? 'bg-gray-50' : ''}
            >
              {columns.map((col, colIdx) => (
                <td
                  key={colIdx}
                  className={`px-3 py-1.5 ${
                    block.border !== false ? 'border border-gray-300' : ''
                  }`}
                  style={{ textAlign: col.align || 'left' }}
                >
                  {formatValue(row[col.field], col.format)}
                </td>
              ))}
            </tr>
          ))}
          {block.summary?.enabled && block.summary.columns.length > 0 && (
            <tr>
              {columns.map((col, colIdx) => {
                const sc = block.summary!.columns.find((c) => c.field === col.field);
                return (
                  <td
                    key={colIdx}
                    className={`bg-gray-200 px-3 py-2 font-bold ${
                      block.border !== false ? 'border border-gray-300' : ''
                    }`}
                    style={{ textAlign: col.align || 'right' }}
                  >
                    {sc
                      ? formatValue(
                          computeAgg(data, sc.field, sc.aggregation),
                          sc.format || col.format,
                        )
                      : colIdx === 0
                        ? block.summary!.label || text({ zh: '合计', en: 'Total' })
                        : ''}
                  </td>
                );
              })}
            </tr>
          )}
        </tbody>
      </table>
    </div>
  );
};

function computeAgg(rows: Record<string, unknown>[], field: string, agg: string): number {
  const values = rows.map((r) => Number(r[field]) || 0);
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

function formatValue(value: unknown, format?: string): string {
  if (value === null || value === undefined) return '';
  if (!format) return String(value);

  if (format === 'number' && typeof value === 'number') return value.toLocaleString();
  if (format === 'currency' && typeof value === 'number')
    return `¥${value.toLocaleString('zh-CN', { minimumFractionDigits: 2 })}`;
  if (format === 'percent' && typeof value === 'number') return `${(value * 100).toFixed(1)}%`;
  if (format === 'date' && value) return new Date(String(value)).toLocaleDateString();

  return String(value);
}
