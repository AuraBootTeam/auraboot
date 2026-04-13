import type { PluginResource } from '../_shared/types.js'

export const RESOURCES: PluginResource[] = [
  {
    key: 'home.workbench',
    path: '/home',
    title: { en: 'Home', zh: '工作台' },
    icon: 'home',
    menu: { order: 0, group: 'workbench' },
    file: './plugins/core-dashboard/pages/home/index.tsx',
  },
  {
    key: 'home.settings',
    path: '/home/settings',
    title: { en: 'Workbench Settings', zh: '工作台设置' },
    menu: false,
    parentKey: 'home.workbench',
    file: './plugins/core-dashboard/pages/home/settings.tsx',
  },
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
