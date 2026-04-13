import type { PluginManifest } from '@auraboot/plugin-sdk'

export const manifest: PluginManifest = {
  code: 'core.demo',
  name: 'Demo',
  version: '0.1.0',
  description: 'Hello-world plugin proving the kernel ↔ plugin contract is wired.',
  kind: 'core',
  visibility: 'public',
  dependencies: { coreVersion: '^0.0.1' },
}
