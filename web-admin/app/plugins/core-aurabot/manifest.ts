import type { PluginManifest } from '@auraboot/plugin-sdk'

export const manifest: PluginManifest = {
  code: 'core.aurabot',
  name: 'AuraBot',
  version: '0.1.0',
  description: 'AuraBot dashboard, traces, run logs, providers, prompts, RAG knowledge.',
  kind: 'core',
  visibility: 'public',
  permissions: ['aurabot.dashboard.read', 'aurabot.trace.read', 'aurabot.run.read', 'aurabot.provider.read', 'aurabot.prompt.read', 'aurabot.knowledge.read'],
  dependencies: { coreVersion: '^0.0.1' },
}
