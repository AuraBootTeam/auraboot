import { definePlugin } from '@auraboot/plugin-sdk'
import { manifest } from './manifest.js'

export default definePlugin({
  manifest,
  setup(ctx) {
    ctx.registerNavigationResource({
      key: 'platform.plugins',
      path: '/system/plugins',
      title: { en: 'Plugin Manager', zh: '插件管理' },
      icon: 'package',
      menu: { order: 10, group: 'platform' },
      permission: 'platform.plugin.manage',
      source: 'plugin',
    })
  },
})
