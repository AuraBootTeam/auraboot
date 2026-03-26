/**
 * ReportCrossTabBlock — pivot/cross-tab table
 * Rows = rowField values, Columns = columnField values, Cells = aggregated valueField
 */

import React from 'react';
import type { CrossTabBlock } from '../types';

interface ReportCrossTabBlockProps {
  block: CrossTabBlock;
  mode: 'design' | 'runtime';
  data?: Record<string, unknown>[];
}

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
      return 0;
  }
}

function formatVal(value: number, format?: string): string {
  if (!format) return value.toLocaleString();
  if (format === 'currency')
    return `¥${value.toLocaleString('zh-CN', { minimumFractionDigits: 2 })}`;
  if (format === 'percent') return `${(value * 100).toFixed(1)}%`;
  return value.toLocaleString();
}

function buildPivot(data: Record<string, unknown>[], block: CrossTabBlock) {
  const rowKeys = new Set<string>();
  const colKeys = new Set<string>();
  const cells = new Map<string, number[]>();

  for (const row of data) {
    const rk = String(row[block.rowField] ?? 'Other');
    const ck = String(row[block.columnField] ?? 'Other');
    const val = Number(row[block.valueField]) || 0;
    rowKeys.add(rk);
    colKeys.add(ck);
    const key = `${rk}|${ck}`;
    if (!cells.has(key)) cells.set(key, []);
    cells.get(key)!.push(val);
  }

  return {
    rowKeys: Array.from(rowKeys).sort(),
    colKeys: Array.from(colKeys).sort(),
    cells,
  };
}

export const ReportCrossTabBlock: React.FC<ReportCrossTabBlockProps> = ({
  block,
  mode,
  data = [],
}) => {
  // Design mode placeholder
  if (mode === 'design') {
    if (!block.rowField || !block.columnField || !block.valueField) {
      return (
        <div className="rounded border border-dashed border-gray-300 p-4 text-center text-sm text-gray-400">
          <div className="mb-1 font-medium">{block.title || 'Cross Tab'}</div>
          <div>Configure row, column, and value fields</div>
        </div>
      );
    }

    // Show sample pivot
    return (
      <div>
        {block.title && (
          <div className="mb-2 text-sm font-semibold text-gray-800">{block.title}</div>
        )}
        <table className="border-collapse border border-gray-300 text-xs">
          <thead>
            <tr>
              <th className="border border-gray-300 bg-gray-200 px-2 py-1 text-left">
                {block.rowField} \ {block.columnField}
              </th>
              <th className="border border-gray-300 bg-blue-50 px-2 py-1">Col A</th>
              <th className="border border-gray-300 bg-blue-50 px-2 py-1">Col B</th>
              {block.showRowTotal && (
                <th className="border border-gray-300 bg-gray-100 px-2 py-1">Total</th>
              )}
            </tr>
          </thead>
          <tbody>
            <tr>
              <td className="border border-gray-300 bg-gray-50 px-2 py-1 font-medium">Row 1</td>
              <td className="border border-gray-300 px-2 py-1 text-right text-gray-500">123</td>
              <td className="border border-gray-300 px-2 py-1 text-right text-gray-500">456</td>
              {block.showRowTotal && (
                <td className="border border-gray-300 px-2 py-1 text-right font-medium text-gray-600">
                  579
                </td>
              )}
            </tr>
            <tr>
              <td className="border border-gray-300 bg-gray-50 px-2 py-1 font-medium">Row 2</td>
              <td className="border border-gray-300 px-2 py-1 text-right text-gray-500">789</td>
              <td className="border border-gray-300 px-2 py-1 text-right text-gray-500">101</td>
              {block.showRowTotal && (
                <td className="border border-gray-300 px-2 py-1 text-right font-medium text-gray-600">
                  890
                </td>
              )}
            </tr>
            {block.showColumnTotal && (
              <tr>
                <td className="border border-gray-300 bg-gray-100 px-2 py-1 font-bold">Total</td>
                <td className="border border-gray-300 bg-gray-100 px-2 py-1 text-right font-bold text-gray-600">
                  912
                </td>
                <td className="border border-gray-300 bg-gray-100 px-2 py-1 text-right font-bold text-gray-600">
                  557
                </td>
                {block.showRowTotal && (
                  <td className="border border-gray-300 bg-gray-200 px-2 py-1 text-right font-bold">
                    1,469
                  </td>
                )}
              </tr>
            )}
          </tbody>
        </table>
      </div>
    );
  }

  // Runtime
  if (!block.rowField || !block.columnField || !block.valueField || data.length === 0) {
    return <div className="py-4 text-center text-sm text-gray-500">No data available</div>;
  }

  const { rowKeys, colKeys, cells } = buildPivot(data, block);

  const getVal = (rk: string, ck: string) => {
    const vals = cells.get(`${rk}|${ck}`) || [];
    return computeAgg(vals, block.aggregation);
  };

  const getRowTotal = (rk: string) => {
    const vals: number[] = [];
    for (const ck of colKeys) {
      vals.push(getVal(rk, ck));
    }
    return vals.reduce((a, b) => a + b, 0);
  };

  const getColTotal = (ck: string) => {
    const vals: number[] = [];
    for (const rk of rowKeys) {
      vals.push(getVal(rk, ck));
    }
    return vals.reduce((a, b) => a + b, 0);
  };

  const grandTotal = rowKeys.reduce((sum, rk) => sum + getRowTotal(rk), 0);

  return (
    <div>
      {block.title && <div className="mb-2 text-sm font-semibold text-gray-800">{block.title}</div>}
      <table className="border-collapse border border-gray-300 text-sm">
        <thead>
          <tr>
            <th className="border border-gray-300 bg-gray-200 px-3 py-2 text-left">
              {block.rowField} \ {block.columnField}
            </th>
            {colKeys.map((ck) => (
              <th key={ck} className="border border-gray-300 bg-blue-50 px-3 py-2 text-right">
                {ck}
              </th>
            ))}
            {block.showRowTotal && (
              <th className="border border-gray-300 bg-gray-100 px-3 py-2 text-right font-bold">
                Total
              </th>
            )}
          </tr>
        </thead>
        <tbody>
          {rowKeys.map((rk) => (
            <tr key={rk}>
              <td className="border border-gray-300 bg-gray-50 px-3 py-1.5 font-medium">{rk}</td>
              {colKeys.map((ck) => (
                <td key={ck} className="border border-gray-300 px-3 py-1.5 text-right">
                  {formatVal(getVal(rk, ck), block.format)}
                </td>
              ))}
              {block.showRowTotal && (
                <td className="border border-gray-300 bg-gray-50 px-3 py-1.5 text-right font-medium">
                  {formatVal(getRowTotal(rk), block.format)}
                </td>
              )}
            </tr>
          ))}
          {block.showColumnTotal && (
            <tr>
              <td className="border border-gray-300 bg-gray-100 px-3 py-2 font-bold">Total</td>
              {colKeys.map((ck) => (
                <td
                  key={ck}
                  className="border border-gray-300 bg-gray-100 px-3 py-2 text-right font-bold"
                >
                  {formatVal(getColTotal(ck), block.format)}
                </td>
              ))}
              {block.showRowTotal && (
                <td className="border border-gray-300 bg-gray-200 px-3 py-2 text-right font-bold">
                  {formatVal(grandTotal, block.format)}
                </td>
              )}
            </tr>
          )}
        </tbody>
      </table>
    </div>
  );
};
