import type { PluginResource } from '../_shared/types.js'

export const RESOURCES: PluginResource[] = [
  {
    // Converted to a DSL page (ai_settings_hub, a card-grid over a static dataSource) served at
    // /p/c/ai_settings_hub. No `file`: this is now a menu-only entry linking to the DSL route
    // (toRouteEntries skips fileless resources), replacing the hand-written settings.tsx.
    key: 'ai.settings', path: '/p/c/ai_settings_hub',
    title: { en: 'AI Settings', zh: 'AI 设置' }, icon: 'settings',
    menu: { order: 10, group: 'ai' },
  },
  {
    key: 'ai.colleagues', path: '/ai/colleagues',
    title: { en: 'AI Colleagues', zh: 'AI 同事' }, icon: 'users',
    menu: { order: 20, group: 'ai' },
    file: './plugins/core-ai-colleagues/pages/ai/colleagues.tsx',
  },
  {
    key: 'ai.colleagues.new', path: '/ai/colleagues/new',
    title: { en: 'New Agent', zh: '创建 AI 同事' },
    menu: false, parentKey: 'ai.colleagues',
    file: './plugins/core-ai-colleagues/pages/ai/colleagues.new.tsx',
  },
  {
    key: 'ai.colleagues.detail', path: '/ai/colleagues/:agentPid',
    title: { en: 'AI Agent', zh: 'AI 同事详情' },
    menu: false, parentKey: 'ai.colleagues',
    file: './plugins/core-ai-colleagues/pages/ai/colleagues.$agentPid.tsx',
  },
  {
    key: 'ai.colleagues.chat', path: '/ai/colleagues/:agentPid/chat',
    title: { en: 'Chat', zh: '对话' },
    menu: false, parentKey: 'ai.colleagues',
    file: './plugins/core-ai-colleagues/pages/ai/colleagues.$agentPid.chat.tsx',
  },
]
