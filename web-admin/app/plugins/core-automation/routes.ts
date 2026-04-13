import { route, type RouteConfigEntry } from '@react-router/dev/routes'

export function automationRoutes(): RouteConfigEntry[] {
  return [
    route('/automations', './plugins/core-automation/pages/automations.tsx'),
    route('/automation/:id', './plugins/core-automation/pages/automation.$id.tsx'),
  ]
}
