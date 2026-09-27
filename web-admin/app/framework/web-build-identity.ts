import type { ContributionRegistry, WebContractRequirement } from './extensions/contribution-registry.js'

/** Replaced by Vite at build time; never read from mutable browser storage or runtime environment. */
declare const __AURA_WEB_COMPOSITION_KEY__: string | null

export function observeBuiltWebContracts(
  registry: ContributionRegistry,
  requirements: readonly WebContractRequirement[],
  expectedCompositionKey: string,
) {
  const compositionKey = typeof __AURA_WEB_COMPOSITION_KEY__ === 'undefined' ? null : __AURA_WEB_COMPOSITION_KEY__
  if (!/^sha256:[0-9a-f]{64}$/.test(expectedCompositionKey) || compositionKey !== expectedCompositionKey) {
    throw new Error('Loaded Web composition differs from externally pinned identity')
  }
  // This stamp links the observation to a composition input, not to a signed build artifact.
  return { compositionKey, ...registry.observeContracts(requirements) }
}
