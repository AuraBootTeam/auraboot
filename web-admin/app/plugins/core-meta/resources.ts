import type { PluginResource } from '../_shared/types.js'

export const RESOURCES: PluginResource[] = [
  // Models
  { key: 'meta.models', path: '/meta/models', title: { en: 'Models', zh: '模型' }, icon: 'database', menu: { order: 10, group: 'meta' }, permission: 'meta.model.read', file: './plugins/core-meta/pages/meta/models/index.tsx' },
  { key: 'meta.models.new', path: '/meta/models/new', title: { en: 'New Model', zh: '新建模型' }, menu: false, parentKey: 'meta.models', permission: 'meta.model.edit', file: './plugins/core-meta/pages/meta/models/new.tsx' },
  { key: 'meta.models.new.virtual', path: '/meta/models/new/virtual', title: { en: 'New Virtual Model', zh: '新建虚拟模型' }, menu: false, parentKey: 'meta.models', permission: 'meta.model.edit', file: './plugins/core-meta/pages/meta/models/new/virtual.tsx' },
  { key: 'meta.models.detail', path: '/meta/models/:pid', title: { en: 'Model', zh: '模型详情' }, menu: false, parentKey: 'meta.models', permission: 'meta.model.read', file: './plugins/core-meta/pages/meta/models/$pid.tsx' },
  { key: 'meta.models.edit', path: '/meta/models/:pid/edit', title: { en: 'Edit Model', zh: '编辑模型' }, menu: false, parentKey: 'meta.models', permission: 'meta.model.edit', file: './plugins/core-meta/pages/meta/models/$pid.edit.tsx' },
  // Fields
  { key: 'meta.fields', path: '/meta/fields', title: { en: 'Fields', zh: '字段' }, icon: 'columns', menu: { order: 20, group: 'meta' }, permission: 'meta.field.read', file: './plugins/core-meta/pages/meta/fields/index.tsx' },
  { key: 'meta.fields.new', path: '/meta/fields/new', title: { en: 'New Field', zh: '新建字段' }, menu: false, parentKey: 'meta.fields', permission: 'meta.field.read', file: './plugins/core-meta/pages/meta/fields/new.tsx' },
  { key: 'meta.fields.detail', path: '/meta/fields/:pid', title: { en: 'Field', zh: '字段详情' }, menu: false, parentKey: 'meta.fields', permission: 'meta.field.read', file: './plugins/core-meta/pages/meta/fields/$pid.tsx' },
  { key: 'meta.fields.usage', path: '/meta/fields/:pid/usage', title: { en: 'Field Usage', zh: '字段使用情况' }, menu: false, parentKey: 'meta.fields', permission: 'meta.field.read', file: './plugins/core-meta/pages/meta/fields/$pid.usage.tsx' },
  { key: 'meta.fields.impact', path: '/meta/fields/:pid/impact', title: { en: 'Field Impact', zh: '字段影响分析' }, menu: false, parentKey: 'meta.fields', permission: 'meta.field.read', file: './plugins/core-meta/pages/meta/fields/$pid.impact.tsx' },
  // Dict
  { key: 'meta.dict', path: '/meta/dict', title: { en: 'Dictionaries', zh: '字典' }, icon: 'book', menu: { order: 30, group: 'meta' }, permission: 'meta.dict.read', file: './plugins/core-meta/pages/meta/dict/index.tsx' },
  { key: 'meta.dict.new', path: '/meta/dict/new', title: { en: 'New Dictionary', zh: '新建字典' }, menu: false, parentKey: 'meta.dict', permission: 'meta.dict.read', file: './plugins/core-meta/pages/meta/dict/new.tsx' },
  { key: 'meta.dict.detail', path: '/meta/dict/:pid', title: { en: 'Dictionary', zh: '字典详情' }, menu: false, parentKey: 'meta.dict', permission: 'meta.dict.read', file: './plugins/core-meta/pages/meta/dict/$pid.tsx' },
  { key: 'meta.dict.edit', path: '/meta/dict/:pid/edit', title: { en: 'Edit Dictionary', zh: '编辑字典' }, menu: false, parentKey: 'meta.dict', permission: 'meta.dict.read', file: './plugins/core-meta/pages/meta/dict/$pid.edit.tsx' },
  // Named Queries
  { key: 'meta.named-queries', path: '/meta/named-queries', title: { en: 'Named Queries', zh: '命名查询' }, icon: 'search', menu: { order: 40, group: 'meta' }, permission: 'meta.named-query.read', file: './plugins/core-meta/pages/meta/named-queries/index.tsx' },
  { key: 'meta.named-queries.new', path: '/meta/named-queries/new', title: { en: 'New Named Query', zh: '新建命名查询' }, menu: false, parentKey: 'meta.named-queries', permission: 'meta.named-query.read', file: './plugins/core-meta/pages/meta/named-queries/new.tsx' },
  { key: 'meta.named-queries.detail', path: '/meta/named-queries/:pid', title: { en: 'Named Query', zh: '命名查询详情' }, menu: false, parentKey: 'meta.named-queries', permission: 'meta.named-query.read', file: './plugins/core-meta/pages/meta/named-queries/$pid.tsx' },
  // Misc
  { key: 'meta.ai-modeling', path: '/meta/ai-modeling', title: { en: 'AI Modeling', zh: 'AI 建模' }, icon: 'sparkles', menu: false, permission: 'meta.ai-modeling.use', file: './plugins/core-meta/pages/meta/ai-modeling/index.tsx' },
]
