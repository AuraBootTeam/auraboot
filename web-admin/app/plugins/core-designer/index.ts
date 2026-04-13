import { definePlugin } from '@auraboot/plugin-sdk'
import { manifest } from './manifest.js'

export default definePlugin({
  manifest,
  setup(ctx) {
    ctx.registerNavigationResources([
      {
        key: 'designer.page',
        path: '/page-designer',
        title: { en: 'Page Designer', zh: '页面设计器' },
        icon: 'layout-dashboard',
        menu: { order: 10, group: 'designer' },
        permission: 'designer.page.use',
        source: 'plugin',
      },
      {
        key: 'designer.bpmn',
        path: '/bpmn-designer',
        title: { en: 'BPMN Designer', zh: 'BPMN 设计器' },
        icon: 'workflow',
        menu: { order: 20, group: 'designer' },
        permission: 'designer.bpmn.use',
        source: 'plugin',
      },
      {
        key: 'designer.flow',
        path: '/flow-designer',
        title: { en: 'Flow Designer', zh: '流程设计器' },
        icon: 'git-branch',
        menu: { order: 30, group: 'designer' },
        permission: 'designer.flow.use',
        source: 'plugin',
      },
      {
        key: 'designer.query',
        path: '/query-builder',
        title: { en: 'Query Builder', zh: '查询构建器' },
        icon: 'search',
        menu: { order: 40, group: 'designer' },
        permission: 'query.builder.use',
        source: 'plugin',
      },
    ])
  },
})
