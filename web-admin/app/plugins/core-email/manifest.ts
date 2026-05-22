import type { PluginManifest } from '@auraboot/plugin-sdk'

export const manifest: PluginManifest = {
  code: 'core.email',
  name: 'Email',
  version: '0.1.0',
  description: 'Gmail account management, compose, and message thread views.',
  kind: 'core',
  visibility: 'public',
  permissions: ['email.view'],
  dependencies: { coreVersion: '^0.0.1' },
}
