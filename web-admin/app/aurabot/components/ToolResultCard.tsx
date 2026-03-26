/**
 * ToolResultCard
 *
 * Collapsible card showing tool query results (success/failure).
 * Renders a compact table for record arrays; raw JSON otherwise.
 *
 * @since 1.0.0
 */

import { useState } from 'react';
import { ChevronDown, ChevronRight, CheckCircle, XCircle } from 'lucide-react';

// ============================================================================
// Types
// ============================================================================

interface ToolResultCardProps {
  toolName: string;
  result: Record<string, any>;
  success: boolean;
}

// ============================================================================
// Helpers
// ============================================================================

/** Strip prefix (cmd__, nq__, builtin__) and replace __ with ' › ' */
function formatToolName(name: string): string {
  return name.replace(/^(cmd__|nq__|builtin__)/, '').replace(/__/g, ' › ');
}

/** Truncate a cell value for display */
function truncateValue(val: unknown, maxLen = 40): string {
  if (val == null) return '—';
  const str = typeof val === 'object' ? JSON.stringify(val) : String(val);
  return str.length > maxLen ? str.slice(0, maxLen) + '…' : str;
}

/** Extract records array from result (handles common shapes) */
function extractRecords(result: Record<string, any>): Record<string, any>[] | null {
  if (Array.isArray(result)) return result;
  if (Array.isArray(result.records)) return result.records;
  if (Array.isArray(result.data)) return result.data;
  if (result.data && Array.isArray(result.data.records)) return result.data.records;
  return null;
}

// ============================================================================
// Component
// ============================================================================

const MAX_ROWS = 10;
const MAX_COLS = 6;

export function ToolResultCard({ toolName, result, success }: ToolResultCardProps) {
  const [expanded, setExpanded] = useState(false);

  const records = extractRecords(result);
  const displayName = formatToolName(toolName);
  const recordCount = records ? records.length : null;

  // Determine columns from first record
  const columns = records && records.length > 0 ? Object.keys(records[0]).slice(0, MAX_COLS) : [];

  return (
    <div className="mb-3 flex justify-start">
      <div className="w-full max-w-[95%] overflow-hidden rounded-xl border border-gray-200 bg-white shadow-sm dark:border-gray-600 dark:bg-gray-800">
        {/* Header */}
        <button
          onClick={() => setExpanded(!expanded)}
          className="flex w-full items-center gap-2 px-3 py-2 text-left transition-colors hover:bg-gray-50 dark:hover:bg-gray-700/50"
        >
          {success ? (
            <CheckCircle className="h-4 w-4 flex-shrink-0 text-green-500" />
          ) : (
            <XCircle className="h-4 w-4 flex-shrink-0 text-red-500" />
          )}
          <span className="truncate text-sm font-medium text-gray-700 dark:text-gray-300">
            {displayName}
          </span>
          {recordCount !== null && (
            <span className="mr-1 ml-auto text-xs text-gray-400 dark:text-gray-500">
              {recordCount} record{recordCount !== 1 ? 's' : ''}
            </span>
          )}
          {expanded ? (
            <ChevronDown className="h-4 w-4 flex-shrink-0 text-gray-400" />
          ) : (
            <ChevronRight className="h-4 w-4 flex-shrink-0 text-gray-400" />
          )}
        </button>

        {/* Expanded content */}
        {expanded && (
          <div className="border-t border-gray-100 dark:border-gray-700">
            {records && records.length > 0 ? (
              <div className="overflow-x-auto">
                <table className="w-full text-xs">
                  <thead>
                    <tr className="bg-gray-50 dark:bg-gray-700/50">
                      {columns.map((col) => (
                        <th
                          key={col}
                          className="px-2 py-1.5 text-left font-medium whitespace-nowrap text-gray-500 dark:text-gray-400"
                        >
                          {col}
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {records.slice(0, MAX_ROWS).map((row, i) => (
                      <tr key={i} className="border-t border-gray-50 dark:border-gray-700/50">
                        {columns.map((col) => (
                          <td
                            key={col}
                            className="px-2 py-1 whitespace-nowrap text-gray-600 dark:text-gray-300"
                          >
                            {truncateValue(row[col])}
                          </td>
                        ))}
                      </tr>
                    ))}
                  </tbody>
                </table>
                {records.length > MAX_ROWS && (
                  <div className="border-t border-gray-50 px-2 py-1 text-center text-xs text-gray-400 dark:border-gray-700/50 dark:text-gray-500">
                    ... and {records.length - MAX_ROWS} more
                  </div>
                )}
              </div>
            ) : (
              <pre className="max-h-48 overflow-x-auto p-3 text-xs text-gray-600 dark:text-gray-300">
                {JSON.stringify(result, null, 2)}
              </pre>
            )}
          </div>
        )}
      </div>
    </div>
  );
}

export default ToolResultCard;
