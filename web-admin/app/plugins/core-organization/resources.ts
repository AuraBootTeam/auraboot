import type { PluginResource } from '../_shared/types.js'

export const RESOURCES: PluginResource[] = [
  {
    key: 'org.teams',
    path: '/organization/teams',
    title: { en: 'Teams', zh: '团队' },
    icon: 'users',
    menu: { order: 10, group: 'organization' },
    permission: 'org.team.read',
    file: './plugins/core-organization/pages/organization/teams.tsx',
  },
  {
    key: 'org.team-detail',
    path: '/organization/teams/:teamPid',
    title: { en: 'Team Detail', zh: '团队详情' },
    menu: false,
    parentKey: 'org.teams',
    permission: 'org.team.read',
    file: './plugins/core-organization/pages/organization/team-detail.tsx',
  },
  {
    key: 'org.member-detail',
    path: '/organization/members/:memberPid',
    title: { en: 'Member Detail', zh: '成员详情' },
    menu: false,
    permission: 'org.member.read',
    file: './plugins/core-organization/pages/organization/member-detail.tsx',
  },
]
