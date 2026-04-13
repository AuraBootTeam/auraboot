/**
 * Report Designer — edit existing report route
 * Lazy-loaded to reduce initial bundle size.
 */

import React, { Suspense } from 'react';
import { useParams } from 'react-router';
import { RouteLoadingFallback } from '~/components/RouteLoadingFallback';

const ReportDesigner = React.lazy(() =>
  import('~/report-designer').then((m) => ({ default: m.ReportDesigner })),
);

export default function ReportDesignerEditPage() {
  const { id } = useParams();

  return (
    <Suspense fallback={<RouteLoadingFallback />}>
      <ReportDesigner reportId={id} />
    </Suspense>
  );
}
