/**
 * ExportButton Component
 *
 * Dropdown button for exporting data in Excel or CSV format.
 * Calls the backend export API and triggers a file download.
 */

import React, { useState, useRef, useCallback, useEffect } from 'react';
import { cn } from '~/utils/cn';
import { useToastContext } from '~/contexts/ToastContext';
import { ResultHelper } from '~/utils/type';

export interface ExportButtonProps {
  /** Model code or page key for the data source */
  modelCode: string;
  /** Optional view PID to export only current view's data */
  viewPid?: string;
  /** Current filter conditions to export only filtered data */
  filters?: Array<{ field: string; operator: string; value: unknown }>;
  /** Custom CSS class */
  className?: string;
}

type ExportFormat = 'xlsx' | 'csv';

/**
 * ExportButton - Provides Excel and CSV export functionality
 */
export const ExportButton: React.FC<ExportButtonProps> = ({
  modelCode,
  viewPid,
  filters,
  className,
}) => {
  const [open, setOpen] = useState(false);
  const [exporting, setExporting] = useState(false);
  const { showErrorToast } = useToastContext();
  const dropdownRef = useRef<HTMLDivElement>(null);

  // Close dropdown on outside click
  useEffect(() => {
    const handleClickOutside = (e: MouseEvent) => {
      if (dropdownRef.current && !dropdownRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    if (open) {
      document.addEventListener('mousedown', handleClickOutside);
      return () => document.removeEventListener('mousedown', handleClickOutside);
    }
  }, [open]);

  const handleExport = useCallback(
    async (format: ExportFormat) => {
      setExporting(true);
      setOpen(false);

      try {
        // Step 1: Request export
        const exportRes = await fetch(`/api/dynamic/${modelCode}/export`, {
          method: 'post',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            format: format === 'xlsx' ? 'excel' : 'csv',
            viewPid,
            conditions: filters,
          }),
        });

        if (!exportRes.ok) throw new Error('Export request failed');

        const exportData = await exportRes.json();
        if (!ResultHelper.isSuccess(exportData) || !exportData.data?.downloadUrl) {
          throw new Error(exportData.desc || 'Export failed');
        }

        // Step 2: Download the file
        const downloadUrl = exportData.data.downloadUrl;
        const link = document.createElement('a');
        link.href = downloadUrl;
        link.download = `${modelCode}_export.${format}`;
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);
      } catch (err) {
        console.error('Export failed:', err);
        showErrorToast(err instanceof Error ? err.message : 'Export failed');
      } finally {
        setExporting(false);
      }
    },
    [modelCode, viewPid, filters],
  );

  return (
    <div ref={dropdownRef} className={cn('relative inline-block', className)}>
      <button
        type="button"
        data-testid="export-button"
        onClick={() => setOpen(!open)}
        disabled={exporting}
        className={cn(
          'inline-flex items-center gap-1.5 px-3 py-1.5 text-sm font-medium',
          'rounded-md border border-gray-300 bg-white text-gray-700 shadow-sm',
          'hover:bg-gray-50 focus:ring-2 focus:ring-blue-500 focus:outline-none',
          'disabled:cursor-not-allowed disabled:opacity-50',
          'transition-colors duration-150',
        )}
      >
        {exporting ? (
          <span className="h-4 w-4 animate-spin rounded-full border-2 border-gray-300 border-t-blue-500" />
        ) : (
          <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M12 10v6m0 0l-3-3m3 3l3-3m2 8H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"
            />
          </svg>
        )}
        Export
        <svg className="h-3 w-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
        </svg>
      </button>

      {open && (
        <div className="absolute right-0 z-50 mt-1 w-40 rounded-md border border-gray-200 bg-white shadow-lg">
          <button
            type="button"
            data-testid="export-excel"
            onClick={() => handleExport('xlsx')}
            className="flex w-full items-center gap-2 px-4 py-2 text-left text-sm text-gray-700 hover:bg-gray-50"
          >
            <svg className="h-4 w-4 text-green-600" fill="currentColor" viewBox="0 0 20 20">
              <path
                fillRule="evenodd"
                d="M4 4a2 2 0 012-2h4.586A2 2 0 0112 2.586L15.414 6A2 2 0 0116 7.414V16a2 2 0 01-2 2H6a2 2 0 01-2-2V4z"
                clipRule="evenodd"
              />
            </svg>
            Excel (.xlsx)
          </button>
          <button
            type="button"
            data-testid="export-csv"
            onClick={() => handleExport('csv')}
            className="flex w-full items-center gap-2 px-4 py-2 text-left text-sm text-gray-700 hover:bg-gray-50"
          >
            <svg className="h-4 w-4 text-blue-600" fill="currentColor" viewBox="0 0 20 20">
              <path
                fillRule="evenodd"
                d="M4 4a2 2 0 012-2h4.586A2 2 0 0112 2.586L15.414 6A2 2 0 0116 7.414V16a2 2 0 01-2 2H6a2 2 0 01-2-2V4z"
                clipRule="evenodd"
              />
            </svg>
            CSV (.csv)
          </button>
        </div>
      )}
    </div>
  );
};

export default ExportButton;
