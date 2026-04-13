/**
 * Data Table Preview
 *
 * Preview component for table blocks.
 */

import React from 'react';
import type { DslBlock, DslColumnRef } from '~/studio/domain/dsl/types';
import { parseColumnShorthand } from '~/studio/domain/dsl/types';

export interface DataTablePreviewProps {
  block: DslBlock;
}

export const DataTablePreview: React.FC<DataTablePreviewProps> = ({ block }) => {
  const columns = block.columns || [];
  const hasSelection = !!block.selection;

  // Parse columns
  const parsedColumns = columns.slice(0, 6).map((col) => parseColumnShorthand(col));

  return (
    <div className="bg-white">
      {/* Table header */}
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-gray-200 bg-gray-50">
              {hasSelection && (
                <th className="w-10 px-3 py-2">
                  <input type="checkbox" disabled className="rounded border-gray-300" />
                </th>
              )}
              {parsedColumns.length === 0 ? (
                <th className="px-3 py-2 text-left font-normal text-gray-400">
                  点击右侧面板添加列
                </th>
              ) : (
                parsedColumns.map((col, index) => (
                  <th
                    key={col.field || index}
                    className="px-3 py-2 text-left font-medium text-gray-600"
                    style={{ width: col.width }}
                  >
                    <div className="flex items-center gap-1">
                      <span>{col.field}</span>
                      {col.sortable && (
                        <svg
                          className="h-3 w-3 text-gray-400"
                          fill="none"
                          stroke="currentColor"
                          viewBox="0 0 24 24"
                        >
                          <path
                            strokeLinecap="round"
                            strokeLinejoin="round"
                            strokeWidth={2}
                            d="M7 16V4m0 0L3 8m4-4l4 4m6 0v12m0 0l4-4m-4 4l-4-4"
                          />
                        </svg>
                      )}
                      {col.fixed && (
                        <svg
                          className="h-3 w-3 text-blue-400"
                          fill="none"
                          stroke="currentColor"
                          viewBox="0 0 24 24"
                        >
                          <path
                            strokeLinecap="round"
                            strokeLinejoin="round"
                            strokeWidth={2}
                            d="M5 5a2 2 0 012-2h10a2 2 0 012 2v16l-7-3.5L5 21V5z"
                          />
                        </svg>
                      )}
                    </div>
                  </th>
                ))
              )}
              {columns.length > 6 && (
                <th className="px-3 py-2 text-xs font-normal text-gray-400">
                  +{columns.length - 6}
                </th>
              )}
            </tr>
          </thead>
          <tbody>
            {/* Mock rows */}
            {[1, 2, 3].map((row) => (
              <tr key={row} className="border-b border-gray-100">
                {hasSelection && (
                  <td className="px-3 py-2">
                    <input type="checkbox" disabled className="rounded border-gray-300" />
                  </td>
                )}
                {parsedColumns.length === 0 ? (
                  <td className="px-3 py-2 text-gray-300">--</td>
                ) : (
                  parsedColumns.map((col, index) => (
                    <td key={col.field || index} className="px-3 py-2">
                      <CellPreview column={col} rowIndex={row} />
                    </td>
                  ))
                )}
                {columns.length > 6 && <td className="px-3 py-2"></td>}
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {/* Pagination hint */}
      <div className="flex items-center justify-between border-t border-gray-100 px-3 py-2 text-xs text-gray-400">
        <span>共 -- 条</span>
        <div className="flex items-center gap-2">
          <span>每页 10 条</span>
          <span>第 1 页</span>
        </div>
      </div>
    </div>
  );
};

/**
 * Cell preview based on column config
 */
interface CellPreviewProps {
  column: {
    field: string;
    render?: string;
    copyable?: boolean;
    actions?: unknown[];
  };
  rowIndex: number;
}

const CellPreview: React.FC<CellPreviewProps> = ({ column, rowIndex }) => {
  // Actions column
  if (column.field === '$actions' || column.actions) {
    return (
      <div className="flex items-center gap-2">
        <span className="cursor-pointer text-blue-500">查看</span>
        <span className="cursor-pointer text-blue-500">编辑</span>
      </div>
    );
  }

  // Render type preview
  switch (column.render) {
    case 'tag':
      return (
        <span className="inline-flex rounded-full bg-green-100 px-2 py-0.5 text-xs text-green-700">
          状态
        </span>
      );
    case 'datetime':
      return <span className="text-gray-500">2024-01-01 12:00</span>;
    case 'currency':
      return <span className="text-gray-900">¥ 1,000.00</span>;
    default:
      return (
        <span className="flex items-center gap-1 text-gray-400">
          --
          {column.copyable && (
            <svg
              className="h-3 w-3 text-gray-300"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z"
              />
            </svg>
          )}
        </span>
      );
  }
};

export default DataTablePreview;
