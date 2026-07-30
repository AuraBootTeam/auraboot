import type {
  AssetRegistration,
  ComponentLoaderRegistration,
  I18nRegistration,
  PageRuntimeHookRegistration,
  RendererRegistration,
  ServiceProviderRegistration,
} from '@auraboot/plugin-sdk'

export type WebContributionKind =
  | 'renderer'
  | 'component-loader'
  | 'page-runtime-hook'
  | 'service-provider'
  | 'asset'
  | 'i18n'

type WebContributionRegistration =
  | RendererRegistration
  | ComponentLoaderRegistration
  | PageRuntimeHookRegistration
  | ServiceProviderRegistration
  | AssetRegistration
  | I18nRegistration

interface StoredContribution<T extends WebContributionRegistration> {
  kind: WebContributionKind
  plugin: string
  registration: T
}

export interface ContributionDiagnostic {
  kind: WebContributionKind
  id: string
  plugin: string
  owner: string
  featureKey?: string
  status: 'registered' | 'gated'
  key: string
}

const contributionKey = (
  kind: WebContributionKind,
  registration: WebContributionRegistration,
): string => {
  if (kind === 'service-provider') {
    const provider = registration as ServiceProviderRegistration
    return provider.mode === 'decorator'
      ? `${provider.token}:decorator:${provider.id}`
      : `${provider.token}:primary`
  }
  if (kind === 'i18n') {
    const i18n = registration as I18nRegistration
    return `${i18n.locale}:${i18n.namespace}:${i18n.id}`
  }
  return registration.id
}

const byPriorityThenIdentity = <
  T extends StoredContribution<WebContributionRegistration>,
>(
  left: T,
  right: T,
): number =>
  (right.registration.priority ?? 0) -
    (left.registration.priority ?? 0) ||
  left.plugin.localeCompare(right.plugin) ||
  left.registration.id.localeCompare(right.registration.id)

/**
 * One governed registry for extension seams that used to require replacing
 * OSS source files. Conflicts fail closed and every accepted/gated item keeps
 * its plugin owner for runtime diagnostics.
 */
export class ContributionRegistry {
  private readonly entries = new Map<
    WebContributionKind,
    Map<string, StoredContribution<WebContributionRegistration>>
  >()

  private readonly diagnostics: ContributionDiagnostic[] = []
  private readonly listeners = new Set<() => void>()
  private version = 0

  constructor(
    private hasFeature: (featureKey: string) => boolean = () => true,
  ) {}

  /**
   * Configure the gate before plugin activation without replacing the
   * registry object that React consumers may already be subscribed to.
   */
  setFeatureGate(hasFeature: (featureKey: string) => boolean): void {
    this.hasFeature = hasFeature
  }

  register<T extends WebContributionRegistration>(
    kind: WebContributionKind,
    plugin: string,
    registration: T,
  ): void {
    const key = contributionKey(kind, registration)
    if (registration.featureKey && !this.hasFeature(registration.featureKey)) {
      this.diagnostics.push({
        kind,
        id: registration.id,
        plugin,
        owner: plugin,
        featureKey: registration.featureKey,
        status: 'gated',
        key,
      })
      return
    }
    let kindEntries = this.entries.get(kind)
    if (!kindEntries) {
      kindEntries = new Map()
      this.entries.set(kind, kindEntries)
    }
    const previous = kindEntries.get(key)
    if (previous) {
      throw new Error(
        `[ContributionRegistry] ${kind} '${key}' from '${plugin}' conflicts with '${previous.plugin}'`,
      )
    }
    kindEntries.set(key, { kind, plugin, registration })
    this.diagnostics.push({
      kind,
      id: registration.id,
      plugin,
      owner: plugin,
      featureKey: registration.featureKey,
      status: 'registered',
      key,
    })
    this.emit()
  }

  getComponentLoader(id: string): ComponentLoaderRegistration | undefined {
    const direct = this.get('component-loader', id)?.registration as
      | ComponentLoaderRegistration
      | undefined
    if (direct) return direct
    const normalized = id.toLowerCase()
    return this.list('component-loader')
      .map((entry) => entry.registration as ComponentLoaderRegistration)
      .find(
        (registration) =>
          registration.id.toLowerCase() === normalized ||
          registration.componentName?.toLowerCase() === normalized ||
          registration.aliases?.some(
            (alias) => alias.toLowerCase() === normalized,
          ),
      )
  }

  getRenderer(id: string): RendererRegistration | undefined {
    return this.get('renderer', id)?.registration as
      | RendererRegistration
      | undefined
  }

  getPrimaryService(token: string): ServiceProviderRegistration | undefined {
    return this.get('service-provider', `${token}:primary`)?.registration as
      | ServiceProviderRegistration
      | undefined
  }

  getServiceDecorators(token: string): readonly ServiceProviderRegistration[] {
    return this.list('service-provider')
      .filter(
        (entry) =>
          (entry.registration as ServiceProviderRegistration).token === token &&
          (entry.registration as ServiceProviderRegistration).mode ===
            'decorator',
      )
      .map(
        (entry) => entry.registration as ServiceProviderRegistration,
      )
  }

  listPageRuntimeHooks(
    phase?: PageRuntimeHookRegistration['phase'],
  ): readonly PageRuntimeHookRegistration[] {
    return this.list('page-runtime-hook')
      .map((entry) => entry.registration as PageRuntimeHookRegistration)
      .filter((registration) => !phase || registration.phase === phase)
  }

  listDiagnostics(): readonly ContributionDiagnostic[] {
    return [...this.diagnostics]
  }

  subscribe(listener: () => void): () => void {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  getSnapshot(): number {
    return this.version
  }

  removeByPlugin(plugin: string): void {
    let removed = false
    for (const kindEntries of this.entries.values()) {
      for (const [key, entry] of kindEntries) {
        if (entry.plugin === plugin) {
          kindEntries.delete(key)
          removed = true
        }
      }
    }
    for (let index = this.diagnostics.length - 1; index >= 0; index -= 1) {
      if (this.diagnostics[index].plugin === plugin) {
        this.diagnostics.splice(index, 1)
        removed = true
      }
    }
    if (removed) this.emit()
  }

  private get(
    kind: WebContributionKind,
    key: string,
  ): StoredContribution<WebContributionRegistration> | undefined {
    return this.entries.get(kind)?.get(key)
  }

  private list(
    kind: WebContributionKind,
  ): readonly StoredContribution<WebContributionRegistration>[] {
    return [...(this.entries.get(kind)?.values() ?? [])].sort(
      byPriorityThenIdentity,
    )
  }

  private emit(): void {
    this.version += 1
    for (const listener of this.listeners) listener()
  }
}
