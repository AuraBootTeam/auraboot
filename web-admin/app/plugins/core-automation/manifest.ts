import type { PluginManifest } from '@auraboot/plugin-sdk'

export const manifest: PluginManifest = {
  code: 'core.automation',
  name: 'Automation',
  version: '0.1.0',
  description: 'Automation builder and runtime (basic).',
  kind: 'core',
  visibility: 'public',
  permissions: ['automation.read', 'automation.edit'],
  dependencies: { coreVersion: '^0.0.1' },
}
