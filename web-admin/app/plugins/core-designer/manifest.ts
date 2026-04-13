import type { PluginManifest } from '@auraboot/plugin-sdk'

export const manifest: PluginManifest = {
  code: 'core.designer',
  name: 'Designers',
  version: '0.1.0',
  description: 'Page Designer, BPMN Designer, Flow Designer, Query Builder.',
  kind: 'core',
  visibility: 'public',
  permissions: ['designer.page.use', 'designer.bpmn.use', 'designer.flow.use', 'query.builder.use'],
  dependencies: { coreVersion: '^0.0.1' },
}
