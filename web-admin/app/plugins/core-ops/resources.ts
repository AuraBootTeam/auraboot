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
  {
    key: 'ops.troubleshooting', path: '/ops/troubleshooting',
    title: { en: 'Troubleshooting', zh: '鹰眼排障台' }, icon: 'search-check',
    menu: { order: 60, group: 'ops' },
    file: './plugins/core-ops/pages/troubleshooting/index.tsx',
  },
  {
    key: 'ops.error-board', path: '/ops/errors',
    title: { en: 'Error Board', zh: '错误看板' }, icon: 'alert-triangle',
    menu: { order: 70, group: 'ops' },
    file: './plugins/core-ops/pages/errors/index.tsx',
  },
  {
    key: 'ops.runtime-metrics', path: '/ops/runtime',
    title: { en: 'Runtime Metrics', zh: '运行时指标' }, icon: 'activity',
    menu: { order: 80, group: 'ops' },
    file: './plugins/core-ops/pages/runtime/index.tsx',
  },
]
