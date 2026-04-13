/**
 * Plugin kind determines distribution and licensing model.
 *
 * - `core`: Ships with the kernel, OSS, no license check.
 * - `oss`: Open-source community plugin, OSS, no license check.
 * - `enterprise`: Commercial plugin, requires entitlement.
 * - `solution`: Industry/vertical bundle, commercial.
 */
export type PluginKind = 'core' | 'oss' | 'enterprise' | 'solution'

export interface PluginDependencies {
  /** Other plugin codes this plugin requires to be active. */
  plugins?: string[]
  /** Semver range of @auraboot/plugin-sdk this plugin is built against. */
  coreVersion?: string
}

export interface PluginLicenseRequirement {
  required?: boolean
  /** Feature keys that must be entitled for this plugin to activate. */
  featureKeys?: string[]
}

export interface PluginCompatibility {
  minCoreVersion?: string
  maxCoreVersion?: string
}

export interface PluginManifest {
  /** Globally unique identifier, e.g. `core.system`, `ent.im-chat`. */
  code: string
  name: string
  version: string
  description?: string

  kind: PluginKind
  visibility?: 'public' | 'private'

  /** Module entry path relative to plugin root. Optional for plugins registered via setup() only. */
  entry?: string

  dependencies?: PluginDependencies
  license?: PluginLicenseRequirement
  compatibility?: PluginCompatibility

  /** Feature keys this plugin contributes (declared once, registered via PluginContext.registerFeature). */
  features?: string[]

  /** Permission codes this plugin contributes. */
  permissions?: string[]
}
