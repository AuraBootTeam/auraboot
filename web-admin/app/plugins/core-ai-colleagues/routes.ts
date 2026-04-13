import { route, type RouteConfigEntry } from '@react-router/dev/routes'

export function aiColleaguesRoutes(): RouteConfigEntry[] {
  return [
    route('/ai/settings', './routes/ai/settings.tsx'),
    route('/ai/colleagues', './routes/ai/colleagues.tsx'),
    route('/ai/colleagues/new', './routes/ai/colleagues.new.tsx'),
    route('/ai/colleagues/:agentPid/chat', './routes/ai/colleagues.$agentPid.chat.tsx'),
    route('/ai/colleagues/:agentPid', './routes/ai/colleagues.$agentPid.tsx'),
  ]
}
