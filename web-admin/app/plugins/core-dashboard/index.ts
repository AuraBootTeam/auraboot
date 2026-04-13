import { definePlugin } from '@auraboot/plugin-sdk'
import { toNavigationResources } from '../_shared/types.js'
import { manifest } from './manifest.js'
import { RESOURCES } from './resources.js'

/**
 * core-dashboard plugin — dashboard viewer.
 *
 * The legacy module barrel lives in ./module.ts (exports DashboardViewer,
 * dashboardService, useDashboardStore, widgetRegistry). It's still consumed
 * by enterprise overlay (ent-dashboard-designer adds the authoring UX).
 */
export default definePlugin({
  manifest,
  setup(ctx) {
    ctx.registerNavigationResources(toNavigationResources(RESOURCES))
  },
})
