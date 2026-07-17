import type { PluginManifest } from '@auraboot/plugin-sdk'

export const manifest: PluginManifest = {
  code: 'core.decisionops',
  name: 'Rule Center',
  version: '0.1.0',
  description: 'Rule Center console — condition fragments, strategy studio, decision definitions, execution logs, governance.',
  kind: 'core',
  visibility: 'public',
  permissions: ['decision.definition.read'],
  dependencies: { coreVersion: '^0.0.1' },
}
