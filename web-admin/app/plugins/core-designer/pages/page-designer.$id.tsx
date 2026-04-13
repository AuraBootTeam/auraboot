/**
 * Page Designer - Editor Route
 *
 * Lazy-loaded to keep the Studio workbench (~200KB+) out of the initial bundle.
 * The heavy component lives in PageDesignerEditorImpl and is loaded on demand.
 *
 * @since 4.0.0
 */

import React, { Suspense } from 'react';
import { RouteLoadingFallback } from '~/components/RouteLoadingFallback';

const PageDesignerEditorImpl = React.lazy(
  () => import('~/plugins/core-designer/components/studio/workbench/PageDesignerEditorImpl'),
);

export default function PageDesignerEditor() {
  return (
    <Suspense fallback={<RouteLoadingFallback />}>
      <PageDesignerEditorImpl />
    </Suspense>
  );
}
