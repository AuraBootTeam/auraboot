import { route, type RouteConfigEntry } from '@react-router/dev/routes'

export function automationRoutes(): RouteConfigEntry[] {
  return [
    route('/automations', './routes/automations.tsx'),
    route('/automation/:id', './routes/automation.$id.tsx'),
  ]
}
