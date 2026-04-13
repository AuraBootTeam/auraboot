import { route, type RouteConfigEntry } from '@react-router/dev/routes'

export function adminRoutes(): RouteConfigEntry[] {
  return [
    route('/admin/document-upload', './routes/admin/document-upload.tsx'),
    route('/admin/cloud-config', './routes/admin/cloud-config.tsx'),
    route('/admin/login-channels', './routes/admin/login-channels.tsx'),
    route('/admin/entitlements', './routes/admin/entitlements.tsx'),
    route('/admin/infrastructure', './routes/admin/infrastructure.tsx'),
    route('/admin/templates', './routes/admin/templates.tsx'),
    route('/admin/templates/:templateId/preview', './routes/admin/templates.$templateId.preview.tsx'),
    route('/admin/environments', './routes/admin/environments.tsx'),
  ]
}
