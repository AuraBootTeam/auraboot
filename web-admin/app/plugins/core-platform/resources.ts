import type { PluginResource } from '../_shared/types.js'

export const RESOURCES: PluginResource[] = [
  {
    key: 'platform.plugins',
    path: '/plugins',
    title: { en: 'Plugins', zh: '插件管理' },
    icon: 'package',
    menu: { order: 10, group: 'platform' },
    permission: 'plugin_management',
    file: './plugins/core-platform/pages/plugins/index.tsx',
  },
  {
    key: 'platform.plugin-solution-detail',
    path: '/plugins/solutions/:code',
    title: { en: 'Solution Detail', zh: '解决方案详情' },
    icon: 'package',
    permission: 'plugin_management',
    file: './plugins/core-platform/pages/plugins/solutions/$code.tsx',
  },
  {
    key: 'platform.plugin-detail',
    path: '/plugins/:pluginId',
    title: { en: 'Plugin Detail', zh: '插件详情' },
    icon: 'package',
    permission: 'plugin_management',
    file: './plugins/core-platform/pages/plugins/$pluginId.tsx',
  },
  {
    key: 'platform.kernel-plugins',
    path: '/system/kernel-plugins',
    title: { en: 'Kernel Plugins', zh: '内核插件' },
    icon: 'cpu',
    menu: { order: 20, group: 'platform' },
    file: './plugins/core-platform/pages/KernelPluginsPage.tsx',
  },
]
