import type { PluginResource } from '../_shared/types.js'

export const RESOURCES: PluginResource[] = [
  {
    key: 'dashboard.list',
    path: '/dashboards',
    title: { en: 'Dashboards', zh: '仪表板' },
    icon: 'gauge',
    menu: { order: 10, group: 'dashboard' },
    permission: 'dashboard.view',
    file: './plugins/core-dashboard/pages/dashboards/index.tsx',
  },
  {
    key: 'dashboard.view',
    path: '/dashboards/view/:code',
    title: { en: 'Dashboard', zh: '仪表板详情' },
    menu: false,
    parentKey: 'dashboard.list',
    permission: 'dashboard.view',
    file: './plugins/core-dashboard/pages/dashboards/view.$code.tsx',
  },
]
