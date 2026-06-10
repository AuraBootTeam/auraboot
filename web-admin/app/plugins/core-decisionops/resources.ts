import type { PluginResource } from '../_shared/types.js'

export const RESOURCES: PluginResource[] = [
  {
    key: 'decisionops.legacyConsoleRedirect',
    path: '/decision-ops',
    title: { en: 'DecisionOps Redirect', zh: '决策中心兼容跳转' },
    icon: 'git-branch',
    menu: { hidden: true },
    breadcrumb: false,
    tab: false,
    file: './plugins/core-decisionops/pages/DecisionOpsConsolePage.tsx',
    meta: { legacyRedirectTo: '/p/decisionops_rollouts' },
  },
]
