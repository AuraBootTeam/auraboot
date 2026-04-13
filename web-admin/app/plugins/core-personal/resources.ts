import type { PluginResource } from '../_shared/types.js'

export const RESOURCES: PluginResource[] = [
  {
    key: 'personal.profile',
    path: '/personal/profile',
    title: { en: 'Profile', zh: '个人资料' },
    icon: 'user',
    menu: { order: 10, group: 'personal' },
    file: './plugins/core-personal/pages/personal/profile.tsx',
  },
  {
    key: 'personal.security',
    path: '/personal/security',
    title: { en: 'Security', zh: '账号安全' },
    icon: 'shield',
    menu: { order: 20, group: 'personal' },
    file: './plugins/core-personal/pages/personal/security.tsx',
  },
  {
    key: 'personal.social-links',
    path: '/personal/social-links',
    title: { en: 'Social Links', zh: '关联账号' },
    icon: 'link',
    menu: { order: 30, group: 'personal' },
    file: './plugins/core-personal/pages/personal/social-links.tsx',
  },
  {
    key: 'personal.deactivation',
    path: '/personal/deactivation',
    title: { en: 'Deactivate Account', zh: '注销账号' },
    icon: 'user-x',
    menu: { order: 90, group: 'personal' },
    file: './plugins/core-personal/pages/personal/deactivation.tsx',
  },
]
