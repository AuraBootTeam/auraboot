import type { PluginResource } from '../_shared/types.js'

export const RESOURCES: PluginResource[] = [
  {
    key: 'ops.notifications', path: '/notifications',
    title: { en: 'Notifications', zh: '通知' }, icon: 'bell',
    menu: { order: 10, group: 'ops' },
    file: './plugins/core-ops/pages/notifications/index.tsx',
  },
  {
    key: 'ops.notification-rules', path: '/notification-rules',
    title: { en: 'Notification Rules', zh: '通知规则' }, icon: 'bell-ring',
    menu: { order: 20, group: 'ops' },
    file: './plugins/core-ops/pages/notification-rules/index.tsx',
  },
  {
    key: 'ops.scheduler', path: '/scheduler',
    title: { en: 'Scheduler', zh: '调度' }, icon: 'clock',
    menu: { order: 30, group: 'ops' },
    file: './plugins/core-ops/pages/scheduler/index.tsx',
  },
  {
    key: 'ops.audit-logs', path: '/audit-logs',
    title: { en: 'Audit Logs', zh: '审计日志' }, icon: 'shield-alert',
    menu: { order: 40, group: 'ops' },
    file: './plugins/core-ops/pages/audit-logs/index.tsx',
  },
  {
    key: 'ops.documents', path: '/documents',
    title: { en: 'Documents', zh: '文档' }, icon: 'file-text',
    menu: { order: 50, group: 'ops' },
    file: './plugins/core-ops/pages/documents/index.tsx',
  },
]
