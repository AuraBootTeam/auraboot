import type { PluginResource } from '../_shared/types.js'

export const RESOURCES: PluginResource[] = [
  {
    key: 'decisionops.strategyStudio',
    path: '/decision-ops',
    title: { en: 'Strategy Studio', zh: '策略编排器' },
    icon: 'git-branch',
    menu: { hidden: true },
    breadcrumb: false,
    tab: false,
    file: './plugins/core-decisionops/pages/DecisionOpsConsolePage.tsx',
    meta: {
      implementation: 'strategy-studio',
      pairedDslEntry: '/p/decisionops_rollouts',
    },
  },
]
