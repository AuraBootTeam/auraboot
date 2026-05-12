/**
 * Dashboard Export to Excel
 *
 * Exports all dashboard widgets' data to a multi-sheet XLSX file.
 * Each widget with data becomes a separate sheet.
 * Uses the SheetJS (xlsx) library already installed in the project.
 */

import React, { useState, useCallback } from 'react';
import { TableCellsIcon } from '@heroicons/react/24/outline';
import { useToastContext } from '~/contexts/ToastContext';
import type { Widget } from '../types';
import { useI18n } from '~/contexts/I18nContext';
import { getLocalizedText } from '~/framework/meta/runtime/expression/i18n-renderer';

interface DashboardExportExcelProps {
  widgets: Widget[];
  fileName?: string;
}

export const DashboardExportExcel: React.FC<DashboardExportExcelProps> = ({
  widgets,
  fileName = 'dashboard',
}) => {
  const [exporting, setExporting] = useState(false);
  const { showSuccessToast, showErrorToast } = useToastContext();
  const { locale, t } = useI18n();

  const handleExport = useCallback(async () => {
    setExporting(true);
    try {
      const XLSX = await import('xlsx');

      const wb = XLSX.utils.book_new();
      let sheetCount = 0;

      for (const widget of widgets) {
        const ds = widget.config?.dataSource;
        if (!ds) continue;

        const title = getLocalizedText(widget.config?.title, locale, t) || `Widget_${widget.id}`;
        // Sanitize sheet name (max 31 chars, no special chars)
        const sheetName = title.replace(/[\\/*?[\]:]/g, '_').slice(0, 31);

        try {
          let rows: Record<string, unknown>[] = [];

          if (ds.type === 'static' && ds.staticData) {
            rows = Array.isArray(ds.staticData) ? ds.staticData : [];
          } else {
            // Fetch data from API
            const request: Record<string, unknown> = { type: ds.type };

            if (ds.type === 'aggregate' && ds.modelCode) {
              request.modelCode = ds.modelCode;
            } else if (ds.type === 'namedQuery' && ds.queryCode) {
              request.queryCode = ds.queryCode;
            }

            if (ds.dimensions) request.dimensions = ds.dimensions;
            if (ds.metrics) request.metrics = ds.metrics;
            if (ds.filters) request.filters = ds.filters;

            const resp = await fetch('/api/meta/chart-data', {
              method: 'post',
              headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify(request),
            });

            if (resp.ok) {
              const body = await resp.json();
              rows = body?.data?.rows || [];
            }
          }

          if (rows.length > 0) {
            const ws = XLSX.utils.json_to_sheet(rows);
            XLSX.utils.book_append_sheet(wb, ws, sheetName);
            sheetCount++;
          }
        } catch {
          // Skip widgets that fail to fetch
        }
      }

      if (sheetCount === 0) {
        showErrorToast('No data to export');
        return;
      }

      const wbout = XLSX.write(wb, { bookType: 'xlsx', type: 'array' });
      const blob = new Blob([wbout], {
        type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `${fileName}.xlsx`;
      a.click();
      URL.revokeObjectURL(url);

      showSuccessToast(`Exported ${sheetCount} sheets`);
    } catch {
      showErrorToast('Excel export failed');
    } finally {
      setExporting(false);
    }
  }, [widgets, fileName, showSuccessToast, showErrorToast]);

  return (
    <button
      onClick={handleExport}
      disabled={exporting || widgets.length === 0}
      className="inline-flex items-center gap-1.5 rounded-md border border-gray-300 bg-white px-3 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-50"
      title="Export to Excel"
      data-testid="toolbar-btn-export-excel"
    >
      <TableCellsIcon className="h-4 w-4" />
      {exporting ? '...' : 'Excel'}
    </button>
  );
};

export default DashboardExportExcel;
