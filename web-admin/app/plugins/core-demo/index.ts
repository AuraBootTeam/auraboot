import { definePlugin } from '@auraboot/plugin-sdk'
import { manifest } from './manifest.js'

/**
 * core-demo — the "Hello World" plugin.
 *
 * Purpose: prove the end-to-end contract between PluginLoader, PluginContext,
 * RouteRegistry, and a real React component. This plugin contributes one
 * navigation resource. When the App shell calls
 * `pluginLoader.activateAll()`, the route shows up in the menu and the
 * page is reachable.
 *
 * It exists primarily as a smoke test and as the canonical example for
 * future core-* plugin migrations (M3.x). Safe to remove once `core-system`,
 * `core-iam`, etc. all use this same definePlugin pattern.
 */
export default definePlugin({
  manifest,
  setup(ctx) {
    ctx.registerNavigationResource({
      key: 'core.demo.home',
      path: '/_demo',
      title: { en: 'Demo', zh: '示例' },
      icon: 'sparkles',
      // Lazy-load the page component to keep the plugin's import cheap.
      loader: () => import('./pages/DemoPage.js') as Promise<{ default: unknown }>,
      menu: { order: 999, group: 'developer' },
      source: 'plugin',
    })

    ctx.log.info('core-demo activated')
  },
})
