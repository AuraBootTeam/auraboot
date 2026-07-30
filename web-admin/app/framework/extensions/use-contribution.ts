import { useSyncExternalStore } from 'react'

import { getKernel } from '../bootstrap'

export function useContributionRegistry() {
  const registry = getKernel().contributionRegistry
  useSyncExternalStore(
    (listener) => registry.subscribe(listener),
    () => registry.getSnapshot(),
    () => registry.getSnapshot(),
  )
  return registry
}
