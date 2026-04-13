/**
 * Report Designer — new report route
 * Lazy-loaded to reduce initial bundle size (~142KB).
 */

import React, { Suspense } from 'react';
import { RouteLoadingFallback } from '~/components/RouteLoadingFallback';

const ReportDesigner = React.lazy(() =>
  import('~/report-designer').then((m) => ({ default: m.ReportDesigner })),
);

export default function ReportDesignerPage() {
  return (
    <Suspense fallback={<RouteLoadingFallback />}>
      <ReportDesigner />
    </Suspense>
  );
}
