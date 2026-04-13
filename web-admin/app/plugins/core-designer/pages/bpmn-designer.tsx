/**
 * BPMN Designer route page
 * Lazy-loaded to reduce initial bundle size (~55KB).
 */

import React, { Suspense } from 'react';
import { RouteLoadingFallback } from '~/ui/RouteLoadingFallback';

const BPMNDesigner = React.lazy(() =>
  import('~/plugins/core-designer/components/bpmn-designer/BPMNDesigner').then((m) => ({ default: m.BPMNDesigner })),
);

export default function BPMNDesignerPage() {
  return (
    <Suspense fallback={<RouteLoadingFallback />}>
      <BPMNDesigner />
    </Suspense>
  );
}
