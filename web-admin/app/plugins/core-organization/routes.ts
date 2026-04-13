import { route, type RouteConfigEntry } from '@react-router/dev/routes'

export function organizationRoutes(): RouteConfigEntry[] {
  return [
    route('/organization/members/:memberPid', './plugins/core-organization/pages/organization/member-detail.tsx'),
    route('/organization/teams', './plugins/core-organization/pages/organization/teams.tsx'),
    route('/organization/teams/:teamPid', './plugins/core-organization/pages/organization/team-detail.tsx'),
  ]
}
