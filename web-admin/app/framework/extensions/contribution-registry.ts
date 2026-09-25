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

export type WebContractRequirement =
  | { mode: 'exact'; kind: WebContributionKind; key: string; contract: string }
  | { mode: 'component-loader' | 'service'; key: string; contract: string }

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
  supportedContracts: readonly string[]
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
    if (registration.supportedContracts !== undefined && !Array.isArray(registration.supportedContracts)) {
      throw new Error('[ContributionRegistry] Invalid supported contract list')
    }
    const contracts = [...(registration.supportedContracts ?? [])]
    if (contracts.some(contract => typeof contract !== 'string' || !contract.trim()) || new Set(contracts).size !== contracts.length) {
      throw new Error('[ContributionRegistry] Invalid or duplicate supported contract')
    }
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
    kindEntries.set(key, { kind, plugin, registration, supportedContracts: Object.freeze(contracts) })
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

  /** Observe a nonempty requirement set at one registry generation, without asserting artifact provenance. */
  observeContracts(requirements: readonly WebContractRequirement[]) {
    if (!Array.isArray(requirements) || requirements.length === 0) {
      throw new Error('[ContributionRegistry] Nonempty web contract requirements required')
    }
    const identities = new Set<string>()
    const frozen = requirements.map(requirement => {
      if (!requirement || typeof requirement.key !== 'string' || !requirement.key.trim()
        || typeof requirement.contract !== 'string' || !requirement.contract.trim()
        || !['exact', 'component-loader', 'service'].includes(requirement.mode)) {
        throw new Error('[ContributionRegistry] Invalid web contract requirement')
      }
      if (requirement.mode === 'exact' && !['renderer', 'component-loader', 'page-runtime-hook', 'service-provider', 'asset', 'i18n'].includes(requirement.kind)) {
        throw new Error('[ContributionRegistry] Invalid exact contribution kind')
      }
      const identity = JSON.stringify([requirement.mode, requirement.mode === 'exact' ? requirement.kind : null, requirement.key, requirement.contract])
      if (identities.has(identity)) throw new Error('[ContributionRegistry] Duplicate web contract requirement')
      identities.add(identity)
      return { ...requirement }
    })
    const generation = this.version
    const observations = frozen.map(requirement => ({
      requirement,
      observation: requirement.mode === 'service'
        ? this.observeServiceContract(requirement.key, requirement.contract)
        : requirement.mode === 'component-loader'
          ? this.observeComponentLoaderContract(requirement.key, requirement.contract)
          : this.observeContract(requirement.kind, requirement.key, requirement.contract),
    }))
    const findings = observations.flatMap(({ requirement, observation }) =>
      observation.findings.map(code => ({ requirement, code })),
    )
    const stable = generation === this.version && observations.every(item => item.observation.generation === generation)
    return { generation, stable, observations, findings, supported: stable && observations.every(item => item.observation.supported) }
  }

  /** Inspect an exact registry key, not a manifest claim or component-loader alias. */
  observeContract(kind: WebContributionKind, key: string, requiredContract: string) {
    if (!key.trim() || !requiredContract.trim()) {
      throw new Error('[ContributionRegistry] Exact key and contract required')
    }
    const generation = this.version
    const entry = this.get(kind, key)
    const findings: string[] = []
    if (!entry) findings.push('web-contribution-unavailable')
    else if (!entry.supportedContracts.includes(requiredContract)) findings.push('web-contract-unavailable')
    return {
      kind,
      key,
      requiredContract,
      generation,
      plugin: entry?.plugin ?? null,
      registrationId: entry?.registration.id ?? null,
      supported: findings.length === 0,
      findings,
    }
  }

  /** Observe the selected loader using the same direct/alias resolution as consumers. */
  observeComponentLoaderContract(id: string, requiredContract: string) {
    if (!id.trim() || !requiredContract.trim()) throw new Error('[ContributionRegistry] Exact key and contract required')
    const generation = this.version
    const selected = this.getComponentLoader(id)
    const observation = this.observeContract('component-loader', selected?.id ?? id, requiredContract)
    const findings = [...observation.findings]
    if (generation !== this.version) findings.push('web-registry-changed')
    return { ...observation, requestedKey: id, generation, supported: findings.length === 0, findings }
  }

  /** A service contract must hold across its primary and every active decorator. */
  observeServiceContract(token: string, requiredContract: string) {
    if (!token.trim() || !requiredContract.trim()) throw new Error('[ContributionRegistry] Exact token and contract required')
    const generation = this.version
    const primary = this.observeContract('service-provider', `${token}:primary`, requiredContract)
    const decorators = this.getServiceDecorators(token).map(registration =>
      this.observeContract('service-provider', `${token}:decorator:${registration.id}`, requiredContract),
    )
    const observations = [primary, ...decorators]
    const findings = observations.flatMap(observation =>
      observation.findings.map(finding => `${observation.key}:${finding}`),
    )
    if (generation !== this.version || observations.some(observation => observation.generation !== generation)) {
      findings.push('web-registry-changed')
    }
    return { token, requiredContract, generation, observations, supported: findings.length === 0, findings }
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
