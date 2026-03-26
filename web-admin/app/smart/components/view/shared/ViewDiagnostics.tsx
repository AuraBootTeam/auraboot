/**
 * ViewDiagnostics — Reusable diagnostics panel for view types (Gantt, Calendar, etc.).
 * Extracted from GanttView's inline diagnostics pattern.
 *
 * Shows a summary of data issues, filterable issue list, field mapping, and action buttons.
 */

import React, { useState, useMemo } from 'react';
import { cn } from '~/utils/cn';

export interface DiagnosticCategory {
  key: string;
  label: string;
  count: number;
}

export interface DiagnosticIssue {
  recordId: string;
  title: string;
  reason: string;
  details: Record<string, unknown>;
}

export interface ViewDiagnosticsProps {
  totalRecords: number;
  validRecords: number;
  categories: DiagnosticCategory[];
  issues: DiagnosticIssue[];
  fieldMapping?: Record<string, string>;
  onRecordClick?: (recordId: string) => void;
  onOpenViewConfig?: () => void;
  onSwitchToTableView?: () => void;
  onRefresh?: () => void;
  className?: string;
}

const MAX_VISIBLE_ISSUES = 10;

export const ViewDiagnostics: React.FC<ViewDiagnosticsProps> = ({
  totalRecords,
  validRecords,
  categories,
  issues,
  fieldMapping,
  onRecordClick,
  onOpenViewConfig,
  onSwitchToTableView,
  onRefresh,
  className,
}) => {
  const [activeFilter, setActiveFilter] = useState<string>('all');

  const filteredIssues = useMemo(() => {
    const base = activeFilter === 'all' ? issues : issues.filter((i) => i.reason === activeFilter);
    return base.slice(0, MAX_VISIBLE_ISSUES);
  }, [issues, activeFilter]);

  return (
    <div data-testid="view-diagnostics" className={`px-6 py-10 text-sm text-gray-600 dark:text-gray-400 ${className || ''}`}>
      {/* Summary header */}
      <div className="mb-2 font-medium text-gray-800 dark:text-gray-200">
        View loaded, but some records cannot be rendered.
      </div>

      {/* Field mapping */}
      {fieldMapping && Object.keys(fieldMapping).length > 0 && (
        <div className="mb-3 text-gray-500 dark:text-gray-400">
          Current mapping:{' '}
          {Object.entries(fieldMapping).map(([key, value], idx) => (
            <span key={key}>
              {idx > 0 && ', '}
              {key}=<span className="font-mono">{value}</span>
            </span>
          ))}
        </div>
      )}

      {/* Summary grid */}
      <div className="grid grid-cols-1 gap-1 sm:grid-cols-2">
        <div>Total records: {totalRecords}</div>
        <div>Valid records: {validRecords}</div>
        {categories.map((cat) => (
          <div key={cat.key}>
            {cat.label}: {cat.count}
          </div>
        ))}
      </div>

      {/* Action buttons */}
      <div className="mt-4 flex flex-wrap gap-2">
        {onOpenViewConfig && (
          <button
            type="button"
            onClick={onOpenViewConfig}
            className="rounded-md border border-gray-300 bg-white px-3 py-1.5 text-xs text-gray-700 hover:bg-gray-50 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-300 dark:hover:bg-gray-600"
          >
            Configure
          </button>
        )}
        {onSwitchToTableView && (
          <button
            type="button"
            onClick={onSwitchToTableView}
            className="rounded-md border border-gray-300 bg-white px-3 py-1.5 text-xs text-gray-700 hover:bg-gray-50 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-300 dark:hover:bg-gray-600"
          >
            Switch to Table
          </button>
        )}
        {onRefresh && (
          <button
            type="button"
            onClick={onRefresh}
            className="rounded-md border border-gray-300 bg-white px-3 py-1.5 text-xs text-gray-700 hover:bg-gray-50 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-300 dark:hover:bg-gray-600"
          >
            Refresh
          </button>
        )}
      </div>

      {/* Issue list */}
      {issues.length > 0 && (
        <div className="mt-5 rounded-md border border-gray-200 dark:border-gray-700">
          <div className="border-b border-gray-200 px-3 py-2 text-xs font-medium text-gray-700 dark:border-gray-700 dark:text-gray-300">
            Issue records (first {MAX_VISIBLE_ISSUES})
          </div>

          {/* Filter buttons */}
          <div className="flex flex-wrap gap-1 border-b border-gray-100 px-3 py-2 dark:border-gray-700">
            <button
              type="button"
              onClick={() => setActiveFilter('all')}
              className={cn(
                'rounded px-2 py-1 text-xs',
                activeFilter === 'all'
                  ? 'bg-blue-100 text-blue-700 dark:bg-blue-900 dark:text-blue-300'
                  : 'bg-gray-100 text-gray-600 hover:bg-gray-200 dark:bg-gray-700 dark:text-gray-400 dark:hover:bg-gray-600',
              )}
            >
              All ({issues.length})
            </button>
            {categories
              .filter((cat) => cat.count > 0)
              .map((cat) => (
                <button
                  key={cat.key}
                  type="button"
                  onClick={() => setActiveFilter(cat.key)}
                  className={cn(
                    'rounded px-2 py-1 text-xs',
                    activeFilter === cat.key
                      ? 'bg-blue-100 text-blue-700 dark:bg-blue-900 dark:text-blue-300'
                      : 'bg-gray-100 text-gray-600 hover:bg-gray-200 dark:bg-gray-700 dark:text-gray-400 dark:hover:bg-gray-600',
                  )}
                >
                  {cat.label} ({cat.count})
                </button>
              ))}
          </div>

          {/* Issue rows */}
          <div className="divide-y divide-gray-100 dark:divide-gray-700">
            {filteredIssues.map((item, idx) => (
              <div
                key={`${item.recordId}-${idx}`}
                className={cn(
                  'flex items-center justify-between gap-3 px-3 py-2 text-xs',
                  onRecordClick && 'cursor-pointer hover:bg-gray-50 dark:hover:bg-gray-700',
                )}
                onClick={onRecordClick ? () => onRecordClick(item.recordId) : undefined}
              >
                <div className="min-w-0 flex-1">
                  <div className="truncate font-medium text-gray-800 dark:text-gray-200">{item.title}</div>
                  <div className="mt-0.5 text-gray-500 dark:text-gray-400">
                    {item.reason}
                    {Object.entries(item.details).map(([k, v]) => (
                      <span key={k}> · {k}={String(v ?? 'null')}</span>
                    ))}
                  </div>
                </div>
                {onRecordClick && (
                  <button
                    type="button"
                    onClick={(e) => {
                      e.stopPropagation();
                      onRecordClick(item.recordId);
                    }}
                    className="rounded border border-gray-300 bg-white px-2 py-1 text-xs text-gray-700 hover:bg-gray-50 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-300 dark:hover:bg-gray-600"
                  >
                    Open
                  </button>
                )}
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
};

export default ViewDiagnostics;
