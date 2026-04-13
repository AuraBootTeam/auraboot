/**
 * DataSourceRegistry — resolves DataSourceRef references at runtime.
 *
 * Plugins register a provider per DataSourceRef.type (`namedQuery`, `api`,
 * `dictionary`, `relation`, etc). When DSL renderers encounter a DataSourceRef,
 * they look up the matching provider and call resolve(ref).
 */

import type { DataSourceRef, DataSourceType } from '@auraboot/dsl-types'

export interface DataSourceProviderRecord {
  type: DataSourceType
  resolve: (ref: DataSourceRef) => Promise<unknown>
  plugin?: string
}

export class DataSourceRegistry {
  private readonly providers = new Map<string, DataSourceProviderRecord>()

  register(record: DataSourceProviderRecord & { override?: boolean }): void {
    const { override, ...rest } = record
    if (this.providers.has(record.type) && !override) {
      const existing = this.providers.get(record.type)!
      throw new Error(
        `[DataSourceRegistry] type='${record.type}' already registered by plugin='${existing.plugin ?? '<unknown>'}', refused new registration from plugin='${record.plugin ?? '<unknown>'}'. Pass override:true to replace.`,
      )
    }
    this.providers.set(record.type, rest)
  }

  resolve(ref: DataSourceRef): Promise<unknown> {
    const provider = this.providers.get(ref.type)
    if (!provider) {
      return Promise.reject(
        new Error(`[DataSourceRegistry] no provider for type='${ref.type}' (source='${ref.source}')`),
      )
    }
    return provider.resolve(ref)
  }

  list(): readonly DataSourceProviderRecord[] {
    return Array.from(this.providers.values())
  }
}
