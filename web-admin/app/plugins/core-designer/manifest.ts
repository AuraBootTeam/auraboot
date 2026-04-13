import type { PluginManifest } from '@auraboot/plugin-sdk'

export const manifest: PluginManifest = {
  code: 'core.designer',
  name: 'Designers',
  version: '0.1.0',
  description: 'Page Designer, BPMN Designer, Flow Designer, Query Builder.',
  kind: 'core',
  visibility: 'public',
  permissions: ['designer.page.use', 'designer.bpmn.use', 'designer.flow.use', 'query.builder.use'],
  // core.bpm components are imported directly (BPMN designer property panels
  // reference bpm field components) but NOT declared here — declaring would
  // create a circular dependency with core.bpm (which imports core.designer's
  // BPMN viewer for the Process Status page). The imports are React
  // components, not runtime hooks, so they work regardless of activation order.
  dependencies: { plugins: ['core.aurabot'], coreVersion: '^0.0.1' },
}
