import type { PluginManifest } from '@auraboot/plugin-sdk'

export const manifest: PluginManifest = {
  code: 'core.ai-colleagues',
  name: 'AI Colleagues',
  version: '0.1.0',
  description: 'AI agent management — center settings, agent grid, creation wizard, detail, chat.',
  kind: 'core',
  visibility: 'public',
  permissions: ['ai.settings.read', 'ai.agent.read', 'ai.agent.create', 'ai.agent.chat'],
  dependencies: { coreVersion: '^0.0.1' },
}
