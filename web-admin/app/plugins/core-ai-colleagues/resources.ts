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
    // Converted to a DSL page (ai_colleagues, kind:detail) whose single custom block,
    // AgentColleaguesGrid, ports the agent card grid. Menu-only entry linking to the DSL route.
    key: 'ai.colleagues', path: '/p/c/ai_colleagues',
    title: { en: 'AI Colleagues', zh: 'AI 同事' }, icon: 'users',
    menu: { order: 20, group: 'ai' },
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
    // Converted to a DSL page (ai_colleague_chat) whose AgentChatEmbed custom block wraps
    // AuraBotChat; the agent comes from ?agentPid=. Kept as a resource for menu parentage,
    // but fileless (no React route) — callers navigate to /p/c/ai_colleague_chat?agentPid=…
    key: 'ai.colleagues.chat', path: '/p/c/ai_colleague_chat',
    title: { en: 'Chat', zh: '对话' },
    menu: false, parentKey: 'ai.colleagues',
  },
]
