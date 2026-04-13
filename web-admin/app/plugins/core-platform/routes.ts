import { route, type RouteConfigEntry } from '@react-router/dev/routes'
import { toRouteEntries } from '../_shared/types.js'
import { RESOURCES } from './resources.js'

export function platformRoutes(): RouteConfigEntry[] {
  return toRouteEntries(RESOURCES, (path, file) => route(path, file))
}
