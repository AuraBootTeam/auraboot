import { route, type RouteConfigEntry } from '@react-router/dev/routes'

export function aiColleaguesRoutes(): RouteConfigEntry[] {
  return [
    route('/ai/settings', './plugins/core-ai-colleagues/pages/ai/settings.tsx'),
    route('/ai/colleagues', './plugins/core-ai-colleagues/pages/ai/colleagues.tsx'),
    route('/ai/colleagues/new', './plugins/core-ai-colleagues/pages/ai/colleagues.new.tsx'),
    route('/ai/colleagues/:agentPid/chat', './plugins/core-ai-colleagues/pages/ai/colleagues.$agentPid.chat.tsx'),
    route('/ai/colleagues/:agentPid', './plugins/core-ai-colleagues/pages/ai/colleagues.$agentPid.tsx'),
  ]
}
