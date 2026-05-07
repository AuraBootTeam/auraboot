import type { PluginResource } from '../_shared/types.js'

export const RESOURCES: PluginResource[] = [
  {
    key: 'automation.list',
    path: '/automations',
    title: { en: 'Automations', zh: '自动化' },
    icon: 'zap',
    menu: { order: 10, group: 'automation' },
    file: './plugins/core-automation/pages/automations.tsx',
  },
  {
    key: 'automation.edit',
    path: '/automation/:id',
    title: { en: 'Automation', zh: '自动化详情' },
    menu: false,
    parentKey: 'automation.list',
    file: './plugins/core-automation/pages/automation.$id.tsx',
  },
]
