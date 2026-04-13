/**
 * Query Builder Route
 * Lazy-loaded to reduce initial bundle size.
 */

import React, { Suspense } from 'react';
import { RouteLoadingFallback } from '~/components/RouteLoadingFallback';

const QueryBuilder = React.lazy(() =>
  import('~/query-builder/QueryBuilder').then((m) => ({ default: m.QueryBuilder })),
);

export default function QueryBuilderPage() {
  return (
    <div className="h-[calc(100vh-64px)]">
      <Suspense fallback={<RouteLoadingFallback />}>
        <QueryBuilder />
      </Suspense>
    </div>
  );
}
