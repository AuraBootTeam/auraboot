import type { PluginResource } from '../_shared/types.js'

/**
 * Single source of truth for core-bpm routes + nav metadata.
 *
 * `index.ts` registers these as NavigationResources (menu/breadcrumb/
 * permission); `routes.ts` derives React Router 7 route() entries from
 * the `file` field. No path duplication.
 */
export const RESOURCES: PluginResource[] = [
  {
    key: 'bpm.task-center',
    path: '/bpm/task-center',
    title: { en: 'Task Center', zh: '我的待办' },
    icon: 'inbox',
    menu: { order: 10, group: 'bpm' },
    file: './plugins/core-bpm/pages/TaskCenter.tsx',
  },
  {
    key: 'bpm.process-status',
    path: '/bpm/process-status',
    title: { en: 'Process Status', zh: '流程状态' },
    icon: 'activity',
    menu: false,
    file: './plugins/core-bpm/pages/ProcessStatus.tsx',
  },
  {
    key: 'bpm.sla-monitor',
    path: '/bpm/sla-monitor',
    title: { en: 'SLA Monitor', zh: 'SLA 监控' },
    icon: 'gauge',
    menu: { order: 30, group: 'bpm' },
    file: './plugins/core-bpm/pages/SlaMonitor.tsx',
  },
]
