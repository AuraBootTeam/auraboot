/**
 * Workbench Settings — /home/settings
 * Wraps the DashboardDesigner for editing the user's personal workbench.
 */

import React, { useEffect, useState } from 'react';
import { dashboardService } from '~/plugins/core-dashboard/services/dashboardService';
import { useI18n } from '~/contexts/I18nContext';

export default function WorkbenchSettingsPage() {
  const { t } = useI18n();
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    async function loadWorkbench() {
      try {
        await dashboardService.getWorkbench();
      } catch {
        // Ignore — this page only shows availability state.
      } finally {
        setLoading(false);
      }
    }
    loadWorkbench();
  }, []);

  if (loading) {
    return (
      <div className="flex h-[60vh] items-center justify-center">
        <div className="h-8 w-8 animate-spin rounded-full border-3 border-blue-500 border-t-transparent" />
      </div>
    );
  }

  return (
    <div className="flex h-[60vh] flex-col items-center justify-center gap-4">
      <span className="text-4xl">&#9888;</span>
      <span className="text-lg font-medium text-gray-700">
        {t('workbench.settingsUnavailable')}
      </span>
      <p className="max-w-md text-center text-sm text-gray-500">
        {t('workbench.settingsUnavailableHint')}
      </p>
      <a
        href="/home"
        className="rounded-lg border border-gray-200 px-4 py-2 text-sm text-gray-600 transition-colors hover:bg-gray-50"
      >
        {t('common.back')}
      </a>
    </div>
  );
}
