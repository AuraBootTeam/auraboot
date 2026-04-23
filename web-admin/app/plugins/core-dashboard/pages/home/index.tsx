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

  return (
    <div className="h-full w-full px-5 py-4">
      <div className="mb-4 flex items-center justify-between">
        <h1 className="text-lg font-semibold text-gray-900">
          {t('workbench.pageTitle')}
        </h1>
        <div className="flex items-center gap-2">
          <button
            type="button"
            onClick={loadWorkbench}
            className="rounded-lg border border-gray-200 px-3 py-1.5 text-sm text-gray-600 hover:bg-gray-50 transition-colors flex items-center gap-1.5"
          >
            <svg className="h-4 w-4" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5">
              <path d="M13.5 8a5.5 5.5 0 1 1-1.6-3.9" strokeLinecap="round" strokeLinejoin="round" />
              <path d="M13.5 3v4h-4" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
            {t('workbench.refresh')}
          </button>
        </div>
      </div>
      <DashboardViewer
        widgets={dashboard.widgets}
        layoutConfig={dashboard.layoutConfig}
        title="workbench"
      />
    </div>
  );
}
