import type { PluginManifest } from '@auraboot/plugin-sdk'

export const manifest: PluginManifest = {
  code: 'core.admin',
  name: 'Admin',
  version: '0.1.0',
  description: 'System admin: cloud config, login channels, entitlements, infrastructure, templates, environments, document upload.',
  kind: 'core',
  visibility: 'public',
  permissions: ['admin.read', 'admin.manage'],
  dependencies: { coreVersion: '^0.0.1' },
}
