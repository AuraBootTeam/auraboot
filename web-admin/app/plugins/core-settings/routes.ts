import { route, type RouteConfigEntry } from '@react-router/dev/routes'

export function settingsRoutes(): RouteConfigEntry[] {
  return [
    route('/settings/plugins', './routes/settings/PluginManagement.tsx'),
    route('/settings/user-preferences', './routes/settings/user-preferences.tsx'),
    route('/settings/system-preferences', './routes/settings/system-preferences.tsx'),
    route('/settings/notification-preferences', './routes/settings/notification-preferences.tsx'),
    route('/settings/billing', './routes/settings/billing.tsx'),
    route('/settings/webhooks', './routes/settings/webhooks.tsx'),
    route('/settings/api-docs', './routes/settings/api-docs.tsx'),
    route('/settings/connectors', './routes/settings/connectors.tsx'),
    route('/settings/exchange-rates', './routes/settings/exchange-rates.tsx'),
    route('/settings/timezone', './routes/settings/timezone.tsx'),
    route('/settings/i18n-coverage', './routes/settings/i18n-coverage.tsx'),
    route('/settings/i18n-workflow', './routes/settings/i18n-workflow.tsx'),
  ]
}
