/**
 * ACP ResultContract Renderer
 *
 * Renders tool execution results in a structured way based on data shape:
 * - records array → table
 * - summary text → card
 * - error → error message
 * - raw data → collapsible JSON
 *
 * This is the frontend counterpart of the backend ResultContract DTO.
 * It auto-detects the result shape and renders the most appropriate view.
 */

import { useState } from 'react';

interface ResultContractRendererProps {
  result: Record<string, unknown>;
  toolName?: string;
}

export function ResultContractRenderer({ result, toolName }: ResultContractRendererProps) {
  if (!result) return null;

  // Error result
  if (result.error || result.success === false) {
    return (
      <div className="mt-2 rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-700">
        {String(result.error ?? result.message ?? 'Execution failed')}
      </div>
    );
  }

  // Records array → table
  const records = result.records as Record<string, unknown>[] | undefined;
  if (records && Array.isArray(records) && records.length > 0) {
    return (
      <RecordsTable
        records={records}
        total={result.total as number}
        returned={(result.returned as number) || records.length}
      />
    );
  }

  // Models list (from builtin__list_models)
  const models = result.models as Record<string, unknown>[] | undefined;
  if (models && Array.isArray(models) && models.length > 0) {
    return <ModelsCard models={models} total={result.total as number} />;
  }

  // Command success with data
  if (result.success === true && result.data) {
    return (
      <div className="mt-2 rounded-md border border-green-200 bg-green-50 p-3 text-sm">
        <span className="font-medium text-green-800">Success</span>
        {typeof result.commandCode === 'string' && (
          <span className="ml-2 text-green-600">({result.commandCode})</span>
        )}
      </div>
    );
  }

  // Fallback: collapsible JSON
  return <JsonFallback data={result} />;
}

// ========== Sub-components ==========

function RecordsTable({
  records,
  total,
  returned,
}: {
  records: Record<string, unknown>[];
  total?: number;
  returned: number;
}) {
  const [expanded, setExpanded] = useState(true);

  // Extract column headers from first record, filter out system fields
  const systemFields = new Set([
    'id',
    'pid',
    'tenant_id',
    'created_at',
    'updated_at',
    'created_by',
    'updated_by',
    'deleted_flag',
  ]);
  const allKeys = Object.keys(records[0]).filter((k) => !systemFields.has(k));
  // Show max 6 columns
  const columns = allKeys.slice(0, 6);

  return (
    <div className="mt-2 rounded-md border border-gray-200 bg-white">
      <div
        className="flex cursor-pointer items-center justify-between border-b border-gray-100 px-3 py-2"
        onClick={() => setExpanded(!expanded)}
      >
        <span className="text-sm font-medium text-gray-700">
          {total !== undefined ? `${returned} of ${total} records` : `${returned} records`}
        </span>
        <svg
          className={`h-4 w-4 text-gray-400 transition-transform ${expanded ? 'rotate-180' : ''}`}
          fill="none"
          stroke="currentColor"
          viewBox="0 0 24 24"
        >
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
        </svg>
      </div>

      {expanded && (
        <div className="overflow-x-auto">
          <table className="min-w-full text-sm">
            <thead>
              <tr className="border-b bg-gray-50">
                {columns.map((col) => (
                  <th key={col} className="px-3 py-1.5 text-left font-medium text-gray-600">
                    {col}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {records.slice(0, 10).map((row, i) => (
                <tr key={i} className="border-b border-gray-50 hover:bg-gray-50/50">
                  {columns.map((col) => (
                    <td key={col} className="max-w-[200px] truncate px-3 py-1.5 text-gray-700">
                      {formatCellValue(row[col])}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
          {records.length > 10 && (
            <div className="border-t px-3 py-1.5 text-center text-xs text-gray-400">
              Showing 10 of {records.length} returned records
            </div>
          )}
        </div>
      )}
    </div>
  );
}

function ModelsCard({ models, total }: { models: Record<string, unknown>[]; total?: number }) {
  return (
    <div className="mt-2 rounded-md border border-blue-200 bg-blue-50 p-3">
      <div className="mb-2 text-sm font-medium text-blue-800">
        {total || models.length} models found
      </div>
      <div className="flex flex-wrap gap-1.5">
        {models.slice(0, 20).map((m, i) => (
          <span key={i} className="rounded bg-blue-100 px-2 py-0.5 text-xs text-blue-700">
            {String(m.displayName || m.code || m.tableName)}
          </span>
        ))}
        {models.length > 20 && (
          <span className="text-xs text-blue-400">+{models.length - 20} more</span>
        )}
      </div>
    </div>
  );
}

function JsonFallback({ data }: { data: unknown }) {
  const [expanded, setExpanded] = useState(false);

  return (
    <div className="mt-2">
      <button
        onClick={() => setExpanded(!expanded)}
        className="flex items-center space-x-1 text-sm text-gray-500 hover:text-gray-700"
      >
        <span>{expanded ? 'Hide' : 'Show'} raw data</span>
        <svg
          className={`h-3 w-3 transition-transform ${expanded ? 'rotate-180' : ''}`}
          fill="none"
          stroke="currentColor"
          viewBox="0 0 24 24"
        >
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
        </svg>
      </button>
      {expanded && (
        <pre className="mt-1 max-h-48 overflow-auto rounded border bg-gray-50 p-2 text-xs text-gray-600">
          {JSON.stringify(data, null, 2)}
        </pre>
      )}
    </div>
  );
}

function formatCellValue(value: unknown): string {
  if (value === null || value === undefined) return '-';
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
}
