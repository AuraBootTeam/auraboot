import type { PluginResource } from '../_shared/types.js'

export const RESOURCES: PluginResource[] = [
  {
    key: 'platform.plugins',
    path: '/system/plugins',
    title: { en: 'Plugin Manager', zh: '插件管理' },
    icon: 'package',
    menu: { order: 10, group: 'platform' },
    permission: 'platform.plugin.manage',
    file: './plugins/core-platform/pages/system/plugins/index.tsx',
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
