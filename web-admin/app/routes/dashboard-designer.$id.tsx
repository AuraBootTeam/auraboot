/**
 * Dashboard Designer edit route
 * Lazy-loaded to reduce initial bundle size.
 */

import React, { Suspense } from 'react';
import { useParams } from 'react-router';
import { RouteLoadingFallback } from '~/components/RouteLoadingFallback';

const DashboardDesigner = React.lazy(() =>
  import('~/dashboard-designer').then((m) => ({ default: m.DashboardDesigner })),
);

export default function DashboardDesignerEditPage() {
  const { id } = useParams();

  return (
    <Suspense fallback={<RouteLoadingFallback />}>
      <DashboardDesigner dashboardId={id} />
    </Suspense>
  );
}
