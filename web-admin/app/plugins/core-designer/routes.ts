import { route, type RouteConfigEntry } from '@react-router/dev/routes'

export function designerRoutes(): RouteConfigEntry[] {
  return [
    route('/page-designer', './routes/page-designer.tsx'),
    route('/page-designer/:id', './routes/page-designer.$id.tsx'),
    route('/bpmn-designer', './routes/bpmn-designer.tsx'),
    route('/flow-designer', './routes/flow-designer.tsx'),
    route('/query-builder', './routes/query-builder.tsx'),
  ]
}
