import { useState, useMemo } from 'react';
import { ChevronUpIcon, ChevronDownIcon, ArrowDownTrayIcon } from '@heroicons/react/24/outline';

export interface PivotData {
  rowHeaders: Array<Record<string, any>>;
  colHeaders: any[];
  cells: any[][];
  rowSubtotals?: any[];
  colSubtotals?: any[];
  grandTotal?: any;
  colDimensionField?: string;
  valueField?: string;
  aggregation?: string;
  totalRecords?: number;
}

interface PivotTableProps {
  data: PivotData;
  title?: string;
  className?: string;
  onExport?: () => void;
}

type SortConfig = {
  colIndex: number | null;
  direction: 'asc' | 'desc';
};

/**
 * PivotTable component — renders cross-tabulation data with
 * sortable columns, expandable headers, subtotals, and grand totals.
 */
export default function PivotTable({ data, title, className = '', onExport }: PivotTableProps) {
  const [sortConfig, setSortConfig] = useState<SortConfig>({ colIndex: null, direction: 'asc' });

  // Get row dimension field names
  const rowDimFields = useMemo(() => {
    if (data.rowHeaders.length === 0) return [];
    return Object.keys(data.rowHeaders[0]);
  }, [data.rowHeaders]);

  // Sorted row indices
  const sortedIndices = useMemo(() => {
    const indices = data.rowHeaders.map((_, i) => i);
    if (sortConfig.colIndex === null) return indices;

    return indices.sort((a, b) => {
      const valA = data.cells[a]?.[sortConfig.colIndex!] ?? 0;
      const valB = data.cells[b]?.[sortConfig.colIndex!] ?? 0;
      const numA = typeof valA === 'number' ? valA : Number(valA) || 0;
      const numB = typeof valB === 'number' ? valB : Number(valB) || 0;
      return sortConfig.direction === 'asc' ? numA - numB : numB - numA;
    });
  }, [data, sortConfig]);

  const handleSort = (colIndex: number) => {
    setSortConfig((prev) => ({
      colIndex,
      direction: prev.colIndex === colIndex && prev.direction === 'asc' ? 'desc' : 'asc',
    }));
  };

  const formatValue = (val: any): string => {
    if (val === null || val === undefined) return '-';
    if (typeof val === 'number') {
      return Number.isInteger(val)
        ? val.toLocaleString()
        : val.toLocaleString(undefined, { maximumFractionDigits: 2 });
    }
    return String(val);
  };

  return (
    <div className={`rounded-lg bg-white shadow dark:bg-gray-800 ${className}`}>
      {/* Header */}
      <div className="flex items-center justify-between border-b px-4 py-3 dark:border-gray-700">
        <div>
          {title && (
            <h3 className="text-lg font-semibold text-gray-800 dark:text-gray-200">{title}</h3>
          )}
          <div className="text-sm text-gray-500">
            {data.totalRecords !== undefined && `${data.totalRecords} records`}
            {data.aggregation && data.valueField && ` | ${data.aggregation}(${data.valueField})`}
          </div>
        </div>
        {onExport && (
          <button
            onClick={onExport}
            className="flex items-center gap-1 rounded bg-blue-50 px-3 py-1.5 text-sm text-blue-600 transition-colors hover:bg-blue-100"
          >
            <ArrowDownTrayIcon className="h-4 w-4" />
            Export
          </button>
        )}
      </div>

      {/* Table */}
      <div className="max-h-[600px] overflow-auto">
        <table className="w-full text-sm">
          <thead className="sticky top-0 bg-gray-50 dark:bg-gray-700">
            <tr>
              {/* Row dimension headers */}
              {rowDimFields.map((field) => (
                <th
                  key={field}
                  className="border-r px-3 py-2 text-left font-medium text-gray-600 dark:border-gray-600 dark:text-gray-300"
                >
                  {field}
                </th>
              ))}
              {/* Column dimension headers */}
              {data.colHeaders.map((colHeader, ci) => (
                <th
                  key={ci}
                  className="cursor-pointer px-3 py-2 text-right font-medium text-gray-600 transition-colors hover:bg-gray-100 dark:text-gray-300 dark:hover:bg-gray-600"
                  onClick={() => handleSort(ci)}
                >
                  <div className="flex items-center justify-end gap-1">
                    <span>{formatValue(colHeader)}</span>
                    {sortConfig.colIndex === ci &&
                      (sortConfig.direction === 'asc' ? (
                        <ChevronUpIcon className="h-3 w-3" />
                      ) : (
                        <ChevronDownIcon className="h-3 w-3" />
                      ))}
                  </div>
                </th>
              ))}
              {/* Subtotal column */}
              {data.rowSubtotals && (
                <th className="bg-gray-100 px-3 py-2 text-right font-bold text-gray-700 dark:bg-gray-600 dark:text-gray-200">
                  Total
                </th>
              )}
            </tr>
          </thead>
          <tbody>
            {sortedIndices.map((rowIdx) => (
              <tr
                key={rowIdx}
                className="dark:hover:bg-gray-750 border-t transition-colors hover:bg-gray-50 dark:border-gray-700"
              >
                {/* Row dimension values */}
                {rowDimFields.map((field) => (
                  <td
                    key={field}
                    className="border-r px-3 py-2 font-medium text-gray-800 dark:border-gray-700 dark:text-gray-200"
                  >
                    {formatValue(data.rowHeaders[rowIdx][field])}
                  </td>
                ))}
                {/* Cell values */}
                {data.cells[rowIdx]?.map((cellVal, ci) => (
                  <td
                    key={ci}
                    className="px-3 py-2 text-right text-gray-700 tabular-nums dark:text-gray-300"
                  >
                    {formatValue(cellVal)}
                  </td>
                ))}
                {/* Row subtotal */}
                {data.rowSubtotals && (
                  <td className="bg-gray-50 px-3 py-2 text-right font-bold text-gray-800 tabular-nums dark:bg-gray-700 dark:text-gray-200">
                    {formatValue(data.rowSubtotals[rowIdx])}
                  </td>
                )}
              </tr>
            ))}

            {/* Column subtotals row */}
            {data.colSubtotals && (
              <tr className="border-t-2 bg-gray-100 font-bold dark:border-gray-600 dark:bg-gray-700">
                <td
                  colSpan={rowDimFields.length}
                  className="border-r px-3 py-2 text-gray-700 dark:border-gray-600 dark:text-gray-200"
                >
                  Total
                </td>
                {data.colSubtotals.map((val, ci) => (
                  <td
                    key={ci}
                    className="px-3 py-2 text-right text-gray-800 tabular-nums dark:text-gray-200"
                  >
                    {formatValue(val)}
                  </td>
                ))}
                {/* Grand total */}
                {data.grandTotal !== undefined && data.grandTotal !== null && (
                  <td className="bg-blue-50 px-3 py-2 text-right text-blue-700 tabular-nums dark:bg-gray-600 dark:text-blue-400">
                    {formatValue(data.grandTotal)}
                  </td>
                )}
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {/* Empty state */}
      {data.rowHeaders.length === 0 && (
        <div className="flex items-center justify-center py-12 text-gray-400">
          No data available for the selected dimensions
        </div>
      )}
    </div>
  );
}
