import type { PluginResource } from '../_shared/types.js'

export const RESOURCES: PluginResource[] = [
  { key: 'settings.plugins', path: '/settings/plugins', title: { en: 'Plugins', zh: '插件管理' }, icon: 'puzzle', menu: { order: 10, group: 'settings' }, permission: 'settings.tenant.manage', file: './plugins/core-settings/pages/settings/PluginManagement.tsx' },
  { key: 'settings.user-prefs', path: '/settings/user-preferences', title: { en: 'User Preferences', zh: '用户偏好' }, icon: 'sliders', menu: { order: 20, group: 'settings' }, file: './plugins/core-settings/pages/settings/user-preferences.tsx' },
  { key: 'settings.system-prefs', path: '/settings/system-preferences', title: { en: 'System Preferences', zh: '系统偏好' }, icon: 'sliders-horizontal', menu: { order: 30, group: 'settings' }, permission: 'settings.tenant.manage', file: './plugins/core-settings/pages/settings/system-preferences.tsx' },
  { key: 'settings.notif-prefs', path: '/settings/notification-preferences', title: { en: 'Notification Preferences', zh: '通知偏好' }, icon: 'bell', menu: { order: 40, group: 'settings' }, file: './plugins/core-settings/pages/settings/notification-preferences.tsx' },
  { key: 'settings.billing', path: '/settings/billing', title: { en: 'Billing', zh: '账单' }, icon: 'credit-card', menu: { order: 50, group: 'settings' }, permission: 'settings.tenant.manage', file: './plugins/core-settings/pages/settings/billing.tsx' },
  { key: 'settings.webhooks', path: '/settings/webhooks', title: { en: 'Webhooks', zh: 'Webhooks' }, icon: 'webhook', menu: { order: 60, group: 'settings' }, permission: 'settings.tenant.manage', file: './plugins/core-settings/pages/settings/webhooks.tsx' },
  { key: 'settings.api-docs', path: '/settings/api-docs', title: { en: 'API Docs', zh: 'API 文档' }, icon: 'file-code', menu: { order: 70, group: 'settings' }, file: './plugins/core-settings/pages/settings/api-docs.tsx' },
  { key: 'settings.connectors', path: '/settings/connectors', title: { en: 'Connectors', zh: '连接器' }, icon: 'plug', menu: { order: 80, group: 'settings' }, permission: 'settings.tenant.manage', file: './plugins/core-settings/pages/settings/connectors.tsx' },
  { key: 'settings.exchange-rates', path: '/settings/exchange-rates', title: { en: 'Exchange Rates', zh: '汇率' }, icon: 'banknote', menu: { order: 90, group: 'settings' }, file: './plugins/core-settings/pages/settings/exchange-rates.tsx' },
  { key: 'settings.timezone', path: '/settings/timezone', title: { en: 'Timezone', zh: '时区' }, icon: 'globe', menu: { order: 100, group: 'settings' }, file: './plugins/core-settings/pages/settings/timezone.tsx' },
  { key: 'settings.i18n-coverage', path: '/settings/i18n-coverage', title: { en: 'i18n Coverage', zh: 'i18n 覆盖率' }, icon: 'languages', menu: { order: 110, group: 'settings' }, file: './plugins/core-settings/pages/settings/i18n-coverage.tsx' },
  { key: 'settings.i18n-workflow', path: '/settings/i18n-workflow', title: { en: 'i18n Workflow', zh: 'i18n 翻译流程' }, icon: 'workflow', menu: { order: 120, group: 'settings' }, file: './plugins/core-settings/pages/settings/i18n-workflow.tsx' },
]
