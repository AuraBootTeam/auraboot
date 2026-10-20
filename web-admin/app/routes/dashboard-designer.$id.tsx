/**
 * Dashboard Designer edit route
 * Lazy-loaded to reduce initial bundle size.
 */

import React, { Suspense, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router';
import { RouteLoadingFallback } from '~/ui/RouteLoadingFallback';

const DashboardDesigner = React.lazy(() =>
  import('~/plugins/core-dashboard/module').then((m) => ({
    default: m.DashboardDesigner,
  })),
);

export default function DashboardDesignerEditPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const openSavedDashboard = useCallback(
    (pid: string) => {
      if (pid !== id)
        navigate(`/dashboard-designer/${encodeURIComponent(pid)}`, {
          replace: true,
        });
    },
    [id, navigate],
  );

  return (
    <Suspense fallback={<RouteLoadingFallback />}>
      <DashboardDesigner dashboardId={id} onSaveComplete={openSavedDashboard} />
    </Suspense>
  );
}
