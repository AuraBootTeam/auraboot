import React, { useEffect, useState } from 'react'

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

export function LazyContributedComponent({
  contributionId,
  fallback = null,
  ...props
}: {
  contributionId: string
  fallback?: React.ReactNode
  [key: string]: unknown
}) {
  const registry = useContributionRegistry()
  const registration = registry.getComponentLoader(contributionId)
  const [Component, setComponent] = useState<React.ComponentType<any> | null>(null)

  useEffect(() => {
    let active = true
    setComponent(null)
    if (!registration) return () => { active = false }
    registration.load().then((loaded) => {
      if (!active || !loaded || typeof loaded !== 'object') return
      const exports = loaded as Record<string, unknown>
      const candidate = exports[registration.exportName ?? '']
        ?? exports[registration.componentName ?? '']
        ?? exports.default
      if (typeof candidate === 'function') setComponent(() => candidate as React.ComponentType<any>)
    })
    return () => { active = false }
  }, [registration])

  return Component ? <Component {...props} /> : <>{fallback}</>
}

export const ContributedSubTableViewer = createContributedComponent(
  'sub-table-viewer',
  CoreSubTableViewer,
)

export const ContributedReviewDrawer = createContributedComponent(
  'review-drawer',
  CoreReviewDrawer,
)
