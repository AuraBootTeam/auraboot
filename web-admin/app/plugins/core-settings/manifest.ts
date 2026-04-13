import type { PluginManifest } from '@auraboot/plugin-sdk'

export const manifest: PluginManifest = {
  code: 'core.settings',
  name: 'Settings',
  version: '0.1.0',
  description: 'Tenant settings: plugins, preferences, billing, webhooks, API docs, connectors, currency, timezone, i18n.',
  kind: 'core',
  visibility: 'public',
  permissions: ['settings.tenant.manage'],
  dependencies: { coreVersion: '^0.0.1' },
}
