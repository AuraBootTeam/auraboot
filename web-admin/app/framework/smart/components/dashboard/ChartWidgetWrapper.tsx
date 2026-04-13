/**
 * ChartWidgetWrapper
 *
 * Wraps chart components with an action menu (top-right corner)
 * providing export functionality (CSV, JSON).
 */

import React, { useState, useRef, useEffect, useCallback } from 'react';
import { useChartData } from '~/framework/smart/hooks/useChartData';
import type { ChartDataSource, FilterConfig } from '~/framework/smart/types/chart';

interface ChartWidgetWrapperProps {
  /** Chart title for export filename */
  title?: string;
  /** Data source config for fetching export data */
  dataSource?: ChartDataSource;
  /** Linkage filters applied to this widget */
  linkageFilters?: FilterConfig[];
  /** Whether to show export menu */
  enableExport?: boolean;
  /** Children (the actual chart component) */
  children: React.ReactNode;
}

export const ChartWidgetWrapper: React.FC<ChartWidgetWrapperProps> = ({
  title,
  dataSource,
  linkageFilters,
  enableExport = true,
  children,
}) => {
  const [menuOpen, setMenuOpen] = useState(false);
  const menuRef = useRef<HTMLDivElement>(null);

  // Fetch data for export (reuses the same hook as the chart)
  const { data } = useChartData({
    dataSource: dataSource || { type: 'static' },
    linkageFilters,
    enabled: !!dataSource,
  });

  // Close menu on outside click or Escape
  useEffect(() => {
    if (!menuOpen) return;

    const handleClickOutside = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        setMenuOpen(false);
      }
    };
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setMenuOpen(false);
    };

    document.addEventListener('mousedown', handleClickOutside);
    document.addEventListener('keydown', handleKeyDown);
    return () => {
      document.removeEventListener('mousedown', handleClickOutside);
      document.removeEventListener('keydown', handleKeyDown);
    };
  }, [menuOpen]);

  const exportAsCsv = useCallback(() => {
    if (!data?.rows?.length) return;
    const rows = data.rows;
    const fields = Object.keys(rows[0]);
    const header = fields.join(',');
    const lines = rows.map((row) =>
      fields
        .map((f) => {
          const val = row[f];
          if (val == null) return '';
          let str = String(val);
          // Prevent CSV injection: prefix formula-triggering characters
          if (/^[=+\-@\t\r]/.test(str)) {
            str = "'" + str;
          }
          if (str.includes(',') || str.includes('"') || str.includes('\n')) {
            return '"' + str.replace(/"/g, '""') + '"';
          }
          return str;
        })
        .join(','),
    );
    const csv = [header, ...lines].join('\n');
    downloadFile(csv, `${title || 'chart'}_export.csv`, 'text/csv;charset=utf-8');
    setMenuOpen(false);
  }, [data, title]);

  const exportAsJson = useCallback(() => {
    if (!data?.rows?.length) return;
    const json = JSON.stringify(data.rows, null, 2);
    downloadFile(json, `${title || 'chart'}_export.json`, 'application/json;charset=utf-8');
    setMenuOpen(false);
  }, [data, title]);

  if (!enableExport || !dataSource) {
    return <>{children}</>;
  }

  return (
    <div className="relative h-full">
      {/* Action button */}
      <div className="absolute top-1 right-1 z-10" ref={menuRef}>
        <button
          onClick={() => setMenuOpen(!menuOpen)}
          className="rounded p-1 text-gray-400 transition-colors hover:bg-gray-100 hover:text-gray-600"
          title="Actions"
        >
          <svg className="h-4 w-4" fill="currentColor" viewBox="0 0 20 20">
            <path d="M10 6a2 2 0 110-4 2 2 0 010 4zM10 12a2 2 0 110-4 2 2 0 010 4zM10 18a2 2 0 110-4 2 2 0 010 4z" />
          </svg>
        </button>

        {menuOpen && (
          <div className="absolute right-0 mt-1 w-36 rounded-md border border-gray-200 bg-white py-1 shadow-lg">
            <button
              onClick={exportAsCsv}
              disabled={!data?.rows?.length}
              className="flex w-full items-center gap-2 px-3 py-1.5 text-left text-sm text-gray-700 hover:bg-gray-50 disabled:cursor-not-allowed disabled:text-gray-300"
            >
              <svg className="h-3.5 w-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M12 10v6m0 0l-3-3m3 3l3-3m2 8H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"
                />
              </svg>
              CSV
            </button>
            <button
              onClick={exportAsJson}
              disabled={!data?.rows?.length}
              className="flex w-full items-center gap-2 px-3 py-1.5 text-left text-sm text-gray-700 hover:bg-gray-50 disabled:cursor-not-allowed disabled:text-gray-300"
            >
              <svg className="h-3.5 w-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M12 10v6m0 0l-3-3m3 3l3-3m2 8H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"
                />
              </svg>
              JSON
            </button>
          </div>
        )}
      </div>

      {/* Chart content */}
      {children}
    </div>
  );
};

function downloadFile(content: string, filename: string, mimeType: string) {
  const blob = new Blob([content], { type: mimeType });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}

export default ChartWidgetWrapper;
