import type { PluginManifest } from '@auraboot/plugin-sdk'

export const manifest: PluginManifest = {
  code: 'core.platform',
  name: 'Platform',
  version: '0.1.0',
  description: 'System-wide plugin manager (cross-tenant view).',
  kind: 'core',
  visibility: 'public',
  permissions: ['platform.plugin.manage'],
  dependencies: { coreVersion: '^0.0.1' },
}
