/**
 * ReportGroupedTableBlock — renders a grouped-table block
 * Groups rows by a field, shows group headers and optional subtotals
 */

import React from 'react';
import type { GroupedTableBlock, SummaryColumnConfig } from '../types';

interface ReportGroupedTableBlockProps {
  block: GroupedTableBlock;
  mode: 'design' | 'runtime';
  data?: Record<string, unknown>[];
}

function computeAggregation(rows: Record<string, unknown>[], field: string, agg: string): number {
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
  return String(value);
}

function groupData(
  data: Record<string, unknown>[],
  field: string,
): Map<string, Record<string, unknown>[]> {
  const groups = new Map<string, Record<string, unknown>[]>();
  for (const row of data) {
    const key = String(row[field] ?? 'Other');
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key)!.push(row);
  }
  return groups;
}

const SAMPLE_GROUPS = [
  { group: 'Group A', rows: [{ sample: 'Row 1' }, { sample: 'Row 2' }] },
  { group: 'Group B', rows: [{ sample: 'Row 3' }] },
];

export const ReportGroupedTableBlock: React.FC<ReportGroupedTableBlockProps> = ({
  block,
  mode,
  data = [],
}) => {
  const columns = block.columns;
  const hasColumns = columns.length > 0;
  const cellBorder = block.border !== false ? 'border border-gray-300' : '';

  // Design mode placeholder
  if (mode === 'design') {
    if (!hasColumns || !block.groupByField) {
      return (
        <div className="rounded border border-dashed border-gray-300 p-4 text-center text-sm text-gray-400">
          <div className="mb-1 font-medium">{block.title || 'Grouped Table'}</div>
          <div>
            {!block.groupByField ? 'Select a group-by field' : 'Configure columns'} in the property
            panel
          </div>
        </div>
      );
    }

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
                    className={`bg-gray-100 px-2 py-1.5 font-semibold text-gray-700 ${cellBorder}`}
                    style={{ textAlign: col.align || 'left' }}
                  >
                    {col.label || col.field}
                  </th>
                ))}
              </tr>
            </thead>
          )}
          <tbody>
            {SAMPLE_GROUPS.map((g) => (
              <React.Fragment key={g.group}>
                <tr>
                  <td
                    colSpan={columns.length}
                    className={`bg-blue-50 px-2 py-1.5 font-semibold text-blue-800 ${cellBorder}`}
                  >
                    {block.groupByField}: {g.group}
                  </td>
                </tr>
                {g.rows.map((_, rowIdx) => (
                  <tr key={rowIdx}>
                    {columns.map((col, colIdx) => (
                      <td
                        key={colIdx}
                        className={`px-2 py-1 text-gray-500 ${cellBorder}`}
                        style={{ textAlign: col.align || 'left' }}
                      >
                        Sample
                      </td>
                    ))}
                  </tr>
                ))}
                {block.groupSubtotal?.enabled && (
                  <tr>
                    {columns.map((col, colIdx) => {
                      const sc = block.groupSubtotal?.columns.find((c) => c.field === col.field);
                      return (
                        <td
                          key={colIdx}
                          className={`bg-gray-50 px-2 py-1 font-medium text-gray-600 ${cellBorder}`}
                          style={{ textAlign: col.align || 'left' }}
                        >
                          {colIdx === 0 && !sc ? 'Subtotal' : sc ? `[${sc.aggregation}]` : ''}
                        </td>
                      );
                    })}
                  </tr>
                )}
              </React.Fragment>
            ))}
          </tbody>
        </table>
      </div>
    );
  }

  // Runtime mode
  if (!hasColumns || !block.groupByField || data.length === 0) {
    return <div className="py-4 text-center text-sm text-gray-500">No data available</div>;
  }

  const groups = groupData(data, block.groupByField);

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
                  className={`bg-gray-100 px-3 py-2 font-semibold text-gray-700 ${cellBorder}`}
                  style={{ textAlign: col.align || 'left' }}
                >
                  {col.label || col.field}
                </th>
              ))}
            </tr>
          </thead>
        )}
        <tbody>
          {Array.from(groups.entries()).map(([groupKey, rows]) => (
            <React.Fragment key={groupKey}>
              <tr>
                <td
                  colSpan={columns.length}
                  className={`bg-blue-50 px-3 py-2 font-semibold text-blue-800 ${cellBorder}`}
                >
                  {block.groupByField}: {groupKey} ({rows.length})
                </td>
              </tr>
              {rows.map((row, rowIdx) => (
                <tr key={rowIdx}>
                  {columns.map((col, colIdx) => (
                    <td
                      key={colIdx}
                      className={`px-3 py-1.5 ${cellBorder}`}
                      style={{ textAlign: col.align || 'left' }}
                    >
                      {formatValue(row[col.field], col.format)}
                    </td>
                  ))}
                </tr>
              ))}
              {block.groupSubtotal?.enabled && (
                <tr>
                  {columns.map((col, colIdx) => {
                    const sc = block.groupSubtotal?.columns.find((c) => c.field === col.field);
                    return (
                      <td
                        key={colIdx}
                        className={`bg-gray-50 px-3 py-1.5 font-medium ${cellBorder}`}
                        style={{ textAlign: col.align || 'right' }}
                      >
                        {colIdx === 0 && !sc ? block.groupSubtotal?.label || 'Subtotal' : ''}
                        {sc
                          ? formatValue(
                              computeAggregation(rows, sc.field, sc.aggregation),
                              sc.format || col.format,
                            )
                          : ''}
                      </td>
                    );
                  })}
                </tr>
              )}
            </React.Fragment>
          ))}
          {block.grandTotal?.enabled && (
            <tr>
              {columns.map((col, colIdx) => {
                const sc = block.grandTotal?.columns.find((c) => c.field === col.field);
                return (
                  <td
                    key={colIdx}
                    className={`bg-gray-200 px-3 py-2 font-bold ${cellBorder}`}
                    style={{ textAlign: col.align || 'right' }}
                  >
                    {colIdx === 0 && !sc ? block.grandTotal?.label || 'Grand Total' : ''}
                    {sc
                      ? formatValue(
                          computeAggregation(data, sc.field, sc.aggregation),
                          sc.format || col.format,
                        )
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
