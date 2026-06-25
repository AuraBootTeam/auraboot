import type { PluginResource } from '../_shared/types.js'

export const RESOURCES: PluginResource[] = [
  {
    key: 'org.teams',
    path: '/organization/teams',
    title: { en: 'Teams', zh: '团队' },
    icon: 'users',
    menu: { order: 10, group: 'organization' },
    file: './plugins/core-organization/pages/organization/team-dsl-list.tsx',
    dsl: { modelCode: 'ab_team', pageKey: 'ab_team_list' },
  },
  {
    key: 'org.teams.new',
    path: '/organization/teams/new',
    title: { en: 'New Team', zh: '新建团队' },
    menu: false,
    parentKey: 'org.teams',
    file: './plugins/core-organization/pages/organization/team-dsl-new.tsx',
    dsl: { modelCode: 'ab_team', pageKey: 'ab_team_form' },
  },
  {
    key: 'org.teams.edit',
    path: '/organization/teams/:teamPid/edit',
    title: { en: 'Edit Team', zh: '编辑团队' },
    menu: false,
    parentKey: 'org.teams',
    file: './plugins/core-organization/pages/organization/team-dsl-edit.tsx',
    dsl: { modelCode: 'ab_team', pageKey: 'ab_team_form' },
  },
  {
    key: 'org.team-detail',
    path: '/organization/teams/:teamPid',
    title: { en: 'Team Detail', zh: '团队详情' },
    menu: false,
    parentKey: 'org.teams',
    file: './plugins/core-organization/pages/organization/team-dsl-detail.tsx',
    dsl: { modelCode: 'ab_team', pageKey: 'ab_team_detail' },
  },
  {
    key: 'org.member-detail',
    path: '/organization/members/:memberPid',
    title: { en: 'Member Detail', zh: '成员详情' },
    menu: false,
    file: './plugins/core-organization/pages/organization/member-detail.tsx',
  },
]
