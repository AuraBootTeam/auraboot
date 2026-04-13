/**
 * Dashboard Designer route page
 * Lazy-loaded to reduce initial bundle size (~75KB).
 */

import React, { Suspense } from 'react';
import { RouteLoadingFallback } from '~/ui/RouteLoadingFallback';

const DashboardDesigner = React.lazy(() =>
  import('~/plugins/core-dashboard/module').then((m) => ({ default: m.DashboardDesigner })),
);

export default function DashboardDesignerPage() {
  return (
    <Suspense fallback={<RouteLoadingFallback />}>
      <DashboardDesigner />
    </Suspense>
  );
}
