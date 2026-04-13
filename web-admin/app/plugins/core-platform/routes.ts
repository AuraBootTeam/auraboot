import { route, type RouteConfigEntry } from '@react-router/dev/routes'

export function platformRoutes(): RouteConfigEntry[] {
  return [
    route('/system/plugins', './routes/system/plugins/index.tsx'),
  ]
}
