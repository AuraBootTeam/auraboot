import type { PluginManifest } from '@auraboot/plugin-sdk'

export const manifest: PluginManifest = {
  code: 'core.organization',
  name: 'Organization',
  version: '0.1.0',
  description: 'Organization members and teams (basic).',
  kind: 'core',
  visibility: 'public',
  permissions: ['org.member.read', 'org.team.read', 'org.team.edit'],
  dependencies: { coreVersion: '^0.0.1' },
}
