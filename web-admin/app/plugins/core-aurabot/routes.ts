import { route, type RouteConfigEntry } from '@react-router/dev/routes'

export function aurabotRoutes(): RouteConfigEntry[] {
  return [
    route('/aurabot/dashboard', './plugins/core-aurabot/pages/mission-control/index.tsx'),
    route('/aurabot/traces', './plugins/core-aurabot/pages/ai-trace/index.tsx'),
    route('/aurabot/traces/:traceId', './plugins/core-aurabot/pages/ai-trace/$traceId.tsx'),
    route('/aurabot/runs', './plugins/core-aurabot/pages/aurabot/runs.tsx'),
    route('/aurabot/providers', './plugins/core-aurabot/pages/aurabot/providers.tsx'),
    route('/aurabot/prompts', './plugins/core-aurabot/pages/aurabot/prompts.tsx'),
    route('/aurabot/knowledge', './plugins/core-aurabot/pages/aurabot/knowledge.tsx'),
    route('/aurabot/knowledge/:kbPid', './plugins/core-aurabot/pages/aurabot/knowledge.$kbPid.tsx'),
  ]
}
