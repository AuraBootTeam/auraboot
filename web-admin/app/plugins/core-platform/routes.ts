import { route, type RouteConfigEntry } from '@react-router/dev/routes'

export function platformRoutes(): RouteConfigEntry[] {
  return [
    route('/system/plugins', './plugins/core-platform/pages/system/plugins/index.tsx'),
    // Kernel Plugin Manager — visualizes the in-process PluginLoader
    // (M4.3). Distinct from /system/plugins (backend PF4J management).
    route('/system/kernel-plugins', './plugins/core-platform/pages/KernelPluginsPage.tsx'),
  ]
}
