import { route, type RouteConfigEntry } from '@react-router/dev/routes'
import { toRouteEntries } from '../_shared/types.js'
import { RESOURCES } from './resources.js'

/**
 * Static React Router 7 routes for core-bpm. Derived from ./resources.ts.
 * Imported by packages/core/route-manifest.ts.
 */
export function bpmRoutes(): RouteConfigEntry[] {
  return toRouteEntries(RESOURCES, (path, file) => route(path, file))
}
