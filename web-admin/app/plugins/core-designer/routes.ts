import { route, type RouteConfigEntry } from '@react-router/dev/routes'

export function designerRoutes(): RouteConfigEntry[] {
  return [
    route('/page-designer', './plugins/core-designer/pages/page-designer.tsx'),
    route('/page-designer/:id', './plugins/core-designer/pages/page-designer.$id.tsx'),
    route('/bpmn-designer', './plugins/core-designer/pages/bpmn-designer.tsx'),
    route('/flow-designer', './plugins/core-designer/pages/flow-designer.tsx'),
    route('/query-builder', './plugins/core-designer/pages/query-builder.tsx'),
  ]
}
