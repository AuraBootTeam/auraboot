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

export default function WorkbenchPage() {
  const { t } = useI18n();
  const [dashboard, setDashboard] = useState<Dashboard | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [todayLabel, setTodayLabel] = useState('');

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

  useEffect(() => {
    setTodayLabel(
      new Date().toLocaleDateString('en-US', {
        weekday: 'long',
        month: 'long',
        day: 'numeric',
      }),
    );
  }, []);

  const headerBand = (
    <header className="flex items-end justify-between mb-6">
      <div>
        <h1 className="text-[22px] font-semibold tracking-tight text-gray-900 dark:text-gray-100">
          {t('workbench.title', undefined, '工作台')}
        </h1>
        <div data-testid="workbench-subline" className="text-[13px] text-gray-500 mt-1">
          {todayLabel ? `${todayLabel} · ` : ''}
          {t('workbench.subline', undefined, '概览')}
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
        <button type="button" className="px-3.5 py-2 rounded-md border border-[#e3e8ee] bg-white text-[13px] font-medium text-gray-900 hover:border-[#cdd5df] dark:bg-gray-900 dark:border-gray-700 dark:text-gray-100">
          {t('workbench.export', undefined, '导出')}
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
