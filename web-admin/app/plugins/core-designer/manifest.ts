import type { PluginManifest } from '@auraboot/plugin-sdk'

export const manifest: PluginManifest = {
  code: 'core.designer',
  name: 'Designers',
  version: '0.1.0',
  description: 'Page Designer, Flow Designer, Query Builder.',
  kind: 'core',
  visibility: 'public',
  permissions: ['designer.page.use', 'designer.flow.use', 'query.builder.use'],
  dependencies: { plugins: ['core.aurabot'], coreVersion: '^0.0.1' },
}
