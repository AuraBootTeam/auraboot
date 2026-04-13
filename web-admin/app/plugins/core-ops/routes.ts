import { route, type RouteConfigEntry } from '@react-router/dev/routes'

export function opsRoutes(): RouteConfigEntry[] {
  return [
    route('/notifications', './plugins/core-ops/pages/notifications/index.tsx'),
    route('/notification-rules', './plugins/core-ops/pages/notification-rules/index.tsx'),
    route('/scheduler', './plugins/core-ops/pages/scheduler/index.tsx'),
    route('/audit-logs', './plugins/core-ops/pages/audit-logs/index.tsx'),
    route('/documents', './plugins/core-ops/pages/documents/index.tsx'),
  ]
}
