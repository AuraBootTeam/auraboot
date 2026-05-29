/**
 * Workbench Homepage — /home
 * Renders the user's personal workbench dashboard.
 */

import React, { useEffect, useState, useCallback } from 'react';
import { DashboardViewer } from '~/plugins/core-dashboard/components/DashboardViewer';
import type { Dashboard } from '~/plugins/core-dashboard/types';
import { dashboardService } from '~/plugins/core-dashboard/services/dashboardService';
import { useI18n } from '~/contexts/I18nContext';

export default function WorkbenchPage() {
  const { t } = useI18n();
  const [dashboard, setDashboard] = useState<Dashboard | null>(null);
  const [loading, setLoading] = useState(true);
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

  if (loading) {
    return (
      <div className="flex h-[60vh] items-center justify-center">
        <div className="h-8 w-8 animate-spin rounded-full border-3 border-blue-500 border-t-transparent" />
      </div>
    );
  }

  if (error) {
    return (
      <div className="flex h-[60vh] flex-col items-center justify-center gap-4">
        <span className="text-lg text-gray-400">
          {t('workbench.loadFailed')}
        </span>
        <button
          type="button"
          onClick={loadWorkbench}
          className="rounded-lg bg-blue-500 px-4 py-2 text-sm text-white hover:bg-blue-600"
        >
          {t('common.retry')}
        </button>
      </div>
    );
  }

  if (!dashboard || !dashboard.widgets?.length) {
    return (
      <div className="flex h-[60vh] flex-col items-center justify-center gap-4">
        <span className="text-4xl">&#127968;</span>
        <span className="text-lg text-gray-500">
          {t('workbench.empty')}
        </span>
      </div>
    );
  }

  const todayLabel = new Date().toLocaleDateString('en-US', { weekday: 'long', month: 'long', day: 'numeric' });

  return (
    <div className="px-8 py-6 bg-[#fafbfc] dark:bg-gray-900 min-h-full">
      <header className="flex items-end justify-between mb-6">
        <div>
          <h1 className="text-[22px] font-semibold tracking-tight text-gray-900 dark:text-gray-100">
            {t('workbench.title')}
          </h1>
          <div data-testid="workbench-subline" className="text-[13px] text-gray-500 mt-1">
            {todayLabel} · {t('workbench.subline')}
          </div>
        </div>
        <div className="flex gap-2">
          <button type="button" className="px-3.5 py-2 rounded-md border border-[#e3e8ee] bg-white text-[13px] font-medium text-gray-900 hover:border-[#cdd5df] dark:bg-gray-900 dark:border-gray-700 dark:text-gray-100">
            {t('workbench.export')}
          </button>
          <button type="button" className="px-3.5 py-2 rounded-md bg-[#635bff] text-[13px] font-medium text-white hover:bg-[#534eeb]">
            + {t('workbench.new')}
          </button>
        </div>
      </header>

      <DashboardViewer
        widgets={dashboard.widgets}
        layoutConfig={dashboard.layoutConfig}
        title="workbench"
      />
    </div>
  );
}
