import { definePlugin } from '@auraboot/plugin-sdk'
import { manifest } from './manifest.js'

export default definePlugin({
  manifest,
  setup(ctx) {
    ctx.registerNavigationResources([
      {
        key: 'automation.list',
        path: '/automations',
        title: { en: 'Automations', zh: '自动化' },
        icon: 'zap',
        menu: { order: 10, group: 'automation' },
        permission: 'automation.read',
        source: 'plugin',
      },
      {
        key: 'automation.edit',
        path: '/automation/:id',
        title: { en: 'Automation', zh: '自动化详情' },
        menu: false,
        parentKey: 'automation.list',
        permission: 'automation.edit',
        source: 'plugin',
      },
    ])
  },
})
