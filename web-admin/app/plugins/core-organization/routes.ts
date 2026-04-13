import { route, type RouteConfigEntry } from '@react-router/dev/routes'

export function organizationRoutes(): RouteConfigEntry[] {
  return [
    route('/organization/members/:memberPid', './routes/organization/member-detail.tsx'),
    route('/organization/teams', './routes/organization/teams.tsx'),
    route('/organization/teams/:teamPid', './routes/organization/team-detail.tsx'),
  ]
}
