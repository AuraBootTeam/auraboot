import type { PluginResource } from '../_shared/types.js'

export const RESOURCES: PluginResource[] = [
  {
    key: 'decisionops.console',
    path: '/decision-ops',
    title: { en: 'DecisionOps', zh: '决策中心' },
    icon: 'git-branch',
    menu: { order: 60, group: 'automation' },
    file: './plugins/core-decisionops/pages/DecisionOpsConsolePage.tsx',
  },
]
