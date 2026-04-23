import type { PluginManifest } from '@auraboot/plugin-sdk'

export const manifest: PluginManifest = {
  code: 'core.dashboard',
  name: 'Dashboard',
  version: '0.1.0',
  description: 'Dashboard viewer and designer with chart widgets, workbench layouts, and authoring tools.',
  kind: 'core',
  visibility: 'public',
  permissions: ['dashboard.view'],
  dependencies: { coreVersion: '^0.0.1' },
}
