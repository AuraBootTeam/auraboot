/**
 * Report Designer — new report route
 * Lazy-loaded to reduce initial bundle size (~142KB).
 */

import React, { Suspense } from 'react';
import { RouteLoadingFallback } from '~/ui/RouteLoadingFallback';

const ReportDesigner = React.lazy(() =>
  import('~/plugins/core-designer/components/report-designer').then((m) => ({ default: m.ReportDesigner })),
);

export default function ReportDesignerPage() {
  return (
    <Suspense fallback={<RouteLoadingFallback />}>
      <ReportDesigner />
    </Suspense>
  );
}
