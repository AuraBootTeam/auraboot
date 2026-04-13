/**
 * ToolbarMoreMenu — Collapsible "more" dropdown for secondary toolbar actions.
 *
 * Groups Import, Export (Excel/CSV), Print, and Report into a single "⋮" button
 * to keep the toolbar clean for standard list pages.
 */

import React, { useState, useRef, useEffect, useCallback } from 'react';
import { cn } from '~/utils/cn';
import { useToastContext } from '~/contexts/ToastContext';
import { useI18n } from '~/contexts/I18nContext';
import { ResultHelper } from '~/utils/type';
import { reportTemplateService, type ReportTemplateDTO } from '~/services/reportTemplateService';

export interface ToolbarMoreMenuProps {
  /** Callback to open import dialog */
  onImport: () => void;
  /** Model code for export API */
  modelCode: string;
  /** Current filter conditions for export */
  filters?: Array<{ field: string; operator: string; value: unknown }>;
}

export const ToolbarMoreMenu: React.FC<ToolbarMoreMenuProps> = ({
  onImport,
  modelCode,
  filters,
}) => {
  const [open, setOpen] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [reportTemplates, setReportTemplates] = useState<ReportTemplateDTO[]>([]);
  const [loadingReports, setLoadingReports] = useState(false);
  const [generatingReport, setGeneratingReport] = useState(false);
  const menuRef = useRef<HTMLDivElement>(null);
  const { showErrorToast, showSuccessToast } = useToastContext();
  const { t } = useI18n();

  useEffect(() => {
    const handleClickOutside = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    if (open) {
      document.addEventListener('mousedown', handleClickOutside);
      return () => document.removeEventListener('mousedown', handleClickOutside);
    }
  }, [open]);

  const handleExport = useCallback(
    async (format: 'xlsx' | 'csv') => {
      setExporting(true);
      setOpen(false);
      try {
        const res = await fetch(`/api/dynamic/${modelCode}/export`, {
          method: 'post',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            format: format === 'xlsx' ? 'excel' : 'csv',
            conditions: filters,
          }),
        });
        if (!res.ok) throw new Error('Export request failed');
        const data = await res.json();
        if (!ResultHelper.isSuccess(data) || !data.data?.downloadUrl) {
          throw new Error(data.desc || 'Export failed');
        }
        const link = document.createElement('a');
        link.href = data.data.downloadUrl;
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
    [modelCode, filters, showErrorToast],
  );

  // Load report templates when menu opens
  useEffect(() => {
    if (!open || reportTemplates.length > 0) return;
    let cancelled = false;
    setLoadingReports(true);
    reportTemplateService
      .getPublished()
      .then((resp) => {
        if (cancelled) return;
        if (ResultHelper.isSuccess(resp) && resp.data) {
          const filtered = resp.data.filter((tpl) => !tpl.category || tpl.category === modelCode);
          setReportTemplates(filtered);
        }
      })
      .catch(() => {})
      .finally(() => {
        if (!cancelled) setLoadingReports(false);
      });
    return () => {
      cancelled = true;
    };
  }, [open, modelCode, reportTemplates.length]);

  const handleGenerateReport = useCallback(
    async (template: ReportTemplateDTO) => {
      setGeneratingReport(true);
      setOpen(false);
      try {
        const params: Record<string, unknown> = {};
        if (filters?.length) params.filters = filters;
        const blob = await reportTemplateService.generate(template.code, params);
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        const ext = template.outputFormat?.toLowerCase() || 'pdf';
        a.download = `${template.name || template.code}.${ext}`;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
        showSuccessToast(`Report generated: ${template.name}`);
      } catch (err) {
        showErrorToast(err instanceof Error ? err.message : 'Report generation failed');
      } finally {
        setGeneratingReport(false);
      }
    },
    [filters, showSuccessToast, showErrorToast],
  );

  const printLabel = t('action.print') || 'Print';

  return (
    <div ref={menuRef} className="relative inline-block">
      <button
        type="button"
        data-testid="toolbar-more-menu"
        onClick={() => setOpen(!open)}
        disabled={exporting || generatingReport}
        className={cn(
          'inline-flex items-center justify-center rounded-md border border-gray-300 bg-white p-2 text-gray-500',
          'hover:bg-gray-50 hover:text-gray-700 focus:ring-2 focus:ring-blue-500 focus:outline-none',
          'disabled:cursor-not-allowed disabled:opacity-50',
          'transition-colors duration-150',
        )}
        title={t('action.more') || 'More actions'}
      >
        {exporting || generatingReport ? (
          <span className="h-4 w-4 animate-spin rounded-full border-2 border-gray-300 border-t-blue-500" />
        ) : (
          <svg className="h-4 w-4" fill="currentColor" viewBox="0 0 20 20">
            <path d="M10 6a2 2 0 110-4 2 2 0 010 4zM10 12a2 2 0 110-4 2 2 0 010 4zM10 18a2 2 0 110-4 2 2 0 010 4z" />
          </svg>
        )}
      </button>

      {open && (
        <div className="absolute right-0 z-50 mt-1 w-48 rounded-md border border-gray-200 bg-white py-1 shadow-lg">
          {/* Print */}
          <button
            type="button"
            data-testid="more-menu-print"
            onClick={() => {
              setOpen(false);
              window.print();
            }}
            className="flex w-full items-center gap-2.5 px-3 py-2 text-left text-sm text-gray-700 hover:bg-gray-50"
          >
            <svg
              className="h-4 w-4 text-gray-400"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M17 17h2a2 2 0 002-2v-4a2 2 0 00-2-2H5a2 2 0 00-2 2v4a2 2 0 002 2h2m2 4h6a2 2 0 002-2v-4a2 2 0 00-2-2H9a2 2 0 00-2 2v4a2 2 0 002 2zm8-12V5a2 2 0 00-2-2H9a2 2 0 00-2 2v4h10z"
              />
            </svg>
            {printLabel}
          </button>

          <div className="mx-2 my-1 h-px bg-gray-100" />

          {/* Import */}
          <button
            type="button"
            data-testid="more-menu-import"
            onClick={() => {
              setOpen(false);
              onImport();
            }}
            className="flex w-full items-center gap-2.5 px-3 py-2 text-left text-sm text-gray-700 hover:bg-gray-50"
          >
            <svg
              className="h-4 w-4 text-gray-400"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-8l-4-4m0 0L8 8m4-4v12"
              />
            </svg>
            Import
          </button>

          {/* Export Excel */}
          <button
            type="button"
            data-testid="more-menu-export-excel"
            onClick={() => handleExport('xlsx')}
            className="flex w-full items-center gap-2.5 px-3 py-2 text-left text-sm text-gray-700 hover:bg-gray-50"
          >
            <svg
              className="h-4 w-4 text-gray-400"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M12 10v6m0 0l-3-3m3 3l3-3m2 8H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"
              />
            </svg>
            Export Excel
          </button>

          {/* Export CSV */}
          <button
            type="button"
            data-testid="more-menu-export-csv"
            onClick={() => handleExport('csv')}
            className="flex w-full items-center gap-2.5 px-3 py-2 text-left text-sm text-gray-700 hover:bg-gray-50"
          >
            <svg
              className="h-4 w-4 text-gray-400"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            >
              <path
                strokeLinecap="round"
                strokeLinejoin="round"
                strokeWidth={2}
                d="M12 10v6m0 0l-3-3m3 3l3-3m2 8H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"
              />
            </svg>
            Export CSV
          </button>

          {/* Report Templates */}
          {(loadingReports || reportTemplates.length > 0) && (
            <>
              <div className="mx-2 my-1 h-px bg-gray-100" />
              {loadingReports ? (
                <div className="px-3 py-2 text-center text-xs text-gray-400">
                  Loading reports...
                </div>
              ) : (
                reportTemplates.map((tpl) => (
                  <button
                    key={tpl.pid}
                    type="button"
                    data-testid={`more-menu-report-${tpl.code}`}
                    onClick={() => handleGenerateReport(tpl)}
                    className="flex w-full items-center gap-2.5 px-3 py-2 text-left text-sm text-gray-700 hover:bg-gray-50"
                  >
                    <svg
                      className="h-4 w-4 text-gray-400"
                      fill="none"
                      stroke="currentColor"
                      viewBox="0 0 24 24"
                    >
                      <path
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        strokeWidth={2}
                        d="M9 17v-2m3 2v-4m3 4v-6m2 10H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"
                      />
                    </svg>
                    <span className="truncate">{tpl.name}</span>
                    <span
                      className={cn(
                        'ml-auto inline-flex shrink-0 items-center rounded px-1 py-0.5 text-[10px] font-medium',
                        tpl.outputFormat === 'pdf'
                          ? 'bg-red-50 text-red-600'
                          : tpl.outputFormat === 'xlsx'
                            ? 'bg-green-50 text-green-600'
                            : 'bg-blue-50 text-blue-600',
                      )}
                    >
                      {tpl.outputFormat}
                    </span>
                  </button>
                ))
              )}
            </>
          )}
        </div>
      )}
    </div>
  );
};

export default ToolbarMoreMenu;
