/**
 * ChatBiResultCard
 *
 * Renders ChatBI query results inline in AuraBot chat —
 * interpretation text + chart visualization + optional SQL toggle.
 *
 * Reuses AIChartRenderer for chart rendering (bar, pie, line, table).
 *
 * @since 3.2.0
 */

import { useState } from 'react';
import { AIChartRenderer } from '~/smart/components/ai/AIChartRenderer';

interface ChatBiResult {
  interpretation?: string;
  modelCode?: string;
  chartType?: string;
  chartConfig?: Record<string, unknown>;
  columns?: string[];
  records?: Record<string, unknown>[];
  total?: number;
  sql?: string;
  truncated?: boolean;
}

interface ChatBiResultCardProps {
  result: ChatBiResult;
}

export function ChatBiResultCard({ result }: ChatBiResultCardProps) {
  const [showSql, setShowSql] = useState(false);

  const {
    interpretation,
    chartType = 'table',
    chartConfig,
    columns = [],
    records = [],
    total,
    sql,
    truncated,
  } = result;

  return (
    <div className="mb-3 flex justify-start">
      <div className="w-full max-w-[95%] overflow-hidden rounded-xl border border-indigo-200 bg-white shadow-sm dark:border-indigo-700 dark:bg-gray-800">
        {/* Header */}
        <div className="flex items-center gap-2 border-b border-indigo-100 bg-indigo-50 px-3 py-2 dark:border-indigo-800 dark:bg-indigo-900/20">
          <svg
            className="h-4 w-4 flex-shrink-0 text-indigo-500"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
          >
            <path d="M3 3v18h18" />
            <path d="M7 16l4-8 4 4 4-6" />
          </svg>
          <span className="text-sm font-medium text-indigo-700 dark:text-indigo-300">
            Data Query
          </span>
          {total != null && (
            <span className="ml-auto text-xs text-indigo-400 dark:text-indigo-500">
              {total} record{total !== 1 ? 's' : ''}
              {truncated && ' (truncated)'}
            </span>
          )}
        </div>

        {/* Interpretation */}
        {interpretation && (
          <div className="border-b border-gray-100 px-3 py-2 text-sm text-gray-600 dark:border-gray-700 dark:text-gray-300">
            {interpretation}
          </div>
        )}

        {/* Chart — dark container to match AIChartRenderer's color scheme */}
        <div className="rounded-b-none bg-gray-900 p-3">
          <AIChartRenderer
            chartType={chartType}
            data={records as Record<string, unknown>[]}
            columns={columns}
            chartConfig={chartConfig}
          />
        </div>

        {/* SQL toggle */}
        {sql && (
          <div className="border-t border-gray-100 dark:border-gray-700">
            <button
              onClick={() => setShowSql(!showSql)}
              className="flex w-full items-center gap-1.5 px-3 py-1.5 text-xs text-gray-400 transition-colors hover:text-gray-600 dark:hover:text-gray-300"
            >
              <svg
                className="h-3 w-3"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="2"
              >
                <polyline points={showSql ? '18 15 12 9 6 15' : '6 9 12 15 18 9'} />
              </svg>
              {showSql ? 'Hide SQL' : 'Show SQL'}
            </button>
            {showSql && (
              <pre className="overflow-x-auto px-3 pb-2 text-xs whitespace-pre-wrap text-gray-500 dark:text-gray-400">
                {sql}
              </pre>
            )}
          </div>
        )}
      </div>
    </div>
  );
}

export default ChatBiResultCard;
