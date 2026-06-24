import type { PluginResource } from '../_shared/types.js'

export const RESOURCES: PluginResource[] = [
  {
    key: 'decisionops.consolePreview',
    path: '/decision-ops',
    title: { en: 'DecisionOps Console Preview', zh: '决策中心综合控制台预览' },
    icon: 'git-branch',
    menu: { hidden: true },
    breadcrumb: false,
    tab: false,
    file: './plugins/core-decisionops/pages/DecisionOpsConsolePage.tsx',
    meta: {
      implementation: 'react-console-preview',
      pairedDslEntry: '/p/decisionops_rollouts',
    },
  },
]
