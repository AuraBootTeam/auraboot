/**
 * ReportGenerateButton
 *
 * Dropdown button for generating reports from published templates.
 * Fetches published templates filtered by category (modelCode),
 * lets user pick a template, then downloads the generated document.
 */

import React, { useState, useRef, useCallback, useEffect } from 'react';
import { cn } from '~/utils/cn';
import { useToastContext } from '~/contexts/ToastContext';
import { reportTemplateService, type ReportTemplateDTO } from '~/shared/services/reportTemplateService';
import { ResultHelper } from '~/utils/type';

export interface ReportGenerateButtonProps {
  /** Model code — used to filter templates by category */
  modelCode: string;
  /** Current record PID for single-record reports */
  recordPid?: string;
  /** Additional parameters to pass to the report */
  parameters?: Record<string, unknown>;
  /** Custom CSS class */
  className?: string;
}

export const ReportGenerateButton: React.FC<ReportGenerateButtonProps> = ({
  modelCode,
  recordPid,
  parameters,
  className,
}) => {
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const [generating, setGenerating] = useState(false);
  const [templates, setTemplates] = useState<ReportTemplateDTO[]>([]);
  const { showErrorToast, showSuccessToast } = useToastContext();
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

  const loadTemplates = useCallback(async () => {
    setLoading(true);
    try {
      const resp = await reportTemplateService.getPublished();
      if (ResultHelper.isSuccess(resp) && resp.data) {
        // Filter by category matching modelCode (convention: template category = model code)
        const filtered = resp.data.filter((t) => !t.category || t.category === modelCode);
        setTemplates(filtered.length > 0 ? filtered : resp.data);
      }
    } catch {
      // silently fail — dropdown will show empty
    } finally {
      setLoading(false);
    }
  }, [modelCode]);

  const handleOpen = useCallback(() => {
    if (!open) {
      loadTemplates();
    }
    setOpen(!open);
  }, [open, loadTemplates]);

  const handleGenerate = useCallback(
    async (template: ReportTemplateDTO) => {
      setGenerating(true);
      setOpen(false);
      try {
        const params: Record<string, unknown> = { ...parameters };
        if (recordPid) {
          params.recordId = recordPid;
        }

        const blob = await reportTemplateService.generate(template.code, params);
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;

        // Determine file extension from output format
        const ext = template.outputFormat?.toLowerCase() || 'pdf';
        a.download = `${template.name || template.code}.${ext}`;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);

        showSuccessToast(`Report generated: ${template.name}`);
      } catch (err) {
        console.error('Report generation failed:', err);
        showErrorToast(err instanceof Error ? err.message : 'Report generation failed');
      } finally {
        setGenerating(false);
      }
    },
    [recordPid, parameters, showSuccessToast, showErrorToast],
  );

  return (
    <div ref={dropdownRef} className={cn('relative inline-block', className)}>
      <button
        type="button"
        data-testid="report-generate-button"
        onClick={handleOpen}
        disabled={generating}
        className={cn(
          'inline-flex items-center gap-1.5 px-3 py-1.5 text-sm font-medium',
          'rounded-md border border-gray-300 bg-white text-gray-700 shadow-sm',
          'hover:bg-gray-50 focus:ring-2 focus:ring-blue-500 focus:outline-none',
          'disabled:cursor-not-allowed disabled:opacity-50',
          'transition-colors duration-150',
        )}
      >
        {generating ? (
          <span className="h-4 w-4 animate-spin rounded-full border-2 border-gray-300 border-t-blue-500" />
        ) : (
          <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M17 17h2a2 2 0 002-2v-4a2 2 0 00-2-2H5a2 2 0 00-2 2v4a2 2 0 002 2h2m2 4h6a2 2 0 002-2v-4a2 2 0 00-2-2H9a2 2 0 00-2 2v4a2 2 0 002 2zm8-12V5a2 2 0 00-2-2H9a2 2 0 00-2 2v4h10z"
            />
          </svg>
        )}
        Report
        <svg className="h-3 w-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
        </svg>
      </button>

      {open && (
        <div
          data-testid="report-generate-dropdown"
          className="absolute right-0 z-50 mt-1 max-h-64 w-56 overflow-y-auto rounded-md border border-gray-200 bg-white shadow-lg"
        >
          {loading && <div className="px-4 py-3 text-center text-sm text-gray-500">Loading...</div>}
          {!loading && templates.length === 0 && (
            <div className="px-4 py-3 text-center text-sm text-gray-500">
              No published templates
            </div>
          )}
          {templates.map((tpl) => (
            <button
              key={tpl.pid}
              type="button"
              data-testid={`report-template-${tpl.code}`}
              onClick={() => handleGenerate(tpl)}
              className="flex w-full items-center gap-2 px-4 py-2 text-left text-sm text-gray-700 hover:bg-gray-50"
            >
              <span
                className={cn(
                  'inline-flex items-center rounded px-1.5 py-0.5 text-[10px] font-medium',
                  tpl.outputFormat === 'pdf'
                    ? 'bg-red-50 text-red-600'
                    : tpl.outputFormat === 'xlsx'
                      ? 'bg-green-50 text-green-600'
                      : 'bg-blue-50 text-blue-600',
                )}
              >
                {tpl.outputFormat}
              </span>
              <span className="truncate">{tpl.name}</span>
            </button>
          ))}
        </div>
      )}
    </div>
  );
};

export default ReportGenerateButton;
