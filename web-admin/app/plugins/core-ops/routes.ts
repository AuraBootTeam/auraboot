import { route, type RouteConfigEntry } from '@react-router/dev/routes'

export function opsRoutes(): RouteConfigEntry[] {
  return [
    route('/notifications', './routes/notifications/index.tsx'),
    route('/notification-rules', './routes/notification-rules/index.tsx'),
    route('/scheduler', './routes/scheduler/index.tsx'),
    route('/audit-logs', './routes/audit-logs/index.tsx'),
    route('/documents', './routes/documents/index.tsx'),
  ]
}
