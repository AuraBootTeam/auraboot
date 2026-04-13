import { route, type RouteConfigEntry } from '@react-router/dev/routes'

export function aurabotRoutes(): RouteConfigEntry[] {
  return [
    route('/aurabot/dashboard', './routes/mission-control/index.tsx'),
    route('/aurabot/traces', './routes/ai-trace/index.tsx'),
    route('/aurabot/traces/:traceId', './routes/ai-trace/$traceId.tsx'),
    route('/aurabot/runs', './routes/aurabot/runs.tsx'),
    route('/aurabot/providers', './routes/aurabot/providers.tsx'),
    route('/aurabot/prompts', './routes/aurabot/prompts.tsx'),
    route('/aurabot/knowledge', './routes/aurabot/knowledge.tsx'),
    route('/aurabot/knowledge/:kbPid', './routes/aurabot/knowledge.$kbPid.tsx'),
  ]
}
