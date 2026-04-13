import type { PluginManifest } from '@auraboot/plugin-sdk'

export const manifest: PluginManifest = {
  code: 'core.personal',
  name: 'Personal',
  version: '0.1.0',
  description: 'Personal profile, security, social links, account deactivation.',
  kind: 'core',
  visibility: 'public',
  dependencies: { coreVersion: '^0.0.1' },
}
