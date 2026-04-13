import { definePlugin } from '@auraboot/plugin-sdk'
import { manifest } from './manifest.js'

export default definePlugin({
  manifest,
  setup(ctx) {
    ctx.registerNavigationResources([
      {
        key: 'ai.settings',
        path: '/ai/settings',
        title: { en: 'AI Settings', zh: 'AI 设置' },
        icon: 'settings',
        menu: { order: 10, group: 'ai' },
        permission: 'ai.settings.read',
        source: 'plugin',
      },
      {
        key: 'ai.colleagues',
        path: '/ai/colleagues',
        title: { en: 'AI Colleagues', zh: 'AI 同事' },
        icon: 'users',
        menu: { order: 20, group: 'ai' },
        permission: 'ai.agent.read',
        source: 'plugin',
      },
      {
        key: 'ai.colleagues.new',
        path: '/ai/colleagues/new',
        title: { en: 'New Agent', zh: '创建 AI 同事' },
        menu: false,
        parentKey: 'ai.colleagues',
        permission: 'ai.agent.create',
        source: 'plugin',
      },
      {
        key: 'ai.colleagues.detail',
        path: '/ai/colleagues/:agentPid',
        title: { en: 'AI Agent', zh: 'AI 同事详情' },
        menu: false,
        parentKey: 'ai.colleagues',
        permission: 'ai.agent.read',
        source: 'plugin',
      },
      {
        key: 'ai.colleagues.chat',
        path: '/ai/colleagues/:agentPid/chat',
        title: { en: 'Chat', zh: '对话' },
        menu: false,
        parentKey: 'ai.colleagues',
        permission: 'ai.agent.chat',
        source: 'plugin',
      },
    ])
  },
})
