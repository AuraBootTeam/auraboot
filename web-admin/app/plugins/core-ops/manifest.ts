import type { PluginManifest } from '@auraboot/plugin-sdk'

export const manifest: PluginManifest = {
  code: 'core.ops',
  name: 'Operations',
  version: '0.1.0',
  description: 'Operational tools — notifications, notification rules, scheduler, audit logs, documents.',
  kind: 'core',
  visibility: 'public',
  permissions: ['notification.read', 'scheduler.read', 'audit.read', 'documents.use'],
  dependencies: { coreVersion: '^0.0.1' },
}
