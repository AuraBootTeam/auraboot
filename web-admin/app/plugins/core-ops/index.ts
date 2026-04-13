import { definePlugin } from '@auraboot/plugin-sdk'
import { manifest } from './manifest.js'

export default definePlugin({
  manifest,
  setup(ctx) {
    ctx.registerNavigationResources([
      { key: 'ops.notifications', path: '/notifications', title: { en: 'Notifications', zh: '通知' }, icon: 'bell', menu: { order: 10, group: 'ops' }, permission: 'notification.read', source: 'plugin' },
      { key: 'ops.notification-rules', path: '/notification-rules', title: { en: 'Notification Rules', zh: '通知规则' }, icon: 'bell-ring', menu: { order: 20, group: 'ops' }, permission: 'notification.read', source: 'plugin' },
      { key: 'ops.scheduler', path: '/scheduler', title: { en: 'Scheduler', zh: '调度' }, icon: 'clock', menu: { order: 30, group: 'ops' }, permission: 'scheduler.read', source: 'plugin' },
      { key: 'ops.audit-logs', path: '/audit-logs', title: { en: 'Audit Logs', zh: '审计日志' }, icon: 'shield-alert', menu: { order: 40, group: 'ops' }, permission: 'audit.read', source: 'plugin' },
      { key: 'ops.documents', path: '/documents', title: { en: 'Documents', zh: '文档' }, icon: 'file-text', menu: { order: 50, group: 'ops' }, permission: 'documents.use', source: 'plugin' },
    ])
  },
})
