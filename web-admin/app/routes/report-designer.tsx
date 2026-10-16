/**
 * Report Designer — new report route
 * Lazy-loaded to reduce initial bundle size (~142KB).
 */

import React, { Suspense, useCallback } from 'react';
import { useNavigate } from 'react-router';
import { RouteLoadingFallback } from '~/ui/RouteLoadingFallback';

const ReportDesigner = React.lazy(() =>
  import('~/plugins/core-designer/components/report-designer').then((m) => ({
    default: m.ReportDesigner,
  })),
);

export default function ReportDesignerPage() {
  const navigate = useNavigate();
  const openSavedReport = useCallback(
    (pid: string) => {
      navigate(`/report-designer/${encodeURIComponent(pid)}`, { replace: true });
    },
    [navigate],
  );
  return (
    <Suspense fallback={<RouteLoadingFallback />}>
      <ReportDesigner onSaveComplete={openSavedReport} />
    </Suspense>
  );
}
