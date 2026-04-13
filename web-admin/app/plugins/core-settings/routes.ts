import { route, type RouteConfigEntry } from '@react-router/dev/routes'

export function settingsRoutes(): RouteConfigEntry[] {
  return [
    route('/settings/plugins', './plugins/core-settings/pages/settings/PluginManagement.tsx'),
    route('/settings/user-preferences', './plugins/core-settings/pages/settings/user-preferences.tsx'),
    route('/settings/system-preferences', './plugins/core-settings/pages/settings/system-preferences.tsx'),
    route('/settings/notification-preferences', './plugins/core-settings/pages/settings/notification-preferences.tsx'),
    route('/settings/billing', './plugins/core-settings/pages/settings/billing.tsx'),
    route('/settings/webhooks', './plugins/core-settings/pages/settings/webhooks.tsx'),
    route('/settings/api-docs', './plugins/core-settings/pages/settings/api-docs.tsx'),
    route('/settings/connectors', './plugins/core-settings/pages/settings/connectors.tsx'),
    route('/settings/exchange-rates', './plugins/core-settings/pages/settings/exchange-rates.tsx'),
    route('/settings/timezone', './plugins/core-settings/pages/settings/timezone.tsx'),
    route('/settings/i18n-coverage', './plugins/core-settings/pages/settings/i18n-coverage.tsx'),
    route('/settings/i18n-workflow', './plugins/core-settings/pages/settings/i18n-workflow.tsx'),
  ]
}
