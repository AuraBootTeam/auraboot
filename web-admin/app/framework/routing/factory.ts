import type { RouteRegistry } from '@auraboot/nav-model'
import { RouteRegistryImpl } from './registry.js'

export interface RouteRegistryOptions {
  /**
   * Whether to throw on re-registration. Default false (warn only) for
   * HMR friendliness.
   */
  strict?: boolean
}

/**
 * Factory for the kernel's RouteRegistry singleton. The kernel creates one
 * instance during boot; plugins register through it via PluginContext.
 */
export function createRouteRegistry(_opts?: RouteRegistryOptions): RouteRegistry {
  return new RouteRegistryImpl()
}
