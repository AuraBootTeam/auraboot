import { definePlugin } from '@auraboot/plugin-sdk'
import { manifest } from './manifest.js'

export default definePlugin({
  manifest,
  setup(ctx) {
    ctx.registerNavigationResources([
      {
        key: 'org.teams',
        path: '/organization/teams',
        title: { en: 'Teams', zh: '团队' },
        icon: 'users',
        menu: { order: 10, group: 'organization' },
        permission: 'org.team.read',
        source: 'plugin',
      },
      {
        key: 'org.team-detail',
        path: '/organization/teams/:teamPid',
        title: { en: 'Team Detail', zh: '团队详情' },
        menu: false,
        parentKey: 'org.teams',
        permission: 'org.team.read',
        source: 'plugin',
      },
      {
        key: 'org.member-detail',
        path: '/organization/members/:memberPid',
        title: { en: 'Member Detail', zh: '成员详情' },
        menu: false,
        permission: 'org.member.read',
        source: 'plugin',
      },
    ])
  },
})
