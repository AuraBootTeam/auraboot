import { definePlugin } from '@auraboot/plugin-sdk'
import { manifest } from './manifest.js'

export default definePlugin({
  manifest,
  setup(ctx) {
    ctx.registerNavigationResources([
      {
        key: 'meta.models',
        path: '/meta/models',
        title: { en: 'Models', zh: '模型' },
        icon: 'database',
        menu: { order: 10, group: 'meta' },
        permission: 'meta.model.read',
        source: 'plugin',
      },
      {
        key: 'meta.fields',
        path: '/meta/fields',
        title: { en: 'Fields', zh: '字段' },
        icon: 'columns',
        menu: { order: 20, group: 'meta' },
        permission: 'meta.field.read',
        source: 'plugin',
      },
      {
        key: 'meta.dict',
        path: '/meta/dict',
        title: { en: 'Dictionaries', zh: '字典' },
        icon: 'book',
        menu: { order: 30, group: 'meta' },
        permission: 'meta.dict.read',
        source: 'plugin',
      },
      {
        key: 'meta.named-queries',
        path: '/meta/named-queries',
        title: { en: 'Named Queries', zh: '命名查询' },
        icon: 'search',
        menu: { order: 40, group: 'meta' },
        permission: 'meta.named-query.read',
        source: 'plugin',
      },
      {
        key: 'meta.consistency-rules',
        path: '/meta/consistency-rules',
        title: { en: 'Consistency Rules', zh: '一致性规则' },
        icon: 'shield-check',
        menu: { order: 50, group: 'meta' },
        permission: 'meta.rules.read',
        source: 'plugin',
      },
      {
        key: 'meta.ai-modeling',
        path: '/meta/ai-modeling',
        title: { en: 'AI Modeling', zh: 'AI 建模' },
        icon: 'sparkles',
        menu: { order: 60, group: 'meta' },
        permission: 'meta.ai-modeling.use',
        source: 'plugin',
      },
    ])
  },
})
