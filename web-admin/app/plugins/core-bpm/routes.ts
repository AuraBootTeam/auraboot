import { route, type RouteConfigEntry } from '@react-router/dev/routes'

/**
 * Static React Router 7 route declarations for core-bpm.
 *
 * Imported by `packages/core/route-manifest.ts` so React Router typegen
 * picks them up. The same paths are also registered via PluginContext
 * in ./index.ts for menu/breadcrumb/permission gating.
 */
export function bpmRoutes(): RouteConfigEntry[] {
  return [
    route('/bpm/task-center', './plugins/core-bpm/pages/TaskCenter.tsx'),
    route('/bpm/approval-inbox', './plugins/core-bpm/pages/ApprovalInbox.tsx'),
    route('/bpm/process-status', './plugins/core-bpm/pages/ProcessStatus.tsx'),
    route('/bpm/sla-monitor', './plugins/core-bpm/pages/SlaMonitor.tsx'),
  ]
}
