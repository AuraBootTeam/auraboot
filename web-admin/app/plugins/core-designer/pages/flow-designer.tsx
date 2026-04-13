import React, { Suspense } from 'react';
import { RouteLoadingFallback } from '~/ui/RouteLoadingFallback';

const FlowDesigner = React.lazy(() => import('~/plugins/core-designer/components/flow-designer/FlowDesigner'));

type MetaArgs = Record<string, unknown>;

export const meta = (_: MetaArgs) => {
  return [
    { title: 'Flow Designer' },
    { name: 'description', content: 'Visual flow designer with multi-row multi-column layout' },
  ];
};

export default function FlowDesignerPage() {
  return (
    <div className="h-screen">
      <Suspense fallback={<RouteLoadingFallback />}>
        <FlowDesigner />
      </Suspense>
    </div>
  );
}
