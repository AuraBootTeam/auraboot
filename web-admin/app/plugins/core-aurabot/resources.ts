import type { PluginResource } from '../_shared/types.js'

export const RESOURCES: PluginResource[] = [
  {
    key: 'aurabot.dashboard', path: '/aurabot/dashboard',
    title: { en: 'AuraBot Dashboard', zh: 'AuraBot 看板' }, icon: 'gauge',
    menu: { order: 10, group: 'aurabot' }, permission: 'aurabot.dashboard.read',
    file: './plugins/core-aurabot/pages/mission-control/index.tsx',
  },
  {
    key: 'aurabot.traces', path: '/aurabot/traces',
    title: { en: 'Trace Console', zh: '追踪控制台' }, icon: 'activity',
    menu: { order: 20, group: 'aurabot' }, permission: 'aurabot.trace.read',
    file: './plugins/core-aurabot/pages/ai-trace/index.tsx',
  },
  {
    key: 'aurabot.trace-detail', path: '/aurabot/traces/:traceId',
    title: { en: 'Trace', zh: '追踪详情' },
    menu: false, parentKey: 'aurabot.traces', permission: 'aurabot.trace.read',
    file: './plugins/core-aurabot/pages/ai-trace/$traceId.tsx',
  },
  {
    key: 'aurabot.runs', path: '/aurabot/runs',
    title: { en: 'Run Log', zh: '运行日志' }, icon: 'list',
    menu: { order: 30, group: 'aurabot' }, permission: 'aurabot.run.read',
    file: './plugins/core-aurabot/pages/aurabot/runs.tsx',
  },
  {
    key: 'aurabot.providers', path: '/aurabot/providers',
    title: { en: 'LLM Providers', zh: '模型服务' }, icon: 'cpu',
    menu: { order: 40, group: 'aurabot' }, permission: 'aurabot.provider.read',
    file: './plugins/core-aurabot/pages/aurabot/providers.tsx',
  },
  {
    key: 'aurabot.prompts', path: '/aurabot/prompts',
    title: { en: 'Prompts', zh: '提示词模板' }, icon: 'message-square',
    menu: { order: 50, group: 'aurabot' }, permission: 'aurabot.prompt.read',
    file: './plugins/core-aurabot/pages/aurabot/prompts.tsx',
  },
  {
    key: 'aurabot.knowledge', path: '/aurabot/knowledge',
    title: { en: 'RAG Knowledge', zh: 'RAG 知识库' }, icon: 'book-open',
    menu: { order: 60, group: 'aurabot' }, permission: 'aurabot.knowledge.read',
    file: './plugins/core-aurabot/pages/aurabot/knowledge.tsx',
  },
  {
    key: 'aurabot.knowledge-detail', path: '/aurabot/knowledge/:kbPid',
    title: { en: 'Knowledge Base', zh: '知识库详情' },
    menu: false, parentKey: 'aurabot.knowledge', permission: 'aurabot.knowledge.read',
    file: './plugins/core-aurabot/pages/aurabot/knowledge.$kbPid.tsx',
  },
  {
    key: 'aurabot.learning-drafts', path: '/aurabot/learning-drafts',
    title: { en: 'Skill Drafts', zh: '技能草稿' }, icon: 'lightbulb',
    menu: { order: 35, group: 'aurabot' }, permission: 'aurabot.run.read',
    file: './plugins/core-aurabot/pages/mission-control/learning-drafts.tsx',
  },
  {
    key: 'aurabot.interrupts', path: '/aurabot/interrupts',
    title: { en: 'Interrupts', zh: '中断审计' }, icon: 'alert-circle',
    menu: { order: 36, group: 'aurabot' }, permission: 'aurabot.run.read',
    file: './plugins/core-aurabot/pages/mission-control/interrupts.tsx',
  },
  {
    key: 'aurabot.memory-promotions', path: '/aurabot/memory-promotions',
    title: { en: 'Memory Promotions', zh: '记忆晋升' }, icon: 'arrow-up-right-circle',
    menu: { order: 37, group: 'aurabot' }, permission: 'aurabot.run.read',
    file: './plugins/core-aurabot/pages/mission-control/memory-promotions.tsx',
  },
  {
    key: 'aurabot.my-profile', path: '/aurabot/my-profile',
    title: { en: 'My Profile', zh: '我的画像' }, icon: 'user-circle',
    menu: { order: 38, group: 'aurabot' }, permission: 'aurabot.run.read',
    file: './plugins/core-aurabot/pages/mission-control/my-profile.tsx',
  },
  {
    key: 'aurabot.soul-profiles-admin', path: '/aurabot/soul-profiles',
    title: { en: 'Soul Profiles (Admin)', zh: 'Soul Profiles 管理' }, icon: 'users',
    menu: { order: 39, group: 'aurabot' }, permission: 'aurabot.dashboard.read',
    file: './plugins/core-aurabot/pages/mission-control/soul-profiles-admin.tsx',
  },
  {
    key: 'aurabot.admin.runs', path: '/admin/agent-runs',
    title: { en: 'Agent Runs', zh: 'Agent 运行记录' }, icon: 'list',
    menu: { order: 100, group: 'aurabot' }, permission: 'aurabot.run.read',
    file: './plugins/core-aurabot/pages/admin/agent-runs.tsx',
  },
  {
    key: 'aurabot.admin.shadow-runs', path: '/admin/agent-runs/shadow-runs',
    title: { en: 'Shadow Runs', zh: '影子运行比对' }, icon: 'git-compare',
    menu: { order: 110, group: 'aurabot' }, permission: 'aurabot.run.read',
    file: './plugins/core-aurabot/pages/admin/shadow-runs.tsx',
  },
]
