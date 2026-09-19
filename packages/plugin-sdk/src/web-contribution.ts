export type WebActivationPhase = 'foundation' | 'feature' | 'application'
export type WebContributionKind = 'block' | 'action' | 'widget' | 'provider' | 'i18n'

export interface WebContributionRoute {
  /** Globally unique logical route ID. */
  id: string
  path: string
  /** Public package export path, never a source-tree-relative import. */
  export: `./${string}`
  /** Product routes must enter both the client and SSR build graphs. */
  rendering: 'universal'
  permission?: string
  featureKey?: string
}

export interface WebRegistryContribution {
  kind: WebContributionKind
  /** Globally unique within a contribution kind. */
  id: string
  /** Public package export path, never a web-shell internal import. */
  export: `./${string}`
  permission?: string
  featureKey?: string
}

export interface WebContributionManifest {
  schemaVersion: 1
  package: {
    name: `@auraboot/${string}`
    version: string
  }
  plugin: {
    code: string
    activationPhase: WebActivationPhase
    dependsOn: string[]
  }
  peerDependencies: {
    react: string
    reactDom: string
    router: string
    pluginSdk: string
  }
  routes: WebContributionRoute[]
  contributions: WebRegistryContribution[]
}

/** Preserve literal contribution IDs while checking the public manifest shape. */
export function defineWebContribution<const T extends WebContributionManifest>(manifest: T): T {
  return manifest
}
