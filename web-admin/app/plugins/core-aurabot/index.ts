import { definePlugin } from '@auraboot/plugin-sdk'
import { manifest } from './manifest.js'

export default definePlugin({
  manifest,
  setup(ctx) {
    ctx.registerNavigationResources([
      {
        key: 'aurabot.dashboard',
        path: '/aurabot/dashboard',
        title: { en: 'AuraBot Dashboard', zh: 'AuraBot 看板' },
        icon: 'gauge',
        menu: { order: 10, group: 'aurabot' },
        permission: 'aurabot.dashboard.read',
        source: 'plugin',
      },
      {
        key: 'aurabot.traces',
        path: '/aurabot/traces',
        title: { en: 'Trace Console', zh: '追踪控制台' },
        icon: 'activity',
        menu: { order: 20, group: 'aurabot' },
        permission: 'aurabot.trace.read',
        source: 'plugin',
      },
      {
        key: 'aurabot.trace-detail',
        path: '/aurabot/traces/:traceId',
        title: { en: 'Trace', zh: '追踪详情' },
        menu: false,
        parentKey: 'aurabot.traces',
        permission: 'aurabot.trace.read',
        source: 'plugin',
      },
      {
        key: 'aurabot.runs',
        path: '/aurabot/runs',
        title: { en: 'Run Log', zh: '运行日志' },
        icon: 'list',
        menu: { order: 30, group: 'aurabot' },
        permission: 'aurabot.run.read',
        source: 'plugin',
      },
      {
        key: 'aurabot.providers',
        path: '/aurabot/providers',
        title: { en: 'LLM Providers', zh: '模型服务' },
        icon: 'cpu',
        menu: { order: 40, group: 'aurabot' },
        permission: 'aurabot.provider.read',
        source: 'plugin',
      },
      {
        key: 'aurabot.prompts',
        path: '/aurabot/prompts',
        title: { en: 'Prompts', zh: '提示词模板' },
        icon: 'message-square',
        menu: { order: 50, group: 'aurabot' },
        permission: 'aurabot.prompt.read',
        source: 'plugin',
      },
      {
        key: 'aurabot.knowledge',
        path: '/aurabot/knowledge',
        title: { en: 'RAG Knowledge', zh: 'RAG 知识库' },
        icon: 'book-open',
        menu: { order: 60, group: 'aurabot' },
        permission: 'aurabot.knowledge.read',
        source: 'plugin',
      },
      {
        key: 'aurabot.knowledge-detail',
        path: '/aurabot/knowledge/:kbPid',
        title: { en: 'Knowledge Base', zh: '知识库详情' },
        menu: false,
        parentKey: 'aurabot.knowledge',
        permission: 'aurabot.knowledge.read',
        source: 'plugin',
      },
    ])
  },
})
