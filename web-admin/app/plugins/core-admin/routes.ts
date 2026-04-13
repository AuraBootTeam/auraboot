import { route, type RouteConfigEntry } from '@react-router/dev/routes'

export function adminRoutes(): RouteConfigEntry[] {
  return [
    route('/admin/document-upload', './plugins/core-admin/pages/admin/document-upload.tsx'),
    route('/admin/cloud-config', './plugins/core-admin/pages/admin/cloud-config.tsx'),
    route('/admin/login-channels', './plugins/core-admin/pages/admin/login-channels.tsx'),
    route('/admin/entitlements', './plugins/core-admin/pages/admin/entitlements.tsx'),
    route('/admin/infrastructure', './plugins/core-admin/pages/admin/infrastructure.tsx'),
    route('/admin/templates', './plugins/core-admin/pages/admin/templates.tsx'),
    route('/admin/templates/:templateId/preview', './plugins/core-admin/pages/admin/templates.$templateId.preview.tsx'),
    route('/admin/environments', './plugins/core-admin/pages/admin/environments.tsx'),
  ]
}
