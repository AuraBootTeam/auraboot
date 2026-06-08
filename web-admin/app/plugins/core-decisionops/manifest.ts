import type { PluginManifest } from '@auraboot/plugin-sdk'

export const manifest: PluginManifest = {
  code: 'core.decisionops',
  name: 'DecisionOps',
  version: '0.1.0',
  description: 'DecisionOps console — decision definitions, condition designer, execution logs, governance.',
  kind: 'core',
  visibility: 'public',
  permissions: ['decision.definition.read'],
  dependencies: { coreVersion: '^0.0.1' },
}
