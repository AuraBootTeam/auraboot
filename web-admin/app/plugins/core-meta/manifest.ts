import type { PluginManifest } from '@auraboot/plugin-sdk'

export const manifest: PluginManifest = {
  code: 'core.meta',
  name: 'Meta',
  version: '0.1.0',
  description: 'Models, fields, dictionaries, named queries, consistency rules, AI-driven modeling.',
  kind: 'core',
  visibility: 'public',
  permissions: ['meta.model.read', 'meta.model.edit', 'meta.field.read', 'meta.dict.read', 'meta.named-query.read', 'meta.rules.read', 'meta.ai-modeling.use'],
  dependencies: { coreVersion: '^0.0.1' },
}
