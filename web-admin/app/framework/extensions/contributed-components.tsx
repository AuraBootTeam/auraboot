import React from 'react'

import { useContributionRegistry } from './use-contribution'

const CoreSubTableViewer = React.lazy(() =>
  import('~/framework/meta/rendering/blocks/SubTableViewer').then((module) => ({
    default: module.SubTableViewer,
  })),
)

const CoreReviewDrawer = React.lazy(() =>
  import('~/framework/meta/rendering/blocks/ReviewDrawerBlockRenderer').then(
    (module) => ({ default: module.ReviewDrawerBlockRenderer }),
  ),
)

function createContributedComponent(
  contributionId: string,
  Fallback: React.ComponentType<any>,
) {
  return function ContributedComponent(props: Record<string, unknown>) {
    const registry = useContributionRegistry()
    const registration = registry.getRenderer(contributionId)
    const Component =
      (registration?.component as React.ComponentType<any> | undefined) ??
      Fallback
    return <Component {...props} />
  }
}

export const ContributedSubTableViewer = createContributedComponent(
  'sub-table-viewer',
  CoreSubTableViewer,
)

export const ContributedReviewDrawer = createContributedComponent(
  'review-drawer',
  CoreReviewDrawer,
)
