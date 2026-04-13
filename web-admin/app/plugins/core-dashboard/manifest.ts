import type { PluginManifest } from '@auraboot/plugin-sdk'

export const manifest: PluginManifest = {
  code: 'core.dashboard',
  name: 'Dashboard',
  version: '0.1.0',
  description: 'Dashboard viewer + basic charts (line/bar/pie/area). The full Dashboard Designer ships in ent-dashboard-designer (enterprise).',
  kind: 'core',
  visibility: 'public',
  permissions: ['dashboard.view'],
  dependencies: { coreVersion: '^0.0.1' },
}
