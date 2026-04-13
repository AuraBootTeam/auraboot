import { route, type RouteConfigEntry } from '@react-router/dev/routes'

export function personalRoutes(): RouteConfigEntry[] {
  return [
    route('/personal/profile', './routes/personal/profile.tsx'),
    route('/personal/security', './routes/personal/security.tsx'),
    route('/personal/social-links', './routes/personal/social-links.tsx'),
    route('/personal/deactivation', './routes/personal/deactivation.tsx'),
  ]
}
