/**
 * Dashboard Designer route page
 * Lazy-loaded to reduce initial bundle size (~75KB).
 */

import React, { Suspense, useCallback } from 'react';
import { useNavigate } from 'react-router';
import { RouteLoadingFallback } from '~/ui/RouteLoadingFallback';

const DashboardDesigner = React.lazy(() =>
  import('~/plugins/core-dashboard/module').then((m) => ({ default: m.DashboardDesigner })),
);

export default function DashboardDesignerPage() {
  const navigate = useNavigate();
  const openSavedDashboard = useCallback((pid: string) => {
    navigate(`/dashboard-designer/${encodeURIComponent(pid)}`, { replace: true });
  }, [navigate]);
  return (
    <Suspense fallback={<RouteLoadingFallback />}>
      <DashboardDesigner onSaveComplete={openSavedDashboard} />
    </Suspense>
  );
}
