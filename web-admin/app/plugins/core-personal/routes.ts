import { route, type RouteConfigEntry } from '@react-router/dev/routes'

export function personalRoutes(): RouteConfigEntry[] {
  return [
    route('/personal/profile', './plugins/core-personal/pages/personal/profile.tsx'),
    route('/personal/security', './plugins/core-personal/pages/personal/security.tsx'),
    route('/personal/social-links', './plugins/core-personal/pages/personal/social-links.tsx'),
    route('/personal/deactivation', './plugins/core-personal/pages/personal/deactivation.tsx'),
  ]
}
