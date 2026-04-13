import { definePlugin } from '@auraboot/plugin-sdk'
import { manifest } from './manifest.js'

/**
 * core-bpm plugin — registers BPM navigation resources.
 *
 * The page components live under ./pages/ and are loaded via React Router 7
 * (see ./routes.ts for the static declarations). This setup() registers the
 * same paths with the kernel so menu, breadcrumb, permission gating, and
 * tab strips can derive from a single source.
 *
 * Pilot migration target for M3 — proves the kernel ↔ React Router contract
 * for a real OSS feature. Subsequent core-* plugins follow the same shape.
 */
export default definePlugin({
  manifest,
  setup(ctx) {
    ctx.registerNavigationResources([
      {
        key: 'bpm.task-center',
        path: '/bpm/task-center',
        title: { en: 'Task Center', zh: '我的待办' },
        icon: 'inbox',
        menu: { order: 10, group: 'bpm' },
        permission: 'bpm.task.read',
        source: 'plugin',
        meta: { file: './plugins/core-bpm/pages/TaskCenter.tsx' },
      },
      {
        key: 'bpm.approval-inbox',
        path: '/bpm/approval-inbox',
        title: { en: 'Approval Inbox', zh: '审批中心' },
        icon: 'check-circle',
        menu: { order: 20, group: 'bpm' },
        permission: 'bpm.task.act',
        source: 'plugin',
        meta: { file: './plugins/core-bpm/pages/ApprovalInbox.tsx' },
      },
      {
        key: 'bpm.process-status',
        path: '/bpm/process-status',
        title: { en: 'Process Status', zh: '流程状态' },
        icon: 'activity',
        // Hidden from menu — accessed via deep link with query params.
        menu: false,
        permission: 'bpm.process.read',
        source: 'plugin',
        meta: { file: './plugins/core-bpm/pages/ProcessStatus.tsx' },
      },
      {
        key: 'bpm.sla-monitor',
        path: '/bpm/sla-monitor',
        title: { en: 'SLA Monitor', zh: 'SLA 监控' },
        icon: 'gauge',
        menu: { order: 30, group: 'bpm' },
        permission: 'bpm.sla.read',
        source: 'plugin',
        meta: { file: './plugins/core-bpm/pages/SlaMonitor.tsx' },
      },
    ])

    ctx.log.info('core-bpm activated', { routes: 4 })
  },
})
