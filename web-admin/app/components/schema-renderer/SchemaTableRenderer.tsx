/**
 * Schema Table Region Renderer
 *
 * Renders the data table section of a schema-driven page,
 * including headers, rows, cell content rendering, and row actions.
 */

import React, { useCallback } from 'react';
import { Link } from 'react-router';
import type { DynamicEntity } from '~/types/dynamic';
import type {
  TableRendererProps,
  ColumnDefinition,
  ActionDefinition,
  LocalizedText,
} from './types';

/**
 * Loading spinner shown while data is being fetched
 */
function LoadingSpinner({ colSpan }: { colSpan: number }) {
  return (
    <tr>
      <td colSpan={colSpan} className="px-6 py-4 text-center">
        <div className="flex items-center justify-center">
          <svg
            className="mr-2 h-5 w-5 animate-spin text-blue-600"
            xmlns="http://www.w3.org/2000/svg"
            fill="none"
            viewBox="0 0 24 24"
          >
            <circle
              className="opacity-25"
              cx="12"
              cy="12"
              r="10"
              stroke="currentColor"
              strokeWidth="4"
            />
            <path
              className="opacity-75"
              fill="currentColor"
              d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"
            />
          </svg>
          Loading...
        </div>
      </td>
    </tr>
  );
}

/**
 * Empty state shown when no data is available
 */
function EmptyState({ colSpan }: { colSpan: number }) {
  return (
    <tr>
      <td colSpan={colSpan} className="px-6 py-4 text-center text-gray-500">
        No data available
      </td>
    </tr>
  );
}

/**
 * Row action buttons
 */
function RowActions({
  actions,
  record,
  onRowAction,
  getLocalizedText,
}: {
  actions: ActionDefinition[];
  record: DynamicEntity;
  onRowAction?: (action: ActionDefinition, record: DynamicEntity) => void;
  getLocalizedText: (text: LocalizedText, fallback?: string) => string;
}) {
  if (!actions || actions.length === 0) {
    return null;
  }

  return (
    <div className="flex space-x-2">
      {actions.map((action, index) => (
        <button
          key={action.code || index}
          onClick={(e) => {
            e.stopPropagation();
            onRowAction?.(action, record);
          }}
          className="text-sm text-blue-600 hover:text-blue-800"
        >
          {getLocalizedText(action.label, action.code)}
        </button>
      ))}
    </div>
  );
}

/**
 * Renders cell content based on column type
 */
function CellContent({
  column,
  record,
  entityCode,
  onRowAction,
  getLocalizedText,
}: {
  column: ColumnDefinition;
  record: DynamicEntity;
  entityCode?: string;
  onRowAction?: (action: ActionDefinition, record: DynamicEntity) => void;
  getLocalizedText: (text: LocalizedText, fallback?: string) => string;
}) {
  const fieldKey = column.code || column.field || column.dataIndex || '';
  const value = record[fieldKey];

  if (value === null || value === undefined) {
    return <span className="text-gray-400">-</span>;
  }

  const cellType = column.type || column.render;

  switch (cellType) {
    case 'date':
      return <>{new Date(value as string).toLocaleDateString()}</>;

    case 'datetime':
      return <>{new Date(value as string).toLocaleString()}</>;

    case 'boolean':
      return (
        <span
          className={`inline-flex rounded-full px-2 py-1 text-xs font-medium ${
            value ? 'bg-green-100 text-green-800' : 'bg-red-100 text-red-800'
          }`}
        >
          {value ? 'Yes' : 'No'}
        </span>
      );

    case 'status':
      return (
        <span className="inline-flex rounded-full bg-blue-100 px-2 py-1 text-xs font-medium text-blue-800">
          {String(value)}
        </span>
      );

    case 'link':
      return (
        <Link
          to={`/dynamic/${entityCode}/${record.id}`}
          className="text-blue-600 underline hover:text-blue-800"
        >
          {String(value)}
        </Link>
      );

    case 'actions':
      return (
        <RowActions
          actions={column.actions || []}
          record={record}
          onRowAction={onRowAction}
          getLocalizedText={getLocalizedText}
        />
      );

    default:
      return <>{String(value)}</>;
  }
}

/**
 * Table header row
 */
function TableHeader({
  columns,
  getLocalizedText,
}: {
  columns: ColumnDefinition[];
  getLocalizedText: (text: LocalizedText, fallback?: string) => string;
}) {
  return (
    <thead className="bg-gray-50">
      <tr>
        {columns.map((column, index) => (
          <th
            key={column.code || index}
            className="px-6 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase"
          >
            {getLocalizedText(column.label, column.code || '')}
          </th>
        ))}
      </tr>
    </thead>
  );
}

/**
 * Table body with data rows
 */
function TableBody({
  columns,
  data,
  loading,
  entityCode,
  onRowClick,
  onRowAction,
  getLocalizedText,
}: {
  columns: ColumnDefinition[];
  data: DynamicEntity[];
  loading: boolean;
  entityCode?: string;
  onRowClick?: (record: DynamicEntity) => void;
  onRowAction?: (action: ActionDefinition, record: DynamicEntity) => void;
  getLocalizedText: (text: LocalizedText, fallback?: string) => string;
}) {
  if (loading) {
    return (
      <tbody>
        <LoadingSpinner colSpan={columns.length} />
      </tbody>
    );
  }

  if (data.length === 0) {
    return (
      <tbody>
        <EmptyState colSpan={columns.length} />
      </tbody>
    );
  }

  return (
    <tbody className="divide-y divide-gray-200 bg-white">
      {data.map((record, rowIndex) => (
        <tr
          key={record.id || rowIndex}
          onClick={() => onRowClick?.(record)}
          className={onRowClick ? 'cursor-pointer hover:bg-gray-50' : ''}
        >
          {columns.map((column, colIndex) => (
            <td
              key={column.code || colIndex}
              className="px-6 py-4 text-sm whitespace-nowrap text-gray-900"
            >
              <CellContent
                column={column}
                record={record}
                entityCode={entityCode}
                onRowAction={onRowAction}
                getLocalizedText={getLocalizedText}
              />
            </td>
          ))}
        </tr>
      ))}
    </tbody>
  );
}

/**
 * Schema Table Region Renderer
 *
 * Renders a complete data table with headers, rows, and loading/empty states.
 */
export function SchemaTableRenderer({
  region,
  data,
  loading,
  schema,
  onRowClick,
  onRowAction,
  getLocalizedText,
}: TableRendererProps) {
  if (!region.columns || region.columns.length === 0) {
    return null;
  }

  const entityCode = schema?.meta?.entityCode;

  return (
    <div className="overflow-x-auto">
      <table className="min-w-full divide-y divide-gray-200">
        <TableHeader columns={region.columns} getLocalizedText={getLocalizedText} />
        <TableBody
          columns={region.columns}
          data={data}
          loading={loading}
          entityCode={entityCode}
          onRowClick={onRowClick}
          onRowAction={onRowAction}
          getLocalizedText={getLocalizedText}
        />
      </table>
    </div>
  );
}

export default SchemaTableRenderer;
