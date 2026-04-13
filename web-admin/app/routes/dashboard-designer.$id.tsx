/**
 * Dashboard Designer edit route
 * Lazy-loaded to reduce initial bundle size.
 */

import React, { Suspense } from 'react';
import { useParams } from 'react-router';
import { RouteLoadingFallback } from '~/ui/RouteLoadingFallback';

const DashboardDesigner = React.lazy(() =>
  import('~/plugins/core-dashboard/module').then((m) => ({ default: m.DashboardDesigner })),
);

export default function DashboardDesignerEditPage() {
  const { id } = useParams();

  return (
    <Suspense fallback={<RouteLoadingFallback />}>
      <DashboardDesigner dashboardId={id} />
    </Suspense>
  );
}
