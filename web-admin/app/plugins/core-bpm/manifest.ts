import type { PluginManifest } from '@auraboot/plugin-sdk'

export const manifest: PluginManifest = {
  code: 'core.bpm',
  name: 'BPM',
  version: '0.1.0',
  description: 'Business Process Management — task center, approval inbox, process status viewer, SLA monitor.',
  kind: 'core',
  visibility: 'public',
  permissions: [
    'bpm.task.read',
    'bpm.task.act',
    'bpm.process.read',
    'bpm.sla.read',
  ],
  dependencies: { plugins: ['core.designer'], coreVersion: '^0.0.1' },
}
