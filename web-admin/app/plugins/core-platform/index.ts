import { definePlugin } from '@auraboot/plugin-sdk'
import { manifest } from './manifest.js'

export default definePlugin({
  manifest,
  setup(ctx) {
    ctx.registerNavigationResources([
      {
        key: 'platform.plugins',
        path: '/system/plugins',
        title: { en: 'Plugin Manager', zh: '插件管理' },
        icon: 'package',
        menu: { order: 10, group: 'platform' },
        permission: 'platform.plugin.manage',
        source: 'plugin',
      },
      {
        key: 'platform.kernel-plugins',
        path: '/system/kernel-plugins',
        title: { en: 'Kernel Plugins', zh: '内核插件' },
        icon: 'cpu',
        menu: { order: 20, group: 'platform' },
        // No permission gate — kernel state is dev/diagnostic visibility.
        source: 'plugin',
      },
    ])
  },
})
