/**
 * Workbench Settings — /home/settings
 * Wraps the DashboardDesigner for editing the user's personal workbench.
 */

import React, { useEffect, useState } from 'react';
import { DashboardDesigner } from '~/plugins/core-dashboard/DashboardDesigner';
import { dashboardService } from '~/plugins/core-dashboard/services/dashboardService';

export default function WorkbenchSettingsPage() {
  const [workbenchId, setWorkbenchId] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    async function loadWorkbench() {
      try {
        const wb = await dashboardService.getWorkbench();
        setWorkbenchId(wb?.pid ?? null);
      } catch {
        // CATCH: non-transactional HTTP call — if workbench doesn't exist yet,
        // DashboardDesigner will create a new one
        setWorkbenchId(null);
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
    <DashboardDesigner
      dashboardId={workbenchId ?? undefined}
      initialTitle="My Workbench"
      onSaveComplete={() => {
        // Stay on settings page after save
      }}
      onClose={() => {
        window.location.href = '/home';
      }}
    />
  );
}
