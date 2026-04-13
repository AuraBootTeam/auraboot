import type { PluginResource } from '../_shared/types.js'

export const RESOURCES: PluginResource[] = [
  {
    key: 'designer.page', path: '/page-designer',
    title: { en: 'Page Designer', zh: '页面设计器' }, icon: 'layout-dashboard',
    menu: { order: 10, group: 'designer' }, permission: 'designer.page.use',
    file: './plugins/core-designer/pages/page-designer.tsx',
  },
  {
    key: 'designer.page.detail', path: '/page-designer/:id',
    title: { en: 'Page Designer', zh: '页面设计器' },
    menu: false, parentKey: 'designer.page', permission: 'designer.page.use',
    file: './plugins/core-designer/pages/page-designer.$id.tsx',
  },
  {
    key: 'designer.bpmn', path: '/bpmn-designer',
    title: { en: 'BPMN Designer', zh: 'BPMN 设计器' }, icon: 'workflow',
    menu: { order: 20, group: 'designer' }, permission: 'designer.bpmn.use',
    file: './plugins/core-designer/pages/bpmn-designer.tsx',
  },
  {
    key: 'designer.flow', path: '/flow-designer',
    title: { en: 'Flow Designer', zh: '流程设计器' }, icon: 'git-branch',
    menu: { order: 30, group: 'designer' }, permission: 'designer.flow.use',
    file: './plugins/core-designer/pages/flow-designer.tsx',
  },
  {
    key: 'designer.query', path: '/query-builder',
    title: { en: 'Query Builder', zh: '查询构建器' }, icon: 'search',
    menu: { order: 40, group: 'designer' }, permission: 'query.builder.use',
    file: './plugins/core-designer/pages/query-builder.tsx',
  },
]
