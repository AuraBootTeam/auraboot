/**
 * Workbench Homepage — /home
 * Renders the user's personal workbench dashboard.
 */

import React, { useEffect, useState, useCallback } from 'react';
import { Link } from 'react-router';
import { DashboardViewer } from '~/plugins/core-dashboard/components/DashboardViewer';
import type { Dashboard } from '~/plugins/core-dashboard/types';
import { dashboardService } from '~/plugins/core-dashboard/services/dashboardService';
import { useI18n } from '~/contexts/I18nContext';
import { useToastContext } from '~/contexts/ToastContext';
import { get } from '~/shared/services/http-client';
import { listInboxItems } from '~/shared/services/inboxService';
import { fetchRecentVisits } from '~/plugins/core-dashboard/widgets/workbench/useRecentVisits';
import { getLocalizedText } from '~/framework/meta/runtime/expression/i18n-renderer';

export default function WorkbenchPage() {
  const { t, locale } = useI18n();
  const { showSuccessToast, showErrorToast } = useToastContext();
  const [dashboard, setDashboard] = useState<Dashboard | null>(null);
  const [loading, setLoading] = useState(true);
  const [exporting, setExporting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const loadWorkbench = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const result = await dashboardService.getWorkbench();
      setDashboard(result ?? null);
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : 'Failed to load workbench';
      setError(message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadWorkbench();
  }, [loadWorkbench]);

  // Workbench snapshot export: multi-sheet XLSX with the widget inventory plus
  // the same live data the widgets render (stats, inbox, recent visits). Client
  // side by design — no backend export endpoint required.
  const handleExport = useCallback(async () => {
    setExporting(true);
    try {
      const XLSX = await import('xlsx');
      const wb = XLSX.utils.book_new();
      let sheetCount = 0;
      const addSheet = (name: string, rows: Record<string, unknown>[]) => {
        if (!rows.length) return;
        const ws = XLSX.utils.json_to_sheet(rows);
        XLSX.utils.book_append_sheet(wb, ws, name.replace(/[\\/*?[\]:]/g, '_').slice(0, 31));
        sheetCount += 1;
      };

      // 1) widget inventory
      if (dashboard?.widgets?.length) {
        addSheet('widgets', dashboard.widgets.map((w) => ({
          id: w.id,
          type: w.type,
          title: getLocalizedText(w.config?.title, locale, t) || w.id,
        })));
      }

      // 2) workbench stats (same endpoint the stats widget uses)
      try {
        const result = await get<Record<string, unknown>>('/api/workbench/stats');
        const raw = (result.code === '0' && result.data ? result.data : {}) as Record<string, unknown>;
        const stats = (
          raw.stats && typeof raw.stats === 'object' && !('value' in (raw.stats as object))
            ? raw.stats
            : raw
        ) as Record<string, unknown>;
        addSheet('stats', Object.entries(stats).map(([key, value]) => ({ key, value: String(value) })));
      } catch {
        // stats are optional for the export
      }

      // 3) inbox items
      try {
        const inbox = await listInboxItems({ pageSize: 50 });
        addSheet('inbox', (inbox.records ?? []).map((item) => ({
          itemType: item.itemType,
          title: item.title,
          subtitle: item.subtitle ?? '',
          priority: item.priority,
          status: item.status,
        })));
      } catch {
        // inbox is optional for the export
      }

      // 4) recent visits
      try {
        const visits = await fetchRecentVisits(20);
        addSheet('recent', visits.map((v) => ({
          title: v.title,
          path: v.path,
          visitedAt: v.visitedAt,
        })));
      } catch {
        // recent visits are optional for the export
      }

      if (sheetCount === 0) {
        showErrorToast(t('workbench.exportNoData', undefined, 'No data to export'));
        return;
      }

      const wbout = XLSX.write(wb, { bookType: 'xlsx', type: 'array' });
      const blob = new Blob([wbout], {
        type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `workbench-${new Date().toISOString().slice(0, 10)}.xlsx`;
      a.click();
      URL.revokeObjectURL(url);
      showSuccessToast(t('workbench.exported', undefined, `Exported ${sheetCount} sheets`));
    } catch {
      showErrorToast(t('workbench.exportFailed', undefined, 'Excel export failed'));
    } finally {
      setExporting(false);
    }
  }, [dashboard, locale, t, showSuccessToast, showErrorToast]);

  const todayLabel = new Date().toLocaleDateString('en-US', { weekday: 'long', month: 'long', day: 'numeric' });

  const headerBand = (
    <header className="flex items-end justify-between mb-6">
      <div>
        <h1 className="text-[22px] font-semibold tracking-tight text-gray-900 dark:text-gray-100">
          {t('workbench.title', undefined, '工作台')}
        </h1>
        <div data-testid="workbench-subline" className="text-[13px] text-gray-500 mt-1">
          {todayLabel} · {t('workbench.subline', undefined, '概览')}
        </div>
      </div>
      <div className="flex gap-2">
        <Link
          to="/home/settings"
          data-testid="workbench-open-in-dashboard"
          className="px-3.5 py-2 rounded-md border border-[#e3e8ee] bg-white text-[13px] font-medium text-gray-900 hover:border-[#cdd5df] dark:bg-gray-900 dark:border-gray-700 dark:text-gray-100"
        >
          {t('workbench.openInDashboard', undefined, '在仪表盘中打开')}
        </Link>
        <button
          type="button"
          onClick={handleExport}
          disabled={exporting}
          data-testid="workbench-export"
          className="px-3.5 py-2 rounded-md border border-[#e3e8ee] bg-white text-[13px] font-medium text-gray-900 hover:border-[#cdd5df] dark:bg-gray-900 dark:border-gray-700 dark:text-gray-100 disabled:opacity-50"
        >
          {exporting ? t('workbench.exporting', undefined, '导出中...') : t('workbench.export', undefined, '导出')}
        </button>
        <button type="button" className="px-3.5 py-2 rounded-md bg-[#635bff] text-[13px] font-medium text-white hover:bg-[#534eeb]">
          + {t('workbench.new', undefined, '新建')}
        </button>
      </div>
    </header>
  );

  let body: React.ReactNode;
  if (loading) {
    body = (
      <div className="flex h-[40vh] items-center justify-center">
        <div className="h-8 w-8 animate-spin rounded-full border-[3px] border-[#635bff] border-t-transparent" />
      </div>
    );
  } else if (error) {
    body = (
      <div className="flex h-[40vh] flex-col items-center justify-center gap-4 rounded-[10px] border border-[#e3e8ee] bg-white dark:bg-gray-900 dark:border-gray-700">
        <span className="text-lg text-gray-400">
          {t('workbench.loadFailed', undefined, '加载失败')}
        </span>
        <button
          type="button"
          onClick={loadWorkbench}
          className="rounded-md bg-[#635bff] px-4 py-2 text-sm text-white hover:bg-[#534eeb]"
        >
          {t('common.retry', undefined, '重试')}
        </button>
      </div>
    );
  } else if (!dashboard || !dashboard.widgets?.length) {
    body = (
      <div className="flex h-[40vh] flex-col items-center justify-center gap-3 rounded-[10px] border border-[#e3e8ee] bg-white dark:bg-gray-900 dark:border-gray-700">
        <span className="text-4xl" aria-hidden="true">&#127968;</span>
        <span className="text-[15px] text-gray-500">
          {t('workbench.empty', undefined, '工作台尚未配置')}
        </span>
      </div>
    );
  } else {
    body = (
      <DashboardViewer
        widgets={dashboard.widgets}
        layoutConfig={dashboard.layoutConfig}
        title="workbench"
        hideWidgetActions
      />
    );
  }

  return (
    <div className="px-8 py-6 bg-[#fafbfc] dark:bg-gray-900 min-h-full">
      {headerBand}
      {body}
    </div>
  );
}
